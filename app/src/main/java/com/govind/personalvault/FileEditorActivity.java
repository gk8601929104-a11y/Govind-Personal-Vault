package com.govind.personalvault;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.govind.personalvault.data.VaultDb;
import com.govind.personalvault.media.MediaRepository;
import com.govind.personalvault.model.MediaItemRecord;
import com.govind.personalvault.model.VaultItem;
import com.govind.personalvault.security.VaultSession;
import com.govind.personalvault.ui.Ui;

public final class FileEditorActivity extends BaseActivity {
    private EditText title;
    private EditText tags;
    private EditText notes;
    private TextView fileName;
    private Button favoriteButton;
    private Button save;
    private Button categoryButton;
    private String selectedCategory = "Personal";
    private boolean favorite;
    private Uri picked;
    private String editId;
    private ActivityResultLauncher<String> picker;
    private MediaRepository.Task importTask;
    private VaultDb.Task metaTask;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        editId = getIntent().getStringExtra("media_id");
        picker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            picked = uri;
            String name = uri.getLastPathSegment();
            if (name == null) name = "Selected file";
            int cut = name.lastIndexOf('/');
            if (cut >= 0 && cut < name.length() - 1) name = name.substring(cut + 1);
            fileName.setText(name);
            if (title.getText().toString().trim().isEmpty()) {
                int dot = name.lastIndexOf('.');
                title.setText(dot > 0 ? name.substring(0, dot) : name);
            }
        });
        build();
        if (editId != null) loadExisting();
    }

    private void build() {
        boolean edit = editId != null;
        LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(palette.bg);
        root.addView(topBar(edit ? "Edit file" : "Encrypt file",
                "Encrypted with AES-256-GCM before it is written to this device.", true, null, null));
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = Ui.vertical(this);
        page.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 28));
        title = Ui.edit(this, "Title", 200);
        field(page, "Title", title);
        fileName = Ui.text(this, edit ? "Stored in the vault" : "Choose file  No file chosen", 14, palette.muted);
        if (!edit) {
            Button choose = Ui.secondary(this, "Choose file");
            choose.setOnClickListener(v -> picker.launch("*/*"));
            page.addView(choose, Ui.margins(this, Ui.MATCH, Ui.dp(this, 48), 0, 12, 0, 0));
        }
        page.addView(fileName, Ui.margins(this, Ui.MATCH, Ui.WRAP, 2, 8, 2, 0));
        page.addView(Ui.label(this, "Category"), Ui.margins(this, Ui.MATCH, Ui.WRAP, 2, 16, 2, 8));
        LinearLayout catFav = Ui.horizontal(this);
        categoryButton = Ui.secondary(this, selectedCategory + "  ▾");
        categoryButton.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("Category")
                .setItems(VaultItem.CATEGORIES, (d, which) -> {
                    selectedCategory = VaultItem.CATEGORIES[which];
                    categoryButton.setText(selectedCategory + "  ▾");
                })
                .show());
        catFav.addView(categoryButton, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
        favoriteButton = Ui.secondary(this, "☐  Favorite");
        favoriteButton.setOnClickListener(v -> {
            favorite = !favorite;
            favoriteButton.setText(favorite ? "☑  Favorite" : "☐  Favorite");
        });
        LinearLayout.LayoutParams favParams = new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 44));
        favParams.leftMargin = Ui.dp(this, 8);
        catFav.addView(favoriteButton, favParams);
        page.addView(catFav);
        tags = Ui.edit(this, "Add tag, press comma", 200);
        field(page, "Tags", tags);
        notes = Ui.multiLine(this, "Notes", 4096, 4);
        field(page, "Notes", notes);
        save = Ui.primary(this, "Save");
        save.setOnClickListener(v -> save());
        page.addView(save, Ui.margins(this, Ui.MATCH, Ui.dp(this, 52), 0, 18, 0, 0));
        Button cancel = Ui.secondary(this, "Cancel");
        cancel.setOnClickListener(v -> finish());
        page.addView(cancel, Ui.margins(this, Ui.MATCH, Ui.dp(this, 48), 0, 8, 0, 0));
        scroll.addView(page, centeredScrollParams(640));
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        safeContentView(root);
    }

    private void rebuildCategories() {
        if (categoryButton != null) categoryButton.setText(selectedCategory + "  ▾");
    }

    private void loadExisting() {
        metaTask = VaultDb.get(this).getMediaAsync(editId, (item, error) -> {
            if (error != null || item == null) {
                message("File no longer exists");
                finish();
                return;
            }
            title.setText(item.displayTitle());
            fileName.setText(item.originalName);
            selectedCategory = VaultItem.normalizeCategory(item.category);
            favorite = item.favorite;
            favoriteButton.setText(favorite ? "☑  Favorite" : "☐  Favorite");
            tags.setText(item.tags);
            notes.setText(item.notes);
            rebuildCategories();
            item.clearSensitive();
        });
    }

    private void save() {
        String heading = title.getText().toString().trim();
        if (heading.isEmpty()) {
            message("Title is required");
            title.requestFocus();
            return;
        }
        if (editId != null) {
            MediaItemRecord meta = new MediaItemRecord();
            meta.id = editId;
            meta.title = heading;
            meta.category = selectedCategory;
            meta.favorite = favorite;
            meta.tags = tags.getText().toString().trim();
            meta.notes = notes.getText().toString();
            save.setEnabled(false);
            metaTask = VaultDb.get(this).updateMediaMetaAsync(meta, (ok, error) -> {
                save.setEnabled(true);
                if (error != null) { FileEditorActivity.this.error(error); return; }
                message("Updated");
                finish();
            });
            return;
        }
        if (picked == null) {
            message("Choose a file first");
            return;
        }
        if (!VaultSession.isUnlocked()) {
            message("Unlock the vault first");
            return;
        }
        save.setEnabled(false);
        save.setText("Encrypting…");
        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
        uris.add(picked);
        importTask = MediaRepository.importAsync(this, uris, (c, t, n, b) -> { }, (summary, error) -> {
            if (error != null) {
                save.setEnabled(true);
                save.setText("Save");
                FileEditorActivity.this.error(error);
                return;
            }
            if (summary == null || summary.ids.isEmpty()) {
                save.setEnabled(true);
                save.setText("Save");
                message("Import failed");
                return;
            }
            MediaItemRecord meta = new MediaItemRecord();
            meta.id = summary.ids.get(0);
            meta.title = heading;
            meta.category = selectedCategory;
            meta.favorite = favorite;
            meta.tags = tags.getText().toString().trim();
            meta.notes = notes.getText().toString();
            metaTask = VaultDb.get(this).updateMediaMetaAsync(meta, (ok, metaError) -> {
                save.setEnabled(true);
                save.setText("Save");
                if (metaError != null) { FileEditorActivity.this.error(metaError); return; }
                message("Added to vault");
                setResult(RESULT_OK);
                finish();
            });
        });
    }

    @Override protected void clearSensitiveUi() {
        if (title != null) title.setText("");
        if (notes != null) notes.setText("");
        if (tags != null) tags.setText("");
    }

    @Override protected void onDestroy() {
        if (importTask != null) importTask.cancel();
        if (metaTask != null) metaTask.cancel();
        super.onDestroy();
    }
}
