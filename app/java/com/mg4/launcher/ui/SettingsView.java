package com.mg4.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mg4.launcher.L;
import com.mg4.launcher.R;
import com.mg4.launcher.svc.CarFeatures;
import com.mg4.launcher.svc.Diag;
import com.mg4.launcher.svc.LogUploader;
import com.mg4.launcher.svc.Updater;

/**
 * Impostazioni a schermo intero: menu delle sezioni a sinistra (a destra con la guida a destra), contenuto della
 * sezione dall'altra parte, versione e stato degli aggiornamenti sempre visibili in fondo al menu.
 * Interruttori e pulsanti seguono "Dimensione comandi" (Ui.K).
 */
public final class SettingsView extends FrameLayout {

    public interface Host {
        void closeSettings();

        void settingsChanged(boolean rebuild);

        void openOriginalHome();

        Updater updater();

        LogUploader logs();

        /** La lingua è cambiata: ricostruire tutta l'interfaccia. */
        void languageChanged();

        /** 1.0.0 (punto 8): elenco delle app installate, per scegliere cosa apre il tasto 360. */
        java.util.List<com.mg4.launcher.apps.AppCatalog.App> allLaunchableApps();
    }

    // 1.0.0 (punto 11): 7 sezioni nello stesso ordine su tutte le versioni - Auto, Home, Barra e pulsanti,
    // Radio e loghi, Aspetto, Avvio e app, Sistema. SEC_DRIVE è stato rinominato SEC_LAUNCH (ora "Avvio e app":
    // il lato guida si è spostato in Auto, i loghi radio si sono spostati qui sotto in SEC_LOGOS).
    public static final int SEC_CAR = 0, SEC_HOME = 1, SEC_BAR = 2, SEC_LOGOS = 3, SEC_LOOK = 4, SEC_LAUNCH = 5, SEC_SYSTEM = 6;

    /** Sezione da mostrare alla prossima apertura (es. Sistema dal popup degli aggiornamenti). */
    public static void openAt(int s) {
        section = s;
    }
    private static final int[] SEC_ICON = {R.drawable.ic_car, R.drawable.ic_home, R.drawable.ic_buttons, R.drawable.ic_radio, R.drawable.ic_palette, R.drawable.ic_play, R.drawable.ic_gear};
    private static final int[] SEC_NAME = {R.string.sec_car, R.string.sec_home, R.string.sec_bar, R.string.sec_logos, R.string.sec_look, R.string.sec_launch, R.string.sec_system};
    private static final int[] SEC_SUB = {R.string.sec_car_sub, R.string.sec_home_sub, R.string.sec_bar_sub, R.string.sec_logos_sub, R.string.sec_look_sub, R.string.sec_launch_sub, R.string.sec_system_sub};
    /** Spazio in alto a sinistra per la spia luci del sistema (come nella home). */
    private static final int SYSTEM_LIGHT_INSET = 64;
    private static final long QUIET_CHECK_MS = 30 * 60_000L;

    /** Sezione aperta: resta la stessa quando la schermata si ricostruisce (tema, lingua, dimensione…). */
    private static int section = SEC_CAR;
    /** Ultimo risultato del controllo aggiornamenti, condiviso tra le aperture. */
    private static Updater.Release lastRelease;
    private static long lastQuietCheck;
    private static String lastStatus;

    private final Theme t;
    private final Prefs prefs;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean rhd, sysIndicator;

    // 1.0.1: le voci del menu stanno in un ScrollView; l'altezza di ogni voce si adatta allo spazio (tra MIN e MAX),
    // il riquadro aggiornamenti (footer) resta fisso in basso, fuori dallo scorrimento.
    private static final int ITEM_MAX_DP = 76, ITEM_MIN_DP = 60, ITEM_GAP_DP = 6, ITEM_SUB_MIN_DP = 68;
    private final LinearLayout menuItems;
    private final ScrollView menuScroll;
    private int itemDp = ITEM_MAX_DP;
    private boolean revealPending = true;
    private final FrameLayout footer;
    private final LinearLayout content;
    private boolean diagOpen;

    // viste aggiornate di continuo
    private TextView diagText, logUploadSub, logSaveSub, updSub;
    private View logSaveBtn, updCheckBtn, updInstallBtn;

    public SettingsView(Context c, Theme t, Prefs prefs, Host host) {
        super(c);
        this.t = t;
        this.prefs = prefs;
        this.host = host;
        boolean carRhd = CarFeatures.isRightHandDrive(c);
        String side = prefs.driveSide();
        rhd = Prefs.DRIVE_RIGHT.equals(side) || (Prefs.DRIVE_AUTO.equals(side) && carRhd);
        sysIndicator = CarFeatures.hasSystemLightIndicator(c) || carRhd;

        setBackgroundColor(t.bg);
        setClickable(true);

        LinearLayout card = Ui.row(c);
        card.setGravity(Gravity.NO_GRAVITY);
        card.setBackground(Ui.round(t.panel, dp(28)));
        card.setClipToOutline(true);
        LayoutParams clp = new LayoutParams(-1, -1);
        clp.setMargins(dp(20), dp(20), dp(20), dp(20));
        addView(card, clp);

        // --- menu delle sezioni ---
        LinearLayout menu = Ui.column(c);
        menu.setBackgroundColor(t.day ? 0xFFF6F7F9 : 0xFF111317);
        menu.setPadding(dp(24), dp(24), dp(24), dp(20));
        TextView title = Ui.text(c, L.s(R.string.set_menu_title), 32, t.text, Ui.MEDIUM);
        title.setPadding(dp(12) + (sysIndicator && !rhd ? dp(SYSTEM_LIGHT_INSET) : 0), 0, dp(12), dp(18));
        menu.addView(title);
        menuItems = Ui.column(c);
        menuScroll = new ScrollView(c);
        menuScroll.setVerticalScrollBarEnabled(false);
        menuScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        menuScroll.addView(menuItems, new FrameLayout.LayoutParams(-1, -2));
        menu.addView(menuScroll, Ui.lpWeight(-1, 0, 1));
        // appena si conosce lo spazio, si adatta l'altezza delle voci e si mostra quella selezionata
        menuScroll.addOnLayoutChangeListener((v, left, topY, right, bottomY, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottomY - topY <= 0) return;
            fitMenu();
            if (revealPending) {
                revealPending = false;
                menuScroll.post(this::revealSelected);
            }
        });
        footer = new FrameLayout(c);
        menu.addView(footer, Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, dp(8), 0, 0));

        View sep = new View(c);
        sep.setBackgroundColor(t.track);

        // --- contenuto ---
        content = Ui.column(c);
        int insetLeft = sysIndicator && rhd ? dp(SYSTEM_LIGHT_INSET) : 0;
        content.setPadding(dp(44) + insetLeft, dp(34), dp(44), dp(30));

        if (!rhd) {
            card.addView(menu, new LinearLayout.LayoutParams(dp(440), -1));
            card.addView(sep, new LinearLayout.LayoutParams(dp(1), -1));
            card.addView(content, Ui.lpWeight(0, -1, 1));
        } else {
            card.addView(content, Ui.lpWeight(0, -1, 1));
            card.addView(sep, new LinearLayout.LayoutParams(dp(1), -1));
            card.addView(menu, new LinearLayout.LayoutParams(dp(440), -1));
        }

        Updater up = host.updater();
        up.setListener((text, r, busy) -> {
            if (r != null) lastRelease = r;
            lastStatus = text;
            refreshUpdateViews(busy);
        });
        if (SystemClock.elapsedRealtime() - lastQuietCheck > QUIET_CHECK_MS || lastQuietCheck == 0) {
            lastQuietCheck = SystemClock.elapsedRealtime();
            up.checkSilent(prefs.updateRepo(), prefs.githubToken(), r -> {
                if (r != null) lastRelease = r;
                refreshUpdateViews(up.isBusy());
            });
        }

        buildMenu();
        buildFooter(up.isBusy());
        showSection(section);
    }

    // =========================================================================================
    // Menu
    // =========================================================================================

    private void buildMenu() {
        Context c = getContext();
        menuItems.removeAllViews();
        for (int i = 0; i < SEC_NAME.length; i++) {
            final int idx = i;
            boolean on = i == section;
            FrameLayout item = new FrameLayout(c);
            item.setBackground(Ui.pressable(c, on ? t.chip : 0x00000000, 20, t));
            item.setClickable(true);
            item.setOnClickListener(v -> {
                if (section == idx && !diagOpen) return;
                section = idx;
                diagOpen = false;
                buildMenu();
                showSection(idx);
            });
            if (on) {
                View bar = new View(c);
                bar.setBackground(Ui.round(t.text, dp(3)));
                item.addView(bar, new LayoutParams(dp(5), dp(Math.max(28, itemDp - 32)), Gravity.CENTER_VERTICAL | (rhd ? Gravity.RIGHT : Gravity.LEFT)));
            }
            LinearLayout r = Ui.row(c);
            r.setPadding(dp(20), 0, dp(20), 0);
            // 1.0.1: voci più compatte (riquadro icona 44 dp, o 40 se lo spazio è poco)
            int boxDp = itemDp >= ITEM_SUB_MIN_DP ? 44 : 40;
            FrameLayout box = new FrameLayout(c);
            box.setBackground(Ui.round(on ? t.text : t.chip, dp(14)));
            box.addView(Ui.icon(c, SEC_ICON[i], on ? t.panel : t.text, 26), new LayoutParams(dp(26), dp(26), Gravity.CENTER));
            r.addView(box, Ui.lp(dp(boxDp), dp(boxDp)));
            LinearLayout tx = Ui.column(c);
            tx.addView(Ui.oneLine(Ui.text(c, L.s(SEC_NAME[i]), 23, t.text, on ? Ui.MEDIUM : Ui.REGULAR)));
            // il sottotitolo si nasconde quando lo spazio non basta
            if (itemDp >= ITEM_SUB_MIN_DP) {
                TextView sub = Ui.oneLine(Ui.text(c, L.s(SEC_SUB[i]), 15, t.sub, Ui.REGULAR));
                tx.addView(sub, Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(3), 0, 0));
            }
            r.addView(tx, Ui.margins(Ui.lpWeight(0, -2, 1), dp(16), 0, 0, 0));
            item.addView(r, new LayoutParams(-1, -1));
            item.setContentDescription(L.s(SEC_NAME[i]));
            menuItems.addView(item, Ui.margins(new LinearLayout.LayoutParams(-1, dp(itemDp)), 0, 0, 0, i == SEC_NAME.length - 1 ? 0 : dp(ITEM_GAP_DP)));
        }
    }

    /**
     * Adatta l'altezza delle voci allo spazio che resta sopra il riquadro aggiornamenti: se le 7 sezioni ci stanno
     * tutte si vedono senza scorrere; altrimenti restano ad altezza minima e il menu scorre.
     */
    private void fitMenu() {
        int avail = menuScroll.getHeight();
        int n = SEC_NAME.length;
        float perDp = (avail / (float) dp(1) - (n - 1) * ITEM_GAP_DP) / n;
        int want = Math.max(ITEM_MIN_DP, Math.min(ITEM_MAX_DP, (int) perDp));
        if (want == itemDp) return;
        itemDp = want;
        menuScroll.post(() -> {
            buildMenu();
            menuScroll.post(this::revealSelected);
        });
    }

    /** Fa scorrere il menu quanto basta perché la sezione selezionata sia tutta visibile. */
    private void revealSelected() {
        View it = menuItems.getChildAt(section);
        if (it == null) return;
        int vh = menuScroll.getHeight(), sy = menuScroll.getScrollY(), pad = dp(8);
        if (it.getTop() < sy) menuScroll.scrollTo(0, Math.max(0, it.getTop() - pad));
        else if (it.getBottom() > sy + vh) menuScroll.scrollTo(0, it.getBottom() - vh + pad);
    }

    /** Riquadro in fondo al menu: versione installata, oppure nuova versione con "Installa". */
    private void buildFooter(boolean busy) {
        Context c = getContext();
        footer.removeAllViews();
        Updater up = host.updater();
        Updater.Release r = lastRelease;
        boolean newer = r != null && r.newer && r.apkUrl != null;
        LinearLayout box = Ui.row(c);
        box.setPadding(dp(18), dp(14), dp(14), dp(14));
        box.setMinimumHeight(dp(70));
        if (newer) {
            box.setBackground(Ui.round(t.text, dp(20)));
            box.addView(Ui.icon(c, R.drawable.ic_download, t.panel, 28));
            LinearLayout tx = Ui.column(c);
            tx.addView(Ui.oneLine(Ui.text(c, L.f(R.string.upd_new_short, r.tag), 18, t.panel, Ui.MEDIUM)));
            String sub = busy && lastStatus != null ? lastStatus : L.f(R.string.upd_installed_ver, up.currentVersion());
            tx.addView(Ui.oneLine(Ui.text(c, sub, 14, t.panel, Ui.REGULAR)), Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(4), 0, 0));
            tx.getChildAt(1).setAlpha(0.75f);
            box.addView(tx, Ui.margins(Ui.lpWeight(0, -2, 1), dp(14), 0, dp(10), 0));
            TextView inst = Ui.text(c, L.s(R.string.btn_install), Ui.ts(18), t.text, Ui.MEDIUM);
            inst.setGravity(Gravity.CENTER);
            inst.setPadding(tp(20), 0, tp(20), 0);
            inst.setBackground(Ui.pressable(c, t.panel, 14 * Ui.K, t));
            inst.setClickable(!busy);
            inst.setAlpha(busy ? 0.5f : 1f);
            inst.setOnClickListener(v -> up.install(r, prefs.githubToken()));
            box.addView(inst, Ui.lp(-2, tp(52)));
        } else {
            box.setBackground(Ui.round(t.chip, dp(20)));
            boolean known = r != null;
            box.addView(Ui.icon(c, known ? R.drawable.ic_check : R.drawable.ic_info, known ? t.battery : t.sub, 26));
            LinearLayout tx = Ui.column(c);
            tx.addView(Ui.oneLine(Ui.text(c, "MG4 Launcher " + up.currentVersion(), 18, t.text, Ui.MEDIUM)));
            String sub = busy && lastStatus != null ? lastStatus : L.s(known ? R.string.upd_current : R.string.upd_unknown);
            tx.addView(Ui.oneLine(Ui.text(c, sub, 14, t.sub, Ui.REGULAR)), Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(4), 0, 0));
            box.addView(tx, Ui.margins(Ui.lpWeight(0, -2, 1), dp(14), 0, 0, 0));
        }
        footer.addView(box, new LayoutParams(-1, -2));
    }

    private void refreshUpdateViews(boolean busy) {
        buildFooter(busy);
        if (updSub != null) {
            Context c = getContext();
            updSub.setVisibility(VISIBLE);
            updSub.setText(lastStatus != null ? lastStatus
                    : lastRelease == null ? L.s(R.string.upd_unknown)
                    : lastRelease.newer ? L.f(R.string.upd_new, lastRelease.tag)
                    : L.s(R.string.upd_current));
            updCheckBtn.setEnabled(!busy);
            updCheckBtn.setAlpha(busy ? 0.5f : 1f);
            boolean canInstall = !busy && lastRelease != null && lastRelease.newer && lastRelease.apkUrl != null;
            updInstallBtn.setVisibility(canInstall ? VISIBLE : GONE);
        }
    }

    // =========================================================================================
    // Contenuto delle sezioni
    // =========================================================================================

    private void showSection(int s) {
        Context c = getContext();
        content.removeAllViews();
        diagText = null;
        logUploadSub = null;
        logSaveSub = null;
        updSub = null;

        // intestazione: titolo + chiudi
        LinearLayout head = Ui.row(c);
        if (diagOpen) {
            head.addView(Ui.iconButton(c, t, R.drawable.ic_chev_left, t.text, t.chip, 64, 30, 32, L.s(R.string.btn_back), v -> {
                diagOpen = false;
                showSection(section);
            }), Ui.margins(Ui.lp(tp(64), tp(64)), 0, 0, dp(20), 0));
        }
        head.addView(Ui.oneLine(Ui.text(c, L.s(diagOpen ? R.string.set_diag_title : SEC_NAME[s]), 34, t.text, Ui.MEDIUM)), Ui.lpWeight(0, -2, 1));
        head.addView(Ui.iconButton(c, t, R.drawable.ic_close, t.text, t.chip, 64, 30, 32, L.s(R.string.close), v -> host.closeSettings()));
        content.addView(head, new LinearLayout.LayoutParams(-1, -2));

        if (diagOpen) {
            buildDiag(c);
            refreshTick.run();
            return;
        }

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout col = Ui.column(c);
        col.setPadding(0, dp(26), 0, 0);
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        content.addView(sv, Ui.lpWeight(-1, 0, 1));

        switch (s) {
            case SEC_CAR: buildCar(c, col); break;
            case SEC_HOME: buildHome(c, col); break;
            case SEC_BAR: buildBar(c, col); break;
            case SEC_LOGOS: buildLogos(c, col); break;
            case SEC_LOOK: buildLook(c, col); break;
            case SEC_LAUNCH: buildLaunch(c, col); break;
            default: buildSystem(c, col); break;
        }
        refreshTick.run();
    }

    private void buildCar(Context c, LinearLayout col) {
        col.addView(label(c, L.s(R.string.set_car_color)));
        LinearLayout sw = Ui.row(c);
        int sel = prefs.carColorIndex();
        for (int i = 0; i < Prefs.CAR_COLORS.length; i++) {
            final int idx = i;
            sw.addView(swatch(c, Prefs.CAR_COLOR_VALUES[i], i == sel, Prefs.CAR_COLORS[i], v -> {
                prefs.setCarColorIndex(idx);
                host.settingsChanged(false);
                showSection(SEC_CAR);
            }), Ui.margins(Ui.lp(tp(72), tp(72)), 0, 0, dp(16), 0));
        }
        col.addView(sw, top(14));
        col.addView(Ui.text(c, Prefs.CAR_COLORS[sel], 18, t.sub, Ui.REGULAR), top(14));

        // cerchi (0.9.3): miniature come i colori, quelli della foto sono "di serie"
        col.addView(label(c, L.s(R.string.set_wheels).toUpperCase(java.util.Locale.getDefault())), top(30));
        LinearLayout wr = Ui.row(c);
        int wsel = prefs.wheelIndex();
        for (int i = 0; i < Prefs.WHEEL_RES.length; i++) {
            final int idx = i;
            wr.addView(wheelThumb(c, i, i == wsel, v -> {
                prefs.setWheelIndex(idx);
                host.settingsChanged(false);
                showSection(SEC_CAR);
            }), Ui.margins(Ui.lp(tp(92), tp(92)), 0, 0, dp(18), 0));
        }
        col.addView(wr, top(14));
        col.addView(Ui.text(c, L.s(Prefs.WHEEL_NAMES[wsel]), 18, t.sub, Ui.REGULAR), top(14));

        col.addView(label(c, L.s(R.string.set_bodywork)), top(30));
        LinearLayout card = card(c);
        card.addView(row(c, L.s(R.string.set_black_roof), L.s(R.string.set_black_roof_sub),
                toggle(c, prefs.blackRoof(), on -> {
                    prefs.setBlackRoof(on);
                    host.settingsChanged(false);
                    showSection(SEC_CAR);
                }), true));
        col.addView(card, topFull(14));

        // anteprima dell'auto con colore e tetto scelti
        CarView car = new CarView(c);
        car.setLook(Prefs.CAR_COLOR_VALUES[sel], prefs.blackRoof(), prefs.wheelIndex(), t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(520), dp(230));
        lp.gravity = rhd ? Gravity.LEFT : Gravity.RIGHT;
        lp.topMargin = dp(10);
        col.addView(car, lp);

        // 1.0.0 (punto 11): lato guida, spostato qui da "Avvio e app"
        col.addView(label(c, L.s(R.string.set_drive_side)), top(30));
        boolean carRight = CarFeatures.isRightHandDrive(c);
        String[][] sides = {
                {Prefs.DRIVE_AUTO, L.s(R.string.drive_auto) + " · " + L.s(carRight ? R.string.drive_right : R.string.drive_left)},
                {Prefs.DRIVE_LEFT, L.s(R.string.drive_left)},
                {Prefs.DRIVE_RIGHT, L.s(R.string.drive_right)}};
        Flow fd = new Flow(c);
        for (String[] m : sides) {
            fd.addView(choice(c, m[1], m[0].equals(prefs.driveSide()), v -> {
                if (m[0].equals(prefs.driveSide())) return;
                prefs.setDriveSide(m[0]);
                host.settingsChanged(true);   // ricostruisce la home (e le impostazioni) nella nuova disposizione
            }));
        }
        col.addView(fd, topFull(14));
        col.addView(Ui.text(c, L.s(R.string.drive_hint), 17, t.sub, Ui.REGULAR), top(14));
    }

    private void buildHome(Context c, LinearLayout col) {
        col.addView(label(c, L.s(R.string.set_home_card)));
        LinearLayout card = card(c);
        card.addView(row(c, L.s(R.string.set_show_date), L.s(R.string.set_show_date_sub),
                toggle(c, prefs.showDate(), on -> {
                    prefs.setShowDate(on);
                    host.settingsChanged(true);
                }), false));
        card.addView(row(c, L.s(R.string.set_soc_decimals), L.s(R.string.set_soc_decimals_sub),
                toggle(c, prefs.socDecimals(), on -> {
                    prefs.setSocDecimals(on);
                    host.settingsChanged(false);
                }), false));
        card.addView(row(c, L.s(R.string.set_soc_big), L.s(R.string.set_soc_big_sub),
                toggle(c, prefs.socBig(), on -> {
                    prefs.setSocBig(on);
                    host.settingsChanged(true);
                }), false));
        // 1.0.0 (punto 2): unità distanza (autonomia e distanze), prima del resto della scheda
        String[] distVals = {Prefs.DIST_AUTO, Prefs.DIST_KM, Prefs.DIST_MI};
        String[] distNames = {L.s(R.string.unit_auto), "km", "mi"};
        int distSel = java.util.Arrays.asList(distVals).indexOf(prefs.distUnit());
        card.addView(row(c, L.s(R.string.set_dist_unit), L.s(R.string.set_dist_unit_sub),
                choices(c, distNames, distSel, i -> {
                    prefs.setDistUnit(distVals[i]);
                    host.settingsChanged(true);
                    showSection(SEC_HOME);
                }), false));
        card.addView(row(c, L.s(R.string.set_tpms_title), L.s(R.string.set_tpms_sub),
                toggle(c, prefs.showTpms(), on -> {
                    prefs.setShowTpms(on);
                    host.settingsChanged(true);
                    showSection(SEC_HOME);
                }), false));
        if (prefs.showTpms()) {
            String[] units = {Prefs.TYRE_AUTO, Prefs.TYRE_BAR, Prefs.TYRE_KPA, Prefs.TYRE_PSI};
            String[] names = {L.s(R.string.unit_auto), "bar", "kPa", "psi"};
            int sel = java.util.Arrays.asList(units).indexOf(prefs.tyreUnit());
            card.addView(row(c, L.s(R.string.set_tyre_unit), L.s(R.string.set_tyre_unit_sub),
                    choices(c, names, sel, i -> {
                        prefs.setTyreUnit(units[i]);
                        host.settingsChanged(false);
                        showSection(SEC_HOME);
                    }), false));
        }
        // 1.0.2 (issue #8): temperatura interna come opzione; "--" se l'auto non la fornisce, mai l'esterna
        card.addView(row(c, L.s(R.string.set_show_inside_temp), L.s(R.string.set_inside_sub),
                toggle(c, prefs.showInsideTemp(), on -> {
                    prefs.setShowInsideTemp(on);
                    host.settingsChanged(true);
                }), false));
        // 1.0.2 (issue SWI69 #2): limite di ricarica Automatico (solo se l'auto lo supporta) / Mostra / Nascondi
        String[] limVals = {Prefs.LIMIT_AUTO, Prefs.LIMIT_SHOW, Prefs.LIMIT_HIDE};
        String[] limNames = {L.s(R.string.limit_auto), L.s(R.string.limit_show), L.s(R.string.limit_hide)};
        int limSel = java.util.Arrays.asList(limVals).indexOf(prefs.chargeLimitMode());
        card.addView(row(c, L.s(R.string.set_charge_limit), L.s(R.string.set_charge_limit_sub),
                choices(c, limNames, limSel, i -> {
                    prefs.setChargeLimitMode(limVals[i]);
                    host.settingsChanged(false);
                    showSection(SEC_HOME);
                }), true));
        col.addView(card, topFull(14));

        // 1.0.0 (punto 11): riquadro mappa/media/CarPlay/AA - il navigatore che si apre dal riquadro di casa
        col.addView(label(c, L.s(R.string.set_nav_default)), top(30));
        card = card(c);
        String navPref = prefs.navApp();
        String[] navVals = {Prefs.NAV_AUTO, Prefs.NAV_MG, Prefs.NAV_PHONE};
        String[] navNames = {L.s(R.string.unit_auto), L.s(R.string.nav_default_mg), L.s(R.string.nav_default_phone)};
        int navSel = java.util.Arrays.asList(navVals).indexOf(navPref);
        if (navSel < 0) navSel = 0;
        card.addView(row(c, L.s(R.string.set_nav_default), L.s(R.string.set_nav_default_sub),
                choices(c, navNames, navSel, i -> {
                    prefs.setNavApp(navVals[i]);
                    host.settingsChanged(false);
                    showSection(SEC_HOME);
                }), false));
        // 1.0.2 (issue #23): pulsante in alto a destra del riquadro: CarPlay/Android Auto (come prima), un'app, nessuno
        String top = prefs.topButton();
        boolean topApp = top.startsWith(Prefs.TOP_APP);
        String topAppLabel = topApp ? Cam360.label(c, top.substring(Prefs.TOP_APP.length())) : L.s(R.string.top_app);
        String[] topNames = {"CarPlay / Android Auto", topAppLabel, L.s(R.string.top_none)};
        int topSel = Prefs.TOP_NONE.equals(top) ? 2 : topApp ? 1 : 0;
        LinearLayout topRow = Ui.row(c);
        for (int i = 0; i < topNames.length; i++) {
            final int idx = i;
            boolean on = i == topSel;
            TextView ch = choice(c, topNames[i], on, v -> {
                if (idx == 1) { pickTopApp(c); return; }   // apre sempre l'elenco, anche se già scelto
                if (idx == topSel) return;
                prefs.setTopButton(idx == 0 ? Prefs.TOP_PROJECTION : Prefs.TOP_NONE);
                host.settingsChanged(true);
                showSection(SEC_HOME);
            });
            ch.setPadding(tp(20), 0, tp(20), 0);
            if (!on) ch.setBackground(Ui.pressable(c, t.panel, 16 * Ui.K, t));
            topRow.addView(ch, Ui.margins(new LinearLayout.LayoutParams(-2, tp(60)), i == 0 ? 0 : dp(8), 0, 0, 0));
        }
        card.addView(row(c, L.s(R.string.set_top_button), L.s(R.string.set_top_button_sub), topRow, true));
        col.addView(card, topFull(14));
    }

    /** Elenco delle app installate per il pulsante in alto a destra. */
    private void pickTopApp(Context c) {
        java.util.List<com.mg4.launcher.apps.AppCatalog.App> apps = host.allLaunchableApps();
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = apps.get(i).label;
        String cur = prefs.topButton();
        int sel = -1;
        if (cur.startsWith(Prefs.TOP_APP)) {
            String pkg = cur.substring(Prefs.TOP_APP.length());
            for (int i = 0; i < apps.size(); i++) if (apps.get(i).packageName.equals(pkg)) sel = i;
        }
        Confirm.pick(this, t, L.s(R.string.top_pick_app), "", names, sel, L.s(R.string.btn_cancel), i -> {
            prefs.setTopButton(Prefs.TOP_APP + apps.get(i).packageName);
            host.settingsChanged(true);
            showSection(SEC_HOME);
        });
    }

    private void buildBar(Context c, LinearLayout col) {
        col.addView(label(c, L.s(R.string.set_bar_card)));
        LinearLayout card = card(c);
        card.addView(row(c, L.s(R.string.set_big_bar), L.s(R.string.set_big_bar_sub),
                toggle(c, prefs.bigBar(), on -> {
                    prefs.setBigBar(on);
                    host.settingsChanged(true);
                }), false));
        card.addView(row(c, L.s(R.string.set_grouped), L.s(R.string.set_grouped_sub),
                toggle(c, prefs.groupedButtons(), on -> {
                    prefs.setGroupedButtons(on);
                    host.settingsChanged(true);
                }), false));
        card.addView(row(c, L.s(R.string.set_assist), L.s(R.string.set_assist_sub),
                toggle(c, prefs.showAssist(), on -> {
                    prefs.setShowAssist(on);
                    host.settingsChanged(true);
                }), false));
        card.addView(row(c, L.s(R.string.set_rear_fog), L.s(R.string.set_rear_fog_sub),
                toggle(c, prefs.showRearFog(), on -> {
                    prefs.setShowRearFog(on);
                    host.settingsChanged(true);
                }), false));
        card.addView(row(c, L.s(R.string.set_charge_buttons), L.s(R.string.set_charge_buttons_sub),
                toggle(c, prefs.chargeButtons(), on -> {
                    prefs.setChargeButtons(on);
                    host.settingsChanged(true);
                }), false));
        // 1.0.0 (punto 8): cosa apre il tasto 360
        String cam = prefs.cam360();
        boolean camApp = cam.startsWith(Prefs.CAM_APP);
        String appLabel = camApp ? com.mg4.launcher.ui.Cam360.label(c, cam.substring(Prefs.CAM_APP.length())) : L.s(R.string.cam360_app);
        String[] camNames = {L.s(R.string.cam360_original), L.s(R.string.cam360_mg4), appLabel, L.s(R.string.cam360_hidden)};
        int camSel = Prefs.CAM_ORIGINAL.equals(cam) ? 0 : Prefs.CAM_MG4.equals(cam) ? 1 : camApp ? 2 : 3;
        LinearLayout camRow = Ui.row(c);
        for (int i = 0; i < camNames.length; i++) {
            final int idx = i;
            boolean on = i == camSel;
            TextView ch = choice(c, camNames[i], on, v -> {
                if (idx == 2) { pickCam360App(c); return; }   // sempre apre l'elenco, anche se già selezionato
                if (idx == camSel) return;
                prefs.setCam360(idx == 0 ? Prefs.CAM_ORIGINAL : idx == 1 ? Prefs.CAM_MG4 : Prefs.CAM_HIDDEN);
                host.settingsChanged(false);
                showSection(SEC_BAR);
            });
            ch.setPadding(tp(20), 0, tp(20), 0);
            if (!on) ch.setBackground(Ui.pressable(c, t.panel, 16 * Ui.K, t));
            camRow.addView(ch, Ui.margins(new LinearLayout.LayoutParams(-2, tp(60)), i == 0 ? 0 : dp(8), 0, 0, 0));
        }
        card.addView(row(c, L.s(R.string.set_cam360), L.s(R.string.set_cam360_sub), camRow, true));
        col.addView(card, topFull(14));
    }

    /** Elenco delle app installate (escluso noi) per scegliere "un'altra app" per il tasto 360. */
    private void pickCam360App(Context c) {
        java.util.List<com.mg4.launcher.apps.AppCatalog.App> apps = host.allLaunchableApps();
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = apps.get(i).label;
        String cur = prefs.cam360();
        int sel = -1;
        if (cur.startsWith(Prefs.CAM_APP)) {
            String pkg = cur.substring(Prefs.CAM_APP.length());
            for (int i = 0; i < apps.size(); i++) if (apps.get(i).packageName.equals(pkg)) sel = i;
        }
        Confirm.pick(this, t, L.s(R.string.cam360_pick_app), "", names, sel, L.s(R.string.btn_cancel), i -> {
            prefs.setCam360(Prefs.CAM_APP + apps.get(i).packageName);
            host.settingsChanged(false);
            showSection(SEC_BAR);
        });
    }

    // ---- loghi radio -----------------------------------------------------------------------

    private final com.mg4.launcher.svc.LogoPacks.Listener logoListener = () -> {
        if (section == SEC_LOGOS && !diagOpen && isAttachedToWindow()) showSection(SEC_LOGOS);
    };

    private void buildLogos(Context c, LinearLayout col) {
        com.mg4.launcher.svc.LogoPacks lp = com.mg4.launcher.svc.LogoPacks.get(c);
        col.addView(label(c, L.s(R.string.set_logos_card)));
        LinearLayout card = card(c);
        java.util.List<String> chosen = lp.chosen();
        StringBuilder names = new StringBuilder();
        for (String cc : chosen) names.append(names.length() == 0 ? "" : ", ").append(com.mg4.launcher.svc.LogoPacks.countryName(cc));
        boolean auto = com.mg4.launcher.svc.LogoPacks.AUTO.equals(lp.choice());
        String sub = auto ? L.f(R.string.logos_auto_sub, names) : names.toString();
        card.addView(row(c, L.s(R.string.set_logos_country), sub, logoButtons(c, lp), false));

        // stato dei pacchetti dei paesi scelti (e di quelli installati da chiavetta)
        java.util.Map<String, com.mg4.launcher.svc.LogoPacks.Pack> inst = lp.installed();
        StringBuilder st = new StringBuilder();
        java.util.LinkedHashSet<String> show = new java.util.LinkedHashSet<>(chosen);
        show.addAll(inst.keySet());
        for (String cc : show) {
            com.mg4.launcher.svc.LogoPacks.Pack p = inst.get(cc);
            if (st.length() > 0) st.append('\n');
            st.append(com.mg4.launcher.svc.LogoPacks.countryName(cc)).append(": ");
            if (p == null) st.append(L.s(R.string.logos_not_downloaded));
            else st.append(L.f(R.string.logos_pack_state, p.version > 0 ? String.valueOf(p.version) : "–", p.logos,
                    String.format(L.locale(), "%.1f MB", p.size / 1048576f)));
        }
        String state = lp.status();
        if (lp.isBusy()) st.append('\n').append(L.s(R.string.logos_downloading));
        else if ("offline".equals(state)) st.append('\n').append(L.s(R.string.logos_offline));
        else if ("error".equals(state)) st.append('\n').append(L.s(R.string.logos_error));
        LinearLayout btns = Ui.row(c);
        TextView upd = button(c, L.s(R.string.btn_update), v -> lp.refresh(true));
        upd.setEnabled(!lp.isBusy());
        upd.setAlpha(lp.isBusy() ? 0.4f : 1f);
        btns.addView(upd);
        if (!inst.isEmpty()) {
            TextView del = button(c, L.s(R.string.btn_delete), v -> {
                String[] ccs = inst.keySet().toArray(new String[0]);
                if (ccs.length == 1) {
                    Confirm.show(this, t, L.s(R.string.logos_delete_title), L.f(R.string.logos_delete_msg,
                            com.mg4.launcher.svc.LogoPacks.countryName(ccs[0])), L.s(R.string.btn_cancel), L.s(R.string.btn_delete),
                            () -> lp.delete(ccs[0]));
                    return;
                }
                String[] nm = new String[ccs.length];
                for (int i = 0; i < ccs.length; i++) nm[i] = com.mg4.launcher.svc.LogoPacks.countryName(ccs[i]);
                Confirm.pick(this, t, L.s(R.string.logos_delete_title), L.s(R.string.logos_delete_pick), nm, -1,
                        L.s(R.string.btn_cancel), i -> lp.delete(ccs[i]));
            });
            btns.addView(del, Ui.margins(Ui.lp(-2, tp(60)), dp(12), 0, 0, 0));
        }
        card.addView(row(c, L.s(R.string.set_logos_state), st.toString(), btns, true));
        col.addView(card, topFull(14));

        // 1.0.0 (punto 11): cartella loghi personali - solo informativa, l'installazione è automatica
        col.addView(label(c, L.s(R.string.set_logos_usb)), top(30));
        LinearLayout uc = card(c);
        uc.addView(row(c, L.s(R.string.set_logos_usb), L.s(R.string.set_logos_usb_sub), spacer0(c), true));
        col.addView(uc, topFull(14));
    }

    /** "Cambia" (elenco dei paesi) e, dalla 0.9.6, "Codice" per scrivere a mano un paese qualsiasi (es. US, NZ). */
    private LinearLayout logoButtons(Context c, com.mg4.launcher.svc.LogoPacks lp) {
        LinearLayout b = Ui.row(c);
        b.addView(button(c, L.s(R.string.btn_change), v -> pickLogoCountries(lp)));
        b.addView(button(c, L.s(R.string.btn_logo_code), v -> {
            String cur = com.mg4.launcher.svc.LogoPacks.AUTO.equals(lp.choice()) ? "" : lp.choice();
            Confirm.input(this, t, L.s(R.string.logos_code_title), L.s(R.string.logos_code_msg), cur, "AU, NZ",
                    L.s(R.string.btn_cancel), L.s(R.string.btn_ok), text -> {
                        StringBuilder v2 = new StringBuilder();
                        for (String p : text.toUpperCase(java.util.Locale.ROOT).split("[^A-Z]+")) {
                            if (p.length() == 2 && v2.indexOf(p) < 0) v2.append(v2.length() == 0 ? "" : ",").append(p);
                        }
                        lp.setChoice(v2.length() == 0 ? com.mg4.launcher.svc.LogoPacks.AUTO : v2.toString());
                        showSection(SEC_LOGOS);
                    });
        }), Ui.margins(Ui.lp(-2, tp(60)), dp(12), 0, 0, 0));
        return b;
    }

    private void pickLogoCountries(com.mg4.launcher.svc.LogoPacks lp) {
        String[] codes = com.mg4.launcher.svc.LogoPacks.COUNTRIES;
        String[] opts = new String[codes.length + 1];
        boolean[] on = new boolean[opts.length];
        opts[0] = L.s(R.string.unit_auto);
        boolean auto = com.mg4.launcher.svc.LogoPacks.AUTO.equals(lp.choice());
        on[0] = auto;
        java.util.List<String> chosen = lp.chosen();
        // ordine alfabetico per nome nella lingua del launcher
        Integer[] order = new Integer[codes.length];
        for (int i = 0; i < codes.length; i++) order[i] = i;
        java.text.Collator col = java.text.Collator.getInstance(L.locale());
        java.util.Arrays.sort(order, (a, b) -> col.compare(com.mg4.launcher.svc.LogoPacks.countryName(codes[a]),
                com.mg4.launcher.svc.LogoPacks.countryName(codes[b])));
        for (int i = 0; i < codes.length; i++) {
            String cc = codes[order[i]];
            opts[i + 1] = com.mg4.launcher.svc.LogoPacks.countryName(cc);
            on[i + 1] = !auto && chosen.contains(cc);
        }
        Confirm.multi(this, t, L.s(R.string.set_logos_country), L.s(R.string.logos_pick_msg), opts, on,
                L.s(R.string.btn_cancel), L.s(R.string.btn_ok), idx -> {
                    StringBuilder v = new StringBuilder();
                    for (int i : idx) v.append(v.length() == 0 ? "" : ",").append(codes[order[i - 1]]);
                    lp.setChoice(v.length() == 0 ? com.mg4.launcher.svc.LogoPacks.AUTO : v.toString());
                    showSection(SEC_LOGOS);
                });
    }

    private interface OnChoice {
        void chosen(int index);
    }

    /** Piccole scelte in una riga della scheda (selezionata = colori invertiti, le altre su sfondo pannello). */
    private LinearLayout choices(Context c, String[] names, int sel, OnChoice l) {
        LinearLayout r = Ui.row(c);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView ch = choice(c, names[i], i == sel, v -> {
                if (idx != sel) l.chosen(idx);
            });
            ch.setPadding(tp(20), 0, tp(20), 0);
            if (i != sel) ch.setBackground(Ui.pressable(c, t.panel, 16 * Ui.K, t));
            r.addView(ch, Ui.margins(new LinearLayout.LayoutParams(-2, tp(60)), i == 0 ? 0 : dp(8), 0, 0, 0));
        }
        return r;
    }

    private void buildLook(Context c, LinearLayout col) {
        col.addView(label(c, L.s(R.string.set_theme)));
        String mode = prefs.themeMode();
        String[][] modes = {{Prefs.THEME_AUTO, L.s(R.string.theme_auto)}, {Prefs.THEME_NIGHT, L.s(R.string.theme_night)}, {Prefs.THEME_DAY, L.s(R.string.theme_day)}};
        Flow f = new Flow(c);
        for (String[] m : modes) {
            f.addView(choice(c, m[1], m[0].equals(mode), v -> {
                if (m[0].equals(prefs.themeMode())) return;
                prefs.setThemeMode(m[0]);
                host.settingsChanged(true);
            }));
        }
        col.addView(f, topFull(14));

        col.addView(label(c, L.s(R.string.set_apps_size)), top(28));
        int[] appNames = {R.string.ui_size_normal, R.string.ui_size_large, R.string.ui_size_xlarge};
        Flow fa = new Flow(c);
        for (int i = 0; i < appNames.length; i++) {
            final int idx = i;
            fa.addView(choice(c, L.s(appNames[i]), prefs.appsScale() == i, v -> {
                if (prefs.appsScale() == idx) return;
                prefs.setAppsScale(idx);
                host.settingsChanged(true);   // ricostruisce la schermata App con la nuova dimensione
            }));
        }
        col.addView(fa, topFull(14));

        col.addView(label(c, L.s(R.string.set_ui_size)), top(28));
        int[] names = {R.string.ui_size_normal, R.string.ui_size_large, R.string.ui_size_xlarge};
        f = new Flow(c);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            f.addView(choice(c, L.s(names[i]), prefs.uiScale() == i, v -> {
                if (prefs.uiScale() == idx) return;
                prefs.setUiScale(idx);
                host.settingsChanged(true);   // ricostruisce home e impostazioni con la nuova dimensione
            }));
        }
        col.addView(f, topFull(14));

        col.addView(label(c, L.s(R.string.set_language)), top(28));
        String cur = prefs.language();
        f = new Flow(c);
        f.addView(choice(c, L.s(R.string.lang_auto) + " · " + L.nameOf(L.resolve(L.AUTO)), L.AUTO.equals(cur), v -> setLang(L.AUTO)));
        for (int i = 0; i < L.CODES.length; i++) {
            final String code = L.CODES[i];
            f.addView(choice(c, L.NAMES[i], code.equals(cur), v -> setLang(code)));
        }
        col.addView(f, topFull(14));
    }

    private void setLang(String code) {
        if (code.equals(prefs.language())) return;
        prefs.setLanguage(code);
        host.languageChanged();   // riapplica la lingua e ricostruisce l'interfaccia
    }

    private static int officinaDays = 3;

    /** 1.0.0 (punto 11): "Avvio e app" - avvio sicuro, premi Home da solo, altri launcher, app originali, dongle. */
    private void buildLaunch(Context c, LinearLayout col) {
        // home predefinita e modalità officina
        Context app = c.getApplicationContext();
        boolean officina = com.mg4.launcher.svc.HomeMode.officina(app);
        col.addView(label(c, L.s(R.string.set_start_section)));
        LinearLayout hc = card(c);
        String homeSub = officina ? L.f(R.string.officina_until_sub, com.mg4.launcher.svc.HomeMode.officinaDate(app))
                : L.s(com.mg4.launcher.svc.HomeMode.enabled(app) ? R.string.home_default_sub : R.string.home_default_off_sub);
        hc.addView(row(c, L.s(R.string.home_default_title), homeSub, toggle(c, com.mg4.launcher.svc.HomeMode.enabled(app), on -> {
            if (on) {
                com.mg4.launcher.svc.HomeMode.setEnabled(app, true);
                showSection(SEC_LAUNCH);
            } else {
                Confirm.show(this, t, L.s(R.string.confirm_home_off_title), L.s(R.string.confirm_home_off_msg),
                        L.s(R.string.btn_cancel), L.s(R.string.btn_turn_off), () -> com.mg4.launcher.svc.HomeMode.setEnabled(app, false));
                showSection(SEC_LAUNCH);   // finché non si conferma l'interruttore resta acceso
            }
        }), false));
        // 0.9.6: avvio sicuro (la schermata della home MG si disattiva, come fa MG4Dash) e avviso altri launcher
        if (com.mg4.launcher.svc.HomeLock.mgExists(app)) {
            String safeSub = com.mg4.launcher.svc.CrashGuard.tripped(app) ? L.s(R.string.set_safe_start_tripped)
                    : L.s(R.string.set_safe_start_sub);
            hc.addView(row(c, L.s(R.string.set_safe_start), safeSub, toggle(c, com.mg4.launcher.svc.HomeLock.option(app), on -> {
                if (on) com.mg4.launcher.svc.CrashGuard.reset(app);
                com.mg4.launcher.svc.HomeLock.setOption(app, on);
                postDelayed(() -> showSection(SEC_LAUNCH), 400);
            }), false));
        }
        java.util.List<String> others = com.mg4.launcher.svc.HomeLock.otherLaunchers(app);
        if (!others.isEmpty()) {
            TextView warn = Ui.text(c, "⚠", 26, 0xFFFFB020, Ui.MEDIUM);
            hc.addView(row(c, L.s(R.string.set_other_launchers), L.f(R.string.set_other_launchers_sub, android.text.TextUtils.join(", ", others)),
                    warn, false));
        }
        LinearLayout oc = Ui.row(c);
        if (officina) {
            oc.addView(button(c, L.s(R.string.btn_deactivate), v -> {
                com.mg4.launcher.svc.HomeMode.end(app, "annullata");
                showSection(SEC_LAUNCH);
            }));
        } else {
            int[] days = {1, 3, 7};
            int[] names = {R.string.days_1, R.string.days_3, R.string.days_7};
            for (int i = 0; i < 3; i++) {
                final int d = days[i];
                TextView ch = choice(c, L.s(names[i]), officinaDays == d, v -> {
                    officinaDays = d;
                    showSection(SEC_LAUNCH);
                });
                // sulla scheda (sfondo chip) le scelte non selezionate hanno lo sfondo del pannello
                if (officinaDays != d) ch.setBackground(Ui.pressable(c, t.panel, 16 * Ui.K, t));
                oc.addView(ch, Ui.margins(new LinearLayout.LayoutParams(-2, tp(60)), 0, 0, dp(10), 0));
            }
            TextView act = button(c, L.s(R.string.btn_activate), v -> {
                String until = new java.text.SimpleDateFormat("dd/MM", java.util.Locale.ITALY)
                        .format(new java.util.Date(System.currentTimeMillis() + officinaDays * 24L * 3600_000L));
                Confirm.show(this, t, L.s(R.string.confirm_officina_title), L.f(R.string.confirm_officina_msg, until),
                        L.s(R.string.btn_cancel), L.s(R.string.btn_activate), () -> com.mg4.launcher.svc.HomeMode.startOfficina(app, officinaDays));
            });
            act.setTextColor(t.panel);
            act.setBackground(Ui.pressable(c, t.text, 16 * Ui.K, t));
            oc.addView(act, Ui.margins(new LinearLayout.LayoutParams(-2, tp(60)), dp(6), 0, 0, 0));
        }
        hc.addView(row(c, L.s(R.string.officina_title), officina
                ? L.f(R.string.officina_until_sub, com.mg4.launcher.svc.HomeMode.officinaDate(app)) : L.s(R.string.officina_sub), oc, false));
        // premi Home da solo dopo l'accensione (servizio di accessibilità)
        int[] secs = Prefs.AUTO_HOME_SECONDS;
        String[] secNames = new String[secs.length];
        int secSel = 0;
        for (int i = 0; i < secs.length; i++) {
            secNames[i] = secs[i] == 0 ? L.s(R.string.off) : secs[i] + " s";
            if (secs[i] == prefs.autoHomeSeconds()) secSel = i;
        }
        LinearLayout ah = Ui.column(c);
        ah.setPadding(dp(28), dp(18), dp(28), dp(20));
        ah.addView(Ui.text(c, L.s(R.string.set_auto_home), 24, t.text, Ui.REGULAR));
        ah.addView(Ui.text(c, L.s(R.string.set_auto_home_sub), 17, t.sub, Ui.REGULAR), Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(6), 0, 0));
        ah.addView(choices(c, secNames, secSel, i -> {
            prefs.setAutoHomeSeconds(secs[i]);
            showSection(SEC_LAUNCH);
        }), Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(14), 0, 0));
        hc.addView(ah);
        col.addView(hc, topFull(14));

        // app originali (apri la home MG per verifiche/officina)
        col.addView(label(c, L.s(R.string.set_original_label)), top(30));
        LinearLayout oac = card(c);
        oac.addView(row(c, L.s(R.string.set_open_original), L.s(R.string.set_original_sub),
                button(c, L.s(R.string.btn_open), v -> host.openOriginalHome()), true));
        col.addView(oac, topFull(14));

        // 1.0.2 (punto 5): dopo la retromarcia si torna all'app di prima (CarPlay, Android Auto, navigatore...)
        col.addView(label(c, L.s(R.string.set_retro_label)), top(30));
        LinearLayout rc = card(c);
        rc.addView(row(c, L.s(R.string.set_retro_return), L.s(R.string.set_retro_return_sub),
                toggle(c, prefs.retroReturn(), on -> prefs.setRetroReturn(on)), true));
        col.addView(rc, topFull(14));

        // CarPlay / Android Auto: riavvio del dongle wireless che non si collega
        com.mg4.launcher.svc.DongleWatch dw = com.mg4.launcher.svc.DongleWatch.peek();
        if (dw != null) {
            col.addView(label(c, L.s(R.string.set_projection_card)), top(30));
            LinearLayout pc = card(c);
            pc.addView(row(c, L.s(R.string.set_dongle), L.s(R.string.set_dongle_sub),
                    toggle(c, dw.enabled(), on -> {
                        dw.setEnabled(on);
                        showSection(SEC_LAUNCH);
                    }), false));
            if (dw.enabled()) {
                int[] ds = {15, 20, 30, 45};
                String[] dn = new String[ds.length];
                int dsel = 1;
                for (int i = 0; i < ds.length; i++) {
                    dn[i] = ds[i] + " s";
                    if (ds[i] == dw.delaySeconds()) dsel = i;
                }
                pc.addView(row(c, L.s(R.string.set_dongle_delay), L.s(R.string.set_dongle_delay_sub), choices(c, dn, dsel, i -> {
                    dw.setDelaySeconds(ds[i]);
                    showSection(SEC_LAUNCH);
                }), false));
            }
            pc.addView(row(c, L.s(R.string.set_dongle_now), L.s(R.string.set_dongle_now_sub), button(c, L.s(R.string.btn_restart), v -> {
                if (dw.inCall()) {
                    Confirm.notice(this, t, L.s(R.string.set_dongle_now), "", new String[]{L.s(R.string.dongle_in_call)}, L.s(R.string.btn_ok), null);
                    return;
                }
                Confirm.show(this, t, L.s(R.string.set_dongle_now), L.s(R.string.dongle_confirm), L.s(R.string.btn_cancel),
                        L.s(R.string.btn_restart), () -> dw.restartNow(null));
            }), true));
            col.addView(pc, topFull(14));
        }
    }

    private void buildSystem(Context c, LinearLayout col) {
        Updater up = host.updater();
        col.addView(label(c, L.s(R.string.set_updates)));
        LinearLayout card = card(c);
        LinearLayout btns = Ui.row(c);
        updInstallBtn = button(c, L.s(R.string.btn_install), v -> {
            if (lastRelease != null) up.install(lastRelease, prefs.githubToken());
        });
        ((TextView) updInstallBtn).setTextColor(t.panel);
        updInstallBtn.setBackground(Ui.pressable(c, t.text, 16 * Ui.K, t));
        btns.addView(updInstallBtn, Ui.margins(Ui.lp(-2, tp(60)), 0, 0, dp(12), 0));
        updCheckBtn = button(c, L.s(R.string.btn_check), v -> up.check(prefs.updateRepo(), prefs.githubToken()));
        btns.addView(updCheckBtn, Ui.lp(-2, tp(60)));
        LinearLayout ur = row(c, "MG4 Launcher " + up.currentVersion(), "", btns, true);
        updSub = (TextView) ((ViewGroup) ur.getChildAt(0)).getChildAt(1);
        card.addView(ur);
        col.addView(card, topFull(14));

        col.addView(label(c, L.s(R.string.set_diag_section)), top(30));
        card = card(c);
        LinearLayout lr = row(c, L.s(R.string.log_upload_on), "", toggle(c, host.logs().enabled(), on -> host.logs().setEnabled(on)), false);
        if (!LogUploader.configured()) lr.getChildAt(1).setEnabled(false);
        logUploadSub = (TextView) ((ViewGroup) lr.getChildAt(0)).getChildAt(1);
        card.addView(lr);
        logSaveBtn = button(c, L.s(R.string.btn_save), v -> saveLog());
        LinearLayout sr = row(c, L.s(R.string.save_log), L.s(R.string.save_log_sub), logSaveBtn, false);
        logSaveSub = (TextView) ((ViewGroup) sr.getChildAt(0)).getChildAt(1);
        card.addView(sr);
        LinearLayout dr = row(c, L.s(R.string.set_diag_title), L.s(R.string.set_diag_sub),
                Ui.icon(c, R.drawable.ic_chev_right, t.sub, Math.round(30 * Ui.K)), true);
        dr.setBackground(Ui.pressable(c, 0x00000000, 0, t));
        dr.setClickable(true);
        dr.setOnClickListener(v -> {
            diagOpen = true;
            showSection(SEC_SYSTEM);
        });
        card.addView(dr);
        col.addView(card, topFull(14));

        // 0.8.6-beta: disinstallazione dall'auto (rimette la home MG e toglie il launcher)
        card = card(c);
        View unBtn = button(c, L.s(R.string.btn_uninstall), v -> askUninstall());
        card.addView(row(c, L.s(R.string.uninstall_title), L.s(R.string.uninstall_sub), unBtn, false));
        col.addView(card, topFull(14));

        refreshUpdateViews(up.isBusy());
    }

    private void buildDiag(Context c) {
        ScrollView ds = new ScrollView(c);
        ds.setBackground(Ui.round(t.chip, dp(22)));
        diagText = Ui.text(c, "", 14, t.sub, Typeface.MONOSPACE);
        diagText.setPadding(dp(22), dp(18), dp(22), dp(18));
        diagText.setTextIsSelectable(true);
        diagText.setLineSpacing(0, 1.15f);
        ds.addView(diagText);
        content.addView(ds, Ui.margins(Ui.lpWeight(-1, 0, 1), 0, dp(24), 0, 0));
    }

    /** Salva il log completo (in background) e mostra dove è finito. */
    /** Conferma e disinstallazione del launcher; se non riesce lo si dice. */
    private void askUninstall() {
        Confirm.show(this, t, L.s(R.string.uninstall_title), L.s(R.string.uninstall_confirm),
                L.s(R.string.btn_cancel), L.s(R.string.btn_uninstall),
                () -> com.mg4.launcher.svc.SelfUninstall.run(getContext(), why -> post(() ->
                        Confirm.show(this, t, L.s(R.string.uninstall_title), L.f(R.string.uninstall_failed, why),
                                L.s(R.string.btn_cancel), "OK", () -> { }))));
    }

    private void saveLog() {
        logSaveBtn.setEnabled(false);
        logSaveBtn.setAlpha(0.5f);
        if (logSaveSub != null) logSaveSub.setText(L.s(R.string.log_saving));
        Context app = getContext().getApplicationContext();
        new Thread(() -> {
            Diag.Saved r = Diag.saveLog(app);
            main.post(() -> {
                if (logSaveBtn != null) {
                    logSaveBtn.setEnabled(true);
                    logSaveBtn.setAlpha(1f);
                }
                if (logSaveSub == null) return;
                if (r.paths.isEmpty()) {
                    logSaveSub.setText(L.s(R.string.log_failed));
                    return;
                }
                StringBuilder sb = new StringBuilder(L.s(r.onUsb ? R.string.log_saved_usb : R.string.log_saved)).append(' ');
                sb.append(r.paths.get(0));
                if (!r.onUsb) sb.append('\n').append(L.s(R.string.log_usb_hint));
                logSaveSub.setSingleLine(false);
                logSaveSub.setText(sb.toString());
            });
        }, "mg4-log").start();
    }

    // =========================================================================================
    // Elementi grafici
    // =========================================================================================

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }

    private int tp(float v) {
        return Ui.tp(getContext(), v);
    }

    private LinearLayout.LayoutParams top(int m) {
        return Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(m), 0, 0);
    }

    private LinearLayout.LayoutParams topFull(int m) {
        return Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, dp(m), 0, 0);
    }

    private TextView label(Context c, String s) {
        TextView tv = Ui.text(c, s.toUpperCase(L.locale()), 15, t.sub, Ui.MEDIUM);
        tv.setLetterSpacing(0.08f);
        return tv;
    }

    private LinearLayout card(Context c) {
        LinearLayout l = Ui.column(c);
        l.setBackground(Ui.round(t.chip, dp(22)));
        l.setClipToOutline(true);
        return l;
    }

    /** Riga grande: titolo + spiegazione a sinistra, comando a destra; separatore sotto se non è l'ultima. */
    /** Vista vuota a larghezza zero, per le righe solo informative (senza misure prendeva tutta la riga). */
    private static View spacer0(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 0));
        return v;
    }

    private LinearLayout row(Context c, String title, String sub, View right, boolean last) {
        LinearLayout r = Ui.row(c);
        r.setMinimumHeight(tp(96));
        r.setPadding(dp(28), dp(14), dp(28), dp(14));
        if (!last) {
            GradientDrawable line = new GradientDrawable();
            line.setColor(t.chip);
            r.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                    new android.graphics.drawable.ColorDrawable(t.track), line}) {{
                setLayerInset(1, 0, 0, 0, dp(1));
            }});
        }
        LinearLayout tx = Ui.column(c);
        tx.addView(Ui.text(c, title, 24, t.text, Ui.REGULAR));
        TextView st = Ui.text(c, sub, 17, t.sub, Ui.REGULAR);
        tx.addView(st, Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(6), 0, 0));
        if (sub.isEmpty()) st.setVisibility(GONE);
        r.addView(tx, Ui.margins(Ui.lpWeight(0, -2, 1), 0, 0, dp(20), 0));
        r.addView(right);
        return r;
    }

    /** Pulsante secondario di una riga (sfondo pannello sulla scheda). */
    private TextView button(Context c, String text, View.OnClickListener l) {
        TextView b = Ui.text(c, text, Ui.ts(20), t.text, Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(tp(26), 0, tp(26), 0);
        b.setMinHeight(tp(60));
        b.setBackground(Ui.pressable(c, t.panel, 16 * Ui.K, t));
        b.setClickable(true);
        b.setOnClickListener(l);
        b.setLayoutParams(Ui.lp(-2, tp(60)));
        return b;
    }

    /** Scelta a pulsanti (tema, dimensione, lingua, guida): selezionata = colori invertiti. */
    private TextView choice(Context c, String text, boolean on, View.OnClickListener l) {
        TextView b = Ui.text(c, text, Ui.ts(20), on ? t.panel : t.text, Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(tp(26), 0, tp(26), 0);
        b.setSingleLine(true);
        b.setBackground(on ? Ui.round(t.text, tp(16)) : Ui.pressable(c, t.chip, 16 * Ui.K, t));
        b.setClickable(true);
        b.setOnClickListener(l);
        b.setLayoutParams(new ViewGroup.LayoutParams(-2, tp(60)));
        return b;
    }

    /** Miniatura di un cerchio: cerchio con la gomma intorno, bordo come i colori se è quello scelto. */
    private View wheelThumb(Context c, int idx, boolean selected, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF1B1B1C);   // gomma
        g.setStroke(dp(selected ? 4 : 1), selected ? t.text : t.track);
        f.setBackground(g);
        android.widget.ImageView iv = new android.widget.ImageView(c);
        iv.setImageResource(Prefs.WHEEL_RES[idx]);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = tp(13);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        ((FrameLayout.LayoutParams) iv.getLayoutParams()).setMargins(pad, pad, pad, pad);
        f.setContentDescription(L.s(Prefs.WHEEL_NAMES[idx]));
        f.setClickable(true);
        f.setOnClickListener(l);
        return f;
    }

    private View swatch(Context c, int color, boolean selected, String desc, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(selected ? 4 : 1), selected ? t.text : t.track);
        f.setBackground(g);
        f.setContentDescription(desc);
        f.setClickable(true);
        f.setOnClickListener(l);
        return f;
    }

    private interface OnToggle {
        void changed(boolean on);
    }

    private Toggle toggle(Context c, boolean on, OnToggle l) {
        Toggle tg = new Toggle(c, on, l);
        tg.setLayoutParams(Ui.lp(tp(76), tp(44)));
        return tg;
    }

    /** Interruttore disegnato con i colori del tema (acceso = colore testo, spento = binario). */
    private final class Toggle extends View {
        private boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Toggle(Context c, boolean on, OnToggle l) {
            super(c);
            this.on = on;
            setClickable(true);
            setOnClickListener(v -> {
                this.on = !this.on;
                invalidate();
                l.changed(this.on);
            });
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.4f);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), pad = h * 4f / 44f, d = h - 2 * pad;
            r.set(0, 0, w, h);
            p.setColor(on ? t.text : t.track);
            cv.drawRoundRect(r, h / 2, h / 2, p);
            p.setColor(on ? t.panel : t.sub);
            float cx = on ? w - pad - d / 2 : pad + d / 2;
            cv.drawCircle(cx, h / 2, d / 2, p);
        }
    }

    /** Riga di pulsanti che va a capo quando non c'è spazio (lingue, guida a dimensioni grandi). */
    private final class Flow extends ViewGroup {
        private final int gap = dp(12);

        Flow(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int max = MeasureSpec.getSize(ws);
            int x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                v.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(v.getLayoutParams().height, MeasureSpec.EXACTLY));
                if (x > 0 && x + v.getMeasuredWidth() > max) {
                    x = 0;
                    y += lineH + gap;
                    lineH = 0;
                }
                x += v.getMeasuredWidth() + gap;
                lineH = Math.max(lineH, v.getMeasuredHeight());
            }
            setMeasuredDimension(max, y + lineH);
        }

        @Override
        protected void onLayout(boolean changed, int l, int tt, int rr, int b) {
            int max = rr - l, x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
                if (x > 0 && x + w > max) {
                    x = 0;
                    y += lineH + gap;
                    lineH = 0;
                }
                int left = rhd ? max - x - w : x;   // con la guida a destra le scelte partono da destra
                v.layout(left, y, left + w, y + h);
                x += w + gap;
                lineH = Math.max(lineH, h);
            }
        }
    }

    // =========================================================================================
    // Aggiornamento continuo (stato log e diagnostica)
    // =========================================================================================

    private String logUploadText() {
        Context c = getContext();
        if (!LogUploader.configured()) return L.s(R.string.log_upload_nokey);
        if (!host.logs().enabled()) return L.s(R.string.log_upload_off);
        String st = LogUploader.status();
        if (st.startsWith("ok ")) return L.f(R.string.log_upload_last, st.substring(3));
        if (st.startsWith("errore ")) return L.f(R.string.log_upload_error, st.substring(7));
        return L.s(R.string.log_upload_never);
    }

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            main.removeCallbacks(this);
            if (diagText == null && logUploadSub == null) return;
            if (diagText != null) {
                String d = Diag.dump();
                diagText.setText(d.isEmpty() ? L.s(R.string.diag_waiting) : d);
            }
            if (logUploadSub != null) {
                logUploadSub.setVisibility(VISIBLE);
                logUploadSub.setText(logUploadText());
            }
            if (isAttachedToWindow()) main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshTick.run();
        com.mg4.launcher.svc.LogoPacks.get(getContext()).addListener(logoListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        main.removeCallbacks(refreshTick);
        host.updater().setListener(null);
        com.mg4.launcher.svc.LogoPacks.get(getContext()).removeListener(logoListener);
    }
}
