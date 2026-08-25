package com.govind.personalvault;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.govind.personalvault.data.VaultDb;
import com.govind.personalvault.model.VaultItem;
import com.govind.personalvault.security.DraftStore;
import com.govind.personalvault.security.VaultSession;
import com.govind.personalvault.ui.Ui;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

public final class EntryEditorActivity extends BaseActivity {
    private static final SecureRandom RANDOM=new SecureRandom();
    private String kind;
    private String itemId;
    private String draftKey;
    private VaultItem loadedItem;
    private EditText title;
    private EditText username;
    private EditText secret;
    private EditText url;
    private EditText notes;
    private Button save;
    private Button reveal;
    private Button favoriteButton;
    private String selectedCategory = "Personal";
    private boolean favorite;
    private LinearLayout categoryRow;
    private boolean loaded;
    private boolean saved;
    private boolean secretVisible;
    private VaultDb.Task task;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        kind=getIntent().getStringExtra("kind");if(!VaultItem.validKind(kind))kind=VaultItem.PASSWORD;
        itemId=getIntent().getStringExtra("item_id");draftKey=kind+"|"+(itemId==null?"new":itemId);
        build();
        if(itemId==null){loaded=true;restoreDraft();focusTitleForNewItem();}else loadItem();
    }

    @Override protected void onResume(){super.onResume();if(loaded&&VaultSession.isUnlocked()&&fieldsAreEmpty())restoreDraft();}

    private void build(){
        boolean password=VaultItem.PASSWORD.equals(kind);
        boolean card=VaultItem.CARD.equals(kind);
        String heading=itemId==null?(password?"New login":card?"New card":"New note"):(password?"Edit login":card?"Edit card":"Edit note");
        LinearLayout root=Ui.vertical(this);root.setBackgroundColor(palette.bg);root.addView(topBar(heading,"Encrypted before it is stored",true,null,null));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        LinearLayout page=Ui.vertical(this);page.setPadding(Ui.dp(this,18),Ui.dp(this,10),Ui.dp(this,18),Ui.dp(this,28));

        title=Ui.edit(this,password?"e.g. IIT portal":card?"Visa personal":"Note title",200);field(page,"Title",title);
        username=Ui.username(this,card?"Name on card":"Username or email",300);
        secret=Ui.secret(this,card?"Card number":"Password",1024);
        url=Ui.username(this,card?"MM/YY":"https://example.com",2048);
        notes=Ui.multiLine(this,card?"CVV and extra notes":password?"Additional private details":"Write your private note",32768,password||card?5:12);
        if(password||card){
            field(page,card?"Name on card":"Username or email",username);
            field(page,card?"Card number":"Password",secret);
            if(password){
                LinearLayout secretActions=Ui.horizontal(this);Button generate=Ui.secondary(this,"Generate strong password");generate.setOnClickListener(v->generatePassword());secretActions.addView(generate,new LinearLayout.LayoutParams(0,Ui.dp(this,48),1));
                reveal=Ui.secondary(this,"Show");reveal.setOnClickListener(v->toggleSecret());LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(Ui.dp(this,96),Ui.dp(this,48));rp.leftMargin=Ui.dp(this,8);secretActions.addView(reveal,rp);page.addView(secretActions,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,9,0,0));
            }
            field(page,card?"Expiry":"Website (optional)",url);field(page,card?"CVV / notes":"Private notes (optional)",notes);
        }else{username.setVisibility(View.GONE);secret.setVisibility(View.GONE);url.setVisibility(View.GONE);field(page,"Private note",notes);}

        page.addView(Ui.label(this,"Category"),Ui.margins(this,Ui.MATCH,Ui.WRAP,2,16,2,8));
        HorizontalScrollView cats=new HorizontalScrollView(this);cats.setHorizontalScrollBarEnabled(false);
        LinearLayout catRow=Ui.horizontal(this);categoryRow=catRow;cats.addView(catRow);page.addView(cats);
        rebuildCategories();
        favoriteButton=Ui.secondary(this,"☆ Favorite");
        favoriteButton.setOnClickListener(v->{favorite=!favorite;favoriteButton.setText(favorite?"★ Favorite":"☆ Favorite");});
        page.addView(favoriteButton,Ui.margins(this,Ui.MATCH,Ui.dp(this,44),0,12,0,0));

        save=Ui.primary(this,itemId==null?"Save":"Update");save.setOnClickListener(v->saveItem());page.addView(save,Ui.margins(this,Ui.MATCH,Ui.dp(this,54),0,20,0,0));
        Button discard=Ui.secondary(this,"Cancel");discard.setOnClickListener(v->{saved=true;DraftStore.clear(this,draftKey);clearSensitiveUi();finish();});page.addView(discard,Ui.margins(this,Ui.MATCH,Ui.dp(this,50),0,9,0,0));
        scroll.addView(page,centeredScrollParams(760));root.addView(scroll,new LinearLayout.LayoutParams(Ui.MATCH,0,1));safeContentView(root);
    }

    private void rebuildCategories(){
        if(categoryRow==null)return;
        categoryRow.removeAllViews();
        for(int i=0;i<VaultItem.CATEGORIES.length;i++){
            final String category=VaultItem.CATEGORIES[i];
            Button chip=Ui.pill(this,category,category.equals(selectedCategory));
            chip.setOnClickListener(v->{selectedCategory=category;rebuildCategories();});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(Ui.WRAP,Ui.dp(this,36));
            if(i>0)p.leftMargin=Ui.dp(this,6);
            categoryRow.addView(chip,p);
        }
    }

    private void loadItem(){
        save.setEnabled(false);save.setText("Opening encrypted item…");
        task=VaultDb.get(this).getAsync(itemId,(item,error)->{
            if(error!=null){EntryEditorActivity.this.error(error);finish();return;}
            if(item==null){message("Item no longer exists");finish();return;}
            loadedItem=item.copy();setFields(item);loaded=true;save.setEnabled(true);save.setText("Update encrypted item");restoreDraft();
        });
    }

    private void setFields(VaultItem item){
        title.setText(item.title);username.setText(item.username);secret.setText(item.secret);url.setText(item.url);notes.setText(item.notes);
        selectedCategory=VaultItem.normalizeCategory(item.category);favorite=item.favorite;
        if(favoriteButton!=null)favoriteButton.setText(favorite?"★ Favorite":"☆ Favorite");
        rebuildCategories();
    }

    private void saveItem(){
        String requestedTitle=title.getText().toString().trim();if(requestedTitle.isEmpty()){message("Title is required");title.requestFocus();return;}
        boolean credential=VaultItem.PASSWORD.equals(kind)||VaultItem.CARD.equals(kind);
        VaultItem item=loadedItem==null?new VaultItem():loadedItem.copy();item.id=itemId==null?"":itemId;item.kind=kind;item.title=requestedTitle;
        item.username=credential?username.getText().toString():"";item.secret=credential?secret.getText().toString():"";item.url=credential?url.getText().toString().trim():"";item.notes=notes.getText().toString();
        item.category=selectedCategory;item.favorite=favorite;
        save.setEnabled(false);save.setText("Encrypting and saving…");
        task=VaultDb.get(this).saveAsync(item,(id,error)->{
            save.setEnabled(true);save.setText(itemId==null?"Save":"Update");
            if(error!=null){EntryEditorActivity.this.error(error);return;}
            saved=true;DraftStore.clear(this,draftKey);message("Added to vault");clearSensitiveUi();finish();
        });
    }

    private void generatePassword(){
        final char[] lower="abcdefghijkmnopqrstuvwxyz".toCharArray(),upper="ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray(),digits="23456789".toCharArray(),symbols="!@#$%&*+-=?".toCharArray();
        char[] all=(new String(lower)+new String(upper)+new String(digits)+new String(symbols)).toCharArray();char[] result=new char[20];
        result[0]=pick(lower);result[1]=pick(upper);result[2]=pick(digits);result[3]=pick(symbols);for(int i=4;i<result.length;i++)result[i]=pick(all);
        for(int i=result.length-1;i>0;i--){int j=RANDOM.nextInt(i+1);char temp=result[i];result[i]=result[j];result[j]=temp;}
        secret.setText(result,0,result.length);secret.setSelection(secret.length());java.util.Arrays.fill(result,'\0');message("Strong 20-character password generated");
    }

    private char pick(char[] values){return values[RANDOM.nextInt(values.length)];}
    private void toggleSecret(){secretVisible=!secretVisible;secret.setTransformationMethod(secretVisible?HideReturnsTransformationMethod.getInstance():PasswordTransformationMethod.getInstance());secret.setSelection(secret.length());reveal.setText(secretVisible?"Hide":"Show");}

    private void focusTitleForNewItem(){
        title.postDelayed(()->{
            if(isFinishing()||isDestroyed()||itemId!=null)return;
            if(title.requestFocus()){
                title.setSelection(title.length());
                InputMethodManager keyboard=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
                if(keyboard!=null)keyboard.showSoftInput(title, 0);
            }
        },220L);
    }

    private void restoreDraft(){
        try{Map<String,String> values=DraftStore.load(this,draftKey);if(values.isEmpty())return;title.setText(values.get("title"));username.setText(values.get("username"));secret.setText(values.get("secret"));url.setText(values.get("url"));notes.setText(values.get("notes"));message("Encrypted draft restored");}
        catch(Exception error){DraftStore.clear(this,draftKey);}
    }

    private void saveDraft(){
        if(saved||!loaded||!VaultSession.isUnlocked()||fieldsAreEmpty())return;
        HashMap<String,String> values=new HashMap<String,String>();values.put("title",text(title));values.put("username",text(username));values.put("secret",text(secret));values.put("url",text(url));values.put("notes",text(notes));
        try{DraftStore.save(this,draftKey,values);}catch(Exception ignored){ }
    }

    private boolean fieldsAreEmpty(){return text(title).isEmpty()&&text(username).isEmpty()&&text(secret).isEmpty()&&text(url).isEmpty()&&text(notes).isEmpty();}
    private String text(EditText field){return field==null?"":field.getText().toString();}

    @Override protected void onStop(){saveDraft();super.onStop();}
    @Override protected void clearSensitiveUi(){if(title!=null)title.setText("");if(username!=null)username.setText("");if(secret!=null)secret.setText("");if(url!=null)url.setText("");if(notes!=null)notes.setText("");if(loadedItem!=null){loadedItem.title="";loadedItem.username="";loadedItem.secret="";loadedItem.url="";loadedItem.notes="";}}
    @Override protected void onDestroy(){if(task!=null)task.cancel();clearSensitiveUi();super.onDestroy();}
}
