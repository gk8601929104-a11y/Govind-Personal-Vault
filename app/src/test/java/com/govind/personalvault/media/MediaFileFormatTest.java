package com.govind.personalvault.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class MediaFileFormatTest {
    @Test public void chunkMathIsStable() throws Exception {
        int chunk = MediaFileFormat.CHUNK_SIZE;
        assertEquals(0L, MediaFileFormat.chunkCount(0L, chunk));
        assertEquals(1L, MediaFileFormat.chunkCount(1L, chunk));
        assertEquals(1L, MediaFileFormat.chunkCount(chunk, chunk));
        assertEquals(2L, MediaFileFormat.chunkCount(chunk + 1L, chunk));
        assertEquals(chunk, MediaFileFormat.clearChunkLength(chunk + 9L, chunk, 0L));
        assertEquals(9, MediaFileFormat.clearChunkLength(chunk + 9L, chunk, 1L));
        assertEquals(
                MediaFileFormat.HEADER_BYTES + MediaFileFormat.IV_BYTES + 4L + 9L + MediaFileFormat.TAG_BYTES,
                MediaFileFormat.expectedFileLength(9L, chunk));
    }

    @Test public void chunkAadBindsIdentityAndOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        byte[] a = MediaFileFormat.chunkAad(first, MediaFileFormat.CHUNK_SIZE, 3L, 100);
        byte[] b = MediaFileFormat.chunkAad(first, MediaFileFormat.CHUNK_SIZE, 4L, 100);
        byte[] c = MediaFileFormat.chunkAad(second, MediaFileFormat.CHUNK_SIZE, 3L, 100);
        assertFalse(java.util.Arrays.equals(a, b));
        assertFalse(java.util.Arrays.equals(a, c));
        assertEquals(40, a.length);
    }

    @Test public void independentGcmChunkRoundTripAndTamperFailure() throws Exception {
        byte[] key = new byte[32];
        byte[] iv = new byte[12];
        byte[] clear = new byte[4096];
        new SecureRandom().nextBytes(key);
        new SecureRandom().nextBytes(iv);
        new SecureRandom().nextBytes(clear);
        UUID id = UUID.randomUUID();
        byte[] aad = MediaFileFormat.chunkAad(id, MediaFileFormat.CHUNK_SIZE, 0L, clear.length);

        Cipher encrypt = Cipher.getInstance("AES/GCM/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        encrypt.updateAAD(aad);
        byte[] encrypted = encrypt.doFinal(clear);

        Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        decrypt.updateAAD(aad);
        assertArrayEquals(clear, decrypt.doFinal(encrypted));

        encrypted[encrypted.length - 1] ^= 1;
        Cipher tampered = Cipher.getInstance("AES/GCM/NoPadding");
        tampered.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        tampered.updateAAD(aad);
        boolean failed = false;
        try { tampered.doFinal(encrypted); }
        catch (javax.crypto.AEADBadTagException expected) { failed = true; }
        assertEquals(true, failed);

        MediaFileFormat.wipe(key, iv, clear, aad, encrypted);
    }
}
