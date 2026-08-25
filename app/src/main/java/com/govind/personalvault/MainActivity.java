package com.govind.personalvault;

import android.content.Intent;
import android.os.Bundle;

import com.govind.personalvault.security.SecurityManager;
import com.govind.personalvault.security.VaultSession;

public final class MainActivity extends BaseActivity {
    @Override protected boolean requiresUnlockedVault() { return false; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Class<?> target;
            if (!SecurityManager.get(this).isSetUp()) target=SetupActivity.class;
            else if (!VaultSession.isUnlocked()) target=LockActivity.class;
            else target=VaultActivity.class;
            startActivity(new Intent(this,target));
            finish();
        } catch(Throwable failure){StartupGuard.show(this,failure);}
    }
}
