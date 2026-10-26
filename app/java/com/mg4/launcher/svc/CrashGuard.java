package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Salvavita dell'avvio sicuro (come MG4Dash): 3 crash del launcher in 3 minuti → la schermata della home MG si
 * riattiva subito, così lo schermo dell'auto non resta mai senza home. L'avvio sicuro resta sospeso finché
 * l'utente non apre di nuovo il launcher a mano (o non riaccende l'opzione).
 * I dati stanno nella memoria "device protected", leggibile anche prima dello sblocco.
 */
public final class CrashGuard {
    private static final int LIMIT = 3;
    private static final long WINDOW_MS = 3 * 60_000L;
    private static volatile boolean installed;

    private CrashGuard() {}

    private static SharedPreferences sp(Context c) {
        Context d = c.getApplicationContext();
        try {
            d = d.createDeviceProtectedStorageContext();
        } catch (Throwable ignored) {
        }
        return d.getSharedPreferences("mg4salvavita", Context.MODE_PRIVATE);
    }

    /** Avvio sicuro sospeso dopo troppi crash? */
    public static boolean tripped(Context c) {
        try {
            return sp(c).getBoolean("scattato", false);
        } catch (Throwable e) {
            return false;
        }
    }

    /** L'utente ha aperto il launcher a mano: si può riprovare l'avvio sicuro. */
    public static void reset(Context c) {
        try {
            SharedPreferences s = sp(c);
            if (s.getBoolean("scattato", false) || !s.getString("crash", "").isEmpty()) {
                s.edit().putBoolean("scattato", false).putString("crash", "").apply();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Da chiamare all'avvio del processo (attività, servizio di accessibilità, receiver). */
    public static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        Context app = c.getApplicationContext();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                SharedPreferences s = sp(app);
                long now = System.currentTimeMillis(), up = android.os.SystemClock.elapsedRealtime();
                StringBuilder keep = new StringBuilder();
                int n = 1;
                for (String x : s.getString("crash", "").split(",")) {
                    try {
                        long v = Long.parseLong(x);
                        if (up - v < WINDOW_MS && v <= up) {
                            keep.append(v).append(',');
                            n++;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                keep.append(up);
                SharedPreferences.Editor ed = s.edit().putString("crash", keep.toString());
                if (n >= LIMIT) {
                    ed.putBoolean("scattato", true).putLong("scattatoAlle", now);
                    ed.commit();
                    HomeLock.release(app, "salvavita: " + n + " crash in 3 minuti");
                } else {
                    ed.commit();
                }
            } catch (Throwable ignored) {
                // mai nascondere il crash originale
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
