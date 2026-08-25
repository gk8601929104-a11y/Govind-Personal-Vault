package com.govind.personalvault;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.govind.personalvault.data.VaultDb;
import com.govind.personalvault.media.InAppDocumentReader;
import com.govind.personalvault.media.MediaRepository;
import com.govind.personalvault.model.MediaItemRecord;
import com.govind.personalvault.security.SecureWork;
import com.govind.personalvault.security.VaultSession;
import com.govind.personalvault.ui.Ui;
import com.govind.personalvault.ui.ZoomPanImageView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** In-app encrypted document viewer. No plaintext file is written to disk. */
public final class SecureDocumentActivity extends BaseActivity {
    private String mediaId;
    private TextView name;
    private TextView detail;
    private TextView pageLabel;
    private TextView textPreview;
    private ZoomPanImageView pageView;
    private ScrollView textScroll;
    private ProgressBar progress;
    private FrameLayout stage;
    private Button export;
    private LinearLayout chromeTop;
    private LinearLayout chromeActions;
    private boolean chromeVisible = true;
    private MediaItemRecord record;
    private InAppDocumentReader.Preview preview;
    private Bitmap pageBitmap;
    private VaultDb.Task metadataTask;
    private VaultDb.Task listTask;
    private SecureWork.Task previewTask;
    private MediaRepository.Task actionTask;
    private boolean active;
    private float textSizeSp = 16f;
    private float textDownX;
    private float textDownY;
    private boolean textMoved;
    private final ArrayList<String> documentIds = new ArrayList<>();
    private int documentIndex = -1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        mediaId = getIntent().getStringExtra("media_id");
        if (mediaId == null || mediaId.trim().isEmpty()) {
            finish();
            return;
        }
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        active = VaultSession.isUnlocked();
        if (active) {
            loadNeighbors();
            load();
        }
    }

    @Override protected void onStop() {
        active = false;
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (metadataTask != null) metadataTask.cancel();
        if (listTask != null) listTask.cancel();
        if (previewTask != null) previewTask.cancel();
        if (actionTask != null) actionTask.cancel();
        clearSensitiveUi();
        super.onDestroy();
    }

    @Override protected void clearSensitiveUi() {
        if (record != null) record.clearSensitive();
        record = null;
        closePreview();
        if (name != null) name.setText("Encrypted document");
        if (detail != null) detail.setText("Vault locked");
        if (textPreview != null) textPreview.setText("");
        if (export != null) export.setEnabled(false);
    }

    private void closePreview() {
        if (pageView != null) pageView.setImageBitmap(null);
        if (textPreview != null) textPreview.setText("");
        final Bitmap oldBitmap = pageBitmap;
        pageBitmap = null;
        final InAppDocumentReader.Preview oldPreview = preview;
        preview = null;
        if (oldBitmap != null || oldPreview != null) {
            SecureWork.submit(() -> {
                if (oldBitmap != null && !oldBitmap.isRecycled()) oldBitmap.recycle();
                if (oldPreview != null) oldPreview.close();
                return Boolean.TRUE;
            }, (ignored, error) -> { });
        }
    }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(palette.bg);

        stage = new FrameLayout(this);
        stage.setBackgroundColor(palette.bg);

        pageView = new ZoomPanImageView(this);
        pageView.setContentDescription("Decrypted document page");
        pageView.setVisibility(View.GONE);
        pageView.setSwipeListener(new ZoomPanImageView.SwipeListener() {
            @Override public void onSwipeNext() { turnPage(1); }
            @Override public void onSwipePrevious() { turnPage(-1); }
        });
        pageView.setTapListener(this::toggleChrome);
        stage.addView(pageView, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        textScroll = new ScrollView(this);
        textScroll.setFillViewport(true);
        textScroll.setVisibility(View.GONE);
        textPreview = Ui.text(this, "", 16, palette.text);
        textPreview.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        textPreview.setLineSpacing(0, 1.22f);
        textPreview.setTextIsSelectable(true);
        textScroll.addView(textPreview, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));
        ScaleGestureDetector textZoom = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        textSizeSp = Math.max(12f, Math.min(36f, textSizeSp * detector.getScaleFactor()));
                        textPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
                        return true;
                    }
                });
        textScroll.setClickable(true);
        textScroll.setOnTouchListener((v, event) -> {
            textZoom.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    textDownX = event.getX();
                    textDownY = event.getY();
                    textMoved = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - textDownX) > Ui.dp(this, 10)
                            || Math.abs(event.getY() - textDownY) > Ui.dp(this, 10)) {
                        textMoved = true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (!textMoved) {
                        toggleChrome();
                        v.performClick();
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
        stage.addView(textScroll, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        pageLabel = Ui.badge(this, "", palette.accent);
        pageLabel.setVisibility(View.GONE);
        FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        pageParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        pageParams.bottomMargin = Ui.dp(this, 72);
        stage.addView(pageLabel, pageParams);

        progress = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        progressParams.gravity = Gravity.CENTER;
        stage.addView(progress, progressParams);
        root.addView(stage, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        LinearLayout top = Ui.horizontal(this);
        chromeTop = top;
        Button back = Ui.overlayBack(this);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        root.addView(top, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        LinearLayout actions = Ui.horizontal(this);
        chromeActions = actions;
        actions.setBackgroundColor(Ui.withAlpha(palette.bg, 180));
        export = Ui.secondary(this, "Export");
        export.setEnabled(false);
        export.setOnClickListener(v -> export());
        Button delete = Ui.danger(this, "Delete");
        delete.setOnClickListener(v -> startActivity(
                new Intent(this, MediaDeleteConfirmActivity.class)
                        .putExtra("media_id", mediaId)));
        actions.addView(export, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1);
        deleteParams.leftMargin = Ui.dp(this, 8);
        actions.addView(delete, deleteParams);
        root.addView(actions, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));

        name = Ui.heading(this, "");
        name.setVisibility(View.GONE);
        detail = Ui.text(this, "", 13, palette.muted);
        detail.setVisibility(View.GONE);
        setFileViewerContent(root, top, actions);
        setViewerImmersive(true);
        stage.setOnClickListener(v -> toggleChrome());
    }

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        int vis = chromeVisible ? View.VISIBLE : View.GONE;
        if (chromeTop != null) chromeTop.setVisibility(vis);
        if (chromeActions != null) chromeActions.setVisibility(vis);
        if (pageLabel != null && preview != null && preview.kind == InAppDocumentReader.Kind.PDF) {
            pageLabel.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
        }
        setViewerImmersive(true);
    }

    private void loadNeighbors() {
        if (listTask != null) listTask.cancel();
        listTask = VaultDb.get(this).listMediaAsync("", "document", 500, (items, error) -> {
            if (!active) {
                clearRecords(items);
                return;
            }
            documentIds.clear();
            documentIndex = -1;
            if (items != null) {
                for (MediaItemRecord item : items) {
                    if (item != null && item.id != null && !item.id.isEmpty() && item.isDocument()) {
                        if (item.id.equals(mediaId)) documentIndex = documentIds.size();
                        documentIds.add(item.id);
                    }
                    if (item != null) item.clearSensitive();
                }
            }
        });
    }

    private void load() {
        if (metadataTask != null) metadataTask.cancel();
        metadataTask = VaultDb.get(this).getMediaAsync(mediaId, (item, error) -> {
            metadataTask = null;
            if (!active) {
                if (item != null) item.clearSensitive();
                return;
            }
            if (error != null) {
                SecureDocumentActivity.this.error(error);
                finish();
                return;
            }
            if (item == null) {
                message("Document no longer exists");
                finish();
                return;
            }
            if (!item.isDocument()) {
                item.clearSensitive();
                message("This item belongs in the Media Vault");
                finish();
                return;
            }
            if (record != null) record.clearSensitive();
            record = item;
            name.setText(record.originalName);
            detail.setText(typeLabel(record)
                    + " • " + humanSize(record.size)
                    + "\nProtected "
                    + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(new Date(record.updatedAt)));
            export.setEnabled(true);
            openPreview();
        });
    }

    private void openPreview() {
        progress.setVisibility(View.VISIBLE);
        pageView.setVisibility(View.GONE);
        textScroll.setVisibility(View.GONE);
        pageLabel.setVisibility(View.GONE);
        if (previewTask != null) previewTask.cancel();
        closePreview();
        final String id = mediaId;
        final String mime = record == null ? "" : record.mimeType;
        final String original = record == null ? "" : record.originalName;
        previewTask = SecureWork.submit(
                () -> InAppDocumentReader.open(this, id, mime, original),
                (value, error) -> {
                    previewTask = null;
                    if (!active) {
                        if (value != null) value.close();
                        return;
                    }
                    progress.setVisibility(View.GONE);
                    if (error != null || value == null) {
                        showMessage("This document could not be opened inside the vault.");
                        return;
                    }
                    preview = value;
                    showPreview();
                });
    }

    private void showPreview() {
        if (preview == null) return;
        if (preview.kind == InAppDocumentReader.Kind.PDF) {
            pageView.setVisibility(View.VISIBLE);
            pageView.setSwipeEnabled(preview.pageCount > 1 || documentIds.size() > 1);
            renderPdfPage(0);
            return;
        }
        if (preview.kind == InAppDocumentReader.Kind.IMAGE && preview.image != null) {
            pageView.setVisibility(View.VISIBLE);
            pageView.setSwipeEnabled(documentIds.size() > 1);
            pageView.setImageBitmap(preview.image);
            pageLabel.setVisibility(View.GONE);
            return;
        }
        if (preview.kind == InAppDocumentReader.Kind.TEXT && preview.text != null && !preview.text.isEmpty()) {
            textScroll.setVisibility(View.VISIBLE);
            textPreview.setText(preview.text);
            if (preview.note != null && !preview.note.isEmpty()) {
                detail.setText(detail.getText() + "\n" + preview.note);
            }
            return;
        }
        showMessage(preview.note == null || preview.note.isEmpty()
                ? "No in-app preview for this file type. Export a copy if you need another app."
                : preview.note);
    }

    private void showMessage(String message) {
        textScroll.setVisibility(View.VISIBLE);
        textPreview.setText(message);
    }

    private void turnPage(int delta) {
        if (pageView.isZoomed()) return;
        if (preview != null && preview.kind == InAppDocumentReader.Kind.PDF && preview.pageCount > 0) {
            int next = preview.pageIndex + delta;
            if (next >= 0 && next < preview.pageCount) {
                renderPdfPage(next);
                return;
            }
        }
        showNeighbor(delta);
    }

    private void showNeighbor(int delta) {
        if (documentIds.size() < 2) return;
        if (documentIndex < 0) documentIndex = documentIds.indexOf(mediaId);
        if (documentIndex < 0) return;
        int next = documentIndex + delta;
        if (next < 0 || next >= documentIds.size()) return;
        mediaId = documentIds.get(next);
        documentIndex = next;
        load();
    }

    private void renderPdfPage(int index) {
        final InAppDocumentReader.Preview current = preview;
        if (current == null || current.pdf == null) return;
        progress.setVisibility(View.VISIBLE);
        final int width = Math.max(720, getResources().getDisplayMetrics().widthPixels);
        final int height = Math.max(720, getResources().getDisplayMetrics().heightPixels);
        if (previewTask != null) previewTask.cancel();
        previewTask = SecureWork.submit(
                () -> current.renderPdfPage(index, width, height),
                (value, error) -> {
                    previewTask = null;
                    if (!active) {
                        if (value != null && !value.isRecycled()) value.recycle();
                        return;
                    }
                    progress.setVisibility(View.GONE);
                    if (error != null || value == null) {
                        showMessage("This PDF page could not be rendered.");
                        return;
                    }
                    if (pageBitmap != null && !pageBitmap.isRecycled()) pageBitmap.recycle();
                    pageBitmap = value;
                    pageView.setImageBitmap(pageBitmap);
                    pageLabel.setText((index + 1) + " / " + current.pageCount);
                    pageLabel.setVisibility(View.VISIBLE);
                });
    }

    private void export() {
        if (record == null || actionTask != null || !VaultSession.isUnlocked()) return;
        export.setEnabled(false);
        export.setText("Exporting…");
        actionTask = MediaRepository.exportAsync(this, mediaId, (Uri uri, Exception error) -> {
            actionTask = null;
            if (!active) return;
            export.setText("Export copy");
            export.setEnabled(true);
            if (error != null) SecureDocumentActivity.this.error(error);
            else message("Exported to Downloads/Govind Personal Vault");
        });
    }

    private static void clearRecords(List<MediaItemRecord> items) {
        if (items == null) return;
        for (MediaItemRecord item : items) if (item != null) item.clearSensitive();
    }

    private static String typeLabel(MediaItemRecord item) {
        String fileName = item.originalName == null ? "" : item.originalName;
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String extension = fileName.substring(dot + 1)
                    .toUpperCase(Locale.ROOT)
                    .replaceAll("[^A-Z0-9]", "");
            if (!extension.isEmpty()) return extension;
        }
        return item.mimeType == null ? "DOCUMENT" : item.mimeType;
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