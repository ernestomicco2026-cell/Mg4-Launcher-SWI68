package com.mg4.launcher.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Conferma a tutto schermo nello stile del launcher: titolo, spiegazione, "Annulla" e pulsante principale. */
public final class Confirm {
    private Confirm() {}

    public static void show(FrameLayout root, Theme t, String title, String msg, String cancel, String ok, Runnable onOk) {
        Context c = root.getContext();
        FrameLayout dim = new FrameLayout(c);
        dim.setBackgroundColor(t.day ? 0x66000000 : 0xB3000000);
        dim.setClickable(true);
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.round(t.panel, Ui.dp(c, 28)));
        card.setPadding(Ui.dp(c, 40), Ui.dp(c, 34), Ui.dp(c, 40), Ui.dp(c, 30));
        card.setClickable(true);
        card.addView(Ui.text(c, title, 30, t.text, Ui.MEDIUM));
        TextView m = Ui.text(c, msg, 20, t.sub, Ui.REGULAR);
        m.setLineSpacing(0, 1.15f);
        card.addView(m, Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, Ui.dp(c, 16), 0, 0));
        LinearLayout btns = Ui.row(c);
        btns.setGravity(Gravity.END);
        btns.addView(button(c, t, cancel, false, v -> ((ViewGroup) dim.getParent()).removeView(dim)), Ui.lp(-2, Ui.tp(c, 60)));
        btns.addView(button(c, t, ok, true, v -> {
            ((ViewGroup) dim.getParent()).removeView(dim);
            onOk.run();
        }), Ui.margins(Ui.lp(-2, Ui.tp(c, 60)), Ui.dp(c, 12), 0, 0, 0));
        card.addView(btns, Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, Ui.dp(c, 28), 0, 0));
        dim.addView(card, new FrameLayout.LayoutParams(Ui.dp(c, 980), -2, Gravity.CENTER));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
    }

    public interface OnPick {
        void picked(int index);
    }

    /**
     * Scelta tra pochi valori (es. limite di ricarica, corrente): titolo, spiegazione, un pulsante per valore
     * (quello attuale evidenziato) e "Annulla". Toccare un valore chiude e lo applica.
     */
    public static void pick(FrameLayout root, Theme t, String title, String msg, String[] options, int selected, String cancel, OnPick onPick) {
        Context c = root.getContext();
        FrameLayout dim = new FrameLayout(c);
        dim.setBackgroundColor(t.day ? 0x66000000 : 0xB3000000);
        dim.setClickable(true);
        dim.setOnClickListener(v -> ((ViewGroup) dim.getParent()).removeView(dim));
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.round(t.panel, Ui.dp(c, 28)));
        card.setPadding(Ui.dp(c, 40), Ui.dp(c, 34), Ui.dp(c, 40), Ui.dp(c, 30));
        card.setClickable(true);
        card.addView(Ui.text(c, title, 30, t.text, Ui.MEDIUM));
        if (msg != null && !msg.isEmpty()) {
            TextView m = Ui.text(c, msg, 20, t.sub, Ui.REGULAR);
            m.setLineSpacing(0, 1.15f);
            card.addView(m, Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, Ui.dp(c, 16), 0, 0));
        }
        LinearLayout opts = Ui.row(c);
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            boolean on = i == selected;
            TextView b = Ui.text(c, options[i], Ui.ts(24), on ? t.panel : t.text, Ui.MEDIUM);
            b.setGravity(Gravity.CENTER);
            b.setBackground(on ? Ui.round(t.text, Ui.tp(c, 18)) : Ui.pressable(c, t.chip, 18 * Ui.K, t));
            b.setClickable(true);
            b.setOnClickListener(v -> {
                ((ViewGroup) dim.getParent()).removeView(dim);
                if (idx != selected) onPick.picked(idx);
            });
            opts.addView(b, Ui.margins(Ui.lpWeight(0, Ui.tp(c, 84), 1), i == 0 ? 0 : Ui.dp(c, 12), 0, 0, 0));
        }
        card.addView(opts, Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, Ui.dp(c, 26), 0, 0));
        LinearLayout btns = Ui.row(c);
        btns.setGravity(Gravity.END);
        btns.addView(button(c, t, cancel, false, v -> ((ViewGroup) dim.getParent()).removeView(dim)), Ui.lp(-2, Ui.tp(c, 60)));
        card.addView(btns, Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, Ui.dp(c, 24), 0, 0));
        dim.addView(card, new FrameLayout.LayoutParams(Ui.dp(c, 1080), -2, Gravity.CENTER));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Avviso con elenco puntato e un solo pulsante (popup "Novità"). */
    public static void notice(FrameLayout root, Theme t, String title, String subtitle, String[] items, String ok, Runnable onClose) {
        Context c = root.getContext();
        FrameLayout dim = new FrameLayout(c);
        dim.setBackgroundColor(t.day ? 0x66000000 : 0xB3000000);
        dim.setClickable(true);
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.round(t.panel, Ui.dp(c, 28)));
        card.setPadding(Ui.dp(c, 44), Ui.dp(c, 36), Ui.dp(c, 44), Ui.dp(c, 30));
        card.setClickable(true);
        card.addView(Ui.text(c, title, 32, t.text, Ui.MEDIUM));
        if (subtitle != null && !subtitle.isEmpty()) {
            card.addView(Ui.text(c, subtitle, 18, t.sub, Ui.REGULAR), Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, Ui.dp(c, 8), 0, 0));
        }
        LinearLayout list = Ui.column(c);
        for (String it : items) {
            LinearLayout r = Ui.row(c);
            r.setGravity(Gravity.TOP);
            View dot = new View(c);
            dot.setBackground(Ui.round(t.text, Ui.dp(c, 4)));
            r.addView(dot, Ui.margins(new LinearLayout.LayoutParams(Ui.dp(c, 8), Ui.dp(c, 8)), 0, Ui.dp(c, 10), Ui.dp(c, 16), 0));
            TextView tv = Ui.text(c, it, 20, t.text, Ui.REGULAR);
            tv.setLineSpacing(0, 1.1f);
            r.addView(tv, Ui.lpWeight(0, -2, 1));
            list.addView(r, Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, Ui.dp(c, 12), 0, 0));
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        card.addView(sv, Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, Ui.dp(c, 12), 0, 0));
        LinearLayout btns = Ui.row(c);
        btns.setGravity(Gravity.END);
        btns.addView(button(c, t, ok, true, v -> {
            ((ViewGroup) dim.getParent()).removeView(dim);
            if (onClose != null) onClose.run();
        }), Ui.lp(-2, Ui.tp(c, 60)));
        card.addView(btns, Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, Ui.dp(c, 26), 0, 0));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(c, 1000), -2, Gravity.CENTER);
        lp.topMargin = Ui.dp(c, 30);
        lp.bottomMargin = Ui.dp(c, 30);
        dim.addView(card, lp);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
    }

    private static TextView button(Context c, Theme t, String text, boolean primary, View.OnClickListener l) {
        TextView b = Ui.text(c, text, Ui.ts(20), primary ? t.panel : t.text, Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.tp(c, 28), 0, Ui.tp(c, 28), 0);
        b.setBackground(Ui.pressable(c, primary ? t.text : t.chip, 16 * Ui.K, t));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }
}
