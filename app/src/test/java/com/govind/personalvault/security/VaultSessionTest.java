package com.govind.personalvault.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.Arrays;

public final class VaultSessionTest {
    @After
    public void tearDown() {
        VaultSession.lock();
    }

    @Test
    public void lockInvalidatesCapturedEpoch() throws Exception {
        byte[] key = key((byte) 3);
        try {
            VaultSession.unlock(key);
            long epoch = VaultSession.requireEpoch();
            assertTrue(VaultSession.isValidEpoch(epoch));

            VaultSession.lock();
            assertFalse(VaultSession.isValidEpoch(epoch));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Test
    public void staleLockCannotLockNewerSession() throws Exception {
        byte[] first = key((byte) 5);
        byte[] second = key((byte) 9);
        try {
            VaultSession.unlock(first);
            long staleEpoch = VaultSession.requireEpoch();

            VaultSession.lock();
            VaultSession.unlock(second);
            long currentEpoch = VaultSession.requireEpoch();

            assertFalse(VaultSession.lockIfEpoch(staleEpoch));
            assertTrue(VaultSession.isValidEpoch(currentEpoch));
        } finally {
            Arrays.fill(first, (byte) 0);
            Arrays.fill(second, (byte) 0);
        }
    }

    @Test(expected = GeneralSecurityException.class)
    public void staleEpochCannotReadNewSessionKey() throws Exception {
        byte[] first = key((byte) 11);
        byte[] second = key((byte) 13);
        try {
            VaultSession.unlock(first);
            long staleEpoch = VaultSession.requireEpoch();
            VaultSession.lock();
            VaultSession.unlock(second);
            VaultSession.requireKeyCopy(staleEpoch);
        } finally {
            Arrays.fill(first, (byte) 0);
            Arrays.fill(second, (byte) 0);
        }
    }

    private static byte[] key(byte seed) {
        byte[] value = new byte[CryptoBox.KEY_BYTES];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (seed + index * 7);
        }
        return value;
    }
}
