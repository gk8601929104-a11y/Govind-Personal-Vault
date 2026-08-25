package com.govind.personalvault.media;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.govind.personalvault.security.VaultSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Chunked AES-256-GCM writer. It never loads the complete media file into memory. */
public final class MediaCryptoWriter {
    public interface ProgressListener {
        void onProgress(long clearBytesWritten);
    }

    public static final class Result {
        public final File encryptedFile;
        public final long clearLength;

        Result(File encryptedFile, long clearLength) {
            this.encryptedFile = encryptedFile;
            this.clearLength = clearLength;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private MediaCryptoWriter() { }

    public static Result encrypt(
            Context context,
            Uri source,
            UUID mediaId,
            ProgressListener progress) throws IOException, GeneralSecurityException {
        if (context == null || source == null || mediaId == null) {
            throw new IllegalArgumentException("Media import arguments are missing");
        }
        long sessionEpoch = VaultSession.requireEpoch();

        ContentResolver resolver = context.getContentResolver();
        File part = MediaFileFormat.partFile(context, mediaId);
        File target = MediaFileFormat.finalFile(context, mediaId);
        if (part.exists() && !part.delete()) throw new IOException("Old temporary media file could not be removed");
        if (target.exists()) throw new IOException("Encrypted media ID already exists");

        byte[] salt = new byte[MediaFileFormat.SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] clearChunk = new byte[MediaFileFormat.CHUNK_SIZE];
        byte[] iv = new byte[MediaFileFormat.IV_BYTES];
        long total = 0L;
        boolean completed = false;

        try (MediaFileFormat.KeyMaterial keys = MediaFileFormat.deriveKeys(mediaId, salt);
             InputStream input = resolver.openInputStream(source);
             RandomAccessFile output = new RandomAccessFile(part, "rw")) {
            requireLiveSession(sessionEpoch);
            if (input == null) throw new IOException("Selected media could not be opened");
            output.setLength(0L);
            MediaFileFormat.writeHeader(
                    output,
                    mediaId,
                    MediaFileFormat.CHUNK_SIZE,
                    0L,
                    salt,
                    keys.headerMacKey);
            output.seek(MediaFileFormat.HEADER_BYTES);

            long chunkIndex = 0L;
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Media import was cancelled");
                }
                requireLiveSession(sessionEpoch);

                int read = readChunk(input, clearChunk);
                if (read == 0) break;

                RANDOM.nextBytes(iv);
                output.write(iv);
                output.writeInt(read);

                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.ENCRYPT_MODE,
                        new SecretKeySpec(keys.contentKey, "AES"),
                        new GCMParameterSpec(128, iv));
                byte[] aad = MediaFileFormat.chunkAad(
                        mediaId,
                        MediaFileFormat.CHUNK_SIZE,
                        chunkIndex,
                        read);
                cipher.updateAAD(aad);
                MediaFileFormat.wipe(aad);

                CipherOutputStream encrypted = new CipherOutputStream(
                        new NonClosingRandomAccessOutputStream(output),
                        cipher);
                try {
                    encrypted.write(clearChunk, 0, read);
                } finally {
                    encrypted.close();
                }

                requireLiveSession(sessionEpoch);
                for (int index = 0; index < read; index++) clearChunk[index] = 0;
                total = Math.addExact(total, read);
                chunkIndex++;
                if (progress != null) progress.onProgress(total);
            }

            requireLiveSession(sessionEpoch);
            if (total <= 0L) throw new IOException("Empty media files cannot be imported");
            MediaFileFormat.writeHeader(
                    output,
                    mediaId,
                    MediaFileFormat.CHUNK_SIZE,
                    total,
                    salt,
                    keys.headerMacKey);
            long expected = MediaFileFormat.expectedFileLength(total, MediaFileFormat.CHUNK_SIZE);
            if (output.length() != expected) throw new IOException("Encrypted media length verification failed");
            output.getFD().sync();
            completed = true;
        } catch (ArithmeticException overflow) {
            throw new IOException("Selected media is too large", overflow);
        } finally {
            MediaFileFormat.wipe(salt, clearChunk, iv);
            if (!completed && part.exists()) part.delete();
        }

        moveAtomically(part, target);
        return new Result(target, total);
    }

    private static int readChunk(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total);
            if (read < 0) break;
            if (read == 0) {
                int single = input.read();
                if (single < 0) break;
                buffer[total++] = (byte) single;
            } else {
                total += read;
            }
        }
        return total;
    }

    private static void moveAtomically(File part, File target) throws IOException {
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(part.toPath(), target.toPath());
        }
        try (FileOutputStream directorySync = new FileOutputStream(target, true)) {
            directorySync.getFD().sync();
        }
    }

    private static void requireLiveSession(long expectedEpoch)
            throws GeneralSecurityException {
        if (!VaultSession.isValidEpoch(expectedEpoch)) {
            throw new GeneralSecurityException("Vault locked during media import");
        }
    }

    private static final class NonClosingRandomAccessOutputStream extends OutputStream {
        private final RandomAccessFile output;

        NonClosingRandomAccessOutputStream(RandomAccessFile output) {
            this.output = output;
        }

        @Override public void write(int value) throws IOException {
            output.write(value);
        }

        @Override public void write(byte[] value, int offset, int length) throws IOException {
            output.write(value, offset, length);
        }

        @Override public void close() { }
    }
}
