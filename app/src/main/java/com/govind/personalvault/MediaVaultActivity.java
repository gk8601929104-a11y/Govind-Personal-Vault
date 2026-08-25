package com.govind.personalvault;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.govind.personalvault.data.VaultDb;
import com.govind.personalvault.media.MediaRepository;
import com.govind.personalvault.model.MediaItemRecord;
import com.govind.personalvault.security.VaultSession;
import com.govind.personalvault.ui.Ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Programmatic encrypted-media gallery. No layout XML is used. */
public final class MediaVaultActivity extends BaseActivity {
    private static final long SEARCH_DELAY_MS = 300L;

    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = this::loadMedia;
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
    private Button allFilter;
    private Button photosFilter;
    private Button videosFilter;
    private Button audioFilter;
    private GridView grid;
    private MediaAdapter adapter;
    private VaultDb.Task listTask;
    private MediaRepository.Task mediaTask;
    private String filter = "all";
    private boolean importing;
    private boolean active;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        picker = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                this::onMediaSelected);
        build();
        MediaRepository.cleanupAsync(this, (ignored, error) -> { });
    }

    @Override protected void onResume() {
        super.onResume();
        active = VaultSession.isUnlocked();
        if (!active) return;
        loadMedia();
        if (!pendingImportUris.isEmpty() && !importing) beginImport();
    }

    @Override protected void onStop() {
        active = false;
        searchHandler.removeCallbacks(searchRunnable);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (listTask != null) listTask.cancel();
        if (mediaTask != null) mediaTask.cancel();
        if (!pendingImportUris.isEmpty()) releasePermissions(new ArrayList<>(pendingImportUris));
        pendingImportUris.clear();
        if (adapter != null) adapter.clear();
        super.onDestroy();
    }

    @Override protected void clearSensitiveUi() {
        if (adapter != null) adapter.clear();
        if (grid != null) grid.setVisibility(View.GONE);
        if (emptyState != null) emptyState.setVisibility(View.GONE);
    }

    private void build() {
        LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(palette.bg);
        root.addView(topBar(
                "Media",
                null,
                true,
                "Import",
                v -> launchPicker(),
                "\uD83D\uDD0D",
                v -> toggleSearchBox(searchBox, search)));

        LinearLayout header = Ui.vertical(this);
        header.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 8));

        LinearLayout filters = Ui.horizontal(this);
        allFilter = filterButton("All", "all");
        photosFilter = filterButton("Photo", "image");
        videosFilter = filterButton("Video", "video");
        audioFilter = filterButton("Audio", "audio");
        addWeighted(filters, allFilter, 0);
        addWeighted(filters, photosFilter, 6);
        addWeighted(filters, videosFilter, 6);
        addWeighted(filters, audioFilter, 6);
        header.addView(filters);

        LinearLayout titleRow = Ui.horizontal(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = Ui.text(this, "Library", 13, palette.muted);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleRow.addView(heading, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        count = Ui.badge(this, "0", palette.muted);
        titleRow.addView(count);
        header.addView(titleRow, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 10, 0, 6));
        searchBox = buildSearchBox();
        searchBox.setVisibility(View.GONE);
        header.addView(searchBox);

        progressPanel = Ui.card(this);
        progressPanel.setVisibility(View.GONE);
        progressTitle = Ui.heading(this, "Encrypting media…");
        progressDetail = Ui.text(this, "Preparing secure import", 13, palette.muted);
        progressPanel.addView(progressTitle);
        progressPanel.addView(progressDetail, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 7, 0, 0));
        header.addView(progressPanel, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));
        root.addView(header);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 154));
        grid.setHorizontalSpacing(Ui.dp(this, 10));
        grid.setVerticalSpacing(Ui.dp(this, 10));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        grid.setClipToPadding(false);
        grid.setSelector(android.R.color.transparent);
        adapter = new MediaAdapter();
        grid.setAdapter(adapter);
        root.addView(grid, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));

        emptyState = Ui.vertical(this);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(Ui.dp(this, 30), Ui.dp(this, 30), Ui.dp(this, 30), Ui.dp(this, 30));
        TextView icon = Ui.text(this, "▣", 60, palette.muted);
        icon.setGravity(Gravity.CENTER);
        emptyState.addView(icon);
        TextView emptyTitle = Ui.heading(this, "No encrypted media yet");
        emptyTitle.setGravity(Gravity.CENTER);
        emptyState.addView(emptyTitle, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 12, 0, 0));
        TextView emptyText = Ui.text(this, "Import photos, videos, or audio.", 14, palette.muted);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setLineSpacing(0, 1.18f);
        emptyState.addView(emptyText, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 18));
        importButton = Ui.primary(this, "Import media");
        importButton.setOnClickListener(v -> launchPicker());
        emptyState.addView(importButton, centeredPanelParams(300));
        root.addView(emptyState, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));

        safeContentView(root);
        updateFilters();
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
        clearSearch.setContentDescription("Clear media search");
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

    private Button filterButton(String text, String value) {
        Button button = Ui.chip(this, text);
        button.setOnClickListener(v -> {
            filter = value;
            updateFilters();
            loadMedia();
        });
        return button;
    }

    private void addWeighted(LinearLayout parent, View child, int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1);
        params.leftMargin = Ui.dp(this, leftMargin);
        parent.addView(child, params);
    }

    private void updateFilters() {
        styleFilter(allFilter, "all".equals(filter));
        styleFilter(photosFilter, "image".equals(filter));
        styleFilter(videosFilter, "video".equals(filter));
        styleFilter(audioFilter, "audio".equals(filter));
    }

    private void styleFilter(Button button, boolean selected) {
        button.setBackground(Ui.roundRect(
                this,
                selected ? Ui.withAlpha(palette.accent, 36) : palette.surface,
                18,
                1,
                selected ? Ui.withAlpha(palette.accent, 140) : palette.line));
        button.setTextColor(selected ? palette.accent : palette.text);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void launchPicker() {
        if (importing) {
            message("Finish the current import first");
            return;
        }
        picker.launch(new String[]{"image/*", "video/*", "audio/*"});
    }

    private void onMediaSelected(List<Uri> uris) {
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
        else message("Unlock the vault to finish importing the selected media");
    }

    private void beginImport() {
        if (importing || pendingImportUris.isEmpty() || !VaultSession.isUnlocked()) return;
        importing = true;
        progressPanel.setVisibility(View.VISIBLE);
        importButton.setEnabled(false);
        ArrayList<Uri> selected = new ArrayList<>(pendingImportUris);
        mediaTask = MediaRepository.importAsync(
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
                        MediaVaultActivity.this.error(error);
                    } else if (summary != null) {
                        if (summary.failed == 0) message(summary.imported + " media item(s) encrypted");
                        else message(summary.imported + " imported • " + summary.failed + " failed");
                    }
                    loadMedia();
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

    private void loadMedia() {
        if (!active || !VaultSession.isUnlocked() || adapter == null) return;
        if (listTask != null) listTask.cancel();
        String query = search == null ? "" : search.getText().toString();
        listTask = VaultDb.get(this).listMediaAsync(query, filter, 300, (items, error) -> {
            if (!active) {
                clearRecords(items);
                return;
            }
            if (error != null) {
                MediaVaultActivity.this.error(error);
                clearRecords(items);
                return;
            }
            adapter.replace(items == null ? new ArrayList<>() : items);
            int size = adapter.getCount();
            count.setText(size + (size == 1 ? " ITEM" : " ITEMS"));
            grid.setVisibility(size == 0 ? View.GONE : View.VISIBLE);
            emptyState.setVisibility(size == 0 ? View.VISIBLE : View.GONE);
        });
    }

    private void open(MediaItemRecord item) {
        Intent intent = new Intent(
                this,
                item.isImage() ? SecureImageViewerActivity.class : SecureMediaPlayerActivity.class);
        intent.putExtra("media_id", item.id);
        startActivity(intent);
    }

    private static void clearRecords(List<MediaItemRecord> items) {
        if (items == null) return;
        for (MediaItemRecord item : items) if (item != null) item.clearSensitive();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes / 1024.0;
        if (value < 1024.0) return String.format(Locale.US, "%.1f KB", value);
        value /= 1024.0;
        if (value < 1024.0) return String.format(Locale.US, "%.1f MB", value);
        return String.format(Locale.US, "%.2f GB", value / 1024.0);
    }

    private final class MediaAdapter extends BaseAdapter {
        private final ArrayList<MediaItemRecord> items = new ArrayList<>();
        private final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
            @Override protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                if (oldValue != null && oldValue != newValue && !oldValue.isRecycled()) oldValue.recycle();
            }
        };

        void replace(List<MediaItemRecord> values) {
            clear();
            items.addAll(values);
            notifyDataSetChanged();
        }

        void clear() {
            for (MediaItemRecord item : items) item.clearSensitive();
            items.clear();
            bitmaps.evictAll();
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public MediaItemRecord getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
            MediaItemRecord item = getItem(position);
            LinearLayout card = Ui.card(MediaVaultActivity.this);
            card.setPadding(Ui.dp(MediaVaultActivity.this, 10), Ui.dp(MediaVaultActivity.this, 10), Ui.dp(MediaVaultActivity.this, 10), Ui.dp(MediaVaultActivity.this, 11));
            card.setOnClickListener(v -> open(item));
            card.setContentDescription(item.kindLabel() + ", " + item.originalName);

            Bitmap thumbnail = thumbnail(item);
            if (thumbnail != null) {
                ImageView image = new ImageView(MediaVaultActivity.this);
                image.setImageBitmap(thumbnail);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackgroundColor(palette.raised);
                card.addView(image, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(MediaVaultActivity.this, 124)));
            } else {
                TextView icon = Ui.text(
                        MediaVaultActivity.this,
                        item.isVideo() ? "▶" : item.isAudio() ? "♫" : "▧",
                        42,
                        item.isVideo() ? palette.warning : item.isAudio() ? palette.accent : palette.muted);
                icon.setGravity(Gravity.CENTER);
                icon.setBackground(Ui.roundRect(MediaVaultActivity.this, palette.raised, 16, 0, 0));
                card.addView(icon, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(MediaVaultActivity.this, 124)));
            }

            TextView name = Ui.text(MediaVaultActivity.this, item.originalName, 12, palette.text);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(name, Ui.margins(MediaVaultActivity.this, Ui.MATCH, Ui.WRAP, 2, 8, 2, 0));
            TextView detail = Ui.text(
                    MediaVaultActivity.this,
                    item.kindLabel() + " · " + humanSize(item.size),
                    11,
                    palette.muted);
            detail.setMaxLines(1);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(detail, Ui.margins(MediaVaultActivity.this, Ui.MATCH, Ui.WRAP, 2, 5, 2, 0));
            return card;
        }

        private Bitmap thumbnail(MediaItemRecord item) {
            Bitmap cached = bitmaps.get(item.id);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
            if (item.thumbnail == null || item.thumbnail.length == 0) {
                return null;
            }

            byte[] encoded = item.thumbnail;
            Bitmap bitmap;
            try {
                bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
            } finally {
                Arrays.fill(encoded, (byte) 0);
                item.thumbnail = new byte[0];
            }

            if (bitmap != null) {
                bitmaps.put(item.id, bitmap);
            }
            return bitmap;
        }
    }
}
