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

    /** Una sola volta dopo l'accensione o il risveglio, dopo i secondi scelti nelle impostazioni. */
    private void scheduleAutoHome(String why) {
        h.removeCallbacks(autoHome);
        int sec = com.mg4.launcher.ui.Prefs.autoHomeSeconds(getSharedPreferences("mg4launcher", MODE_PRIVATE));
        if (sec <= 0) {
            Diag.put("avvio.premiHome", "spento");
            return;
        }
        autoHomeWhy = why;
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

    @Override
    protected void onServiceConnected() {
        if (Firmware.wrong(this)) {
            Diag.put("avvio.accessibilita", "inattivo (firmware non SWI68)");
            return;
        }
        StartGuard.a11yConnected = true;
        StartGuard.markA11y();
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
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        if (!StartGuard.a11yConnected) return;   // firmware sbagliato: il servizio non fa nulla
        String pkg = e.getPackageName().toString();
        StartGuard.onForeground(this, pkg);
        // il nostro launcher deve restare in primo piano 5 s: si ricontrolla senza aspettare un altro evento
        if (getPackageName().equals(pkg) && StartGuard.windowOpen()) {
            h.postDelayed(() -> StartGuard.onForeground(this, StartGuard.topPackage(this)), 5200);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        StartGuard.a11yConnected = false;
        h.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(screenOn);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
