package com.govind.personalvault;

import android.content.Context;
import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
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
    private static final SecureRandom RANDOM = new SecureRandom();
    private String kind;
    private String itemId;
    private String draftKey;
    private VaultItem loadedItem;
    private EditText title;
    private EditText username;
    private EditText secret;
    private EditText url;
    private EditText cvv;
    private EditText notes;
    private EditText tags;
    private Button save;
    private Button reveal;
    private Button favoriteButton;
    private Button categoryButton;
    private String selectedCategory = "Personal";
    private boolean favorite;
    private boolean loaded;
    private boolean saved;
    private boolean secretVisible;
    private VaultDb.Task task;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        kind = getIntent().getStringExtra("kind");
        if (!VaultItem.validKind(kind)) kind = VaultItem.PASSWORD;
        itemId = getIntent().getStringExtra("item_id");
        draftKey = kind + "|" + (itemId == null ? "new" : itemId);
        build();
        if (itemId == null) {
            loaded = true;
            restoreDraft();
            String prefill = getIntent().getStringExtra("prefill_secret");
            if (prefill != null && !prefill.isEmpty() && secret != null) secret.setText(prefill);
            focusTitleForNewItem();
        } else loadItem();
    }

    @Override protected void onResume() {
        super.onResume();
        if (loaded && VaultSession.isUnlocked() && fieldsAreEmpty()) restoreDraft();
    }

    private void build() {
        boolean password = VaultItem.PASSWORD.equals(kind);
        boolean card = VaultItem.CARD.equals(kind);
        boolean note = VaultItem.NOTE.equals(kind);
        String heading = itemId == null
                ? (password ? "New login" : card ? "New card" : "New note")
                : (password ? "Edit login" : card ? "Edit card" : "Edit note");
        LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(palette.bg);
        root.addView(topBar(heading, "Encrypted with AES-256-GCM before it is written to this device.", true, null, null));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = Ui.vertical(this);
        page.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 28));

        title = Ui.edit(this, "Title", 200);
        field(page, "Title", title);

        username = Ui.username(this, card ? "Name on card" : "Username or email", 300);
        secret = Ui.secret(this, card ? "Card number" : "Password", 1024);
        url = Ui.username(this, card ? "MM/YY" : "https://example.com", 2048);
        cvv = Ui.secret(this, "CVV", 12);
        notes = Ui.multiLine(this, note ? "Write your private note" : "Notes", 32768, note ? 10 : 4);
        tags = Ui.edit(this, "Add tag, press comma", 200);

        if (password || card) {
            field(page, card ? "Cardholder" : "Username", username);
            field(page, card ? "Number" : "Password", secret);
            if (password) {
                LinearLayout secretActions = Ui.horizontal(this);
                Button generate = Ui.secondary(this, "Generate");
                generate.setOnClickListener(v -> generatePassword());
                secretActions.addView(generate, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
                reveal = Ui.secondary(this, "Show");
                reveal.setOnClickListener(v -> toggleSecret());
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 48));
                rp.leftMargin = Ui.dp(this, 8);
                secretActions.addView(reveal, rp);
                page.addView(secretActions, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
            }
            field(page, card ? "Expiry" : "Website", url);
            if (card) field(page, "CVV", cvv);
        } else {
            username.setVisibility(View.GONE);
            secret.setVisibility(View.GONE);
            url.setVisibility(View.GONE);
            cvv.setVisibility(View.GONE);
            field(page, "Note", notes);
        }

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

        field(page, "Tags", tags);
        if (!note) field(page, "Notes", notes);

        save = Ui.primary(this, "Save");
        save.setOnClickListener(v -> saveItem());
        page.addView(save, Ui.margins(this, Ui.MATCH, Ui.dp(this, 52), 0, 18, 0, 0));
        Button discard = Ui.secondary(this, "Cancel");
        discard.setOnClickListener(v -> {
            saved = true;
            DraftStore.clear(this, draftKey);
            clearSensitiveUi();
            finish();
        });
        page.addView(discard, Ui.margins(this, Ui.MATCH, Ui.dp(this, 48), 0, 8, 0, 0));
        scroll.addView(page, centeredScrollParams(760));
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        safeContentView(root);
    }

    private void loadItem() {
        save.setEnabled(false);
        save.setText("Opening…");
        task = VaultDb.get(this).getAsync(itemId, (item, error) -> {
            if (error != null) {
                EntryEditorActivity.this.error(error);
                finish();
                return;
            }
            if (item == null) {
                message("Item no longer exists");
                finish();
                return;
            }
            loadedItem = item.copy();
            setFields(item);
            loaded = true;
            save.setEnabled(true);
            save.setText("Save");
            restoreDraft();
        });
    }

    private void setFields(VaultItem item) {
        title.setText(item.title);
        username.setText(item.username);
        secret.setText(item.secret);
        url.setText(item.url);
        tags.setText(item.tags);
        selectedCategory = VaultItem.normalizeCategory(item.category);
        favorite = item.favorite;
        if (VaultItem.CARD.equals(kind)) {
            String[] parts = splitCvv(item.notes);
            cvv.setText(parts[0]);
            notes.setText(parts[1]);
        } else {
            notes.setText(item.notes);
        }
        favoriteButton.setText(favorite ? "☑  Favorite" : "☐  Favorite");
        categoryButton.setText(selectedCategory + "  ▾");
    }

    private void saveItem() {
        String requestedTitle = title.getText().toString().trim();
        if (requestedTitle.isEmpty()) {
            message("Title is required");
            title.requestFocus();
            return;
        }
        boolean credential = VaultItem.PASSWORD.equals(kind) || VaultItem.CARD.equals(kind);
        VaultItem item = loadedItem == null ? new VaultItem() : loadedItem.copy();
        item.id = itemId == null ? "" : itemId;
        item.kind = kind;
        item.title = requestedTitle;
        item.username = credential ? username.getText().toString() : "";
        item.secret = credential ? secret.getText().toString() : "";
        item.url = credential ? url.getText().toString().trim() : "";
        item.notes = VaultItem.CARD.equals(kind) ? joinCvv(text(cvv), text(notes)) : notes.getText().toString();
        item.category = selectedCategory;
        item.favorite = favorite;
        item.tags = tags.getText().toString().trim();
        save.setEnabled(false);
        save.setText("Encrypting…");
        task = VaultDb.get(this).saveAsync(item, (id, error) -> {
            save.setEnabled(true);
            save.setText("Save");
            if (error != null) {
                EntryEditorActivity.this.error(error);
                return;
            }
            saved = true;
            DraftStore.clear(this, draftKey);
            message("Added to vault");
            clearSensitiveUi();
            finish();
        });
    }

    private void generatePassword() {
        secret.setText(GeneratorActivity.create(20));
        secret.setSelection(secret.length());
        message("Strong 20-character password generated");
    }

    private void toggleSecret() {
        secretVisible = !secretVisible;
        secret.setTransformationMethod(secretVisible
                ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        secret.setSelection(secret.length());
        reveal.setText(secretVisible ? "Hide" : "Show");
    }

    private void focusTitleForNewItem() {
        title.postDelayed(() -> {
            if (isFinishing() || isDestroyed() || itemId != null) return;
            if (title.requestFocus()) {
                title.setSelection(title.length());
                InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.showSoftInput(title, 0);
            }
        }, 220L);
    }

    private void restoreDraft() {
        try {
            Map<String, String> values = DraftStore.load(this, draftKey);
            if (values.isEmpty()) return;
            title.setText(values.get("title"));
            username.setText(values.get("username"));
            secret.setText(values.get("secret"));
            url.setText(values.get("url"));
            notes.setText(values.get("notes"));
            if (tags != null && values.get("tags") != null) tags.setText(values.get("tags"));
            if (cvv != null && values.get("cvv") != null) cvv.setText(values.get("cvv"));
            message("Encrypted draft restored");
        } catch (Exception error) {
            DraftStore.clear(this, draftKey);
        }
    }

    private void saveDraft() {
        if (saved || !loaded || !VaultSession.isUnlocked() || fieldsAreEmpty()) return;
        HashMap<String, String> values = new HashMap<String, String>();
        values.put("title", text(title));
        values.put("username", text(username));
        values.put("secret", text(secret));
        values.put("url", text(url));
        values.put("notes", text(notes));
        values.put("tags", text(tags));
        values.put("cvv", text(cvv));
        try { DraftStore.save(this, draftKey, values); } catch (Exception ignored) { }
    }

    private boolean fieldsAreEmpty() {
        return text(title).isEmpty() && text(username).isEmpty() && text(secret).isEmpty()
                && text(url).isEmpty() && text(notes).isEmpty() && text(tags).isEmpty() && text(cvv).isEmpty();
    }

    private String text(EditText field) { return field == null ? "" : field.getText().toString(); }

    private static String joinCvv(String cvvValue, String notesValue) {
        String cvvTrim = cvvValue == null ? "" : cvvValue.trim();
        String notesTrim = notesValue == null ? "" : notesValue;
        if (cvvTrim.isEmpty()) return notesTrim;
        return "CVV\t" + cvvTrim + "\n" + notesTrim;
    }

    private static String[] splitCvv(String notesValue) {
        if (notesValue != null && notesValue.startsWith("CVV\t")) {
            int nl = notesValue.indexOf('\n');
            if (nl < 0) return new String[]{notesValue.substring(4), ""};
            return new String[]{notesValue.substring(4, nl), notesValue.substring(nl + 1)};
        }
        return new String[]{"", notesValue == null ? "" : notesValue};
    }

    @Override protected void onStop() { saveDraft(); super.onStop(); }

    @Override protected void clearSensitiveUi() {
        if (title != null) title.setText("");
        if (username != null) username.setText("");
        if (secret != null) secret.setText("");
        if (url != null) url.setText("");
        if (notes != null) notes.setText("");
        if (tags != null) tags.setText("");
        if (cvv != null) cvv.setText("");
        if (loadedItem != null) {
            loadedItem.title = "";
            loadedItem.username = "";
            loadedItem.secret = "";
            loadedItem.url = "";
            loadedItem.notes = "";
            loadedItem.tags = "";
        }
    }

    @Override protected void onDestroy() {
        if (task != null) task.cancel();
        clearSensitiveUi();
        super.onDestroy();
    }
}
