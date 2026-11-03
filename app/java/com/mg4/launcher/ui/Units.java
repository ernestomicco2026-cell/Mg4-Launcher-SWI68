package com.mg4.launcher.ui;

import com.mg4.launcher.L;
import com.mg4.launcher.svc.Diag;

/**
 * 1.0.0: unità delle distanze. L'auto dà l'autonomia sempre in km (servizio di ricarica) e il navigatore le distanze in
 * metri; se il quadro è in miglia si convertono qui, invece di cambiare solo la scritta.
 * Fonte dell'unità in "Automatica": il quadro (servizio di ricarica, 0 km / 1 miglia), altrimenti il navigatore
 * (1 km, 2 miglia).
 */
public final class Units {
    public static final double KM_TO_MI = 0.621371;
    /** Unità del navigatore MG: 1 km, 2 miglia, -1 non letta. Scritta da NavClient. */
    public static volatile int navUnit = -1;
    private static String lastDiag = "";

    private Units() {}

    /** True se le distanze vanno mostrate in miglia (scelta dell'utente, o come il quadro). */
    public static boolean miles(Prefs p, int quadro) {
        String pref = p.distUnit();
        boolean mi;
        String source;
        if (Prefs.DIST_KM.equals(pref)) {
            mi = false;
            source = "scelta km";
        } else if (Prefs.DIST_MI.equals(pref)) {
            mi = true;
            source = "scelta miglia";
        } else if (quadro >= 0) {
            mi = quadro == 1;
            source = "quadro " + quadro;
        } else if (navUnit > 0) {
            mi = navUnit == 2;
            source = "navigatore " + navUnit;
        } else {
            mi = false;
            source = "nessuna fonte (km)";
        }
        String d = source + " · quadro " + quadro + " · navigatore " + navUnit + " · mostrata " + (mi ? "mi" : "km");
        if (!d.equals(lastDiag)) {
            lastDiag = d;
            Diag.put("veicolo.unitaDistanza", d);
        }
        return mi;
    }

    /** Autonomia (km dall'auto) nell'unità mostrata, arrotondata. */
    public static int range(int km, boolean mi) {
        return mi ? (int) Math.round(km * KM_TO_MI) : km;
    }

    /** Distanza del navigatore: "350 m" / "1,2 km" / "0,3 mi" / "12 mi". */
    public static String distance(int meters, boolean mi) {
        if (!mi) {
            if (meters < 1000) return meters + " m";
            return String.format(L.locale(), meters < 10000 ? "%.1f km" : "%.0f km", meters / 1000f);
        }
        double m = meters / 1000.0 * KM_TO_MI;
        if (m < 0.1) return Math.round(meters * 3.28084 / 10) * 10 + " ft";
        return String.format(L.locale(), m < 10 ? "%.1f mi" : "%.0f mi", m);
    }
}
