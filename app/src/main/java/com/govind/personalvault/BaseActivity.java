package com.govind.personalvault;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import com.govind.personalvault.security.SecurityManager;
import com.govind.personalvault.security.VaultSession;
import com.govind.personalvault.ui.Ui;

public abstract class BaseActivity extends ComponentActivity {
    protected Ui.Palette palette;
    private static volatile String sensitiveClipboardValue;
    private static final Handler idleHandler = new Handler(Looper.getMainLooper());
    private static final Runnable idleLock = () -> {
        if (VaultSession.isUnlocked()) VaultSession.lock();
    };

    /**
     * Screenshot / screen-record blocking. Keep false only while collecting QA screenshots.
     * Set back to true before the final public release.
     */
    public static final boolean BLOCK_SCREENSHOTS = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        Ui.applyTheme(VaultPrefs.isLight(this));
        palette = Ui.colors();
        applyScreenshotPolicy(getWindow());
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
        configureWindow();
    }

    protected static void applyScreenshotPolicy(Window window) {
        if (window == null) return;
        if (BLOCK_SCREENSHOTS) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (requiresUnlockedVault() && SecurityManager.get(this).isSetUp() && !VaultSession.isUnlocked()) {
            Intent lock = new Intent(this, LockActivity.class);
            lock.putExtra("overlay", true);
            lock.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(lock);
        }
    }

    @Override protected void onStop() {
        if (!isChangingConfigurations()) clearSensitiveUi();
        super.onStop();
    }

    /** Activities override this to remove decrypted view/model state whenever they are no longer visible. */
    protected void clearSensitiveUi() { }

    protected boolean requiresUnlockedVault() { return true; }

    @SuppressWarnings("deprecation")
    private void configureWindow() {
        try {
            Window window = getWindow();
            window.setDecorFitsSystemWindows(false);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            window.setNavigationBarContrastEnforced(false);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowInsetsController controller = window.getInsetsController();
            int flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if (controller != null) {
                if (VaultPrefs.isLight(this)) {
                    controller.setSystemBarsAppearance(flags, flags);
                } else {
                    controller.setSystemBarsAppearance(0, flags);
                }
            }
        } catch (RuntimeException | LinkageError ignored) { }
    }

    protected void safeContentView(View root) {
        int left=root.getPaddingLeft(), top=root.getPaddingTop(), right=root.getPaddingRight(), bottom=root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view,insets)->{
            try {
                Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                Insets gestures=insets.getInsets(WindowInsets.Type.systemGestures());
                Insets ime=insets.getInsets(WindowInsets.Type.ime());
                int safeBottom=Math.max(Math.max(bars.bottom,gestures.bottom),ime.bottom);
                view.setPadding(left+bars.left,top+bars.top,right+bars.right,bottom+safeBottom);
            } catch (RuntimeException | LinkageError ignored) { view.setPadding(left,top,right,bottom); }
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
    }

    /** Hide status/nav bars for file viewers so the file fills the screen. */
    protected void setViewerImmersive(boolean hide) {
        try {
            Window window = getWindow();
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) return;
            if (hide) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            } else {
                controller.show(WindowInsets.Type.systemBars());
            }
        } catch (RuntimeException | LinkageError ignored) { }
    }

    protected void setFileViewerContent(View root, View topOverlay, View bottomOverlay) {
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            try {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                if (topOverlay != null) {
                    topOverlay.setPadding(
                            bars.left + Ui.dp(this, 10),
                            bars.top + Ui.dp(this, 8),
                            bars.right + Ui.dp(this, 10),
                            Ui.dp(this, 8));
                }
                if (bottomOverlay != null) {
                    bottomOverlay.setPadding(
                            bars.left + Ui.dp(this, 10),
                            Ui.dp(this, 8),
                            bars.right + Ui.dp(this, 10),
                            bars.bottom + Ui.dp(this, 10));
                }
            } catch (RuntimeException | LinkageError ignored) { }
            return insets;
        });
        root.requestApplyInsets();
    }

    protected LinearLayout topBar(String title, String subtitle, boolean back, String action, View.OnClickListener listener) {
        return topBar(title, subtitle, back, action, listener, null, null);
    }

    protected LinearLayout topBar(
            String title,
            String subtitle,
            boolean back,
            String action,
            View.OnClickListener listener,
            String extraGlyph,
            View.OnClickListener extraListener) {
        LinearLayout bar = Ui.horizontal(this);
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        bar.setBackgroundColor(palette.bg);
        if (back) {
            Button button = Ui.overlayBack(this);
            button.setOnClickListener(v -> finish());
            bar.addView(button);
        }
        LinearLayout labels = Ui.vertical(this);
        labels.setPadding(Ui.dp(this, back ? 12 : 2), 0, extraGlyph != null || action != null ? Ui.dp(this, 8) : 0, 0);
        TextView heading = Ui.text(this, title, 20, palette.text);
        heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        heading.setLetterSpacing(-0.02f);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(heading);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = Ui.text(this, subtitle, 12, palette.muted);
            sub.setPadding(0, Ui.dp(this, 3), 0, 0);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(sub);
        }
        bar.addView(labels, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        if (extraGlyph != null) {
            Button extra = Ui.iconButton(this, extraGlyph, "Search");
            extra.setOnClickListener(extraListener);
            LinearLayout.LayoutParams extraParams = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
            extraParams.rightMargin = action != null ? Ui.dp(this, 8) : 0;
            bar.addView(extra, extraParams);
        }
        if (action != null) {
            Button button = Ui.chip(this, action);
            button.setLayoutParams(new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 40)));
            button.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
            button.setOnClickListener(listener);
            bar.addView(button);
        }
        return bar;
    }

    protected void toggleSearchBox(View searchBox, android.widget.EditText field) {
        if (searchBox == null || field == null) return;
        boolean open = searchBox.getVisibility() != View.VISIBLE;
        searchBox.setVisibility(open ? View.VISIBLE : View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (open) {
            field.requestFocus();
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        } else if (imm != null) {
            imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        }
    }

    protected void field(LinearLayout parent, String label, View input) {
        parent.addView(Ui.label(this,label),Ui.margins(this,Ui.MATCH,Ui.WRAP,2,12,2,6)); parent.addView(input);
    }

    protected ScrollView.LayoutParams centeredScrollParams(int maxWidthDp) {
        int width=Math.min(getResources().getDisplayMetrics().widthPixels,Ui.dp(this,maxWidthDp));
        ScrollView.LayoutParams params=new ScrollView.LayoutParams(width,Ui.WRAP);params.gravity=Gravity.CENTER_HORIZONTAL;return params;
    }

    protected LinearLayout.LayoutParams centeredPanelParams(int maxWidthDp) {
        int available=Math.max(1,getResources().getDisplayMetrics().widthPixels-Ui.dp(this,44));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(Math.min(available,Ui.dp(this,maxWidthDp)),Ui.WRAP);params.gravity=Gravity.CENTER;return params;
    }

    protected void message(String text) { Toast.makeText(this,text,Toast.LENGTH_SHORT).show(); }
    protected void error(Exception error) { message(error == null || error.getMessage() == null ? "The operation could not be completed" : error.getMessage()); }

    protected void copySecret(String label, String value) {
        ClipboardManager manager=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if(manager==null){message("Clipboard is unavailable");return;}
        ClipData clip=ClipData.newPlainText(label,value);
        PersistableBundle extras=new PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE",true); clip.getDescription().setExtras(extras);
        manager.setPrimaryClip(clip); sensitiveClipboardValue=value;
        long clearAfter = VaultPrefs.clipboardMs(this);
        message("Copied. Clipboard clears in " + (clearAfter / 1000L) + "s");
        new Handler(Looper.getMainLooper()).postDelayed(()->{
            try {
                ClipData current=manager.getPrimaryClip();
                if(current==null||current.getItemCount()<=0)return;
                android.content.ClipData.Item first=current.getItemAt(0);
                CharSequence text=first==null?null:first.coerceToText(this);
                if(text!=null&&value.contentEquals(text)){manager.clearPrimaryClip();sensitiveClipboardValue=null;}
            } catch(RuntimeException ignored){ }
        }, clearAfter);
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        bumpIdleTimer();
        return super.dispatchTouchEvent(ev);
    }

    protected void bumpIdleTimer() {
        idleHandler.removeCallbacks(idleLock);
        long ms = VaultPrefs.autolockMs(this);
        if (ms > 0L && VaultSession.isUnlocked()) idleHandler.postDelayed(idleLock, ms);
    }

    static void cancelIdleTimer() {
        idleHandler.removeCallbacks(idleLock);
    }

    static void clearSensitiveClipboard(Context context) {
        String expected=sensitiveClipboardValue;
        if(expected==null)return;
        try {
            ClipboardManager manager=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData current=manager==null?null:manager.getPrimaryClip();
            if(manager!=null&&current!=null&&current.getItemCount()>0){
                android.content.ClipData.Item first=current.getItemAt(0);
                CharSequence text=first==null?null:first.coerceToText(context);
                if(text!=null&&expected.contentEquals(text))manager.clearPrimaryClip();
            }
        } catch(RuntimeException ignored){ }
        sensitiveClipboardValue=null;
    }
}
