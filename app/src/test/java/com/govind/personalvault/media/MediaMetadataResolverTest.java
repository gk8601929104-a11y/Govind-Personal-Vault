package com.govind.personalvault.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaMetadataResolverTest {
    @Test public void mediaMimeDetectionIsStrict() {
        assertTrue(MediaMetadataResolver.isMediaMime("image/png"));
        assertTrue(MediaMetadataResolver.isMediaMime("video/x-matroska"));
        assertTrue(MediaMetadataResolver.isMediaMime("audio/flac"));
        assertFalse(MediaMetadataResolver.isMediaMime("application/pdf"));
        assertFalse(MediaMetadataResolver.isMediaMime("text/plain"));
        assertFalse(MediaMetadataResolver.isMediaMime("application/octet-stream"));
        assertFalse(MediaMetadataResolver.isMediaMime(null));
    }
}
