package com.govind.personalvault.media;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.util.Log;

import com.govind.personalvault.data.VaultDb;
import com.govind.personalvault.model.MediaItemRecord;
import com.govind.personalvault.security.VaultSession;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serialized media I/O coordinator with crash reconciliation, storage preflight, runtime low-space
 * protection, and throttled main-thread progress delivery.
 */
public final class MediaRepository {
    private static final String TAG = "VaultMedia";

    private static final long KIB = 1024L;
    private static final long MIB = 1024L * KIB;
    private static final long GIB = 1024L * MIB;

    private static final long PROGRESS_INTERVAL_MS = 225L;
    private static final long MIN_FREE_RESERVE_BYTES = 128L * MIB;
    private static final long UNKNOWN_SIZE_MIN_FREE_BYTES = 512L * MIB;
    private static final long METADATA_AND_THUMBNAIL_RESERVE_BYTES = 2L * MIB;
    private static final long RUNTIME_SPACE_CHECK_INTERVAL_BYTES = 64L * MIB;

    private static final String PART_SUFFIX = MediaFileFormat.EXTENSION + ".part";
    private static final String TRASH_SUFFIX = MediaFileFormat.EXTENSION + ".trash";
    private static final String FINAL_SUFFIX = MediaFileFormat.EXTENSION;

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "vault-media");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                thread.setUncaughtExceptionHandler(
                        (failed, error) -> Log.e(TAG, "Media worker failed", error));
                return thread;
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback<T> {
        void onComplete(T result, Exception error);
    }

    public interface ImportProgress {
        void onProgress(int currentFile, int totalFiles, String name, long encryptedBytes);
    }

    private interface Operation<T> {
        T run(Task task) throws Exception;
    }

    private enum ImportKind { MEDIA, DOCUMENT }

    public static final class ImportSummary {
        public final int imported;
        public final int failed;
        public final List<String> errors;

        public ImportSummary(int imported, int failed, List<String> errors) {
            this.imported = imported;
            this.failed = failed;
            this.errors = errors;
        }
    }

    public static final class Task {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Future<?> future;

        private synchronized void attach(Future<?> value) {
            future = value;
            if (cancelled.get()) {
                value.cancel(true);
            }
        }

        public synchronized void cancel() {
            cancelled.set(true);
            if (future != null) {
                future.cancel(true);
            }
        }

        boolean isCancelled() {
            return cancelled.get();
        }
    }

    private MediaRepository() { }

    public static Task importAsync(
            Context context,
            List<Uri> selected,
            ImportProgress progress,
            Callback<ImportSummary> callback) {
        return importAsync(context, selected, ImportKind.MEDIA, progress, callback);
    }

    public static Task importDocumentsAsync(
            Context context,
            List<Uri> selected,
            ImportProgress progress,
            Callback<ImportSummary> callback) {
        return importAsync(context, selected, ImportKind.DOCUMENT, progress, callback);
    }

    private static Task importAsync(
            Context context,
            List<Uri> selected,
            ImportKind kind,
            ImportProgress progress,
            Callback<ImportSummary> callback) {

        Context app = context.getApplicationContext();
        ArrayList<Uri> uris =
                new ArrayList<>(selected == null ? new ArrayList<>() : selected);

        return submit(
                task -> importBlocking(app, uris, kind, progress, task),
                callback);
    }

    public static Task exportAsync(
            Context context,
            String id,
            Callback<Uri> callback) {

        Context app = context.getApplicationContext();
        return submit(task -> {
            throwIfCancelled(task);

            MediaItemRecord item = VaultDb.get(app).getMediaBlocking(id);
            if (item == null) {
                throw new IOException("Encrypted file no longer exists");
            }

            try {
                throwIfCancelled(task);
                return MediaExporter.export(app, item);
            } finally {
                item.clearSensitive();
            }
        }, callback);
    }

    public static Task deleteAsync(
            Context context,
            String id,
            Callback<Boolean> callback) {

        Context app = context.getApplicationContext();
        return submit(task -> {
            throwIfCancelled(task);
            return deleteBlocking(app, id);
        }, callback);
    }

    public static Task cleanupAsync(
            Context context,
            Callback<Integer> callback) {

        Context app = context.getApplicationContext();
        return submit(task -> {
            throwIfCancelled(task);
            return reconcileBlocking(app);
        }, callback);
    }

    private static ImportSummary importBlocking(
            Context context,
            List<Uri> uris,
            ImportKind kind,
            ImportProgress progress,
            Task task) throws Exception {

        long sessionEpoch = VaultSession.requireEpoch();

        // The same single-thread executor serializes cleanup and import, so no live .part file is
        // ever mistaken for a stale file in this process.
        reconcileBlocking(context);
        requireImportActive(task, sessionEpoch);

        ProgressDispatcher progressDispatcher =
                new ProgressDispatcher(task, progress);

        int imported = 0;
        ArrayList<String> errors = new ArrayList<>();
        int total = uris.size();

        for (int index = 0; index < total; index++) {
            requireImportActive(task, sessionEpoch);

            Uri uri = uris.get(index);
            MediaMetadataResolver.Metadata metadata = null;
            MediaItemRecord record = null;
            byte[] detachedThumbnail = null;
            UUID id = UUID.randomUUID();
            File encrypted = null;

            int currentFile = index + 1;

            try {
                metadata = kind == ImportKind.DOCUMENT
                        ? MediaMetadataResolver.resolveDocument(context, uri)
                        : MediaMetadataResolver.resolveMedia(context, uri);
                ensureSpace(context, uri, metadata.declaredSize);
                requireImportActive(task, sessionEpoch);

                String displayName = metadata.displayName;
                progressDispatcher.update(
                        currentFile,
                        total,
                        displayName,
                        0L,
                        true);

                detachedThumbnail =
                        MediaThumbnailer.create(context, uri, metadata.mimeType);
                requireImportActive(task, sessionEpoch);

                RuntimeSpaceGuard runtimeSpace = new RuntimeSpaceGuard(context);

                MediaCryptoWriter.Result result = MediaCryptoWriter.encrypt(
                        context,
                        uri,
                        id,
                        bytes -> {
                            requireImportActiveUnchecked(task, sessionEpoch);
                            runtimeSpace.onBytesWritten(bytes);
                            progressDispatcher.update(
                                    currentFile,
                                    total,
                                    displayName,
                                    bytes,
                                    false);
                        });

                encrypted = result.encryptedFile;
                requireImportActive(task, sessionEpoch);
                runtimeSpace.forceCheck();

                progressDispatcher.update(
                        currentFile,
                        total,
                        displayName,
                        result.clearLength,
                        true);

                long now = System.currentTimeMillis();
                record = new MediaItemRecord();
                record.id = id.toString();
                record.originalName = metadata.displayName;
                record.mimeType = metadata.mimeType;
                record.size = result.clearLength;
                record.thumbnail = detachedThumbnail;
                detachedThumbnail = null;
                record.createdAt = now;
                record.updatedAt = now;

                VaultDb.get(context).saveMediaBlocking(record);
                imported++;
            } catch (ImportCancelledRuntimeException cancelled) {
                deleteImportArtifacts(context, id, encrypted);
                throw new IOException(kind == ImportKind.DOCUMENT
                        ? "Document import was cancelled"
                        : "Media import was cancelled", cancelled);
            } catch (VaultSessionChangedRuntimeException locked) {
                deleteImportArtifacts(context, id, encrypted);
                throw new GeneralSecurityException(
                        kind == ImportKind.DOCUMENT
                                ? "Vault locked during document import"
                                : "Vault locked during media import",
                        locked);
            } catch (LowStorageRuntimeException lowStorage) {
                deleteImportArtifacts(context, id, encrypted);
                String name =
                        metadata == null
                                ? (kind == ImportKind.DOCUMENT ? "Selected document" : "Selected media")
                                : metadata.displayName;
                errors.add(name + ": " + safeMessage(lowStorage));
                break;
            } catch (Exception failure) {
                deleteImportArtifacts(context, id, encrypted);

                if (task.isCancelled() || Thread.currentThread().isInterrupted()) {
                    throw new IOException(kind == ImportKind.DOCUMENT
                            ? "Document import was cancelled"
                            : "Media import was cancelled", failure);
                }

                if (!VaultSession.isValidEpoch(sessionEpoch)) {
                    throw new GeneralSecurityException(
                            kind == ImportKind.DOCUMENT
                                ? "Vault locked during document import"
                                : "Vault locked during media import",
                            failure);
                }

                String name =
                        metadata == null
                                ? (kind == ImportKind.DOCUMENT ? "Selected document" : "Selected media")
                                : metadata.displayName;
                errors.add(name + ": " + safeMessage(failure));
            } finally {
                if (record != null) {
                    record.clearSensitive();
                }
                if (detachedThumbnail != null) {
                    Arrays.fill(detachedThumbnail, (byte) 0);
                }
            }
        }

        return new ImportSummary(imported, errors.size(), errors);
    }

    /**
     * Reconciles internal encrypted files with SQLite metadata.
     *
     * <p>Rules:
     * <ul>
     *   <li>Every .part is deleted immediately because imports are not resumable after process death.</li>
     *   <li>A .trash with a surviving DB row is restored if no final file exists.</li>
     *   <li>A .trash without a DB row is permanently deleted.</li>
     *   <li>A final .gvm without a DB row is deleted as an orphan.</li>
     *   <li>A DB row without either a final or recoverable trash file is deleted.</li>
     * </ul>
     */
    private static int reconcileBlocking(Context context) throws Exception {
        File directory = MediaFileFormat.directory(context);
        Set<String> databaseIds = listMediaIdsBlocking(context);
        Set<String> availableFinalIds = new HashSet<>();
        int repaired = 0;

        File[] files = directory.listFiles();
        if (files == null) {
            files = new File[0];
        }

        // Temporary files can never be resumed safely after a process restart.
        for (File file : files) {
            if (file.getName().endsWith(PART_SUFFIX)) {
                deleteStrict(file, "Temporary encrypted media could not be cleaned up");
                repaired++;
            }
        }

        // Resolve interrupted delete operations before evaluating final files.
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(TRASH_SUFFIX)) {
                continue;
            }

            UUID id = parseManagedId(name, TRASH_SUFFIX);
            if (id == null) {
                deleteStrict(file, "Invalid encrypted-media trash file could not be removed");
                repaired++;
                continue;
            }

            String idText = id.toString();
            File finalFile = MediaFileFormat.finalFile(context, id);

            if (!databaseIds.contains(idText)) {
                deleteStrict(file, "Deleted encrypted media could not be removed");
                repaired++;
                continue;
            }

            if (finalFile.exists()) {
                // A valid final file and DB row already exist; the trash copy is stale.
                deleteStrict(file, "Stale encrypted-media trash could not be removed");
            } else {
                moveFile(file, finalFile);
            }

            availableFinalIds.add(idText);
            repaired++;
        }

        files = directory.listFiles();
        if (files == null) {
            files = new File[0];
        }

        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(FINAL_SUFFIX)
                    || name.endsWith(PART_SUFFIX)
                    || name.endsWith(TRASH_SUFFIX)) {
                continue;
            }

            UUID id = parseManagedId(name, FINAL_SUFFIX);
            if (id == null) {
                deleteStrict(file, "Invalid encrypted media file could not be removed");
                repaired++;
                continue;
            }

            String idText = id.toString();
            if (databaseIds.contains(idText)) {
                availableFinalIds.add(idText);
            } else {
                deleteStrict(file, "Orphan encrypted media could not be removed");
                repaired++;
            }
        }

        for (String databaseId : databaseIds) {
            if (!availableFinalIds.contains(databaseId)) {
                VaultDb.get(context).deleteMediaMetadataBlocking(databaseId);
                repaired++;
            }
        }

        return repaired;
    }

    private static boolean deleteBlocking(Context context, String id) throws Exception {
        UUID mediaId;
        try {
            mediaId = UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid media identifier", invalid);
        }

        // Resolve any earlier interrupted operation before starting a new delete transaction.
        reconcileBlocking(context);

        File original = MediaFileFormat.finalFile(context, mediaId);
        File trash = MediaFileFormat.trashFile(context, mediaId);

        boolean fileExisted = original.exists() || trash.exists();

        if (trash.exists()) {
            deleteStrict(trash, "Old encrypted-media trash could not be removed");
        }

        if (original.exists()) {
            moveFile(original, trash);
        }

        boolean metadataDeleted;
        try {
            metadataDeleted = VaultDb.get(context).deleteMediaMetadataBlocking(id);
        } catch (Exception failure) {
            if (trash.exists() && !original.exists()) {
                try {
                    moveFile(trash, original);
                } catch (IOException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            throw failure;
        }

        if (trash.exists() && !trash.delete()) {
            // The DB row is already gone. Startup reconciliation will retry permanent deletion.
            Log.w(TAG, "Encrypted-media trash will be retried during reconciliation");
        }

        return metadataDeleted || fileExisted;
    }

    private static void ensureSpace(
            Context context,
            Uri uri,
            long providerDeclaredSize) throws IOException {

        long clearSize = resolveSourceSize(context, uri, providerDeclaredSize);
        File filesDir = context.getFilesDir();

        StatFs stats = new StatFs(filesDir.getAbsolutePath());
        long availableNow = stats.getAvailableBytes();
        long allocatable = resolveAllocatableBytes(context, filesDir, availableNow);
        long bestAvailable = Math.max(availableNow, allocatable);

        if (clearSize <= 0L) {
            if (bestAvailable < UNKNOWN_SIZE_MIN_FREE_BYTES) {
                throw new IOException(
                        "The selected file size is unknown and internal storage is too low");
            }
            return;
        }

        long required = requiredInternalBytes(clearSize);
        if (bestAvailable < required) {
            long missing = required - bestAvailable;
            throw new IOException(
                    "Not enough internal storage. Free at least "
                            + bytesToWholeMegabytes(missing)
                            + " MB and try again");
        }
    }

    private static long resolveSourceSize(
            Context context,
            Uri uri,
            long providerDeclaredSize) {

        long resolved = providerDeclaredSize > 0L ? providerDeclaredSize : -1L;

        try (ParcelFileDescriptor descriptor =
                     context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (descriptor != null && descriptor.getStatSize() > 0L) {
                resolved = Math.max(resolved, descriptor.getStatSize());
            }
        } catch (IOException | SecurityException ignored) {
            // Continue to the AssetFileDescriptor fallback.
        }

        try (AssetFileDescriptor descriptor =
                     context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (descriptor != null && descriptor.getLength() > 0L) {
                resolved = Math.max(resolved, descriptor.getLength());
            }
        } catch (IOException | SecurityException ignored) {
            // Unknown-length providers are handled by runtime free-space checks.
        }

        return resolved;
    }

    private static long requiredInternalBytes(long clearSize) throws IOException {
        long encryptedSize =
                MediaFileFormat.expectedFileLength(
                        clearSize,
                        MediaFileFormat.CHUNK_SIZE);

        long reserve = Math.max(
                MIN_FREE_RESERVE_BYTES,
                Math.min(GIB, clearSize / 20L));

        try {
            return Math.addExact(
                    encryptedSize,
                    Math.addExact(
                            METADATA_AND_THUMBNAIL_RESERVE_BYTES,
                            reserve));
        } catch (ArithmeticException overflow) {
            throw new IOException("Selected media is too large", overflow);
        }
    }

    private static long resolveAllocatableBytes(
            Context context,
            File filesDir,
            long fallback) {

        StorageManager storageManager =
                context.getSystemService(StorageManager.class);
        if (storageManager == null) {
            return fallback;
        }

        try {
            java.util.UUID storageUuid =
                    storageManager.getUuidForPath(filesDir);
            return storageManager.getAllocatableBytes(storageUuid);
        } catch (IOException | SecurityException failure) {
            return fallback;
        }
    }

    private static void assertRuntimeReserve(Context context) {
        StatFs stats = new StatFs(context.getFilesDir().getAbsolutePath());
        if (stats.getAvailableBytes() < MIN_FREE_RESERVE_BYTES) {
            throw new LowStorageRuntimeException(
                    "Internal storage became too low during import");
        }
    }

    private static Set<String> listMediaIdsBlocking(Context context) {
        return VaultDb.get(context).listMediaIdsBlocking();
    }

    private static UUID parseManagedId(String fileName, String suffix) {
        if (fileName == null
                || suffix == null
                || !fileName.endsWith(suffix)) {
            return null;
        }

        String raw = fileName.substring(0, fileName.length() - suffix.length());
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static void deleteImportArtifacts(
            Context context,
            UUID id,
            File encrypted) {

        deleteQuietly(encrypted);

        try {
            deleteQuietly(MediaFileFormat.partFile(context, id));
        } catch (IOException failure) {
            Log.w(TAG, "Temporary media cleanup path could not be opened", failure);
        }

        try {
            File finalFile = MediaFileFormat.finalFile(context, id);
            if (encrypted == null || !finalFile.equals(encrypted)) {
                deleteQuietly(finalFile);
            }
        } catch (IOException failure) {
            Log.w(TAG, "Encrypted media cleanup path could not be opened", failure);
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Media cleanup will be retried during reconciliation: " + file.getName());
        }
    }

    private static void deleteStrict(File file, String message) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException(message);
        }
    }

    private static void moveFile(File source, File target) throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source.toPath(), target.toPath());
        }
    }

    private static void throwIfCancelled(Task task) throws IOException {
        if (task.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new IOException("Media operation was cancelled");
        }
    }

    private static void requireImportActive(
            Task task,
            long sessionEpoch) throws IOException, GeneralSecurityException {

        throwIfCancelled(task);
        if (!VaultSession.isValidEpoch(sessionEpoch)) {
            throw new GeneralSecurityException("Vault locked during media import");
        }
    }

    private static void requireImportActiveUnchecked(
            Task task,
            long sessionEpoch) {

        if (task.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new ImportCancelledRuntimeException();
        }
        if (!VaultSession.isValidEpoch(sessionEpoch)) {
            throw new VaultSessionChangedRuntimeException();
        }
    }

    private static long bytesToWholeMegabytes(long bytes) {
        if (bytes <= 0L) {
            return 0L;
        }
        return 1L + ((bytes - 1L) / MIB);
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.trim().isEmpty()
                ? "Import failed"
                : value;
    }

    private static <T> Task submit(
            Operation<T> operation,
            Callback<T> callback) {

        Task task = new Task();
        task.attach(EXECUTOR.submit(() -> {
            if (task.isCancelled()) {
                return;
            }

            T result = null;
            Exception error = null;

            try {
                result = operation.run(task);
            } catch (Exception failure) {
                error = failure;
            }

            if (task.isCancelled()) {
                return;
            }

            T delivered = result;
            Exception failure = error;

            MAIN.post(() -> {
                if (task.isCancelled()) {
                    return;
                }

                try {
                    if (callback != null) {
                        callback.onComplete(delivered, failure);
                    }
                } catch (RuntimeException callbackError) {
                    Log.e(TAG, "Media callback failed", callbackError);
                }
            });
        }));

        return task;
    }

    private static final class RuntimeSpaceGuard {
        private final Context appContext;
        private long nextCheckAt = RUNTIME_SPACE_CHECK_INTERVAL_BYTES;

        RuntimeSpaceGuard(Context context) {
            appContext = context.getApplicationContext();
        }

        void onBytesWritten(long bytesWritten) {
            if (bytesWritten < nextCheckAt) {
                return;
            }

            assertRuntimeReserve(appContext);

            try {
                nextCheckAt = Math.addExact(
                        bytesWritten,
                        RUNTIME_SPACE_CHECK_INTERVAL_BYTES);
            } catch (ArithmeticException overflow) {
                nextCheckAt = Long.MAX_VALUE;
            }
        }

        void forceCheck() {
            assertRuntimeReserve(appContext);
        }
    }

    private static final class ProgressDispatcher {
        private final Task task;
        private final ImportProgress callback;
        private final Runnable delivery = this::deliver;

        private boolean scheduled;
        private long lastDeliveryUptime;
        private int currentFile;
        private int totalFiles;
        private String name = "";
        private long bytes;

        ProgressDispatcher(Task task, ImportProgress callback) {
            this.task = task;
            this.callback = callback;
        }

        void update(
                int current,
                int total,
                String displayName,
                long latestBytes,
                boolean force) {

            if (callback == null || task.isCancelled()) {
                return;
            }

            long delay;
            synchronized (this) {
                currentFile = current;
                totalFiles = total;
                name = displayName == null ? "Selected media" : displayName;
                bytes = latestBytes;

                if (force && scheduled) {
                    MAIN.removeCallbacks(delivery);
                    scheduled = false;
                }

                if (scheduled) {
                    return;
                }

                long now = SystemClock.uptimeMillis();
                delay = force
                        ? 0L
                        : Math.max(
                                0L,
                                PROGRESS_INTERVAL_MS - (now - lastDeliveryUptime));
                scheduled = true;
            }

            if (delay == 0L) {
                MAIN.post(delivery);
            } else {
                MAIN.postDelayed(delivery, delay);
            }
        }

        private void deliver() {
            int deliveredCurrent;
            int deliveredTotal;
            String deliveredName;
            long deliveredBytes;

            synchronized (this) {
                scheduled = false;
                lastDeliveryUptime = SystemClock.uptimeMillis();
                deliveredCurrent = currentFile;
                deliveredTotal = totalFiles;
                deliveredName = name;
                deliveredBytes = bytes;
            }

            if (task.isCancelled()) {
                return;
            }

            try {
                callback.onProgress(
                        deliveredCurrent,
                        deliveredTotal,
                        deliveredName,
                        deliveredBytes);
            } catch (RuntimeException callbackError) {
                Log.e(TAG, "Media progress callback failed", callbackError);
            }
        }
    }

    private static final class ImportCancelledRuntimeException extends RuntimeException {
        ImportCancelledRuntimeException() {
            super("Media import was cancelled");
        }
    }

    private static final class VaultSessionChangedRuntimeException extends RuntimeException {
        VaultSessionChangedRuntimeException() {
            super("Vault locked during media import");
        }
    }

    private static final class LowStorageRuntimeException extends RuntimeException {
        LowStorageRuntimeException(String message) {
            super(message);
        }
    }
}
