package com.govind.personalvault;

import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Base for all activities. Handles screenshot protection flag and
 * common lifecycle hooks used by the vault session.
 */
public abstract class BaseActivity extends AppCompatActivity {

    /** Temporarily false for QA screenshots. Set true before final release. */
    public static final boolean BLOCK_SCREENSHOTS = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BLOCK_SCREENSHOTS) {
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Session timeout / auto-lock checks can be added here.
    }
}
