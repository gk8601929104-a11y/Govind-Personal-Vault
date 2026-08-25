package com.govind.personalvault.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

public final class Ui {
    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    public static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    public static final class Palette {
        public final int bg;
        public final int surface;
        public final int raised;
        public final int text;
        public final int muted;
        public final int accent;
        public final int accentText;
        public final int danger;
        public final int warning;
        public final int line;

        Palette(int bg, int surface, int raised, int text, int muted, int accent, int accentText, int danger, int warning, int line) {
            this.bg = bg;
            this.surface = surface;
            this.raised = raised;
            this.text = text;
            this.muted = muted;
            this.accent = accent;
            this.accentText = accentText;
            this.danger = danger;
            this.warning = warning;
            this.line = line;
        }
    }

    private static Palette PALETTE = darkPalette();
    private Ui() {}
    public static Palette colors() { return PALETTE; }

    public static Palette darkPalette() {
        return new Palette(
                Color.rgb(8, 8, 8),
                Color.rgb(18, 18, 18),
                Color.rgb(26, 26, 26),
                Color.rgb(244, 241, 234),
                Color.rgb(154, 154, 148),
                Color.rgb(244, 241, 234),
                Color.rgb(18, 18, 18),
                Color.rgb(227, 107, 94),
                Color.rgb(214, 186, 122),
                Color.rgb(48, 48, 48));
    }

    public static Palette lightPalette() {
        return new Palette(
                Color.rgb(246, 244, 238),
                Color.rgb(255, 255, 255),
                Color.rgb(236, 232, 224),
                Color.rgb(22, 22, 20),
                Color.rgb(110, 108, 102),
                Color.rgb(22, 22, 20),
                Color.rgb(246, 244, 238),
                Color.rgb(196, 72, 62),
                Color.rgb(160, 122, 48),
                Color.rgb(214, 210, 200));
    }

    public static void applyTheme(boolean light) {
        PALETTE = light ? lightPalette() : darkPalette();
    }

    public static Typeface serif() {
        return Typeface.create("serif", Typeface.NORMAL);
    }
    public static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    public static LinearLayout vertical(Context context) { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
    public static LinearLayout horizontal(Context context) { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }

    public static TextView text(Context context, String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(context, 1), 1.12f);
        return view;
    }

    public static TextView display(Context context, String value) {
        TextView view = text(context, value, 34, PALETTE.text);
        view.setTypeface(serif());
        view.setLetterSpacing(-0.02f);
        return view;
    }

    public static TextView title(Context context, String value) {
        TextView view = text(context, value, 28, PALETTE.text);
        view.setTypeface(serif());
        view.setLetterSpacing(-0.02f);
        return view;
    }

    public static TextView heading(Context context, String value) {
        TextView view = text(context, value, 20, PALETTE.text);
        view.setTypeface(serif());
        view.setLetterSpacing(-0.01f);
        return view;
    }

    public static TextView label(Context context, String value) {
        TextView view = text(context, value, 12, PALETTE.muted);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLetterSpacing(0.04f);
        return view;
    }

    public static EditText edit(Context context, String hint, int maxLength) {
        EditText edit = baseEdit(context, hint, maxLength);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return edit;
    }

    public static EditText username(Context context, String hint, int maxLength) {
        EditText edit = baseEdit(context, hint, maxLength);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return edit;
    }

    public static EditText secret(Context context, String hint, int maxLength) {
        EditText edit = baseEdit(context, hint, maxLength);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return edit;
    }

    public static EditText pin(Context context, String hint) {
        EditText edit = baseEdit(context, hint, 12);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        edit.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        edit.setTextSize(22);
        edit.setLetterSpacing(0.22f);
        edit.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(context, 58)));
        return edit;
    }

    public static EditText multiLine(Context context, String hint, int maxLength, int minLines) {
        EditText edit = baseEdit(context, hint, maxLength);
        edit.setSingleLine(false);
        edit.setMinLines(minLines);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        edit.setLayoutParams(params);
        edit.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        return edit;
    }

    private static EditText baseEdit(Context context, String hint, int maxLength) {
        EditText edit = new EditText(context);
        edit.setHint(hint);
        edit.setHintTextColor(withAlpha(PALETTE.muted, 200));
        edit.setTextColor(PALETTE.text);
        edit.setTextSize(16);
        edit.setPadding(dp(context, 16), dp(context, 2), dp(context, 16), dp(context, 2));
        edit.setBackground(roundRect(context, PALETTE.raised, 16, 1, withAlpha(PALETTE.line, 140)));
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        edit.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(context, 54)));
        edit.setSaveEnabled(false);
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        return edit;
    }

    public static Button primary(Context context, String text) {
        return button(context, text, PALETTE.accent, PALETTE.accentText, true, 0, 0);
    }

    public static Button secondary(Context context, String text) {
        return button(context, text, PALETTE.surface, PALETTE.text, false, 1, withAlpha(PALETTE.line, 180));
    }

    public static Button danger(Context context, String text) {
        return button(context, text, withAlpha(PALETTE.danger, 28), PALETTE.danger, true, 1, withAlpha(PALETTE.danger, 90));
    }

    public static Button button(Context context, String text, int fill, int textColor, boolean bold) {
        return button(context, text, fill, textColor, bold, 0, 0);
    }

    public static Button button(Context context, String text, int fill, int textColor, boolean bold, float stroke, int strokeColor) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextColor(textColor);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        button.setLetterSpacing(0.01f);
        if (bold) button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable base = roundRect(context, fill, 14, stroke, strokeColor);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(textColor, 48)), base, null));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        button.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(context, 52)));
        return button;
    }

    public static Button overlayBack(Context context) {
        Button button = new Button(context);
        button.setText("‹");
        button.setTextSize(26);
        button.setTextColor(PALETTE.text);
        button.setAllCaps(false);
        button.setContentDescription("Back");
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        int size = dp(context, 44);
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        GradientDrawable base = roundRect(context, withAlpha(PALETTE.bg, 175), 99, 1, withAlpha(PALETTE.text, 55));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(PALETTE.text, 45)), base, null));
        return button;
    }

    public static Button iconButton(Context context, String glyph, String description) {
        Button button = overlayBack(context);
        button.setText(glyph);
        button.setTextSize(16);
        button.setContentDescription(description);
        return button;
    }

    public static Button pill(Context context, String text, boolean selected) {
        Button button = button(
                context,
                text,
                selected ? PALETTE.accent : Color.TRANSPARENT,
                selected ? PALETTE.accentText : PALETTE.text,
                false,
                selected ? 0 : 1,
                selected ? 0 : withAlpha(PALETTE.line, 200));
        button.setTextSize(13);
        button.setSingleLine(true);
        button.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(WRAP, dp(context, 36)));
        return button;
    }

    public static Button chip(Context context, String text) {
        Button button = pill(context, text, false);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, dp(context, 40), 1));
        return button;
    }

    public static LinearLayout card(Context context) {
        LinearLayout card = vertical(context);
        card.setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 18));
        card.setBackground(roundRect(context, PALETTE.surface, 22, 1, withAlpha(PALETTE.line, 120)));
        return card;
    }

    public static TextView badge(Context context, String text, int color) {
        TextView badge = Ui.text(context, text, 11, color);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setLetterSpacing(0.08f);
        badge.setPadding(dp(context, 11), dp(context, 6), dp(context, 11), dp(context, 6));
        badge.setBackground(roundRect(context, withAlpha(color, 22), 99, 1, withAlpha(color, 70)));
        return badge;
    }

    public static Space space(Context context, int height) {
        Space space = new Space(context);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, height)));
        return space;
    }

    public static LinearLayout.LayoutParams margins(Context context, int width, int height, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.setMargins(dp(context, l), dp(context, t), dp(context, r), dp(context, b));
        return p;
    }

    public static GradientDrawable roundRect(Context context, int color, float radius, float stroke, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(context, radius));
        if (stroke > 0) d.setStroke(dp(context, stroke), strokeColor);
        return d;
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
