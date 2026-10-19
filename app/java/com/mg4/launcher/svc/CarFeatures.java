package com.mg4.launcher.svc;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;

import java.util.HashMap;
import java.util.Map;

/**
 * Configurazione dell'auto che serve alla grafica della home: lato di guida e spia luci disegnata dal sistema
 * in alto a sinistra. Si prova da più fonti, perché non tutte esistono su ogni firmware:
 * <ul>
 *   <li>impostazione di sistema {@code wheel_position} (1 sinistra, 2 destra);</li>
 *   <li>proprietà del CarService INFO_DRIVER_SEAT (sedile del guidatore: 1 sinistra, 4 destra);</li>
 *   <li>fornitore della configurazione delle app originali ({@code com.saicmotor.caradapter.featureprovider}).</li>
 * </ul>
 * Tutti i valori letti finiscono nella diagnostica ({@code auto.config.*}).
 */
public final class CarFeatures {
    private static final String FEATURE_URI = "content://com.saicmotor.caradapter.featureprovider/feature/key/";
    /** VehicleProperty.INFO_DRIVER_SEAT (Android 9). */
    private static final int INFO_DRIVER_SEAT = 356516106;
    private static final int SEAT_ROW_1_LEFT = 1, SEAT_ROW_1_RIGHT = 4;

    private static final Map<String, String> cache = new HashMap<>();
    private static Boolean rhd, indicator;

    private CarFeatures() {}

    /** Guida a destra secondo l'auto (false se non si riesce a saperlo). */
    public static synchronized boolean isRightHandDrive(Context c) {
        if (rhd != null) return rhd;
        int wheel = -1;
        try {
            wheel = Settings.System.getInt(c.getContentResolver(), "wheel_position", -1);
        } catch (Throwable ignored) {
        }
        int seat = Integer.MIN_VALUE;
        boolean carReady = false;
        try {
            CarProps p = VehicleClient.carProps();
            carReady = p != null && p.ready();
            if (carReady) seat = p.getIntQuiet(INFO_DRIVER_SEAT);
        } catch (Throwable ignored) {
        }
        boolean r = wheel == 2 || seat == SEAT_ROW_1_RIGHT;
        boolean known = wheel == 1 || wheel == 2 || seat == SEAT_ROW_1_LEFT || seat == SEAT_ROW_1_RIGHT;
        Diag.put("auto.config.guida", (r ? "destra" : known ? "sinistra" : "sconosciuta (sinistra)")
                + " · wheel_position " + wheel + " · sedile guida " + (seat == Integer.MIN_VALUE ? "?" : String.valueOf(seat)));
        // se il CarService non era ancora pronto, si riprova al prossimo avvio della home
        if (known || carReady) rhd = r;
        return r;
    }

    private static long lastTry;

    /**
     * Lato di guida appena diventa noto (per ricostruire la home se all'avvio il CarService non era pronto);
     * null finché non si sa. Riprova al massimo ogni 5 secondi.
     */
    public static synchronized Boolean knownRhd(Context c) {
        if (rhd != null) return rhd;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTry < 5000) return null;
        lastTry = now;
        isRightHandDrive(c);
        return rhd;
    }

    /** La barra di sistema disegna sempre la spia luci in alto a sinistra? */
    public static synchronized boolean hasSystemLightIndicator(Context c) {
        if (indicator != null) return indicator;
        String rear = feature(c, "systemui_state_rearfog__");
        String rearNew = feature(c, "systemui_state_rearfognew__");
        String front = feature(c, "systemui_state_frontfog__");
        String frontNew = feature(c, "systemui_state_frontfognew__");
        String hbRear = feature(c, "hiboard_RearFog");
        String hbFront = feature(c, "hiboard_Frontfog");
        boolean on = "1".equals(rear) || "1".equals(rearNew);
        Diag.put("auto.config.spiaLuci", (on ? "sì" : "no") + " · rearfog " + rear + "/" + rearNew + " · frontfog " + front + "/"
                + frontNew + " · hiboard " + hbRear + "/" + hbFront);
        indicator = on;
        return on;
    }

    private static Boolean providerExists;

    /**
     * Il fornitore di configurazione SAIC esiste? Sulla SWI68 no: si verifica una volta sola con
     * resolveContentProvider e poi non lo si interroga più (evita errori a ogni avvio).
     */
    public static synchronized boolean hasFeatureProvider(Context c) {
        if (providerExists == null) {
            boolean ok = false;
            try {
                ok = c.getPackageManager().resolveContentProvider("com.saicmotor.caradapter.featureprovider", 0) != null;
            } catch (Throwable ignored) {
            }
            providerExists = ok;
            Diag.put("auto.config.fornitore", ok ? "presente" : "assente (non interrogato)");
        }
        return providerExists;
    }

    /** Valore di una chiave del fornitore di configurazione, "-" se non esiste. */
    private static String feature(Context c, String key) {
        if (cache.containsKey(key)) return cache.get(key);
        if (!hasFeatureProvider(c)) return "-";
        String v = "-";
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(Uri.parse(FEATURE_URI + key), null, null, null, null);
            if (cur != null && cur.moveToFirst() && cur.getColumnCount() > 0) {
                String s = cur.getString(0);
                if (s != null) v = s.trim();
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        cache.put(key, v);
        return v;
    }
}
