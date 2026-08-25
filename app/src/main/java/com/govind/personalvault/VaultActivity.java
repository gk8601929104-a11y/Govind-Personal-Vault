package com.govind.personalvault;

import android.content.Intent;
import android.graphics.Typeface;
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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.govind.personalvault.data.VaultDb;
import com.govind.personalvault.media.MediaRepository;
import com.govind.personalvault.model.MediaItemRecord;
import com.govind.personalvault.model.VaultItem;
import com.govind.personalvault.security.VaultSession;
import com.govind.personalvault.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class VaultActivity extends BaseActivity {
    private static final int DELETE_REQUEST = 73;
    private static final long SEARCH_DEBOUNCE_MS = 300L;
    private static final String TAB_OVERVIEW = "overview";
    private static final String TAB_FILES = "files";
    private static final String FILTER_ALL = "All";
    private static final String FILTER_FAVORITES = "Favorites";

    private boolean active;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable pendingSearch = () -> {
        if (active && VaultSession.isUnlocked()) loadCurrent();
    };
    private final ArrayList<Uri> pendingImportUris = new ArrayList<>();

    private String selectedTab = TAB_OVERVIEW;
    private String selectedFilter = FILTER_ALL;
    private ActivityResultLauncher<String[]> picker;
    private LinearLayout overviewPanel;
    private LinearLayout headerActions;
    private LinearLayout chipsRow;
    private View searchBox;
    private EditText search;
    private TextView clearSearch;
    private TextView pageTitle;
    private TextView pageSubtitle;
    private TextView countPasswords;
    private TextView countNotes;
    private TextView countCards;
    private TextView countFiles;
    private Button addButton;
    private Button emptyAction;
    private TextView emptyTitle;
    private TextView emptySubtitle;
    private LinearLayout emptyState;
    private ListView list;
    private ItemAdapter adapter;
    private FileAdapter fileAdapter;
    private VaultDb.Task listTask;
    private VaultDb.Task countTask;
    private MediaRepository.Task importTask;
    private LinearLayout[] navItems;
    private String[] navIds;
    private boolean suppressSearch;
    private boolean importing;
    private long loadGeneration;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        picker = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                this::onFilesSelected);
        build();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!TAB_OVERVIEW.equals(selectedTab)) {
                    selectTab(TAB_OVERVIEW);
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        active = VaultSession.isUnlocked();
        if (active) loadCurrent();
    }

    @Override protected void onStop() {
        active = false;
        searchHandler.removeCallbacks(pendingSearch);
        super.onStop();
    }

    private void build() {
        LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(palette.bg);

        LinearLayout top = Ui.vertical(this);
        top.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 4));
        pageTitle = Ui.display(this, "Vault");
        pageTitle.setTextSize(28);
        pageTitle.setSingleLine(true);
        pageTitle.setMaxLines(1);
        pageTitle.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(pageTitle);

        LinearLayout subRow = Ui.horizontal(this);
        pageSubtitle = Ui.text(this, "Encrypted on this device.", 13, palette.muted);
        pageSubtitle.setMaxLines(2);
        subRow.addView(pageSubtitle, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        headerActions = Ui.horizontal(this);
        Button settings = Ui.pill(this, "Settings", false);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        headerActions.addView(settings);
        addButton = Ui.primary(this, "+ New");
        addButton.setLayoutParams(new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40)));
        addButton.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        addButton.setOnClickListener(v -> primaryAction());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40));
        addParams.leftMargin = Ui.dp(this, 8);
        headerActions.addView(addButton, addParams);
        subRow.addView(headerActions);
        top.addView(subRow, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
        root.addView(top);

        searchBox = buildSearchBox();
        root.addView(searchBox, Ui.margins(this, Ui.MATCH, Ui.WRAP, 18, 8, 18, 0));

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setClipToPadding(false);
        chipScroll.setPadding(0, 0, Ui.dp(this, 18), 0);
        chipsRow = Ui.horizontal(this);
        chipScroll.addView(chipsRow);
        root.addView(chipScroll, Ui.margins(this, Ui.MATCH, Ui.WRAP, 12, 10, 12, 4));

        overviewPanel = Ui.vertical(this);
        overviewPanel.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 8));
        TextView overviewHint = Ui.text(this, "AES-256-GCM · stays on this phone", 12, palette.muted);
        overviewPanel.addView(overviewHint);
        LinearLayout row1 = Ui.horizontal(this);
        LinearLayout cardPasswords = statCard("0", "Passwords", v -> selectTab(VaultItem.PASSWORD));
        countPasswords = (TextView) cardPasswords.getTag();
        LinearLayout cardNotes = statCard("0", "Notes", v -> selectTab(VaultItem.NOTE));
        countNotes = (TextView) cardNotes.getTag();
        row1.addView(cardPasswords, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
        gap.leftMargin = Ui.dp(this, 10);
        row1.addView(cardNotes, gap);
        overviewPanel.addView(row1, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
        LinearLayout row2 = Ui.horizontal(this);
        LinearLayout cardCards = statCard("0", "Cards", v -> selectTab(VaultItem.CARD));
        countCards = (TextView) cardCards.getTag();
        LinearLayout cardFiles = statCard("0", "Files", v -> selectTab(TAB_FILES));
        countFiles = (TextView) cardFiles.getTag();
        row2.addView(cardCards, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
        gap2.leftMargin = Ui.dp(this, 10);
        row2.addView(cardFiles, gap2);
        overviewPanel.addView(row2, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));
        Button lock = Ui.secondary(this, "Lock now");
        lock.setOnClickListener(v -> lockNow());
        overviewPanel.addView(lock, Ui.margins(this, Ui.MATCH, Ui.dp(this, 48), 0, 22, 0, 0));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), Ui.dp(this, 8));
        adapter = new ItemAdapter();
        fileAdapter = new FileAdapter();
        list.setAdapter(adapter);

        emptyState = Ui.vertical(this);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(Ui.dp(this, 28), Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24));
        TextView shield = Ui.text(this, "◉", 28, palette.muted);
        shield.setGravity(Gravity.CENTER);
        emptyState.addView(shield);
        emptyTitle = Ui.heading(this, "Nothing here yet");
        emptyTitle.setGravity(Gravity.CENTER);
        emptyState.addView(emptyTitle, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));
        emptySubtitle = Ui.text(this, "", 14, palette.muted);
        emptySubtitle.setGravity(Gravity.CENTER);
        emptyState.addView(emptySubtitle, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 16));
        emptyAction = Ui.primary(this, "+ New");
        emptyAction.setOnClickListener(v -> primaryAction());
        emptyState.addView(emptyAction, centeredPanelParams(240));

        FrameLayout body = new FrameLayout(this);
        body.addView(overviewPanel, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        body.addView(list, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        body.addView(emptyState, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        root.addView(body, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));

        LinearLayout navWrap = Ui.vertical(this);
        View hairline = new View(this);
        hairline.setBackgroundColor(Ui.withAlpha(palette.line, 180));
        navWrap.addView(hairline, new LinearLayout.LayoutParams(Ui.MATCH, Math.max(1, Ui.dp(this, 1) / 2 == 0 ? 1 : Ui.dp(this, 1))));
        LinearLayout nav = buildNav();
        nav.setBackgroundColor(palette.surface);
        navWrap.addView(nav);
        root.addView(navWrap);
        safeContentView(root);
        selectTab(TAB_OVERVIEW);
    }

    private LinearLayout statCard(String count, String label, View.OnClickListener tap) {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        card.setOnClickListener(tap);
        TextView value = Ui.text(this, count, 28, palette.text);
        value.setTypeface(Ui.serif());
        TextView name = Ui.text(this, label, 13, palette.muted);
        card.addView(value);
        card.addView(name, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));
        card.setTag(value);
        return card;
    }

    private LinearLayout buildNav() {
        LinearLayout nav = Ui.horizontal(this);
        nav.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        nav.setBackgroundColor(palette.surface);
        String[] labels = {"Overview", "Passwords", "Notes", "Cards", "Files"};
        String[] ids = {TAB_OVERVIEW, VaultItem.PASSWORD, VaultItem.NOTE, VaultItem.CARD, TAB_FILES};
        String[] glyphs = {"▦", "⌁", "✎", "▭", "▤"};
        navIds = ids;
        navItems = new LinearLayout[ids.length];
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            LinearLayout item = Ui.vertical(this);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            TextView icon = Ui.text(this, glyphs[i], 16, palette.muted);
            icon.setGravity(Gravity.CENTER);
            TextView label = Ui.text(this, labels[i], 10, palette.muted);
            label.setGravity(Gravity.CENTER);
            item.addView(icon);
            item.addView(label, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 2, 0, 0));
            item.setOnClickListener(v -> selectTab(id));
            nav.addView(item, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
            navItems[i] = item;
        }
        return nav;
    }

    private View buildSearchBox() {
        LinearLayout box = Ui.horizontal(this);
        box.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 6), 0);
        box.setBackground(Ui.roundRect(this, palette.raised, 16, 1, Ui.withAlpha(palette.line, 140)));
        TextView glyph = Ui.text(this, "⌕", 16, palette.muted);
        box.addView(glyph);
        search = Ui.edit(this, "Filter this list", 160);
        search.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        search.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        box.addView(search, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1));
        clearSearch = Ui.text(this, "×", 22, palette.muted);
        clearSearch.setGravity(Gravity.CENTER);
        clearSearch.setVisibility(View.GONE);
        clearSearch.setOnClickListener(v -> search.setText(""));
        box.addView(clearSearch, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearSearch.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                if (suppressSearch) return;
                searchHandler.removeCallbacks(pendingSearch);
                searchHandler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        return box;
    }

    private void selectTab(String tab) {
        selectedTab = tab;
        selectedFilter = FILTER_ALL;
        suppressSearch = true;
        if (search != null) search.setText("");
        suppressSearch = false;
        boolean overview = TAB_OVERVIEW.equals(tab);
        boolean files = TAB_FILES.equals(tab);
        overviewPanel.setVisibility(overview ? View.VISIBLE : View.GONE);
        searchBox.setVisibility(overview ? View.GONE : View.VISIBLE);
        chipsRow.getParent();
        ((View) chipsRow.getParent()).setVisibility(overview ? View.GONE : View.VISIBLE);
        addButton.setVisibility(overview ? View.GONE : View.VISIBLE);
        if (VaultItem.PASSWORD.equals(tab)) {
            pageTitle.setText("Passwords");
            pageSubtitle.setText("Logins encrypted on this device.");
            addButton.setText("+ New login");
            emptySubtitle.setText("No logins yet. Add a site and the vault will keep the secret.");
            emptyAction.setText("+ New login");
        } else if (VaultItem.NOTE.equals(tab)) {
            pageTitle.setText("Notes");
            pageSubtitle.setText("Private writing, sealed at rest.");
            addButton.setText("+ New note");
            emptySubtitle.setText("Your first secure note lives only in this vault.");
            emptyAction.setText("+ New note");
        } else if (VaultItem.CARD.equals(tab)) {
            pageTitle.setText("Cards");
            pageSubtitle.setText("Numbers, CVVs, and expiry — never in plaintext on disk.");
            addButton.setText("+ New card");
            emptySubtitle.setText("Store a card when you need it, not in a notes app.");
            emptyAction.setText("+ New card");
        } else if (files) {
            pageTitle.setText("Files");
            pageSubtitle.setText("Upload, encrypt, open inside the vault.");
            addButton.setText("+ Encrypt file");
            emptySubtitle.setText("Drop a file into the vault. It is encrypted before it is stored.");
            emptyAction.setText("+ Encrypt file");
        } else {
            pageTitle.setText("Vault");
            pageSubtitle.setText("Your vault, only on this phone.");
            emptySubtitle.setText("");
        }
        rebuildChips();
        for (int i = 0; i < navIds.length; i++) {
            boolean on = navIds[i].equals(tab);
            LinearLayout item = navItems[i];
            TextView icon = (TextView) item.getChildAt(0);
            TextView label = (TextView) item.getChildAt(1);
            int color = on ? palette.text : palette.muted;
            icon.setTextColor(color);
            label.setTextColor(color);
            label.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        if (active) loadCurrent();
    }

    private void rebuildChips() {
        chipsRow.removeAllViews();
        String[] filters;
        if (TAB_FILES.equals(selectedTab)) {
            filters = new String[]{FILTER_ALL, "Photo", "Video", "Audio", "Docs"};
        } else {
            filters = new String[]{FILTER_FAVORITES, FILTER_ALL, "Personal", "Work", "Finance", "Shopping", "Social", "Travel", "Other"};
        }
        for (int i = 0; i < filters.length; i++) {
            final String filter = filters[i];
            Button chip = Ui.pill(this, filter, filter.equals(selectedFilter));
            chip.setOnClickListener(v -> {
                selectedFilter = filter;
                rebuildChips();
                loadCurrent();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 36));
            if (i > 0) params.leftMargin = Ui.dp(this, 6);
            chipsRow.addView(chip, params);
        }
    }

    private void primaryAction() {
        if (TAB_FILES.equals(selectedTab)) {
            if (importing) {
                message("Finish the current import first");
                return;
            }
            picker.launch(new String[]{"*/*"});
            return;
        }
        if (TAB_OVERVIEW.equals(selectedTab)) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        openEditor(null);
    }

    private void loadCurrent() {
        if (!active || !VaultSession.isUnlocked()) return;
        loadCounts();
        if (TAB_OVERVIEW.equals(selectedTab)) {
            list.setVisibility(View.GONE);
            emptyState.setVisibility(View.GONE);
            return;
        }
        if (TAB_FILES.equals(selectedTab)) {
            list.setAdapter(fileAdapter);
            loadFiles();
            return;
        }
        list.setAdapter(adapter);
        loadItems();
    }

    private void loadCounts() {
        if (countTask != null) countTask.cancel();
        countTask = VaultDb.get(this).countsAsync((counts, error) -> {
            if (!active || counts == null) return;
            if (error != null) {
                if (VaultSession.isUnlocked()) error(error);
                return;
            }
            if (countPasswords != null) countPasswords.setText(String.valueOf(counts.passwords));
            if (countNotes != null) countNotes.setText(String.valueOf(counts.notes));
            if (countCards != null) countCards.setText(String.valueOf(counts.cards));
            if (countFiles != null) countFiles.setText(String.valueOf(counts.media + counts.documents));
        });
    }

    private void loadItems() {
        if (listTask != null) listTask.cancel();
        long generation = ++loadGeneration;
        String query = search == null ? "" : search.getText().toString();
        listTask = VaultDb.get(this).listAsync(selectedTab, query, 500, (items, error) -> {
            if (!active || generation != loadGeneration) return;
            if (error != null) {
                if (VaultSession.isUnlocked()) error(error);
                return;
            }
            ArrayList<VaultItem> shown = new ArrayList<>();
            if (items != null) {
                for (VaultItem item : items) {
                    if (matchesFilter(item)) shown.add(item);
                    else item.clearSensitive();
                }
            }
            adapter.replace(shown);
            boolean empty = shown.isEmpty();
            emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            list.setVisibility(empty ? View.GONE : View.VISIBLE);
        });
    }

    private boolean matchesFilter(VaultItem item) {
        if (FILTER_ALL.equals(selectedFilter)) return true;
        if (FILTER_FAVORITES.equals(selectedFilter)) return item.favorite;
        return selectedFilter.equalsIgnoreCase(item.category);
    }

    private void loadFiles() {
        if (listTask != null) listTask.cancel();
        String query = search == null ? "" : search.getText().toString();
        String kind = "files";
        if ("Photo".equals(selectedFilter)) kind = "image";
        else if ("Video".equals(selectedFilter)) kind = "video";
        else if ("Audio".equals(selectedFilter)) kind = "audio";
        else if ("Docs".equals(selectedFilter)) kind = "document";
        listTask = VaultDb.get(this).listMediaAsync(query, kind, 400, (items, error) -> {
            if (!active) {
                clearMedia(items);
                return;
            }
            if (error != null) {
                error(error);
                clearMedia(items);
                return;
            }
            fileAdapter.replace(items == null ? new ArrayList<>() : items);
            boolean empty = fileAdapter.getCount() == 0;
            emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            list.setVisibility(empty ? View.GONE : View.VISIBLE);
        });
    }

    private void openEditor(VaultItem item) {
        Intent intent = new Intent(this, EntryEditorActivity.class);
        intent.putExtra("kind", selectedTab);
        if (item != null) intent.putExtra("item_id", item.id);
        startActivity(intent);
    }

    private void openFile(MediaItemRecord item) {
        Intent intent = new Intent(
                this,
                item.isDocument()
                        ? SecureDocumentActivity.class
                        : item.isImage()
                        ? SecureImageViewerActivity.class
                        : SecureMediaPlayerActivity.class);
        intent.putExtra("media_id", item.id);
        startActivity(intent);
    }

    private void lockNow() {
        adapter.clear();
        fileAdapter.clear();
        VaultSession.lock();
        Intent lock = new Intent(this, LockActivity.class);
        lock.putExtra("overlay", true);
        startActivity(lock);
    }

    private void askDelete(VaultItem item) {
        Intent intent = new Intent(this, DeleteConfirmActivity.class);
        intent.putExtra("item_id", item.id);
        startActivityForResult(intent, DELETE_REQUEST);
    }

    private void onFilesSelected(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        pendingImportUris.clear();
        for (Uri uri : uris) {
            if (uri == null) continue;
            pendingImportUris.add(uri);
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException | UnsupportedOperationException ignored) { }
        }
        if (VaultSession.isUnlocked()) beginImport();
        else message("Unlock the vault to finish importing");
    }

    private void beginImport() {
        if (importing || pendingImportUris.isEmpty() || !VaultSession.isUnlocked()) return;
        importing = true;
        ArrayList<Uri> selected = new ArrayList<>(pendingImportUris);
        ArrayList<Uri> media = new ArrayList<>();
        ArrayList<Uri> documents = new ArrayList<>();
        for (Uri uri : selected) {
            String mime = getContentResolver().getType(uri);
            if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/"))) {
                media.add(uri);
            } else {
                documents.add(uri);
            }
        }
        message("Encrypting " + selected.size() + " file(s)…");
        if (!media.isEmpty()) {
            importTask = MediaRepository.importAsync(this, media, (c, t, n, b) -> { }, (summary, error) -> {
                if (!documents.isEmpty()) {
                    importTask = MediaRepository.importDocumentsAsync(this, documents, (c, t, n, b) -> { },
                            (docSummary, docError) -> finishImport(selected, merge(summary, docSummary), error != null ? error : docError));
                } else {
                    finishImport(selected, summary, error);
                }
            });
            return;
        }
        importTask = MediaRepository.importDocumentsAsync(this, documents, (c, t, n, b) -> { },
                (summary, error) -> finishImport(selected, summary, error));
    }

    private static MediaRepository.ImportSummary merge(MediaRepository.ImportSummary a, MediaRepository.ImportSummary b) {
        int imported = (a == null ? 0 : a.imported) + (b == null ? 0 : b.imported);
        int failed = (a == null ? 0 : a.failed) + (b == null ? 0 : b.failed);
        return new MediaRepository.ImportSummary(imported, failed, new ArrayList<>());
    }

    private void finishImport(List<Uri> selected, MediaRepository.ImportSummary summary, Exception error) {
        importing = false;
        releasePermissions(selected);
        pendingImportUris.clear();
        if (!active) return;
        if (error != null) error(error);
        else if (summary != null && summary.failed == 0) message("Added to vault");
        else if (summary != null) message(summary.imported + " imported • " + summary.failed + " failed");
        loadCurrent();
    }

    private void releasePermissions(List<Uri> uris) {
        for (Uri uri : uris) {
            try {
                getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException | UnsupportedOperationException ignored) { }
        }
    }

    private static void clearMedia(List<MediaItemRecord> items) {
        if (items == null) return;
        for (MediaItemRecord item : items) if (item != null) item.clearSensitive();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == DELETE_REQUEST && result == RESULT_OK) {
            message("Item deleted");
            loadCurrent();
        }
    }

    @Override protected void clearSensitiveUi() {
        active = false;
        loadGeneration++;
        if (adapter != null) adapter.clear();
        if (fileAdapter != null) fileAdapter.clear();
        if (emptyState != null) emptyState.setVisibility(View.GONE);
    }

    @Override protected void onDestroy() {
        searchHandler.removeCallbacksAndMessages(null);
        if (listTask != null) listTask.cancel();
        if (countTask != null) countTask.cancel();
        super.onDestroy();
    }

    private final class ItemAdapter extends BaseAdapter {
        private final ArrayList<VaultItem> items = new ArrayList<>();
        void replace(List<VaultItem> values) {
            clear();
            if (values != null) for (VaultItem value : values) items.add(value.copy());
            notifyDataSetChanged();
        }
        void clear() {
            for (VaultItem item : items) item.clearSensitive();
            items.clear();
            notifyDataSetChanged();
        }
        @Override public int getCount() { return items.size(); }
        @Override public VaultItem getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View reusable, ViewGroup parent) {
            VaultItem item = getItem(position);
            LinearLayout row = Ui.vertical(VaultActivity.this);
            row.setPadding(Ui.dp(VaultActivity.this, 16), Ui.dp(VaultActivity.this, 14), Ui.dp(VaultActivity.this, 16), Ui.dp(VaultActivity.this, 14));
            LinearLayout top = Ui.horizontal(VaultActivity.this);
            TextView title = Ui.text(VaultActivity.this, item.title, 16, palette.text);
            title.setTypeface(Ui.serif());
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(title, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
            TextView badge = Ui.pill(VaultActivity.this, item.favorite ? "★ " + item.category : item.category, false);
            badge.setTextSize(11);
            top.addView(badge);
            row.addView(top);
            String secondary = VaultItem.CARD.equals(item.kind)
                    ? item.maskedSecret()
                    : VaultItem.PASSWORD.equals(item.kind)
                    ? (item.username.isEmpty() ? item.url : item.username)
                    : item.notes;
            if (secondary.length() > 90) secondary = secondary.substring(0, 90);
            TextView sub = Ui.text(VaultActivity.this, secondary, 13, palette.muted);
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(sub, Ui.margins(VaultActivity.this, Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
            row.setOnClickListener(v -> openEditor(item));
            row.setOnLongClickListener(v -> { askDelete(item); return true; });
            return row;
        }
    }

    private final class FileAdapter extends BaseAdapter {
        private final ArrayList<MediaItemRecord> items = new ArrayList<>();
        void replace(List<MediaItemRecord> values) {
            clear();
            if (values != null) for (MediaItemRecord value : values) items.add(value.copy());
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
            LinearLayout row = Ui.vertical(VaultActivity.this);
            row.setPadding(Ui.dp(VaultActivity.this, 16), Ui.dp(VaultActivity.this, 14), Ui.dp(VaultActivity.this, 16), Ui.dp(VaultActivity.this, 14));
            LinearLayout top = Ui.horizontal(VaultActivity.this);
            TextView title = Ui.text(VaultActivity.this, item.originalName, 16, palette.text);
            title.setTypeface(Ui.serif());
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(title, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
            TextView badge = Ui.pill(VaultActivity.this, item.kindLabel(), false);
            badge.setTextSize(11);
            top.addView(badge);
            row.addView(top);
            TextView sub = Ui.text(VaultActivity.this, item.mimeType, 13, palette.muted);
            sub.setMaxLines(1);
            row.addView(sub, Ui.margins(VaultActivity.this, Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
            row.setOnClickListener(v -> openFile(item));
            return row;
        }
    }
}
