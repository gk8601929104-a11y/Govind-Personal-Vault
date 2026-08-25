package com.govind.personalvault;

import android.os.Bundle;

/**
 * Entry point. Immediately hands off to StartupGuard which decides
 * whether to show Setup, Lock, or the main vault.
 */
public class MainActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StartupGuard.start(this);
        finish();
    }
}
