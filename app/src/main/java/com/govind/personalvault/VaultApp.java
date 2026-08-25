package com.govind.personalvault;

import android.app.Application;

import com.govind.personalvault.security.SecurityManager;

public class VaultApp extends Application {
    private static VaultApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        SecurityManager.getInstance(this);
    }

    public static VaultApp get() {
        return instance;
    }
}
