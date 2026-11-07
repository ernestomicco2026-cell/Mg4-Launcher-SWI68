package com.mg4.launcher.svc;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 1.0.2 (punto 5): dopo la retromarcia si torna all'app che era davanti (CarPlay, Android Auto anche da box o dongle,
 * navigatore o un'altra app), invece di restare sul launcher.
 * <p>
 * Perché si tornava al launcher: con la marcia in R il sistema apre la telecamera (app aroundview / AVM) sopra tutto;
 * la proiezione CarPlay/Android Auto (Allgo) in quel momento lascia lo schermo e chiude la sua schermata, quindi quando
 * la telecamera si chiude Android mostra quello che resta sotto, cioè la home (il nostro launcher, oppure per un attimo
 * la home MG che l'avvio sicuro sostituisce con il launcher). Non è HomeLock né HomeMode a riportarlo davanti; solo
 * nei primi minuti dopo l'accensione StartGuard, vedendo comparire la home MG, riapriva il launcher: lì ora si ferma
 * se c'è un ritorno dalla retromarcia in corso.
 * <p>
 * Regole: si memorizza l'app in primo piano quando compare la telecamera; quando la telecamera si chiude e davanti c'è
 * il launcher (o la home MG) si riapre quell'app nello stesso modo del pulsante CarPlay/Android Auto del launcher.
 * Non si riapre se l'utente nel frattempo ha scelto un'altra app o premuto Home, se la retromarcia è durata più di
 * 3 minuti, se l'app non è più collegata (box staccato) o se l'impostazione è spenta.
 */
public final class RetroReturn {
    private static final long MAX_REVERSE_MS = 3 * 60_000L, SETTLE_MS = 700, SUPPRESS_MS = 6000;

    private static final Handler h = new Handler(Looper.getMainLooper());
    private static volatile MediaClient media;

    private static String prevPkg;          // ultima app in primo piano prima della telecamera
    private static String lastAppPkg;       // ultima app non-home vista (Allgo può chiudersi un attimo prima della telecamera)
    private static long lastAppAt;
    private static String savedPkg;         // app memorizzata all'apertura della telecamera
    private static boolean inCamera;
    private static long cameraSince, cameraEnd;
    private static boolean userActed;
    private static String broughtBy = "";   // come è comparso il launcher dopo la telecamera
    private static long suppressUntil;

    private RetroReturn() {}

    public static void setMedia(MediaClient m) {
        media = m;
    }

    /** App della telecamera (retrocamera, 360°): stessi nomi che l'avvio all'accensione non copre. */
    public static boolean isCamera(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        if (p.equals("com.saicmotor.hmi.aroundview") || p.equals("com.saicmotor.hmi.camera")) return true;
        return p.startsWith("com.saicmotor.") && (p.contains("aroundview") || p.contains(".avm") || p.contains("rvc") || p.contains("rearview"));
    }

    private static boolean isProjection(String pkg) {
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        return p.contains("allgo") || p.contains("carplay") || p.contains("androidauto");
    }

    /** Finestre che non sono un'app scelta dall'utente (barra di sistema, tastiera, pannelli). */
    private static boolean ignored(Context c, String pkg) {
        return pkg.equals("com.android.systemui") || pkg.equals("android") || pkg.contains("inputmethod")
                || pkg.equals("com.saicmotor.hmi.hvac.panel");
    }

    private static boolean isHomeLike(Context c, String pkg) {
        return c.getPackageName().equals(pkg) || StartGuard.isHome(c, pkg);
    }

    /** StartGuard non deve riaprire il launcher mentre si sta tornando all'app di prima. */
    public static boolean suppressHome() {
        return SystemClock.elapsedRealtime() < suppressUntil;
    }

    /** Tasto Home premuto (dock o tasto del launcher): niente ritorno automatico. */
    public static void userPressedHome(String how) {
        if (inCamera || SystemClock.elapsedRealtime() - cameraEnd < SETTLE_MS + 500) {
            userActed = true;
            Diag.put("retro.ritorno", "app memorizzata " + label(savedPkg) + " · non riaperta: premuto Home (" + how + ")");
        }
    }

    /** Il launcher è comparso: come (HOME dal sistema, ritorno nello stack...). Serve alla diagnostica. */
    public static void noteLauncherShown(String how) {
        if (SystemClock.elapsedRealtime() - cameraEnd < 3000 || inCamera) broughtBy = how;
    }

    /** Nuova app in primo piano (evento di accessibilità). */
    public static synchronized void onForeground(Context c, String pkg) {
        if (pkg == null || ignored(c, pkg)) return;
        long now = SystemClock.elapsedRealtime();
        if (isCamera(pkg)) {
            if (!inCamera) {
                inCamera = true;
                cameraSince = now;
                userActed = false;
                broughtBy = "";
                savedPkg = prevPkg != null && !isHomeLike(c, prevPkg) && !isCamera(prevPkg) ? prevPkg : null;
                if (savedPkg == null && lastAppPkg != null && now - lastAppAt < 3000) savedPkg = lastAppPkg;
                Diag.put("retro.ritorno", "telecamera aperta (" + pkg + ") · app memorizzata " + label(savedPkg));
            }
            return;
        }
        if (inCamera) {
            inCamera = false;
            cameraEnd = now;
            long dur = now - cameraSince;
            String first = pkg;
            String saved = savedPkg;
            String head = "app memorizzata " + label(saved) + " · dopo la telecamera " + first;
            if (saved == null) {
                Diag.put("retro.ritorno", head + " · niente da riaprire (prima c'era " + label(prevPkg) + ")");
            } else if (!enabled(c)) {
                Diag.put("retro.ritorno", head + " · non riaperta: impostazione spenta");
            } else if (dur > MAX_REVERSE_MS) {
                Diag.put("retro.ritorno", head + " · non riaperta: retromarcia di " + (dur / 1000) + " s (oltre 3 minuti)");
            } else if (first.equals(saved)) {
                Diag.put("retro.ritorno", head + " · tornata da sola");
            } else if (!isHomeLike(c, first)) {
                Diag.put("retro.ritorno", head + " · non riaperta: davanti c'è un'altra app scelta (" + first + ")");
            } else {
                suppressUntil = now + SUPPRESS_MS;
                Context app = c.getApplicationContext();
                h.postDelayed(() -> reopen(app, saved, head), SETTLE_MS);
            }
        }
        prevPkg = pkg;
        if (!isHomeLike(c, pkg)) {
            lastAppPkg = pkg;
            lastAppAt = now;
        }
    }

    private static boolean enabled(Context c) {
        return c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getBoolean("retroRitorno", true);
    }

    private static void reopen(Context c, String pkg, String head) {
        String top = StartGuard.topPackage(c);
        String how = broughtBy.isEmpty() ? "" : " · launcher riportato da " + broughtBy;
        if (userActed) return;   // già scritto da userPressedHome
        if (top != null && !isHomeLike(c, top)) {
            Diag.put("retro.ritorno", head + how + " · non riaperta: l'utente ha scelto " + top);
            return;
        }
        if (isProjection(pkg)) {
            MediaClient m = media;
            if (m == null || m.last().deviceType == 0) {
                Diag.put("retro.ritorno", head + how + " · non riaperta: CarPlay/Android Auto non più collegato");
                return;
            }
            boolean ok = m.openProjection();
            Diag.put("retro.ritorno", head + how + " · riaperta " + (m.last().deviceType == MediaClient.DEV_AAUTO ? "Android Auto" : "CarPlay")
                    + (ok ? "" : " (non riuscita)"));
            return;
        }
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                Diag.put("retro.ritorno", head + how + " · non riaperta: app non più disponibile");
                return;
            }
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Diag.put("retro.ritorno", head + how + " · riaperta " + pkg);
        } catch (Exception e) {
            Diag.put("retro.ritorno", head + how + " · non riaperta: " + e.getClass().getSimpleName());
        }
    }

    private static String label(String pkg) {
        if (pkg == null) return "nessuna";
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("androidauto")) return "Android Auto (" + pkg + ")";
        if (p.contains("carplay") || p.contains("allgo")) return "CarPlay (" + pkg + ")";
        return pkg;
    }
}
