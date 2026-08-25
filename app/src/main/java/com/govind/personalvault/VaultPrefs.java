package com.govind.personalvault;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Thin wrapper around SharedPreferences for non-sensitive settings.
 * Sensitive material never lives here.
 */
public final class VaultPrefs {
    private static final String PREFS = "vault_prefs";
    private final SharedPreferences sp;

    public VaultPrefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean getBoolean(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    public void putBoolean(String key, boolean value) {
        sp.edit().putBoolean(key, value).apply();
    }

    public int getInt(String key, int def) {
        return sp.getInt(key, def);
    }

    public void putInt(String key, int value) {
        sp.edit().putInt(key, value).apply();
    }

    public String getString(String key, String def) {
        return sp.getString(key, def);
    }

    public void putString(String key, String value) {
        sp.edit().putString(key, value).apply();
    }
}
