package com.govind.personalvault;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.govind.personalvault.media.VaultBackup;
import com.govind.personalvault.security.SecureWork;
import com.govind.personalvault.security.SecurityManager;
import com.govind.personalvault.security.VaultSession;
import com.govind.personalvault.ui.Ui;

import java.util.Arrays;

import javax.crypto.Cipher;

public final class SettingsActivity extends BaseActivity {
    private EditText currentPin;
    private EditText newPin;
    private EditText confirmPin;
    private Button changePin;
    private Button biometric;
    private TextView biometricStatus;
    private SecureWork.Task task;

    private Button clipboardButton;
    private Button autolockButton;
    private Button darkButton;
    private Button lightButton;
    private ActivityResultLauncher<String[]> importBackup;
    private ActivityResultLauncher<String[]> importEnc;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        importBackup = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) confirmImport(uri);
        });
        importEnc = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) importEncFile(uri);
        });
        build();
    }

    private void build(){
        LinearLayout root=Ui.vertical(this);root.setBackgroundColor(palette.bg);root.addView(topBar("The vault","Appearance, lock timing, backup, and the master PIN.",true,null,null));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout page=Ui.vertical(this);page.setPadding(Ui.dp(this,18),Ui.dp(this,10),Ui.dp(this,18),Ui.dp(this,28));

        page.addView(Ui.heading(this,"Appearance"));
        page.addView(Ui.text(this,"Dark is the default. The choice is stored on this device only.",13,palette.muted),Ui.margins(this,Ui.MATCH,Ui.WRAP,0,4,0,10));
        LinearLayout themeRow=Ui.horizontal(this);
        darkButton=Ui.pill(this,"Dark",!VaultPrefs.isLight(this));
        lightButton=Ui.pill(this,"Light",VaultPrefs.isLight(this));
        darkButton.setOnClickListener(v->setTheme(false));
        lightButton.setOnClickListener(v->setTheme(true));
        themeRow.addView(darkButton);
        LinearLayout.LayoutParams lightParams=new LinearLayout.LayoutParams(Ui.WRAP,Ui.dp(this,36));lightParams.leftMargin=Ui.dp(this,8);
        themeRow.addView(lightButton,lightParams);
        page.addView(themeRow);

        page.addView(Ui.heading(this,"Auto-lock"),Ui.margins(this,Ui.MATCH,Ui.WRAP,0,28,0,0));
        page.addView(Ui.text(this,"The vault key is wiped from memory after inactivity while the app is open. Background still locks immediately.",13,palette.muted),Ui.margins(this,Ui.MATCH,Ui.WRAP,0,4,0,10));
        autolockButton=Ui.secondary(this,VaultPrefs.autolockLabel(VaultPrefs.autolockMs(this)));
        autolockButton.setOnClickListener(v->cycleAutolock());
        page.addView(autolockButton,new LinearLayout.LayoutParams(Ui.MATCH,Ui.dp(this,48)));

        page.addView(Ui.heading(this,"Clipboard"),Ui.margins(this,Ui.MATCH,Ui.WRAP,0,28,0,0));
        page.addView(Ui.text(this,"Copied secrets are cleared automatically.",13,palette.muted),Ui.margins(this,Ui.MATCH,Ui.WRAP,0,4,0,10));
        clipboardButton=Ui.secondary(this,VaultPrefs.clipboardLabel(VaultPrefs.clipboardMs(this)));
        clipboardButton.setOnClickListener(v->cycleClipboard());
        page.addView(clipboardButton,new LinearLayout.LayoutParams(Ui.MATCH,Ui.dp(this,48)));

        page.addView(Ui.heading(this,"Master password"),Ui.margins(this,Ui.MATCH,Ui.WRAP,0,28,0,0));
        page.addView(Ui.text(this,"This re-wraps the vault key. Existing items stay encrypted with the same data key.",13,palette.muted),Ui.margins(this,Ui.MATCH,Ui.WRAP,0,4,0,8));
        currentPin=Ui.pin(this,"Current PIN");newPin=Ui.pin(this,"New 6–12 digit PIN");confirmPin=Ui.pin(this,"Confirm new PIN");
        field(page,"Current",currentPin);field(page,"New",newPin);field(page,"Confirm new",confirmPin);
        changePin=Ui.primary(this,"Update password");changePin.setOnClickListener(v->changePin());page.addView(changePin,Ui.margins(this,Ui.MATCH,Ui.dp(this,52),0,16,0,0));

        page.addView(Ui.heading(this,"Biometric"),Ui.margins(this,Ui.MATCH,Ui.WRAP,0,24,0,0));
        biometricStatus=Ui.text(this,"",13,palette.muted);page.addView(biometricStatus,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,5,0,10));
        biometric=Ui.secondary(this,"Enable biometric");biometric.setOnClickListener(v->toggleBiometric());page.addView(biometric);updateBiometricStatus();

        page.addView(Ui.heading(this,"Session"),Ui.margins(this,Ui.MATCH,Ui.WRAP,0,28,0,0));
        Button lock=Ui.secondary(this,"Lock now");lock.setOnClickListener(v->{VaultSession.lock();finish();});page.addView(lock,Ui.margins(this,Ui.MATCH,Ui.dp(this,52),0,10,0,0));

        page.addView(Ui.heading(this,"Encrypted backup"),Ui.margins(this,Ui.MATCH,Ui.WRAP,0,28,0,0));
        page.addView(Ui.text(this,"Exports the wrapped key and ciphertext. Useless without the master password.",13,palette.muted),Ui.margins(this,Ui.MATCH,Ui.WRAP,0,4,0,10));
        LinearLayout backupRow=Ui.horizontal(this);
        Button exportVault=Ui.primary(this,"Export vault");
        exportVault.setLayoutParams(new LinearLayout.LayoutParams(Ui.WRAP,Ui.dp(this,44)));
        exportVault.setOnClickListener(v->exportVault());
        Button importVault=Ui.secondary(this,"Import backup");
        importVault.setLayoutParams(new LinearLayout.LayoutParams(Ui.WRAP,Ui.dp(this,44)));
        LinearLayout.LayoutParams importParams=new LinearLayout.LayoutParams(Ui.WRAP,Ui.dp(this,44));
        importParams.leftMargin=Ui.dp(this,8);
        importVault.setOnClickListener(v->importBackup.launch(new String[]{"application/zip","*/*"}));
        backupRow.addView(exportVault);
        backupRow.addView(importVault,importParams);
        page.addView(backupRow);
        Button importEncButton=Ui.secondary(this,"Import .enc file");
        importEncButton.setOnClickListener(v->importEnc.launch(new String[]{"*/*"}));
        page.addView(importEncButton,Ui.margins(this,Ui.WRAP,Ui.dp(this,44),0,10,0,0));

        page.addView(Ui.heading(this,"Destroy vault"),Ui.margins(this,Ui.MATCH,Ui.WRAP,0,28,0,0));
        page.addView(Ui.text(this,"Permanently wipes this device copy. There is no recovery without your phrase.",13,palette.muted),Ui.margins(this,Ui.MATCH,Ui.WRAP,0,4,0,10));
        Button destroy=Ui.destroy(this,"Destroy vault");destroy.setOnClickListener(v->confirmDestroy());page.addView(destroy,Ui.margins(this,Ui.MATCH,Ui.dp(this,52),0,8,0,0));

        LinearLayout about=Ui.card(this);about.addView(Ui.heading(this,"Aegis "+installedVersionName()));TextView aboutText=Ui.text(this,"Android 12+ · offline · AES-256-GCM",13,palette.muted);about.addView(aboutText,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,7,0,0));page.addView(about,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,22,0,0));
        scroll.addView(page,centeredScrollParams(720));root.addView(scroll,new LinearLayout.LayoutParams(Ui.MATCH,0,1));safeContentView(root);
    }

    private void setTheme(boolean light){
        VaultPrefs.setLight(this, light);
        Ui.applyTheme(light);
        recreate();
    }

    private void cycleAutolock(){
        long current=VaultPrefs.autolockMs(this);
        long next=VaultPrefs.AUTOLOCK_OPTIONS_MS[0];
        for(int i=0;i<VaultPrefs.AUTOLOCK_OPTIONS_MS.length;i++){
            if(VaultPrefs.AUTOLOCK_OPTIONS_MS[i]==current){
                next=VaultPrefs.AUTOLOCK_OPTIONS_MS[(i+1)%VaultPrefs.AUTOLOCK_OPTIONS_MS.length];
                break;
            }
        }
        VaultPrefs.setAutolockMs(this,next);
        autolockButton.setText(VaultPrefs.autolockLabel(next));
        bumpIdleTimer();
    }

    private void cycleClipboard(){
        long current=VaultPrefs.clipboardMs(this);
        long next=VaultPrefs.CLIPBOARD_OPTIONS_MS[0];
        for(int i=0;i<VaultPrefs.CLIPBOARD_OPTIONS_MS.length;i++){
            if(VaultPrefs.CLIPBOARD_OPTIONS_MS[i]==current){
                next=VaultPrefs.CLIPBOARD_OPTIONS_MS[(i+1)%VaultPrefs.CLIPBOARD_OPTIONS_MS.length];
                break;
            }
        }
        VaultPrefs.setClipboardMs(this,next);
        clipboardButton.setText(VaultPrefs.clipboardLabel(next));
    }

    private void exportVault(){
        message("Exporting encrypted backup…");
        task=SecureWork.submit(()->VaultBackup.export(this),(uri,error)->{
            if(error!=null){SettingsActivity.this.error(error);return;}
            message("Backup saved to Downloads/Govind Personal Vault");
        });
    }

    private void confirmImport(Uri uri){
        new android.app.AlertDialog.Builder(this)
                .setTitle("Import backup")
                .setMessage("This replaces the vault on this phone with the backup. The current copy is overwritten.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Import", (d,w)->{
                    task=SecureWork.submit(()->{VaultBackup.importArchive(this, uri);return Boolean.TRUE;},(ok,error)->{
                        if(error!=null){SettingsActivity.this.error(error);return;}
                        VaultSession.lock();
                        message("Backup restored. Unlock with the backup PIN.");
                        Intent lock=new Intent(this, LockActivity.class);
                        lock.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(lock);
                        finish();
                    });
                })
                .show();
    }

    private void importEncFile(Uri uri){
        task=SecureWork.submit(()->VaultBackup.importEnc(this, uri),(id,error)->{
            if(error!=null){SettingsActivity.this.error(error);return;}
            message("Added to vault");
        });
    }

    private void confirmDestroy(){
        new android.app.AlertDialog.Builder(this)
                .setTitle("Destroy vault")
                .setMessage("This wipes the local vault. Recovery phrase is the only way back.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Destroy", (d,w)->destroyVault())
                .show();
    }

    private void destroyVault(){
        VaultSession.lock();
        com.govind.personalvault.data.VaultDb.shutdown();
        deleteDatabase("govind_personal_vault.db");
        java.io.File media=new java.io.File(getFilesDir(),"media");
        java.io.File[] files=media.listFiles();
        if(files!=null) for(java.io.File file:files) file.delete();
        media.delete();
        SecurityManager.get(this).wipeIdentity();
        VaultPrefs.prefs(this).edit().clear().commit();
        message("Vault destroyed");
        Intent setup=new Intent(this, SetupActivity.class);
        setup.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(setup);
        finish();
    }

    @SuppressWarnings("deprecation")
    private String installedVersionName() {
        try {
            String installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (installed != null && !installed.trim().isEmpty()) {
                return installed.trim();
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }
        return "1.4.3";
    }

    private void updateBiometricStatus(){boolean enabled=SecurityManager.get(this).isBiometricEnabled();biometricStatus.setText(enabled?"Strong-biometric unlock is enabled on this device.":"Optional. Your PIN and recovery phrase continue to work independently.");biometric.setText(enabled?"Disable biometric unlock":"Enable strong biometric");}

    private void toggleBiometric(){
        SecurityManager security=SecurityManager.get(this);if(security.isBiometricEnabled()){biometric.setEnabled(false);task=SecureWork.submit(()->{security.disableBiometric();return Boolean.TRUE;},(ok,error)->{biometric.setEnabled(true);if(error!=null){SettingsActivity.this.error(error);return;}updateBiometricStatus();message("Biometric unlock disabled");});return;}
        BiometricManager manager=getSystemService(BiometricManager.class);if(manager==null||manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS){message("Enroll a strong biometric in Android settings first");return;}
        biometric.setEnabled(false);biometricStatus.setText("Preparing an auth-bound device key…");
        task=SecureWork.submit(()->security.beginBiometricEnrollmentCipher(),(cipher,error)->{if(error!=null){biometric.setEnabled(true);SettingsActivity.this.error(error);updateBiometricStatus();return;}showEnrollmentPrompt(cipher);});
    }

    private void showEnrollmentPrompt(Cipher cipher){
        BiometricPrompt prompt=new BiometricPrompt.Builder(this).setTitle("Enable biometric unlock").setSubtitle("Confirm with a strong biometric")
                .setNegativeButton("Cancel",getMainExecutor(),(dialog,which)->{biometric.setEnabled(true);updateBiometricStatus();}).build();
        prompt.authenticate(new BiometricPrompt.CryptoObject(cipher),null,getMainExecutor(),new BiometricPrompt.AuthenticationCallback(){
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){Cipher authenticated=result.getCryptoObject()==null?null:result.getCryptoObject().getCipher();if(authenticated==null){biometric.setEnabled(true);message("Secure biometric cipher was unavailable");return;}task=SecureWork.submit(()->{SecurityManager.get(SettingsActivity.this).finishBiometricEnrollment(authenticated);return Boolean.TRUE;},(ok,error)->{biometric.setEnabled(true);if(error!=null){SettingsActivity.this.error(error);return;}updateBiometricStatus();message("Biometric unlock enabled");});}
            @Override public void onAuthenticationError(int code,CharSequence message){biometric.setEnabled(true);updateBiometricStatus();if(code!=BiometricPrompt.BIOMETRIC_ERROR_CANCELED&&code!=BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED)SettingsActivity.this.message(message.toString());}
            @Override public void onAuthenticationFailed(){biometricStatus.setText("Biometric not recognized. Try again.");}
        });
    }

    private void changePin(){
        String first=newPin.getText().toString(),second=confirmPin.getText().toString();if(!TextUtils.equals(first,second)){message("New PIN entries do not match");return;}
        char[] current=currentPin.getText().toString().toCharArray(),next=first.toCharArray();changePin.setEnabled(false);changePin.setText("Re-wrapping vault key…");
        task=SecureWork.submit(()->{SecurityManager.get(this).changePin(current,next);return Boolean.TRUE;},(ok,error)->{Arrays.fill(current,'\0');Arrays.fill(next,'\0');currentPin.setText("");newPin.setText("");confirmPin.setText("");changePin.setEnabled(true);changePin.setText("Change PIN securely");if(error!=null){SettingsActivity.this.error(error);return;}message("PIN changed securely");});
    }

    @Override protected void clearSensitiveUi(){if(task!=null)task.cancel();if(currentPin!=null)currentPin.setText("");if(newPin!=null)newPin.setText("");if(confirmPin!=null)confirmPin.setText("");}
    @Override protected void onDestroy(){if(task!=null)task.cancel();super.onDestroy();}
}
