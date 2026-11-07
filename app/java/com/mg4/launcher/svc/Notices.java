package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 1.0.2 (punto 10c): avvisi da remoto letti da releases/avvisi.json del proprio repository.
 * <pre>
 * { "avvisi": [ { "id": "2026-11-menu", "versioni": ["1.0.0"], "importante": true,
 *     "testo": { "it": "...", "en": "...", ... }, "pulsante": "aggiorna" } ] }
 * </pre>
 * "versioni" vuoto o assente = tutte. Testo nella lingua del launcher, con l'inglese come riserva. Ogni avviso si mostra
 * una volta sola; se "importante" è true si rimostra a ogni accensione finché l'utente non preme OK o non si aggiorna
 * (cambiando versione l'avviso, legato alle versioni, non vale più). File assente o illeggibile = nessun avviso.
 */
public final class Notices {
    public static final class Notice {
        public String id, text;
        public boolean important, updateButton;
    }

    private static final String K_SHOWN = "avvisiMostrati", K_CONFIRMED = "avvisiConfermati";
    private static final Set<String> shownThisPowerOn = new HashSet<>();
    private static String readIds = "—";

    private Notices() {}

    /** Nuova accensione: gli avvisi importanti non confermati possono ricomparire. */
    public static synchronized void newPowerOn() {
        shownThisPowerOn.clear();
    }

    /** Avvisi da mostrare adesso, nell'ordine del file. */
    public static synchronized List<Notice> pending(Context c, JSONArray arr, String version, String lang) {
        List<Notice> out = new ArrayList<>();
        if (arr == null) {
            diag(c);
            return out;
        }
        SharedPreferences sp = sp(c);
        Set<String> shown = sp.getStringSet(K_SHOWN, new HashSet<>());
        Set<String> confirmed = sp.getStringSet(K_CONFIRMED, new HashSet<>());
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < arr.length() && i < 20; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "").trim();
            if (id.isEmpty()) continue;
            ids.append(ids.length() == 0 ? "" : ", ").append(id);
            JSONArray vs = o.optJSONArray("versioni");
            if (vs != null && vs.length() > 0) {
                boolean match = false;
                for (int k = 0; k < vs.length(); k++) if (version.equals(vs.optString(k).trim())) match = true;
                if (!match) continue;
            }
            JSONObject t = o.optJSONObject("testo");
            String text = t == null ? "" : t.optString(lang, "");
            if (text.trim().isEmpty() && t != null) text = t.optString("en", "");
            if (text.trim().isEmpty()) continue;
            boolean important = o.optBoolean("importante", false);
            if (shownThisPowerOn.contains(id)) continue;
            if (important ? confirmed.contains(id) : shown.contains(id)) continue;
            Notice n = new Notice();
            n.id = id;
            n.text = text.trim();
            n.important = important;
            n.updateButton = "aggiorna".equalsIgnoreCase(o.optString("pulsante", ""));
            out.add(n);
        }
        readIds = ids.length() == 0 ? "nessuno" : ids.toString();
        diag(c);
        return out;
    }

    public static synchronized void markShown(Context c, Notice n) {
        shownThisPowerOn.add(n.id);
        add(c, K_SHOWN, n.id);
        diag(c);
    }

    public static synchronized void markConfirmed(Context c, Notice n) {
        add(c, K_CONFIRMED, n.id);
        diag(c);
    }

    private static void add(Context c, String key, String id) {
        SharedPreferences sp = sp(c);
        Set<String> s = new HashSet<>(sp.getStringSet(key, new HashSet<>()));
        if (s.add(id)) sp.edit().putStringSet(key, s).apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
    }

    private static void diag(Context c) {
        SharedPreferences sp = sp(c);
        Diag.put("avvisi.stato", "letti " + readIds + " · mostrati " + sp.getStringSet(K_SHOWN, new HashSet<>())
                + " · confermati " + sp.getStringSet(K_CONFIRMED, new HashSet<>()));
    }
}
