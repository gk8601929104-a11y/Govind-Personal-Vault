package com.govind.personalvault;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.govind.personalvault.data.VaultDb;
import com.govind.personalvault.media.EncryptedCipherDataSource;
import com.govind.personalvault.media.MediaRepository;
import com.govind.personalvault.model.MediaItemRecord;
import com.govind.personalvault.security.VaultSession;
import com.govind.personalvault.ui.Ui;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Fully programmatic Media3 player for authenticated encrypted-media streaming.
 *
 * <p>No decrypted media file is written to disk or shared with another app. The activity uses a
 * secure SurfaceView for video, a programmatic audio-only state, safe Media3 error mapping,
 * programmatic audio-track selection, and an ACTION_SCREEN_OFF receiver that immediately locks the
 * vault and releases player/decoder buffers.
 */
@OptIn(markerClass = UnstableApi.class)
public final class SecureMediaPlayerActivity extends BaseActivity {
    private static final long PROGRESS_INTERVAL_MS = 500L;
    private static final long SEEK_STEP_MS = 10_000L;
    private static final long CONTROLS_HIDE_DELAY_MS = 3_500L;
    private static final long GESTURE_OVERLAY_HIDE_DELAY_MS = 850L;
    private static final float GESTURE_RANGE_MULTIPLIER = 1.30f;
    private static final float MIN_WINDOW_BRIGHTNESS = 0.02f;

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Handler chromeHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<AudioChoice> audioChoices = new ArrayList<>();

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (player != null) {
                progressHandler.postDelayed(this, PROGRESS_INTERVAL_MS);
            }
        }
    };

    private final Runnable hideChrome = new Runnable() {
        @Override public void run() {
            hideControlsNow();
        }
    };

    private final Runnable hideGestureOverlay = new Runnable() {
        @Override public void run() {
            if (gestureOverlay != null) {
                gestureOverlay.setVisibility(View.GONE);
            }
        }
    };

    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_SCREEN_OFF.equals(intent == null ? null : intent.getAction())) {
                return;
            }

            active = false;
            cancelOutstandingTasks();

            // Invalidate the VMK and every epoch-bound media DataSource before releasing the player.
            VaultSession.lock();
            BaseActivity.clearSensitiveClipboard(SecureMediaPlayerActivity.this);
            clearSensitiveUi();
            finish();
        }
    };

    private String mediaId;
    private TextView title;
    private TextView detail;
    private TextView audioArt;
    private SurfaceView surface;
    private FrameLayout videoStage;
    private PlayerView playerView;
    private TextView gestureOverlay;
    private LinearLayout topChrome;
    private LinearLayout labelsPanel;
    private LinearLayout controls;
    private LinearLayout actionsRow;
    private LinearLayout overlayTop;
    private View contentRoot;
    private SeekBar seek;
    private TextView elapsed;
    private TextView duration;
    private Button play;
    private Button speed;
    private Button resize;
    private Button audioTrackButton;
    private LinearLayout displayOptions;
    private ProgressBar buffering;
    private GestureDetector tapDetector;

    private MediaItemRecord record;
    private VaultDb.Task metadataTask;
    private MediaRepository.Task actionTask;
    private ExoPlayer player;
    private Dialog audioTrackDialog;

    private boolean scrubbing;
    private boolean active;
    private boolean screenReceiverRegistered;
    private boolean controlsVisible = true;
    private boolean resizeModeUserSelected;
    private boolean gestureAdjusting;
    private boolean gestureAdjustsVolume;
    private boolean gestureSeeks;
    private float gestureDownX;
    private float gestureDownY;
    private int gestureStartVolume;
    private float gestureStartBrightness;
    private long gestureStartPosition;
    private long pendingSeekMs = -1L;
    private int touchSlop;
    private AudioManager audioManager;
    private int speedIndex = 1;
    private int resizeModeIndex;
    private int videoPixelWidth;
    private int videoPixelHeight;

    private final float[] speeds = new float[]{0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private final int[] resizeModes = new int[]{
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
    };
    private final String[] resizeLabels = new String[]{"Fit", "Zoom", "Fill"};

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        mediaId = getIntent().getStringExtra("media_id");
        if (mediaId == null || mediaId.trim().isEmpty()) {
            finish();
            return;
        }

        build();
        tapDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (record != null && record.isVideo()) {
                    toggleFullscreenControls();
                } else {
                    togglePlayback();
                }
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                int width = videoStage == null ? 1 : Math.max(1, videoStage.getWidth());
                if (e.getX() < width * 0.33f) {
                    seekBy(-SEEK_STEP_MS);
                    showGestureValue("−10s");
                } else if (e.getX() > width * 0.67f) {
                    seekBy(SEEK_STEP_MS);
                    showGestureValue("+10s");
                } else {
                    togglePlayback();
                }
                hideGestureOverlayLater();
                return true;
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerScreenOffReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = VaultSession.isUnlocked();
        applyOrientationMode();
        if (active) {
            if (player == null) {
                loadMetadata();
            } else {
                player.play();
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyOrientationMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && shouldUseImmersiveVideo()) {
            enterImmersiveMode();
        }
    }

    @Override
    protected void onStop() {
        active = false;
        unregisterScreenOffReceiver();
        dismissAudioTrackDialog();
        releasePlayer();
        exitImmersiveMode();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterScreenOffReceiver();
        cancelOutstandingTasks();
        chromeHandler.removeCallbacksAndMessages(null);
        clearSensitiveUi();
        super.onDestroy();
    }

    @Override
    protected void clearSensitiveUi() {
        dismissAudioTrackDialog();
        releasePlayer();

        if (record != null) {
            record.clearSensitive();
        }
        record = null;

        if (title != null) {
            title.setText("Secure player");
        }
        if (detail != null) {
            detail.setText("Vault locked");
        }
    }

    private void build() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        LinearLayout root = Ui.vertical(this);
        contentRoot = root;
        root.setBackgroundColor(palette.bg);

        topChrome = topBar(
                "Secure media player",
                "Authenticated playback without plaintext files",
                true,
                null,
                null);
        topChrome.setVisibility(View.GONE);

        labelsPanel = Ui.vertical(this);
        labelsPanel.setVisibility(View.GONE);

        title = Ui.heading(this, "Opening encrypted media…");
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        detail = Ui.text(this, "Preparing authenticated stream", 13, palette.muted);
        labelsPanel.addView(title);
        labelsPanel.addView(detail, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 5, 0, 0));

        videoStage = new FrameLayout(this);
        videoStage.setBackgroundColor(Color.BLACK);
        videoStage.setClickable(true);
        videoStage.setOnClickListener(v -> toggleFullscreenControls());
        videoStage.setOnTouchListener(this::handlePlayerTouch);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setControllerAutoShow(false);
        playerView.setResizeMode(resizeModes[0]);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setOnClickListener(v -> toggleFullscreenControls());
        playerView.setOnTouchListener(this::handlePlayerTouch);
        videoStage.addView(playerView, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        View videoSurface = playerView.getVideoSurfaceView();
        if (videoSurface instanceof SurfaceView) {
            surface = (SurfaceView) videoSurface;
            surface.setSecure(BLOCK_SCREENSHOTS);
        }

        audioArt = Ui.text(this, "♫", 78, palette.accent);
        audioArt.setGravity(Gravity.CENTER);
        audioArt.setBackgroundColor(palette.surface);
        audioArt.setVisibility(View.GONE);
        audioArt.setOnClickListener(v -> toggleFullscreenControls());
        videoStage.addView(audioArt, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        gestureOverlay = Ui.text(this, "", 17, Color.WHITE);
        gestureOverlay.setGravity(Gravity.CENTER);
        gestureOverlay.setPadding(
                Ui.dp(this, 18),
                Ui.dp(this, 11),
                Ui.dp(this, 18),
                Ui.dp(this, 11));
        gestureOverlay.setBackground(
                Ui.roundRect(this, Ui.withAlpha(palette.bg, 220), 18, 1, Color.argb(90, 255, 255, 255)));
        gestureOverlay.setVisibility(View.GONE);
        gestureOverlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        FrameLayout.LayoutParams gestureParams =
                new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER);
        videoStage.addView(gestureOverlay, gestureParams);

        buffering = new ProgressBar(this);
        buffering.setIndeterminate(true);
        buffering.setVisibility(View.GONE);
        FrameLayout.LayoutParams bufferParams =
                new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.CENTER);
        videoStage.addView(buffering, bufferParams);

        controls = Ui.vertical(this);
        controls.setPadding(
                Ui.dp(this, 10),
                Ui.dp(this, 6),
                Ui.dp(this, 10),
                Ui.dp(this, 6));
        controls.setBackgroundColor(Ui.withAlpha(palette.bg, 214));

        LinearLayout seekRow = Ui.horizontal(this);
        elapsed = Ui.text(this, "0:00", 11, Color.WHITE);
        duration = Ui.text(this, "0:00", 11, Color.WHITE);
        duration.setGravity(Gravity.END);
        seekRow.addView(elapsed, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setEnabled(false);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                ExoPlayer current = player;
                if (fromUser && current != null && current.getDuration() > 0L) {
                    elapsed.setText(formatTime(current.getDuration() * value / 1000L));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                scrubbing = true;
                showControlsTemporarily();
                chromeHandler.removeCallbacks(hideChrome);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                ExoPlayer current = player;
                if (current != null && current.getDuration() > 0L) {
                    current.seekTo(current.getDuration() * seekBar.getProgress() / 1000L);
                }
                scrubbing = false;
                scheduleControlsHide();
            }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
        seekParams.leftMargin = Ui.dp(this, 4);
        seekParams.rightMargin = Ui.dp(this, 4);
        seekRow.addView(seek, seekParams);
        seekRow.addView(duration, new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
        controls.addView(seekRow);

        LinearLayout transport = Ui.horizontal(this);
        Button back = Ui.secondary(this, "−10s");
        back.setOnClickListener(v -> seekBy(-SEEK_STEP_MS));

        play = Ui.primary(this, "Play");
        play.setEnabled(false);
        play.setOnClickListener(v -> togglePlayback());

        Button forward = Ui.secondary(this, "+10s");
        forward.setOnClickListener(v -> seekBy(SEEK_STEP_MS));

        speed = Ui.secondary(this, "1×");
        speed.setOnClickListener(v -> cycleSpeed());

        resize = Ui.secondary(this, "Fit");
        resize.setContentDescription("Change video resize mode");
        resize.setOnClickListener(v -> cycleResizeMode());

        addControl(transport, back, 0);
        addControl(transport, play, 5);
        addControl(transport, forward, 5);
        addControl(transport, speed, 5);
        addControl(transport, resize, 5);
        controls.addView(transport, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));

        displayOptions = Ui.horizontal(this);
        displayOptions.setVisibility(View.GONE);
        audioTrackButton = Ui.secondary(this, "Audio tracks");
        audioTrackButton.setContentDescription("Choose audio track");
        audioTrackButton.setOnClickListener(v -> showAudioTrackDialog());
        displayOptions.addView(audioTrackButton, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        controls.addView(displayOptions, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));

        FrameLayout.LayoutParams overlayParams =
                new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM);
        videoStage.addView(controls, overlayParams);

        LinearLayout overlayTop = Ui.horizontal(this);
        this.overlayTop = overlayTop;
        Button overlayBack = Ui.overlayBack(this);
        overlayBack.setOnClickListener(v -> finish());
        overlayTop.addView(overlayBack);
        videoStage.addView(overlayTop, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP));

        actionsRow = Ui.horizontal(this);
        actionsRow.setPadding(
                Ui.dp(this, 10),
                Ui.dp(this, 8),
                Ui.dp(this, 10),
                Ui.dp(this, 8));
        actionsRow.setBackgroundColor(Ui.withAlpha(palette.bg, 180));
        Button export = Ui.secondary(this, "Export");
        export.setOnClickListener(v -> export());

        Button delete = Ui.danger(this, "Delete");
        delete.setOnClickListener(v -> startActivity(
                new Intent(this, MediaDeleteConfirmActivity.class)
                        .putExtra("media_id", mediaId)));

        actionsRow.addView(export, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1);
        deleteParams.leftMargin = Ui.dp(this, 8);
        actionsRow.addView(delete, deleteParams);
        FrameLayout.LayoutParams actionsParams =
                new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP);
        actionsParams.topMargin = Ui.dp(this, 52);
        videoStage.addView(actionsRow, actionsParams);

        root.addView(videoStage, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));

        safeContentView(root);
        installPlayerInsetsHandling();
        applyOrientationMode();
    }

    private void addControl(LinearLayout row, Button button, int leftMarginDp) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1);
        params.leftMargin = Ui.dp(this, leftMarginDp);
        row.addView(button, params);
    }

    private void loadMetadata() {
        if (metadataTask != null) {
            metadataTask.cancel();
        }

        metadataTask = VaultDb.get(this).getMediaAsync(mediaId, (item, error) -> {
            metadataTask = null;

            if (!active) {
                if (item != null) {
                    item.clearSensitive();
                }
                return;
            }

            if (error != null) {
                SecureMediaPlayerActivity.this.error(error);
                finish();
                return;
            }

            if (item == null) {
                message("Media no longer exists");
                finish();
                return;
            }

            if (item.isImage()) {
                item.clearSensitive();
                startActivity(
                        new Intent(this, SecureImageViewerActivity.class)
                                .putExtra("media_id", mediaId));
                finish();
                return;
            }

            if (record != null) {
                record.clearSensitive();
            }
            record = item;

            title.setText(record.originalName);
            detail.setText(record.mimeType + " • " + humanSize(record.size));
            playerView.setVisibility(record.isVideo() ? View.VISIBLE : View.GONE);
            audioArt.setVisibility(record.isAudio() ? View.VISIBLE : View.GONE);
            resize.setVisibility(record.isVideo() ? View.VISIBLE : View.GONE);
            applyOrientationMode();

            initializePlayer();
        });
    }

    private void initializePlayer() {
        releasePlayer();
        applyAutomaticResizeMode();

        if (record == null || !VaultSession.isUnlocked()) {
            return;
        }

        EncryptedCipherDataSource.Factory encryptedFactory =
                new EncryptedCipherDataSource.Factory(this);

        DefaultMediaSourceFactory mediaSources =
                new DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(encryptedFactory);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1_200, 40_000, 250, 800)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(record.isAudio() ? C.AUDIO_CONTENT_TYPE_MUSIC : C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();

        ExoPlayer created = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSources)
                .setLoadControl(loadControl)
                .setRenderersFactory(renderers)
                .setSeekBackIncrementMs(SEEK_STEP_MS)
                .setSeekForwardIncrementMs(SEEK_STEP_MS)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        player = created;
        playerView.setPlayer(created);
        created.setSeekParameters(SeekParameters.CLOSEST_SYNC);
        created.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (player == created && play != null) {
                    play.setText(isPlaying ? "Pause" : "Play");
                    if (isPlaying) {
                        showControlsTemporarily();
                        scheduleControlsHide();
                    } else {
                        showControlsPersistently();
                    }
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (player != created) {
                    return;
                }

                if (buffering != null) {
                    buffering.setVisibility(
                            state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                }

                if (state == Player.STATE_READY) {
                    play.setEnabled(true);
                    seek.setEnabled(true);
                    updateProgress();
                } else if (state == Player.STATE_ENDED) {
                    play.setText("Replay");
                    showControlsPersistently();
                }
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                if (player == created) {
                    handleTracks(tracks);
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                if (player != created || videoSize == null) {
                    return;
                }
                int width = videoSize.width;
                int height = videoSize.height;
                int rotation = videoSize.unappliedRotationDegrees;
                if (rotation == 90 || rotation == 270) {
                    int swap = width;
                    width = height;
                    height = swap;
                }
                videoPixelWidth = width;
                videoPixelHeight = height;
                applyAutomaticResizeMode();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (player != created) {
                    return;
                }

                String userMessage = mapPlaybackError(error);
                releasePlayer();
                detail.setText(userMessage);
                message(userMessage);
            }
        });

        Uri secureUri = Uri.parse(
                EncryptedCipherDataSource.URI_SCHEME + "://media/" + mediaId);

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(secureUri)
                .setMimeType(record.mimeType)
                .setMediaId(mediaId)
                .build();

        created.setMediaItem(mediaItem);
        created.setPlayWhenReady(true);
        created.prepare();
        progressHandler.post(progressTick);
        if (buffering != null) {
            buffering.setVisibility(View.VISIBLE);
        }
    }

    private void handleTracks(Tracks tracks) {
        if (record == null || player == null) {
            return;
        }

        audioChoices.clear();

        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }

            TrackGroup mediaGroup = group.getMediaTrackGroup();
            for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                audioChoices.add(new AudioChoice(
                        mediaGroup,
                        trackIndex,
                        group.getTrackFormat(trackIndex),
                        group.isTrackSupported(trackIndex, true),
                        group.isTrackSelected(trackIndex)));
            }
        }

        if (record.isVideo()
                && tracks.containsType(C.TRACK_TYPE_VIDEO)
                && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, true)) {
            failUnsupportedPlayback(
                    "This video's codec is not supported on this device.");
            return;
        }

        if (record.isAudio()
                && tracks.containsType(C.TRACK_TYPE_AUDIO)
                && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO, true)) {
            failUnsupportedPlayback(
                    "This audio codec is not supported on this device.");
            return;
        }

        updateAudioTrackButton();
    }

    private void updateAudioTrackButton() {
        if (audioTrackButton == null) {
            return;
        }

        int supportedCount = 0;
        AudioChoice selected = null;

        for (AudioChoice choice : audioChoices) {
            if (choice.supported) {
                supportedCount++;
            }
            if (choice.selected) {
                selected = choice;
            }
        }

        if (audioChoices.size() <= 1 || supportedCount == 0) {
            audioTrackButton.setVisibility(View.GONE);
            if (displayOptions != null) {
                displayOptions.setVisibility(View.GONE);
            }
            return;
        }

        audioTrackButton.setVisibility(View.VISIBLE);
        if (displayOptions != null) {
            displayOptions.setVisibility(View.VISIBLE);
        }
        audioTrackButton.setText(
                selected == null
                        ? "Audio tracks"
                        : "Audio: " + shortAudioLabel(selected.format));
    }

    private void showAudioTrackDialog() {
        showControlsPersistently();
        ExoPlayer current = player;
        if (current == null || audioChoices.size() <= 1) {
            return;
        }

        dismissAudioTrackDialog();

        Dialog dialog = new Dialog(this);
        audioTrackDialog = dialog;

        LinearLayout root = Ui.vertical(this);
        root.setPadding(
                Ui.dp(this, 18),
                Ui.dp(this, 18),
                Ui.dp(this, 18),
                Ui.dp(this, 14));
        root.setBackground(Ui.roundRect(this, palette.surface, 20, 1, palette.line));

        root.addView(Ui.heading(this, "Audio track"));
        root.addView(
                Ui.text(
                        this,
                        "Switch between the audio streams stored inside this media file.",
                        13,
                        palette.muted),
                Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 5, 0, 12));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        RadioGroup choicesView = new RadioGroup(this);
        choicesView.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(choicesView, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));

        RadioButton automatic = createTrackRadio("Automatic selection", true);
        automatic.setId(View.generateViewId());
        automatic.setOnClickListener(v -> {
            ExoPlayer activePlayer = player;
            if (activePlayer != null) {
                TrackSelectionParameters parameters =
                        activePlayer.getTrackSelectionParameters()
                                .buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                .build();
                activePlayer.setTrackSelectionParameters(parameters);
            }
            dialog.dismiss();
        });
        choicesView.addView(automatic, trackRowParams());

        int number = 1;
        boolean selectedAssigned = false;

        for (AudioChoice choice : audioChoices) {
            String label = audioLabel(number++, choice);
            RadioButton row = createTrackRadio(label, choice.supported);
            row.setId(View.generateViewId());
            row.setChecked(choice.selected);
            if (choice.selected) {
                selectedAssigned = true;
            }

            row.setOnClickListener(v -> {
                ExoPlayer activePlayer = player;
                if (activePlayer != null && choice.supported) {
                    TrackSelectionOverride override =
                            new TrackSelectionOverride(
                                    choice.group,
                                    choice.trackIndex);

                    TrackSelectionParameters parameters =
                            activePlayer.getTrackSelectionParameters()
                                    .buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                    .setOverrideForType(override)
                                    .build();

                    activePlayer.setTrackSelectionParameters(parameters);
                }
                dialog.dismiss();
            });

            choicesView.addView(row, trackRowParams());
        }

        if (!selectedAssigned) {
            automatic.setChecked(true);
        }

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this, 360)));

        Button close = Ui.secondary(this, "Close");
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(close, Ui.margins(this, Ui.MATCH, Ui.dp(this, 48), 0, 12, 0, 0));

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(ignored -> {
            if (audioTrackDialog == dialog) {
                audioTrackDialog = null;
            }
        });
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            applyScreenshotPolicy(window);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int availableWidth =
                    getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32);
            int width = Math.min(availableWidth, Ui.dp(this, 560));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private RadioButton createTrackRadio(String text, boolean enabled) {
        RadioButton button = new RadioButton(this);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(enabled ? palette.text : palette.muted);
        button.setEnabled(enabled);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(
                Ui.dp(this, 8),
                Ui.dp(this, 8),
                Ui.dp(this, 8),
                Ui.dp(this, 8));
        return button;
    }

    private LinearLayout.LayoutParams trackRowParams() {
        return new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
    }

    private void failUnsupportedPlayback(String userMessage) {
        releasePlayer();
        detail.setText(userMessage);
        message(userMessage);
    }

    private void releasePlayer() {
        progressHandler.removeCallbacks(progressTick);
        dismissAudioTrackDialog();

        ExoPlayer current = player;
        player = null;

        if (playerView != null) {
            try { playerView.setPlayer(null); }
            catch (RuntimeException ignored) { }
        }

        if (current != null) {
            try {
                current.release();
            } catch (RuntimeException ignored) {
                // The reference is already detached; do not allow a release failure to retain UI data.
            }
        }

        audioChoices.clear();
        speedIndex = 1;
        resizeModeIndex = 0;
        resizeModeUserSelected = false;
        videoPixelWidth = 0;
        videoPixelHeight = 0;
        scrubbing = false;
        gestureAdjusting = false;
        chromeHandler.removeCallbacks(hideChrome);
        chromeHandler.removeCallbacks(hideGestureOverlay);
        if (gestureOverlay != null) {
            gestureOverlay.setVisibility(View.GONE);
        }

        if (audioTrackButton != null) {
            audioTrackButton.setText("Audio tracks");
            audioTrackButton.setVisibility(View.GONE);
        }
        if (displayOptions != null) {
            displayOptions.setVisibility(View.GONE);
        }
        if (speed != null) {
            speed.setText("1×");
        }
        if (resize != null) {
            resize.setText("Fit");
        }
        if (playerView != null) {
            playerView.setResizeMode(resizeModes[0]);
        }
        if (play != null) {
            play.setText("Play");
            play.setEnabled(false);
        }
        if (buffering != null) {
            buffering.setVisibility(View.GONE);
        }
        if (seek != null) {
            seek.setProgress(0);
            seek.setEnabled(false);
        }
        if (elapsed != null) {
            elapsed.setText("0:00");
        }
        if (duration != null) {
            duration.setText("0:00");
        }
    }

    private boolean shouldUseImmersiveVideo() {
        return record != null
                && record.isVideo()
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void applyOrientationMode() {
        if (topChrome == null || labelsPanel == null || controls == null) return;
        topChrome.setVisibility(View.GONE);
        labelsPanel.setVisibility(View.GONE);
        boolean immersive = shouldUseImmersiveVideo();
        if (immersive) {
            enterImmersiveMode();
        } else {
            exitImmersiveMode();
        }
        showControlsTemporarily();
        applyAutomaticResizeMode();
        if (contentRoot != null) contentRoot.requestApplyInsets();
    }

    private void installPlayerInsetsHandling() {
        if (contentRoot == null) {
            return;
        }

        contentRoot.setOnApplyWindowInsetsListener((view, insets) -> {
            try {
                if (shouldUseImmersiveVideo()) {
                    // In immersive playback, system-gesture insets must not remain as a black
                    // strip below the video. System bars can still be revealed transiently.
                    view.setPadding(0, 0, 0, 0);
                } else {
                    Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    Insets gestures = insets.getInsets(WindowInsets.Type.systemGestures());
                    Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    int safeBottom = Math.max(Math.max(bars.bottom, gestures.bottom), ime.bottom);
                    view.setPadding(bars.left, bars.top, bars.right, safeBottom);
                }
            } catch (RuntimeException | LinkageError ignored) {
                view.setPadding(0, 0, 0, 0);
            }
            return insets;
        });
        contentRoot.requestApplyInsets();
    }

    private void enterImmersiveMode() {
        try {
            Window window = getWindow();
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } catch (RuntimeException | LinkageError ignored) { }
    }

    private void exitImmersiveMode() {
        try {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.show(WindowInsets.Type.systemBars());
        } catch (RuntimeException | LinkageError ignored) { }
    }

    private void toggleFullscreenControls() {
        if (controlsVisible) hideControlsNow();
        else showControlsPersistently();
    }

    private void showControlsPersistently() {
        chromeHandler.removeCallbacks(hideChrome);
        controlsVisible = true;
        if (controls != null) controls.setVisibility(View.VISIBLE);
        if (actionsRow != null) actionsRow.setVisibility(View.VISIBLE);
        if (overlayTop != null) overlayTop.setVisibility(View.VISIBLE);
        if (topChrome != null) topChrome.setVisibility(View.GONE);
        if (labelsPanel != null) labelsPanel.setVisibility(View.GONE);
    }

    private void showControlsTemporarily() {
        showControlsPersistently();
        scheduleControlsHide();
    }

    private void scheduleControlsHide() {
        chromeHandler.removeCallbacks(hideChrome);
        ExoPlayer current = player;
        if (record != null && record.isVideo() && current != null && current.isPlaying() && !scrubbing) {
            chromeHandler.postDelayed(hideChrome, CONTROLS_HIDE_DELAY_MS);
        }
    }

    private void hideControlsNow() {
        if (scrubbing) return;
        controlsVisible = false;
        if (controls != null) controls.setVisibility(View.GONE);
        if (actionsRow != null) actionsRow.setVisibility(View.GONE);
        if (overlayTop != null) overlayTop.setVisibility(View.GONE);
        if (topChrome != null) topChrome.setVisibility(View.GONE);
        if (shouldUseImmersiveVideo()) {
            enterImmersiveMode();
        }
    }

    private void cycleResizeMode() {
        resizeModeUserSelected = true;
        resizeModeIndex = (resizeModeIndex + 1) % resizeModes.length;
        applyCurrentResizeMode();
        showControlsTemporarily();
    }

    private void applyAutomaticResizeMode() {
        if (!resizeModeUserSelected) {
            // Most tablets are wider/taller than 16:9. Zoom fills the immersive canvas and
            // removes letterbox bars; the Resize button still allows Fit or Fill on demand.
            resizeModeIndex = shouldUseImmersiveVideo() ? 1 : 0;
            if (videoPixelWidth > 0 && videoPixelHeight > videoPixelWidth) {
                resizeModeIndex = 0;
            }
        }
        applyCurrentResizeMode();
    }

    private void applyCurrentResizeMode() {
        if (playerView != null) {
            playerView.setResizeMode(resizeModes[resizeModeIndex]);
        }
        if (resize != null) {
            resize.setText(resizeLabels[resizeModeIndex]);
        }
    }

    private boolean handlePlayerTouch(View touchedView, MotionEvent event) {
        if (record == null || (!record.isVideo() && !record.isAudio())) {
            return false;
        }

        if (tapDetector != null && !gestureAdjusting && !gestureSeeks) {
            tapDetector.onTouchEvent(event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureDownX = event.getX();
                gestureDownY = event.getY();
                gestureAdjusting = false;
                gestureSeeks = false;
                gestureAdjustsVolume = gestureDownX >= Math.max(1, touchedView.getWidth()) / 2f;
                gestureStartVolume = currentMusicVolume();
                gestureStartBrightness = currentWindowBrightness();
                gestureStartPosition = player == null ? 0L : Math.max(0L, player.getCurrentPosition());
                chromeHandler.removeCallbacks(hideGestureOverlay);
                return true;

            case MotionEvent.ACTION_MOVE:
                float verticalDistance = gestureDownY - event.getY();
                float horizontalDistance = event.getX() - gestureDownX;

                if (!gestureAdjusting && !gestureSeeks) {
                    if (Math.abs(verticalDistance) < touchSlop
                            && Math.abs(horizontalDistance) < touchSlop) {
                        return true;
                    }
                    if (Math.abs(horizontalDistance) > Math.abs(verticalDistance)) {
                        gestureSeeks = true;
                        chromeHandler.removeCallbacks(hideChrome);
                    } else if (Math.abs(verticalDistance) > touchSlop) {
                        gestureAdjusting = true;
                        chromeHandler.removeCallbacks(hideChrome);
                    } else {
                        return true;
                    }
                }

                if (gestureSeeks) {
                    applySeekGesture(touchedView, horizontalDistance);
                    return true;
                }

                float height = Math.max(1f, touchedView.getHeight());
                float delta = verticalDistance / height * GESTURE_RANGE_MULTIPLIER;
                if (gestureAdjustsVolume) {
                    adjustMusicVolume(delta);
                } else if (record.isVideo()) {
                    adjustWindowBrightness(delta);
                } else {
                    adjustMusicVolume(delta);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (gestureSeeks && player != null && pendingSeekMs >= 0L) {
                    player.seekTo(pendingSeekMs);
                    pendingSeekMs = -1L;
                    hideGestureOverlayLater();
                    scheduleControlsHide();
                } else if (gestureSeeks && player != null) {
                    hideGestureOverlayLater();
                    scheduleControlsHide();
                } else if (gestureAdjusting) {
                    hideGestureOverlayLater();
                    scheduleControlsHide();
                }
                gestureAdjusting = false;
                gestureSeeks = false;
                return true;

            default:
                return true;
        }
    }

    private void applySeekGesture(View touchedView, float horizontalDistance) {
        ExoPlayer current = player;
        if (current == null) {
            return;
        }
        long durationValue = current.getDuration();
        if (durationValue <= 0L) {
            return;
        }
        float width = Math.max(1f, touchedView.getWidth());
        long span = Math.min(durationValue, 180_000L);
        long target = gestureStartPosition + (long) (horizontalDistance / width * span);
        target = Math.max(0L, Math.min(durationValue, target));
        pendingSeekMs = target;
        if (seek != null) {
            seek.setProgress((int) Math.min(1000L, target * 1000L / durationValue));
        }
        if (elapsed != null) {
            elapsed.setText(formatTime(target));
        }
        showGestureValue((horizontalDistance >= 0f ? "+ " : "− ")
                + formatTime(Math.abs(target - gestureStartPosition))
                + "  →  "
                + formatTime(target));
    }

    private int currentMusicVolume() {
        AudioManager manager = audioManager;
        if (manager == null) {
            return 0;
        }
        try {
            return manager.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private void adjustMusicVolume(float delta) {
        AudioManager manager = audioManager;
        if (manager == null) {
            showGestureValue("Volume unavailable");
            return;
        }

        try {
            int maximum = Math.max(1, manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int target = clampInt(
                    Math.round(gestureStartVolume + delta * maximum),
                    0,
                    maximum);
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            int percent = Math.round(target * 100f / maximum);
            showGestureValue("Volume  " + percent + "%");
        } catch (RuntimeException ignored) {
            showGestureValue("Volume unavailable");
        }
    }

    private float currentWindowBrightness() {
        try {
            float current = getWindow().getAttributes().screenBrightness;
            if (current >= 0f) {
                return clampFloat(current, MIN_WINDOW_BRIGHTNESS, 1f);
            }
            int system = Settings.System.getInt(
                    getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS);
            return clampFloat(system / 255f, MIN_WINDOW_BRIGHTNESS, 1f);
        } catch (Settings.SettingNotFoundException | RuntimeException ignored) {
            return 0.5f;
        }
    }

    private void adjustWindowBrightness(float delta) {
        float target = clampFloat(
                gestureStartBrightness + delta,
                MIN_WINDOW_BRIGHTNESS,
                1f);
        try {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.screenBrightness = target;
            getWindow().setAttributes(attributes);
            showGestureValue("Brightness  " + Math.round(target * 100f) + "%");
        } catch (RuntimeException ignored) {
            showGestureValue("Brightness unavailable");
        }
    }

    private void showGestureValue(String value) {
        if (gestureOverlay == null) {
            return;
        }
        gestureOverlay.setText(value);
        gestureOverlay.setContentDescription(value);
        gestureOverlay.setVisibility(View.VISIBLE);
        gestureOverlay.bringToFront();
        chromeHandler.removeCallbacks(hideGestureOverlay);
    }

    private void hideGestureOverlayLater() {
        chromeHandler.removeCallbacks(hideGestureOverlay);
        chromeHandler.postDelayed(hideGestureOverlay, GESTURE_OVERLAY_HIDE_DELAY_MS);
    }

    private static int clampInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clampFloat(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void dismissAudioTrackDialog() {
        Dialog dialog = audioTrackDialog;
        audioTrackDialog = null;
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (RuntimeException ignored) {
                // The window may already have been detached during lifecycle teardown.
            }
        }
    }

    private void togglePlayback() {
        showControlsTemporarily();
        ExoPlayer current = player;
        if (current == null || !VaultSession.isUnlocked()) {
            return;
        }

        if (current.getPlaybackState() == Player.STATE_ENDED) {
            current.seekTo(0L);
        }

        if (current.isPlaying()) {
            current.pause();
        } else {
            current.play();
        }
    }

    private void seekBy(long delta) {
        showControlsTemporarily();
        ExoPlayer current = player;
        if (current == null) {
            return;
        }

        long durationValue = current.getDuration();
        long target = Math.max(0L, current.getCurrentPosition() + delta);
        if (durationValue > 0L) {
            target = Math.min(durationValue, target);
        }
        current.seekTo(target);
    }

    private void cycleSpeed() {
        showControlsTemporarily();
        speedIndex = (speedIndex + 1) % speeds.length;
        float value = speeds[speedIndex];

        speed.setText(String.format(
                Locale.US,
                "%s×",
                value == Math.round(value)
                        ? Integer.toString(Math.round(value))
                        : Float.toString(value)));

        ExoPlayer current = player;
        if (current != null) {
            current.setPlaybackSpeed(value);
        }
    }

    private void updateProgress() {
        ExoPlayer current = player;
        if (current == null || scrubbing) {
            return;
        }

        long durationValue = current.getDuration();
        long position = Math.max(0L, current.getCurrentPosition());

        if (durationValue > 0L) {
            seek.setProgress((int) Math.min(1000L, position * 1000L / durationValue));
        }

        elapsed.setText(formatTime(position));
        duration.setText(formatTime(Math.max(0L, durationValue)));
    }

    private void export() {
        showControlsTemporarily();
        if (record == null || actionTask != null || !VaultSession.isUnlocked()) {
            return;
        }

        message("Exporting authenticated copy…");
        actionTask = MediaRepository.exportAsync(this, mediaId, (uri, error) -> {
            actionTask = null;
            if (!active) {
                return;
            }

            if (error != null) {
                SecureMediaPlayerActivity.this.error(error);
            } else {
                message("Exported to the device gallery");
            }
        });
    }

    private void cancelOutstandingTasks() {
        if (metadataTask != null) {
            metadataTask.cancel();
            metadataTask = null;
        }
        if (actionTask != null) {
            actionTask.cancel();
            actionTask = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void registerScreenOffReceiver() {
        if (screenReceiverRegistered) {
            return;
        }

        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                        screenOffReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenOffReceiver, filter);
            }
            screenReceiverRegistered = true;
        } catch (RuntimeException ignored) {
            screenReceiverRegistered = false;
        }
    }

    private void unregisterScreenOffReceiver() {
        if (!screenReceiverRegistered) {
            return;
        }

        screenReceiverRegistered = false;
        try {
            unregisterReceiver(screenOffReceiver);
        } catch (RuntimeException ignored) {
            // Receiver may already have been removed by framework teardown.
        }
    }

    private String mapPlaybackError(PlaybackException error) {
        if (!VaultSession.isUnlocked()) {
            return "Vault locked. Unlock it to play this media.";
        }

        switch (error.errorCode) {
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
                return "This media container is not supported on this device.";

            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
                return "This media file is damaged or has an invalid container.";

            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED:
                return "The device could not start a decoder for this media.";

            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return "This audio or video codec is not supported on this device.";

            case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
                return "This media exceeds the device's playback capabilities.";

            case PlaybackException.ERROR_CODE_DECODING_FAILED:
                return "The device could not decode this media securely.";

            case PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND:
                return "The encrypted media file is missing.";

            case PlaybackException.ERROR_CODE_IO_NO_PERMISSION:
                return "The encrypted media file could not be accessed.";

            case PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE:
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED:
                return "Secure media authentication or reading failed.";

            default:
                return "Secure playback could not continue.";
        }
    }

    private static String audioLabel(int number, AudioChoice choice) {
        StringBuilder label = new StringBuilder("Track ").append(number);
        Format format = choice.format;

        if (format.label != null && !format.label.trim().isEmpty()) {
            label.append(" • ").append(format.label.trim());
        } else {
            String language = displayLanguage(format.language);
            if (!language.isEmpty()) {
                label.append(" • ").append(language);
            }
        }

        if (format.channelCount > 0) {
            label.append(" • ").append(format.channelCount).append(" ch");
        }

        if (format.sampleMimeType != null && !format.sampleMimeType.trim().isEmpty()) {
            label.append(" • ").append(format.sampleMimeType.trim());
        }

        if (!choice.supported) {
            label.append(" • Unsupported");
        }

        return label.toString();
    }

    private static String shortAudioLabel(Format format) {
        if (format.label != null && !format.label.trim().isEmpty()) {
            return format.label.trim();
        }

        String language = displayLanguage(format.language);
        if (!language.isEmpty()) {
            return language;
        }

        return format.channelCount > 0
                ? format.channelCount + " ch"
                : "Selected";
    }

    private static String displayLanguage(String languageTag) {
        if (languageTag == null) {
            return "";
        }

        String trimmed = languageTag.trim();
        if (trimmed.isEmpty() || "und".equalsIgnoreCase(trimmed)) {
            return "";
        }

        Locale locale = Locale.forLanguageTag(trimmed);
        String value = locale.getDisplayLanguage();
        return value == null || value.trim().isEmpty() ? trimmed : value;
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        return hours > 0L
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }

        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.US, "%.1f KB", kb);
        }

        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(Locale.US, "%.1f MB", mb);
        }

        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static final class AudioChoice {
        final TrackGroup group;
        final int trackIndex;
        final Format format;
        final boolean supported;
        final boolean selected;

        AudioChoice(
                TrackGroup group,
                int trackIndex,
                Format format,
                boolean supported,
                boolean selected) {
            this.group = group;
            this.trackIndex = trackIndex;
            this.format = format;
            this.supported = supported;
            this.selected = selected;
        }
    }
}
