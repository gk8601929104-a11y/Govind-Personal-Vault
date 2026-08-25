package com.govind.personalvault.model;

import java.util.Arrays;

/** Decrypted media/document metadata kept only while the vault is unlocked. */
public final class MediaItemRecord {
    public String id = "";
    public String originalName = "";
    public String mimeType = "application/octet-stream";
    public String title = "";
    public String category = "Personal";
    public String tags = "";
    public String notes = "";
    public boolean favorite;
    public long size;
    public byte[] thumbnail = new byte[0];
    public long createdAt;
    public long updatedAt;

    public boolean isImage() { return mimeType != null && mimeType.startsWith("image/"); }
    public boolean isVideo() { return mimeType != null && mimeType.startsWith("video/"); }
    public boolean isAudio() { return mimeType != null && mimeType.startsWith("audio/"); }
    public boolean isDocument() { return isDocumentMime(mimeType); }

    public static boolean isDocumentMime(String mime) {
        return mime == null
                || !(mime.startsWith("image/")
                || mime.startsWith("video/")
                || mime.startsWith("audio/"));
    }

    public String displayTitle() {
        if (title != null && !title.trim().isEmpty()) return title.trim();
        return originalName == null ? "" : originalName;
    }

    public String kindLabel() {
        if (isImage()) return "PHOTO";
        if (isVideo()) return "VIDEO";
        if (isAudio()) return "AUDIO";
        return "DOCUMENT";
    }

    public MediaItemRecord copy() {
        MediaItemRecord copy = new MediaItemRecord();
        copy.id = id;
        copy.originalName = originalName;
        copy.mimeType = mimeType;
        copy.title = title == null ? "" : title;
        copy.category = VaultItem.normalizeCategory(category);
        copy.tags = tags == null ? "" : tags;
        copy.notes = notes == null ? "" : notes;
        copy.favorite = favorite;
        copy.size = size;
        copy.thumbnail = thumbnail == null ? new byte[0] : Arrays.copyOf(thumbnail, thumbnail.length);
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        return copy;
    }

    public void clearSensitive() {
        if (thumbnail != null) Arrays.fill(thumbnail, (byte) 0);
        thumbnail = new byte[0];
        originalName = "";
        mimeType = "application/octet-stream";
        title = "";
        category = "Personal";
        tags = "";
        notes = "";
        favorite = false;
        size = 0L;
    }
}
