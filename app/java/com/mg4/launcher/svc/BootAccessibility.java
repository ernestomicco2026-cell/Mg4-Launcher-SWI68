package com.mg4.launcher.svc;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

/**
 * Servizio di accessibilità del launcher, usato SOLO per sapere quale app passa in primo piano
 * (TYPE_WINDOW_STATE_CHANGED, nessun contenuto delle finestre). Android lo collega all'avvio del sistema, prima
 * di BOOT_COMPLETED: così la home MG aperta dal firmware si sostituisce prima.
 */
public final class BootAccessibility extends AccessibilityService {
    private static final long EXPIRY_CHECK_MS = 30 * 60_000L;
    private final Handler h = new Handler(Looper.getMainLooper());

    /** Attiva il servizio in modo permanente (WRITE_SECURE_SETTINGS, firma platform), senza toccare gli altri. */
    // --- 0.9.6: se un'altra app riscrive l'elenco dei servizi di accessibilità e ci toglie, ci si rimette ---
    private static volatile boolean watching;
    private static final java.util.ArrayDeque<Long> readds = new java.util.ArrayDeque<>();

    public static synchronized void watchList(Context ctx) {
        if (watching) return;
        watching = true;
        Context c = ctx.getApplicationContext();
        try {
            c.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    false, new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            if (!StartGuard.unlocked(c) || !HomeMode.active(c)) return;
                            String me = new ComponentName(c, BootAccessibility.class).flattenToString();
                            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                            if (cur != null && cur.toLowerCase().contains(me.toLowerCase())) return;
                            long now = android.os.SystemClock.elapsedRealtime();
                            while (!readds.isEmpty() && now - readds.peekFirst() > 10 * 60_000L) readds.removeFirst();
                            if (readds.size() >= 5) {
                                Diag.put("avvio.accessibilitaTolta", "tolta di nuovo da un'altra app: smetto per 10 minuti");
                                return;
                            }
                            readds.addLast(now);
                            Diag.put("avvio.accessibilitaTolta", "un'altra app l'aveva tolta (elenco: " + cur + "): rimessa");
                            ensureEnabled(c);
                        }
                    });
        } catch (Throwable e) {
            Diag.put("avvio.accessibilitaTolta", "controllo non disponibile: " + e);
        }
    }

    public static void ensureEnabled(Context c) {
        try {
            String me = new ComponentName(c, BootAccessibility.class).flattenToString();
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean listed = false;
            if (!TextUtils.isEmpty(cur)) for (String s : cur.split(":")) if (s.equalsIgnoreCase(me)) listed = true;
            int on = Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (listed && on == 1) {
                Diag.put("avvio.accessibilita", StartGuard.a11yConnected ? "attivo" : "abilitato, in attesa di collegamento");
                return;
            }
            if (!listed) {
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        TextUtils.isEmpty(cur) ? me : cur + ":" + me);
            }
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Diag.put("avvio.accessibilita", "attivato ora");
        } catch (Throwable e) {
            Diag.put("avvio.accessibilita", "errore: " + e);
        }
    }

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            HomeMode.checkExpiry(c);
            StartGuard.openWindow(c, "risveglio schermo");
            scheduleAutoHome("risveglio");
        }
    };

    // ---- "Premi Home da solo dopo l'accensione" (opzione) ---------------------------------------

    private final Runnable autoHome = this::autoHomeNow;
    private String autoHomeWhy = "";
    /** Fino a quando riprovare se la home MG non è ancora comparsa (0.8.6-beta). */
    private long autoHomeUntil;
    private static final long AUTO_HOME_RETRY_MS = 5000, AUTO_HOME_WAIT_MS = 180_000;

    /** Una sola volta dopo l'accensione o il risveglio, dopo i secondi scelti nelle impostazioni. */
    private void scheduleAutoHome(String why) {
        h.removeCallbacks(autoHome);
        int sec = com.mg4.launcher.ui.Prefs.autoHomeSeconds(getSharedPreferences("mg4launcher", MODE_PRIVATE));
        if (sec <= 0) {
            Diag.put("avvio.premiHome", "spento");
            return;
        }
        autoHomeWhy = why;
        autoHomeUntil = android.os.SystemClock.elapsedRealtime() + AUTO_HOME_WAIT_MS;
        Diag.put("avvio.premiHome", "tra " + sec + " s (" + why + ")");
        h.postDelayed(autoHome, sec * 1000L);
    }

    /**
     * Preme Home solo se in primo piano c'è la home MG: non interviene se il launcher è già davanti o se c'è un'altra app
     * (retrocamera, 360°, CarPlay/Android Auto, chiamata...).
     */
    private void autoHomeNow() {
        String top = StartGuard.topPackage(this);
        String result;
        if (getPackageName().equals(top)) {
            // la home MG può arrivare dopo: si ricontrolla finché non compare (al massimo 3 minuti)
            if (!StartGuard.mgSeenInWindow() && android.os.SystemClock.elapsedRealtime() < autoHomeUntil) {
                Diag.put("avvio.premiHome", "launcher davanti, in attesa della home MG · " + autoHomeWhy);
                h.postDelayed(autoHome, AUTO_HOME_RETRY_MS);
                return;
            }
            result = "non serviva (launcher già davanti)";
        } else if (BootWatcher.MG_HOME.equals(top)) {
            boolean ok = performGlobalAction(GLOBAL_ACTION_HOME);
            result = ok ? "premuto Home (c'era la home MG)" : "Home non riuscito";
        } else {
            result = "no: in primo piano " + (top == null ? "sconosciuto" : top);
        }
        Diag.put("avvio.premiHome", result + " · " + autoHomeWhy);
    }

    private final Runnable expiry = new Runnable() {
        @Override
        public void run() {
            HomeMode.checkExpiry(BootAccessibility.this);
            h.postDelayed(this, EXPIRY_CHECK_MS);
        }
    };

    /** 0.8.7-beta: collegato prima dello sblocco → si parte all'arrivo di USER_UNLOCKED. */
    private boolean started, unlockRegistered;
    private boolean hvacPanel;
    private final BroadcastReceiver unlock = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Diag.put("avvio.accessibilita", "utente sbloccato: avvio");
            start();
        }
    };

    @Override
    protected void onServiceConnected() {
        if (Firmware.wrong(this)) {
            Diag.put("avvio.accessibilita", "inattivo (firmware non SWI68)");
            return;
        }
        StartGuard.markA11y();
        hvacPanel = SysHvacPanel.supported(this);
        if (hvacPanel) SysHvacPanel.bind(this);
        CrashGuard.install(this);
        watchList(this);
        if (!StartGuard.unlocked(this)) {
            Diag.put("avvio.accessibilita", "collegato prima dello sblocco: aspetto lo sblocco");
            try {
                registerReceiver(unlock, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
                unlockRegistered = true;
            } catch (Throwable ignored) {
            }
            // sblocco avvenuto nel frattempo?
            if (StartGuard.unlocked(this)) start();
            return;
        }
        start();
    }

    /** Avvio vero e proprio, una sola volta, a utente sbloccato. */
    private void start() {
        if (started) return;
        started = true;
        StartGuard.a11yConnected = true;
        Diag.put("avvio.accessibilita", "attivo");
        HomeMode.checkExpiry(this);
        StartGuard.openWindow(this, "accensione");
        // la home MG potrebbe essere già in primo piano
        StartGuard.onForeground(this, StartGuard.topPackage(this));
        scheduleAutoHome("accensione");
        try {
            registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        } catch (Throwable ignored) {
        }
        h.postDelayed(expiry, EXPIRY_CHECK_MS);
        // 0.9.6: avvio sicuro riconfermato a ogni accensione; se la home MG non compare, si apre comunque il launcher
        new Thread(() -> HomeLock.apply(this, "accensione"), "mg4-homelock").start();
        h.postDelayed(() -> StartGuard.lateCheck(this), 8000);
        h.postDelayed(() -> StartGuard.lateCheck(this), 20000);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (hvacPanel) SysHvacPanel.onEvent(this, e);   // 0.9.6: pannello clima di sistema sopra MG4 Clima
        if (e == null || e.getPackageName() == null) return;
        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            onSystemUiClick(e);
            return;
        }
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!started || !StartGuard.a11yConnected) return;   // firmware sbagliato o utente non ancora sbloccato
        String pkg = e.getPackageName().toString();
        RetroReturn.onForeground(this, pkg);   // 1.0.2: prima di StartGuard, che altrimenti riaprirebbe il launcher
        StartGuard.onForeground(this, pkg);
        // il nostro launcher deve restare in primo piano 5 s: si ricontrolla senza aspettare un altro evento
        if (getPackageName().equals(pkg) && StartGuard.windowOpen()) {
            h.postDelayed(() -> StartGuard.onForeground(this, StartGuard.topPackage(this)), 5200);
        }
    }

    // ---- tasto Home del dock di sistema (0.9.9... 1.0.0, punto 1) -------------------------------
    //
    // Verificato nella SystemUI SWI68 (com.android.systemui.saicmotor.dock.DockBar.bindHomeButton): la casetta
    // del dock di sistema (visibile sopra qualunque app, anche dentro le impostazioni dell'auto) lancia un Intent
    // con il componente esplicito com.saicmotor.hmi.launcher/.ui.MainActivity, cioè la home MG - non chiede "qual è
    // la home predefinita". Con l'avvio sicuro quella home è disattivata, quindi il tocco non fa nulla. Non si può
    // correggere la SystemUI (app di sistema), quindi si riconosce il tocco (testo della voce "Home" del dock,
    // preso dalle risorse della SystemUI così vale in ogni lingua dell'auto) e, se dopo un attimo il launcher non è
    // ancora comparso, si preme Home da soli con il servizio di accessibilità.
    private static final String SYSTEMUI_PKG = "com.android.systemui";
    private String dockHomeLabel;
    private boolean dockHomeLabelTried;

    private String dockHomeLabel() {
        if (dockHomeLabelTried) return dockHomeLabel;
        dockHomeLabelTried = true;
        try {
            android.content.res.Resources r = getPackageManager().getResourcesForApplication(SYSTEMUI_PKG);
            int id = r.getIdentifier("dock_home_name", "string", SYSTEMUI_PKG);
            if (id != 0) dockHomeLabel = r.getString(id);
        } catch (Throwable ignored) {
        }
        return dockHomeLabel;
    }

    private void onSystemUiClick(AccessibilityEvent e) {
        if (!started || !StartGuard.a11yConnected) return;
        if (!SYSTEMUI_PKG.equals(e.getPackageName().toString())) return;
        String label = dockHomeLabel();
        if (label == null || e.getText() == null || e.getText().isEmpty() || !label.contentEquals(e.getText().get(0))) return;
        RetroReturn.userPressedHome("casetta del dock");
        if (getPackageName().equals(StartGuard.topPackage(this))) return;   // siamo già davanti, niente da fare
        h.postDelayed(() -> {
            if (getPackageName().equals(StartGuard.topPackage(this))) return;
            boolean ok = performGlobalAction(GLOBAL_ACTION_HOME);
            Diag.put("home.tastoTouch", "casetta del dock toccata, l'avvio diretto alla home MG non è arrivato · Home "
                    + (ok ? "premuto" : "non riuscito"));
        }, 400);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        StartGuard.a11yConnected = false;
        h.removeCallbacksAndMessages(null);
        if (started) {
            try {
                unregisterReceiver(screenOn);
            } catch (Throwable ignored) {
            }
        }
        if (unlockRegistered) {
            try {
                unregisterReceiver(unlock);
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }
}
