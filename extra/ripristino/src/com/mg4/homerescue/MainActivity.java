package com.mg4.homerescue;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * MG4 Ripristino Home: all'apertura riaccende la home MG originale (tutti i firmware conosciuti), la rimette come
 * predefinita, lo dice in 8 lingue e propone di disinstallarsi. Funziona anche se il launcher non c'è più.
 * Colori: palette MG4 notte (bg 0A0B0D, panel 15171B, chip 1E2126, testo F2F3F5, sub 9BA1A9, batteria 34C77B).
 */
public final class MainActivity extends Activity {
    private static final int BG = 0xFF0A0B0D, PANEL = 0xFF15171B, CHIP = 0xFF1E2126, TEXT = 0xFFF2F3F5, SUB = 0xFF9BA1A9,
            OK = 0xFF34C77B;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView mark, title, note, detail;
    private LinearLayout buttons;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BG);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(56), dp(44), dp(56), dp(40));
        card.setBackground(round(PANEL, 28));
        mark = text("…", 64, SUB, true);
        title = text(getString(R.string.working), 34, TEXT, true);
        note = text("", 20, SUB, false);
        detail = text("", 14, SUB, false);
        detail.setTypeface(Typeface.MONOSPACE);
        card.addView(mark);
        card.addView(title, margins(0, dp(10)));
        card.addView(note, margins(0, dp(16)));
        buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.setVisibility(View.INVISIBLE);
        buttons.addView(button(getString(R.string.open_home), true, v -> {
            if (HomeRescue.openMgHome(this)) finish();
        }));
        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(dp(16), 1));
        buttons.addView(button(getString(R.string.uninstall), false, v -> uninstallSelf()));
        card.addView(buttons, margins(0, dp(30)));
        card.addView(detail, margins(0, dp(24)));
        root.addView(card, new LinearLayout.LayoutParams(dp(1100), -2));
        setContentView(root);

        new Thread(() -> {
            String r = HomeRescue.rescue(getApplicationContext(), "app aperta");
            boolean ok = HomeRescue.mgHomeOk(this);
            boolean launcher = HomeRescue.installed(this, HomeRescue.LAUNCHER);
            main.post(() -> show(ok, launcher, r));
        }, "mg4-homerescue").start();
    }

    private void show(boolean ok, boolean launcher, String result) {
        mark.setText(ok ? "✓" : "!");
        mark.setTextColor(ok ? OK : TEXT);
        title.setText(ok ? R.string.ok : R.string.fail);
        note.setText(launcher ? getString(R.string.launcher_present) : "");
        note.setVisibility(launcher ? View.VISIBLE : View.GONE);
        detail.setText(result);
        buttons.setVisibility(View.VISIBLE);
    }

    /** Disinstallazione di questa app (DELETE_PACKAGES dalla firma platform); se non si può, conferma di sistema. */
    private void uninstallSelf() {
        try {
            String action = getPackageName() + ".TOLTA";
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(action).setPackage(getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            getPackageManager().getPackageInstaller().uninstall(getPackageName(), pi.getIntentSender());
        } catch (Throwable e) {
            try {
                startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) {
            }
        }
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private TextView button(String s, boolean primary, View.OnClickListener l) {
        TextView b = text(s, 21, primary ? PANEL : TEXT, true);
        b.setPadding(dp(30), dp(16), dp(30), dp(16));
        b.setBackground(round(primary ? TEXT : CHIP, 16));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams margins(int left, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(left, top, 0, 0);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
