package com.govind.personalvault.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.govind.personalvault.model.MediaItemRecord;
import com.govind.personalvault.model.VaultItem;
import com.govind.personalvault.security.SecurityManager;
import com.govind.personalvault.security.VaultSession;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serialized asynchronous SQLite access. Sensitive columns contain only AES-GCM envelopes. */
public final class VaultDb extends SQLiteOpenHelper {
    private static final String TAG = "VaultDb";
    private static final String DB_NAME = "govind_personal_vault.db";
    private static final int DB_VERSION = 3;
    private static volatile VaultDb instance;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vault-database");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.setUncaughtExceptionHandler((failed, error) -> Log.e(TAG, "Database worker failed", error));
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    public interface Callback<T> { void onComplete(T result, Exception error); }

    public static final class Counts {
        public final int passwords;
        public final int notes;
        public final int cards;
        public final int media;
        public final int documents;

        Counts(int passwords, int notes, int cards, int media, int documents) {
            this.passwords = passwords;
            this.notes = notes;
            this.cards = cards;
            this.media = media;
            this.documents = documents;
        }
    }

    public static final class Task {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Future<?> future;
        private synchronized void attach(Future<?> value) {
            future = value;
            if (cancelled.get()) value.cancel(false);
        }
        public synchronized void cancel() {
            cancelled.set(true);
            if (future != null) future.cancel(false);
        }
        private boolean isCancelled() { return cancelled.get(); }
    }

    public static VaultDb get(Context context) {
        VaultDb value = instance;
        if (value == null) {
            synchronized (VaultDb.class) {
                value = instance;
                if (value == null) instance = value = new VaultDb(context.getApplicationContext());
            }
        }
        return value;
    }

    public static void shutdown() {
        synchronized (VaultDb.class) {
            if (instance != null) {
                try { instance.close(); } catch (RuntimeException ignored) { }
                instance = null;
            }
        }
    }

    private VaultDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.context = context;
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        Cursor pragma = db.rawQuery("PRAGMA secure_delete=ON", null);
        try {
            if (pragma.moveToFirst() && pragma.getInt(0) == 0) {
                Log.w(TAG, "SQLite secure_delete could not be enabled");
            }
        } finally {
            pragma.close();
        }
    }

    @Override public void onCreate(SQLiteDatabase db) {
        createVaultTable(db);
        createMediaTable(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createMediaTable(db);
        if (oldVersion < 3) upgradeVaultItemsV3(db);
    }

    private static void createVaultTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS vault_items (" +
                "_id TEXT PRIMARY KEY," +
                "kind TEXT NOT NULL," +
                "title_blob TEXT NOT NULL," +
                "username_blob TEXT NOT NULL," +
                "secret_blob TEXT NOT NULL," +
                "url_blob TEXT NOT NULL," +
                "notes_blob TEXT NOT NULL," +
                "category_blob TEXT NOT NULL DEFAULT ''," +
                "favorite_blob TEXT NOT NULL DEFAULT ''," +
                "tags_blob TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS vault_kind_updated_idx ON vault_items(kind, updated_at DESC)");
    }

    private static void upgradeVaultItemsV3(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS vault_items_v3 (" +
                "_id TEXT PRIMARY KEY," +
                "kind TEXT NOT NULL," +
                "title_blob TEXT NOT NULL," +
                "username_blob TEXT NOT NULL," +
                "secret_blob TEXT NOT NULL," +
                "url_blob TEXT NOT NULL," +
                "notes_blob TEXT NOT NULL," +
                "category_blob TEXT NOT NULL DEFAULT ''," +
                "favorite_blob TEXT NOT NULL DEFAULT ''," +
                "tags_blob TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("INSERT OR IGNORE INTO vault_items_v3 (" +
                "_id, kind, title_blob, username_blob, secret_blob, url_blob, notes_blob," +
                "category_blob, favorite_blob, tags_blob, created_at, updated_at) " +
                "SELECT _id, kind, title_blob, username_blob, secret_blob, url_blob, notes_blob," +
                "'', '', '', created_at, updated_at FROM vault_items");
        db.execSQL("DROP TABLE IF EXISTS vault_items");
        db.execSQL("ALTER TABLE vault_items_v3 RENAME TO vault_items");
        db.execSQL("CREATE INDEX IF NOT EXISTS vault_kind_updated_idx ON vault_items(kind, updated_at DESC)");
    }

    private static void createMediaTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS media_items (" +
                "_id TEXT PRIMARY KEY," +
                "original_name_blob TEXT NOT NULL," +
                "mime_type_blob TEXT NOT NULL," +
                "size_blob TEXT NOT NULL," +
                "thumbnail_blob TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS media_updated_idx ON media_items(updated_at DESC)");
    }

    public Task saveAsync(VaultItem source, Callback<String> callback) {
        VaultItem snapshot = source == null ? new VaultItem() : source.copy();
        return submit(() -> saveBlocking(snapshot), callback);
    }

    public Task getAsync(String id, Callback<VaultItem> callback) {
        final String requested = safeId(id);
        return submit(() -> getBlocking(requested), callback);
    }

    public Task listAsync(String kind, String search, int limit, Callback<List<VaultItem>> callback) {
        final String requestedKind = VaultItem.validKind(kind) ? kind : VaultItem.PASSWORD;
        final String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        final int safeLimit = Math.max(1, Math.min(1000, limit));
        return submit(() -> listBlocking(requestedKind, query, safeLimit), callback);
    }

    public Task deleteAsync(String id, Callback<Boolean> callback) {
        final String requested = safeId(id);
        return submit(() -> getWritableDatabase().delete("vault_items", "_id=?", new String[]{requested}) > 0, callback);
    }

    public Task listMediaAsync(String search, String kind, int limit, Callback<List<MediaItemRecord>> callback) {
        final String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        final String requestedKind = normalizeMediaKind(kind);
        final int safeLimit = Math.max(1, Math.min(500, limit));
        return submit(() -> listMediaBlocking(query, requestedKind, safeLimit), callback);
    }

    public Task getMediaAsync(String id, Callback<MediaItemRecord> callback) {
        final String requested = safeId(id);
        return submit(() -> getMediaBlocking(requested), callback);
    }

    public Task countsAsync(Callback<Counts> callback) {
        return submit(() -> {
            int passwords = 0;
            int notes = 0;
            int cards = 0;
            int media = 0;
            Cursor cursor = getReadableDatabase().rawQuery(
                    "SELECT kind, COUNT(*) FROM vault_items GROUP BY kind",
                    null);
            try {
                while (cursor.moveToNext()) {
                    if (VaultItem.PASSWORD.equals(cursor.getString(0))) passwords = cursor.getInt(1);
                    else if (VaultItem.NOTE.equals(cursor.getString(0))) notes = cursor.getInt(1);
                    else if (VaultItem.CARD.equals(cursor.getString(0))) cards = cursor.getInt(1);
                }
            } finally {
                cursor.close();
            }
            int documents = 0;
            Cursor mediaCursor = getReadableDatabase().query(
                    "media_items",
                    new String[]{"_id", "mime_type_blob"},
                    null,
                    null,
                    null,
                    null,
                    null);
            try {
                SecurityManager security = SecurityManager.get(context);
                while (mediaCursor.moveToNext()) {
                    String id = mediaCursor.getString(mediaCursor.getColumnIndexOrThrow("_id"));
                    String mime = security.decryptText(
                            mediaPurpose(id, "mime_type"),
                            mediaCursor.getString(mediaCursor.getColumnIndexOrThrow("mime_type_blob")));
                    if (MediaItemRecord.isDocumentMime(mime)) documents++;
                    else media++;
                }
            } finally {
                mediaCursor.close();
            }
            return new Counts(passwords, notes, cards, media, documents);
        }, callback);
    }

    /** Media worker only. Never call this blocking method from the main thread. */
    public void saveMediaBlocking(MediaItemRecord source) throws Exception {
        if (!VaultSession.isUnlocked()) throw new GeneralSecurityException("Vault is locked");
        MediaItemRecord item = source == null ? new MediaItemRecord() : source.copy();
        try {
            String id = safeId(item.id);
            if (item.originalName.trim().isEmpty()) throw new GeneralSecurityException("File name is required");
            String mime = item.mimeType == null ? "" : item.mimeType.trim().toLowerCase(Locale.ROOT);
            if (mime.length() > 200 || !mime.matches("[a-z0-9!#$&^_.+\\-]+/[a-z0-9!#$&^_.+\\-]+")) {
                throw new GeneralSecurityException("Invalid file type");
            }
            item.mimeType = mime;
            if (item.size <= 0L) throw new GeneralSecurityException("Invalid file size");
            long now = System.currentTimeMillis();
            long created = item.createdAt > 0L ? item.createdAt : now;
            SecurityManager security = SecurityManager.get(context);
            ContentValues values = new ContentValues();
            values.put("_id", id);
            values.put("original_name_blob", security.encryptText(mediaPurpose(id, "original_name"), item.originalName));
            values.put("mime_type_blob", security.encryptText(mediaPurpose(id, "mime_type"), item.mimeType));
            values.put("size_blob", security.encryptText(mediaPurpose(id, "size"), Long.toString(item.size)));
            values.put("thumbnail_blob", security.encryptBytes(mediaPurpose(id, "thumbnail"), item.thumbnail));
            values.put("created_at", created);
            values.put("updated_at", item.updatedAt > 0L ? item.updatedAt : now);
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                long row = db.insertWithOnConflict("media_items", null, values, SQLiteDatabase.CONFLICT_ABORT);
                if (row < 0L) throw new GeneralSecurityException("Media metadata could not be saved");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            item.clearSensitive();
        }
    }

    /** Media worker only. Never call this blocking method from the main thread. */
    public MediaItemRecord getMediaBlocking(String id) throws Exception {
        String requested = safeId(id);
        Cursor cursor = getReadableDatabase().query(
                "media_items",
                null,
                "_id=?",
                new String[]{requested},
                null,
                null,
                null,
                "1");
        try { return cursor.moveToFirst() ? decodeMedia(cursor) : null; }
        finally { cursor.close(); }
    }

    /** Media worker only. Never call this blocking method from the main thread. */
    public java.util.Set<String> listMediaIdsBlocking() {
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        Cursor cursor = getReadableDatabase().query(
                "media_items",
                new String[]{"_id"},
                null,
                null,
                null,
                null,
                null);
        try {
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(0));
            }
        } finally {
            cursor.close();
        }
        return ids;
    }

    /** Media worker only. Never call this blocking method from the main thread. */
    public boolean deleteMediaMetadataBlocking(String id) {
        return getWritableDatabase().delete("media_items", "_id=?", new String[]{safeId(id)}) > 0;
    }

    private String saveBlocking(VaultItem item) throws Exception {
        if (!VaultSession.isUnlocked()) throw new GeneralSecurityException("Vault is locked");
        if (!VaultItem.validKind(item.kind)) throw new GeneralSecurityException("Invalid vault item type");
        if (item.title.trim().isEmpty()) throw new GeneralSecurityException("Title is required");
        String id = item.id == null || item.id.isEmpty() ? UUID.randomUUID().toString() : safeId(item.id);
        long now = System.currentTimeMillis();
        long created = item.createdAt > 0 ? item.createdAt : existingCreatedAt(id, now);
        SecurityManager security = SecurityManager.get(context);
        ContentValues values = new ContentValues();
        values.put("_id", id);
        values.put("kind", item.kind);
        values.put("title_blob", security.encryptField(id, "title", item.title));
        values.put("username_blob", security.encryptField(id, "username", item.username));
        values.put("secret_blob", security.encryptField(id, "secret", item.secret));
        values.put("url_blob", security.encryptField(id, "url", item.url));
        values.put("notes_blob", security.encryptField(id, "notes", item.notes));
        values.put("category_blob", security.encryptField(id, "category", VaultItem.normalizeCategory(item.category)));
        values.put("favorite_blob", security.encryptField(id, "favorite", item.favorite ? "1" : "0"));
        values.put("tags_blob", security.encryptField(id, "tags", item.tags));
        values.put("created_at", created);
        values.put("updated_at", now);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long row = db.insertWithOnConflict("vault_items", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            if (row < 0) throw new GeneralSecurityException("Vault item could not be saved");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return id;
    }

    private VaultItem getBlocking(String id) throws Exception {
        Cursor cursor = getReadableDatabase().query(
                "vault_items",
                null,
                "_id=?",
                new String[]{id},
                null,
                null,
                null,
                "1");
        try { return cursor.moveToFirst() ? decode(cursor) : null; }
        finally { cursor.close(); }
    }

    private List<VaultItem> listBlocking(String kind, String query, int limit) throws Exception {
        ArrayList<VaultItem> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "vault_items",
                null,
                "kind=?",
                new String[]{kind},
                null,
                null,
                "updated_at DESC",
                String.valueOf(limit));
        try {
            while (cursor.moveToNext()) {
                VaultItem item = decode(cursor);
                if (query.isEmpty() || matches(item, query)) result.add(item);
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    private List<MediaItemRecord> listMediaBlocking(String query, String kind, int limit) throws Exception {
        ArrayList<MediaItemRecord> result = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "media_items",
                null,
                null,
                null,
                null,
                null,
                "updated_at DESC",
                String.valueOf(limit));
        try {
            while (cursor.moveToNext()) {
                MediaItemRecord item = decodeMedia(cursor);
                boolean kindMatches = ("files".equals(kind)
                        || ("all".equals(kind) && !item.isDocument())
                        || ("image".equals(kind) && item.isImage())
                        || ("video".equals(kind) && item.isVideo())
                        || ("audio".equals(kind) && item.isAudio())
                        || ("document".equals(kind) && item.isDocument()));
                boolean queryMatches = query.isEmpty()
                        || item.originalName.toLowerCase(Locale.ROOT).contains(query)
                        || item.mimeType.toLowerCase(Locale.ROOT).contains(query);
                if (kindMatches && queryMatches) result.add(item);
                else item.clearSensitive();
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    private VaultItem decode(Cursor cursor) throws Exception {
        String id = cursor.getString(cursor.getColumnIndexOrThrow("_id"));
        SecurityManager security = SecurityManager.get(context);
        VaultItem item = new VaultItem();
        item.id = id;
        item.kind = cursor.getString(cursor.getColumnIndexOrThrow("kind"));
        item.title = security.decryptField(id, "title", cursor.getString(cursor.getColumnIndexOrThrow("title_blob")));
        item.username = security.decryptField(id, "username", cursor.getString(cursor.getColumnIndexOrThrow("username_blob")));
        item.secret = security.decryptField(id, "secret", cursor.getString(cursor.getColumnIndexOrThrow("secret_blob")));
        item.url = security.decryptField(id, "url", cursor.getString(cursor.getColumnIndexOrThrow("url_blob")));
        item.notes = security.decryptField(id, "notes", cursor.getString(cursor.getColumnIndexOrThrow("notes_blob")));
        item.category = optionalField(security, id, "category", cursor, "category_blob", "Personal");
        item.category = VaultItem.normalizeCategory(item.category);
        item.favorite = "1".equals(optionalField(security, id, "favorite", cursor, "favorite_blob", "0"));
        item.tags = optionalField(security, id, "tags", cursor, "tags_blob", "");
        item.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"));
        item.updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"));
        return item;
    }

    private MediaItemRecord decodeMedia(Cursor cursor) throws Exception {
        String id = cursor.getString(cursor.getColumnIndexOrThrow("_id"));
        SecurityManager security = SecurityManager.get(context);
        MediaItemRecord item = new MediaItemRecord();
        item.id = id;
        item.originalName = security.decryptText(
                mediaPurpose(id, "original_name"),
                cursor.getString(cursor.getColumnIndexOrThrow("original_name_blob")));
        item.mimeType = security.decryptText(
                mediaPurpose(id, "mime_type"),
                cursor.getString(cursor.getColumnIndexOrThrow("mime_type_blob")));
        String size = security.decryptText(
                mediaPurpose(id, "size"),
                cursor.getString(cursor.getColumnIndexOrThrow("size_blob")));
        try { item.size = Long.parseLong(size); }
        catch (NumberFormatException damaged) { throw new GeneralSecurityException("Encrypted media size is invalid", damaged); }
        item.thumbnail = security.decryptBytes(
                mediaPurpose(id, "thumbnail"),
                cursor.getString(cursor.getColumnIndexOrThrow("thumbnail_blob")));
        item.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"));
        item.updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"));
        return item;
    }

    private long existingCreatedAt(String id, long fallback) {
        Cursor cursor = getReadableDatabase().query(
                "vault_items",
                new String[]{"created_at"},
                "_id=?",
                new String[]{id},
                null,
                null,
                null,
                "1");
        try { return cursor.moveToFirst() ? cursor.getLong(0) : fallback; }
        finally { cursor.close(); }
    }

    private boolean matches(VaultItem item, String query) {
        return item.title.toLowerCase(Locale.ROOT).contains(query)
                || item.username.toLowerCase(Locale.ROOT).contains(query)
                || item.url.toLowerCase(Locale.ROOT).contains(query)
                || item.notes.toLowerCase(Locale.ROOT).contains(query)
                || item.category.toLowerCase(Locale.ROOT).contains(query)
                || item.tags.toLowerCase(Locale.ROOT).contains(query);
    }

    private static String mediaPurpose(String id, String field) {
        return "media|" + id + "|" + field + "|v1";
    }

    private static String normalizeMediaKind(String kind) {
        return "image".equals(kind)
                || "video".equals(kind)
                || "audio".equals(kind)
                || "document".equals(kind)
                || "files".equals(kind)
                ? kind
                : "all";
    }

    private static String optionalField(
            SecurityManager security,
            String id,
            String field,
            Cursor cursor,
            String column,
            String fallback) throws Exception {
        int index = cursor.getColumnIndex(column);
        if (index < 0) return fallback;
        String envelope = cursor.getString(index);
        if (envelope == null || envelope.isEmpty()) return fallback;
        try {
            String value = security.decryptField(id, field, envelope);
            return value == null || value.isEmpty() ? fallback : value;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String safeId(String id) {
        if (id == null || id.length() > 64 || !id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid item identifier");
        }
        return id;
    }


    private static void clearDroppedResult(Object result) {
        if (result instanceof MediaItemRecord) {
            ((MediaItemRecord) result).clearSensitive();
            return;
        }
        if (result instanceof VaultItem) {
            VaultItem item = (VaultItem) result;
            item.title = "";
            item.username = "";
            item.secret = "";
            item.url = "";
            item.notes = "";
            item.category = "Personal";
            item.tags = "";
            return;
        }
        if (result instanceof List<?>) {
            for (Object value : (List<?>) result) clearDroppedResult(value);
        }
    }

    private <T> Task submit(Callable<T> operation, Callback<T> callback) {
        Task task = new Task();
        task.attach(executor.submit(() -> {
            if (task.isCancelled()) {
                return;
            }

            T result = null;
            Exception error = null;
            try {
                result = operation.call();
            } catch (Exception failure) {
                error = failure;
            }

            if (task.isCancelled()) {
                clearDroppedResult(result);
                return;
            }

            T delivered = result;
            Exception failure = error;
            main.post(() -> {
                if (task.isCancelled()) {
                    clearDroppedResult(delivered);
                    return;
                }

                Exception visibleFailure = failure;
                T visibleResult = delivered;
                if (!VaultSession.isUnlocked() && visibleFailure == null) {
                    clearDroppedResult(visibleResult);
                    visibleFailure = new GeneralSecurityException("Vault was locked");
                    visibleResult = null;
                }

                if (callback == null) {
                    clearDroppedResult(visibleResult);
                    return;
                }

                try {
                    callback.onComplete(visibleResult, visibleFailure);
                } catch (RuntimeException callbackError) {
                    clearDroppedResult(visibleResult);
                    Log.e(TAG, "Database callback failed", callbackError);
                }
            });
        }));
        return task;
    }
}
