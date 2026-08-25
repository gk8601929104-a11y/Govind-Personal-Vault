package com.govind.personalvault;

import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.govind.personalvault.security.SecureWork;
import com.govind.personalvault.security.SecurityManager;
import com.govind.personalvault.security.VaultSession;
import com.govind.personalvault.ui.Ui;

import java.util.Arrays;

import javax.crypto.Cipher;

public final class LockActivity extends BaseActivity {
    private static final int RECOVERY_REQUEST = 41;
    private EditText pin;
    private Button unlock;
    private Button biometric;
    private TextView status;
    private SecureWork.Task task;
    private boolean completing;

    @Override protected boolean requiresUnlockedVault(){return false;}

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        if(!SecurityManager.get(this).isSetUp()){startActivity(new Intent(this,SetupActivity.class));finish();return;}
        build();
    }

    @Override protected void onResume(){
        super.onResume();
        if(VaultSession.isUnlocked()&&!completing){unlockSucceeded();return;}
        updateLockout();
    }

    private void build(){
        LinearLayout root=Ui.vertical(this);root.setGravity(Gravity.CENTER);root.setBackgroundColor(palette.bg);root.setPadding(Ui.dp(this,24),Ui.dp(this,28),Ui.dp(this,24),Ui.dp(this,28));
        LinearLayout panel=Ui.card(this);
        panel.addView(Ui.brandMark(this, 108));
        TextView word=Ui.title(this,"AEGIS");word.setGravity(Gravity.CENTER);word.setLetterSpacing(0.18f);
        panel.addView(word,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,10,0,0));
        TextView sub=Ui.text(this,"Private vault",14,palette.muted);sub.setGravity(Gravity.CENTER);sub.setLetterSpacing(0.12f);
        panel.addView(sub,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,4,0,0));
        TextView title=Ui.heading(this,"Unlock");title.setGravity(Gravity.CENTER);panel.addView(title,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,16,0,0));
        TextView detail=Ui.text(this,"Unlock on this device. The vault key never leaves the phone.",14,palette.muted);detail.setGravity(Gravity.CENTER);panel.addView(detail,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,8,0,18));
        pin=Ui.pin(this,"Enter PIN");panel.addView(pin);
        unlock=Ui.primary(this,"Unlock vault");unlock.setOnClickListener(v->unlockPin());panel.addView(unlock,Ui.margins(this,Ui.MATCH,Ui.dp(this,52),0,14,0,0));
        biometric=Ui.secondary(this,"Use strong biometric");biometric.setOnClickListener(v->unlockBiometric());panel.addView(biometric,Ui.margins(this,Ui.MATCH,Ui.dp(this,52),0,10,0,0));
        status=Ui.text(this,"",13,palette.warning);status.setGravity(Gravity.CENTER);panel.addView(status,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,12,0,0));
        Button recovery=Ui.secondary(this,"Use recovery phrase");recovery.setOnClickListener(v->{Intent intent=new Intent(this,RecoveryActivity.class);startActivityForResult(intent,RECOVERY_REQUEST);});panel.addView(recovery,Ui.margins(this,Ui.MATCH,Ui.dp(this,50),0,12,0,0));
        TextView footer=Ui.text(this,"Designed and developed by Govind",12,palette.muted);footer.setGravity(Gravity.CENTER);panel.addView(footer,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,22,0,2));
        root.addView(panel,centeredPanelParams(440));safeContentView(root);
        biometric.setVisibility(SecurityManager.get(this).isBiometricEnabled()?android.view.View.VISIBLE:android.view.View.GONE);
    }

    private void unlockPin(){
        char[] value=pin.getText().toString().toCharArray();setBusy(true,"Checking PIN…");
        task=SecureWork.submit(()->{SecurityManager.get(this).unlockWithPin(value);return Boolean.TRUE;},(result,failure)->{
            Arrays.fill(value,'\0');pin.setText("");setBusy(false,"");
            if(failure!=null){error(failure);updateLockout();return;}unlockSucceeded();
        });
    }

    private void unlockBiometric(){
        BiometricManager manager=getSystemService(BiometricManager.class);
        if(manager==null||manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS){message("A strong enrolled biometric is unavailable");return;}
        setBusy(true,"Preparing biometric unlock…");
        task=SecureWork.submit(()->SecurityManager.get(this).beginBiometricUnlockCipher(),(cipher,failure)->{
            if(failure!=null){setBusy(false,"");error(failure);biometric.setVisibility(android.view.View.GONE);task=SecureWork.submit(()->{SecurityManager.get(this).disableBiometric();return Boolean.TRUE;},null);return;}
            showBiometricPrompt(cipher);
        });
    }

    private void showBiometricPrompt(Cipher cipher){
        BiometricPrompt prompt=new BiometricPrompt.Builder(this).setTitle("Unlock Aegis")
                .setSubtitle("Use a strong biometric").setNegativeButton("Use PIN",getMainExecutor(),(dialog,which)->setBusy(false,"")).build();
        prompt.authenticate(new BiometricPrompt.CryptoObject(cipher),null,getMainExecutor(),new BiometricPrompt.AuthenticationCallback(){
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){
                Cipher authenticated=result.getCryptoObject()==null?null:result.getCryptoObject().getCipher();
                if(authenticated==null){setBusy(false,"");message("Biometric authentication did not return a secure cipher");return;}
                task=SecureWork.submit(()->{SecurityManager.get(LockActivity.this).finishBiometricUnlock(authenticated);return Boolean.TRUE;},(ok,error)->{setBusy(false,"");if(error!=null){LockActivity.this.error(error);return;}unlockSucceeded();});
            }
            @Override public void onAuthenticationError(int code,CharSequence message){setBusy(false,"");if(code!=BiometricPrompt.BIOMETRIC_ERROR_CANCELED&&code!=BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED)status.setText(message);}
            @Override public void onAuthenticationFailed(){status.setText("Biometric not recognized. Try again or use PIN.");}
        });
    }

    private void updateLockout(){long remaining=SecurityManager.get(this).remainingLockoutMillis();if(remaining>0){long seconds=(remaining+999)/1000;status.setText("Security delay active: "+seconds+" seconds");unlock.setEnabled(false);status.postDelayed(this::updateLockout,1000);}else{status.setText("");unlock.setEnabled(true);}}
    private void setBusy(boolean busy,String text){unlock.setEnabled(!busy);biometric.setEnabled(!busy);status.setText(text);}

    private void unlockSucceeded(){
        if(completing)return;completing=true;
        if(getIntent().getBooleanExtra("overlay",false)){setResult(RESULT_OK);finish();return;}
        Intent vault=new Intent(this,VaultActivity.class);vault.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);startActivity(vault);
    }

    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==RECOVERY_REQUEST&&result==RESULT_OK)unlockSucceeded();}
    @Override protected void clearSensitiveUi(){if(task!=null)task.cancel();if(pin!=null)pin.setText("");}
    @Override protected void onDestroy(){if(task!=null)task.cancel();super.onDestroy();}
}
