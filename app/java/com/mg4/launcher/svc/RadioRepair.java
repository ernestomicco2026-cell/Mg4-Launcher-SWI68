package com.mg4.launcher.svc;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Riparazione del servizio radio sui firmware SWI68 vecchi (R46/R59, RadioService_eh32_P).
 *
 * Il servizio radio, appena parte, rilegge i promemoria dei programmi DAB salvati nel suo database
 * (ProgramAlarm.resetDabAlarm) e crasha se uno ha l'ora vuota (NullPointerException in setProgramReminder).
 * Il promemoria resta nel database, quindi crasha a ogni avvio. Quando muore con la sessione DAB aperta,
 * system_server chiude la sessione (BroadcastRadioService hal1 Dab.close da binderDied) e va in crash nativo
 * (SIGSEGV in Dab::nativeClose): si riavvia tutto l'infotainment.
 *
 * Se nel buffer crash di logcat c'è questo crash, il launcher cancella i dati del servizio radio
 * (come "pm clear com.saicmotor.service.radio"), solo quando il servizio NON è in esecuzione: così il
 * promemoria rotto sparisce senza far morire il servizio con la sessione DAB aperta.
 */
public final class RadioRepair {
    public static final String PKG = "com.saicmotor.service.radio";
    private static final String PREFS = "radio_repair";
    private static final int MAX_PER_DAY = 3;

    private RadioRepair() {
    }

    /** Ultimo crash "promemoria DAB" trovato nel buffer crash (ms, anno corrente), 0 se nessuno. */
    static long lastAlarmCrash() {
        long last = 0;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-b", "crash", "-v", "time", "-t", "600"});
            String stamp = null;
            boolean radioBlock = false;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.contains("FATAL EXCEPTION")) {
                        stamp = l.length() >= 18 ? l.substring(0, 18) : null;
                        radioBlock = false;
                    } else if (l.contains("Process: " + PKG + ",")) {
                        radioBlock = true;
                    } else if (radioBlock && stamp != null && l.contains("ProgramAlarm.setProgramReminder")) {
                        long t = parse(stamp);
                        if (t > last) last = t;
                        radioBlock = false;
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            Diag.error("radio.riparazione.log", e.toString());
        }
        return last;
    }

    private static long parse(String stamp) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT);
            Calendar c = Calendar.getInstance();
            Calendar s = Calendar.getInstance();
            s.setTime(f.parse(stamp.trim()));
            s.set(Calendar.YEAR, c.get(Calendar.YEAR));
            // un crash di dicembre letto a gennaio è dell'anno prima
            if (s.getTimeInMillis() > c.getTimeInMillis() + 86_400_000L) s.add(Calendar.YEAR, -1);
            return s.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean serviceRunning(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null) return true;   // non si sa: meglio non toccare
            for (ActivityManager.RunningAppProcessInfo pi : list) {
                if (PKG.equals(pi.processName) || (pi.processName != null && pi.processName.startsWith(PKG + ":"))) return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True se c'è un crash del promemoria DAB non ancora riparato (finché è così non si avvia la radio). */
    public static boolean pending(Context ctx) {
        try {
            long crash = lastAlarmCrash();
            return crash > 0 && crash > prefs(ctx).getLong("at", 0);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Controlla e, se serve, ripara. Da chiamare su un thread in background, solo sui firmware vecchi.
     * @return true se il problema c'è ed è ancora da riparare (servizio in esecuzione o riparazione fallita)
     */
    public static boolean check(Context ctx) {
        long crash = lastAlarmCrash();
        SharedPreferences sp;
        try {
            sp = prefs(ctx);
        } catch (Exception e) {
            Diag.error("radio.riparazione", "preferenze non disponibili: " + e);
            return false;
        }
        long done = sp.getLong("at", 0);
        if (crash == 0) {
            Diag.put("radio.riparazione", done == 0 ? "nessun crash dei promemoria DAB" : "riparata " + clock(done) + ", nessun nuovo crash");
            return false;
        }
        if (crash <= done) {
            Diag.put("radio.riparazione", "riparata " + clock(done) + " (ultimo crash promemoria DAB " + clock(crash) + ")");
            return false;
        }
        long now = System.currentTimeMillis();
        long day = sp.getLong("day", 0);
        int count = now - day < 86_400_000L ? sp.getInt("count", 0) : 0;
        if (count >= MAX_PER_DAY) {
            Diag.put("radio.riparazione", "crash promemoria DAB " + clock(crash) + ": già " + count + " riparazioni oggi, mi fermo");
            return true;
        }
        if (serviceRunning(ctx)) {
            Diag.put("radio.riparazione", "crash promemoria DAB " + clock(crash) + ": servizio radio in esecuzione, aspetto che si fermi");
            return true;
        }
        String res = clear(ctx);
        boolean ok = res.startsWith("ok");
        if (ok) {
            sp.edit().putLong("at", now).putLong("day", count == 0 ? now : day).putInt("count", count + 1).apply();
        }
        Diag.put("radio.riparazione", "crash promemoria DAB " + clock(crash) + " → dati del servizio radio cancellati: " + res);
        if (!ok) Diag.error("radio.riparazione", res);
        return !ok;
    }

    /** ActivityManager.clearApplicationUserData(pacchetto, null): serve CLEAR_APP_USER_DATA (firma platform). */
    private static String clear(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            for (java.lang.reflect.Method m : ActivityManager.class.getMethods()) {
                if (m.getName().equals("clearApplicationUserData") && m.getParameterTypes().length == 2
                        && m.getParameterTypes()[0] == String.class) {
                    Object r = m.invoke(am, PKG, null);
                    return Boolean.TRUE.equals(r) ? "ok" : "rifiutata (" + r + ")";
                }
            }
            return "metodo non trovato";
        } catch (Throwable e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            return "errore: " + t;
        }
    }

    private static String clock(long t) {
        return new SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(new java.util.Date(t));
    }
}
