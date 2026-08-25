package com.govind.personalvault;

import android.content.Context;
import android.content.SharedPreferences;

/** Local UI preferences. These never hold secrets or vault keys. */
public final class VaultPrefs {
    private static final String PREFS = "vault_ui_v1";
    public static final long CLIPBOARD_MS_DEFAULT = 30_000L;
    public static final long[] CLIPBOARD_OPTIONS_MS = {10_000L, 30_000L, 60_000L};
    public static final long[] AUTOLOCK_OPTIONS_MS = {60_000L, 5 * 60_000L, 15 * 60_000L};

    private VaultPrefs() { }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isLight(Context context) {
        return prefs(context).getBoolean("light", false);
    }

    public static void setLight(Context context, boolean light) {
        prefs(context).edit().putBoolean("light", light).apply();
    }

    public static long clipboardMs(Context context) {
        long value = prefs(context).getLong("clipboard_ms", CLIPBOARD_MS_DEFAULT);
        if (value < 5_000L) value = CLIPBOARD_MS_DEFAULT;
        return Math.min(120_000L, value);
    }

    public static void setClipboardMs(Context context, long ms) {
        prefs(context).edit().putLong("clipboard_ms", ms).apply();
    }

    public static long autolockMs(Context context) {
        return Math.max(0L, prefs(context).getLong("autolock_ms", 5 * 60_000L));
    }

    public static void setAutolockMs(Context context, long ms) {
        prefs(context).edit().putLong("autolock_ms", Math.max(0L, ms)).apply();
    }

    public static String clipboardLabel(long ms) {
        return (ms / 1000L) + " seconds";
    }

    public static String autolockLabel(long ms) {
        if (ms <= 0L) return "When app leaves";
        if (ms < 120_000L) return (ms / 1000L) + " seconds";
        return (ms / 60_000L) + " minutes";
    }
}
