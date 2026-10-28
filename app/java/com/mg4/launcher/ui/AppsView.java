package com.mg4.launcher.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mg4.launcher.L;
import com.mg4.launcher.R;
import com.mg4.launcher.apps.AppCatalog;
import com.mg4.launcher.apps.IconStyler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Griglia di tutte le app: sezione "Auto" (icone disegnate) e "Installate da te" (icone ridisegnate in automatico). */
public final class AppsView extends LinearLayout {
    private static final int COLUMNS = 9;

    public interface Host {
        void closeApps();

        void openSettings();

        String navPackageHint();
    }

    private final Theme t;
    private final AppCatalog catalog;
    private final Host host;
    private final Prefs prefs;
    private final float k;   // scala della schermata App (dimensioneApp)
    private final LinearLayout content;
    private final TextView editBtn;
    private boolean editing;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AppsView(Context c, Theme t, AppCatalog catalog, Prefs prefs, Host host) {
        super(c);
        this.t = t;
        this.catalog = catalog;
        this.host = host;
        this.prefs = prefs;
        this.k = prefs.appsScaleFactor();
        setOrientation(VERTICAL);
        setBackgroundColor(t.bg);
        int pad = dp(20);
        setPadding(pad, pad, pad, pad);

        LinearLayout head = Ui.row(c);
        head.setPadding(dp(8), dp(4), dp(8), dp(4));
        // si torna alla home scorrendo verso destra (o con il tasto Home)
        head.addView(Ui.text(c, L.s(R.string.apps_title), 32, t.text, Ui.MEDIUM), Ui.margins(Ui.lp(-2, -2), dp(12), 0, 0, 0));
        head.addView(Ui.spacer(c));
        editBtn = Ui.text(c, L.s(R.string.edit), 19, t.text, Ui.MEDIUM);
        editBtn.setGravity(Gravity.CENTER);
        editBtn.setPadding(dp(26), 0, dp(26), 0);
        editBtn.setClickable(true);
        editBtn.setContentDescription(L.s(R.string.edit_desc));
        editBtn.setOnClickListener(v -> setEditing(!editing));
        head.addView(editBtn, Ui.margins(Ui.lp(-2, dp(60)), 0, 0, dp(12), 0));
        head.addView(Ui.iconButton(c, t, R.drawable.ic_gear, t.text, t.panel, 60, 28, 18, L.s(R.string.settings_desc),
                v -> host.openSettings()));
        addView(head);

        ScrollView sv = new ScrollView(c);
        sv.setBackground(Ui.round(t.panel, dp(24)));
        sv.setClipToOutline(true);
        sv.setVerticalScrollBarEnabled(false);
        content = Ui.column(c);
        content.setPadding(dp(32), dp(20), dp(32), dp(24));
        sv.addView(content, new FrameLayout.LayoutParams(-1, -2));
        addView(sv, Ui.margins(Ui.lpWeight(-1, 0, 1), 0, dp(16), 0, 0));
        setEditing(false);
    }

    private void setEditing(boolean on) {
        editing = on;
        editBtn.setText(L.s(on ? R.string.done : R.string.edit));
        editBtn.setTextColor(on ? t.onAccent : t.text);
        editBtn.setBackground(Ui.pressable(getContext(), on ? t.accent : t.panel, 30, t));
        reload();
    }

    private static String key(AppCatalog.App a) {
        return a.packageName + "/" + (a.component != null ? a.component.getClassName() : "");
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }

    /** Ricarica l'elenco (chiamato anche quando si installa o si rimuove un'app). */
    public void reload() {
        Context c = getContext();
        content.removeAllViews();
        Set<String> hidden = prefs.hiddenApps();
        String note = editing ? L.s(R.string.edit_note) : null;
        // 0.9.5: anche le app dell'auto si possono nascondere in Modifica (stesso elenco delle app nascoste)
        List<AppCatalog.App> car = new ArrayList<>();
        for (AppCatalog.App a : catalog.carApps(host.navPackageHint())) {
            if (editing || !hidden.contains(key(a))) car.add(a);
        }
        if (!car.isEmpty()) {
            content.addView(section(c, L.s(R.string.section_car), note));
            addGrid(c, car, hidden);
        }
        List<AppCatalog.App> all = catalog.userApps();
        List<AppCatalog.App> mine = new ArrayList<>();
        for (AppCatalog.App a : all) {
            if (editing || !hidden.contains(key(a))) mine.add(a);
        }
        content.addView(section(c, L.s(R.string.section_mine), note), Ui.margins(new LayoutParams(-1, -2), car.isEmpty() ? 0 : dp(10), 0, 0, 0));
        if (!mine.isEmpty()) addGrid(c, mine, hidden);
    }

    private View section(Context c, String title, String note) {
        LinearLayout r = Ui.row(c);
        r.setGravity(Gravity.BOTTOM);
        TextView tv = Ui.text(c, title, 15, t.sub, Ui.MEDIUM);
        tv.setLetterSpacing(0.07f);
        r.addView(tv);
        if (note != null) r.addView(Ui.text(c, note, 14, t.sub, Ui.REGULAR), Ui.margins(Ui.lp(-2, -2), dp(12), 0, 0, 0));
        r.setPadding(0, 0, 0, dp(6));
        return r;
    }

    /** editable: elenco delle app nascoste; in modalità Modifica ogni app (dell'auto o installata) si può nascondere. */
    private void addGrid(Context c, List<AppCatalog.App> apps, Set<String> editable) {
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLUMNS == 0) {
                row = Ui.row(c);
                content.addView(row, new LayoutParams(-1, dp(124 * k)));
            }
            row.addView(tile(c, apps.get(i), editable), Ui.lpWeight(0, -1, 1));
        }
        if (row != null) {
            for (int k = apps.size() % COLUMNS; k != 0 && k < COLUMNS; k++) row.addView(new View(c), Ui.lpWeight(0, -1, 1));
        }
    }

    private View tile(Context c, AppCatalog.App app, Set<String> editable) {
        LinearLayout col = Ui.column(c);
        col.setGravity(Gravity.CENTER);
        col.setBackground(Ui.pressable(c, 0, 20, t));
        col.setClickable(true);
        col.setContentDescription(app.label);
        boolean edit = editing && editable != null;
        boolean isHidden = edit && editable.contains(key(app));
        if (edit) {
            col.setOnClickListener(v -> {
                prefs.setAppHidden(key(app), !isHidden);
                reload();
            });
        } else if (editing) {
            col.setAlpha(0.3f);   // le app dell'auto restano sempre
            col.setClickable(false);
        } else {
            col.setOnClickListener(v -> {
                if (catalog.launch(app)) host.closeApps();
            });
        }

        FrameLayout box = new FrameLayout(c);
        int boxBg = t.chip;
        int fg = t.text;
        box.setBackground(Ui.round(boxBg, dp(22 * k)));
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (app.iconRes != 0) {
            iv.setImageResource(app.iconRes);
            iv.setColorFilter(fg);
            box.addView(iv, new FrameLayout.LayoutParams(dp(34 * k), dp(34 * k), Gravity.CENTER));
        } else {
            box.addView(iv, new FrameLayout.LayoutParams(dp(40 * k), dp(40 * k), Gravity.CENTER));
            // icona ridisegnata in background (può richiedere qualche millisecondo per app)
            new Thread(() -> {
                Drawable d = catalog.originalIcon(app);
                Bitmap b = IconStyler.style(app.packageName + "/" + app.label, d, fg);
                boolean glyph = IconStyler.isGlyph(b);
                main.post(() -> {
                    iv.setImageBitmap(b);
                    if (!glyph) {
                        box.setBackground(null);
                        iv.setLayoutParams(new FrameLayout.LayoutParams(dp(72 * k), dp(72 * k), Gravity.CENTER));
                    }
                });
            }, "mg4-icon").start();
        }
        if (edit) {
            // segno in alto a destra: ✓ visibile, × nascosta
            FrameLayout badge = new FrameLayout(c);
            badge.setBackground(Ui.round(isHidden ? t.track : t.accent, dp(14)));
            ImageView bi = Ui.icon(c, isHidden ? R.drawable.ic_close : R.drawable.ic_check, isHidden ? t.text : t.onAccent, 18);
            badge.addView(bi, new FrameLayout.LayoutParams(dp(18 * k), dp(18 * k), Gravity.CENTER));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(28 * k), dp(28 * k), Gravity.TOP | Gravity.END);
            FrameLayout wrap = new FrameLayout(c);
            wrap.addView(box, new FrameLayout.LayoutParams(dp(72 * k), dp(72 * k), Gravity.CENTER));
            wrap.addView(badge, blp);
            if (isHidden) box.setAlpha(0.35f);
            col.addView(wrap, new LayoutParams(dp(88 * k), dp(80 * k)));
        } else {
            col.addView(box, new LayoutParams(dp(72 * k), dp(72 * k)));
        }
        TextView label = Ui.oneLine(Ui.text(c, app.label, 16 * k, isHidden ? t.sub : t.text, Ui.MEDIUM));
        label.setGravity(Gravity.CENTER);
        col.addView(label, Ui.margins(new LayoutParams(-1, -2), dp(4), dp(10), dp(4), 0));
        return col;
    }
}
