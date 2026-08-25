package com.govind.personalvault;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.govind.personalvault.data.VaultDb;
import com.govind.personalvault.media.MediaRepository;
import com.govind.personalvault.model.MediaItemRecord;
import com.govind.personalvault.security.VaultSession;
import com.govind.personalvault.ui.Ui;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Programmatic encrypted document vault. Documents stay encrypted until an explicit export. */
public final class DocumentsVaultActivity extends BaseActivity {
    private static final long SEARCH_DELAY_MS = 300L;

    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = this::loadDocuments;
    private final ArrayList<Uri> pendingImportUris = new ArrayList<>();

    private ActivityResultLauncher<String[]> picker;
    private EditText search;
    private View searchBox;
    private TextView clearSearch;
    private TextView count;
    private LinearLayout emptyState;
    private LinearLayout progressPanel;
    private TextView progressTitle;
    private TextView progressDetail;
    private Button importButton;
    private ListView list;
    private DocumentAdapter adapter;
    private VaultDb.Task listTask;
    private MediaRepository.Task importTask;
    private boolean importing;
    private boolean active;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        picker = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                this::onDocumentsSelected);
        build();
        MediaRepository.cleanupAsync(this, (ignored, error) -> { });
    }

    @Override protected void onResume() {
        super.onResume();
        active = VaultSession.isUnlocked();
        if (!active) return;
        loadDocuments();
        if (!pendingImportUris.isEmpty() && !importing) beginImport();
    }

    @Override protected void onStop() {
        active = false;
        searchHandler.removeCallbacks(searchRunnable);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (listTask != null) listTask.cancel();
        if (importTask != null) importTask.cancel();
        if (!pendingImportUris.isEmpty()) releasePermissions(new ArrayList<>(pendingImportUris));
        pendingImportUris.clear();
        if (adapter != null) adapter.clear();
        super.onDestroy();
    }

    @Override protected void clearSensitiveUi() {
        if (adapter != null) adapter.clear();
        if (emptyState != null) emptyState.setVisibility(View.GONE);
    }

    private void build() {
        LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(palette.bg);
        root.addView(topBar(
                "Documents",
                null,
                true,
                "Import",
                v -> launchPicker(),
                "\uD83D\uDD0D",
                v -> toggleSearchBox(searchBox, search)));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));

        LinearLayout header = Ui.vertical(this);
        header.setPadding(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 8));

        LinearLayout titleRow = Ui.horizontal(this);
        TextView heading = Ui.text(this, "Library", 13, palette.muted);
        heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        titleRow.addView(heading, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        count = Ui.badge(this, "0", palette.muted);
        titleRow.addView(count);
        header.addView(titleRow, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 4, 0, 6));
        searchBox = buildSearchBox();
        searchBox.setVisibility(View.GONE);
        header.addView(searchBox);

        progressPanel = Ui.card(this);
        progressPanel.setVisibility(View.GONE);
        progressTitle = Ui.heading(this, "Encrypting documents…");
        progressDetail = Ui.text(this, "Preparing secure import", 13, palette.muted);
        progressPanel.addView(progressTitle);
        progressPanel.addView(progressDetail, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 7, 0, 0));
        header.addView(progressPanel, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));
        list.addHeaderView(header, null, false);

        emptyState = Ui.vertical(this);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(Ui.dp(this, 28), Ui.dp(this, 34), Ui.dp(this, 28), Ui.dp(this, 34));
        TextView icon = Ui.text(this, "▤", 58, palette.muted);
        icon.setGravity(Gravity.CENTER);
        emptyState.addView(icon);
        TextView emptyTitle = Ui.heading(this, "No encrypted documents yet");
        emptyTitle.setGravity(Gravity.CENTER);
        emptyState.addView(emptyTitle, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 12, 0, 0));
        TextView emptyText = Ui.text(this, "Import a PDF, office file, or text document.", 14, palette.muted);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setLineSpacing(0, 1.18f);
        emptyState.addView(emptyText, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 18));
        importButton = Ui.primary(this, "Import documents");
        importButton.setOnClickListener(v -> launchPicker());
        emptyState.addView(importButton, centeredPanelParams(300));
        list.addFooterView(emptyState, null, false);

        adapter = new DocumentAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));

        safeContentView(root);
    }

    private View buildSearchBox() {
        LinearLayout box = Ui.horizontal(this);
        box.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        box.setBackground(Ui.roundRect(this, palette.raised, 18, 1, Ui.withAlpha(palette.line, 140)));
        search = Ui.edit(this, "Search", 180);
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        box.addView(search, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        clearSearch = Ui.text(this, "×", 28, palette.muted);
        clearSearch.setGravity(Gravity.CENTER);
        clearSearch.setContentDescription("Clear document search");
        clearSearch.setVisibility(View.GONE);
        clearSearch.setOnClickListener(v -> search.setText(""));
        box.addView(clearSearch, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearSearch.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                searchHandler.removeCallbacks(searchRunnable);
                searchHandler.postDelayed(searchRunnable, SEARCH_DELAY_MS);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        return box;
    }

    private void launchPicker() {
        if (importing) {
            message("Finish the current document import first");
            return;
        }
        // Use the Storage Access Framework broadly, then enforce the non-media policy after the
        // provider returns metadata. This also supports providers that report uncommon MIME types.
        picker.launch(new String[]{"*/*"});
    }

    private void onDocumentsSelected(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        pendingImportUris.clear();
        for (Uri uri : uris) {
            if (uri == null) continue;
            pendingImportUris.add(uri);
            try {
                getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException | UnsupportedOperationException ignored) { }
        }
        if (VaultSession.isUnlocked()) beginImport();
        else message("Unlock the vault to finish importing the selected documents");
    }

    private void beginImport() {
        if (importing || pendingImportUris.isEmpty() || !VaultSession.isUnlocked()) return;
        importing = true;
        progressPanel.setVisibility(View.VISIBLE);
        importButton.setEnabled(false);
        ArrayList<Uri> selected = new ArrayList<>(pendingImportUris);
        importTask = MediaRepository.importDocumentsAsync(
                this,
                selected,
                (current, total, name, bytes) -> {
                    if (!active) return;
                    progressTitle.setText("Encrypting " + current + " of " + total);
                    progressDetail.setText(name + " • " + humanSize(bytes) + " protected");
                },
                (summary, error) -> {
                    importing = false;
                    releasePermissions(selected);
                    pendingImportUris.clear();
                    if (!active) return;
                    progressPanel.setVisibility(View.GONE);
                    importButton.setEnabled(true);
                    if (error != null) {
                        DocumentsVaultActivity.this.error(error);
                    } else if (summary != null) {
                        if (summary.failed == 0) {
                            message(summary.imported + " document(s) encrypted");
                        } else {
                            message(summary.imported + " imported • " + summary.failed + " failed");
                        }
                    }
                    loadDocuments();
                });
    }

    private void releasePermissions(List<Uri> uris) {
        for (Uri uri : uris) {
            try {
                getContentResolver().releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException | UnsupportedOperationException ignored) { }
        }
    }

    private void loadDocuments() {
        if (!active || !VaultSession.isUnlocked() || adapter == null) return;
        if (listTask != null) listTask.cancel();
        String query = search == null ? "" : search.getText().toString();
        listTask = VaultDb.get(this).listMediaAsync(query, "document", 500, (items, error) -> {
            if (!active) {
                clearRecords(items);
                return;
            }
            if (error != null) {
                DocumentsVaultActivity.this.error(error);
                clearRecords(items);
                return;
            }
            adapter.replace(items == null ? new ArrayList<>() : items);
            int size = adapter.getCount();
            count.setText(size + (size == 1 ? " ITEM" : " ITEMS"));
            emptyState.setVisibility(size == 0 ? View.VISIBLE : View.GONE);
        });
    }

    private void open(MediaItemRecord item) {
        startActivity(new Intent(this, SecureDocumentActivity.class)
                .putExtra("media_id", item.id));
    }

    private static void clearRecords(List<MediaItemRecord> items) {
        if (items == null) return;
        for (MediaItemRecord item : items) if (item != null) item.clearSensitive();
    }

    private final class DocumentAdapter extends BaseAdapter {
        private final ArrayList<MediaItemRecord> items = new ArrayList<>();

        void replace(List<MediaItemRecord> values) {
            clear();
            if (values != null) items.addAll(values);
            notifyDataSetChanged();
        }

        void clear() {
            for (MediaItemRecord item : items) item.clearSensitive();
            items.clear();
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public MediaItemRecord getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View reusable, ViewGroup parent) {
            MediaItemRecord item = getItem(position);
            LinearLayout outer = Ui.vertical(DocumentsVaultActivity.this);
            outer.setPadding(
                    Ui.dp(DocumentsVaultActivity.this, 2),
                    Ui.dp(DocumentsVaultActivity.this, 5),
                    Ui.dp(DocumentsVaultActivity.this, 2),
                    Ui.dp(DocumentsVaultActivity.this, 5));

            LinearLayout card = Ui.card(DocumentsVaultActivity.this);
            card.setOnClickListener(v -> open(item));
            card.setContentDescription("Document, " + item.originalName);

            LinearLayout row = Ui.horizontal(DocumentsVaultActivity.this);
            TextView icon = Ui.text(
                    DocumentsVaultActivity.this,
                    documentIcon(item),
                    30,
                    documentColor(item));
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(Ui.roundRect(
                    DocumentsVaultActivity.this,
                    Ui.withAlpha(documentColor(item), 24),
                    14,
                    1,
                    Ui.withAlpha(documentColor(item), 90)));
            row.addView(icon, new LinearLayout.LayoutParams(
                    Ui.dp(DocumentsVaultActivity.this, 58),
                    Ui.dp(DocumentsVaultActivity.this, 58)));

            LinearLayout labels = Ui.vertical(DocumentsVaultActivity.this);
            labels.setPadding(Ui.dp(DocumentsVaultActivity.this, 13), 0, 0, 0);
            TextView name = Ui.heading(DocumentsVaultActivity.this, item.originalName);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(name);
            TextView detail = Ui.text(
                    DocumentsVaultActivity.this,
                    typeLabel(item) + " • " + humanSize(item.size),
                    12,
                    palette.muted);
            detail.setMaxLines(1);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(detail, Ui.margins(DocumentsVaultActivity.this, Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
            row.addView(labels, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
            card.addView(row);

            TextView updated = Ui.text(
                    DocumentsVaultActivity.this,
                    "Protected " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(new Date(item.updatedAt)),
                    11,
                    palette.muted);
            card.addView(updated, Ui.margins(DocumentsVaultActivity.this, Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));
            outer.addView(card);
            return outer;
        }
    }

    private static String documentIcon(MediaItemRecord item) {
        String extension = extension(item.originalName);
        if ("pdf".equals(extension)) return "PDF";
        if ("doc".equals(extension) || "docx".equals(extension)) return "DOC";
        if ("xls".equals(extension) || "xlsx".equals(extension) || "csv".equals(extension)) return "XLS";
        if ("ppt".equals(extension) || "pptx".equals(extension)) return "PPT";
        if ("txt".equals(extension) || "rtf".equals(extension) || item.mimeType.startsWith("text/")) return "TXT";
        if ("zip".equals(extension) || "7z".equals(extension) || "rar".equals(extension)) return "ZIP";
        return "FILE";
    }

    private int documentColor(MediaItemRecord item) {
        String icon = documentIcon(item);
        if ("PDF".equals(icon)) return palette.danger;
        if ("DOC".equals(icon)) return Color.rgb(110, 165, 255);
        if ("XLS".equals(icon)) return palette.accent;
        if ("PPT".equals(icon)) return palette.warning;
        return palette.muted;
    }

    private static String typeLabel(MediaItemRecord item) {
        String extension = extension(item.originalName);
        if (!extension.isEmpty()) return extension.toUpperCase(Locale.ROOT);
        return item.mimeType == null ? "DOCUMENT" : item.mimeType;
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes / 1024.0;
        if (value < 1024.0) return String.format(Locale.US, "%.1f KB", value);
        value /= 1024.0;
        if (value < 1024.0) return String.format(Locale.US, "%.1f MB", value);
        return String.format(Locale.US, "%.2f GB", value / 1024.0);
    }
}
