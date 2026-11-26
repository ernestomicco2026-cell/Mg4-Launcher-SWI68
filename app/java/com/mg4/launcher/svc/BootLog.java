package com.mg4.launcher.svc;

import android.content.Context;
import android.os.SystemClock;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 1.0.6 (issue #43, punto A4): riga avvio.cronologia con i secondi dall'accensione dei passi dell'avvio. Accensione =
 * avvio di Android (elapsedRealtime 0) oppure risveglio dello schermo dopo una sospensione (powerOn). Ogni passo si
 * segna solo la prima volta per accensione; la riga si riscrive a ogni passo nuovo.
 */
public final class BootLog {
    private static final String[][] STEPS = {
            {"launcher", "launcher partito"},
            {"avvioSicuro", "avvio sicuro (home MG spenta)"},
            {"allgo", "servizi AllGo attivi"},
            {"chiavetta", "chiavetta USB vista"},
            {"radio", "servizio radio pronto"},
            {"ripresa", "ripresa della sorgente"},
            {"dongle", "intervento del dongle"}};

    private static long t0 = 0;
    private static String origin = "avvio di Android";
    private static final Map<String, String> marks = new LinkedHashMap<>();

    private BootLog() {}

    /** 1.0.7: istante (elapsedRealtime) dell'ultima accensione: avvio di Android (0) o ultimo risveglio dello schermo. */
    public static synchronized long powerOnAt() {
        return t0;
    }

    /** Risveglio dello schermo dopo una sospensione: nuova accensione, la cronologia riparte da zero. */
    public static synchronized void powerOn(String why) {
        t0 = SystemClock.elapsedRealtime();
        origin = why;
        marks.clear();
        marks.put("launcher", "già attivo");
        write();
    }

    /** Passo raggiunto adesso (la prima volta in questa accensione). note facoltativa, senza dati personali. */
    public static void mark(Context c, String step, String note) {
        markAt(step, SystemClock.elapsedRealtime(), note);
    }

    public static synchronized void markAt(String step, long elapsed, String note) {
        if (marks.containsKey(step)) return;
        long ms = Math.max(0, elapsed - t0);
        marks.put(step, String.format(Locale.US, "%.1f s", ms / 1000f) + (note == null || note.isEmpty() ? "" : " (" + note + ")"));
        write();
    }

    private static void write() {
        StringBuilder sb = new StringBuilder("da " + origin);
        for (String[] s : STEPS) {
            String v = marks.get(s[0]);
            sb.append(" · ").append(s[1]).append(' ').append(v == null ? "—" : v);
        }
        Diag.put("avvio.cronologia", sb.toString());
    }
}
