package com.mg4.launcher.svc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Usa come home predefinita" e "Modalità officina".
 * <ul>
 *   <li>Interruttore spento (permanente): home predefinita = home MG, niente avvio all'accensione, niente
 *       riconferma della home. Il launcher resta usabile come app normale.</li>
 *   <li>Officina (1/3/7 giorni): salva lo stato dell'interruttore, lo spegne, sospende aggiornamenti e popup.
 *       Finisce alla scadenza (controllata all'accensione e periodicamente), se l'utente la annulla aprendo il
 *       launcher, o se riaccende l'interruttore; alla fine l'interruttore torna com'era.</li>
 * </ul>
 */
public final class HomeMode {
    private static final String K_ENABLED = "homePredefinita", K_UNTIL = "officinaFinoA", K_PREV = "officinaHomePrima";

    private HomeMode() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
    }

    /** Valore dell'interruttore (spento anche durante l'officina). */
    public static boolean enabled(Context c) {
        return sp(c).getBoolean(K_ENABLED, true) && !officina(c);
    }

    /** Logica di avvio e riconferma della home attive? */
    public static boolean active(Context c) {
        return enabled(c);
    }

    public static String reason(Context c) {
        if (officina(c)) return "officina";
        return sp(c).getBoolean(K_ENABLED, true) ? "attiva" : "interruttore spento";
    }

    /** Officina attiva (la scadenza la chiude da sola). */
    public static boolean officina(Context c) {
        long until = sp(c).getLong(K_UNTIL, 0);
        if (until <= 0) return false;
        if (System.currentTimeMillis() >= until) {
            end(c, "scadenza");
            return false;
        }
        return true;
    }

    public static long officinaUntil(Context c) {
        return sp(c).getLong(K_UNTIL, 0);
    }

    public static String officinaDate(Context c) {
        return new SimpleDateFormat("dd/MM", Locale.ITALY).format(new Date(officinaUntil(c)));
    }

    /** Controllo della scadenza (all'accensione e periodicamente). */
    public static void checkExpiry(Context c) {
        officina(c);
    }

    /** Interruttore "Usa come home predefinita". Riaccenderlo durante l'officina la annulla. */
    public static void setEnabled(Context c, boolean on) {
        if (on && sp(c).getLong(K_UNTIL, 0) > 0) {
            sp(c).edit().putBoolean(K_PREV, true).apply();
            end(c, "interruttore");
            return;
        }
        sp(c).edit().putBoolean(K_ENABLED, on).apply();
        apply(c, on);
        if (on) openOurs(c);
        else openMg(c);
    }

    /** Attiva l'officina per alcuni giorni. */
    public static void startOfficina(Context c, int days) {
        boolean prev = sp(c).getBoolean(K_ENABLED, true);
        long until = System.currentTimeMillis() + days * 24L * 60 * 60 * 1000;
        sp(c).edit().putBoolean(K_PREV, prev).putBoolean(K_ENABLED, false).putLong(K_UNTIL, until).apply();
        Diag.put("officina", "attiva fino al " + new SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(new Date(until)));
        apply(c, false);
        openMg(c);
    }

    /** Fine dell'officina: interruttore com'era, aggiornamenti di nuovo attivi, launcher riaperto se serve. */
    public static void end(Context c, String why) {
        SharedPreferences s = sp(c);
        if (s.getLong(K_UNTIL, 0) <= 0) return;
        boolean prev = s.getBoolean(K_PREV, true);
        s.edit().remove(K_UNTIL).remove(K_PREV).putBoolean(K_ENABLED, prev).apply();
        Diag.put("officina", "disattiva (motivo: " + why + ")");
        apply(c, prev);
        if (prev) openOurs(c);
    }

    /** Effetto dell'interruttore sulla home predefinita. */
    private static void apply(Context c, boolean on) {
        new Thread(() -> {
            if (on) {
                HomeLock.apply(c, "interruttore acceso");
            } else {
                HomeLock.release(c, officina(c) ? "officina" : "interruttore spento");
                HomeDefault.setMg(c);
            }
            diag(c);
        }, "mg4-home").start();
    }

    public static void diag(Context c) {
        String v = active(c) ? "nostra" : "MG (" + (officina(c) ? "officina" : "interruttore spento") + ")";
        Diag.put("home.predefinita", v);
    }

    private static void openMg(Context c) {
        HomeLock.release(c, "home MG aperta");
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(HomeDefault.MG)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable e) {
            Diag.error("home MG", e.toString());
        }
    }

    public static void openOurs(Context c) {
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(HomeDefault.ours(c))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Throwable e) {
            Diag.error("avvio launcher", e.toString());
        }
    }
}
