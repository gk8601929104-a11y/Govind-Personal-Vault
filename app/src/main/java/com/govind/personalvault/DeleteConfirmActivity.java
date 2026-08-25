package com.govind.personalvault;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.govind.personalvault.data.VaultDb;
import com.govind.personalvault.model.VaultItem;
import com.govind.personalvault.security.DraftStore;
import com.govind.personalvault.ui.Ui;

/** Rotation-safe confirmation screen; avoids retaining an Activity-backed dialog window. */
public final class DeleteConfirmActivity extends BaseActivity {
    private String itemId;
    private Button delete;
    private TextView itemMessage;
    private VaultDb.Task task;

    @Override protected void onCreate(Bundle state){super.onCreate(state);itemId=getIntent().getStringExtra("item_id");build();loadTitle();}

    private void build(){
        LinearLayout root=Ui.vertical(this);root.setGravity(Gravity.CENTER);root.setBackgroundColor(palette.bg);root.setPadding(Ui.dp(this,22),Ui.dp(this,22),Ui.dp(this,22),Ui.dp(this,22));
        LinearLayout card=Ui.card(this);TextView badge=Ui.badge(this,"PERMANENT",palette.danger);card.addView(badge);
        card.addView(Ui.title(this,"Delete encrypted item?"),Ui.margins(this,Ui.MATCH,Ui.WRAP,0,14,0,0));
        itemMessage=Ui.text(this,"Opening encrypted item…",15,palette.muted);itemMessage.setLineSpacing(0,1.15f);card.addView(itemMessage,Ui.margins(this,Ui.MATCH,Ui.WRAP,0,8,0,18));
        delete=Ui.danger(this,"Delete permanently");delete.setEnabled(false);delete.setOnClickListener(v->confirm());card.addView(delete);
        Button cancel=Ui.secondary(this,"Cancel");cancel.setOnClickListener(v->finish());card.addView(cancel,Ui.margins(this,Ui.MATCH,Ui.dp(this,50),0,9,0,0));
        root.addView(card,centeredPanelParams(560));safeContentView(root);
    }

    private void loadTitle(){if(itemId==null){finish();return;}task=VaultDb.get(this).getAsync(itemId,(item,error)->{if(error!=null){DeleteConfirmActivity.this.error(error);finish();return;}if(item==null){message("Item no longer exists");finish();return;}itemMessage.setText("“"+item.title+"” will be permanently removed from this device. This action cannot be undone.");item.title="";item.username="";item.secret="";item.url="";item.notes="";delete.setEnabled(true);});}

    private void confirm(){if(itemId==null){finish();return;}delete.setEnabled(false);delete.setText("Deleting…");task=VaultDb.get(this).deleteAsync(itemId,(deleted,error)->{if(error!=null){delete.setEnabled(true);delete.setText("Delete permanently");DeleteConfirmActivity.this.error(error);return;}DraftStore.clear(this,VaultItem.PASSWORD+"|"+itemId);DraftStore.clear(this,VaultItem.NOTE+"|"+itemId);setResult(RESULT_OK);finish();});}
    @Override protected void clearSensitiveUi(){itemId=null;if(itemMessage!=null)itemMessage.setText("Reopen the item after unlocking to confirm deletion.");if(delete!=null)delete.setEnabled(false);}
    @Override protected void onDestroy(){if(task!=null)task.cancel();super.onDestroy();}
}
