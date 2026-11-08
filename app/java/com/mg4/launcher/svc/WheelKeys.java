package com.mg4.launcher.svc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * 1.0.3: tasti al volante. Sulla SWI68 SystemUI li manda come broadcast com.android.systemui.ACTION_HARD_KEY_EVENT con
 * gli extra KEY_CODE (1 volume +, 2 volume −, 3 muto, 4 indietro, 5 avanti: codici SAIC, non KeyEvent), DOWN e
 * LONG_PRESS. Il servizio media SAIC li usa già per avanti/indietro su USB (entrambe le porte) e Bluetooth: qui si
 * ascoltano soltanto, per il log media.tastiVolante e per la prova del Controllo funzioni. Nessun comando viene inviato.
 */
public final class WheelKeys {
    public static final String ACTION = "com.android.systemui.ACTION_HARD_KEY_EVENT";

    public interface Listener {
        void onKey(int code, boolean down, boolean longPress);
    }

    private static boolean registered;
    private static volatile Listener probe;
    private static int count;
    private static final java.util.TreeMap<Integer, Integer> perCode = new java.util.TreeMap<>();

    private WheelKeys() {}

    public static synchronized void start(Context c) {
        if (registered) return;
        try {
            c.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    int code = i.getIntExtra("KEY_CODE", -1);
                    boolean down = i.getBooleanExtra("DOWN", false), lp = i.getBooleanExtra("LONG_PRESS", false);
                    note(code, down, lp);
                    Listener l = probe;
                    if (l != null) l.onKey(code, down, lp);
                }
            }, new IntentFilter(ACTION));
            registered = true;
            Diag.put("media.tastiVolante", "in ascolto (" + ACTION + ") · nessun tasto visto finora");
        } catch (Throwable e) {
            Diag.error("media.tastiVolante", e.toString());
        }
    }

    /** Prova del Controllo funzioni: riceve i tasti finché non si rimette null. */
    public static void setProbe(Listener l) {
        probe = l;
    }

    public static String name(int code) {
        switch (code) {
            case 1: return "volume +";
            case 2: return "volume −";
            case 3: return "muto";
            case 4: return "indietro";
            case 5: return "avanti";
            default: return "codice " + code;
        }
    }

    private static synchronized void note(int code, boolean down, boolean lp) {
        count++;
        Integer n = perCode.get(code);
        perCode.put(code, n == null ? 1 : n + 1);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Integer, Integer> e : perCode.entrySet()) {
            sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey()).append(" ").append(name(e.getKey())).append(" ×").append(e.getValue());
        }
        Diag.put("media.tastiVolante", "ultimo KEY_CODE " + code + " (" + name(code) + ") DOWN " + down + " LONG_PRESS " + lp
                + " · sorgente " + MediaClient.currentSourceName() + " · visti " + count + " eventi: " + sb
                + " · avanti/indietro li gestisce il servizio media SAIC (USB 1, USB 2, Bluetooth)");
    }
}
