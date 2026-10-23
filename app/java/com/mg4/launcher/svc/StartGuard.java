package com.mg4.launcher.svc;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;

import java.util.List;

/**
 * Logica comune dell'avvio all'accensione, usata dal servizio di accessibilità (eventi di cambio finestra) e,
 * se il servizio non è attivo, dal controllo di riserva ogni 400 ms (BootWatcher).
 * <p>
 * Finestra di avvio: dall'accensione (o dal risveglio dello schermo) fino a 5 s dopo che il nostro launcher è in
 * primo piano. Dalla 0.8.6-beta: se la home MG non è ancora comparsa, la finestra resta aperta finché il firmware
 * non la porta davanti almeno una volta (al massimo 3 minuti), perché su alcune auto arriva dopo il nostro launcher. Dentro la finestra, se compare la home MG, si riapre il nostro launcher (al
 * massimo 3 volte). Non interviene se la home MG è stata aperta apposta, se in primo piano c'è un'altra app
 * (retrocamera, CarPlay, Android Auto, chiamata, avvisi) o se l'interruttore è spento / c'è l'officina.
 */
public final class StartGuard {
    private static final long WINDOW_MS = 60_000, OURS_STABLE_MS = 5000, ORIGINAL_GRACE_MS = 10 * 60_000L;
    /** Attesa massima della home MG, se non è ancora comparsa (0.8.6-beta). */
    private static final long WAIT_MG_MS = 180_000, AFTER_MG_MS = 30_000;
    private static final int MAX_RESTARTS = 3;

    // tempi da SystemClock.elapsedRealtime() (ms dall'accensione), -1 = non ancora
    private static long tProcess = -1, tA11y = -1, tBoot = -1, tMg = -1, tOurs = -1;
    private static long windowStart = -1, oursSince = -1, deadline = -1;
    private static boolean mgInWindow;
    private static int restarts;
    private static String windowWhy = "";
    static volatile boolean a11yConnected;

    private StartGuard() {}

    /**
     * Utente sbloccato? Prima dello sblocco le impostazioni del launcher (credential storage) non sono leggibili e
     * leggerle fa crashare il processo (0.8.7-beta: ogni lavoro dell'avvio aspetta lo sblocco).
     */
    public static boolean unlocked(Context c) {
        try {
            android.os.UserManager um = (android.os.UserManager) c.getSystemService(Context.USER_SERVICE);
            return um == null || um.isUserUnlocked();
        } catch (Throwable e) {
            return true;
        }
    }

    /** Da chiamare quando l'utente apre apposta la home MG: per un po' l'avvio non interviene. */
    public static void userOpenedOriginal(Context c) {
        sp(c).edit().putLong("homeOriginaleFinoA", System.currentTimeMillis() + ORIGINAL_GRACE_MS).apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
    }

    public static synchronized void markProcess() {
        if (tProcess < 0) tProcess = Process.getStartElapsedRealtime();
    }

    public static synchronized void markA11y() {
        markProcess();
        if (tA11y < 0) tA11y = SystemClock.elapsedRealtime();
        times();
    }

    public static synchronized void markBoot() {
        markProcess();
        if (tBoot < 0) tBoot = SystemClock.elapsedRealtime();
        times();
    }

    /** Apre la finestra di avvio (accensione o risveglio dello schermo). */
    public static synchronized void openWindow(Context c, String why) {
        markProcess();
        if (!HomeMode.active(c)) {
            Diag.put("avvio.homeRipresa", "no (" + HomeMode.reason(c) + ")");
            return;
        }
        if (windowOpen()) return;
        windowStart = SystemClock.elapsedRealtime();
        deadline = windowStart + WAIT_MG_MS;
        mgInWindow = false;
        oursSince = -1;
        restarts = 0;
        windowWhy = why;
        Diag.put("avvio.homeRipresa", "controllo in corso (" + why + ")");
    }

    public static synchronized boolean windowOpen() {
        return windowStart >= 0 && SystemClock.elapsedRealtime() < deadline;
    }

    /** true se nella finestra attuale la home MG è già comparsa (o la finestra è chiusa). */
    public static synchronized boolean mgSeenInWindow() {
        return windowStart < 0 || mgInWindow;
    }

    /** Nuova app in primo piano (evento di accessibilità o controllo periodico). */
    public static synchronized void onForeground(Context c, String pkg) {
        if (pkg == null) return;
        long now = SystemClock.elapsedRealtime();
        if (BootWatcher.MG_HOME.equals(pkg) && tMg < 0) {
            tMg = now;
            Diag.put("avvio.primoPiano", pkg + " dopo " + (now / 1000) + " s");
            times();
        }
        if (c.getPackageName().equals(pkg) && tOurs < 0) {
            tOurs = now;
            times();
        }
        if (windowStart < 0) return;
        if (now >= deadline) {
            close(restarts > 0 ? "sì (tempo scaduto)" : mgInWindow ? "no (tempo scaduto)" : "no (home MG mai comparsa in 3 min)");
            return;
        }
        if (!HomeMode.active(c)) {
            close("no (" + HomeMode.reason(c) + ")");
            return;
        }
        if (BootWatcher.MG_HOME.equals(pkg)) {
            oursSince = -1;
            if (!mgInWindow) {
                mgInWindow = true;
                // da qui bastano i tempi di prima: 60 s dall'inizio, ma almeno 30 s per verificare la riapertura
                deadline = Math.max(windowStart + WINDOW_MS, now + AFTER_MG_MS);
            }
            if (System.currentTimeMillis() < sp(c).getLong("homeOriginaleFinoA", 0)) {
                close("no (home MG aperta apposta)");
                return;
            }
            if (restarts < MAX_RESTARTS) {
                restarts++;
                HomeMode.openOurs(c);
                Diag.put("avvio.homeRipresa", "riaperto il launcher (tentativo " + restarts + ", " + windowWhy + ")");
            }
        } else if (c.getPackageName().equals(pkg)) {
            if (oursSince < 0) oursSince = now;
            if (now - oursSince >= OURS_STABLE_MS) {
                if (mgInWindow) close(restarts > 0 ? "sì" : "no (già in primo piano)");
                else Diag.put("avvio.homeRipresa", "launcher davanti, in attesa della home MG (" + windowWhy + ")");
            }
        } else {
            oursSince = -1;   // un'altra app (retrocamera, CarPlay, chiamata...): non si tocca
        }
    }

    private static void close(String result) {
        Diag.put("avvio.homeRipresa", result + ", tentativi " + restarts + " (" + windowWhy + ")");
        windowStart = -1;
        deadline = -1;
        oursSince = -1;
    }

    /** App in primo piano adesso (REAL_GET_TASKS, concesso dalla firma platform). */
    public static String topPackage(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            @SuppressWarnings("deprecation")
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty() || tasks.get(0).topActivity == null) return null;
            return tasks.get(0).topActivity.getPackageName();
        } catch (Throwable e) {
            return null;
        }
    }

    private static String s(long t) {
        return t < 0 ? "—" : String.format(java.util.Locale.ROOT, "%.1fs", t / 1000f);
    }

    private static void times() {
        Diag.put("avvio.tempi", "processo " + s(tProcess) + " · accessibilità " + s(tA11y) + " · boot " + s(tBoot)
                + " · home MG " + s(tMg) + " · nostro launcher " + s(tOurs));
    }
}
