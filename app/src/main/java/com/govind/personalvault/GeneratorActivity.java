package com.govind.personalvault;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.govind.personalvault.model.VaultItem;
import com.govind.personalvault.ui.Ui;

import java.security.SecureRandom;

public final class GeneratorActivity extends BaseActivity {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int[] LENGTHS = {12, 16, 20, 24};
    private int length = 20;
    private boolean upper = true;
    private boolean lower = true;
    private boolean digits = true;
    private boolean symbols = true;
    private TextView preview;
    private LinearLayout lengthRow;
    private String current = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(palette.bg);
        LinearLayout top = Ui.horizontal(this);
        top.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        Button back = Ui.iconButton(this, "‹", "Back");
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView brand = Ui.text(this, "Vault", 16, palette.text);
        brand.setTypeface(Ui.serif());
        top.addView(brand, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        root.addView(top);
        View hairline = new android.view.View(this);
        hairline.setBackgroundColor(Ui.withAlpha(palette.line, 180));
        root.addView(hairline, new LinearLayout.LayoutParams(Ui.MATCH, 1));

        ScrollView scroll = new ScrollView(this);
        LinearLayout page = Ui.vertical(this);
        page.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 28));
        page.addView(Ui.label(this, "GENERATOR"));
        page.addView(Ui.title(this, "Generator"), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));
        page.addView(Ui.text(this, "Strong passwords, created on this device. Copy it or drop it into a new login.", 14, palette.muted), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));

        preview = Ui.heading(this, "");
        preview.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18));
        preview.setBackground(Ui.roundRect(this, palette.surface, 18, 1, Ui.withAlpha(palette.line, 120)));
        preview.setOnClickListener(v -> generate());
        page.addView(preview, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 18, 0, 0));

        page.addView(Ui.label(this, "Length"), Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 18, 0, 8));
        lengthRow = Ui.horizontal(this);
        page.addView(lengthRow);
        rebuildLengths();

        LinearLayout checks = Ui.vertical(this);
        checks.addView(checkRow("Uppercase", upper, v -> { upper = ((CheckBox) v).isChecked(); generate(); }));
        checks.addView(checkRow("Lowercase", lower, v -> { lower = ((CheckBox) v).isChecked(); generate(); }));
        checks.addView(checkRow("Digits", digits, v -> { digits = ((CheckBox) v).isChecked(); generate(); }));
        checks.addView(checkRow("Symbols", symbols, v -> { symbols = ((CheckBox) v).isChecked(); generate(); }));
        page.addView(checks, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 16, 0, 0));

        Button copy = Ui.primary(this, "Copy password");
        copy.setOnClickListener(v -> {
            if (current.isEmpty()) generate();
            copySecret("password", current);
        });
        page.addView(copy, Ui.margins(this, Ui.MATCH, Ui.dp(this, 52), 0, 22, 0, 0));
        Button use = Ui.secondary(this, "Use in new login");
        use.setOnClickListener(v -> {
            if (current.isEmpty()) generate();
            Intent intent = new Intent(this, EntryEditorActivity.class);
            intent.putExtra("kind", VaultItem.PASSWORD);
            intent.putExtra("prefill_secret", current);
            startActivity(intent);
            finish();
        });
        page.addView(use, Ui.margins(this, Ui.MATCH, Ui.dp(this, 48), 0, 8, 0, 0));
        scroll.addView(page, centeredScrollParams(640));
        root.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        safeContentView(root);
        generate();
    }

    private LinearLayout checkRow(String label, boolean on, android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = Ui.horizontal(this);
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        CheckBox box = new CheckBox(this);
        box.setChecked(on);
        box.setOnCheckedChangeListener(listener);
        row.addView(box);
        TextView name = Ui.text(this, label, 15, palette.text);
        row.addView(name, Ui.margins(this, Ui.WRAP, Ui.WRAP, 8, 0, 0, 0));
        return row;
    }

    private void rebuildLengths() {
        lengthRow.removeAllViews();
        for (int i = 0; i < LENGTHS.length; i++) {
            final int value = LENGTHS[i];
            Button chip = Ui.pill(this, String.valueOf(value), value == length);
            chip.setOnClickListener(v -> {
                length = value;
                rebuildLengths();
                generate();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(this, 36));
            if (i > 0) p.leftMargin = Ui.dp(this, 8);
            lengthRow.addView(chip, p);
        }
    }

    private void generate() {
        current = create(length, upper, lower, digits, symbols);
        preview.setText(current);
    }

    static String create(int length) {
        return create(length, true, true, true, true);
    }

    static String create(int length, boolean upperOn, boolean lowerOn, boolean digitsOn, boolean symbolsOn) {
        int safe = Math.max(8, Math.min(64, length));
        StringBuilder pools = new StringBuilder();
        java.util.ArrayList<char[]> required = new java.util.ArrayList<>();
        if (lowerOn) { char[] c = "abcdefghijkmnopqrstuvwxyz".toCharArray(); required.add(c); pools.append(c); }
        if (upperOn) { char[] c = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray(); required.add(c); pools.append(c); }
        if (digitsOn) { char[] c = "23456789".toCharArray(); required.add(c); pools.append(c); }
        if (symbolsOn) { char[] c = "!@#$%&*+-=?".toCharArray(); required.add(c); pools.append(c); }
        if (required.isEmpty()) { char[] c = "abcdefghijkmnopqrstuvwxyz".toCharArray(); required.add(c); pools.append(c); }
        char[] all = pools.toString().toCharArray();
        char[] result = new char[safe];
        int i = 0;
        for (; i < required.size() && i < result.length; i++) {
            char[] pool = required.get(i);
            result[i] = pool[RANDOM.nextInt(pool.length)];
        }
        for (; i < result.length; i++) result[i] = all[RANDOM.nextInt(all.length)];
        for (int n = result.length - 1; n > 0; n--) {
            int j = RANDOM.nextInt(n + 1);
            char temp = result[n];
            result[n] = result[j];
            result[j] = temp;
        }
        String value = new String(result);
        java.util.Arrays.fill(result, '\0');
        return value;
    }

    @Override protected void clearSensitiveUi() {
        current = "";
        if (preview != null) preview.setText("");
    }
}
