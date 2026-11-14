package com.mg4.launcher.svc;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Disinstallazione del launcher dall'auto, senza computer (0.8.6-beta).
 * Prima rimette la home MG originale e toglie il servizio di accessibilità del launcher, poi chiede ad Android di
 * disinstallare il pacchetto (PackageInstaller.uninstall: DELETE_PACKAGES, concesso dalla firma platform).
 * Se non è possibile si apre la conferma di sistema (ACTION_DELETE).
 * <p>
 * 1.0.4 (issue #29): prima di tutto il segno "disinstallazione in corso" (HomeLock non spegne più la home MG), poi la
 * home MG riattivata, predefinita e verificata (3 tentativi); solo dopo la richiesta di disinstallazione. Se la home
 * MG non torna accesa il launcher NON si toglie. Se la disinstallazione non riesce o è annullata, il segno si toglie.
 */
public final class SelfUninstall {
    private static final String ACTION_RESULT = "com.mg4.launcher.UNINSTALL_RESULT";

    public interface Result {
        void onFailed(String why);
    }

    private SelfUninstall() {
    }

    public static void run(Context c, Result result) {
        Context app = c.getApplicationContext();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        // 0) issue #29: segno "disinstallazione in corso" su disco PRIMA di tutto. Da qui HomeLock non spegne più la
        //    home MG, nemmeno se il launcher torna davanti mentre la conferma di sistema è a schermo.
        HomeLock.beginUninstall(app);
        Diag.put("disinstalla", "richiesta · segno disinstallazione in corso");
        new Thread(() -> {
            // 1) home MG riattivata, rimessa come predefinita e VERIFICATA (fino a 3 tentativi)
            boolean ok = false;
            String home = "";
            for (int i = 1; i <= 3 && !ok; i++) {
                HomeLock.release(app, "disinstalla, tentativo " + i);
                try {
                    HomeDefault.setMg(app);
                } catch (Throwable e) {
                    Diag.error("disinstalla.home", e.toString());
                }
                ok = !HomeLock.mgDisabled(app);
                home = defaultHome(app);
                if (!ok) SystemClock.sleep(700);
            }
            if (!ok) {
                // la home MG non torna accesa: NON si disinstalla, altrimenti l'auto resterebbe senza home
                HomeLock.endUninstall(app);
                Diag.put("disinstalla", "fermata: home MG ancora disattivata dopo 3 tentativi · launcher non tolto");
                HomeLock.apply(app, "disinstallazione fermata");
                main.post(() -> {
                    if (result != null) result.onFailed("home MG non riattivata");
                });
                return;
            }
            Diag.put("disinstalla", "segno · home MG riattivata e verificata · predefinita: " + home);
            main.post(() -> uninstall(app, result));
        }, "mg4-disinstalla").start();
    }

    /** Pacchetto della home predefinita in questo momento, per il log. */
    private static String defaultHome(Context c) {
        try {
            android.content.pm.ResolveInfo r = c.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            return r == null || r.activityInfo == null ? "nessuna" : r.activityInfo.packageName;
        } catch (Throwable e) {
            return "?";
        }
    }

    /** Disinstallazione non riuscita o annullata: via il segno e tutto come prima. */
    private static void undo(Context app, String why, Result result) {
        HomeLock.endUninstall(app);
        Diag.put("disinstalla", "esito: non disinstallato (" + why + ") · segno tolto, launcher come prima");
        new Thread(() -> HomeLock.apply(app, "disinstallazione annullata"), "mg4-homelock").start();
        BootAccessibility.ensureEnabled(app);
        if (result != null) result.onFailed(why);
    }

    private static void uninstall(Context app, Result result) {
        // 2) servizio di accessibilità del launcher tolto dall'elenco (gli altri restano)
        try {
            String me = new ComponentName(app, BootAccessibility.class).flattenToString();
            String cur = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (!TextUtils.isEmpty(cur)) {
                StringBuilder keep = new StringBuilder();
                for (String s : cur.split(":")) {
                    if (s.isEmpty() || s.equalsIgnoreCase(me)) continue;
                    if (keep.length() > 0) keep.append(':');
                    keep.append(s);
                }
                Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, keep.toString());
            }
        } catch (Throwable e) {
            Diag.error("disinstalla.accessibilita", e.toString());
        }
        // 3) disinstallazione
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    Diag.put("disinstalla", "esito " + st + (msg == null ? "" : " " + msg));
                    if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try {
                                ctx.startActivity(confirm);
                                return;   // l'esito arriva dopo la conferma (o l'annullamento)
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    try {
                        ctx.getApplicationContext().unregisterReceiver(this);
                    } catch (Exception ignored) {
                    }
                    if (st != PackageInstaller.STATUS_SUCCESS) undo(app, msg == null ? "codice " + st : msg, result);
                }
            }, new IntentFilter(ACTION_RESULT));
            PendingIntent pi = PendingIntent.getBroadcast(app, 0,
                    new Intent(ACTION_RESULT).setPackage(app.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT);
            app.getPackageManager().getPackageInstaller().uninstall(app.getPackageName(), pi.getIntentSender());
            Diag.put("disinstalla", "inviata a PackageInstaller");
        } catch (Throwable e) {
            Diag.error("disinstalla", e.toString());
            try {
                // conferma di sistema: nessun esito di ritorno, il segno scade da solo dopo 10 minuti
                Intent del = new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + app.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(del);
            } catch (Throwable e2) {
                undo(app, e2.getClass().getSimpleName(), result);
            }
        }
    }
}
