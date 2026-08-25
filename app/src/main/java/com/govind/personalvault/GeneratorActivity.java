package com.govind.personalvault;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.govind.personalvault.model.VaultItem;
import com.govind.personalvault.ui.Ui;

import java.security.SecureRandom;

public final class GeneratorActivity extends BaseActivity {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int[] LENGTHS = {12, 16, 20, 24};
    private int lengthIndex = 2;
    private TextView preview;
    private Button lengthButton;
    private String current = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(palette.bg);
        root.addView(topBar("Generator", "Strong passwords, created on this phone.", true, null, null));
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = Ui.vertical(this);
        page.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 28));
        page.addView(Ui.text(this, "The value never leaves the device. Copy it or drop it into a new login.", 13, palette.muted));
        preview = Ui.heading(this, "");
        preview.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18));
        preview.setBackground(Ui.roundRect(this, palette.surface, 18, 1, Ui.withAlpha(palette.line, 120)));
        page.addView(preview, Ui.margins(this, Ui.MATCH, Ui.WRAP, 0, 16, 0, 0));
        lengthButton = Ui.secondary(this, "Length  20");
        lengthButton.setOnClickListener(v -> {
            lengthIndex = (lengthIndex + 1) % LENGTHS.length;
            lengthButton.setText("Length  " + LENGTHS[lengthIndex]);
            generate();
        });
        page.addView(lengthButton, Ui.margins(this, Ui.MATCH, Ui.dp(this, 48), 0, 14, 0, 0));
        Button generate = Ui.primary(this, "Generate");
        generate.setOnClickListener(v -> generate());
        page.addView(generate, Ui.margins(this, Ui.MATCH, Ui.dp(this, 52), 0, 10, 0, 0));
        Button copy = Ui.secondary(this, "Copy");
        copy.setOnClickListener(v -> {
            if (current.isEmpty()) generate();
            copySecret("password", current);
        });
        page.addView(copy, Ui.margins(this, Ui.MATCH, Ui.dp(this, 48), 0, 8, 0, 0));
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

    private void generate() {
        current = create(LENGTHS[lengthIndex]);
        preview.setText(current);
    }

    static String create(int length) {
        int safe = Math.max(12, Math.min(32, length));
        final char[] lower = "abcdefghijkmnopqrstuvwxyz".toCharArray();
        final char[] upper = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
        final char[] digits = "23456789".toCharArray();
        final char[] symbols = "!@#$%&*+-=?".toCharArray();
        char[] all = (new String(lower) + new String(upper) + new String(digits) + new String(symbols)).toCharArray();
        char[] result = new char[safe];
        result[0] = lower[RANDOM.nextInt(lower.length)];
        result[1] = upper[RANDOM.nextInt(upper.length)];
        result[2] = digits[RANDOM.nextInt(digits.length)];
        result[3] = symbols[RANDOM.nextInt(symbols.length)];
        for (int i = 4; i < result.length; i++) result[i] = all[RANDOM.nextInt(all.length)];
        for (int i = result.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char temp = result[i];
            result[i] = result[j];
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
