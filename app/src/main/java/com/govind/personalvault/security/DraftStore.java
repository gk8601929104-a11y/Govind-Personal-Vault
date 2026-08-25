package com.govind.personalvault.security;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Encrypted, bounded editor draft storage; no plaintext is placed in activity Bundles. */
public final class DraftStore {
    private static final String PREFS = "vault_editor_drafts_v1";

    private DraftStore() {}

    public static void save(Context context, String key, Map<String, String> fields) throws GeneralSecurityException {
        JSONObject json = new JSONObject();
        try {
            for (Map.Entry<String, String> entry : fields.entrySet()) json.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            json.put("saved_at", System.currentTimeMillis());
        } catch (JSONException impossible) { throw new GeneralSecurityException("Draft could not be prepared", impossible); }
        String encrypted = SecurityManager.get(context).encryptText("draft|" + key + "|v1", json.toString());
        preferences(context).edit().putString(key, encrypted).apply();
    }

    public static Map<String, String> load(Context context, String key) throws GeneralSecurityException {
        String encrypted = preferences(context).getString(key, null);
        HashMap<String, String> result = new HashMap<String, String>();
        if (encrypted == null) return result;
        String clear = SecurityManager.get(context).decryptText("draft|" + key + "|v1", encrypted);
        try {
            JSONObject json = new JSONObject(clear);
            Iterator<String> names = json.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (!"saved_at".equals(name)) result.put(name, json.optString(name, ""));
            }
            return result;
        } catch (JSONException damaged) { throw new GeneralSecurityException("Saved draft is damaged", damaged); }
    }

    public static void clear(Context context, String key) { preferences(context).edit().remove(key).apply(); }
    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
