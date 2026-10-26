package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Avvio sicuro (0.9.6 / 0.3.9 / 0.2.6): la home MG ha una priorità che le app installate non possono battere e il
 * firmware la apre per nome all'accensione. Come fa MG4Dash, si disattiva SOLO la sua schermata principale (il resto
 * dell'app MG, con i suoi servizi, resta attivo): così è Android stesso ad aprire il nostro launcher all'accensione e
 * col tasto Home, senza inseguire la home MG.
 * <p>
 * La schermata MG torna attiva da sola: interruttore "home predefinita" spento, officina, apertura apposta della home
 * MG, "Disinstalla", 3 crash del launcher in 3 minuti ({@link CrashGuard}) e opzione "Avvio sicuro" spenta.
 * Firma platform: il permesso CHANGE_COMPONENT_ENABLED_STATE è concesso dalla firma.
 */
public final class HomeLock {
    public static final String K_ON = "avvioSicuro";

    private HomeLock() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
    }

    /** Opzione "Avvio sicuro" (accesa di default). */
    public static boolean option(Context c) {
        return sp(c).getBoolean(K_ON, true);
    }

    public static void setOption(Context c, boolean on) {
        sp(c).edit().putBoolean(K_ON, on).apply();
        new Thread(() -> apply(c, on ? "opzione accesa" : "opzione spenta"), "mg4-homelock").start();
    }

    /** Schermata principale MG disattivata in questo momento? */
    public static boolean mgDisabled(Context c) {
        try {
            return c.getPackageManager().getComponentEnabledSetting(HomeDefault.MG)
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Stato giusto per le impostazioni attuali: home MG disattivata se launcher attivo + opzione accesa + nessun
     * blocco del salvavita; altrimenti riattivata. Poi riconferma della home predefinita. Da un thread di lavoro.
     */
    public static synchronized void apply(Context c, String why) {
        Context app = c.getApplicationContext();
        boolean want = HomeMode.active(app) && option(app) && !CrashGuard.tripped(app);
        if (want) lock(app, why);
        else release(app, why);
        if (HomeMode.active(app)) HomeDefault.ensure(app);
    }

    private static void lock(Context c, String why) {
        if (mgDisabled(c)) {
            Diag.put("home.avvioSicuro", "attivo (home MG disattivata)");
            return;
        }
        try {
            c.getPackageManager().setComponentEnabledSetting(HomeDefault.MG,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            Diag.put("home.avvioSicuro", "attivato: home MG disattivata (" + why + ")");
        } catch (Throwable e) {
            Diag.put("home.avvioSicuro", "non possibile: " + e);
        }
    }

    /** Riattiva la schermata principale MG (sempre sicuro da chiamare). */
    public static synchronized void release(Context c, String why) {
        Context app = c.getApplicationContext();
        if (!mgDisabled(app)) {
            Diag.putQuiet("home.avvioSicuro", "spento (" + why + ")");
            return;
        }
        try {
            app.getPackageManager().setComponentEnabledSetting(HomeDefault.MG,
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
            Diag.put("home.avvioSicuro", "home MG riattivata (" + why + ")");
        } catch (Throwable e) {
            Diag.put("home.avvioSicuro", "riattivazione non riuscita: " + e);
        }
    }

    /** Altri launcher installati (non nostri, non MG, non di sistema Android): possono contendersi la home. */
    public static List<String> otherLaunchers(Context c) {
        List<String> out = new ArrayList<>();
        try {
            PackageManager pm = c.getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : pm.queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                String p = ri.activityInfo.packageName;
                if (p.equals(c.getPackageName()) || p.startsWith("com.saicmotor") || p.startsWith("com.android")
                        || p.equals("android") || out.contains(p)) continue;
                CharSequence label = ri.loadLabel(pm);
                out.add(label == null ? p : label + " (" + p + ")");
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** true se pkg è un'altra home (MG o un altro launcher): all'accensione si sostituisce come la home MG. */
    public static boolean isOtherHome(Context c, String pkg) {
        if (pkg == null || pkg.equals(c.getPackageName())) return false;
        if (HomeDefault.MG.getPackageName().equals(pkg)) return true;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
            List<ResolveInfo> l = c.getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS);
            return !l.isEmpty() && !pkg.startsWith("com.android");
        } catch (Throwable e) {
            return false;
        }
    }

    /** Il componente esiste su questa auto? (se no, niente da disattivare) */
    public static boolean mgExists(Context c) {
        try {
            c.getPackageManager().getActivityInfo(HomeDefault.MG, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static ComponentName mg() {
        return HomeDefault.MG;
    }
}
