package com.govind.personalvault.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import com.govind.personalvault.security.VaultSession;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * In-app document preview from authenticated encrypted storage.
 * Never writes a plaintext file to disk. PDF bytes live only in a memfd.
 */
public final class InAppDocumentReader {
    public static final long MAX_PDF_BYTES = 80L * 1024L * 1024L;
    public static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ZIP_TEXT_BYTES = 4 * 1024 * 1024;

    public enum Kind { PDF, TEXT, IMAGE, UNSUPPORTED }

    public static final class Preview implements AutoCloseable {
        public final Kind kind;
        public final String text;
        public final String note;
        public PdfRenderer pdf;
        public ParcelFileDescriptor pdfFd;
        public Bitmap image;
        public int pageCount;
        public int pageIndex;

        Preview(Kind kind, String text, String note) {
            this.kind = kind;
            this.text = text;
            this.note = note;
        }

        public synchronized Bitmap renderPdfPage(int index, int maxWidth, int maxHeight) throws Exception {
            if (pdf == null) throw new IOException("PDF preview is not open");
            if (index < 0 || index >= pdf.getPageCount()) throw new IOException("PDF page is out of range");
            VaultSession.requireEpoch();
            PdfRenderer.Page page = pdf.openPage(index);
            try {
                int srcW = Math.max(1, page.getWidth());
                int srcH = Math.max(1, page.getHeight());
                float fit = Math.min(maxWidth / (float) srcW, maxHeight / (float) srcH);
                float scale = Math.max(1.6f, Math.min(2.8f, fit * 2.2f));
                int outW = Math.max(1, Math.round(srcW * scale));
                int outH = Math.max(1, Math.round(srcH * scale));
                Bitmap bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                pageIndex = index;
                return bitmap;
            } finally {
                page.close();
            }
        }

        @Override public synchronized void close() {
            if (image != null && !image.isRecycled()) {
                image.recycle();
            }
            image = null;
            if (pdf != null) {
                try { pdf.close(); } catch (RuntimeException ignored) { }
                pdf = null;
                pdfFd = null;
            } else if (pdfFd != null) {
                try { pdfFd.close(); } catch (IOException | RuntimeException ignored) { }
                pdfFd = null;
            }
            pageCount = 0;
        }
    }

    private InAppDocumentReader() {}

    public static Preview open(Context context, String mediaId, String mimeType, String originalName)
            throws Exception {
        VaultSession.requireEpoch();
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        String name = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);

        if (isPdf(mime, name)) return openPdf(context, mediaId);
        if (mime.startsWith("image/")) return openImage(context, mediaId);
        if (isZipOffice(mime, name) || name.endsWith(".zip") || "application/zip".equals(mime)) {
            return openZipped(context, mediaId, name);
        }
        if (isTextLike(mime, name)) return openText(context, mediaId);
        return openBestEffort(context, mediaId, mime, name);
    }

    private static Preview openPdf(Context context, String mediaId) throws Exception {
        FileDescriptor memfd = null;
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        byte[] buffer = new byte[64 * 1024];
        try (EncryptedMediaInputStream input = EncryptedMediaInputStream.open(context, mediaId)) {
            long length = input.clearLength();
            if (length <= 0L) throw new IOException("Encrypted PDF is empty");
            if (length > MAX_PDF_BYTES) {
                Preview preview = new Preview(
                        Kind.UNSUPPORTED,
                        "",
                        "This PDF is too large to preview in memory. Export a copy to open it.");
                return preview;
            }
            memfd = Os.memfd_create("gvp-pdf", 0);
            int read;
            long written = 0L;
            while ((read = input.read(buffer)) >= 0) {
                VaultSession.requireEpoch();
                int offset = 0;
                while (offset < read) {
                    int put = Os.write(memfd, buffer, offset, read - offset);
                    if (put <= 0) throw new IOException("PDF preview buffer could not be written");
                    offset += put;
                }
                written += read;
                if (written > MAX_PDF_BYTES) throw new IOException("PDF preview exceeded the in-memory limit");
            }
            Os.lseek(memfd, 0, OsConstants.SEEK_SET);
            pfd = ParcelFileDescriptor.dup(memfd);
            renderer = new PdfRenderer(pfd);
            Preview preview = new Preview(Kind.PDF, "", "");
            preview.pdf = renderer;
            preview.pdfFd = pfd;
            preview.pageCount = renderer.getPageCount();
            preview.pageIndex = 0;
            renderer = null;
            pfd = null;
            return preview;
        } catch (ErrnoException posix) {
            throw new IOException("PDF could not be opened in memory", posix);
        } finally {
            Arrays.fill(buffer, (byte) 0);
            if (renderer != null) {
                try { renderer.close(); } catch (RuntimeException ignored) { }
            }
            if (pfd != null) {
                try { pfd.close(); } catch (IOException | RuntimeException ignored) { }
            }
            if (memfd != null && memfd.valid()) {
                try { Os.close(memfd); } catch (ErrnoException | RuntimeException ignored) { }
            }
        }
    }

    private static Preview openImage(Context context, String mediaId) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = EncryptedMediaInputStream.open(context, mediaId)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return unsupported("This image could not be decoded inside the vault.");
        }
        int sample = 1;
        int maxEdge = 4096;
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = EncryptedMediaInputStream.open(context, mediaId)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            if (bitmap == null) return unsupported("This image could not be decoded inside the vault.");
            Preview preview = new Preview(Kind.IMAGE, "", "");
            preview.image = bitmap;
            return preview;
        }
    }

    private static Preview openText(Context context, String mediaId) throws Exception {
        byte[] data = readBounded(context, mediaId, MAX_TEXT_BYTES);
        try {
            String text = decodeText(data);
            if (text.trim().isEmpty()) return unsupported("This file has no readable text preview.");
            Preview preview = new Preview(Kind.TEXT, text, data.length >= MAX_TEXT_BYTES
                    ? "Showing the first part of this file."
                    : "");
            return preview;
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    private static Preview openZipped(Context context, String mediaId, String name) throws Exception {
        StringBuilder extracted = new StringBuilder();
        int total = 0;
        boolean found = false;
        try (ZipInputStream zip = new ZipInputStream(EncryptedMediaInputStream.open(context, mediaId))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > 2500) break;
                if (entry.isDirectory()) continue;
                String entryName = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                String lower = entryName.toLowerCase(Locale.ROOT);
                if (!isDocumentXml(lower, name)) {
                    if (name.endsWith(".zip") && extracted.length() < 8000) {
                        extracted.append(entryName).append('\n');
                        found = true;
                    }
                    continue;
                }
                found = true;
                String piece = readXmlText(zip, MAX_ZIP_TEXT_BYTES - total);
                if (piece.length() > 0) {
                    if (extracted.length() > 0) extracted.append("\n\n");
                    extracted.append(piece);
                    total += piece.length();
                }
                if (total >= MAX_ZIP_TEXT_BYTES) break;
            }
        }
        if (!found) return unsupported("No in-app preview is available for this archive. Export a copy to open it.");
        String text = extracted.toString().trim();
        if (text.isEmpty()) return unsupported("This document has no readable text preview.");
        return new Preview(Kind.TEXT, text, name.endsWith(".zip") ? "Archive contents" : "");
    }

    private static Preview openBestEffort(Context context, String mediaId, String mime, String name)
            throws Exception {
        byte[] data = readBounded(context, mediaId, Math.min(MAX_TEXT_BYTES, 256 * 1024));
        try {
            if (looksLikePdf(data)) return openPdf(context, mediaId);
            if (looksLikeZip(data) || isZipOffice(mime, name)) return openZipped(context, mediaId, name);
            if (looksLikeText(data)) {
                String text = decodeText(data).trim();
                if (!text.isEmpty()) return new Preview(Kind.TEXT, text, "Text preview");
            }
            return unsupported("This file type has no in-app viewer yet. Export a copy if you need another app.");
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    private static Preview unsupported(String message) {
        return new Preview(Kind.UNSUPPORTED, "", message);
    }

    private static byte[] readBounded(Context context, String mediaId, int maxBytes) throws Exception {
        try (EncryptedMediaInputStream input = EncryptedMediaInputStream.open(context, mediaId);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            int total = 0;
            try {
                while (total < maxBytes && (read = input.read(buffer, 0, Math.min(buffer.length, maxBytes - total))) > 0) {
                    VaultSession.requireEpoch();
                    output.write(buffer, 0, read);
                    total += read;
                }
            } finally {
                Arrays.fill(buffer, (byte) 0);
            }
            return output.toByteArray();
        }
    }

    static String readXmlText(InputStream input, int maxChars) throws IOException {
        StringBuilder raw = new StringBuilder();
        byte[] buffer = new byte[16 * 1024];
        int read;
        int bytes = 0;
        try {
            while (bytes < maxChars * 4 && (read = input.read(buffer)) > 0) {
                raw.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                bytes += read;
            }
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
        return stripXml(raw.toString(), maxChars);
    }

    static String stripXml(String xml, int maxChars) {
        if (xml == null || xml.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        boolean inTag = false;
        boolean emittedSpace = true;
        for (int i = 0; i < xml.length() && text.length() < maxChars; i++) {
            char ch = xml.charAt(i);
            if (ch == '<') {
                int close = xml.indexOf('>', i);
                if (close > i) {
                    String tag = xml.substring(i, Math.min(close + 1, xml.length())).toLowerCase(Locale.ROOT);
                    if (tag.startsWith("</w:p") || tag.startsWith("</w:tr") || tag.startsWith("</text:p")
                            || tag.startsWith("</a:p") || tag.startsWith("<w:br") || tag.startsWith("<br")
                            || tag.startsWith("</p") || tag.startsWith("</div") || tag.startsWith("</h")) {
                        text.append('\n');
                        emittedSpace = true;
                    } else if (tag.startsWith("<w:tab") || tag.startsWith("</w:tc")) {
                        text.append('\t');
                        emittedSpace = true;
                    }
                    i = close;
                }
                inTag = false;
                continue;
            }
            if (ch == '&') {
                int semi = xml.indexOf(';', i);
                if (semi > i && semi - i < 10) {
                    text.append(entity(xml.substring(i, semi + 1)));
                    emittedSpace = false;
                    i = semi;
                    continue;
                }
            }
            if (Character.isWhitespace(ch)) {
                if (!emittedSpace && text.length() > 0) {
                    text.append(' ');
                    emittedSpace = true;
                }
            } else {
                text.append(ch);
                emittedSpace = false;
            }
        }
        return text.toString().replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static char entity(String value) {
        if (value == null) return ' ';
        if ("\u0026amp;".equals(value)) return '&';
        if ("\u0026lt;".equals(value)) return '<';
        if ("\u0026gt;".equals(value)) return '>';
        if ("\u0026quot;".equals(value)) return '"';
        if ("\u0026apos;".equals(value)) return '\'';
        if ("\u0026nbsp;".equals(value)) return ' ';
        return ' ';
    }

    private static String decodeText(byte[] data) {
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (data.length >= 3 && (data[0] & 0xff) == 0xEF && (data[1] & 0xff) == 0xBB && (data[2] & 0xff) == 0xBF) {
            offset = 3;
        } else if (data.length >= 2 && (data[0] & 0xff) == 0xFF && (data[1] & 0xff) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (data.length >= 2 && (data[0] & 0xff) == 0xFE && (data[1] & 0xff) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        }
        return new String(data, offset, data.length - offset, charset);
    }

    private static boolean isPdf(String mime, String name) {
        return "application/pdf".equals(mime) || name.endsWith(".pdf");
    }

    private static boolean isTextLike(String mime, String name) {
        if (mime.startsWith("text/")) return true;
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv")
                || name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".log")
                || name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".css")
                || name.endsWith(".js") || name.endsWith(".ini") || name.endsWith(".properties");
    }

    private static boolean isZipOffice(String mime, String name) {
        return name.endsWith(".docx") || name.endsWith(".xlsx") || name.endsWith(".pptx")
                || name.endsWith(".odt") || name.endsWith(".ods") || name.endsWith(".odp")
                || mime.contains("officedocument") || mime.contains("opendocument");
    }

    private static boolean isDocumentXml(String entryName, String archiveName) {
        if (archiveName.endsWith(".docx") && entryName.equals("word/document.xml")) return true;
        if (archiveName.endsWith(".xlsx") && (entryName.equals("xl/sharedstrings.xml")
                || entryName.startsWith("xl/worksheets/sheet"))) return true;
        if (archiveName.endsWith(".pptx") && entryName.startsWith("ppt/slides/slide") && entryName.endsWith(".xml")) {
            return true;
        }
        if ((archiveName.endsWith(".odt") || archiveName.endsWith(".ods") || archiveName.endsWith(".odp"))
                && entryName.equals("content.xml")) {
            return true;
        }
        return false;
    }

    private static boolean looksLikePdf(byte[] data) {
        return data.length >= 5 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';
    }

    private static boolean looksLikeZip(byte[] data) {
        return data.length >= 4 && data[0] == 'P' && data[1] == 'K';
    }

    private static boolean looksLikeText(byte[] data) {
        if (data.length == 0) return false;
        int checked = Math.min(data.length, 1024);
        int printable = 0;
        for (int i = 0; i < checked; i++) {
            int b = data[i] & 0xff;
            if (b == 9 || b == 10 || b == 13 || (b >= 32 && b != 127)) printable++;
            else if (b == 0) return false;
        }
        return printable * 10 >= checked * 8;
    }
}
