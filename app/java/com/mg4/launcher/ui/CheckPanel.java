package com.mg4.launcher.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mg4.launcher.L;
import com.mg4.launcher.R;
import com.mg4.launcher.svc.SelfCheck;

import java.util.List;

/**
 * 1.0.3 (punto 8): finestra del Controllo funzioni. Una riga per prova con ✓ / ✗ / — e una frase semplice; durante la
 * prova dei tasti al volante la riga chiede di premere avanti o indietro (10 s). Alla fine il riepilogo e "Invia log".
 */
public final class CheckPanel {
    private CheckPanel() {}

    public static void show(FrameLayout root, Theme t) {
        Context c = root.getContext();
        FrameLayout dim = new FrameLayout(c);
        dim.setBackgroundColor(t.day ? 0x66000000 : 0xB3000000);
        dim.setClickable(true);
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.round(t.panel, Ui.dp(c, 28)));
        card.setPadding(Ui.dp(c, 40), Ui.dp(c, 30), Ui.dp(c, 40), Ui.dp(c, 26));
        card.setClickable(true);
        LinearLayout head = Ui.row(c);
        head.addView(Ui.text(c, L.s(R.string.check_title), 30, t.text, Ui.MEDIUM), Ui.lpWeight(0, -2, 1));
        TextView summary = Ui.text(c, L.s(R.string.check_running), 18, t.sub, Ui.REGULAR);
        head.addView(summary);
        card.addView(head);

        List<SelfCheck.Item> items = SelfCheck.items();
        LinearLayout grid = Ui.column(c);
        TextView[] marks = new TextView[items.size()], subs = new TextView[items.size()];
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 2 == 0) {
                row = Ui.row(c);
                grid.addView(row, Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, i == 0 ? 0 : Ui.dp(c, 6), 0, 0));
            }
            LinearLayout cell = Ui.row(c);
            cell.setBackground(Ui.round(t.chip, Ui.dp(c, 14)));
            cell.setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10));
            TextView mk = Ui.text(c, "·", 24, t.sub, Ui.MEDIUM);
            mk.setGravity(Gravity.CENTER);
            cell.addView(mk, new LinearLayout.LayoutParams(Ui.dp(c, 34), -2));
            LinearLayout txt = Ui.column(c);
            txt.addView(Ui.oneLine(Ui.text(c, L.s(items.get(i).label), 18, t.text, Ui.MEDIUM)));
            TextView sub = Ui.oneLine(Ui.text(c, "", 15, t.sub, Ui.REGULAR));
            txt.addView(sub, Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, Ui.dp(c, 3), 0, 0));
            cell.addView(txt, Ui.margins(Ui.lpWeight(0, -2, 1), Ui.dp(c, 8), 0, 0, 0));
            marks[i] = mk;
            subs[i] = sub;
            row.addView(cell, Ui.margins(Ui.lpWeight(0, -2, 1), i % 2 == 0 ? 0 : Ui.dp(c, 8), 0, 0, 0));
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(grid);
        card.addView(sv, Ui.margins(new LinearLayout.LayoutParams(-1, Ui.dp(c, 470)), 0, Ui.dp(c, 18), 0, 0));

        LinearLayout btns = Ui.row(c);
        btns.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView send = button(c, t, L.s(R.string.check_send_log), false);
        send.setEnabled(false);
        send.setAlpha(0.4f);
        send.setOnClickListener(v -> {
            com.mg4.launcher.svc.LogUploader.requestNow();
            send.setText(L.s(R.string.check_log_sent));
            send.setEnabled(false);
        });
        btns.addView(send, Ui.lp(-2, Ui.tp(c, 60)));
        TextView close = button(c, t, L.s(R.string.close), true);
        close.setOnClickListener(v -> ((ViewGroup) dim.getParent()).removeView(dim));
        btns.addView(close, Ui.margins(Ui.lp(-2, Ui.tp(c, 60)), Ui.dp(c, 12), 0, 0, 0));
        card.addView(btns, Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, Ui.dp(c, 18), 0, 0));
        dim.addView(card, new FrameLayout.LayoutParams(Ui.dp(c, 1500), -2, Gravity.CENTER));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        int keysIdx = -1;
        for (int i = 0; i < items.size(); i++) if ("tastiVolante".equals(items.get(i).key)) keysIdx = i;
        final int ki = keysIdx;
        SelfCheck.Progress prog = new SelfCheck.Progress() {
            @Override
            public void onItem(SelfCheck.Item it) {
                for (int i = 0; i < items.size(); i++) {
                    if (!items.get(i).key.equals(it.key)) continue;
                    marks[i].setText(it.mark());
                    marks[i].setTextColor(it.status == SelfCheck.OK ? 0xFF2FBF71 : it.status == SelfCheck.FAIL ? 0xFFE5484D : t.sub);
                    String phrase = L.s(it.status == SelfCheck.OK ? R.string.check_ok : it.status == SelfCheck.FAIL ? R.string.check_fail
                            : it.status == SelfCheck.ABSENT ? R.string.check_absent : R.string.check_skipped);
                    subs[i].setText(it.shown.isEmpty() ? phrase : phrase + " · " + it.shown);
                }
            }

            @Override
            public void onKeysCountdown(int seconds) {
                if (ki < 0 || seconds <= 0) return;
                marks[ki].setText("…");
                subs[ki].setText(L.f(R.string.check_press_keys, String.valueOf(seconds)));
            }

            @Override
            public void onDone(List<SelfCheck.Item> all, int ok, int fail, int absent) {
                summary.setText(L.f(R.string.check_summary, String.valueOf(ok), String.valueOf(fail), String.valueOf(absent)));
                send.setEnabled(true);
                send.setAlpha(1f);
            }
        };
        // se il controllo automatico sta già girando si aspetta che finisca, poi parte quello con le righe a schermo
        Runnable start = new Runnable() {
            @Override
            public void run() {
                if (!dim.isAttachedToWindow() && dim.getParent() == null) return;
                if (SelfCheck.running()) dim.postDelayed(this, 1000);
                else SelfCheck.run(c, true, prog);
            }
        };
        dim.post(start);
    }

    private static TextView button(Context c, Theme t, String text, boolean primary) {
        TextView b = Ui.text(c, text, Ui.ts(19), primary ? t.panel : t.text, Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.tp(c, 28), 0, Ui.tp(c, 28), 0);
        b.setBackground(Ui.pressable(c, primary ? t.text : t.chip, 16 * Ui.K, t));
        b.setClickable(true);
        return b;
    }
}
