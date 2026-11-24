package com.mg4.launcher.svc;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.mg4.launcher.MainActivity;
import com.mg4.launcher.ui.Cam360;
import com.mg4.launcher.ui.Prefs;

/**
 * 1.0.6 (issue SWI69 #2): "Scorri in su per tornare" (opzione nelle impostazioni Home, spenta di serie).
 * <p>
 * Una striscia invisibile alta 12 dp sul bordo basso dello schermo, sopra le altre app: scorrendo in su per almeno 80 dp
 * si torna al launcher. È una finestra del servizio di accessibilità (TYPE_ACCESSIBILITY_OVERLAY, nessun permesso in più)
 * grande solo 12 dp: il resto dello schermo non perde tocchi. Non compare mai sopra il launcher stesso, i pannelli di
 * sistema (com.android.systemui) e la telecamera/360 (originale, MG4 360 o l'app scelta). Log home.gesto.
 */
public final class SwipeBack {
    private static final int STRIP_DP = 12, SWIPE_DP = 80;
    private static View strip;
    private static AccessibilityService owner;
    private static String lastLog = "";

    private SwipeBack() {}

    /** App in primo piano cambiata (dal servizio di accessibilità). */
    public static void onForeground(AccessibilityService svc, String pkg) {
        Context c = svc.getApplicationContext();
        String why = blockedBy(c, pkg);
        if (why == null) show(svc, pkg);
        else hide(why);
    }

    /** Opzione cambiata nelle impostazioni: con l'opzione spenta la striscia sparisce subito. */
    public static void refresh(Context c) {
        if (!new Prefs(c).swipeBack()) hide("opzione spenta");
    }

    private static String blockedBy(Context c, String pkg) {
        if (!new Prefs(c).swipeBack()) return "opzione spenta";
        if (pkg == null || pkg.isEmpty()) return "app sconosciuta";
        if (pkg.equals(c.getPackageName())) return "launcher in primo piano";
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
                        if (!Float.isNaN(downY[0]) && downY[0] - e.getRawY() >= need) {
                            downY[0] = Float.NaN;
                            goHome(svc);
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
            lp.gravity = Gravity.BOTTOM;
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

    private static void goHome(AccessibilityService svc) {
        try {
            Intent i = new Intent(svc, MainActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            svc.startActivity(i);
            Diag.put("home.gesto", "usato: ritorno al launcher");
            lastLog = "";
        } catch (Throwable e) {
            Diag.put("home.gesto", "ritorno non riuscito: " + e.getClass().getSimpleName());
        }
    }

    private static void log(String txt) {
        if (txt.equals(lastLog)) return;
        lastLog = txt;
        Diag.put("home.gesto", txt);
    }
}
