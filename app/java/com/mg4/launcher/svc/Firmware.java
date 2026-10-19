package com.mg4.launcher.svc;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Riconosce il firmware dell'auto. Questa versione del launcher è per la SWI68: sulla SWI69 i servizi e le app
 * originali sono diversi (non esistono com.saicmotor.hmi.* e c'è il fornitore di configurazione
 * com.saicmotor.caradapter.featureprovider), quindi lì il launcher non deve tentare nessun collegamento.
 */
public final class Firmware {
    public static final String SWI69_URL = "https://github.com/ernestomicco2026-cell/Mg4-Launcher-SWI69";

    /** App originali della SWI68: se non ce n'è nessuna, non è una SWI68. */
    private static final String[] SWI68_APPS = {
            "com.saicmotor.hmi.launcher", "com.saicmotor.hmi.systemsettings", "com.saicmotor.hmi.radio",
            "com.saicmotor.hmi.hvac", "com.saicmotor.hmi.btcall", "com.saicmotor.hmi.aroundview",
            "com.saicmotor.hmi.vehiclesettings"};

    /** Esito: SWI68 (giusta), SWI69 (versione sbagliata), ALTRO (né l'una né l'altra, es. un telefono). */
    public static final int SWI68 = 0, SWI69 = 1, OTHER = 2;
    private static Integer kind;

    private Firmware() {}

    /** True se questa versione non è adatta al dispositivo: non si tenta nessun collegamento. */
    public static boolean wrong(Context c) {
        return kind(c) != SWI68;
    }

    public static synchronized int kind(Context c) {
        if (kind != null) return kind;
        PackageManager pm = c.getPackageManager();
        boolean provider = CarFeatures.hasFeatureProvider(c);
        int found = 0;
        StringBuilder sb = new StringBuilder();
        for (String p : SWI68_APPS) {
            boolean ok;
            try {
                pm.getPackageInfo(p, 0);
                ok = true;
            } catch (Throwable e) {
                ok = false;
            }
            if (ok) found++;
            else sb.append(sb.length() == 0 ? "" : ", ").append(p.substring(p.lastIndexOf('.') + 1));
        }
        kind = provider ? SWI69 : found == 0 ? OTHER : SWI68;
        Diag.put("firmware", (kind == SWI69 ? "SWI69 (versione sbagliata)" : kind == OTHER ? "non riconosciuto (nessuna app MG)" : "SWI68")
                + " · fornitore config " + (provider ? "sì" : "no")
                + " · app MG " + found + "/" + SWI68_APPS.length + (sb.length() > 0 ? " (mancano: " + sb + ")" : ""));
        return kind;
    }
}
