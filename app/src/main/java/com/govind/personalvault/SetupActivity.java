package com.govind.personalvault;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.govind.personalvault.security.RecoveryPhrase;
import com.govind.personalvault.security.SecureWork;
import com.govind.personalvault.security.SecurityManager;
import com.govind.personalvault.ui.Ui;

import java.util.Arrays;

public final class SetupActivity extends BaseActivity {
    private EditText pin;
    private EditText confirmPin;
    private TextView phraseView;
    private EditText confirmWord3;
    private EditText confirmWord9;
    private CheckBox confirmed;
    private Button create;
    private Button generate;
    private String recoveryPhrase;
    private SecureWork.Task task;
    private boolean leaving;

    @Override protected boolean requiresUnlockedVault() { return false; }

    @Override protected void onResume(){
        super.onResume();
        if(!leaving&&SecurityManager.get(this).isSetUp()&&!com.govind.personalvault.security.VaultSession.isUnlocked()){
            leaving=true;startActivity(new Intent(this,LockActivity.class));finish();
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (SecurityManager.get(this).isSetUp()) {
            leaving=true;startActivity(new Intent(this,LockActivity.class)); finish(); return;
        }
        build();
    }

    private void build() {
        LinearLayout root=Ui.vertical(this); root.setBackgroundColor(palette.bg);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout page=Ui.vertical(this); page.setPadding(Ui.dp(this,20),Ui.dp(this,22),Ui.dp(this,20),Ui.dp(this,30));

        page.addView(Ui.brandMark(this, 96));
        TextView word=Ui.title(this,"AEGIS");word.setGravity(Gravity.CENTER);word.setLetterSpacing(0.18f);
        page.addView(word);
        TextView sub=Ui.text(this,"Private vault",14,palette.muted);sub.setGravity(Gravity.CENTER);sub.setLetterSpacing(0.12f);
        page.addView(sub,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,4,0,16));
        TextView badge=Ui.badge(this,"OFFLINE • ENCRYPTED",palette.accent); page.addView(badge,Ui.margins(this,Ui.WRAP,Ui.WRAP,0,0,0,14));
        page.addView(Ui.title(this,"Create your private vault"));
        TextView intro=Ui.text(this,"Passwords and private notes stay on this device. No account, cloud, ads, or network permission.",15,palette.muted);
        intro.setLineSpacing(0,1.18f); page.addView(intro,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,8,0,20));

        LinearLayout security=Ui.card(this);
        TextView shield=Ui.text(this,"256",28,palette.accent); shield.setTypeface(Typeface.DEFAULT,Typeface.BOLD); shield.setGravity(Gravity.CENTER);
        shield.setBackground(Ui.roundRect(this,Ui.withAlpha(palette.accent,22),18,1,Ui.withAlpha(palette.accent,80)));
        security.addView(shield,new LinearLayout.LayoutParams(Ui.MATCH,Ui.dp(this,62)));
        TextView detail=Ui.text(this,"A random 256-bit vault key encrypts every sensitive field with authenticated AES-GCM. Your PIN never encrypts records directly.",14,palette.muted);
        detail.setLineSpacing(0,1.15f); security.addView(detail,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,14,0,0)); page.addView(security);

        page.addView(Ui.space(this,22)); page.addView(Ui.heading(this,"1. Save your recovery phrase"));
        TextView recoveryInfo=Ui.text(this,"Write all 12 words on paper in the exact order. The app will show them once and will never store the phrase. It resets the PIN for this installed vault; it is not a cloud backup and cannot restore erased app data.",14,palette.muted);
        recoveryInfo.setLineSpacing(0,1.15f); page.addView(recoveryInfo,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,5,0,12));
        generate=Ui.secondary(this,"Generate 12-word phrase"); generate.setOnClickListener(v->generatePhrase()); page.addView(generate);
        phraseView=Ui.text(this,"No phrase generated yet",16,palette.muted); phraseView.setTypeface(Typeface.MONOSPACE); phraseView.setLineSpacing(Ui.dp(this,5),1f);
        phraseView.setPadding(Ui.dp(this,16),Ui.dp(this,16),Ui.dp(this,16),Ui.dp(this,16)); phraseView.setBackground(Ui.roundRect(this,palette.raised,16,1,palette.line));
        page.addView(phraseView,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,12,0,0));
        LinearLayout wordCheck=Ui.horizontal(this);confirmWord3=Ui.edit(this,"Word #3",24);confirmWord9=Ui.edit(this,"Word #9",24);
        wordCheck.addView(confirmWord3,new LinearLayout.LayoutParams(0,Ui.dp(this,56),1));LinearLayout.LayoutParams word9Params=new LinearLayout.LayoutParams(0,Ui.dp(this,56),1);word9Params.leftMargin=Ui.dp(this,8);wordCheck.addView(confirmWord9,word9Params);
        page.addView(Ui.label(this,"Confirm recovery words #3 and #9"),Ui.margins(this,Ui.MATCH,Ui.WRAP,2,14,2,6));page.addView(wordCheck);

        page.addView(Ui.space(this,22)); page.addView(Ui.heading(this,"2. Create a PIN"));
        TextView pinInfo=Ui.text(this,"Use 6–12 digits. Repeated incorrect attempts trigger an increasing security delay.",14,palette.muted);
        page.addView(pinInfo,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,5,0,4));
        pin=Ui.pin(this,"6–12 digit PIN"); confirmPin=Ui.pin(this,"Confirm PIN");
        field(page,"PIN",pin); field(page,"Confirm PIN",confirmPin);

        confirmed=new CheckBox(this); confirmed.setText("I wrote down all 12 recovery words in order"); confirmed.setTextColor(palette.text); confirmed.setTextSize(14);
        confirmed.setButtonTintList(android.content.res.ColorStateList.valueOf(palette.accent)); confirmed.setPadding(0,Ui.dp(this,12),0,Ui.dp(this,10)); page.addView(confirmed);
        create=Ui.primary(this,"Create secure vault"); create.setOnClickListener(v->createVault()); page.addView(create);
        TextView footer=Ui.text(this,"Designed and developed by Govind",12,palette.muted); footer.setGravity(Gravity.CENTER); page.addView(footer,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,24,0,0));

        scroll.addView(page,centeredScrollParams(760)); root.addView(scroll,new LinearLayout.LayoutParams(Ui.MATCH,0,1)); safeContentView(root);
    }

    private void generatePhrase() {
        try {
            recoveryPhrase=RecoveryPhrase.generate(this);
            phraseView.setText(numbered(recoveryPhrase)); phraseView.setTextColor(palette.text);
            confirmed.setChecked(false);confirmWord3.setText("");confirmWord9.setText("");
        } catch(Exception error){error(error);}
    }

    private void createVault() {
        if(recoveryPhrase==null){message("Generate and save the recovery phrase first");return;}
        if(!confirmed.isChecked()){message("Confirm that you wrote down all 12 words");return;}
        String[] words=RecoveryPhrase.normalize(recoveryPhrase).split(" ");
        if(words.length!=12||!words[2].equals(RecoveryPhrase.normalize(confirmWord3.getText().toString()))||!words[8].equals(RecoveryPhrase.normalize(confirmWord9.getText().toString()))){message("Recovery words #3 and #9 do not match");return;}
        String first=pin.getText().toString(), second=confirmPin.getText().toString();
        if(!TextUtils.equals(first,second)){message("PIN entries do not match");return;}
        char[] pinChars=first.toCharArray();
        setBusy(true);
        final String phrase=recoveryPhrase;
        task=SecureWork.submit(()->{SecurityManager.get(this).initialize(pinChars,phrase);return Boolean.TRUE;},(result,failure)->{
            setBusy(false); pin.setText(""); confirmPin.setText(""); Arrays.fill(pinChars,'\0');
            if(failure!=null){error(failure);return;}
            recoveryPhrase=null; phraseView.setText("");
            leaving=true;Intent vault=new Intent(this,VaultActivity.class); vault.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK); startActivity(vault);
        });
    }

    private void setBusy(boolean busy){create.setEnabled(!busy);generate.setEnabled(!busy);create.setText(busy?"Creating encrypted vault…":"Create secure vault");}
    private String numbered(String phrase){String[] words=RecoveryPhrase.normalize(phrase).split(" ");StringBuilder out=new StringBuilder();for(int i=0;i<words.length;i++){if(i>0)out.append(i%3==0?'\n':' ');out.append(i+1).append('.').append(words[i]);if(i%3!=2)out.append("   ");}return out.toString();}

    @Override protected void onDestroy(){if(task!=null)task.cancel();recoveryPhrase=null;if(phraseView!=null)phraseView.setText("");super.onDestroy();}
    @Override protected void clearSensitiveUi(){
        if(task!=null)task.cancel();
        if(recoveryPhrase!=null){recoveryPhrase=null;if(phraseView!=null){phraseView.setText("Generate a new phrase to continue");phraseView.setTextColor(palette.muted);}if(confirmed!=null)confirmed.setChecked(false);}
        if(pin!=null)pin.setText("");if(confirmPin!=null)confirmPin.setText("");if(confirmWord3!=null)confirmWord3.setText("");if(confirmWord9!=null)confirmWord9.setText("");
    }
}
