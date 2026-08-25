package com.govind.personalvault.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Small, versioned AES-GCM envelope used by both records and wrapped keys. */
public final class CryptoBox {
    public static final int KEY_BYTES = 32;
    public static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String VERSION = "v1";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final class Parts {
        public final byte[] iv;
        public final byte[] ciphertext;

        private Parts(byte[] iv, byte[] ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
    }

    private CryptoBox() {}

    public static String encrypt(byte[] key, String plaintext, String aad) throws GeneralSecurityException {
        byte[] clear = plaintext == null ? new byte[0] : plaintext.getBytes(StandardCharsets.UTF_8);
        try { return encrypt(key, clear, utf8(aad)); }
        finally { Arrays.fill(clear, (byte) 0); }
    }

    public static String encrypt(byte[] key, byte[] plaintext, byte[] aad) throws GeneralSecurityException {
        requireKey(key);
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        if (aad != null && aad.length > 0) cipher.updateAAD(aad);
        byte[] encrypted = cipher.doFinal(plaintext == null ? new byte[0] : plaintext);
        try { return encode(iv, encrypted); }
        finally { Arrays.fill(encrypted, (byte) 0); }
    }

    public static String decryptText(byte[] key, String envelope, String aad) throws GeneralSecurityException {
        byte[] clear = decrypt(key, envelope, utf8(aad));
        try { return new String(clear, StandardCharsets.UTF_8); }
        finally { Arrays.fill(clear, (byte) 0); }
    }

    public static byte[] decrypt(byte[] key, String envelope, byte[] aad) throws GeneralSecurityException {
        requireKey(key);
        Parts parts = parse(envelope);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, parts.iv));
        if (aad != null && aad.length > 0) cipher.updateAAD(aad);
        try { return cipher.doFinal(parts.ciphertext); }
        finally {
            Arrays.fill(parts.iv, (byte) 0);
            Arrays.fill(parts.ciphertext, (byte) 0);
        }
    }

    public static String encode(byte[] iv, byte[] ciphertext) throws GeneralSecurityException {
        if (iv == null || iv.length != IV_BYTES || ciphertext == null || ciphertext.length < 16) {
            throw new GeneralSecurityException("Invalid encrypted envelope");
        }
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return VERSION + ":" + encoder.encodeToString(iv) + ":" + encoder.encodeToString(ciphertext);
    }

    public static Parts parse(String envelope) throws GeneralSecurityException {
        if (envelope == null) throw new GeneralSecurityException("Missing encrypted envelope");
        String[] pieces = envelope.split(":", -1);
        if (pieces.length != 3 || !VERSION.equals(pieces[0])) throw new GeneralSecurityException("Unsupported encrypted envelope");
        try {
            byte[] iv = Base64.getUrlDecoder().decode(pieces[1]);
            byte[] ciphertext = Base64.getUrlDecoder().decode(pieces[2]);
            if (iv.length != IV_BYTES || ciphertext.length < 16) throw new GeneralSecurityException("Invalid encrypted envelope");
            return new Parts(iv, ciphertext);
        } catch (IllegalArgumentException malformed) {
            throw new GeneralSecurityException("Invalid encrypted envelope", malformed);
        }
    }

    private static byte[] utf8(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    private static void requireKey(byte[] key) throws GeneralSecurityException {
        if (key == null || key.length != KEY_BYTES) throw new GeneralSecurityException("A 256-bit key is required");
    }
}
