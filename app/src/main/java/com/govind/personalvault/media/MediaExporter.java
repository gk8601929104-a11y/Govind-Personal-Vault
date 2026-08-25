package com.govind.personalvault.media;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import com.govind.personalvault.model.MediaItemRecord;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Locale;

/** Decrypts directly into a pending MediaStore row; no plaintext temp file is created. */
public final class MediaExporter {
    private MediaExporter() { }

    public static Uri export(Context context, MediaItemRecord item)
            throws IOException, GeneralSecurityException {
        if (item == null || item.id == null) throw new IOException("Encrypted file metadata is missing");
        ContentResolver resolver = context.getContentResolver();
        Uri collection = collection(item.mimeType);
        String name = ensureExtension(
                MediaMetadataResolver.sanitizeName(item.originalName),
                item.mimeType);

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(item.mimeType));
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri outputUri = resolver.insert(collection, values);
        if (outputUri == null) throw new IOException("Export destination could not be created");
        boolean complete = false;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = EncryptedMediaInputStream.open(context, item.id);
             OutputStream output = resolver.openOutputStream(outputUri, "w")) {
            if (output == null) throw new IOException("Export destination could not be opened");
            int read;
            while (true) {
                throwIfInterrupted();
                read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
                throwIfInterrupted();
            }
            output.flush();
            throwIfInterrupted();
            complete = true;
        } finally {
            java.util.Arrays.fill(buffer, (byte) 0);
            if (!complete) resolver.delete(outputUri, null, null);
        }

        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (resolver.update(outputUri, values, null, null) != 1) {
            resolver.delete(outputUri, null, null);
            throw new IOException("Export could not be published to device storage");
        }
        return outputUri;
    }

    /** Writes the still-encrypted GVM2 blob to Downloads as a .enc file. */
    public static Uri exportCiphertext(Context context, MediaItemRecord item)
            throws IOException, GeneralSecurityException {
        if (item == null || item.id == null) throw new IOException("Encrypted file metadata is missing");
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(item.id);
        } catch (IllegalArgumentException bad) {
            throw new IOException("Encrypted file id is invalid");
        }
        File source = MediaFileFormat.finalFile(context, uuid);
        if (!source.isFile()) throw new IOException("Encrypted file is missing on disk");
        String base = MediaMetadataResolver.sanitizeName(item.displayTitle());
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String name = base + ".enc";

        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Govind Personal Vault");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri outputUri = resolver.insert(collection, values);
        if (outputUri == null) throw new IOException("Export destination could not be created");
        boolean complete = false;
        byte[] buffer = new byte[64 * 1024];
        try (java.io.FileInputStream input = new java.io.FileInputStream(source);
             OutputStream output = resolver.openOutputStream(outputUri, "w")) {
            if (output == null) throw new IOException("Export destination could not be opened");
            int read;
            while ((read = input.read(buffer)) >= 0) {
                throwIfInterrupted();
                if (read > 0) output.write(buffer, 0, read);
            }
            output.flush();
            complete = true;
        } finally {
            java.util.Arrays.fill(buffer, (byte) 0);
            if (!complete) resolver.delete(outputUri, null, null);
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (resolver.update(outputUri, values, null, null) != 1) {
            resolver.delete(outputUri, null, null);
            throw new IOException("Export could not be published to device storage");
        }
        return outputUri;
    }

    private static Uri collection(String mime) {
        if (mime != null && mime.startsWith("image/")) {
            return MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        }
        if (mime != null && mime.startsWith("video/")) {
            return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        }
        if (mime != null && mime.startsWith("audio/")) {
            return MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        }
        return MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    private static String relativePath(String mime) {
        if (mime != null && mime.startsWith("image/")) {
            return Environment.DIRECTORY_PICTURES + "/Govind Personal Vault";
        }
        if (mime != null && mime.startsWith("video/")) {
            return Environment.DIRECTORY_MOVIES + "/Govind Personal Vault";
        }
        if (mime != null && mime.startsWith("audio/")) {
            return Environment.DIRECTORY_MUSIC + "/Govind Personal Vault";
        }
        return Environment.DIRECTORY_DOWNLOADS + "/Govind Personal Vault";
    }

    private static String ensureExtension(String name, String mime) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) return name;
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(
                mime == null ? "" : mime.toLowerCase(Locale.ROOT));
        return extension == null || extension.isEmpty() ? name : name + "." + extension;
    }
    private static void throwIfInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Export was cancelled");
        }
    }

}
