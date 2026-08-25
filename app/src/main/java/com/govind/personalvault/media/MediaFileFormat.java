package com.govind.personalvault.media;

import android.content.Context;

import com.govind.personalvault.security.VaultSession;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Shared GVM2 seekable, chunk-authenticated encrypted-media format. */
final class MediaFileFormat {
    static final int MAGIC = 0x47564D32; // GVM2
    static final int VERSION = 2;
    static final int CHUNK_SIZE = 1024 * 1024;
    static final int MIN_CHUNK_SIZE = 64 * 1024;
    static final int MAX_CHUNK_SIZE = 4 * 1024 * 1024;
    static final int IV_BYTES = 12;
    static final int TAG_BYTES = 16;
    static final int SALT_BYTES = 16;
    static final int MAC_BYTES = 32;
    static final int KEY_BYTES = 32;
    static final int HEADER_CORE_BYTES = 52;
    static final int HEADER_BYTES = HEADER_CORE_BYTES + MAC_BYTES;
    static final String EXTENSION = ".gvm";

    private static final byte[] HKDF_INFO =
            "GovindPersonalVault/Media/GVM2/v2".getBytes(StandardCharsets.UTF_8);

    static final class Header {
        final int chunkSize;
        final long clearLength;
        final UUID mediaId;
        final byte[] salt;

        Header(int chunkSize, long clearLength, UUID mediaId, byte[] salt) {
            this.chunkSize = chunkSize;
            this.clearLength = clearLength;
            this.mediaId = mediaId;
            this.salt = salt;
        }
    }

    static final class KeyMaterial implements AutoCloseable {
        final byte[] contentKey;
        final byte[] headerMacKey;

        KeyMaterial(byte[] contentKey, byte[] headerMacKey) {
            this.contentKey = contentKey;
            this.headerMacKey = headerMacKey;
        }

        @Override public void close() {
            wipe(contentKey);
            wipe(headerMacKey);
        }
    }

    static final class Opened implements AutoCloseable {
        final Header header;
        final KeyMaterial keys;

        Opened(Header header, KeyMaterial keys) {
            this.header = header;
            this.keys = keys;
        }

        @Override public void close() {
            keys.close();
            wipe(header.salt);
        }
    }

    private MediaFileFormat() { }

    static File directory(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "media");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Secure media directory could not be created");
        }
        if (!directory.isDirectory()) throw new IOException("Secure media path is invalid");
        return directory;
    }

    static File finalFile(Context context, UUID id) throws IOException {
        return new File(directory(context), id.toString() + EXTENSION);
    }

    static File partFile(Context context, UUID id) throws IOException {
        return new File(directory(context), id.toString() + EXTENSION + ".part");
    }

    static File trashFile(Context context, UUID id) throws IOException {
        return new File(directory(context), id.toString() + EXTENSION + ".trash");
    }

    static Opened open(RandomAccessFile input, UUID expectedId)
            throws IOException, GeneralSecurityException {
        if (input.length() < HEADER_BYTES) throw new EOFException("Encrypted media header is truncated");
        input.seek(0L);

        byte[] core = new byte[HEADER_CORE_BYTES];
        byte[] storedMac = new byte[MAC_BYTES];
        input.readFully(core);
        input.readFully(storedMac);

        ByteBuffer values = ByteBuffer.wrap(core);
        int magic = values.getInt();
        int version = values.getInt();
        int chunkSize = values.getInt();
        long clearLength = values.getLong();
        UUID storedId = new UUID(values.getLong(), values.getLong());
        byte[] salt = new byte[SALT_BYTES];
        values.get(salt);

        if (magic != MAGIC || version != VERSION) {
            wipe(core, storedMac, salt);
            throw new IOException("Unsupported encrypted media format");
        }
        if (!expectedId.equals(storedId)) {
            wipe(core, storedMac, salt);
            throw new IOException("Encrypted media identity does not match its metadata");
        }
        if (chunkSize < MIN_CHUNK_SIZE || chunkSize > MAX_CHUNK_SIZE) {
            wipe(core, storedMac, salt);
            throw new IOException("Encrypted media chunk size is invalid");
        }
        if (clearLength < 0L) {
            wipe(core, storedMac, salt);
            throw new IOException("Encrypted media clear length is invalid");
        }

        KeyMaterial keys = deriveKeys(storedId, salt);
        byte[] expectedMac = headerMac(keys.headerMacKey, core);
        boolean authentic = MessageDigest.isEqual(storedMac, expectedMac);
        wipe(core, storedMac, expectedMac);
        if (!authentic) {
            keys.close();
            wipe(salt);
            throw new GeneralSecurityException("Encrypted media header failed authentication");
        }

        long expectedLength = expectedFileLength(clearLength, chunkSize);
        if (input.length() != expectedLength) {
            keys.close();
            wipe(salt);
            throw new IOException("Encrypted media is truncated or has trailing data");
        }

        return new Opened(new Header(chunkSize, clearLength, storedId, salt), keys);
    }

    static void writeHeader(
            RandomAccessFile output,
            UUID mediaId,
            int chunkSize,
            long clearLength,
            byte[] salt,
            byte[] macKey) throws IOException, GeneralSecurityException {
        byte[] core = headerCore(mediaId, chunkSize, clearLength, salt);
        byte[] mac = headerMac(macKey, core);
        try {
            output.seek(0L);
            output.write(core);
            output.write(mac);
        } finally {
            wipe(core, mac);
        }
    }

    static byte[] chunkAad(UUID mediaId, int chunkSize, long chunkIndex, int clearChunkLength) {
        return ByteBuffer.allocate(40)
                .putInt(MAGIC)
                .putInt(VERSION)
                .putLong(mediaId.getMostSignificantBits())
                .putLong(mediaId.getLeastSignificantBits())
                .putInt(chunkSize)
                .putLong(chunkIndex)
                .putInt(clearChunkLength)
                .array();
    }

    static long chunkCount(long clearLength, int chunkSize) {
        return clearLength == 0L ? 0L : ((clearLength - 1L) / chunkSize) + 1L;
    }

    static int clearChunkLength(long clearLength, int chunkSize, long chunkIndex) throws IOException {
        long start;
        try { start = Math.multiplyExact(chunkIndex, (long) chunkSize); }
        catch (ArithmeticException overflow) { throw new IOException("Encrypted media chunk offset overflow", overflow); }
        if (start < 0L || start >= clearLength) throw new EOFException("Encrypted media chunk is outside the file");
        return (int) Math.min((long) chunkSize, clearLength - start);
    }

    static long recordOffset(int chunkSize, long chunkIndex) throws IOException {
        long fullRecord = IV_BYTES + 4L + chunkSize + TAG_BYTES;
        try { return Math.addExact(HEADER_BYTES, Math.multiplyExact(chunkIndex, fullRecord)); }
        catch (ArithmeticException overflow) { throw new IOException("Encrypted media record offset overflow", overflow); }
    }

    static long expectedFileLength(long clearLength, int chunkSize) throws IOException {
        long chunks = chunkCount(clearLength, chunkSize);
        if (chunks == 0L) return HEADER_BYTES;
        long fullBeforeLast = chunks - 1L;
        long fullRecord = IV_BYTES + 4L + chunkSize + TAG_BYTES;
        int lastLength = clearChunkLength(clearLength, chunkSize, fullBeforeLast);
        long lastRecord = IV_BYTES + 4L + lastLength + TAG_BYTES;
        try {
            return Math.addExact(
                    HEADER_BYTES,
                    Math.addExact(Math.multiplyExact(fullBeforeLast, fullRecord), lastRecord));
        } catch (ArithmeticException overflow) {
            throw new IOException("Encrypted media file length overflow", overflow);
        }
    }

    static KeyMaterial deriveKeys(UUID mediaId, byte[] salt) throws GeneralSecurityException {
        byte[] master = VaultSession.requireKeyCopy();
        byte[] prk = null;
        byte[] id = null;
        byte[] t1 = null;
        byte[] t2 = null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            prk = mac.doFinal(master);

            id = ByteBuffer.allocate(16)
                    .putLong(mediaId.getMostSignificantBits())
                    .putLong(mediaId.getLeastSignificantBits())
                    .array();

            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(HKDF_INFO);
            mac.update(id);
            mac.update((byte) 1);
            t1 = mac.doFinal();

            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(t1);
            mac.update(HKDF_INFO);
            mac.update(id);
            mac.update((byte) 2);
            t2 = mac.doFinal();

            return new KeyMaterial(
                    Arrays.copyOf(t1, KEY_BYTES),
                    Arrays.copyOf(t2, KEY_BYTES));
        } finally {
            wipe(master, prk, id, t1, t2);
        }
    }

    private static byte[] headerCore(UUID mediaId, int chunkSize, long clearLength, byte[] salt) {
        if (salt == null || salt.length != SALT_BYTES) throw new IllegalArgumentException("Invalid media salt");
        return ByteBuffer.allocate(HEADER_CORE_BYTES)
                .putInt(MAGIC)
                .putInt(VERSION)
                .putInt(chunkSize)
                .putLong(clearLength)
                .putLong(mediaId.getMostSignificantBits())
                .putLong(mediaId.getLeastSignificantBits())
                .put(salt)
                .array();
    }

    private static byte[] headerMac(byte[] macKey, byte[] core) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
        return mac.doFinal(core);
    }

    static void wipe(byte[]... values) {
        if (values == null) return;
        for (byte[] value : values) if (value != null) Arrays.fill(value, (byte) 0);
    }
}
