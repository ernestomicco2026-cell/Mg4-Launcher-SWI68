package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Invio automatico dei log nel repository privato dei log su GitHub (API "contents", un file per invio):
 *   SWI68/<codice installazione>/<aaaa-mm-gg>/<hh-mm-ss>.txt
 * All'avvio (dopo 90 s, quando i servizi dell'auto hanno risposto), ogni 30 minuti e con "Salva log".
 * Senza rete i log restano in coda (al massimo 20) e partono al primo invio riuscito.
 * Il token NON è nel codice: build.sh genera LogConfig da tools/log-token.txt (escluso da git).
 */
public final class LogUploader {
    private static final String REPO = "ernestomicco2026-cell/mg4-launcher-logs";
    private static final String FOLDER = "SWI68";
    private static final long FIRST_DELAY_MS = 90_000, PERIOD_MS = 30 * 60_000;
    private static final int MAX_QUEUE = 20;
    private static final int MAX_BYTES = 400_000;

    private static volatile LogUploader instance;
    private static volatile String status = "";

    private final Context ctx;
    private final SharedPreferences sp;
    private final HandlerThread thread = new HandlerThread("mg4-log-upload");
    private Handler bg;

    public LogUploader(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        instance = this;
    }

    /** true se questa versione è stata compilata con il token del repository dei log. */
    public static boolean configured() {
        return LogConfig.token().length() > 10;
    }

    public boolean enabled() {
        return sp.getBoolean("log_invio", true);
    }

    public void setEnabled(boolean on) {
        sp.edit().putBoolean("log_invio", on).apply();
        if (on) requestNow();
    }

    /** Stato da mostrare nelle impostazioni (es. "ultimo invio 10:15"). */
    public static String status() {
        return status;
    }

    /** Codice anonimo di questa installazione (non è il numero di telaio). */
    public String installId() {
        String id = sp.getString("log_id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString().substring(0, 8);
            sp.edit().putString("log_id", id).apply();
        }
        return id;
    }

    public void start() {
        if (bg != null) return;
        thread.start();
        bg = new Handler(thread.getLooper());
        if (!configured()) {
            status = "non configurato";
            Diag.put("log.invio", "non configurato in questa versione");
            return;
        }
        if (notCar()) return;
        bg.postDelayed(periodic, FIRST_DELAY_MS);
    }

    public void stop() {
        if (bg != null) bg.removeCallbacksAndMessages(null);
    }

    /** Invia subito un log (es. dopo "Salva log"). */
    public static void requestNow() {
        LogUploader u = instance;
        if (u != null && u.bg != null) u.bg.post(u::uploadOnce);
    }

    private final Runnable periodic = new Runnable() {
        @Override
        public void run() {
            uploadOnce();
            bg.postDelayed(this, PERIOD_MS);
        }
    };

    private File queueDir() {
        File d = new File(ctx.getFilesDir(), "log-coda");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private Boolean notCar;

    /** Telefono, emulatore o firmware non riconosciuto: nessun log (e la coda si svuota). */
    private boolean notCar() {
        if (notCar == null) {
            String why = Firmware.notACar(ctx);
            notCar = why != null;
            if (notCar) {
                status = "disattivato";
                Diag.put("log.invio", "disattivato: " + why);
                File[] q = queueDir().listFiles();
                if (q != null) for (File f : q) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
        }
        return notCar;
    }

    private void uploadOnce() {
        if (!configured() || !enabled() || notCar()) return;
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        String time = new SimpleDateFormat("HH-mm-ss", Locale.ROOT).format(new Date());
        String text = Diag.buildLog(ctx);
        byte[] data;
        try {
            data = text.getBytes("UTF-8");
        } catch (Exception e) {
            data = text.getBytes();
        }
        if (data.length > MAX_BYTES) data = Arrays.copyOfRange(data, data.length - MAX_BYTES, data.length);
        String path = FOLDER + "/" + installId() + "/" + day + "/" + time + ".txt";
        try {
            put(path, data);
            String hhmm = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date());
            status = "ok " + hhmm;
            Diag.put("log.invio", "inviato alle " + hhmm + " → " + path);
            flushQueue();
        } catch (Exception e) {
            status = "errore " + e.getMessage();
            Diag.put("log.invio", "non riuscito (" + Diag.netError(e) + "), in coda");
            enqueue(path, data);
        }
    }

    private void enqueue(String path, byte[] data) {
        File dir = queueDir();
        File[] old = dir.listFiles();
        if (old != null && old.length >= MAX_QUEUE) {
            Arrays.sort(old, (a, b) -> a.getName().compareTo(b.getName()));
            for (int i = 0; i <= old.length - MAX_QUEUE; i++) //noinspection ResultOfMethodCallIgnored
                old[i].delete();
        }
        try (OutputStream o = new FileOutputStream(new File(dir, path.replace('/', '~')))) {
            o.write(data);
        } catch (Exception e) {
            Diag.error("log.coda", e.toString());
        }
    }

    private void flushQueue() {
        File[] files = queueDir().listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : files) {
            try {
                byte[] data = readAll(new FileInputStream(f));
                put(f.getName().replace('~', '/'), data);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } catch (Exception e) {
                Diag.error("log.coda", e.getMessage());
                return;   // riprova al prossimo invio
            }
        }
    }

    /** PUT /repos/{repo}/contents/{path}: crea il file con il contenuto in base64. */
    private void put(String path, byte[] data) throws Exception {
        JSONObject body = new JSONObject();
        body.put("message", "log " + path);
        body.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/contents/" + path).openConnection();
        c.setRequestMethod("PUT");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + LogConfig.token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "MG4Launcher");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.toString().getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        c.disconnect();
        // 422: il file esiste già (reinvio dalla coda di un log già arrivato) → considerato inviato
        if (code == 422) {
            Diag.put("log.giàPresente", path);
            return;
        }
        if (code != 200 && code != 201) throw new Exception("HTTP " + code);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
