package com.govind.personalvault;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.govind.personalvault.data.VaultDb;
import com.govind.personalvault.media.EncryptedMediaInputStream;
import com.govind.personalvault.media.MediaRepository;
import com.govind.personalvault.model.MediaItemRecord;
import com.govind.personalvault.security.SecureWork;
import com.govind.personalvault.security.VaultSession;
import com.govind.personalvault.ui.Ui;
import com.govind.personalvault.ui.ZoomPanImageView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Secure sampled image viewer with pinch-zoom, pan, and gallery swipe. Plain image bytes never touch disk. */
public final class SecureImageViewerActivity extends BaseActivity {
    private String mediaId;
    private TextView title;
    private TextView detail;
    private ZoomPanImageView image;
    private ProgressBar progress;
    private LinearLayout chromeTop;
    private LinearLayout chromeActions;
    private boolean chromeVisible = true;
    private MediaItemRecord record;
    private Bitmap bitmap;
    private VaultDb.Task metadataTask;
    private VaultDb.Task listTask;
    private SecureWork.Task decodeTask;
    private MediaRepository.Task actionTask;
    private boolean active;
    private final ArrayList<String> galleryIds = new ArrayList<>();
    private int galleryIndex = -1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        mediaId = getIntent().getStringExtra("media_id");
        if (mediaId == null) {
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
        if (decodeTask != null) decodeTask.cancel();
        if (actionTask != null) actionTask.cancel();
        clearSensitiveUi();
        super.onDestroy();
    }

    @Override protected void clearSensitiveUi() {
        if (record != null) record.clearSensitive();
        record = null;
        if (image != null) image.setImageBitmap(null);
        recycleBitmap();
    }

    private void recycleBitmap() {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
    }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(palette.bg);

        image = new ZoomPanImageView(this);
        image.setContentDescription("Decrypted vault image");
        image.setSwipeListener(new ZoomPanImageView.SwipeListener() {
            @Override public void onSwipeNext() { showNeighbor(1); }
            @Override public void onSwipePrevious() { showNeighbor(-1); }
        });
        image.setTapListener(this::toggleChrome);
        root.addView(image, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        progress = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        progressParams.gravity = Gravity.CENTER;
        root.addView(progress, progressParams);

        LinearLayout top = Ui.horizontal(this);
        chromeTop = top;
        Button back = Ui.overlayBack(this);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        root.addView(top, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        LinearLayout actions = Ui.horizontal(this);
        chromeActions = actions;
        actions.setBackgroundColor(Ui.withAlpha(palette.bg, 180));
        Button export = Ui.secondary(this, "Export");
        export.setOnClickListener(v -> export());
        Button delete = Ui.danger(this, "Delete");
        delete.setOnClickListener(v -> startActivity(new Intent(this, MediaDeleteConfirmActivity.class).putExtra("media_id", mediaId)));
        actions.addView(export, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1);
        deleteParams.leftMargin = Ui.dp(this, 8);
        actions.addView(delete, deleteParams);
        root.addView(actions, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));

        title = Ui.heading(this, "");
        title.setVisibility(View.GONE);
        detail = Ui.text(this, "", 13, palette.muted);
        detail.setVisibility(View.GONE);
        setFileViewerContent(root, top, actions);
        setViewerImmersive(true);
    }

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        int vis = chromeVisible ? View.VISIBLE : View.GONE;
        if (chromeTop != null) chromeTop.setVisibility(vis);
        if (chromeActions != null) chromeActions.setVisibility(vis);
        setViewerImmersive(true);
    }

    private void loadNeighbors() {
        if (listTask != null) listTask.cancel();
        listTask = VaultDb.get(this).listMediaAsync("", "image", 300, (items, error) -> {
            if (!active) {
                clearRecords(items);
                return;
            }
            galleryIds.clear();
            galleryIndex = -1;
            if (items != null) {
                for (MediaItemRecord item : items) {
                    if (item != null && item.id != null && !item.id.isEmpty() && item.isImage()) {
                        if (item.id.equals(mediaId)) galleryIndex = galleryIds.size();
                        galleryIds.add(item.id);
                    }
                    if (item != null) item.clearSensitive();
                }
            }
            image.setSwipeEnabled(galleryIds.size() > 1);
        });
    }

    private void showNeighbor(int delta) {
        if (image.isZoomed() || galleryIds.size() < 2 || decodeTask != null) return;
        if (galleryIndex < 0) {
            galleryIndex = galleryIds.indexOf(mediaId);
        }
        if (galleryIndex < 0) return;
        int next = galleryIndex + delta;
        if (next < 0 || next >= galleryIds.size()) return;
        mediaId = galleryIds.get(next);
        galleryIndex = next;
        load();
    }

    private void load() {
        if (metadataTask != null) metadataTask.cancel();
        metadataTask = VaultDb.get(this).getMediaAsync(mediaId, (item, error) -> {
            if (!active) {
                if (item != null) item.clearSensitive();
                return;
            }
            if (error != null) {
                SecureImageViewerActivity.this.error(error);
                finish();
                return;
            }
            if (item == null) {
                message("Image no longer exists");
                finish();
                return;
            }
            if (!item.isImage()) {
                item.clearSensitive();
                message("This media item is not an image");
                finish();
                return;
            }
            if (record != null) record.clearSensitive();
            record = item;
            title.setText(record.originalName);
            String extra = galleryIds.size() > 1 && galleryIndex >= 0
                    ? " • " + (galleryIndex + 1) + "/" + galleryIds.size()
                    : "";
            detail.setText(record.mimeType + " • " + humanSize(record.size) + extra);
            decode();
        });
    }

    private void decode() {
        progress.setVisibility(View.VISIBLE);
        if (decodeTask != null) decodeTask.cancel();
        decodeTask = SecureWork.submit(this::decodeSampled, (value, error) -> {
            decodeTask = null;
            if (!active) {
                if (value != null && !value.isRecycled()) value.recycle();
                return;
            }
            progress.setVisibility(View.GONE);
            if (error != null || value == null) {
                SecureImageViewerActivity.this.error(error == null ? new Exception("Image could not be decoded") : error);
                return;
            }
            recycleBitmap();
            bitmap = value;
            image.setImageBitmap(bitmap);
        });
    }

    private Bitmap decodeSampled() throws Exception {
        int targetWidth = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int targetHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        int maxEdge = Math.max(2048, Math.max(targetWidth, targetHeight) * 3);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = EncryptedMediaInputStream.open(this, mediaId)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("Unsupported or damaged image");
        int sample = 1;
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = EncryptedMediaInputStream.open(this, mediaId)) {
            Bitmap result = BitmapFactory.decodeStream(input, null, options);
            if (result == null) throw new Exception("Unsupported or damaged image");
            return result;
        }
    }

    private void export() {
        if (record == null || actionTask != null) return;
        message("Exporting authenticated copy…");
        actionTask = MediaRepository.exportAsync(this, mediaId, (uri, error) -> {
            actionTask = null;
            if (!active) return;
            if (error != null) SecureImageViewerActivity.this.error(error);
            else message("Exported to the device gallery");
        });
    }

    private static void clearRecords(List<MediaItemRecord> items) {
        if (items == null) return;
        for (MediaItemRecord item : items) if (item != null) item.clearSensitive();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb);
        return String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0);
    }
}
