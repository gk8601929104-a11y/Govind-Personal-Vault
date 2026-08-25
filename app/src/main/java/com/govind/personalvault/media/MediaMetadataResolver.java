package com.govind.personalvault.media;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.IOException;
import java.net.URLConnection;
import java.util.Locale;

/** Safely resolves untrusted document-provider metadata. */
public final class MediaMetadataResolver {
    public static final class Metadata {
        public final String displayName;
        public final String mimeType;
        public final long declaredSize;

        Metadata(String displayName, String mimeType, long declaredSize) {
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.declaredSize = declaredSize;
        }
    }

    private MediaMetadataResolver() { }

    public static Metadata resolveMedia(Context context, Uri uri) throws IOException {
        return resolve(context, uri, false);
    }

    public static Metadata resolveDocument(Context context, Uri uri) throws IOException {
        return resolve(context, uri, true);
    }

    private static Metadata resolve(Context context, Uri uri, boolean documentMode)
            throws IOException {
        if (uri == null) throw new IOException("The selected file is unavailable");

        ContentResolver resolver = context.getContentResolver();
        String name = null;
        long size = -1L;
        Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                        name = cursor.getString(nameColumn);
                    }
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        size = cursor.getLong(sizeColumn);
                    }
                }
            } finally {
                cursor.close();
            }
        }

        if (size < 0L) {
            try (AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r")) {
                if (descriptor != null && descriptor.getLength() >= 0L) {
                    size = descriptor.getLength();
                }
            } catch (IOException ignored) { }
        }

        String fallbackName = documentMode ? "Imported document" : "Imported media";
        name = sanitizeName(name == null ? fallbackName : name);

        String mime = resolver.getType(uri);
        if (mime == null || mime.trim().isEmpty() || "application/octet-stream".equalsIgnoreCase(mime)) {
            String guessed = URLConnection.guessContentTypeFromName(name);
            if (guessed != null && !guessed.trim().isEmpty()) mime = guessed;
        }
        if (mime == null || mime.trim().isEmpty()) {
            String extension = extensionOf(name);
            if (!extension.isEmpty()) {
                mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            }
        }

        if (mime == null || mime.trim().isEmpty()) {
            if (!documentMode) {
                throw new IOException("The selected media type could not be identified");
            }
            mime = "application/octet-stream";
        }

        mime = sanitizeMime(mime);
        boolean media = isMediaMime(mime);
        if (documentMode && media) {
            throw new IOException("Photos, videos, and audio belong in the Media Vault");
        }
        if (!documentMode && !media) {
            throw new IOException("Only image, video, and audio files can be imported here");
        }
        if (size == 0L) {
            throw new IOException(documentMode
                    ? "Empty documents cannot be imported"
                    : "Empty media files cannot be imported");
        }
        return new Metadata(name, mime, size);
    }

    public static boolean isMediaMime(String mime) {
        if (mime == null) return false;
        String value = mime.toLowerCase(Locale.ROOT);
        return value.startsWith("image/")
                || value.startsWith("video/")
                || value.startsWith("audio/");
    }

    private static String sanitizeMime(String value) throws IOException {
        String mime = value.toLowerCase(Locale.ROOT).trim();
        if (mime.length() > 200 || !mime.matches("[a-z0-9!#$&^_.+\\-]+/[a-z0-9!#$&^_.+\\-]+")) {
            throw new IOException("The selected file has an invalid content type");
        }
        return mime;
    }

    public static String sanitizeName(String value) {
        String source = value == null ? "Imported file" : value;
        StringBuilder clean = new StringBuilder(Math.min(180, source.length()));
        for (int index = 0; index < source.length() && clean.length() < 180; index++) {
            char c = source.charAt(index);
            if (c == '/' || c == '\\' || c == 0 || Character.isISOControl(c)) clean.append('_');
            else clean.append(c);
        }
        String result = clean.toString().trim();
        while (result.startsWith(".")) result = result.substring(1).trim();
        return result.isEmpty() ? "Imported file" : result;
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
