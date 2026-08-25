package com.govind.personalvault.media;

import android.content.Context;

import com.govind.personalvault.security.VaultSession;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.DestroyFailedException;

/**
 * Sequential authenticated plaintext stream for image decoding and MediaStore export.
 *
 * <p>The stream is bound to one VaultSession epoch, keeps only one bounded clear chunk in memory,
 * reuses its large buffers, verifies every AES-GCM tag explicitly, and fails closed if the vault is
 * locked or re-unlocked while a read is in progress.
 */
public final class EncryptedMediaInputStream extends InputStream {
    private static final long NO_SESSION_EPOCH = -1L;
    private static final int CHUNK_AAD_BYTES = 40;

    private final RandomAccessFile input;
    private final UUID mediaId;
    private final MediaFileFormat.Opened opened;
    private final byte[] singleByte = new byte[1];

    private long sessionEpoch = NO_SESSION_EPOCH;
    private long clearPosition;

    private Cipher cipher;
    private SecretKeySpec contentKeySpec;
    private byte[] encryptedBuffer;
    private byte[] clearBuffer;
    private byte[] ivBuffer;
    private byte[] aadBuffer;

    private int cursor;
    private int limit;
    private int validClearBytes;
    private long loadedChunk = -1L;
    private boolean closed;

    public static EncryptedMediaInputStream open(Context context, String id)
            throws IOException, GeneralSecurityException {
        UUID mediaId;
        try {
            mediaId = UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid media identifier", invalid);
        }

        long openingEpoch = VaultSession.requireEpoch();
        File file = MediaFileFormat.finalFile(context, mediaId);
        RandomAccessFile input = new RandomAccessFile(file, "r");
        MediaFileFormat.Opened opened = null;

        try {
            opened = MediaFileFormat.open(input, mediaId);
            if (!VaultSession.isValidEpoch(openingEpoch)) {
                throw new GeneralSecurityException(
                        "Vault session changed while encrypted media was opening");
            }

            return new EncryptedMediaInputStream(
                    input,
                    mediaId,
                    opened,
                    openingEpoch);
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            if (opened != null) {
                opened.close();
            }
            try {
                input.close();
            } catch (IOException ignored) {
                // Preserve the original open failure.
            }
            throw failure;
        }
    }

    private EncryptedMediaInputStream(
            RandomAccessFile input,
            UUID mediaId,
            MediaFileFormat.Opened opened,
            long sessionEpoch) throws IOException, GeneralSecurityException {
        this.input = input;
        this.mediaId = mediaId;
        this.opened = opened;
        this.sessionEpoch = sessionEpoch;

        int chunkSize = opened.header.chunkSize;
        encryptedBuffer = new byte[Math.addExact(chunkSize, MediaFileFormat.TAG_BYTES)];
        clearBuffer = new byte[chunkSize];
        ivBuffer = new byte[MediaFileFormat.IV_BYTES];
        aadBuffer = new byte[CHUNK_AAD_BYTES];
        cipher = Cipher.getInstance("AES/GCM/NoPadding");
        contentKeySpec = new SecretKeySpec(opened.keys.contentKey, "AES");

        // These values are no longer needed after header authentication and key setup.
        MediaFileFormat.wipe(opened.header.salt, opened.keys.headerMacKey);
        requireLiveSession();
    }

    public synchronized long clearLength() throws IOException {
        ensureOpen();
        return opened.header.clearLength;
    }

    @Override
    public synchronized int read() throws IOException {
        int result = read(singleByte, 0, 1);
        if (result < 0) {
            return -1;
        }
        int value = singleByte[0] & 0xff;
        singleByte[0] = 0;
        return value;
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int length) throws IOException {
        ensureOpen();
        if (buffer == null) {
            throw new NullPointerException("buffer");
        }
        if (offset < 0 || length < 0 || offset > buffer.length - length) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) {
            return 0;
        }
        if (clearPosition >= opened.header.clearLength) {
            return -1;
        }

        requireLiveSession();

        int wanted = (int) Math.min(
                (long) length,
                opened.header.clearLength - clearPosition);
        int copied = 0;

        try {
            while (copied < wanted) {
                long chunkIndex = clearPosition / opened.header.chunkSize;
                int inside = (int) (clearPosition % opened.header.chunkSize);

                if (loadedChunk != chunkIndex || validClearBytes == 0) {
                    loadChunk(chunkIndex);
                }

                cursor = Math.max(cursor, inside);
                int available = limit - cursor;
                if (available <= 0) {
                    releaseLoadedChunk();
                    continue;
                }

                int take = Math.min(wanted - copied, available);
                requireLiveSession();

                System.arraycopy(
                        clearBuffer,
                        cursor,
                        buffer,
                        offset + copied,
                        take);

                int copiedIncludingCurrent = copied + take;
                if (!VaultSession.isValidEpoch(sessionEpoch)) {
                    Arrays.fill(
                            buffer,
                            offset,
                            offset + copiedIncludingCurrent,
                            (byte) 0);
                    throw new IOException("Vault locked during media read");
                }

                cursor += take;
                clearPosition += take;
                copied = copiedIncludingCurrent;

                if (cursor == limit) {
                    releaseLoadedChunk();
                }
            }

            if (!VaultSession.isValidEpoch(sessionEpoch)) {
                Arrays.fill(buffer, offset, offset + copied, (byte) 0);
                throw new IOException("Vault locked during media read");
            }

            return copied;
        } catch (IOException | RuntimeException failure) {
            if (copied > 0) {
                Arrays.fill(buffer, offset, offset + copied, (byte) 0);
            }
            invalidateSensitiveState();
            throw failure;
        }
    }

    @Override
    public synchronized long skip(long count) throws IOException {
        ensureOpen();
        requireLiveSession();
        if (count <= 0L) {
            return 0L;
        }

        long skipped = Math.min(
                count,
                opened.header.clearLength - clearPosition);
        clearPosition += skipped;
        releaseLoadedChunk();
        requireLiveSession();
        return skipped;
    }

    @Override
    public synchronized int available() throws IOException {
        ensureOpen();
        requireLiveSession();
        return (int) Math.min(
                Integer.MAX_VALUE,
                opened.header.clearLength - clearPosition);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        wipePlaybackBuffers();
        opened.close();
        MediaFileFormat.wipe(singleByte);
        sessionEpoch = NO_SESSION_EPOCH;
        input.close();
    }

    private void loadChunk(long chunkIndex) throws IOException {
        requireLiveSession();
        releaseLoadedChunk();

        int clearLength = MediaFileFormat.clearChunkLength(
                opened.header.clearLength,
                opened.header.chunkSize,
                chunkIndex);
        int encryptedLength = Math.addExact(clearLength, MediaFileFormat.TAG_BYTES);
        long recordOffset = MediaFileFormat.recordOffset(
                opened.header.chunkSize,
                chunkIndex);

        try {
            input.seek(recordOffset);
            input.readFully(ivBuffer, 0, MediaFileFormat.IV_BYTES);

            int storedLength = input.readInt();
            if (storedLength != clearLength) {
                throw new IOException("Encrypted media chunk length is invalid");
            }

            input.readFully(encryptedBuffer, 0, encryptedLength);

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    contentKeySpec,
                    new GCMParameterSpec(128, ivBuffer));

            writeChunkAad(
                    aadBuffer,
                    mediaId,
                    opened.header.chunkSize,
                    chunkIndex,
                    storedLength);
            cipher.updateAAD(aadBuffer);

            int produced = cipher.doFinal(
                    encryptedBuffer,
                    0,
                    encryptedLength,
                    clearBuffer,
                    0);

            requireLiveSession();

            if (produced != storedLength) {
                Arrays.fill(clearBuffer, (byte) 0);
                throw new IOException("Decrypted media chunk length is invalid");
            }

            validClearBytes = produced;
            cursor = 0;
            limit = produced;
            loadedChunk = chunkIndex;
        } catch (AEADBadTagException tampered) {
            Arrays.fill(clearBuffer, (byte) 0);
            throw new IOException("Encrypted media failed authentication", tampered);
        } catch (GeneralSecurityException failure) {
            Arrays.fill(clearBuffer, (byte) 0);
            throw new IOException("Encrypted media could not be decrypted", failure);
        } catch (ArithmeticException failure) {
            Arrays.fill(clearBuffer, (byte) 0);
            throw new IOException("Encrypted media chunk is invalid", failure);
        } finally {
            if (encryptedBuffer != null) {
                Arrays.fill(
                        encryptedBuffer,
                        0,
                        Math.min(encryptedLength, encryptedBuffer.length),
                        (byte) 0);
            }
            if (ivBuffer != null) {
                Arrays.fill(ivBuffer, (byte) 0);
            }
            if (aadBuffer != null) {
                Arrays.fill(aadBuffer, (byte) 0);
            }
        }
    }

    private void requireLiveSession() throws IOException {
        if (sessionEpoch == NO_SESSION_EPOCH
                || !VaultSession.isValidEpoch(sessionEpoch)) {
            throw new IOException("Vault locked during media read");
        }
    }

    private void releaseLoadedChunk() {
        if (clearBuffer != null && validClearBytes > 0) {
            Arrays.fill(
                    clearBuffer,
                    0,
                    Math.min(validClearBytes, clearBuffer.length),
                    (byte) 0);
        }
        validClearBytes = 0;
        cursor = 0;
        limit = 0;
        loadedChunk = -1L;
    }

    private void wipePlaybackBuffers() {
        releaseLoadedChunk();
        MediaFileFormat.wipe(
                clearBuffer,
                encryptedBuffer,
                ivBuffer,
                aadBuffer);
        clearBuffer = null;
        encryptedBuffer = null;
        ivBuffer = null;
        aadBuffer = null;

        if (contentKeySpec != null) {
            try {
                contentKeySpec.destroy();
            } catch (DestroyFailedException | RuntimeException ignored) {
                // opened.close() independently wipes the original content-key array.
            }
            contentKeySpec = null;
        }
        cipher = null;
    }

    private void invalidateSensitiveState() {
        wipePlaybackBuffers();
        sessionEpoch = NO_SESSION_EPOCH;
        try {
            opened.close();
        } catch (RuntimeException ignored) {
            // Best-effort wipe while preserving the original failure.
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // Preserve the original failure.
        }
        closed = true;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Encrypted media stream is closed");
        }
    }

    private static void writeChunkAad(
            byte[] output,
            UUID id,
            int chunkSize,
            long chunkIndex,
            int clearChunkLength) {
        if (output == null || output.length != CHUNK_AAD_BYTES) {
            throw new IllegalArgumentException("Invalid media AAD buffer");
        }

        int position = 0;
        position = putInt(output, position, MediaFileFormat.MAGIC);
        position = putInt(output, position, MediaFileFormat.VERSION);
        position = putLong(output, position, id.getMostSignificantBits());
        position = putLong(output, position, id.getLeastSignificantBits());
        position = putInt(output, position, chunkSize);
        position = putLong(output, position, chunkIndex);
        putInt(output, position, clearChunkLength);
    }

    private static int putInt(byte[] output, int offset, int value) {
        output[offset] = (byte) (value >>> 24);
        output[offset + 1] = (byte) (value >>> 16);
        output[offset + 2] = (byte) (value >>> 8);
        output[offset + 3] = (byte) value;
        return offset + Integer.BYTES;
    }

    private static int putLong(byte[] output, int offset, long value) {
        output[offset] = (byte) (value >>> 56);
        output[offset + 1] = (byte) (value >>> 48);
        output[offset + 2] = (byte) (value >>> 40);
        output[offset + 3] = (byte) (value >>> 32);
        output[offset + 4] = (byte) (value >>> 24);
        output[offset + 5] = (byte) (value >>> 16);
        output[offset + 6] = (byte) (value >>> 8);
        output[offset + 7] = (byte) value;
        return offset + Long.BYTES;
    }
}
