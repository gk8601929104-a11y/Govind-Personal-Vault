package com.govind.personalvault.media;

import static androidx.media3.common.C.LENGTH_UNSET;
import static androidx.media3.common.C.RESULT_END_OF_INPUT;
import static androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE;
import static androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;

import com.govind.personalvault.security.VaultSession;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.DestroyFailedException;

/**
 * Media3 DataSource for seekable GVM2 encrypted media.
 *
 * <p>The source keeps at most one bounded clear chunk in memory, reuses its large input/output
 * buffers across seeks, verifies every AES-GCM tag explicitly, and never writes plaintext media to
 * disk. A captured VaultSession epoch is checked before and after authentication and around every
 * copy into Media3's caller buffer.
 */
@OptIn(markerClass = UnstableApi.class)
public final class EncryptedCipherDataSource extends BaseDataSource {
    public static final String URI_SCHEME = "vaultmedia";

    private static final String URI_HOST = "media";
    private static final long NO_SESSION_EPOCH = -1L;
    private static final int CHUNK_AAD_BYTES = 40;

    private final Context appContext;

    private Uri openedUri;
    private RandomAccessFile input;
    private UUID mediaId;
    private MediaFileFormat.Opened opened;

    private long sessionEpoch = NO_SESSION_EPOCH;
    private long clearPosition;
    private long bytesRemaining;

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
    private boolean started;

    public EncryptedCipherDataSource(Context context) {
        super(false);
        appContext = context.getApplicationContext();
    }

    /** Factory suitable for DefaultMediaSourceFactory. */
    public static final class Factory implements DataSource.Factory {
        private final Context appContext;

        public Factory(Context context) {
            appContext = context.getApplicationContext();
        }

        @Override
        public DataSource createDataSource() {
            return new EncryptedCipherDataSource(appContext);
        }
    }

    @Override
    public synchronized long open(DataSpec dataSpec) throws IOException {
        close();
        transferInitializing(dataSpec);

        try {
            openedUri = dataSpec.uri;
            mediaId = parseId(openedUri);

            long openingEpoch = VaultSession.requireEpoch();

            File encryptedFile = MediaFileFormat.finalFile(appContext, mediaId);
            input = new RandomAccessFile(encryptedFile, "r");
            opened = MediaFileFormat.open(input, mediaId);

            if (!VaultSession.isValidEpoch(openingEpoch)) {
                throw new GeneralSecurityException(
                        "Vault session changed while encrypted media was opening");
            }
            sessionEpoch = openingEpoch;

            long requestedPosition = dataSpec.position;
            if (requestedPosition < 0L
                    || requestedPosition > opened.header.clearLength) {
                throw new DataSourceException(
                        "Requested media position is outside the file",
                        ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
            }

            long available = opened.header.clearLength - requestedPosition;
            bytesRemaining = dataSpec.length == LENGTH_UNSET
                    ? available
                    : Math.min(available, dataSpec.length);
            clearPosition = requestedPosition;

            int chunkSize = opened.header.chunkSize;
            encryptedBuffer = new byte[Math.addExact(
                    chunkSize,
                    MediaFileFormat.TAG_BYTES)];
            clearBuffer = new byte[chunkSize];
            ivBuffer = new byte[MediaFileFormat.IV_BYTES];
            aadBuffer = new byte[CHUNK_AAD_BYTES];
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
            contentKeySpec = new SecretKeySpec(opened.keys.contentKey, "AES");

            // These values are needed only while the authenticated header and keys are derived.
            MediaFileFormat.wipe(opened.header.salt, opened.keys.headerMacKey);

            requireLiveSession();

            transferStarted(dataSpec);
            started = true;
            return bytesRemaining;
        } catch (DataSourceException failure) {
            cleanupOpenFailure();
            throw failure;
        } catch (GeneralSecurityException failure) {
            cleanupOpenFailure();
            throw new DataSourceException(
                    "The vault is locked or encrypted media authentication failed",
                    failure,
                    ERROR_CODE_IO_UNSPECIFIED);
        } catch (IOException failure) {
            cleanupOpenFailure();
            throw failure;
        } catch (RuntimeException failure) {
            cleanupOpenFailure();
            throw new DataSourceException(
                    "Encrypted media is invalid",
                    failure,
                    ERROR_CODE_IO_UNSPECIFIED);
        } catch (OutOfMemoryError failure) {
            cleanupOpenFailure();
            throw failure;
        }
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int length)
            throws IOException {
        if (buffer == null) {
            throw new NullPointerException("buffer");
        }
        if (offset < 0 || length < 0 || offset > buffer.length - length) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) {
            return 0;
        }
        if (bytesRemaining == 0L) {
            return RESULT_END_OF_INPUT;
        }
        if (input == null
                || opened == null
                || mediaId == null
                || clearBuffer == null
                || encryptedBuffer == null
                || cipher == null
                || contentKeySpec == null) {
            throw new DataSourceException(
                    "Encrypted media source is not open",
                    ERROR_CODE_IO_UNSPECIFIED);
        }

        int wanted = (int) Math.min((long) length, bytesRemaining);
        int copied = 0;

        try {
            requireLiveSession();

            while (copied < wanted) {
                long chunkIndex = clearPosition / opened.header.chunkSize;
                int offsetInsideChunk =
                        (int) (clearPosition % opened.header.chunkSize);

                if (loadedChunk != chunkIndex || validClearBytes == 0) {
                    loadChunk(chunkIndex);
                }

                cursor = Math.max(cursor, offsetInsideChunk);
                int availableInChunk = limit - cursor;
                if (availableInChunk <= 0) {
                    releaseLoadedChunk();
                    continue;
                }

                int take = Math.min(wanted - copied, availableInChunk);

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
                    throw new DataSourceException(
                            "Vault locked during playback",
                            ERROR_CODE_IO_UNSPECIFIED);
                }

                cursor += take;
                clearPosition += take;
                bytesRemaining -= take;
                copied = copiedIncludingCurrent;

                if (cursor == limit) {
                    releaseLoadedChunk();
                }
            }

            if (!VaultSession.isValidEpoch(sessionEpoch)) {
                Arrays.fill(buffer, offset, offset + copied, (byte) 0);
                throw new DataSourceException(
                        "Vault locked during playback",
                        ERROR_CODE_IO_UNSPECIFIED);
            }

            bytesTransferred(copied);
            return copied;
        } catch (IOException failure) {
            if (copied > 0) {
                Arrays.fill(buffer, offset, offset + copied, (byte) 0);
            }
            invalidateSensitiveState();
            throw failure;
        } catch (RuntimeException failure) {
            if (copied > 0) {
                Arrays.fill(buffer, offset, offset + copied, (byte) 0);
            }
            invalidateSensitiveState();
            throw failure;
        }
    }

    @Override
    public synchronized Uri getUri() {
        return openedUri;
    }

    @Override
    public synchronized void close() throws IOException {
        IOException closeFailure = null;

        wipePlaybackBuffers();

        if (opened != null) {
            opened.close();
            opened = null;
        }

        if (input != null) {
            try {
                input.close();
            } catch (IOException failure) {
                closeFailure = failure;
            }
        }

        input = null;
        mediaId = null;
        openedUri = null;
        sessionEpoch = NO_SESSION_EPOCH;
        clearPosition = 0L;
        bytesRemaining = 0L;

        if (started) {
            started = false;
            transferEnded();
        }

        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void loadChunk(long chunkIndex) throws IOException {
        requireLiveSession();
        releaseLoadedChunk();

        int clearLength = MediaFileFormat.clearChunkLength(
                opened.header.clearLength,
                opened.header.chunkSize,
                chunkIndex);
        int encryptedLength = Math.addExact(
                clearLength,
                MediaFileFormat.TAG_BYTES);
        long recordOffset = MediaFileFormat.recordOffset(
                opened.header.chunkSize,
                chunkIndex);

        try {
            input.seek(recordOffset);
            input.readFully(ivBuffer, 0, MediaFileFormat.IV_BYTES);

            int storedLength = input.readInt();
            if (storedLength != clearLength) {
                throw new IOException(
                        "Encrypted media chunk length is invalid");
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
                throw new IOException(
                        "Decrypted media chunk length is invalid");
            }

            validClearBytes = produced;
            cursor = 0;
            limit = produced;
            loadedChunk = chunkIndex;
        } catch (AEADBadTagException tampered) {
            Arrays.fill(clearBuffer, (byte) 0);
            throw new DataSourceException(
                    "Encrypted media failed authentication",
                    tampered,
                    ERROR_CODE_IO_UNSPECIFIED);
        } catch (GeneralSecurityException failure) {
            Arrays.fill(clearBuffer, (byte) 0);
            throw new DataSourceException(
                    "Encrypted media could not be decrypted",
                    failure,
                    ERROR_CODE_IO_UNSPECIFIED);
        } catch (ArithmeticException failure) {
            Arrays.fill(clearBuffer, (byte) 0);
            throw new DataSourceException(
                    "Encrypted media chunk is invalid",
                    failure,
                    ERROR_CODE_IO_UNSPECIFIED);
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

    private void requireLiveSession() throws DataSourceException {
        if (sessionEpoch == NO_SESSION_EPOCH
                || !VaultSession.isValidEpoch(sessionEpoch)) {
            throw new DataSourceException(
                    "Vault locked during playback",
                    ERROR_CODE_IO_UNSPECIFIED);
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

    /** Wipes every reusable playback buffer and releases the reusable crypto objects. */
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
                // The backing byte[] in MediaFileFormat.Opened is wiped separately below.
            }
            contentKeySpec = null;
        }

        cipher = null;
    }

    private void invalidateSensitiveState() {
        wipePlaybackBuffers();

        if (opened != null) {
            opened.close();
            opened = null;
        }

        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
                // Preserve the original read/decryption failure.
            }
            input = null;
        }

        sessionEpoch = NO_SESSION_EPOCH;
    }

    private void cleanupOpenFailure() {
        wipePlaybackBuffers();

        if (opened != null) {
            opened.close();
            opened = null;
        }

        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
                // Preserve the original open failure.
            }
        }

        input = null;
        mediaId = null;
        openedUri = null;
        sessionEpoch = NO_SESSION_EPOCH;
        clearPosition = 0L;
        bytesRemaining = 0L;

        if (started) {
            started = false;
            transferEnded();
        }
    }

    private static UUID parseId(Uri uri) throws IOException {
        if (uri == null
                || !URI_SCHEME.equals(uri.getScheme())
                || !URI_HOST.equals(uri.getHost())) {
            throw new IOException("Unsupported encrypted media URI");
        }

        List<String> segments = uri.getPathSegments();
        if (segments.size() != 1 || segments.get(0).isEmpty()) {
            throw new IOException("Encrypted media URI has no valid ID");
        }

        try {
            return UUID.fromString(segments.get(0));
        } catch (IllegalArgumentException invalid) {
            throw new IOException(
                    "Encrypted media URI has an invalid ID",
                    invalid);
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
