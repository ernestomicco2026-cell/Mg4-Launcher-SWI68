package com.mg4.launcher.svc;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * 1.0.4 (punto D): MG4 Store dentro il launcher (assets/MG4Store.apk, firmato con la stessa chiave platform).
 * <ul>
 *   <li>Alla prima accensione con un launcher che lo contiene, lo Store si installa da solo, in silenzio, SOLO se manca
 *       o è più vecchio di quello contenuto. Succede una volta sola: dopo lo Store si aggiorna da solo e il launcher non
 *       lo tocca più, nemmeno se una versione nuova del launcher contiene uno Store più vecchio.</li>
 *   <li>Se l'utente lo disinstalla, il launcher non lo rimette: c'è "Installa MG4 Store" nelle impostazioni.</li>
 * </ul>
 * Log "store": installato vN / installato ora / installazione non riuscita (motivo) / tolto dall'utente.
 */
public final class StoreSetup {
    public static final String PKG = "com.mg4.store", ASSET = "MG4Store.apk";
    private static final String K_DONE = "storeGestito", ACTION = "com.mg4.launcher.STORE_INSTALL.";

    public interface Done {
        void done(boolean ok, String message);
    }

    private StoreSetup() {}

    /** Versione installata dello Store (versionCode), -1 se non c'è. */
    public static long installedCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(PKG, 0).versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(PKG, 0).versionName;
        } catch (Exception e) {
            return null;
        }
    }

    /** {versionCode, versionName} dello Store contenuto nel launcher, null se manca. */
    static Object[] bundled(Context c) {
        java.io.File tmp = new java.io.File(c.getCacheDir(), "store-check.apk");
        try {
            copyAsset(c, tmp);
            PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(tmp.getAbsolutePath(), 0);
            return pi == null ? null : new Object[]{(long) pi.versionCode, pi.versionName, tmp.length()};
        } catch (Exception e) {
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Avvio del launcher: installazione automatica una volta sola, poi solo il log. Va chiamato fuori dal thread principale. */
    public static void onStart(Context ctx) {
        Context c = ctx.getApplicationContext();
        SharedPreferences sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        long have = installedCode(c);
        if (sp.getBoolean(K_DONE, false)) {
            Diag.put("store", have >= 0 ? "installato v" + installedName(c) + " (si aggiorna da solo)" : "tolto dall'utente (si rimette da Impostazioni → Installa MG4 Store)");
            return;
        }
        Object[] b = bundled(c);
        if (b == null) {
            Diag.put("store", "nessuno Store dentro il launcher");
            return;
        }
        long code = (Long) b[0];
        if (have >= code) {
            sp.edit().putBoolean(K_DONE, true).apply();   // da qui in poi il launcher non lo tocca più da solo
            Diag.put("store", "installato v" + installedName(c) + " (uguale o più nuovo di quello nel launcher, v" + b[1] + ")");
            return;
        }
        String before = installedName(c);
        install(c, false, (ok, msg) -> {
            // il segno "fatto" solo se è andata bene: se non riesce si riprova alla prossima accensione
            if (ok) sp.edit().putBoolean(K_DONE, true).apply();
            Diag.put("store", ok ? "installato ora v" + b[1] + (before != null ? " (prima v" + before + ")" : "")
                    : "installazione non riuscita (" + msg + ") · si riprova alla prossima accensione");
        });
    }

    /**
     * Installa lo Store contenuto nel launcher. byUser = true (pulsante nelle impostazioni): se Android chiede una
     * conferma la mostra; all'accensione no.
     */
    public static void install(Context ctx, boolean byUser, Done done) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            java.io.File tmp = new java.io.File(app.getCacheDir(), ASSET);
            try {
                copyAsset(app, tmp);
                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(PKG);
                p.setSize(tmp.length());
                // memoria interna: il firmware MG blocca le app non di sistema sull'archivio "automatico"
                p.setInstallLocation(PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
                try {   // INSTALL_REPLACE_EXISTING (0x2) + INSTALL_INTERNAL (0x10), come "adb install -r"
                    java.lang.reflect.Field f = PackageInstaller.SessionParams.class.getField("installFlags");
                    f.setInt(p, f.getInt(p) | 0x2 | 0x10);
                } catch (Throwable ignored) {
                }
                int id = pi.createSession(p);
                String action = ACTION + id;
                BroadcastReceiver rcv = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                            if (byUser && confirm != null) {
                                app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                                return;   // l'esito arriva dopo la conferma
                            }
                            try {   // all'accensione nessuna conferma a schermo: la sessione si chiude
                                pi.abandonSession(id);
                            } catch (Exception ignored) {
                            }
                        }
                        try {
                            app.unregisterReceiver(this);
                        } catch (Exception ignored) {
                        }
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                        String msg = st == PackageInstaller.STATUS_PENDING_USER_ACTION ? "Android chiede una conferma"
                                : i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                        done.done(st == PackageInstaller.STATUS_SUCCESS, msg == null ? "codice " + st : msg);
                    }
                };
                app.registerReceiver(rcv, new IntentFilter(action));
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = new java.io.FileInputStream(tmp); OutputStream os = s.openWrite("base.apk", 0, tmp.length())) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                        s.fsync(os);
                    }
                    PendingIntent pend = PendingIntent.getBroadcast(app, id, new Intent(action).setPackage(app.getPackageName()),
                            PendingIntent.FLAG_UPDATE_CURRENT);
                    s.commit(pend.getIntentSender());
                } catch (Exception e) {
                    try {
                        app.unregisterReceiver(rcv);
                    } catch (Exception ignored) {
                    }
                    try {
                        pi.abandonSession(id);
                    } catch (Exception ignored) {
                    }
                    throw e;
                }
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                done.done(false, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
        }, "mg4-store").start();
    }

    private static void copyAsset(Context c, java.io.File to) throws java.io.IOException {
        try (InputStream in = c.getAssets().open(ASSET); OutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
