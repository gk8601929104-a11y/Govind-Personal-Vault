package com.govind.personalvault;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.govind.personalvault.data.VaultDb;
import com.govind.personalvault.media.MediaRepository;
import com.govind.personalvault.model.MediaItemRecord;
import com.govind.personalvault.ui.Ui;

/** Rotation-safe permanent encrypted-media deletion confirmation. */
public final class MediaDeleteConfirmActivity extends BaseActivity {
    private String mediaId;
    private TextView messageView;
    private Button delete;
    private VaultDb.Task metadataTask;
    private MediaRepository.Task deleteTask;
    private MediaItemRecord record;
    private boolean active;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        mediaId = getIntent().getStringExtra("media_id");
        if (mediaId == null) {
            finish();
            return;
        }
        build();
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
    }

    @Override protected void onStop() {
        active = false;
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (metadataTask != null) metadataTask.cancel();
        if (deleteTask != null) deleteTask.cancel();
        clearSensitiveUi();
        super.onDestroy();
    }

    @Override protected void clearSensitiveUi() {
        if (record != null) record.clearSensitive();
        record = null;
    }

    private void build() {
        LinearLayout root = Ui.vertical(this);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(palette.bg);
        root.setPadding(Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22));
        LinearLayout card = Ui.card(this);
        card.addView(Ui.badge(this, "PERMANENT", palette.danger));
        card.addView(Ui.title(this, "Delete encrypted file?"), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
        messageView = Ui.text(this, "Opening encrypted metadata…", 15, palette.muted);
        messageView.setLineSpacing(0, 1.15f);
        card.addView(messageView, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 18));
        delete = Ui.danger(this, "Delete permanently");
        delete.setEnabled(false);
        delete.setOnClickListener(v -> confirm());
        card.addView(delete);
        Button cancel = Ui.secondary(this, "Cancel");
        cancel.setOnClickListener(v -> finish());
        card.addView(cancel, Ui.margins(this, Ui.MATCH, Ui.dp(this, 50), 0, 9, 0, 0));
        root.addView(card, centeredPanelParams(560));
        safeContentView(root);
    }

    private void load() {
        metadataTask = VaultDb.get(this).getMediaAsync(mediaId, (item, error) -> {
            if (!active) {
                if (item != null) item.clearSensitive();
                return;
            }
            if (error != null) {
                MediaDeleteConfirmActivity.this.error(error);
                finish();
                return;
            }
            if (item == null) {
                message("Encrypted file no longer exists");
                finish();
                return;
            }
            record = item;
            String kind = record.isDocument() ? "document" : "media";
            messageView.setText("Delete “" + record.originalName + "” from the encrypted vault? This "
                    + kind + " cannot be recovered. Existing exported copies are not affected.");
            delete.setEnabled(true);
        });
    }

    private void confirm() {
        delete.setEnabled(false);
        delete.setText("Deleting…");
        deleteTask = MediaRepository.deleteAsync(this, mediaId, (removed, error) -> {
            if (!active) return;
            if (error != null) {
                MediaDeleteConfirmActivity.this.error(error);
                delete.setText("Delete permanently");
                delete.setEnabled(true);
                return;
            }
            boolean document = record != null && record.isDocument();
            message(removed != null && removed
                    ? (document ? "Encrypted document deleted" : "Encrypted media deleted")
                    : (document ? "Document no longer exists" : "Media no longer exists"));
            setResult(RESULT_OK);
            finish();
        });
    }
}
