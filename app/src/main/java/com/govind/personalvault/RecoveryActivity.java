package com.govind.personalvault;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.govind.personalvault.security.RecoveryPhrase;
import com.govind.personalvault.security.SecureWork;
import com.govind.personalvault.security.SecurityManager;
import com.govind.personalvault.ui.Ui;

import java.util.Arrays;

public final class RecoveryActivity extends BaseActivity {
    private EditText phrase;
    private EditText pin;
    private EditText confirm;
    private Button recover;
    private SecureWork.Task task;

    @Override protected boolean requiresUnlockedVault(){return false;}

    @Override protected void onCreate(Bundle state){super.onCreate(state);build();}

    private void build(){
        LinearLayout root=Ui.vertical(this);root.setBackgroundColor(palette.bg);root.addView(topBar("Recovery","Create a new PIN without decrypting records",true,null,null));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout page=Ui.vertical(this);page.setPadding(Ui.dp(this,18),Ui.dp(this,14),Ui.dp(this,18),Ui.dp(this,28));
        LinearLayout warning=Ui.card(this);warning.addView(Ui.heading(this,"Offline recovery"));
        TextView info=Ui.text(this,"Enter the exact 12 words you wrote down during setup. The phrase is checked locally and is never stored.",14,palette.muted);info.setLineSpacing(0,1.15f);warning.addView(info,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,7,0,0));page.addView(warning);
        phrase=Ui.multiLine(this,"word1 word2 … word12",512,4);field(page,"12-word recovery phrase",phrase);
        pin=Ui.pin(this,"New 6–12 digit PIN");confirm=Ui.pin(this,"Confirm new PIN");field(page,"New PIN",pin);field(page,"Confirm new PIN",confirm);
        recover=Ui.primary(this,"Recover and set new PIN");recover.setOnClickListener(v->recover());page.addView(recover,Ui.margins(this,Ui.MATCH,Ui.dp(this,52),0,18,0,0));
        TextView note=Ui.text(this,"If both the PIN and recovery phrase are lost, nobody—including the developer—can recover the encrypted vault.",13,palette.warning);note.setLineSpacing(0,1.15f);page.addView(note,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,18,0,0));
        scroll.addView(page,centeredScrollParams(720));root.addView(scroll,new LinearLayout.LayoutParams(Ui.MATCH,0,1));safeContentView(root);
    }

    private void recover(){
        String normalized=RecoveryPhrase.normalize(phrase.getText().toString());
        String first=pin.getText().toString(),second=confirm.getText().toString();
        if(!TextUtils.equals(first,second)){message("PIN entries do not match");return;}
        char[] newPin=first.toCharArray();recover.setEnabled(false);recover.setText("Checking recovery phrase…");
        task=SecureWork.submit(()->{SecurityManager.get(this).recoverAndChangePin(normalized,newPin);return Boolean.TRUE;},(result,failure)->{
            Arrays.fill(newPin,'\0');phrase.setText("");pin.setText("");confirm.setText("");recover.setEnabled(true);recover.setText("Recover and set new PIN");
            if(failure!=null){error(failure);return;}setResult(RESULT_OK);finish();
        });
    }

    @Override protected void onDestroy(){if(task!=null)task.cancel();if(phrase!=null)phrase.setText("");super.onDestroy();}
    @Override protected void clearSensitiveUi(){if(task!=null)task.cancel();if(phrase!=null)phrase.setText("");if(pin!=null)pin.setText("");if(confirm!=null)confirm.setText("");}
}
