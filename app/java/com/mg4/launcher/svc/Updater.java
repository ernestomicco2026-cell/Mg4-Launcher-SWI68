package com.mg4.launcher.svc;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.mg4.launcher.L;
import com.mg4.launcher.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Aggiornamenti dal repository GitHub: legge l'ultima release, scarica l'APK allegato e lo installa
 * con PackageInstaller (il launcher è firmato con la chiave platform, quindi non serve conferma).
 */
public final class Updater {
    private static final String ACTION_RESULT = "com.mg4.launcher.INSTALL_RESULT";

    public static final class Release {
        public String tag = "";
        public String name = "";
        public String notes = "";
        public String apkUrl;       // URL API dell'allegato
        public String apkName = "";
        public long apkSize;
        public boolean newer;
        boolean raw;                // file del repository (API contents) invece di allegato di release
        String repo = "", path = "";  // per il download di riserva da raw.githubusercontent.com
    }

    public interface Listener {
        /** Messaggio di stato da mostrare (sul thread principale). */
        void onUpdateStatus(String text, Release r, boolean busy);
    }

    private final Context ctx;
    private final HandlerThread thread = new HandlerThread("mg4-update");
    private final Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile boolean busy;

    public Updater(Context c) {
        ctx = c.getApplicationContext();
        thread.start();
        bg = new Handler(thread.getLooper());
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isBusy() {
        return busy;
    }

    public String currentVersion() {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void status(String s, Release r, boolean b) {
        busy = b;
        Diag.put("aggiornamenti", s);
        main.post(() -> {
            if (listener != null) listener.onUpdateStatus(s, r, b);
        });
    }

    /** Controlla l'ultima release del repository. */
    public void check(String repo, String token) {
        if (busy) return;
        status(L.s(R.string.upd_checking), null, true);
        bg.post(() -> {
            try {
                Release r = latest(repo, token);
                if (r.apkUrl == null) {
                    status(L.f(R.string.upd_no_apk, r.tag), r, false);
                } else if (r.newer) {
                    status(L.f(R.string.upd_available, r.tag), r, false);
                } else {
                    status(L.f(R.string.upd_latest, currentVersion(), r.tag), r, false);
                }
            } catch (HttpError e) {
                status(e.getMessage(), null, false);
            } catch (Exception e) {
                status(L.f(R.string.upd_no_connection, e.getClass().getSimpleName()), null, false);
                Diag.error("aggiornamenti", e.toString());
            }
        });
    }

    public interface Found {
        void found(Release r);
    }

    /**
     * Controllo silenzioso in background (per il popup): non tocca lo stato mostrato nelle impostazioni e
     * chiama {@code cb} sul thread principale solo se c'è una versione più nuova con l'APK.
     */
    public void checkQuiet(String repo, String token, Found cb) {
        if (busy) return;
        bg.post(() -> {
            try {
                Release r = latest(repo, token);
                Diag.put("aggiornamenti.auto", "ultima " + r.tag + (r.newer ? " (nuova)" : ""));
                if (r.newer && r.apkUrl != null) main.post(() -> cb.found(r));
            } catch (Exception e) {
                Diag.put("aggiornamenti.auto", "non riuscito: " + e.getClass().getSimpleName() + " (" + Diag.netError(e) + ")");
            }
        });
    }

    /** Controllo silenzioso per le impostazioni: restituisce sempre l'ultima release trovata (anche se non è nuova). */
    public void checkSilent(String repo, String token, Found cb) {
        if (busy) return;
        bg.post(() -> {
            try {
                Release r = latest(repo, token);
                Diag.put("aggiornamenti.controllo", "ultima " + r.tag + (r.newer ? " (nuova)" : ""));
                main.post(() -> cb.found(r));
            } catch (Exception e) {
                Diag.put("aggiornamenti.controllo", "non riuscito: " + e.getClass().getSimpleName() + " (" + Diag.netError(e) + ")");
            }
        });
    }

    /**
     * Scarica l'APK della release e lo installa. L'APK resta in memoria e va direttamente nella sessione
     * di installazione: nessun file temporaneo da salvare (era il punto che non riusciva sull'auto).
     */
    public void install(Release r, String token) {
        if (busy || r == null || r.apkUrl == null) return;
        if (HomeMode.officina(ctx)) return;   // aggiornamenti sospesi durante la modalità officina
        status(L.f(R.string.upd_downloading, r.apkName), r, true);
        bg.post(() -> {
            byte[] apk;
            try {
                apk = downloadWithFallback(r, token);
            } catch (HttpError e) {
                status(e.getMessage(), r, false);
                Diag.error("aggiornamenti.download", e.getMessage());
                return;
            } catch (Exception e) {
                status(L.f(R.string.upd_download_failed, describe(e)), r, false);
                Diag.error("aggiornamenti.download", Diag.trace(e));
                return;
            }
            if (!isApk(apk)) {
                status(L.f(R.string.upd_invalid_apk, apk.length), r, false);
                Diag.error("aggiornamenti.download", "file non valido, " + apk.length + " byte");
                return;
            }
            String version = checkPackage(apk);
            if (version == null) {
                status(L.s(R.string.upd_wrong_package), r, false);
                return;
            }
            status(L.f(R.string.upd_installing, version), r, true);
            try {
                installApk(apk);
            } catch (Exception e) {
                status(L.f(R.string.upd_install_failed, describe(e)), r, false);
                Diag.error("aggiornamenti.installazione", Diag.trace(e));
            }
        });
    }

    private static boolean isApk(byte[] b) {
        return b != null && b.length >= 1024 && b[0] == 'P' && b[1] == 'K';
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : " – " + m);
    }

    /** Prima l'API di GitHub, poi (per i file del repository) raw.githubusercontent.com. */
    private byte[] downloadWithFallback(Release r, String token) throws Exception {
        Exception first;
        try {
            byte[] b = download(r.apkUrl, r, token, r.raw ? "application/vnd.github.raw" : "application/octet-stream");
            if (!r.raw || isApk(b)) return b;
            first = new IOException("non è un APK (" + b.length + " byte)");
        } catch (Exception e) {
            first = e;
        }
        if (!r.raw || r.repo.isEmpty()) throw first;
        Diag.error("aggiornamenti.download", "API GitHub: " + describe(first) + " → provo raw.githubusercontent.com");
        return download("https://raw.githubusercontent.com/" + r.repo + "/main/" + r.path, r, "", "application/octet-stream");
    }

    /**
     * Controlla nome del pacchetto e versione dell'APK scaricato. Serve un file per leggerlo: se non si
     * riesce a scriverlo il controllo si salta (la sessione accetta comunque solo questo pacchetto).
     */
    private String checkPackage(byte[] apk) {
        File[] dirs = {ctx.getCacheDir(), ctx.getFilesDir(), ctx.getExternalCacheDir()};
        for (File dir : dirs) {
            if (dir == null) continue;
            File f = new File(dir, "update-check.apk");
            try {
                if (!dir.exists()) dir.mkdirs();
                try (OutputStream os = new FileOutputStream(f)) {
                    os.write(apk);
                }
                PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
                f.delete();
                if (pi == null) return null;
                if (!ctx.getPackageName().equals(pi.packageName)) return null;
                return pi.versionName;
            } catch (Exception e) {
                Diag.error("aggiornamenti.controllo", dir + ": " + describe(e));
                f.delete();
            }
        }
        return L.s(R.string.upd_new_version);
    }

    // ------------------------------------------------------------------------------------------

    private static final class HttpError extends IOException {
        HttpError(String m) {
            super(m);
        }
    }

    /**
     * Prima la cartella releases/ del repository (latest.json + APK, caricati con un normale push),
     * poi le Release di GitHub.
     */
    private Release latest(String repo, String token) throws Exception {
        Release fromRepo = null;
        HttpError err = null;
        try {
            fromRepo = latestFromRepo(repo, token);
        } catch (HttpError e) {
            err = e;
        }
        Release fromReleases = null;
        try {
            fromReleases = latestRelease(repo, token);
        } catch (HttpError e) {
            if (err == null || fromRepo != null) err = e;
        }
        if (fromRepo != null && (fromReleases == null || fromReleases.apkUrl == null
                || compare(fromRepo.tag, fromReleases.tag) >= 0)) return fromRepo;
        if (fromReleases != null) return fromReleases;
        throw err != null ? err : new HttpError(L.f(R.string.upd_not_found, repo));
    }

    private Release latestFromRepo(String repo, String token) throws Exception {
        String base = "https://api.github.com/repos/" + repo + "/contents/";
        HttpURLConnection c = open(base + "releases/latest.json", token, "application/vnd.github.raw");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw httpError(code, token, repo);
        }
        JSONObject o = new JSONObject(readAll(c.getInputStream()));
        c.disconnect();
        Release r = new Release();
        r.tag = o.optString("version", "");
        r.name = "MG4 Launcher " + r.tag;
        r.notes = o.optString("notes", "");
        String apk = o.optString("apk", "");
        if (!apk.isEmpty()) {
            r.apkUrl = base + apk;
            r.apkName = apk.substring(apk.lastIndexOf('/') + 1);
            r.apkSize = o.optLong("size");
            r.raw = true;
            r.repo = repo;
            r.path = apk;
        }
        r.newer = compare(r.tag, currentVersion()) > 0;
        return r;
    }

    private static HttpError httpError(int code, String token, String repo) {
        if (code == 404) return new HttpError(L.f(R.string.upd_not_found, repo));
        if (code == 401) return new HttpError(L.s(R.string.upd_token_invalid));
        if (code == 403) return new HttpError(L.s(R.string.upd_refused));
        return new HttpError(L.f(R.string.upd_unexpected, code));
    }

    private Release latestRelease(String repo, String token) throws Exception {
        HttpURLConnection c = open("https://api.github.com/repos/" + repo + "/releases/latest", token, "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw httpError(code, token, repo);
        }
        JSONObject o = new JSONObject(readAll(c.getInputStream()));
        c.disconnect();

        Release r = new Release();
        r.tag = o.optString("tag_name", "");
        r.name = o.optString("name", r.tag);
        r.notes = o.optString("body", "");
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").toLowerCase().endsWith(".apk")) {
                    r.apkUrl = a.optString("url");
                    r.apkName = a.optString("name");
                    r.apkSize = a.optLong("size");
                    break;
                }
            }
        }
        r.newer = compare(r.tag, currentVersion()) > 0;
        return r;
    }

    private byte[] download(String url, Release r, String token, String accept) throws Exception {
        // l'allegato risponde con un redirect verso lo storage di GitHub: lo si segue senza il token
        HttpURLConnection c = open(url, token, accept);
        c.setInstanceFollowRedirects(false);
        int code = c.getResponseCode();
        for (int hops = 0; code / 100 == 3 && hops < 5; hops++) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            boolean api = loc != null && loc.startsWith("https://api.github.com/");
            c = open(loc, api ? token : "", api ? accept : "application/octet-stream");
            c.setInstanceFollowRedirects(false);
            code = c.getResponseCode();
        }
        if (code != 200) {
            c.disconnect();
            throw new HttpError(L.f(R.string.upd_download_failed, L.f(R.string.upd_unexpected, code)));
        }
        long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : r.apkSize;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(total > 0 ? (int) total : 512 * 1024);
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[64 * 1024];
            long done = 0, lastShown = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                done += n;
                if (total > 0 && done - lastShown > total / 20) {
                    lastShown = done;
                    status(L.f(R.string.upd_download_pct, (int) Math.min(100, done * 100 / total)), r, true);
                }
            }
        } finally {
            c.disconnect();
        }
        Diag.put("aggiornamenti.scaricati", bo.size() + " byte");
        return bo.toByteArray();
    }

    private void installApk(byte[] apk) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        p.setSize(apk.length);
        // memoria interna esplicita: il firmware MG blocca le app non di sistema sull'archivio interno "automatico"
        p.setInstallLocation(PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
        // come "adb install -r -f": INSTALL_REPLACE_EXISTING (0x2) + INSTALL_INTERNAL (0x10)
        try {
            java.lang.reflect.Field f = PackageInstaller.SessionParams.class.getField("installFlags");
            f.setInt(p, f.getInt(p) | 0x2 | 0x10);
        } catch (Throwable t) {
            Diag.error("aggiornamenti.flag", t.toString());
        }
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream os = s.openWrite("base.apk", 0, apk.length)) {
                os.write(apk);
                s.fsync(os);
            }
            try {
                ctx.registerReceiver(result, new IntentFilter(ACTION_RESULT));
            } catch (Exception ignored) {
            }
            PendingIntent cb = PendingIntent.getBroadcast(ctx, id,
                    new Intent(ACTION_RESULT).setPackage(ctx.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(cb.getIntentSender());
        } catch (Exception e) {
            try {
                pi.abandonSession(id);
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    private final BroadcastReceiver result = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // senza il permesso di sistema Android chiede conferma: si mostra la schermata standard
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                status(L.s(R.string.upd_confirm), null, true);
                return;
            }
            try {
                ctx.unregisterReceiver(this);
            } catch (Exception ignored) {
            }
            if (st == PackageInstaller.STATUS_SUCCESS) {
                status(L.s(R.string.upd_installed), null, false);
            } else {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Diag.error("aggiornamenti.installazione", "stato " + st + ": " + msg);
                status(L.f(R.string.upd_install_failed, msg == null ? L.f(R.string.upd_error_code, st) : msg), null, false);
            }
        }
    };

    private static HttpURLConnection open(String url, String token, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", "MG4Launcher");
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "token " + token);
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    /** Confronta versioni tipo "v0.3.0" e "0.2.0" numero per numero. */
    static int compare(String a, String b) {
        int[] x = nums(a), y = nums(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int[] nums(String v) {
        String[] parts = v.replaceAll("[^0-9.]", "").split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** true se il launcher ha il permesso di installare senza conferma (firmato con la chiave platform). */
    public boolean canInstallSilently() {
        return ctx.checkSelfPermission("android.permission.INSTALL_PACKAGES") == PackageManager.PERMISSION_GRANTED;
    }
}
