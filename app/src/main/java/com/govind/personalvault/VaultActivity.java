package com.govind.personalvault;

import android.content.Intent;
import android.graphics.Typeface;
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
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.govind.personalvault.data.VaultDb;
import com.govind.personalvault.media.MediaRepository;
import com.govind.personalvault.model.MediaItemRecord;
import com.govind.personalvault.model.VaultItem;
import com.govind.personalvault.security.VaultSession;
import com.govind.personalvault.ui.Ui;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public final class VaultActivity extends BaseActivity {
    private static final int DELETE_REQUEST = 73;
    private static final int FILE_EDITOR_REQUEST = 74;
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

    private String selectedTab = TAB_OVERVIEW;
    private String selectedFilter = FILTER_ALL;
    private LinearLayout overviewPanel;
    private LinearLayout chipsRow;
    private View searchBox;
    private EditText search;
    private TextView clearSearch;
    private TextView pageTitle;
    private TextView pageSubtitle;
    private TextView greeting;
    private TextView greetingSub;
    private TextView countPasswords;
    private TextView countNotes;
    private TextView countCards;
    private TextView countFiles;
    private TextView weakCount;
    private TextView reusedCount;
    private TextView favoriteCount;
    private TextView weakHint;
    private TextView reusedHint;
    private TextView favoriteHint;
    private LinearLayout recentList;
    private LinearLayout favoriteList;
    private LinearLayout startCard;
    private Button addButton;
    private Button emptyAction;
    private TextView emptyTitle;
    private TextView emptySubtitle;
    private LinearLayout emptyState;
    private ListView list;
    private LinearLayout fileDetail;
    private TextView fileDetailTitle;
    private TextView fileDetailMeta;
    private ItemAdapter adapter;
    private FileAdapter fileAdapter;
    private VaultDb.Task listTask;
    private VaultDb.Task countTask;
    private MediaRepository.Task actionTask;
    private LinearLayout[] navItems;
    private String[] navIds;
    private LinearLayout drawer;
    private View dim;
    private boolean drawerOpen;
    private boolean suppressSearch;
    private long loadGeneration;
    private MediaItemRecord selectedFile;
    private VaultItem selectedItem;
    private LinearLayout itemDetail;
    private TextView itemDetailTitle;
    private LinearLayout itemDetailBody;
    private TextView drawerCountPasswords;
    private TextView drawerCountNotes;
    private TextView drawerCountCards;
    private TextView drawerCountFiles;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (drawerOpen) {
                    closeDrawer();
                    return;
                }
                if (selectedFile != null) {
                    selectedFile = null;
                    renderFileDetail();
                    return;
                }
                if (selectedItem != null) {
                    selectedItem.clearSensitive();
                    selectedItem = null;
                    loadCurrent();
                    return;
                }
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
        FrameLayout root = new FrameLayout(this);

        LinearLayout shell = Ui.vertical(this);
        shell.setBackgroundColor(palette.bg);

        LinearLayout top = Ui.horizontal(this);
        top.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        Button menu = Ui.iconButton(this, "≡", "Menu");
        menu.setOnClickListener(v -> openDrawer());
        top.addView(menu);
        TextView brand = Ui.text(this, "🛡  Vault", 16, palette.text);
        brand.setTypeface(Ui.serif());
        brand.setPadding(Ui.dp(this, 10), 0, 0, 0);
        top.addView(brand, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        Button searchBtn = Ui.iconButton(this, "⌕", "Search");
        searchBtn.setOnClickListener(v -> {
            if (TAB_OVERVIEW.equals(selectedTab) || "generator".equals(selectedTab)) selectTab(VaultItem.PASSWORD);
            if (search != null) {
                search.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
        top.addView(searchBtn);
        Button lock = Ui.iconButton(this, "⚿", "Lock");
        lock.setOnClickListener(v -> lockNow());
        top.addView(lock);
        shell.addView(top);
        View topLine = new View(this);
        topLine.setBackgroundColor(Ui.withAlpha(palette.line, 180));
        shell.addView(topLine, new LinearLayout.LayoutParams(Ui.MATCH, 1));

        LinearLayout headingRow = Ui.horizontal(this);
        headingRow.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), 0);
        LinearLayout titles = Ui.vertical(this);
        pageTitle = Ui.display(this, "Vault");
        pageTitle.setTextSize(28);
        pageTitle.setSingleLine(true);
        pageTitle.setEllipsize(TextUtils.TruncateAt.END);
        pageSubtitle = Ui.text(this, "", 13, palette.muted);
        pageSubtitle.setMaxLines(2);
        titles.addView(pageTitle);
        titles.addView(pageSubtitle, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
        headingRow.addView(titles, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        addButton = Ui.primary(this, "+ New login");
        addButton.setLayoutParams(new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40)));
        addButton.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        addButton.setOnClickListener(v -> primaryAction());
        headingRow.addView(addButton);
        shell.addView(headingRow);

        searchBox = buildSearchBox();
        shell.addView(searchBox, Ui.margins(this, Ui.MATCH, Ui.WRAP, 18, 10, 18, 0));

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setClipToPadding(false);
        chipScroll.setPadding(0, 0, Ui.dp(this, 18), 0);
        chipsRow = Ui.horizontal(this);
        chipScroll.addView(chipsRow);
        shell.addView(chipScroll, Ui.margins(this, Ui.MATCH, Ui.WRAP, 12, 10, 12, 4));

        FrameLayout body = new FrameLayout(this);
        overviewPanel = buildOverview();
        body.addView(overviewPanel, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        LinearLayout listColumn = Ui.vertical(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), Ui.dp(this, 8));
        adapter = new ItemAdapter();
        fileAdapter = new FileAdapter();
        list.setAdapter(adapter);
        listColumn.addView(list, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        fileDetail = buildFileDetail();
        listColumn.addView(fileDetail);
        itemDetail = buildItemDetail();
        body.addView(itemDetail, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        body.addView(listColumn, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        emptyState = buildEmpty();
        body.addView(emptyState, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        shell.addView(body, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));

        LinearLayout navWrap = Ui.vertical(this);
        View hairline = new View(this);
        hairline.setBackgroundColor(Ui.withAlpha(palette.line, 180));
        navWrap.addView(hairline, new LinearLayout.LayoutParams(Ui.MATCH, 1));
        navWrap.addView(buildNav());
        shell.addView(navWrap);

        root.addView(shell, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        dim = new View(this);
        dim.setBackgroundColor(0x99000000);
        dim.setVisibility(View.GONE);
        dim.setOnClickListener(v -> closeDrawer());
        root.addView(dim, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        drawer = buildDrawer();
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(Ui.dp(this, 300), Ui.MATCH, Gravity.START);
        root.addView(drawer, drawerParams);
        drawer.setVisibility(View.GONE);

        safeContentView(root);
        selectTab(TAB_OVERVIEW);
    }

    private LinearLayout buildOverview() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = Ui.vertical(this);
        page.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 24));
        TextView overLabel = Ui.label(this, "OVERVIEW");
        page.addView(overLabel);
        greeting = Ui.display(this, greetingText());
        greeting.setTextSize(28);
        page.addView(greeting, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));
        greetingSub = Ui.text(this, "Your vault is empty and sealed. Add the first secret.", 14, palette.muted);
        page.addView(greetingSub, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));

        LinearLayout actions = Ui.horizontal(this);
        Button newLogin = Ui.primary(this, "+ New login");
        newLogin.setLayoutParams(new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40)));
        newLogin.setOnClickListener(v -> openEditor(VaultItem.PASSWORD, null));
        Button generate = Ui.secondary(this, "Generate");
        generate.setLayoutParams(new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40)));
        LinearLayout.LayoutParams genParams = new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40));
        genParams.leftMargin = Ui.dp(this, 8);
        generate.setOnClickListener(v -> startActivity(new Intent(this, GeneratorActivity.class)));
        actions.addView(newLogin);
        actions.addView(generate, genParams);
        page.addView(actions, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));

        LinearLayout row1 = Ui.horizontal(this);
        LinearLayout p = overviewTile("⌁", "0", "Passwords", v -> selectTab(VaultItem.PASSWORD));
        countPasswords = (TextView) p.getTag();
        LinearLayout n = overviewTile("✎", "0", "Notes", v -> selectTab(VaultItem.NOTE));
        countNotes = (TextView) n.getTag();
        row1.addView(p, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
        gap.leftMargin = Ui.dp(this, 10);
        row1.addView(n, gap);
        page.addView(row1, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));

        LinearLayout row2 = Ui.horizontal(this);
        LinearLayout c = overviewTile("▭", "0", "Cards", v -> selectTab(VaultItem.CARD));
        countCards = (TextView) c.getTag();
        LinearLayout f = overviewTile("▤", "0", "Files", v -> selectTab(TAB_FILES));
        countFiles = (TextView) f.getTag();
        row2.addView(c, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
        gap2.leftMargin = Ui.dp(this, 10);
        row2.addView(f, gap2);
        page.addView(row2, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));

        TextView[] weak = new TextView[2];
        page.addView(healthCard("Weak passwords", "0", "All logins meet a fair bar.", true, weak));
        weakCount = weak[0];
        weakHint = weak[1];
        TextView[] reused = new TextView[2];
        page.addView(healthCard("Reused secrets", "0", "No reused passwords.", false, reused));
        reusedCount = reused[0];
        reusedHint = reused[1];
        TextView[] fav = new TextView[2];
        page.addView(healthCard("Favorites", "0", "Pinned for quicker access.", false, fav));
        favoriteCount = fav[0];
        favoriteHint = fav[1];

        page.addView(Ui.heading(this, "Recent"), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 22, 0, 8));
        recentList = Ui.vertical(this);
        page.addView(recentList);
        page.addView(Ui.heading(this, "Favorites"), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 18, 0, 8));
        favoriteList = Ui.vertical(this);
        page.addView(favoriteList);

        startCard = Ui.dashedCard(this);
        startCard.addView(Ui.heading(this, "Start with one secret"));
        startCard.addView(Ui.text(this, "The vault never sees your PIN. Items are encrypted with AES-256-GCM before they touch disk.", 13, palette.muted), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 6, 0, 12));
        LinearLayout startRow = Ui.horizontal(this);
        Button addLogin = Ui.primary(this, "Add a login");
        addLogin.setLayoutParams(new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40)));
        addLogin.setOnClickListener(v -> openEditor(VaultItem.PASSWORD, null));
        Button write = Ui.secondary(this, "Write a note");
        write.setLayoutParams(new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40)));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40));
        wp.leftMargin = Ui.dp(this, 8);
        write.setOnClickListener(v -> openEditor(VaultItem.NOTE, null));
        Button openGen = Ui.secondary(this, "Open generator");
        openGen.setLayoutParams(new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40)));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40));
        gp.leftMargin = Ui.dp(this, 8);
        openGen.setOnClickListener(v -> startActivity(new Intent(this, GeneratorActivity.class)));
        startRow.addView(addLogin);
        startRow.addView(write, wp);
        startRow.addView(openGen, gp);
        startCard.addView(startRow);
        page.addView(startCard, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 16, 0, 0));

        scroll.addView(page);
        LinearLayout wrap = Ui.vertical(this);
        wrap.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        return wrap;
    }

    private LinearLayout overviewTile(String glyph, String count, String label, View.OnClickListener tap) {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        card.setOnClickListener(tap);
        LinearLayout top = Ui.horizontal(this);
        top.addView(Ui.iconBubble(this, glyph));
        TextView value = Ui.text(this, count, 22, palette.text);
        value.setTypeface(Ui.serif());
        value.setGravity(Gravity.END);
        top.addView(value, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        card.addView(top);
        TextView name = Ui.text(this, label, 13, palette.muted);
        card.addView(name, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));
        card.setTag(value);
        return card;
    }

    private LinearLayout healthCard(String title, String count, String hint, boolean first, TextView[] slots) {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams params = Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, first ? 14 : 10, 0, 0);
        card.setLayoutParams(params);
        LinearLayout titleRow = Ui.horizontal(this);
        titleRow.addView(Ui.text(this, "🛡  " + title, 13, palette.muted));
        card.addView(titleRow);
        TextView value = Ui.text(this, count, 22, palette.text);
        value.setTypeface(Ui.serif());
        card.addView(value, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));
        TextView sub = Ui.text(this, hint, 12, palette.muted);
        card.addView(sub, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
        if (slots != null && slots.length >= 2) {
            slots[0] = value;
            slots[1] = sub;
        }
        return card;
    }

    private LinearLayout buildEmpty() {
        LinearLayout empty = Ui.vertical(this);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 28), Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24));
        TextView shield = Ui.text(this, "🛡", 22, palette.muted);
        shield.setGravity(Gravity.CENTER);
        int size = Ui.dp(this, 52);
        shield.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        shield.setBackground(Ui.roundRect(this, palette.raised, 99, 1, Ui.withAlpha(palette.line, 140)));
        empty.addView(shield);
        emptyTitle = Ui.heading(this, "Nothing here yet");
        emptyTitle.setGravity(Gravity.CENTER);
        empty.addView(emptyTitle, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
        emptySubtitle = Ui.text(this, "", 14, palette.muted);
        emptySubtitle.setGravity(Gravity.CENTER);
        empty.addView(emptySubtitle, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 16));
        emptyAction = Ui.primary(this, "+ New");
        emptyAction.setOnClickListener(v -> primaryAction());
        empty.addView(emptyAction, centeredPanelParams(220));
        return empty;
    }

    private LinearLayout buildFileDetail() {
        LinearLayout panel = Ui.vertical(this);
        panel.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        panel.setBackgroundColor(palette.surface);
        LinearLayout titleRow = Ui.horizontal(this);
        fileDetailTitle = Ui.heading(this, "");
        titleRow.addView(fileDetailTitle, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        Button edit = Ui.pill(this, "Edit", false);
        edit.setOnClickListener(v -> {
            if (selectedFile == null) return;
            Intent intent = new Intent(this, FileEditorActivity.class);
            intent.putExtra("media_id", selectedFile.id);
            startActivityForResult(intent, FILE_EDITOR_REQUEST);
        });
        titleRow.addView(edit);
        Button more = Ui.pill(this, "···", false);
        more.setOnClickListener(v -> showFileMenu(more));
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 36));
        moreParams.leftMargin = Ui.dp(this, 6);
        titleRow.addView(more, moreParams);
        panel.addView(titleRow);
        fileDetailMeta = Ui.text(this, "", 13, palette.muted);
        fileDetailMeta.setLineSpacing(0, 1.3f);
        panel.addView(fileDetailMeta, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 12));
        LinearLayout actions = Ui.horizontal(this);
        Button download = Ui.secondary(this, "↓ Download");
        download.setOnClickListener(v -> exportSelected(false));
        Button enc = Ui.secondary(this, "Export .enc");
        enc.setOnClickListener(v -> exportSelected(true));
        actions.addView(download, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
        LinearLayout.LayoutParams e = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1);
        e.leftMargin = Ui.dp(this, 8);
        actions.addView(enc, e);
        panel.addView(actions);
        panel.setVisibility(View.GONE);
        return panel;
    }

    private LinearLayout buildItemDetail() {
        LinearLayout panel = Ui.vertical(this);
        panel.setBackgroundColor(palette.bg);
        panel.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 16));
        LinearLayout titleRow = Ui.horizontal(this);
        Button back = Ui.iconButton(this, "‹", "Back");
        back.setOnClickListener(v -> {
            if (selectedItem != null) selectedItem.clearSensitive();
            selectedItem = null;
            loadCurrent();
        });
        titleRow.addView(back);
        itemDetailTitle = Ui.heading(this, "");
        itemDetailTitle.setMaxLines(1);
        itemDetailTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(itemDetailTitle, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        Button more = Ui.iconButton(this, "···", "More");
        more.setOnClickListener(this::showItemMenu);
        titleRow.addView(more);
        panel.addView(titleRow);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        itemDetailBody = Ui.vertical(this);
        scroll.addView(itemDetailBody);
        panel.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        Button edit = Ui.secondary(this, "Edit");
        edit.setOnClickListener(v -> {
            if (selectedItem == null) return;
            openEditor(selectedItem.kind, selectedItem.id);
        });
        panel.addView(edit, Ui.margins(this, Ui.MATCH, Ui.dp(this, 48), 0, 12, 0, 0));
        panel.setVisibility(View.GONE);
        return panel;
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

    private LinearLayout buildDrawer() {
        LinearLayout panel = Ui.vertical(this);
        panel.setBackgroundColor(palette.surface);
        panel.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 12));
        LinearLayout head = Ui.horizontal(this);
        head.addView(Ui.heading(this, "Vault"), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        Button close = Ui.iconButton(this, "×", "Close");
        close.setOnClickListener(v -> closeDrawer());
        head.addView(close);
        panel.addView(head);
        panel.addView(drawerRow("▦", "Overview", null, v -> { closeDrawer(); selectTab(TAB_OVERVIEW); }));
        drawerCountPasswords = addCountedRow(panel, "⌁", "Passwords", VaultItem.PASSWORD);
        drawerCountNotes = addCountedRow(panel, "✎", "Notes", VaultItem.NOTE);
        drawerCountCards = addCountedRow(panel, "▭", "Cards", VaultItem.CARD);
        drawerCountFiles = addCountedRow(panel, "▤", "Files", TAB_FILES);
        panel.addView(drawerRow("⟳", "Generator", null, v -> {
            closeDrawer();
            startActivity(new Intent(this, GeneratorActivity.class));
        }));
        panel.addView(drawerRow("⚙", "Settings", null, v -> {
            closeDrawer();
            startActivity(new Intent(this, SettingsActivity.class));
        }));
        View spacer = new View(this);
        panel.addView(spacer, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        LinearLayout bottom = Ui.horizontal(this);
        Button lock = Ui.secondary(this, "Lock");
        lock.setOnClickListener(v -> { closeDrawer(); lockNow(); });
        bottom.addView(lock, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
        Button theme = Ui.iconButton(this, "☀", "Theme");
        theme.setOnClickListener(v -> {
            VaultPrefs.setLight(this, !VaultPrefs.isLight(this));
            recreate();
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        tp.leftMargin = Ui.dp(this, 8);
        bottom.addView(theme, tp);
        panel.addView(bottom);
        return panel;
    }

    private TextView addCountedRow(LinearLayout panel, String glyph, String label, String tab) {
        LinearLayout row = drawerRow(glyph, label, "0", v -> { closeDrawer(); selectTab(tab); });
        TextView count = (TextView) row.getTag();
        panel.addView(row);
        return count;
    }

    private LinearLayout drawerRow(String glyph, String label, String count, View.OnClickListener tap) {
        LinearLayout row = Ui.horizontal(this);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
        row.setOnClickListener(tap);
        row.addView(Ui.text(this, glyph, 16, palette.text));
        TextView name = Ui.text(this, "  " + label, 15, palette.text);
        row.addView(name, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        if (count != null) {
            TextView value = Ui.text(this, count, 13, palette.muted);
            row.addView(value);
            row.setTag(value);
        }
        return row;
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

    private void openDrawer() {
        drawerOpen = true;
        drawer.setVisibility(View.VISIBLE);
        dim.setVisibility(View.VISIBLE);
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.setVisibility(View.GONE);
        dim.setVisibility(View.GONE);
    }

    private void selectTab(String tab) {
        selectedTab = tab;
        selectedFilter = FILTER_ALL;
        selectedFile = null;
        if (selectedItem != null) {
            selectedItem.clearSensitive();
            selectedItem = null;
        }
        suppressSearch = true;
        if (search != null) search.setText("");
        suppressSearch = false;
        boolean overview = TAB_OVERVIEW.equals(tab);
        boolean files = TAB_FILES.equals(tab);
        overviewPanel.setVisibility(overview ? View.VISIBLE : View.GONE);
        searchBox.setVisibility(overview ? View.GONE : View.VISIBLE);
        ((View) chipsRow.getParent()).setVisibility(overview ? View.GONE : View.VISIBLE);
        addButton.setVisibility(overview ? View.GONE : View.VISIBLE);
        headingRowVisible(!overview);
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
            pageSubtitle.setText("Upload, encrypt, download — or export a .enc backup.");
            addButton.setText("+ Encrypt file");
            emptySubtitle.setText("Drop a document into the vault. It is encrypted before it is stored.");
            emptyAction.setText("+ Encrypt file");
        } else {
            pageTitle.setText("Vault");
            pageSubtitle.setText("");
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

    private void headingRowVisible(boolean show) {
        View parent = (View) pageTitle.getParent().getParent();
        parent.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void rebuildChips() {
        chipsRow.removeAllViews();
        String[] filters = new String[]{FILTER_FAVORITES, FILTER_ALL, "Personal", "Work", "Finance", "Shopping", "Social", "Travel", "Other"};
        for (int i = 0; i < filters.length; i++) {
            final String filter = filters[i];
            String label = FILTER_FAVORITES.equals(filter) ? "★ Favorites" : filter;
            Button chip = Ui.pill(this, label, filter.equals(selectedFilter));
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

    private void showFileMenu(View anchor) {
        if (selectedFile == null) return;
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Open");
        menu.getMenu().add(0, 2, 0, selectedFile.favorite ? "Unstar" : "Star");
        menu.getMenu().add(0, 3, 0, "Delete");
        menu.setOnMenuItemClickListener(item -> {
            if (selectedFile == null) return true;
            if (item.getItemId() == 1) {
                openFile(selectedFile);
                return true;
            }
            if (item.getItemId() == 2) {
                MediaItemRecord meta = selectedFile.copy();
                meta.favorite = !meta.favorite;
                VaultDb.get(this).updateMediaMetaAsync(meta, (ok, error) -> {
                    if (error != null) { error(error); return; }
                    selectedFile.favorite = meta.favorite;
                    renderFileDetail();
                    loadCurrent();
                });
                return true;
            }
            Intent intent = new Intent(this, MediaDeleteConfirmActivity.class);
            intent.putExtra("media_id", selectedFile.id);
            startActivityForResult(intent, DELETE_REQUEST);
            return true;
        });
        menu.show();
    }

    private void showItemMenu(View anchor) {
        if (selectedItem == null) return;
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Edit");
        menu.getMenu().add(0, 2, 0, selectedItem.favorite ? "Unstar" : "Star");
        menu.getMenu().add(0, 3, 0, "Delete");
        menu.setOnMenuItemClickListener(item -> {
            if (selectedItem == null) return true;
            if (item.getItemId() == 1) {
                openEditor(selectedItem.kind, selectedItem.id);
                return true;
            }
            if (item.getItemId() == 2) {
                VaultItem next = selectedItem.copy();
                next.favorite = !next.favorite;
                VaultDb.get(this).saveAsync(next, (id, error) -> {
                    if (error != null) { error(error); return; }
                    selectedItem.favorite = next.favorite;
                    message(next.favorite ? "Starred" : "Unstarred");
                    renderItemDetail();
                    loadCurrent();
                });
                return true;
            }
            askDelete(selectedItem);
            return true;
        });
        menu.show();
    }

    private void primaryAction() {
        if (TAB_FILES.equals(selectedTab)) {
            startActivityForResult(new Intent(this, FileEditorActivity.class), FILE_EDITOR_REQUEST);
            return;
        }
        if (TAB_OVERVIEW.equals(selectedTab)) {
            openEditor(VaultItem.PASSWORD, null);
            return;
        }
        openEditor(selectedTab, null);
    }

    private void loadCurrent() {
        if (!active || !VaultSession.isUnlocked()) return;
        if (selectedItem == null && itemDetail != null) itemDetail.setVisibility(View.GONE);
        loadOverview();
        if (TAB_OVERVIEW.equals(selectedTab)) {
            list.setVisibility(View.GONE);
            emptyState.setVisibility(View.GONE);
            fileDetail.setVisibility(View.GONE);
            if (itemDetail != null) itemDetail.setVisibility(View.GONE);
            ((View) list.getParent()).setVisibility(View.GONE);
            return;
        }
        ((View) list.getParent()).setVisibility(View.VISIBLE);
        if (TAB_FILES.equals(selectedTab)) {
            list.setAdapter(fileAdapter);
            loadFiles();
            return;
        }
        list.setAdapter(adapter);
        loadItems();
    }

    private void loadOverview() {
        if (countTask != null) countTask.cancel();
        countTask = VaultDb.get(this).overviewAsync((data, error) -> {
            if (!active || data == null) return;
            if (error != null) {
                if (VaultSession.isUnlocked()) error(error);
                return;
            }
            int files = data.counts.media + data.counts.documents;
            setCount(countPasswords, data.counts.passwords);
            setCount(countNotes, data.counts.notes);
            setCount(countCards, data.counts.cards);
            setCount(countFiles, files);
            setCount(drawerCountPasswords, data.counts.passwords);
            setCount(drawerCountNotes, data.counts.notes);
            setCount(drawerCountCards, data.counts.cards);
            setCount(drawerCountFiles, files);
            setCount(weakCount, data.weak);
            setCount(reusedCount, data.reused);
            setCount(favoriteCount, data.favorites);
            if (weakHint != null) weakHint.setText(data.weak == 0 ? "All logins meet a fair bar." : "Lengthen or mix these logins.");
            if (reusedHint != null) reusedHint.setText(data.reused == 0 ? "No reused passwords." : "Some logins share a secret.");
            if (favoriteHint != null) favoriteHint.setText(data.favorites == 0 ? "Pinned for quicker access." : "Starred items in this vault.");
            int total = data.counts.passwords + data.counts.notes + data.counts.cards + files;
            greeting.setText(greetingText());
            greetingSub.setText(total == 0
                    ? "Your vault is empty and sealed. Add the first secret."
                    : "Sealed on this phone. AES-256-GCM.");
            startCard.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
            fillRecent(data);
            fillFavorites(data);
        });
    }

    private void fillRecent(VaultDb.Overview data) {
        recentList.removeAllViews();
        ArrayList<Recent> merged = new ArrayList<>();
        for (VaultItem item : data.recentItems) merged.add(Recent.from(item));
        for (MediaItemRecord file : data.files) merged.add(Recent.from(file));
        merged.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        int shown = 0;
        for (Recent item : merged) {
            if (shown >= 5) break;
            recentList.addView(recentRow(item));
            shown++;
        }
        if (shown == 0) recentList.addView(placeholder("Nothing stored yet."));
    }

    private void fillFavorites(VaultDb.Overview data) {
        favoriteList.removeAllViews();
        int shown = 0;
        for (VaultItem item : data.favoriteItems) {
            favoriteList.addView(recentRow(Recent.from(item)));
            shown++;
        }
        for (MediaItemRecord file : data.files) {
            if (!file.favorite) continue;
            favoriteList.addView(recentRow(Recent.from(file)));
            shown++;
        }
        if (shown == 0) favoriteList.addView(placeholder("Star an item to pin it here."));
    }

    private LinearLayout placeholder(String text) {
        LinearLayout card = Ui.card(this);
        TextView view = Ui.text(this, text, 14, palette.muted);
        view.setGravity(Gravity.CENTER);
        card.addView(view);
        return card;
    }

    private LinearLayout recentRow(Recent item) {
        LinearLayout row = Ui.horizontal(this);
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
        LinearLayout labels = Ui.vertical(this);
        TextView title = Ui.text(this, item.title, 15, palette.text);
        title.setTypeface(Ui.serif());
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title);
        labels.addView(Ui.text(this, item.subtitle, 12, palette.muted), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
        row.addView(labels, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        row.addView(Ui.pill(this, item.category, false));
        row.setOnClickListener(v -> {
            if ("file".equals(item.kind)) {
                selectTab(TAB_FILES);
                return;
            }
            selectTab(item.kind);
            VaultDb.get(this).getAsync(item.id, (vaultItem, error) -> {
                if (!active || error != null || vaultItem == null) return;
                if (selectedItem != null) selectedItem.clearSensitive();
                selectedItem = vaultItem.copy();
                renderItemDetail();
            });
        });
        return row;
    }

    private void setCount(TextView view, int value) {
        if (view != null) view.setText(String.valueOf(value));
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
                    if (matchesFilter(item.favorite, item.category)) shown.add(item);
                    else item.clearSensitive();
                }
            }
            adapter.replace(shown);
            boolean empty = shown.isEmpty();
            boolean detail = selectedItem != null;
            emptyState.setVisibility(empty && !detail ? View.VISIBLE : View.GONE);
            ((View) list.getParent()).setVisibility(empty || detail ? View.GONE : View.VISIBLE);
            list.setVisibility(empty || detail ? View.GONE : View.VISIBLE);
            fileDetail.setVisibility(View.GONE);
            renderItemDetail();
        });
    }

    private boolean matchesFilter(boolean favorite, String category) {
        if (FILTER_ALL.equals(selectedFilter)) return true;
        if (FILTER_FAVORITES.equals(selectedFilter)) return favorite;
        return selectedFilter.equalsIgnoreCase(category);
    }

    private void loadFiles() {
        if (listTask != null) listTask.cancel();
        String query = search == null ? "" : search.getText().toString();
        listTask = VaultDb.get(this).listMediaAsync(query, "files", 400, (items, error) -> {
            if (!active) {
                clearMedia(items);
                return;
            }
            if (error != null) {
                error(error);
                clearMedia(items);
                return;
            }
            ArrayList<MediaItemRecord> shown = new ArrayList<>();
            if (items != null) {
                for (MediaItemRecord item : items) {
                    if (matchesFilter(item.favorite, item.category)
                            && (query.isEmpty()
                            || item.displayTitle().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))
                            || item.originalName.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))) {
                        shown.add(item);
                    } else item.clearSensitive();
                }
            }
            fileAdapter.replace(shown);
            boolean empty = shown.isEmpty();
            emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            ((View) list.getParent()).setVisibility(empty ? View.GONE : View.VISIBLE);
            list.setVisibility(empty ? View.GONE : View.VISIBLE);
            renderFileDetail();
        });
    }

    private void renderFileDetail() {
        if (selectedFile == null || !TAB_FILES.equals(selectedTab)) {
            fileDetail.setVisibility(View.GONE);
            return;
        }
        fileDetail.setVisibility(View.VISIBLE);
        fileDetailTitle.setText(selectedFile.displayTitle());
        fileDetailMeta.setText(VaultItem.normalizeCategory(selectedFile.category)
                + "\n\nFilename\n" + selectedFile.originalName
                + "\n\nSize\n" + humanSize(selectedFile.size));
    }

    private void renderItemDetail() {
        boolean show = selectedItem != null && !TAB_OVERVIEW.equals(selectedTab) && !TAB_FILES.equals(selectedTab);
        if (itemDetail != null) itemDetail.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        headingRowVisible(false);
        searchBox.setVisibility(View.GONE);
        ((View) chipsRow.getParent()).setVisibility(View.GONE);
        addButton.setVisibility(View.GONE);
        emptyState.setVisibility(View.GONE);
        ((View) list.getParent()).setVisibility(View.GONE);
        itemDetailTitle.setText(selectedItem.title);
        itemDetailBody.removeAllViews();
        if (VaultItem.PASSWORD.equals(selectedItem.kind)) {
            addCopyField("Username", selectedItem.username);
            addCopyField("Password", selectedItem.secret);
            addMetaField("Website", selectedItem.url);
            addMetaField("Notes", selectedItem.notes);
        } else if (VaultItem.CARD.equals(selectedItem.kind)) {
            addCopyField("Cardholder", selectedItem.username);
            addCopyField("Number", selectedItem.secret);
            addMetaField("Expiry", selectedItem.url);
            String[] cvv = splitStoredCvv(selectedItem.notes);
            addCopyField("CVV", cvv[0]);
            addMetaField("Notes", cvv[1]);
        } else {
            addMetaField("Note", selectedItem.notes);
        }
        addMetaField("Category", selectedItem.category);
        addMetaField("Tags", selectedItem.tags);
        addMetaField("Last updated", relativeTime(selectedItem.updatedAt));
    }

    private void addMetaField(String label, String value) {
        if (value == null || value.isEmpty()) return;
        itemDetailBody.addView(Ui.label(this, label), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 16, 0, 4));
        TextView text = Ui.text(this, value, 15, palette.text);
        itemDetailBody.addView(text);
    }

    private void addCopyField(String label, String value) {
        if (value == null || value.isEmpty()) return;
        itemDetailBody.addView(Ui.label(this, label), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 16, 0, 4));
        LinearLayout row = Ui.horizontal(this);
        TextView text = Ui.text(this, "Password".equals(label) || "CVV".equals(label) ? "••••••••••••" : value, 15, palette.text);
        row.addView(text, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        Button copy = Ui.pill(this, "Copy", false);
        copy.setOnClickListener(v -> copySecret(label.toLowerCase(Locale.ROOT), value));
        row.addView(copy);
        itemDetailBody.addView(row);
    }

    private static String[] splitStoredCvv(String notesValue) {
        if (notesValue != null && notesValue.startsWith("CVV\t")) {
            int nl = notesValue.indexOf('\n');
            if (nl < 0) return new String[]{notesValue.substring(4), ""};
            return new String[]{notesValue.substring(4, nl), notesValue.substring(nl + 1)};
        }
        return new String[]{"", notesValue == null ? "" : notesValue};
    }

    private void exportSelected(boolean ciphertext) {
        if (selectedFile == null || actionTask != null) return;
        message(ciphertext ? "Exporting .enc…" : "Downloading…");
        if (ciphertext) {
            actionTask = MediaRepository.exportCiphertextAsync(this, selectedFile.id, (uri, error) -> {
                actionTask = null;
                if (error != null) error(error);
                else message("Exported .enc to Downloads/Govind Personal Vault");
            });
        } else {
            actionTask = MediaRepository.exportAsync(this, selectedFile.id, (uri, error) -> {
                actionTask = null;
                if (error != null) error(error);
                else message("Downloaded to Govind Personal Vault");
            });
        }
    }

    private void openEditor(String kind, String id) {
        Intent intent = new Intent(this, EntryEditorActivity.class);
        intent.putExtra("kind", kind);
        if (id != null) intent.putExtra("item_id", id);
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

    private static void clearMedia(List<MediaItemRecord> items) {
        if (items == null) return;
        for (MediaItemRecord item : items) if (item != null) item.clearSensitive();
    }

    private static String greetingText() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 12) return "Good morning";
        if (hour < 17) return "Good afternoon";
        return "Good evening";
    }

    private static String relativeTime(long ts) {
        long s = Math.max(0L, (System.currentTimeMillis() - ts) / 1000L);
        if (s < 45) return "just now";
        if (s < 90) return "1 minute ago";
        if (s < 3600) return (s / 60) + " minutes ago";
        if (s < 5400) return "1 hour ago";
        if (s < 86400) return (s / 3600) + " hours ago";
        long days = Math.max(1L, s / 86400L);
        if (days == 1) return "yesterday";
        if (days < 7) return days + " days ago";
        if (days < 11) return "last week";
        if (days < 30) return (days / 7) + " weeks ago";
        return new java.text.SimpleDateFormat("d MMM yyyy", Locale.US).format(new java.util.Date(ts));
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes / 1024.0;
        if (value < 1024.0) return String.format(Locale.US, "%.1f KB", value);
        value /= 1024.0;
        if (value < 1024.0) return String.format(Locale.US, "%.1f MB", value);
        return String.format(Locale.US, "%.2f GB", value / 1024.0);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request == DELETE_REQUEST || request == FILE_EDITOR_REQUEST) && result == RESULT_OK) {
            if (request == DELETE_REQUEST) message("Item deleted");
            loadCurrent();
        }
    }

    @Override protected void clearSensitiveUi() {
        active = false;
        loadGeneration++;
        if (adapter != null) adapter.clear();
        if (fileAdapter != null) fileAdapter.clear();
        if (emptyState != null) emptyState.setVisibility(View.GONE);
        selectedFile = null;
        if (selectedItem != null) {
            selectedItem.clearSensitive();
            selectedItem = null;
        }
    }

    @Override protected void onDestroy() {
        searchHandler.removeCallbacksAndMessages(null);
        if (listTask != null) listTask.cancel();
        if (countTask != null) countTask.cancel();
        super.onDestroy();
    }

    private static final class Recent {
        final String id;
        final String kind;
        final String title;
        final String subtitle;
        final String category;
        final long updatedAt;
        static Recent from(VaultItem item) {
            String sub = VaultItem.PASSWORD.equals(item.kind) ? "Login" : VaultItem.CARD.equals(item.kind) ? "Card" : "Note";
            return new Recent(item.id, item.kind, item.title, sub, item.category, item.updatedAt);
        }
        static Recent from(MediaItemRecord item) {
            return new Recent(item.id, "file", item.displayTitle(), item.originalName, item.category, item.updatedAt);
        }
        Recent(String id, String kind, String title, String subtitle, String category, long updatedAt) {
            this.id = id;
            this.kind = kind;
            this.title = title;
            this.subtitle = subtitle;
            this.category = category;
            this.updatedAt = updatedAt;
        }
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
            LinearLayout row = Ui.horizontal(VaultActivity.this);
            row.setPadding(Ui.dp(VaultActivity.this, 12), Ui.dp(VaultActivity.this, 14), Ui.dp(VaultActivity.this, 12), Ui.dp(VaultActivity.this, 14));
            String glyph = VaultItem.CARD.equals(item.kind) ? "▭" : VaultItem.NOTE.equals(item.kind) ? "✎" : "⌁";
            row.addView(Ui.iconBubble(VaultActivity.this, glyph));
            LinearLayout labels = Ui.vertical(VaultActivity.this);
            TextView title = Ui.text(VaultActivity.this, item.title, 16, palette.text);
            title.setTypeface(Ui.serif());
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(title);
            String who = VaultItem.CARD.equals(item.kind)
                    ? item.maskedSecret()
                    : VaultItem.PASSWORD.equals(item.kind)
                    ? (item.username.isEmpty() ? (item.url.isEmpty() ? "Login" : item.url) : item.username)
                    : (item.notes.isEmpty() ? "Note" : item.notes.split("\n")[0]);
            String secondary = who + " · " + item.category + " · " + relativeTime(item.updatedAt);
            if (secondary.length() > 90) secondary = secondary.substring(0, 90);
            TextView sub = Ui.text(VaultActivity.this, secondary, 13, palette.muted);
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(sub, Ui.margins(VaultActivity.this, Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
            lp.leftMargin = Ui.dp(VaultActivity.this, 10);
            row.addView(labels, lp);
            TextView badge = Ui.pill(VaultActivity.this, item.favorite ? "★ " + item.category : item.category, false);
            badge.setTextSize(11);
            row.addView(badge);
            row.setOnClickListener(v -> {
                if (selectedItem != null) selectedItem.clearSensitive();
                selectedItem = item.copy();
                renderItemDetail();
            });
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
            TextView title = Ui.text(VaultActivity.this, item.displayTitle(), 16, palette.text);
            title.setTypeface(Ui.serif());
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(title, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
            TextView badge = Ui.pill(VaultActivity.this, item.favorite ? "★ " + item.category : item.category, false);
            badge.setTextSize(11);
            top.addView(badge);
            row.addView(top);
            TextView sub = Ui.text(VaultActivity.this, item.originalName, 13, palette.muted);
            sub.setMaxLines(1);
            row.addView(sub, Ui.margins(VaultActivity.this, Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
            row.setOnClickListener(v -> {
                selectedFile = item.copy();
                renderFileDetail();
            });
            return row;
        }
    }
}
