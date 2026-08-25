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

    public boolean isPdf() {
        if (mimeType != null && "application/pdf".equalsIgnoreCase(mimeType.trim())) return true;
        String name = originalName == null ? "" : originalName.toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".pdf");
    }

    public boolean isText() {
        if (mimeType != null) {
            String mime = mimeType.toLowerCase(java.util.Locale.ROOT);
            if (mime.startsWith("text/")) return true;
            if ("application/json".equals(mime) || "application/xml".equals(mime) || "application/javascript".equals(mime)) return true;
        }
        String name = originalName == null ? "" : originalName.toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv")
                || name.endsWith(".log") || name.endsWith(".json") || name.endsWith(".xml");
    }

    /** Folder key used by the Files tab: image, video, audio, pdf, text, document, other. */
    public String typeKey() {
        if (isImage()) return "image";
        if (isVideo()) return "video";
        if (isAudio()) return "audio";
        if (isPdf()) return "pdf";
        if (isText()) return "text";
        if (isDocument()) return "document";
        return "other";
    }

    public String typeFolderLabel() {
        switch (typeKey()) {
            case "image": return "Images";
            case "video": return "Videos";
            case "audio": return "Audio";
            case "pdf": return "PDF";
            case "text": return "Text";
            case "document": return "Documents";
            default: return "Other";
        }
    }

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
        switch (typeKey()) {
            case "image": return "PHOTO";
            case "video": return "VIDEO";
            case "audio": return "AUDIO";
            case "pdf": return "PDF";
            case "text": return "TEXT";
            case "document": return "DOCUMENT";
            default: return "FILE";
        }
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
