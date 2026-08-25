package com.govind.personalvault.media;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import com.govind.personalvault.data.VaultDb;
import com.govind.personalvault.security.VaultSession;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Offline encrypted vault archive. Ciphertext only — useless without the PIN. */
public final class VaultBackup {
    private VaultBackup() { }

    public static Uri export(Context context) throws Exception {
        if (!VaultSession.isUnlocked()) throw new GeneralSecurityException("Vault is locked");
        VaultDb.get(context).walCheckpoint();

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "govind-vault-backup.gpv");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Govind Personal Vault");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) throw new IOException("Backup destination could not be created");
        boolean complete = false;
        try (OutputStream output = resolver.openOutputStream(uri, "w");
             ZipOutputStream zip = new ZipOutputStream(output)) {
            if (output == null) throw new IOException("Backup destination could not be opened");
            putFile(zip, "vault.db", context.getDatabasePath("govind_personal_vault.db"));
            File wal = new File(context.getDatabasePath("govind_personal_vault.db").getAbsolutePath() + "-wal");
            File shm = new File(context.getDatabasePath("govind_personal_vault.db").getAbsolutePath() + "-shm");
            if (wal.isFile()) putFile(zip, "vault.db-wal", wal);
            if (shm.isFile()) putFile(zip, "vault.db-shm", shm);
            File media = new File(context.getFilesDir(), "media");
            File[] files = media.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) putFile(zip, "media/" + file.getName(), file);
                }
            }
            JSONObject prefs = new JSONObject();
            SharedPreferences store = context.getSharedPreferences("vault_security_v1", Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> entry : store.getAll().entrySet()) {
                if (entry.getValue() != null) prefs.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            zip.putNextEntry(new ZipEntry("security.json"));
            zip.write(prefs.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            complete = true;
        } finally {
            if (!complete) resolver.delete(uri, null, null);
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (resolver.update(uri, values, null, null) != 1) {
            resolver.delete(uri, null, null);
            throw new IOException("Backup could not be published");
        }
        return uri;
    }

    public static void importArchive(Context context, Uri uri) throws Exception {
        if (uri == null) throw new IOException("No backup selected");
        File staging = new File(context.getCacheDir(), "vault-import");
        deleteTree(staging);
        if (!staging.mkdirs()) throw new IOException("Import staging failed");
        long total = 0L;
        int entries = 0;
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(input)) {
            if (input == null) throw new IOException("Backup could not be opened");
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > 10_000) throw new IOException("Backup has too many files");
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                File out = safeZipFile(staging, entry.getName());
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Import path could not be created");
                }
                long written = 0L;
                try (FileOutputStream dest = new FileOutputStream(out)) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        written += read;
                        total += read;
                        if (written > 512L * 1024 * 1024) throw new IOException("Backup file is too large");
                        if (total > 2L * 1024 * 1024 * 1024) throw new IOException("Backup is too large");
                        dest.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
        File db = new File(staging, "vault.db");
        if (!db.isFile()) throw new IOException("Backup is missing the vault database");
        File security = new File(staging, "security.json");
        if (!security.isFile()) throw new IOException("Backup is missing security.json");
        String json = new String(java.nio.file.Files.readAllBytes(security.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        JSONObject object = new JSONObject(json);
        if (object.optString("pin_wrapper", "").isEmpty() || object.optString("pin_salt", "").isEmpty()) {
            throw new IOException("Backup is missing the wrapped key");
        }

        VaultDb.shutdown();
        copyReplace(db, context.getDatabasePath("govind_personal_vault.db"));
        File wal = new File(staging, "vault.db-wal");
        File shm = new File(staging, "vault.db-shm");
        File destWal = new File(context.getDatabasePath("govind_personal_vault.db").getAbsolutePath() + "-wal");
        File destShm = new File(context.getDatabasePath("govind_personal_vault.db").getAbsolutePath() + "-shm");
        if (wal.isFile()) copyReplace(wal, destWal); else destWal.delete();
        if (shm.isFile()) copyReplace(shm, destShm); else destShm.delete();
        File mediaDest = new File(context.getFilesDir(), "media");
        deleteTree(mediaDest);
        if (!mediaDest.mkdirs() && !mediaDest.isDirectory()) throw new IOException("Media folder could not be restored");
        File mediaSrc = new File(staging, "media");
        File[] mediaFiles = mediaSrc.listFiles();
        if (mediaFiles != null) {
            for (File file : mediaFiles) {
                if (file.isFile()) copyReplace(file, new File(mediaDest, file.getName()));
            }
        }
        SharedPreferences.Editor editor = context.getSharedPreferences("vault_security_v1", Context.MODE_PRIVATE).edit().clear();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = object.optString(key, "");
            if ("pin_iterations".equals(key) || "recovery_iterations".equals(key)) {
                try { editor.putInt(key, Integer.parseInt(value)); } catch (NumberFormatException ignored) { editor.putString(key, value); }
            } else if ("biometric_enabled".equals(key)) {
                editor.putBoolean(key, "true".equalsIgnoreCase(value));
            } else {
                editor.putString(key, value);
            }
        }
        if (!editor.commit()) throw new IOException("Security material could not be restored");
        deleteTree(staging);
    }

    public static String importEnc(Context context, Uri uri) throws Exception {
        if (!VaultSession.isUnlocked()) throw new GeneralSecurityException("Vault is locked");
        if (uri == null) throw new IOException("No file selected");
        File staging = new File(context.getCacheDir(), "import-" + System.nanoTime() + ".enc");
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(staging)) {
            if (input == null) throw new IOException("File could not be opened");
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        }
        byte[] header = new byte[MediaFileFormat.HEADER_CORE_BYTES];
        try (FileInputStream check = new FileInputStream(staging)) {
            if (check.read(header) != header.length) {
                staging.delete();
                throw new IOException("Not a vault .enc file");
            }
        }
        java.nio.ByteBuffer values = java.nio.ByteBuffer.wrap(header);
        int magic = values.getInt();
        int version = values.getInt();
        values.getInt();
        long clearLength = values.getLong();
        java.util.UUID storedId = new java.util.UUID(values.getLong(), values.getLong());
        if (magic != MediaFileFormat.MAGIC || version != MediaFileFormat.VERSION || clearLength < 0L) {
            staging.delete();
            throw new IOException("Not a vault .enc file");
        }
        File dest = MediaFileFormat.finalFile(context, storedId);
        copyReplace(staging, dest);
        staging.delete();
        com.govind.personalvault.model.MediaItemRecord record = new com.govind.personalvault.model.MediaItemRecord();
        record.id = storedId.toString();
        String name = uri.getLastPathSegment();
        if (name == null) name = "Imported.enc";
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) name = name.substring(slash + 1);
        record.originalName = name;
        record.title = name;
        record.mimeType = "application/octet-stream";
        record.size = Math.max(1L, clearLength);
        record.createdAt = System.currentTimeMillis();
        record.updatedAt = record.createdAt;
        try {
            VaultDb.get(context).saveMediaBlocking(record);
        } catch (Exception exists) {
            VaultDb.get(context).updateMediaMetaNow(record);
        }
        return record.id;
    }

    private static File safeZipFile(File staging, String name) throws IOException {
        if (name == null || name.isEmpty()) throw new IOException("Invalid backup entry");
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) throw new IOException("Invalid backup entry");
        File out = new File(staging, normalized);
        String root = staging.getCanonicalPath();
        String path = out.getCanonicalPath();
        if (!path.equals(root) && !path.startsWith(root + File.separator)) throw new IOException("Invalid backup entry");
        return out;
    }

    private static void putFile(ZipOutputStream zip, String name, File file) throws IOException {
        if (file == null || !file.isFile()) return;
        zip.putNextEntry(new ZipEntry(name));
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) zip.write(buffer, 0, read);
        }
        zip.closeEntry();
    }

    private static void copyReplace(File from, File to) throws IOException {
        File parent = to.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Restore path missing");
        try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
