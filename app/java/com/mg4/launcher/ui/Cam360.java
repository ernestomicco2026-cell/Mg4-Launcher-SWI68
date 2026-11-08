package com.mg4.launcher.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.mg4.launcher.svc.Diag;

/**
 * 1.0.0: cosa apre il tasto 360 della barra. Scelte: 360 originale (predefinito), MG4 360 (solo se installata),
 * un'altra app installata, nascosto. Se l'auto non ha la telecamera 360 il predefinito è "nascosto".
 */
public final class Cam360 {
    public static final String ORIG_PKG = "com.saicmotor.hmi.aroundview";
    public static final String ORIG_CLS = "com.saicmotor.hmi.aroundview.AVMActivity";

    public static final int ORIGINAL = 0, MG4 = 1, APP = 2, HIDDEN = 3;

    /** Cosa fare: tipo, pacchetto e attività da aprire (cls null = attività principale del pacchetto). */
    public static final class Target {
        public final int kind;
        public final String pkg, cls, label;

        Target(int kind, String pkg, String cls, String label) {
            this.kind = kind;
            this.pkg = pkg;
            this.cls = cls;
            this.label = label;
        }
    }

    private static String lastDiag = "";

    private Cam360() {}

    /** True se c'è l'app originale della telecamera 360 (l'auto ha la telecamera). */
    public static boolean hasOriginal(Context c) {
        return installed(c, ORIG_PKG);
    }

    /** Pacchetto di MG4 360 se installata (com.mg4.* con "360" nel nome o nell'etichetta), altrimenti null. */
    public static String mg4Package(Context c) {
        PackageManager pm = c.getPackageManager();
        try {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!pkg.startsWith("com.mg4.") || pkg.equals(c.getPackageName())) continue;
                String label = String.valueOf(ri.loadLabel(pm));
                if (pkg.contains("360") || label.contains("360")) return pkg;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean launchable(Context c, String pkg) {
        try {
            return c.getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) {
            return false;
        }
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Scelta effettiva; se l'app scelta non c'è più si torna al predefinito (e lo si scrive nella diagnostica). */
    public static Target resolve(Context c, Prefs p) {
        String v = p.cam360();
        boolean orig = hasOriginal(c);
        Target t;
        String note = "";
        if (Prefs.CAM_HIDDEN.equals(v)) {
            t = new Target(HIDDEN, null, null, "");
        } else if (Prefs.CAM_MG4.equals(v)) {
            String pkg = mg4Package(c);
            if (pkg != null) t = new Target(MG4, pkg, null, label(c, pkg));
            else {
                t = def(orig);
                note = " (MG4 360 non installata: predefinito)";
            }
        } else if (v.startsWith(Prefs.CAM_APP)) {
            String pkg = v.substring(Prefs.CAM_APP.length());
            // 1.0.3: se l'app scelta non c'è più (o non si può aprire) torna il tasto predefinito, senza crash
            if (!pkg.isEmpty() && installed(c, pkg) && launchable(c, pkg)) t = new Target(APP, pkg, null, label(c, pkg));
            else {
                t = def(orig);
                note = " (" + (pkg.isEmpty() ? "app vuota" : pkg) + " non installata o non apribile: predefinito)";
            }
        } else if (Prefs.CAM_ORIGINAL.equals(v)) {
            t = orig ? new Target(ORIGINAL, ORIG_PKG, ORIG_CLS, "") : def(false);
            if (!orig) note = " (360 originale assente: nascosto)";
        } else {
            t = def(orig);
        }
        String kind = t.kind == ORIGINAL ? "360 originale" : t.kind == MG4 ? "MG4 360 " + t.pkg
                : t.kind == APP ? "app " + t.pkg : "nascosto";
        String d = "scelta " + (v.isEmpty() ? "predefinita" : v) + " → " + kind + note + " · telecamera originale "
                + (orig ? "presente" : "assente") + " · MG4 360 " + (mg4Package(c) != null ? "installata" : "assente");
        if (!d.equals(lastDiag)) {
            lastDiag = d;
            Diag.put("barra.tasto360", d);
        }
        return t;
    }

    private static Target def(boolean orig) {
        return orig ? new Target(ORIGINAL, ORIG_PKG, ORIG_CLS, "") : new Target(HIDDEN, null, null, "");
    }
}
