package com.govind.personalvault;

import android.app.Activity;
import android.content.Intent;

import com.govind.personalvault.security.SecurityManager;

/**
 * Decides the first screen after cold start.
 */
public final class StartupGuard {
    private StartupGuard() {}

    public static void start(Activity from) {
        SecurityManager sm = SecurityManager.getInstance(from);
        Intent next;
        if (!sm.isVaultInitialized()) {
            next = new Intent(from, SetupActivity.class);
        } else if (!sm.isUnlocked()) {
            next = new Intent(from, LockActivity.class);
        } else {
            next = new Intent(from, VaultActivity.class);
        }
        from.startActivity(next);
    }
}
