package com.mg4.launcher.svc;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;

/**
 * 1.0.4 (issue #26): disinstallazione di un'app dall'elenco App (pressione lunga → "Disinstalla").
 * Solo app installate dall'utente: app di sistema (anche aggiornate), app MG (com.saicmotor.*) e nostre app (com.mg4.*)
 * sono protette. Usa il permesso DELETE_PACKAGES che il launcher ha già (firma platform); se Android chiede comunque
 * una conferma la mostra. Log "app.disinstallata" = pacchetto · esito.
 */
public final class AppUninstall {
    public interface Done {
        void done(boolean ok);
    }

    private static final String ACTION = "com.mg4.launcher.APP_UNINSTALL.";
    private static int seq;

    private AppUninstall() {}

    /** true se l'app si può togliere dall'elenco App. */
    public static boolean allowed(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (pkg.startsWith("com.saicmotor") || pkg.startsWith("com.mg4.") || pkg.equals(c.getPackageName())) return false;
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void uninstall(Context ctx, String pkg, Done done) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        if (!allowed(app, pkg)) {
            Diag.put("app.disinstallata", pkg + " · rifiutata (app protetta)");
            main.post(() -> done.done(false));
            return;
        }
        String action = ACTION + (++seq);
        BroadcastReceiver rcv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        try {
                            app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            return;   // l'esito arriva dopo la conferma
                        } catch (Exception ignored) {
                        }
                    }
                }
                try {
                    app.unregisterReceiver(this);
                } catch (Exception ignored) {
                }
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                boolean ok = st == PackageInstaller.STATUS_SUCCESS;
                Diag.put("app.disinstallata", pkg + " · " + (ok ? "ok" : "non riuscita (" + (msg == null ? "codice " + st : msg) + ")"));
                done.done(ok);
            }
        };
        try {
            app.registerReceiver(rcv, new IntentFilter(action));
            PendingIntent pi = PendingIntent.getBroadcast(app, seq, new Intent(action).setPackage(app.getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            app.getPackageManager().getPackageInstaller().uninstall(pkg, pi.getIntentSender());
        } catch (Throwable e) {
            try {
                app.unregisterReceiver(rcv);
            } catch (Exception ignored) {
            }
            Diag.put("app.disinstallata", pkg + " · non riuscita (" + e.getClass().getSimpleName() + ")");
            main.post(() -> done.done(false));
        }
    }
}
