package com.govind.personalvault;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.govind.personalvault.security.VaultSession;
import com.govind.personalvault.ui.Ui;

/** Locks the process-memory VMK whenever the task genuinely enters the background. */
public final class VaultApp extends Application implements Application.ActivityLifecycleCallbacks {
    private int startedActivities;
    private static volatile boolean foreground;

    public static boolean isInForeground() { return foreground; }

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        Ui.applyTheme(VaultPrefs.isLight(this));
    }

    @Override public synchronized void onActivityStarted(Activity activity) { startedActivities++; foreground=true; }

    @Override public synchronized void onActivityStopped(Activity activity) {
        startedActivities = Math.max(0, startedActivities - 1);
        if (startedActivities == 0 && !activity.isChangingConfigurations()) {
            foreground=false;
            BaseActivity.clearSensitiveClipboard(this);
            BaseActivity.cancelIdleTimer();
            VaultSession.lock();
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityResumed(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
