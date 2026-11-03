package com.mg4.launcher.ui;

import android.content.Context;
import android.content.res.Configuration;

/** Colori del launcher (stessi valori del mockup approvato). */
public final class Theme {
    public final boolean day;
    public final int bg, panel, text, sub, track, chip, battery, accent, onAccent, overlay, map, road, roadMinor, glow, shadow;

    private Theme(boolean day) {
        this.day = day;
        if (day) {
            bg = 0xFFE6E8EB; panel = 0xFFFFFFFF; text = 0xFF111318; sub = 0xFF555C66; track = 0xFFE1E4E8;
            chip = 0xFFF1F3F5; battery = 0xFF1E9E5A; overlay = 0xEBFFFFFF; map = 0xFFEEF0F2; road = 0xFFFFFFFF;
            roadMinor = 0xFFE2E5E9; glow = 0xFFC9D3E0; shadow = 0x1A000000;
        } else {
            bg = 0xFF0A0B0D; panel = 0xFF15171B; text = 0xFFF2F3F5; sub = 0xFF9BA1A9; track = 0xFF262A30;
            chip = 0xFF1E2126; battery = 0xFF34C77B; overlay = 0xEB15171B; map = 0xFF1A1D22; road = 0xFF2A2E35;
            roadMinor = 0xFF22252B; glow = 0xFF1F2A3A; shadow = 0x8C000000;
        }
        // nessun colore d'accento: pulsanti e icone usano lo stesso colore del testo
        accent = text;
        onAccent = luminance(accent) > 0.6 ? 0xFF111318 : 0xFFFFFFFF;
    }

    /** 1.0.0: ultimo tema applicato e quando è cambiato (per la diagnostica tema.stato). */
    private static Boolean lastDay;
    private static String lastChange = "—";

    public static Theme resolve(Context c, Prefs p) {
        String mode = p.themeMode();
        boolean day;
        // segnale dell'auto: il sistema passa giorno/notte con la configurazione (uiMode), come per le app originali
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        String read = night == Configuration.UI_MODE_NIGHT_YES ? "notte" : night == Configuration.UI_MODE_NIGHT_NO ? "giorno" : "non definito";
        String source;
        if (Prefs.THEME_DAY.equals(mode)) {
            day = true;
            source = "scelta Giorno";
        } else if (Prefs.THEME_NIGHT.equals(mode)) {
            day = false;
            source = "scelta Notte";
        } else {
            day = night == Configuration.UI_MODE_NIGHT_NO;
            source = "automatico (uiMode di sistema)";
        }
        if (lastDay == null || lastDay != day) {
            lastChange = new java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.ITALY).format(new java.util.Date());
            lastDay = day;
        }
        String mgr = "?";
        try {
            android.app.UiModeManager um = (android.app.UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
            int m = um.getNightMode();
            mgr = m == android.app.UiModeManager.MODE_NIGHT_YES ? "notte" : m == android.app.UiModeManager.MODE_NIGHT_NO ? "giorno"
                    : m == android.app.UiModeManager.MODE_NIGHT_AUTO ? "auto" : String.valueOf(m);
        } catch (Throwable ignored) {
        }
        com.mg4.launcher.svc.Diag.put("tema.stato", source + " · letto " + read + " (UiModeManager " + mgr + ") · applicato "
                + (day ? "giorno" : "notte") + " · ultimo cambio " + lastChange);
        return new Theme(day);
    }

    /** Tema che si applicherebbe adesso (per ricontrollare al ritorno nel launcher). */
    public static boolean wouldBeDay(Context c, Prefs p) {
        String mode = p.themeMode();
        if (Prefs.THEME_DAY.equals(mode)) return true;
        if (Prefs.THEME_NIGHT.equals(mode)) return false;
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_NO;
    }

    private static double luminance(int c) {
        return (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF)) / 255.0;
    }
}
