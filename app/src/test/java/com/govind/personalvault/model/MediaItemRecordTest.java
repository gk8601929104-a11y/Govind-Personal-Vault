package com.govind.personalvault.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaItemRecordTest {
    @Test public void documentsAreSeparatedFromMedia() {
        MediaItemRecord item = new MediaItemRecord();

        item.mimeType = "application/pdf";
        assertTrue(item.isDocument());
        assertFalse(item.isImage());
        assertFalse(item.isVideo());
        assertFalse(item.isAudio());

        item.mimeType = "text/plain";
        assertTrue(item.isDocument());

        item.mimeType = "image/jpeg";
        assertFalse(item.isDocument());
        assertTrue(item.isImage());

        item.mimeType = "video/mp4";
        assertFalse(item.isDocument());
        assertTrue(item.isVideo());

        item.mimeType = "audio/mpeg";
        assertFalse(item.isDocument());
        assertTrue(item.isAudio());
    }
}
