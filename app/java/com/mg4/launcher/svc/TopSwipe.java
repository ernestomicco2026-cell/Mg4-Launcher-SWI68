package com.mg4.launcher.svc;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.mg4.launcher.ui.Cam360;
import com.mg4.launcher.ui.Prefs;

/**
 * 1.0.6 (issue #42): "Consenti lo scorrimento dall'alto a schermo intero" (opzione nelle impostazioni Home, spenta di
 * serie).
 * <p>
 * Il launcher usa già il modo immersivo "sticky" di Android, ma sulla MG4 il gesto dall'alto a schermo intero non fa
 * comparire niente (issue #42). Con l'opzione accesa c'è una striscia invisibile alta 12 dp sul bordo alto, sopra il
 * launcher e le app a schermo intero (finestra del servizio di accessibilità, come "Scorri in su per tornare"):
 * scorrendo in giù per almeno 60 dp si apre la tendina di sistema (impostazioni rapide, con la luminosità) con il
 * comando di Android per i servizi di accessibilità. La barra di stato e la tendina si richiudono da sole come sempre.
 * Mai sopra i pannelli di sistema e la telecamera/360. Log tendina.gesto.
 */
public final class TopSwipe {
    private static final int STRIP_DP = 12, SWIPE_DP = 60;
    private static View strip;
    private static AccessibilityService owner;
    private static String lastLog = "";

    private TopSwipe() {}

    /** App in primo piano cambiata (dal servizio di accessibilità). */
    public static void onForeground(AccessibilityService svc, String pkg) {
        Context c = svc.getApplicationContext();
        String why = blockedBy(c, pkg);
        if (why == null) show(svc, pkg);
        else hide(why);
    }

    /** Opzione cambiata nelle impostazioni: con l'opzione spenta la striscia sparisce subito. */
    public static void refresh(Context c) {
        if (!new Prefs(c).topSwipe()) hide("opzione spenta");
    }

    private static String blockedBy(Context c, String pkg) {
        if (!new Prefs(c).topSwipe()) return "opzione spenta";
        if (pkg == null || pkg.isEmpty()) return "app sconosciuta";
        if ("com.android.systemui".equals(pkg)) return "pannelli di sistema";
        if (Cam360.ORIG_PKG.equals(pkg) || pkg.equals(Cam360.mg4Package(c))) return "telecamera/360";
        String cam = new Prefs(c).cam360();
        if (cam.startsWith(Prefs.CAM_APP) && pkg.equals(cam.substring(Prefs.CAM_APP.length()))) return "telecamera/360 (app scelta)";
        return null;
    }

    private static void show(AccessibilityService svc, String pkg) {
        if (strip != null && owner == svc) {
            log("attiva sopra " + pkg);
            return;
        }
        hide(null);
        try {
            WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
            float d = svc.getResources().getDisplayMetrics().density;
            int h = Math.round(STRIP_DP * d);
            int need = Math.round(SWIPE_DP * d);
            View v = new View(svc);
            final float[] downY = {Float.NaN};
            v.setOnTouchListener((view, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY[0] = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!Float.isNaN(downY[0]) && e.getRawY() - downY[0] >= need) {
                            downY[0] = Float.NaN;
                            openPanel(svc);
                        }
                        return true;
                    default:
                        downY[0] = Float.NaN;
                        return true;
                }
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, h,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            wm.addView(v, lp);
            strip = v;
            owner = svc;
            log("attiva sopra " + pkg);
        } catch (Throwable e) {
            log("striscia non aggiunta: " + e.getClass().getSimpleName());
        }
    }

    private static void hide(String why) {
        if (strip != null && owner != null) {
            try {
                ((WindowManager) owner.getSystemService(Context.WINDOW_SERVICE)).removeView(strip);
            } catch (Throwable ignored) {
            }
        }
        strip = null;
        owner = null;
        if (why != null) log("nascosta: " + why);
    }

    /** Tendina con le impostazioni rapide (luminosità); se il sistema non la apre, la tendina delle notifiche. */
    private static void openPanel(AccessibilityService svc) {
        boolean qs = false, nt = false;
        try {
            qs = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
            if (!qs) nt = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
        } catch (Throwable e) {
            Diag.put("tendina.gesto", "apertura non riuscita: " + e.getClass().getSimpleName());
            return;
        }
        Diag.put("tendina.gesto", "usato: " + (qs ? "impostazioni rapide aperte" : nt ? "tendina delle notifiche aperta" : "il sistema non ha aperto la tendina"));
        lastLog = "";
    }

    private static void log(String txt) {
        if (txt.equals(lastLog)) return;
        lastLog = txt;
        Diag.put("tendina.gesto", txt);
    }
}
