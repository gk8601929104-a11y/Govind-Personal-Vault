package com.govind.personalvault.security;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serialized worker for expensive KDF and Keystore operations. */
public final class SecureWork {
    private static final String TAG = "VaultSecureWork";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vault-security");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback<T> { void onComplete(T result, Exception error); }

    public static final class Task {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Future<?> future;
        private synchronized void attach(Future<?> value) { future = value; if (cancelled.get()) value.cancel(false); }
        public synchronized void cancel() { cancelled.set(true); if (future != null) future.cancel(false); }
        private boolean isCancelled() { return cancelled.get(); }
    }

    private SecureWork() {}

    public static <T> Task submit(Callable<T> operation, Callback<T> callback) {
        Task task = new Task();
        task.attach(EXECUTOR.submit(() -> {
            if (task.isCancelled()) return;
            T result = null;
            Exception error = null;
            try { result = operation.call(); }
            catch (Exception failure) { error = failure; }
            if (task.isCancelled()) return;
            T delivered = result;
            Exception failure = error;
            MAIN.post(() -> {
                if (task.isCancelled()) return;
                try { if (callback != null) callback.onComplete(delivered, failure); }
                catch (RuntimeException callbackError) { Log.e(TAG, "Security callback failed", callbackError); }
            });
        }));
        return task;
    }
}
