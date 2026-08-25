package com.govind.personalvault.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Produces a small bounded thumbnail before the source is encrypted. */
public final class MediaThumbnailer {
    private static final int MAX_EDGE = 360;
    private static final int MAX_BYTES = 192 * 1024;

    private MediaThumbnailer() { }

    public static byte[] create(Context context, Uri uri, String mimeType) {
        Bitmap bitmap = null;
        try {
            if (mimeType.startsWith("image/")) bitmap = decodeSampled(context, uri);
            else if (mimeType.startsWith("video/")) bitmap = videoFrame(context, uri);
            else if (mimeType.startsWith("audio/")) bitmap = embeddedArtwork(context, uri);
            if (bitmap == null) return new byte[0];
            Bitmap scaled = scale(bitmap);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
            return compressBounded(bitmap);
        } catch (RuntimeException | IOException ignored) {
            return new byte[0];
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private static Bitmap decodeSampled(Context context, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / sample > MAX_EDGE * 2 || bounds.outHeight / sample > MAX_EDGE * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            return input == null ? null : BitmapFactory.decodeStream(input, null, options);
        }
    }

    private static Bitmap videoFrame(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            return retriever.getScaledFrameAtTime(
                    1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    MAX_EDGE,
                    MAX_EDGE);
        } finally {
            try { retriever.release(); } catch (IOException | RuntimeException ignored) { }
        }
    }

    private static Bitmap embeddedArtwork(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        byte[] picture = null;
        try {
            retriever.setDataSource(context, uri);
            picture = retriever.getEmbeddedPicture();
            if (picture == null || picture.length == 0) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(picture, 0, picture.length, bounds);
            int sample = 1;
            while (bounds.outWidth / sample > MAX_EDGE * 2 || bounds.outHeight / sample > MAX_EDGE * 2) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
        } finally {
            if (picture != null) Arrays.fill(picture, (byte) 0);
            try { retriever.release(); } catch (IOException | RuntimeException ignored) { }
        }
    }

    private static Bitmap scale(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= MAX_EDGE && height <= MAX_EDGE) return source;
        float ratio = Math.min((float) MAX_EDGE / width, (float) MAX_EDGE / height);
        int targetWidth = Math.max(1, Math.round(width * ratio));
        int targetHeight = Math.max(1, Math.round(height * ratio));
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
    }

    private static byte[] compressBounded(Bitmap bitmap) {
        for (int quality = 82; quality >= 45; quality -= 9) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output);
            byte[] value = output.toByteArray();
            if (value.length <= MAX_BYTES || quality == 46) return value;
            Arrays.fill(value, (byte) 0);
        }
        return new byte[0];
    }
}
