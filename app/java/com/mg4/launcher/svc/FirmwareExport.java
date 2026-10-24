package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Base64OutputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Raccolta del software radio originale per analizzarlo (versione beta).
 *
 * Se il servizio radio dell'auto ha una versione che non conosciamo ancora (o è un firmware vecchio), il launcher
 * carica nel repository privato dei log, una volta sola per versione:
 *   SWI68/firmware/<pacchetto>/<versione>/<file>
 * l'APK del servizio radio (com.saicmotor.service.radio) e dell'app Radio (com.saicmotor.hmi.radio), con gli
 * eventuali file oat (odex/vdex), più /system/build.prop. Gli APK di sistema sono leggibili da tutte le app.
 * Se la stessa versione è già nel repository (caricata da un'altra auto) non la ricarica.
 */
public final class FirmwareExport {
    private static final String REPO = "ernestomicco2026-cell/mg4-launcher-logs";
    private static final String FOLDER = "SWI68/firmware";
    private static final String[] PACKAGES = {"com.saicmotor.service.radio", "com.saicmotor.hmi.radio"};
    /** Versioni del servizio radio già analizzate (R46, R69). */
    private static final String[] KNOWN = {"RadioService.44231", "1.0.0_EH32_SOP_2502201114",
            // 0.9.1: analizzate dai file raccolti (3A111 ha i codici da 21 spostati, le altre come R69)
            "RadioService.3A111", "RadioService.4C051", "1.0.0_EH32_SOP_2407251340", "1.0.0_EH32_SOP_TUR_20241128_2501131436"};
    private static final long DELAY_MS = 180_000;
    private static final long MAX_FILE = 40L * 1024 * 1024;

    private FirmwareExport() {
    }

    private static boolean scheduled;

    /** Una volta per processo, 3 minuti dopo l'avvio del launcher. */
    public static synchronized void schedule(Context c) {
        if (scheduled) return;
        scheduled = true;
        final Context ctx = c.getApplicationContext();
        HandlerThread t = new HandlerThread("mg4-fw-export");
        t.start();
        new Handler(t.getLooper()).postDelayed(() -> {
            try {
                run(ctx);
            } catch (Throwable e) {
                Diag.error("firmware.esporta", e.toString());
            }
            t.quitSafely();
        }, DELAY_MS);
    }

    private static void run(Context ctx) throws Exception {
        if (!LogUploader.configured()) {
            Diag.put("firmware.esporta", "invio log non configurato in questa versione");
            return;
        }
        PackageInfo svc;
        try {
            svc = ctx.getPackageManager().getPackageInfo(PACKAGES[0], 0);
        } catch (Exception e) {
            Diag.put("firmware.esporta", "servizio radio assente");
            return;
        }
        String ver = svc.versionName == null ? "?" : svc.versionName;
        for (String k : KNOWN) {
            // 0.8.8-beta: anche le varianti modificate (es. "…-radiofix2") sono già note
            if (ver.equals(k) || ver.startsWith(k + "-")) {
                Diag.put("firmware.esporta", "servizio radio " + ver + " già noto: niente da caricare");
                return;
            }
        }
        SharedPreferences sp = ctx.createDeviceProtectedStorageContext().getSharedPreferences("fw_export", Context.MODE_PRIVATE);
        String doneKey = "done_" + ver + "_" + svc.versionCode;
        if (sp.getBoolean(doneKey, false)) {
            Diag.put("firmware.esporta", "servizio radio " + ver + " già caricato da questa auto");
            return;
        }
        StringBuilder rep = new StringBuilder();
        boolean allOk = true;
        for (String pkg : PACKAGES) {
            PackageInfo pi;
            try {
                pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
            } catch (Exception e) {
                rep.append(pkg).append(": assente; ");
                continue;
            }
            String pv = safe(pi.versionName) + "_" + pi.versionCode;
            String base = FOLDER + "/" + pkg + "/" + pv + "/";
            for (File f : files(pi.applicationInfo.sourceDir)) {
                String path = base + f.getName();
                try {
                    if (f.length() > MAX_FILE) {
                        rep.append(f.getName()).append(" troppo grande; ");
                        continue;
                    }
                    if (exists(path)) {
                        rep.append(f.getName()).append(" già presente; ");
                        continue;
                    }
                    put(path, f);
                    rep.append(f.getName()).append(" ok (").append(f.length() / 1024).append(" KB); ");
                } catch (Exception e) {
                    allOk = false;
                    rep.append(f.getName()).append(" errore ").append(e.getMessage()).append("; ");
                }
                Diag.put("firmware.esporta", "in corso: " + rep);
            }
        }
        File prop = new File("/system/build.prop");
        if (prop.canRead()) {
            String path = FOLDER + "/com.saicmotor.service.radio/" + safe(ver) + "_" + svc.versionCode + "/build.prop";
            try {
                if (!exists(path)) put(path, prop);
            } catch (Exception e) {
                allOk = false;
                rep.append("build.prop errore ").append(e.getMessage()).append("; ");
            }
        }
        if (allOk) sp.edit().putBoolean(doneKey, true).apply();
        Diag.put("firmware.esporta", (allOk ? "fatto → " : "incompleto (riprovo al prossimo avvio) → ") + FOLDER + ": " + rep);
    }

    /** APK più eventuali oat/<abi>/*.odex|*.vdex accanto (app di sistema precompilate). */
    private static List<File> files(String apkPath) {
        List<File> out = new ArrayList<>();
        File apk = new File(apkPath);
        if (apk.canRead()) out.add(apk);
        File oat = new File(apk.getParentFile(), "oat");
        File[] abis = oat.listFiles();
        if (abis != null) {
            for (File abi : abis) {
                File[] fs = abi.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    if (f.isFile() && f.canRead()) {
                        // nome con l'ABI davanti, per non confondere arm e arm64
                        out.add(new Named(f, abi.getName() + "_" + f.getName()));
                    }
                }
            }
        }
        return out;
    }

    /** File con un nome diverso nel repository. */
    private static final class Named extends File {
        private final String alias;

        Named(File f, String alias) {
            super(f.getPath());
            this.alias = alias;
        }

        @Override
        public String getName() {
            return alias;
        }
    }

    private static String safe(String s) {
        return s == null ? "x" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static HttpURLConnection open(String path, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/contents/" + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Authorization", "Bearer " + LogConfig.token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "MG4Launcher");
        return c;
    }

    private static boolean exists(String path) throws Exception {
        HttpURLConnection c = open(path, "GET");
        int code = c.getResponseCode();
        c.disconnect();
        if (code == 200) return true;
        if (code == 404) return false;
        throw new Exception("HTTP " + code);
    }

    /** PUT contents API scrivendo il base64 direttamente nella richiesta (niente copie del file in memoria). */
    private static void put(String path, File f) throws Exception {
        HttpURLConnection c = open(path, "PUT");
        c.setDoOutput(true);
        byte[] head = ("{\"message\":\"firmware " + path + "\",\"content\":\"").getBytes("UTF-8");
        byte[] tail = "\"}".getBytes("UTF-8");
        long b64len = 4 * ((f.length() + 2) / 3);
        // lunghezza esatta: niente invio "chunked", che non tutti i server accettano
        c.setFixedLengthStreamingMode(head.length + b64len + tail.length);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream o = c.getOutputStream(); InputStream in = new FileInputStream(f)) {
            o.write(head);
            Base64OutputStream b64 = new Base64OutputStream(new NoClose(o), Base64.NO_WRAP);
            byte[] buf = new byte[48 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) b64.write(buf, 0, n);
            b64.close();
            o.write(tail);
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code == 422) return;   // già presente
        if (code != 200 && code != 201) throw new Exception("HTTP " + code);
    }

    /** Chiudere il Base64OutputStream non deve chiudere la richiesta HTTP. */
    private static final class NoClose extends java.io.FilterOutputStream {
        NoClose(OutputStream o) {
            super(o);
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws java.io.IOException {
            flush();
        }
    }
}
