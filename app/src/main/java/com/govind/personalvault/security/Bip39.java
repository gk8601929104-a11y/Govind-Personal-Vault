package com.govind.personalvault.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** The 128-bit entropy / 12-word subset of BIP39 used for local vault recovery. */
public final class Bip39 {
    private static final int ENTROPY_BYTES = 16;
    private static final int WORD_COUNT = 12;
    private static final int WORD_LIST_SIZE = 2048;

    private Bip39() {}

    public static String toMnemonic(byte[] entropy, List<String> words) throws GeneralSecurityException {
        requireInputs(entropy, words);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(entropy);
        StringBuilder phrase = new StringBuilder();
        for (int word = 0; word < WORD_COUNT; word++) {
            int index = 0;
            for (int bit = 0; bit < 11; bit++) {
                int globalBit = word * 11 + bit;
                index = (index << 1) | bitAt(entropy, digest, globalBit);
            }
            if (word > 0) phrase.append(' ');
            phrase.append(words.get(index));
        }
        Arrays.fill(digest, (byte) 0);
        return normalize(phrase.toString());
    }

    public static boolean isValid(String phrase, List<String> words) {
        if (words == null || words.size() != WORD_LIST_SIZE) return false;
        String[] entered = split(phrase);
        if (entered.length != WORD_COUNT) return false;
        byte[] entropy = new byte[ENTROPY_BYTES];
        int suppliedChecksum = 0;
        for (int word = 0; word < WORD_COUNT; word++) {
            int index = words.indexOf(entered[word]);
            if (index < 0) return false;
            for (int bit = 0; bit < 11; bit++) {
                int value = (index >>> (10 - bit)) & 1;
                int globalBit = word * 11 + bit;
                if (globalBit < 128) {
                    if (value == 1) entropy[globalBit / 8] |= (byte) (1 << (7 - (globalBit % 8)));
                } else {
                    suppliedChecksum = (suppliedChecksum << 1) | value;
                }
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(entropy);
            int expected = (digest[0] & 0xff) >>> 4;
            Arrays.fill(digest, (byte) 0);
            return suppliedChecksum == expected;
        } catch (GeneralSecurityException unavailable) {
            return false;
        } finally {
            Arrays.fill(entropy, (byte) 0);
        }
    }

    public static String normalize(String phrase) {
        if (phrase == null) return "";
        String normalized = Normalizer.normalize(phrase, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
        return normalized;
    }

    public static String[] split(String phrase) {
        String normalized = normalize(phrase);
        return normalized.isEmpty() ? new String[0] : normalized.split(" ");
    }

    public static List<String> immutableCopy(List<String> words) {
        return java.util.Collections.unmodifiableList(new ArrayList<String>(words));
    }

    private static int bitAt(byte[] entropy, byte[] digest, int globalBit) {
        if (globalBit < 128) return (entropy[globalBit / 8] >>> (7 - (globalBit % 8))) & 1;
        int checksumBit = globalBit - 128;
        return (digest[0] >>> (7 - checksumBit)) & 1;
    }

    private static void requireInputs(byte[] entropy, List<String> words) throws GeneralSecurityException {
        if (entropy == null || entropy.length != ENTROPY_BYTES) throw new GeneralSecurityException("128 bits of entropy are required");
        if (words == null || words.size() != WORD_LIST_SIZE) throw new GeneralSecurityException("A 2048-word recovery list is required");
    }
}
