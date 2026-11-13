package com.mg4.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mg4.launcher.apps.AppCatalog;
import com.mg4.launcher.apps.IconStyler;
import com.mg4.launcher.svc.Diag;
import com.mg4.launcher.svc.MediaClient;
import com.mg4.launcher.svc.NavClient;
import com.mg4.launcher.svc.Updater;
import com.mg4.launcher.svc.VehicleClient;
import com.mg4.launcher.ui.AppsView;
import com.mg4.launcher.ui.HomeView;
import com.mg4.launcher.ui.Pager;
import com.mg4.launcher.ui.Prefs;
import com.mg4.launcher.ui.SettingsView;
import com.mg4.launcher.ui.Theme;
import com.mg4.launcher.ui.Ui;

/** Home del launcher: costruisce l'interfaccia e collega i servizi dell'auto. */
public final class MainActivity extends Activity implements HomeView.Host, AppsView.Host, SettingsView.Host {

    /** Schermo intero: nasconde barra di stato e dock MG (la SystemUI SAIC segue i flag standard). */
    private static final int IMMERSIVE = View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;

    private Prefs prefs;
    private AppCatalog catalog;
    private VehicleClient vehicle;
    private MediaClient media;
    private NavClient nav;
    private Updater updater;
    private com.mg4.launcher.svc.LogUploader logs;

    private FrameLayout root;
    private Theme theme;
    private HomeView home;
    private AppsView apps;
    private Pager pager;
    private com.mg4.launcher.svc.ConnClient conn;
    private SettingsView settings;
    private int lastUiMode;

    /** Versione per SWI68 su un altro firmware: solo la schermata di avviso, nessun collegamento ai servizi. */
    private boolean wrongFirmware;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        L.init(this, prefs.language());
        // schermo intero dal primo disegno: niente ridimensionamento della finestra all'avvio
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE);
        if (com.mg4.launcher.svc.Firmware.wrong(this)) {
            wrongFirmware = true;
            logs = new com.mg4.launcher.svc.LogUploader(this);
            root = new FrameLayout(this);
            setContentView(root);
            showWrongFirmware();
            logs.start();
            root.postDelayed(saveDiag, 15000);
            return;
        }
        catalog = new AppCatalog(this);
        vehicle = new VehicleClient(this);
        conn = new com.mg4.launcher.svc.ConnClient(this);
        conn.setListener(st -> {
            if (home != null) home.onConn(st);
        });
        media = new MediaClient(this);
        nav = new NavClient(this);
        updater = new Updater(this);
        logs = new com.mg4.launcher.svc.LogUploader(this);
        // 1.0.3: Controllo funzioni (sola lettura) e controllo automatico 60 s dopo l'avvio di una versione nuova
        com.mg4.launcher.svc.SelfCheck.bind(vehicle, media, logs);
        com.mg4.launcher.svc.SelfCheck.scheduleAfterUpdate(this, updater.currentVersion());
        // 1.0.4 (punto F): pacchetto dell'animazione di ricarica del colore scelto (in background, se attiva)
        android.os.Handler later = new android.os.Handler(android.os.Looper.getMainLooper());
        later.postDelayed(() -> {
            if (!isDestroyed() && prefs.chargeAnim())
                new Thread(() -> com.mg4.launcher.svc.ChargePack.ensure(this, Prefs.CAR_COLORS[prefs.carColorIndex()], false), "mg4-anim-avvio").start();
        }, 45_000);
        // 1.0.4 (punto D): MG4 Store contenuto nel launcher, installato una volta sola se manca o è più vecchio (30 s dopo l'avvio)
        later.postDelayed(() -> {
            if (!isDestroyed()) new Thread(() -> com.mg4.launcher.svc.StoreSetup.onStart(this), "mg4-store-avvio").start();
        }, 30_000);
        lastUiMode = getResources().getConfiguration().uiMode;

        root = new FrameLayout(this);
        root.setOnApplyWindowInsetsListener(this::applyInsets);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.requestApplyInsets();
        });
        setContentView(root);
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(vis -> {
            // se il sistema rimostra le barre (es. dopo un popup) le rinasconde
            if ((vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) root.postDelayed(this::goFullscreen, 2500);
        });
        goFullscreen();
        build();
        // primo avvio dopo un aggiornamento: popup "Novità" (una sola volta per versione)
        root.postDelayed(this::showNewsIfUpdated, 1500);

        vehicle.setListener(s -> {
            if (home != null) home.onVehicle(s);
            // lato di guida letto dall'auto solo dopo l'avvio del CarService: se cambia, si ricostruisce la home
            if (home != null && Prefs.DRIVE_AUTO.equals(prefs.driveSide())) {
                Boolean known = com.mg4.launcher.svc.CarFeatures.knownRhd(this);
                if (known != null && known != home.isRightHand()) {
                    boolean wasSettings = settings != null;
                    build();
                    if (wasSettings) openSettings();
                }
            }
        });
        media.setListener(s -> {
            if (home != null) home.onMedia(s);
        });
        nav.setListener(s -> {
            if (home != null) home.onNav(s);
        });
        // collegamento ai servizi dopo il primo disegno: la home compare subito, già nelle dimensioni finali
        root.post(() -> {
            vehicle.start();
            conn.start();
            media.start();
            nav.start();
            logs.start();
            com.mg4.launcher.svc.FirmwareExport.schedule(this);
            com.mg4.launcher.svc.DongleWatch.get(this, media).start();
            com.mg4.launcher.svc.EmbedCheck.log(this);
        });
        // avvio all'accensione: servizio di accessibilità, scadenza officina, riconferma della home predefinita
        com.mg4.launcher.svc.StartGuard.markProcess();
        com.mg4.launcher.svc.HomeMode.checkExpiry(getApplicationContext());
        com.mg4.launcher.svc.HomeMode.diag(getApplicationContext());
        new Thread(() -> {
            com.mg4.launcher.svc.BootAccessibility.ensureEnabled(getApplicationContext());
            com.mg4.launcher.svc.HomeLock.apply(getApplicationContext(), "avvio launcher");
        }, "mg4-home").start();
        com.mg4.launcher.svc.CrashGuard.install(this);
        com.mg4.launcher.svc.BootAccessibility.watchList(this);
        // 0.9.6: 10 minuti senza crash → il salvavita si azzera (l'avvio sicuro può ripartire alla prossima accensione)
        root.postDelayed(() -> com.mg4.launcher.svc.CrashGuard.reset(getApplicationContext()), 10 * 60_000L);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        // all'avvio dell'auto le app utente compaiono solo dopo lo sblocco dell'utente Android
        registerReceiver(packages, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // orologio nel riquadro batteria: ogni minuto e al cambio di ora / fuso
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockTick, tf);
        root.postDelayed(saveDiag, 15000);
        // 1.0.2 (punto 10): primo controllo aggiornamenti e avvisi 2 minuti dopo l'avvio (sistema stabile), poi ogni 6 ore
        root.postDelayed(autoCheck, FIRST_CHECK_MS);
        root.postDelayed(this::logoPacksTick, 20_000);
        // 1.0.2: accensione = avvio del launcher o risveglio dello schermo (l'auto si sospende e riprende, MtkPowerManager)
        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOn, sf);
        com.mg4.launcher.svc.RetroReturn.setMedia(media);
        // ripresa della sorgente solo se il launcher parte con l'auto (non dopo un aggiornamento o un riavvio a metà viaggio)
        if (android.os.SystemClock.elapsedRealtime() < 4 * 60_000L) media.onPowerOn("avvio del launcher all'accensione");
        // 1.0.2 (punto 1): tema automatico con catena di fonti e 60 s di conferma
        com.mg4.launcher.svc.DayNight.get(this).start(day -> {
            if (!Prefs.THEME_AUTO.equals(prefs.themeMode()) || theme == null || theme.day == day) return;
            com.mg4.launcher.svc.Diag.put("tema.ricostruito", (day ? "giorno" : "notte") + " (cambio confermato)");
            IconStyler.clearCache();
            boolean wasSettings = settings != null;
            build();
            if (wasSettings) openSettings();
        });
    }

    /** Risveglio dello schermo = accensione dell'auto: sorgente audio, aggiornamenti e avvisi importanti. */
    private long screenOffAt = -1;

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                screenOffAt = android.os.SystemClock.elapsedRealtime();
                if (home != null) home.pauseChargeAnim();   // 1.0.4: niente decoder video a schermo spento
                return;
            }
            // accensione vera solo dopo almeno 2 minuti di schermo spento (non il tasto "schermo spento" del momento)
            long off = screenOffAt < 0 ? 0 : android.os.SystemClock.elapsedRealtime() - screenOffAt;
            screenOffAt = -1;
            if (off < 2 * 60_000L) return;
            if (home != null) home.onScreenOn();
            if (media != null) media.onPowerOn("risveglio dello schermo dopo " + (off / 60_000) + " min");
            com.mg4.launcher.svc.Notices.newPowerOn();
            root.removeCallbacks(autoCheck);
            root.postDelayed(autoCheck, FIRST_CHECK_MS);
        }
    };

    // ---- modalità officina: aperto il launcher durante l'officina, si chiede se disattivarla ----------

    private boolean officinaAsked;

    private void askOfficina() {
        android.content.Context app = getApplicationContext();
        if (!com.mg4.launcher.svc.HomeMode.officina(app)) {
            officinaAsked = false;
            return;
        }
        if (officinaAsked) return;
        officinaAsked = true;
        root.post(() -> com.mg4.launcher.ui.Confirm.show(root, theme, L.s(R.string.officina_active_title),
                L.f(R.string.officina_active_msg, com.mg4.launcher.svc.HomeMode.officinaDate(app)),
                L.s(R.string.btn_keep), L.s(R.string.btn_deactivate), () -> {
                    com.mg4.launcher.svc.HomeMode.end(app, "annullata");
                    if (settings != null) {
                        closeSettings();
                        openSettings();
                    }
                }));
    }

    // ---- 1.0.2 (punto 10): aggiornamenti e avvisi da remoto ------------------------------------------

    private static final long FIRST_CHECK_MS = 2 * 60_000L;           // dopo l'accensione, a sistema stabile
    private static final long CHECK_EVERY = 6 * 60 * 60 * 1000L;      // poi ogni 6 ore
    private static final long POPUP_SHOW = 20_000;                    // poi l'avviso si nasconde da solo
    private static final long FORCE_RETRY_MS = 60_000, FORCE_WAIT_MAX_MS = 30 * 60_000L;
    private View updPopup;
    private long forceWaitSince;

    private final Runnable autoCheck = new Runnable() {
        @Override
        public void run() {
            root.removeCallbacks(this);
            if (!com.mg4.launcher.svc.HomeMode.officina(getApplicationContext())) updater.checkRemote(MainActivity.this::onRemote);
            root.postDelayed(this, CHECK_EVERY);
        }
    };

    private void onRemote(Updater.Remote r) {
        if (isFinishing() || root == null) return;
        // avvisi da remoto (uno alla volta)
        java.util.List<com.mg4.launcher.svc.Notices.Notice> list = com.mg4.launcher.svc.Notices.pending(this, r.notices,
                updater.currentVersion(), L.resolve(prefs.language()));
        showNotices(list, 0, r.release);
        Updater.Release rel = r.release;
        if (rel == null || rel.apkUrl == null || !rel.newer) return;
        root.removeCallbacks(forceRetry);
        if (r.belowMinima) {
            forceWaitSince = android.os.SystemClock.elapsedRealtime();
            forceRemote = r;
            tryForced(r);
        }
        showUpdatePopup(rel, r.belowMinima);
    }

    /** Aggiornamento forzato ("minima"): solo ad auto ferma; se velocità non leggibile non si forza (resta l'avviso). */
    private void tryForced(Updater.Remote r) {
        Updater.Release rel = r.release;
        if (!updater.canForce(rel.tag)) {
            updater.remoteDiag(r, "sotto la minima " + r.minima + " ma già 3 tentativi falliti per " + rel.tag + ": fermo");
            return;
        }
        Boolean stopped = com.mg4.launcher.svc.VehicleClient.surelyStopped();
        String motion = com.mg4.launcher.svc.VehicleClient.motionDescription();
        if (stopped == null) {
            updater.remoteDiag(r, "sotto la minima " + r.minima + ": velocità e marcia non leggibili (" + motion + "), non forzato: solo avviso");
            return;
        }
        if (!stopped) {
            if (android.os.SystemClock.elapsedRealtime() - forceWaitSince > FORCE_WAIT_MAX_MS) {
                updater.remoteDiag(r, "sotto la minima " + r.minima + ": auto in movimento da 30 min, riprovo al prossimo controllo");
                return;
            }
            updater.remoteDiag(r, "sotto la minima " + r.minima + ": auto non ferma da 2 minuti (" + motion + "), aspetto");
            root.removeCallbacks(forceRetry);
            root.postDelayed(forceRetry, FORCE_RETRY_MS);
            return;
        }
        if (updater.isBusy()) return;
        updater.remoteDiag(r, "sotto la minima " + r.minima + ": auto ferma (" + motion + "), installo " + rel.tag);
        updater.installRemote(rel, true);
    }

    private Updater.Remote forceRemote;
    private final Runnable forceRetry = () -> {
        if (forceRemote != null && !isFinishing()) tryForced(forceRemote);
    };

    private void showNotices(java.util.List<com.mg4.launcher.svc.Notices.Notice> list, int i, Updater.Release rel) {
        if (i >= list.size() || isFinishing()) return;
        com.mg4.launcher.svc.Notices.Notice n = list.get(i);
        com.mg4.launcher.svc.Notices.markShown(this, n);
        boolean upd = n.updateButton && rel != null && rel.newer && rel.apkUrl != null;
        com.mg4.launcher.ui.Confirm.show2(root, theme, L.s(R.string.notice_title), n.text, L.s(R.string.btn_ok), () -> {
            com.mg4.launcher.svc.Notices.markConfirmed(this, n);
            showNotices(list, i + 1, rel);
        }, upd ? L.s(R.string.upd_now) : null, () -> {
            com.mg4.launcher.svc.Notices.markConfirmed(this, n);
            startRemoteInstall(rel);
        });
    }

    /** 1.0.2 (punto 10d): il pulsante Aggiorna funziona anche fuori dalle impostazioni (menu con un problema). */
    private void startRemoteInstall(Updater.Release r) {
        if (r == null) return;
        android.widget.Toast.makeText(this, L.f(R.string.upd_downloading, r.apkName), android.widget.Toast.LENGTH_LONG).show();
        updater.installRemote(r, false);
    }

    private final Runnable hidePopup = () -> hideUpdatePopup();

    private void showUpdatePopup(Updater.Release r, boolean important) {
        if (r == null || updPopup != null) return;
        if (!important && (!prefs.updatePopup() || r.tag.equals(prefs.dismissedUpdate()))) return;
        if (updater.isBusy()) return;
        Theme t = theme;
        LinearLayout card = Ui.row(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(24), dp(14), dp(14), dp(14));
        android.graphics.drawable.GradientDrawable bgd = Ui.round(t.panel, dp(22));
        bgd.setStroke(dp(1), t.track);
        card.setBackground(bgd);
        card.setElevation(dp(14));
        card.setClickable(true);   // i tocchi sul popup non passano alla home sotto

        LinearLayout txt = Ui.column(this);
        txt.addView(Ui.text(this, L.s(R.string.upd_popup_title), 18, t.text, Ui.MEDIUM));
        txt.addView(Ui.text(this, L.f(R.string.upd_popup_text, r.tag), 15, t.sub, Ui.REGULAR));
        card.addView(txt, Ui.margins(Ui.lp(-2, -2), 0, 0, dp(28), 0));

        card.addView(popupButton(L.s(R.string.upd_later), t.chip, t.text, v -> {
            if (!important) prefs.setDismissedUpdate(r.tag);
            hideUpdatePopup();
        }), Ui.margins(Ui.lp(-2, dp(48)), 0, 0, dp(10), 0));
        card.addView(popupButton(L.s(R.string.upd_now), t.accent, t.onAccent, v -> {
            hideUpdatePopup();
            startRemoteInstall(r);
        }), Ui.lp(-2, dp(48)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(20);
        root.addView(card, lp);
        updPopup = card;
        card.setAlpha(0f);
        card.setTranslationY(-dp(30));
        card.animate().alpha(1f).translationY(0).setDuration(280).start();
        root.removeCallbacks(hidePopup);
        root.postDelayed(hidePopup, POPUP_SHOW);
    }

    private void hideUpdatePopup() {
        root.removeCallbacks(hidePopup);
        View v = updPopup;
        updPopup = null;
        if (v == null) return;
        v.animate().alpha(0f).translationY(-dp(30)).setDuration(220).withEndAction(() -> root.removeView(v)).start();
    }

    private TextView popupButton(String text, int bg, int fg, View.OnClickListener l) {
        TextView b = Ui.text(this, text, 16, fg, Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(20), 0, dp(20), 0);
        b.setBackground(Ui.pressable(this, bg, 14, theme));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    // ---- firmware sbagliato ------------------------------------------------------------------

    private void showWrongFirmware() {
        theme = Theme.resolve(this, prefs);
        Theme t = theme;
        root.removeAllViews();
        root.setBackgroundColor(t.bg);
        boolean swi69 = com.mg4.launcher.svc.Firmware.kind(this) == com.mg4.launcher.svc.Firmware.SWI69;
        LinearLayout card = Ui.column(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.round(t.panel, dp(28)));
        card.setPadding(dp(56), dp(44), dp(56), dp(40));
        card.addView(Ui.icon(this, R.drawable.ic_info, t.text, 56));
        TextView title = Ui.text(this, L.s(swi69 ? R.string.fw_swi69_title : R.string.fw_other_title), 36, t.text, Ui.MEDIUM);
        title.setGravity(Gravity.CENTER);
        card.addView(title, Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(20), 0, 0));
        TextView msg = Ui.text(this, L.s(swi69 ? R.string.fw_swi69_msg : R.string.fw_other_msg), 21, t.sub, Ui.REGULAR);
        msg.setGravity(Gravity.CENTER);
        msg.setLineSpacing(0, 1.2f);
        card.addView(msg, Ui.margins(new LinearLayout.LayoutParams(-1, -2), 0, dp(16), 0, 0));
        TextView link = Ui.text(this, com.mg4.launcher.svc.Firmware.SWI69_URL.replace("https://", ""), 22, t.text, Ui.MEDIUM);
        link.setGravity(Gravity.CENTER);
        link.setPadding(dp(24), dp(14), dp(24), dp(14));
        link.setBackground(Ui.round(t.chip, dp(16)));
        link.setTextIsSelectable(true);
        card.addView(link, Ui.margins(new LinearLayout.LayoutParams(-2, -2), 0, dp(26), 0, 0));
        TextView open = Ui.text(this, L.s(R.string.fw_open_original), 20, t.panel, Ui.MEDIUM);
        open.setGravity(Gravity.CENTER);
        open.setPadding(dp(30), 0, dp(30), 0);
        open.setBackground(Ui.pressable(this, t.text, 16, t));
        open.setClickable(true);
        open.setOnClickListener(v -> openAnyOtherHome());
        card.addView(open, Ui.margins(new LinearLayout.LayoutParams(-2, dp(64)), 0, dp(30), 0, 0));
        root.addView(card, new FrameLayout.LayoutParams(dp(1100), -2, Gravity.CENTER));
    }

    /** Apre la home originale dell'auto (qualunque firmware): la prima home che non è questo launcher. */
    private void openAnyOtherHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)
                        || com.mg4.launcher.svc.HomeDefault.isProvision(ri.activityInfo.packageName)) continue;
                startActivity(new Intent(home).setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        } catch (Exception e) {
            Diag.error("home originale", e.toString());
        }
    }

    // ---- popup "Novità" dopo un aggiornamento -------------------------------------------------

    /**
     * Una sola volta per versione (0.9.2: anche per le correzioni 0.9.x), nella lingua scelta; non dopo la prima
     * installazione. Ogni versione ha le sue novità ({@link #newsFor}).
     */
    private void showNewsIfUpdated() {
        String ver = updater.currentVersion();
        String seen = prefs.newsShownFor();
        if (ver.equals(seen)) return;
        boolean updated = false;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            updated = pi.lastUpdateTime > pi.firstInstallTime + 1000;
        } catch (Exception ignored) {
        }
        prefs.setNewsShownFor(ver);
        if (!updated) return;
        showNews(ver);
    }

    /** Novità di ogni versione (testi in tutte le lingue in strings.xml). */
    private static int[] newsFor(String ver) {
        switch (ver) {
            case "1.0.4":
                return new int[]{R.string.news_v104_1, R.string.news_v104_2, R.string.news_v104_3, R.string.news_v104_4};
            case "1.0.3":
                return new int[]{R.string.news_v103_1, R.string.news_v103_2, R.string.news_v103_3, R.string.news_v103_4, R.string.news_v103_5};
            case "1.0.2":
                return new int[]{R.string.news_v102_1, R.string.news_v102_2, R.string.news_v102_3, R.string.news_v102_4, R.string.news_v102_5};
            case "1.0.0":
                return new int[]{R.string.news_v100_1, R.string.news_v100_2, R.string.news_v100_3, R.string.news_v100_4};
            case "0.9.8":
                return new int[]{R.string.news_v098_1, R.string.news_v098_2, R.string.news_v098_3};
            case "0.9.7":
                return new int[]{R.string.news_v097_1, R.string.news_v097_2, R.string.news_v097_3};
            case "0.9.6":
                return new int[]{R.string.newsh_1, R.string.newsh_2, R.string.newsh_3, R.string.newsh_4, R.string.newsh_5, R.string.newsh_6, R.string.newsh_7};
            case "0.9.5":
                return new int[]{R.string.newsg_1, R.string.newsg_2, R.string.newsg_3, R.string.newsg_4, R.string.newsg_6};
            case "0.9.4":
                return new int[]{R.string.newsf_1};
            case "0.9.3":
                return new int[]{R.string.news93_1, R.string.news93_2, R.string.news93_3, R.string.news93_4};
            case "0.9.2":
                return new int[]{R.string.news92_1};
            default:   // 0.9 / 0.9.1
                return new int[]{R.string.news9_1, R.string.news9_2, R.string.news9_3, R.string.news9_4, R.string.news9_5};
        }
    }

    private void showNews(String ver) {
        int[] ids = newsFor(ver);
        String[] items = new String[ids.length];
        for (int i = 0; i < ids.length; i++) items[i] = L.s(ids[i]);
        com.mg4.launcher.ui.Confirm.notice(root, theme, L.f(R.string.news_title, ver), L.s(R.string.news_sub), items,
                L.s(R.string.btn_ok), null);
    }

    /** Ogni 15 s salva la diagnostica su file: adb pull /sdcard/Android/data/com.mg4.launcher/files/diagnostica.txt */
    private final Runnable saveDiag = new Runnable() {
        @Override
        public void run() {
            new Thread(() -> Diag.save(MainActivity.this), "mg4-diag").start();
            root.postDelayed(this, 15000);
        }
    };

    /**
     * Schermo intero: il contenuto occupa sempre tutti i 1920×720 px. Le barre di sistema, se ricompaiono
     * per un attimo (trascinando dal bordo), si sovrappongono senza spostare il layout.
     */
    private WindowInsets applyInsets(View v, WindowInsets in) {
        v.setPadding(0, 0, 0, 0);
        Diag.put("finestra", v.getWidth() + "×" + v.getHeight() + " · margini sistema " + in.getSystemWindowInsetLeft() + ","
                + in.getSystemWindowInsetTop() + "," + in.getSystemWindowInsetRight() + "," + in.getSystemWindowInsetBottom());
        return in.consumeSystemWindowInsets();
    }

    private void goFullscreen() {
        View d = getWindow().getDecorView();
        if (d.getSystemUiVisibility() != IMMERSIVE) d.setSystemUiVisibility(IMMERSIVE);
    }

    /** 1.0.0: true mentre il launcher è davanti (onResume..onPause), per sapere se Home arriva "da dentro". */
    private boolean inFront;

    @Override
    protected void onPause() {
        inFront = false;
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        inFront = true;
        com.mg4.launcher.svc.RetroReturn.noteLauncherShown("ritorno nello stack (la telecamera era sopra il launcher)");
        // 1.0.3: tornando dall'app Radio l'elenco delle stazioni si rilegge subito
        if (media != null) media.refreshRadioLists("ritorno nel launcher");
        // 1.0.3: stato del telefono riletto (riquadro "Preferiti telefono"), anche se gli avvisi del servizio non arrivano
        com.mg4.launcher.svc.PhoneClient.get(this).refresh();
        if (wrongFirmware) {
            goFullscreen();
            return;
        }
        // avvio sicuro: tornati nel launcher dopo aver aperto apposta la home MG → la sua schermata si rispegne
        new Thread(() -> {
            Context app = getApplicationContext();
            if (com.mg4.launcher.svc.HomeLock.option(app) && com.mg4.launcher.svc.HomeMode.active(app)
                    && !com.mg4.launcher.svc.HomeLock.mgDisabled(app)) com.mg4.launcher.svc.HomeLock.apply(app, "ritorno nel launcher");
        }, "mg4-homelock").start();
        askOfficina();
        goFullscreen();
        // 1.0.0: se giorno/notte è cambiato mentre il launcher era dietro e l'avviso non è arrivato, si ricostruisce qui
        if (theme != null && root != null && com.mg4.launcher.ui.Theme.wouldBeDay(this, prefs) != theme.day) {
            com.mg4.launcher.svc.Diag.put("tema.ripreso", "cambio recuperato al ritorno nel launcher");
            IconStyler.clearCache();
            build();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    private void build() {
        theme = Theme.resolve(this, prefs);
        Ui.K = prefs.uiScaleFactor();
        root.setBackgroundColor(theme.bg);
        root.removeAllViews();
        root.removeCallbacks(hidePopup);
        updPopup = null;
        int keepPage = pager != null ? pager.page() : 0;
        pager = new Pager(this);
        home = new HomeView(this, theme, prefs, this, vehicle, media, nav);
        apps = new AppsView(this, theme, catalog, prefs, this);
        pager.addView(home);
        pager.addView(apps);
        pager.setListener(p -> {
            if (p == 1) apps.reload();
        });
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));
        settings = null;
        pager.show(keepPage, false);
        home.onVehicle(vehicle.last());
        home.onConn(conn.last());
        home.onMedia(media.last());
        home.onNav(nav.last());
        getWindow().setStatusBarColor(theme.bg);
        root.requestApplyInsets();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (wrongFirmware) return;
        // il collegamento ai servizi resta attivo; qui si riprendono solo le letture periodiche
        vehicle.resume();
        conn.resume();
        if (home != null) home.updateClock();
        media.resume();
        nav.resume();
    }

    @Override
    protected void onStop() {
        if (home != null) home.pauseChargeAnim();   // 1.0.4: animazione ferma con il launcher in secondo piano
        super.onStop();
        if (wrongFirmware) return;
        vehicle.pause();
        conn.pause();
        media.pause();
        nav.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        root.removeCallbacks(saveDiag);
        logs.stop();
        if (wrongFirmware) return;
        root.removeCallbacks(autoCheck);
        root.removeCallbacks(hidePopup);
        root.removeCallbacks(forceRetry);
        vehicle.stop();
        conn.stop();
        try {
            unregisterReceiver(clockTick);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(screenOn);
        } catch (Exception ignored) {
        }
        com.mg4.launcher.svc.DayNight.get(this).stop();
        media.stop();
        nav.stop();
        try {
            unregisterReceiver(packages);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (wrongFirmware) return;
        // tasto Home: torna sempre alla schermata principale
        // 1.0.0 (issue #19): con il launcher già davanti chiude anche ogni popup aperto (Clima, Comfort, Guida,
        // conferme, elenchi, avvisi)
        com.mg4.launcher.svc.RetroReturn.noteLauncherShown("intent HOME del sistema");
        if (inFront) closePopups("tasto Home");
        closeSettings();
        closeApps();
    }

    /** Toglie dalla schermata tutto ciò che non è la home/app o le impostazioni: popup dei gruppi, conferme, avvisi. */
    private void closePopups(String why) {
        int n = 0;
        if (home != null && home.closePopup()) n++;
        if (root != null) {
            for (int i = root.getChildCount() - 1; i >= 0; i--) {
                View v = root.getChildAt(i);
                if (v == pager || v == settings) continue;
                root.removeViewAt(i);
                n++;
            }
        }
        com.mg4.launcher.svc.Diag.put("home.popupChiusi", n + " (" + why + ")");
    }

    @Override
    public void onBackPressed() {
        if (wrongFirmware) return;
        if (settings != null) closeSettings();
        else if (pager != null && pager.page() != 0) closeApps();
        // altrimenti il launcher resta dov'è
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        if (wrongFirmware) {
            if (L.init(this, prefs.language())) showWrongFirmware();
            return;
        }
        // lingua di sistema cambiata: con "Automatica" il launcher la segue subito
        if (L.init(this, prefs.language())) {
            catalog.refreshLabels();
            build();
            return;
        }
        if (cfg.uiMode != lastUiMode) {
            lastUiMode = cfg.uiMode;
            // 1.0.2: il cambio si applica dopo 60 s di conferma (DayNight richiama la ricostruzione)
            com.mg4.launcher.svc.DayNight.get(this).onConfigurationChanged();
        }
    }

    private final BroadcastReceiver clockTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (home != null) home.updateClock();
            logoPacksTick();
        }
    };

    // ---- pacchetti loghi radio: download quando arriva la rete, zip su chiavetta da installare ----------

    private int logoTicks;

    private void logoPacksTick() {
        com.mg4.launcher.svc.LogoPacks lp = com.mg4.launcher.svc.LogoPacks.get(this);
        lp.tick();
        if (logoTicks++ % 2 != 0) return;   // la chiavetta si controlla ogni 2 minuti
        new Thread(() -> {
            java.util.List<java.io.File> zips = lp.usbPacksToOffer();
            for (java.io.File f : zips) root.post(() -> offerUsbPack(lp, f));
        }, "mg4-usb-loghi").start();
    }

    private void offerUsbPack(com.mg4.launcher.svc.LogoPacks lp, java.io.File f) {
        if (isFinishing()) return;
        String cc = f.getName().substring(0, 2).toUpperCase(java.util.Locale.ROOT);
        com.mg4.launcher.ui.Confirm.show(root, theme, L.s(R.string.logos_usb_title),
                L.f(R.string.logos_usb_msg, com.mg4.launcher.svc.LogoPacks.countryName(cc), f.getParent()),
                L.s(R.string.btn_cancel), L.s(R.string.btn_install), () -> lp.installUsb(f, true));
        lp.installUsb(f, false);   // proposto una volta sola, anche se si annulla
    }

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            IconStyler.clearCache();
            if (apps != null) apps.reload();
        }
    };

    // --- HomeView.Host ------------------------------------------------------------------------

    @Override
    public void openApps() {
        if (pager != null) pager.show(1, true);
    }

    @Override
    public void openSettings() {
        if (settings == null) {
            settings = new SettingsView(this, theme, prefs, this);
            root.addView(settings, new FrameLayout.LayoutParams(-1, -1));
            if (updPopup != null) updPopup.bringToFront();
        }
    }

    @Override
    public void launch(String pkg, String cls) {
        catalog.launchComponent(pkg, cls);
    }

    /** Come la home originale (EnergyView): ChargeManagementActivity con l'extra "module" = scheda da aprire. */
    @Override
    public void openEnergy(String module) {
        // se ChargeManagementActivity non esiste su questo firmware si apre l'app Impostazioni veicolo
        Intent i = catalog.resolveLaunch("com.saicmotor.hmi.vehiclesettings",
                "com.saicmotor.hmi.vehiclesettings.chargemanagement.ui.ChargeManagementActivity");
        if (i == null) return;
        try {
            startActivity(i.putExtra("module", module).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            com.mg4.launcher.svc.Diag.error("energia " + module, e.toString());
        }
    }

    @Override
    public boolean canLaunch(String pkg, String cls) {
        return catalog.canLaunch(pkg, cls);
    }

    @Override
    public FrameLayout overlay() {
        return root;
    }

    @Override
    public void launchNavigator() {
        launchNavigator(prefs.navApp());
    }

    /** Apre il navigatore scelto: MG, quello del telefono (CarPlay/Android Auto) o un'app installata. */
    @Override
    public void launchNavigator(String which) {
        boolean phone = media.last().deviceType != 0;
        if (Prefs.NAV_PHONE.equals(which) || (Prefs.NAV_AUTO.equals(which) && phone)) {
            if (phone) {
                media.openProjection();
                media.openRemoteNavigation();
                return;
            }
        } else if (!Prefs.NAV_AUTO.equals(which) && !Prefs.NAV_MG.equals(which)) {
            // 1.0.4 (issue #27): app di navigazione scelta; se non c'è più si apre quella MG, senza crash
            Intent li = null;
            try {
                li = getPackageManager().getLaunchIntentForPackage(which);
            } catch (Exception ignored) {
            }
            if (li != null) {
                try {
                    startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    Diag.put("nav.appScelta", which + " · aperta");
                    return;
                } catch (Exception e) {
                    Diag.error("navigatore.app", e.toString());
                }
            }
            Diag.put("nav.appScelta", which + " · non disponibile: apro la navigazione MG");
        }
        Intent i = catalog.navIntent(nav.last().mapPackage);
        if (i != null) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception e) {
                Diag.error("navigatore.apri", e.toString());
            }
        }
        nav.openNavigator();
    }

    @Override
    public java.util.List<AppCatalog.App> otherNavApps() {
        return catalog.otherNavApps(nav.last().mapPackage);
    }

    /**
     * 1.0.4 (punto F5): prova dell'animazione di ricarica. Usa il pacchetto del colore scelto se c'è, altrimenti uno zip
     * animazione-<colore>.zip nella cartella principale della chiavetta USB. Si chiudono le impostazioni e la prova
     * gira sull'auto della home: aggancio → loop (3 giri) → finita → sgancio.
     */
    @Override
    public void testChargeAnimation() {
        String color = Prefs.CAR_COLORS[prefs.carColorIndex()];
        new Thread(() -> {
            java.io.File dir;
            String slug;
            if (com.mg4.launcher.svc.ChargePack.complete(this, color)) {
                dir = com.mg4.launcher.svc.ChargePack.dir(this, color);
                slug = com.mg4.launcher.svc.ChargePack.slug(color);
                Diag.put("ricarica.prova", "pacchetto " + color + " installato");
            } else {
                Object[] usb = com.mg4.launcher.svc.ChargePack.loadFromUsb(this);
                if (usb == null) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this, L.s(R.string.anim_test_missing), android.widget.Toast.LENGTH_LONG).show());
                    Diag.put("ricarica.prova", "nessun pacchetto: né scaricato né zip animazione-<colore>.zip sulla chiavetta");
                    return;
                }
                dir = (java.io.File) usb[0];
                slug = (String) usb[1];
            }
            runOnUiThread(() -> {
                if (settings != null) closeSettings();
                if (home != null) home.testChargeAnim(dir, slug, () -> Diag.put("ricarica.prova", "finita · " + slug));
            });
        }, "mg4-prova-animazione").start();
    }

    @Override
    public java.util.List<AppCatalog.App> navCandidateApps() {
        return catalog.navCandidateApps(nav.last().mapPackage);
    }

    @Override
    public java.util.List<AppCatalog.App> allLaunchableApps() {
        return catalog.userApps();
    }

    // --- AppsView.Host ------------------------------------------------------------------------

    @Override
    public void closeApps() {
        if (pager != null) pager.show(0, true);
    }

    @Override
    public String navPackageHint() {
        return nav.last().mapPackage;
    }

    // --- SettingsView.Host --------------------------------------------------------------------

    @Override
    public void closeSettings() {
        if (settings != null) {
            root.removeView(settings);
            settings = null;
        }
    }

    @Override
    public void settingsChanged(boolean rebuild) {
        if (rebuild) {
            IconStyler.clearCache();
            build();
            openSettings();
        } else if (home != null) {
            home.applyCarLook();
            home.refreshNav();
            home.onVehicle(vehicle.last());
        }
    }

    @Override
    public void languageChanged() {
        L.init(this, prefs.language());
        catalog.refreshLabels();
        IconStyler.clearCache();
        build();
        openSettings();
    }

    @Override
    public Updater updater() {
        return updater;
    }

    @Override
    public com.mg4.launcher.svc.LogUploader logs() {
        return logs;
    }

    @Override
    public void openOriginalHome() {
        try {
            com.mg4.launcher.svc.HomeLock.release(this, "home MG aperta apposta");
            // aperta apposta: il controllo all'accensione per un po' non deve riportare il nostro launcher
            com.mg4.launcher.svc.BootWatcher.userOpenedOriginal(this);
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName("com.saicmotor.hmi.launcher", "com.saicmotor.hmi.launcher.ui.MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Diag.error("home originale", e.toString());
        }
    }
}
