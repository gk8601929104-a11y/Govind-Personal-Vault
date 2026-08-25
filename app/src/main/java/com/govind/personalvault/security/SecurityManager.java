package com.govind.personalvault.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.govind.personalvault.VaultApp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;

/**
 * Owns the VMK wrappers and record encryption. Blocking methods must run through SecureWork.
 * The PIN never encrypts records directly.
 */
public final class SecurityManager {
    private static final String PREFS = "vault_security_v1";
    private static final String PIN_PEPPER_ALIAS = "govind_personal_vault_pin_pepper_v1";
    private static final String BIOMETRIC_ALIAS = "govind_personal_vault_biometric_v1";
    private static final int PIN_ITERATIONS = 210_000;
    private static final int RECOVERY_ITERATIONS = 320_000;
    private static final int SALT_BYTES = 16;
    private static final int MAX_DELAY_SECONDS = 15 * 60;
    private static final byte[] PIN_AAD = "govind-vault|vmk|pin|v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RECOVERY_AAD = "govind-vault|vmk|recovery|v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BIOMETRIC_AAD = "govind-vault|vmk|biometric|v1".getBytes(StandardCharsets.UTF_8);
    private static volatile SecurityManager instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();

    public static final class LockoutException extends GeneralSecurityException {
        private static final long serialVersionUID = 1L;
        public final long remainingMillis;
        LockoutException(long remainingMillis) { super("Try again after the security delay"); this.remainingMillis = remainingMillis; }
    }

    public static final class InvalidPinException extends GeneralSecurityException {
        private static final long serialVersionUID = 1L;
        InvalidPinException() { super("Incorrect PIN"); }
    }

    public static final class InvalidRecoveryException extends GeneralSecurityException {
        private static final long serialVersionUID = 1L;
        InvalidRecoveryException() { super("Recovery phrase is not valid for this vault"); }
    }

    public static final class RecoveryRequiredException extends GeneralSecurityException {
        private static final long serialVersionUID = 1L;
        RecoveryRequiredException() { super("Device key changed. Use the recovery phrase to create a new PIN"); }
    }

    public static SecurityManager get(Context context) {
        SecurityManager value = instance;
        if (value == null) {
            synchronized (SecurityManager.class) {
                value = instance;
                if (value == null) instance = value = new SecurityManager(context.getApplicationContext());
            }
        }
        return value;
    }

    private SecurityManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isSetUp() {
        return safeBoolean("setup_complete",false)||(prefs.contains("pin_wrapper")&&prefs.contains("recovery_wrapper"));
    }

    public boolean isBiometricEnabled() {
        return safeBoolean("biometric_enabled",false) && prefs.contains("biometric_wrapper");
    }

    public long remainingLockoutMillis() {
        try { return Math.max(0L, prefs.getLong("pin_locked_until", 0L) - System.currentTimeMillis()); }
        catch (ClassCastException damagedCounter) { prefs.edit().remove("pin_locked_until").apply(); return 0L; }
    }

    public void initialize(char[] pin, String recoveryPhrase) throws Exception {
        validatePin(pin);
        if (isSetUp()) throw new GeneralSecurityException("Vault is already configured");
        String phrase = RecoveryPhrase.normalize(recoveryPhrase);
        if (!RecoveryPhrase.isValid(context, phrase)) throw new GeneralSecurityException("Generate and save a valid recovery phrase first");
        byte[] vmk = randomBytes(CryptoBox.KEY_BYTES);
        byte[] pinSalt = randomBytes(SALT_BYTES);
        byte[] recoverySalt = randomBytes(SALT_BYTES);
        byte[] pinKey = null;
        byte[] recoveryKey = null;
        try {
            deleteKey(PIN_PEPPER_ALIAS);
            pinKey = derivePinKey(pin, pinSalt, true);
            recoveryKey = pbkdf2(phrase.toCharArray(), recoverySalt, RECOVERY_ITERATIONS);
            String pinWrapper = CryptoBox.encrypt(pinKey, vmk, PIN_AAD);
            String recoveryWrapper = CryptoBox.encrypt(recoveryKey, vmk, RECOVERY_AAD);
            boolean committed = prefs.edit().clear()
                    .putInt("schema", 1)
                    .putString("pin_salt", b64(pinSalt))
                    .putInt("pin_iterations", PIN_ITERATIONS)
                    .putString("pin_wrapper", pinWrapper)
                    .putString("recovery_salt", b64(recoverySalt))
                    .putInt("recovery_iterations", RECOVERY_ITERATIONS)
                    .putString("recovery_wrapper", recoveryWrapper)
                    .putInt("pin_failures", 0)
                    .putLong("pin_locked_until", 0L)
                    .putBoolean("setup_complete", true)
                    .commit();
            if (!committed) throw new GeneralSecurityException("Secure settings could not be saved");
            unlockOnlyWhileForeground(vmk);
        } catch (Exception failure) {
            prefs.edit().clear().commit();
            deleteKey(PIN_PEPPER_ALIAS);
            throw failure;
        } finally {
            wipe(vmk, pinSalt, recoverySalt, pinKey, recoveryKey);
            Arrays.fill(pin, '\0');
        }
    }

    public void unlockWithPin(char[] pin) throws Exception {
        validatePin(pin);
        long remaining = remainingLockoutMillis();
        if (remaining > 0) throw new LockoutException(remaining);
        byte[] salt = fromB64(required("pin_salt"));
        byte[] key = null;
        byte[] vmk = null;
        try {
            key = derivePinKey(pin, salt, false);
            vmk = CryptoBox.decrypt(key, required("pin_wrapper"), PIN_AAD);
            requireVmk(vmk);
            unlockOnlyWhileForeground(vmk);
            prefs.edit().putInt("pin_failures", 0).putLong("pin_locked_until", 0L).commit();
        } catch (RecoveryRequiredException recoveryRequired) {
            throw recoveryRequired;
        } catch (GeneralSecurityException wrongPin) {
            recordPinFailure();
            throw new InvalidPinException();
        } finally {
            wipe(salt, key, vmk);
            Arrays.fill(pin, '\0');
        }
    }

    public void recoverAndChangePin(String recoveryPhrase, char[] newPin) throws Exception {
        validatePin(newPin);
        String phrase = RecoveryPhrase.normalize(recoveryPhrase);
        if (!RecoveryPhrase.isValid(context, phrase)) throw new InvalidRecoveryException();
        byte[] recoverySalt = fromB64(required("recovery_salt"));
        byte[] recoveryKey = null;
        byte[] vmk = null;
        try {
            recoveryKey = pbkdf2(phrase.toCharArray(), recoverySalt, safeInt("recovery_iterations",RECOVERY_ITERATIONS));
            try {
                vmk = CryptoBox.decrypt(recoveryKey, required("recovery_wrapper"), RECOVERY_AAD);
                requireVmk(vmk);
            } catch (GeneralSecurityException wrongPhrase) { throw new InvalidRecoveryException(); }
            if(!VaultApp.isInForeground())throw new GeneralSecurityException("Recovery paused because the app left the foreground");
            deleteKey(PIN_PEPPER_ALIAS);
            writePinWrapper(newPin, vmk, true);
            prefs.edit().putInt("pin_failures", 0).putLong("pin_locked_until", 0L).commit();
            unlockOnlyWhileForeground(vmk);
        } finally {
            wipe(recoverySalt, recoveryKey, vmk);
            Arrays.fill(newPin, '\0');
        }
    }

    public void changePin(char[] currentPin, char[] newPin) throws Exception {
        validatePin(newPin);
        unlockWithPin(currentPin);
        byte[] vmk = VaultSession.requireKeyCopy();
        try { writePinWrapper(newPin, vmk, false); }
        finally { wipe(vmk); Arrays.fill(newPin, '\0'); }
    }

    public Cipher beginBiometricEnrollmentCipher() throws Exception {
        if (!VaultSession.isUnlocked()) throw new GeneralSecurityException("Unlock the vault first");
        deleteKey(BIOMETRIC_ALIAS);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(BIOMETRIC_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUnlockedDeviceRequired(true)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build());
        SecretKey key = generator.generateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(BIOMETRIC_AAD);
        return cipher;
    }

    public void finishBiometricEnrollment(Cipher authenticatedCipher) throws Exception {
        byte[] vmk = VaultSession.requireKeyCopy();
        byte[] encrypted = null;
        try {
            encrypted = authenticatedCipher.doFinal(vmk);
            String wrapper = CryptoBox.encode(authenticatedCipher.getIV(), encrypted);
            if (!prefs.edit().putString("biometric_wrapper", wrapper).putBoolean("biometric_enabled", true).commit()) {
                throw new GeneralSecurityException("Biometric settings could not be saved");
            }
        } catch (Exception failure) {
            disableBiometric();
            throw failure;
        } finally { wipe(vmk, encrypted); }
    }

    public Cipher beginBiometricUnlockCipher() throws Exception {
        if (!isBiometricEnabled()) throw new GeneralSecurityException("Biometric unlock is not enabled");
        KeyStore store = androidKeyStore();
        SecretKey key = (SecretKey) store.getKey(BIOMETRIC_ALIAS, null);
        if (key == null) throw new GeneralSecurityException("Biometric key is unavailable");
        CryptoBox.Parts parts = CryptoBox.parse(required("biometric_wrapper"));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, parts.iv));
        cipher.updateAAD(BIOMETRIC_AAD);
        wipe(parts.iv,parts.ciphertext);
        return cipher;
    }

    public void finishBiometricUnlock(Cipher authenticatedCipher) throws Exception {
        CryptoBox.Parts parts = CryptoBox.parse(required("biometric_wrapper"));
        byte[] vmk = null;
        try {
            vmk = authenticatedCipher.doFinal(parts.ciphertext);
            requireVmk(vmk);
            unlockOnlyWhileForeground(vmk);
        } finally { wipe(parts.iv, parts.ciphertext, vmk); }
    }

    public void disableBiometric() {
        prefs.edit().remove("biometric_wrapper").putBoolean("biometric_enabled", false).commit();
        try { deleteKey(BIOMETRIC_ALIAS); } catch (GeneralSecurityException ignored) { }
    }

    public String encryptField(String itemId, String field, String value) throws GeneralSecurityException {
        return encryptText("record|" + itemId + "|" + field + "|v1", value);
    }

    public String decryptField(String itemId, String field, String envelope) throws GeneralSecurityException {
        return decryptText("record|" + itemId + "|" + field + "|v1", envelope);
    }

    public String encryptText(String purpose, String value) throws GeneralSecurityException {
        byte[] vmk = VaultSession.requireKeyCopy();
        try { return CryptoBox.encrypt(vmk, value == null ? "" : value, purpose); }
        finally { wipe(vmk); }
    }

    public String decryptText(String purpose, String envelope) throws GeneralSecurityException {
        byte[] vmk = VaultSession.requireKeyCopy();
        try { return CryptoBox.decryptText(vmk, envelope, purpose); }
        finally { wipe(vmk); }
    }

    public String encryptBytes(String purpose, byte[] value) throws GeneralSecurityException {
        byte[] vmk = VaultSession.requireKeyCopy();
        byte[] aad = (purpose == null ? "" : purpose).getBytes(StandardCharsets.UTF_8);
        try { return CryptoBox.encrypt(vmk, value == null ? new byte[0] : value, aad); }
        finally { wipe(vmk, aad); }
    }

    public byte[] decryptBytes(String purpose, String envelope) throws GeneralSecurityException {
        byte[] vmk = VaultSession.requireKeyCopy();
        byte[] aad = (purpose == null ? "" : purpose).getBytes(StandardCharsets.UTF_8);
        try { return CryptoBox.decrypt(vmk, envelope, aad); }
        finally { wipe(vmk, aad); }
    }

    private void writePinWrapper(char[] pin, byte[] vmk, boolean createPepper) throws Exception {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] key = null;
        try {
            key = derivePinKey(pin, salt, createPepper);
            String wrapper = CryptoBox.encrypt(key, vmk, PIN_AAD);
            if (!prefs.edit().putString("pin_salt", b64(salt)).putInt("pin_iterations", PIN_ITERATIONS)
                    .putString("pin_wrapper", wrapper).commit()) {
                throw new GeneralSecurityException("New PIN could not be saved");
            }
        } finally { wipe(salt, key); }
    }

    private byte[] derivePinKey(char[] pin, byte[] salt, boolean createPepper) throws Exception {
        byte[] stretched = pbkdf2(pin, salt, safeInt("pin_iterations",PIN_ITERATIONS));
        try {
            SecretKey pepper = pinPepper(createPepper);
            Mac mac = Mac.getInstance("HmacSHA256");
            try { mac.init(pepper); }
            catch (InvalidKeyException invalidated) { throw new RecoveryRequiredException(); }
            mac.update("govind-vault|pin-wrap|v1".getBytes(StandardCharsets.UTF_8));
            mac.update(salt);
            return mac.doFinal(stretched);
        } finally { wipe(stretched); }
    }

    private SecretKey pinPepper(boolean create) throws Exception {
        KeyStore store = androidKeyStore();
        SecretKey existing;
        try { existing = (SecretKey) store.getKey(PIN_PEPPER_ALIAS, null); }
        catch (GeneralSecurityException invalidated) { if(!create)throw new RecoveryRequiredException();throw invalidated; }
        if (existing != null) return existing;
        if (!create) throw new RecoveryRequiredException();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(PIN_PEPPER_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUnlockedDeviceRequired(true)
                .build());
        return generator.generateKey();
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    private void recordPinFailure() {
        int failures = Math.min(100,safeInt("pin_failures",0)+1);
        int delay = 0;
        if (failures >= 5) delay = Math.min(MAX_DELAY_SECONDS, 30 * (1 << Math.min(5, failures - 5)));
        prefs.edit().putInt("pin_failures", failures)
                .putLong("pin_locked_until", delay == 0 ? 0L : System.currentTimeMillis() + delay * 1000L)
                .commit();
    }

    private void validatePin(char[] pin) throws GeneralSecurityException {
        if (pin == null || pin.length < 6 || pin.length > 12) throw new GeneralSecurityException("PIN must contain 6 to 12 digits");
        for (char value : pin) if (value < '0' || value > '9') throw new GeneralSecurityException("PIN must contain digits only");
    }

    private String required(String key) throws GeneralSecurityException {
        String value = prefs.getString(key, null);
        if (value == null || value.isEmpty()) throw new GeneralSecurityException("Vault security metadata is incomplete");
        return value;
    }

    private KeyStore androidKeyStore() throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            return store;
        } catch (Exception failure) { throw new GeneralSecurityException("Android Keystore is unavailable", failure); }
    }

    private void deleteKey(String alias) throws GeneralSecurityException {
        KeyStore store = androidKeyStore();
        try { if (store.containsAlias(alias)) store.deleteEntry(alias); }
        catch (Exception failure) { throw new GeneralSecurityException("Old device key could not be replaced", failure); }
    }

    private byte[] randomBytes(int count) { byte[] value = new byte[count]; random.nextBytes(value); return value; }
    private int safeInt(String key,int fallback){try{return prefs.getInt(key,fallback);}catch(ClassCastException damaged){prefs.edit().remove(key).apply();return fallback;}}
    private boolean safeBoolean(String key,boolean fallback){try{return prefs.getBoolean(key,fallback);}catch(ClassCastException damaged){prefs.edit().remove(key).apply();return fallback;}}
    private String b64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private byte[] fromB64(String value) throws GeneralSecurityException {
        try { return Base64.getUrlDecoder().decode(value); }
        catch (IllegalArgumentException malformed) { throw new GeneralSecurityException("Vault security metadata is damaged", malformed); }
    }
    private void requireVmk(byte[] vmk) throws GeneralSecurityException {
        if (vmk == null || vmk.length != CryptoBox.KEY_BYTES) throw new GeneralSecurityException("Invalid vault key");
    }
    private void unlockOnlyWhileForeground(byte[] vmk) throws GeneralSecurityException {
        if(VaultApp.isInForeground())VaultSession.unlock(vmk);else VaultSession.lock();
    }
    private static void wipe(byte[]... values) { if (values != null) for (byte[] value : values) if (value != null) Arrays.fill(value, (byte) 0); }
}
