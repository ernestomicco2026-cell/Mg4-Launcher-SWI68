package com.mg4.launcher.svc;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Disinstallazione del launcher dall'auto, senza computer (0.8.6-beta).
 * Prima rimette la home MG originale e toglie il servizio di accessibilità del launcher, poi chiede ad Android di
 * disinstallare il pacchetto (PackageInstaller.uninstall: DELETE_PACKAGES, concesso dalla firma platform).
 * Se non è possibile si apre la conferma di sistema (ACTION_DELETE).
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
        Diag.put("disinstalla", "richiesta");
        // 1) home MG originale come predefinita (prima si riattiva la sua schermata, se l'avvio sicuro l'aveva spenta)
        HomeLock.release(app, "disinstalla");
        try {
            HomeDefault.setMg(app);
        } catch (Throwable e) {
            Diag.error("disinstalla.home", e.toString());
        }
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
                                return;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (st != PackageInstaller.STATUS_SUCCESS && result != null) {
                        result.onFailed(msg == null ? "codice " + st : msg);
                        BootAccessibility.ensureEnabled(ctx);   // disinstallazione non riuscita: si torna come prima
                    }
                }
            }, new IntentFilter(ACTION_RESULT));
            PendingIntent pi = PendingIntent.getBroadcast(app, 0,
                    new Intent(ACTION_RESULT).setPackage(app.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT);
            app.getPackageManager().getPackageInstaller().uninstall(app.getPackageName(), pi.getIntentSender());
            Diag.put("disinstalla", "inviata a PackageInstaller");
        } catch (Throwable e) {
            Diag.error("disinstalla", e.toString());
            try {
                Intent del = new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + app.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(del);
            } catch (Throwable e2) {
                BootAccessibility.ensureEnabled(app);
                if (result != null) result.onFailed(e2.getClass().getSimpleName());
            }
        }
    }
}
