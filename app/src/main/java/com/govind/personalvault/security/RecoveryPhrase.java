package com.govind.personalvault.security;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RecoveryPhrase {
    private static final Pattern JSON_WORD = Pattern.compile("\\\"([a-z]+)\\\"");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile List<String> cachedWords;

    private RecoveryPhrase() {}

    public static String generate(Context context) throws IOException, GeneralSecurityException {
        byte[] entropy = new byte[16];
        RANDOM.nextBytes(entropy);
        try { return Bip39.toMnemonic(entropy, words(context)); }
        finally { java.util.Arrays.fill(entropy, (byte) 0); }
    }

    public static boolean isValid(Context context, String phrase) {
        try { return Bip39.isValid(phrase, words(context)); }
        catch (IOException unavailable) { return false; }
    }

    public static String normalize(String phrase) { return Bip39.normalize(phrase); }

    private static List<String> words(Context context) throws IOException {
        List<String> result = cachedWords;
        if (result != null) return result;
        synchronized (RecoveryPhrase.class) {
            result = cachedWords;
            if (result != null) return result;
            StringBuilder json = new StringBuilder(30_000);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getApplicationContext().getAssets().open("bip39_english.json"), java.nio.charset.StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) >= 0) json.append(buffer, 0, count);
            }
            ArrayList<String> parsed = new ArrayList<String>(2048);
            Matcher matcher = JSON_WORD.matcher(json);
            while (matcher.find()) parsed.add(matcher.group(1));
            if (parsed.size() != 2048) throw new IOException("Recovery word list is unavailable");
            result = Bip39.immutableCopy(parsed);
            cachedWords = result;
            return result;
        }
    }
}
