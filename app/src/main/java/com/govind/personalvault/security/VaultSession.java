package com.govind.personalvault.security;

import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Process-memory-only holder for the unlocked vault master key.
 *
 * <p>The session epoch changes on every successful unlock and every lock. Long-running work captures
 * the epoch at start and must reject its key material as soon as that epoch is no longer current.
 */
public final class VaultSession {
    private static byte[] key;
    private static long epoch;

    private VaultSession() { }

    /**
     * Starts a new unlocked session using a defensive copy of {@code masterKey}.
     *
     * <p>Any key from an older session is wiped before it becomes unreachable. The caller remains
     * responsible for wiping its own {@code masterKey} array.
     */
    public static synchronized void unlock(byte[] masterKey)
            throws GeneralSecurityException {
        if (masterKey == null || masterKey.length != CryptoBox.KEY_BYTES) {
            throw new GeneralSecurityException("Invalid vault key");
        }

        if (epoch == Long.MAX_VALUE) {
            wipeCurrentKey();
            throw new GeneralSecurityException(
                    "Vault session counter exhausted; restart the app process");
        }

        byte[] replacement = Arrays.copyOf(masterKey, masterKey.length);
        wipeCurrentKey();
        key = replacement;
        epoch++;
    }

    /** Returns whether a master key is currently held in process memory. */
    public static synchronized boolean isUnlocked() {
        return key != null;
    }

    /** Returns a defensive copy of the current master key. */
    public static synchronized byte[] requireKeyCopy()
            throws GeneralSecurityException {
        if (key == null) {
            throw new GeneralSecurityException("Vault is locked");
        }
        return Arrays.copyOf(key, key.length);
    }

    /**
     * Returns a defensive key copy only when {@code expectedEpoch} is still the active session.
     */
    public static synchronized byte[] requireKeyCopy(long expectedEpoch)
            throws GeneralSecurityException {
        if (key == null || epoch != expectedEpoch) {
            throw new GeneralSecurityException("Vault session is no longer valid");
        }
        return Arrays.copyOf(key, key.length);
    }

    /** Returns the active epoch, failing when the vault is locked. */
    public static synchronized long requireEpoch()
            throws GeneralSecurityException {
        if (key == null) {
            throw new GeneralSecurityException("Vault is locked");
        }
        return epoch;
    }

    /**
     * Returns true only when the vault is unlocked and {@code expectedEpoch} is still current.
     */
    public static synchronized boolean isValidEpoch(long expectedEpoch) {
        return key != null && epoch == expectedEpoch;
    }

    /**
     * Wipes the in-memory master key and invalidates all work started by the previous session.
     */
    public static synchronized void lock() {
        wipeCurrentKey();
        if (epoch != Long.MAX_VALUE) {
            epoch++;
        }
    }

    /**
     * Locks only if the supplied epoch is still active.
     *
     * <p>This prevents a delayed callback from an old activity or worker from locking a newer
     * session that was unlocked after that callback was scheduled.
     */
    public static synchronized boolean lockIfEpoch(long expectedEpoch) {
        if (key == null || epoch != expectedEpoch) {
            return false;
        }
        wipeCurrentKey();
        if (epoch != Long.MAX_VALUE) {
            epoch++;
        }
        return true;
    }

    private static void wipeCurrentKey() {
        if (key != null) {
            Arrays.fill(key, (byte) 0);
            key = null;
        }
    }
}
