package com.govind.personalvault.security;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SecurityCoreTest {
    private SecurityCoreTest() {}

    public static void main(String[] args) throws Exception {
        testAuthenticatedEncryption();
        testBip39RoundTrip();
        System.out.println("PASS: AES-GCM tamper/AAD tests and BIP39 checksum tests");
    }

    private static void testAuthenticatedEncryption() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 7 + 3);
        String first = CryptoBox.encrypt(key, "private value", "item|one|secret");
        String second = CryptoBox.encrypt(key, "private value", "item|one|secret");
        check(!first.equals(second), "fresh IV required");
        check("private value".equals(CryptoBox.decryptText(key, first, "item|one|secret")), "round trip");
        expectFailure(() -> CryptoBox.decryptText(key, first, "item|two|secret"), "AAD mismatch must fail");
        CryptoBox.Parts parts = CryptoBox.parse(first);
        parts.ciphertext[parts.ciphertext.length - 1] ^= 1;
        String tampered = CryptoBox.encode(parts.iv, parts.ciphertext);
        expectFailure(() -> CryptoBox.decryptText(key, tampered, "item|one|secret"), "tamper must fail");
        Arrays.fill(key, (byte) 0);
    }

    private static void testBip39RoundTrip() throws Exception {
        List<String> words = new ArrayList<String>(2048);
        for (int i = 0; i < 2048; i++) words.add(String.format(java.util.Locale.ROOT, "word%04d", i));
        byte[] entropy = new byte[16];
        for (int i = 0; i < entropy.length; i++) entropy[i] = (byte) (i * 13 + 5);
        String phrase = Bip39.toMnemonic(entropy, words);
        check(Bip39.isValid(phrase, words), "valid checksum");
        String[] changed = Bip39.split(phrase);
        changed[11] = words.get((words.indexOf(changed[11]) + 1) % words.size());
        check(!Bip39.isValid(join(changed), words), "changed checksum must fail");
    }

    private static String join(String[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) { if (i > 0) out.append(' '); out.append(values[i]); }
        return out.toString();
    }

    private static void expectFailure(Throwing action, String message) throws Exception {
        try { action.run(); }
        catch (GeneralSecurityException expected) { return; }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private interface Throwing { void run() throws Exception; }
}
