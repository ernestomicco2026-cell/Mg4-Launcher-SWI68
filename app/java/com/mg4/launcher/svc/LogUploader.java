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
 * All’avvio dopo 90 s e ogni 30 minuti. Anche "Salva log" richiede un invio.
 * Senza rete i log restano in coda (al massimo 20) e partono al primo invio riuscito.
 * Il token NON è nel codice: build.sh genera LogConfig da tools/log-token.txt (escluso da git).
 */
public final class LogUploader {
    private final String REPO;
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
        REPO = configuredRepository(ctx);
        sp = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        instance = this;
    }

    /**
     * EN: Read an optional bundled repository override so custom builds send reports to their configured destination.
     * IT: Leggi un repository alternativo incluso, affinche le build personalizzate usino la destinazione configurata.
     */
    private static String configuredRepository(Context context) {
        try (InputStream in = context.getAssets().open("log-repo.txt")) {
            String repo = new String(readAll(in), "UTF-8").trim();
            if (repo.matches("[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+")) return repo;
        } catch (Exception ignored) {}
        return "ernestomicco2026-cell/mg4-launcher-logs";
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
        bg.post(() -> {
            // EN: Remove the old queue once, because earlier logs may contain location-related text.
            // IT: Elimina una volta la vecchia coda, perche i log precedenti possono contenere testo sulla posizione.
            if (!sp.getBoolean("log_location_privacy_v2", false)) {
                File[] legacy = queueDir().listFiles();
                if (legacy != null) for (File f : legacy) f.delete();
                sp.edit().putBoolean("log_location_privacy_v2", true).commit();
            }
        });
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

    public interface ReportListener { void complete(String message); }

    /**
     * EN: Capture the maneuver at the tap, save its sanitized report locally, then attempt upload with retry support.
     * IT: Acquisisci la manovra al tocco, salva localmente il rapporto filtrato, poi tenta l'invio con nuovi tentativi.
     */
    public static void reportMissingIcon(int code, int meters, String text,
                                         ReportListener listener) {
        LogUploader u = instance;
        if (u == null || u.bg == null || !configured()) {
            listener.complete("Log upload is not configured");
            return;
        }
        final Date captured = new Date();
        final String report = ManeuverReport.body(code, meters, text);
        u.bg.post(() -> {
            String message;
            try {
                if (u.notCar()) throw new Exception("Not available on this device");
                String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(captured);
                String time = new SimpleDateFormat("HH-mm-ss-SSS", Locale.ROOT).format(captured);
                String path = FOLDER + "/" + u.installId() + "/" + day + "/missing-icon-" + time
                        + "-" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
                byte[] bytes = report.getBytes("UTF-8");
                if (bytes.length > MAX_BYTES) bytes = Arrays.copyOf(bytes, MAX_BYTES);
                // Persist the exact captured report before attempting network I/O.
                if (!u.enqueue(path, bytes)) throw new Exception("Could not save report locally");
                message = "Missing icon log saved; queued for upload";
                if (Offline.canTry()) {
                    try {
                        u.put(path, bytes);
                        new File(u.queueDir(), path.replace('/', '~')).delete();
                        Offline.ok();
                        message = "Missing icon log sent to developer";
                        u.flushQueue();
                    } catch (Exception e) {
                        Offline.failed("log", e);
                        message = "Upload failed; missing icon log saved for retry";
                    }
                }
            } catch (Exception e) {
                message = "Could not send missing icon log: " + e.getMessage();
            }
            final String result = message;
            new Handler(u.ctx.getMainLooper()).post(() -> listener.complete(result));
        });
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
        if (!Offline.canTry()) {
            // 1.0.0: senza rete il log va in coda senza tentare (pausa comune crescente, vedi rete.offline)
            status = "offline";
            Diag.put("log.invio", "offline: in coda");
            enqueue(path, data);
            return;
        }
        try {
            put(path, data);
            Offline.ok();
            String hhmm = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date());
            status = "ok " + hhmm;
            Diag.put("log.invio", "inviato alle " + hhmm + " → " + path);
            flushQueue();
        } catch (Exception e) {
            status = "errore " + e.getMessage();
            if (Offline.failed("log", e)) Diag.put("log.invio", "offline: in coda");
            else Diag.put("log.invio", "non riuscito (" + Diag.netError(e) + "), in coda");
            enqueue(path, data);
        }
    }

    private boolean enqueue(String path, byte[] data) {
        File dir = queueDir();
        File[] old = dir.listFiles();
        if (old != null && old.length >= MAX_QUEUE) {
            Arrays.sort(old, (a, b) -> a.getName().compareTo(b.getName()));
            for (int i = 0; i <= old.length - MAX_QUEUE; i++) //noinspection ResultOfMethodCallIgnored
                old[i].delete();
        }
        try (OutputStream o = new FileOutputStream(new File(dir, path.replace('/', '~')))) {
            o.write(data);
            return true;
        } catch (Exception e) {
            Diag.error("log.coda", e.toString());
            return false;
        }
    }

    private void flushQueue() {
        File[] files = queueDir().listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : files) {
            try {
                byte[] data = readAll(new FileInputStream(f));
                if (f.getName().contains("missing-icon-")
                        && !new String(data, "UTF-8").matches("(?s)===== MISSING NAVIGATION ICON =====\\nIcon code: -?[0-9]+\\nInstruction type: [a-z_]+\\nDistance meters: -?[0-9]+\\n")) {
                    f.delete(); // Drop legacy reports that included road names / diagnostic dumps.
                    continue;
                }
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
        // EN: HTTP 422 can be a validation error; count a duplicate as sent only if its content hash matches.
        // IT: HTTP 422 puo indicare un errore di validazione; considera inviato un duplicato solo se l'hash coincide.
        if (code == 422) {
            HttpURLConnection check = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/contents/" + path).openConnection();
            check.setConnectTimeout(15000);
            check.setReadTimeout(30000);
            check.setRequestProperty("Authorization", "Bearer " + LogConfig.token());
            check.setRequestProperty("Accept", "application/vnd.github+json");
            check.setRequestProperty("User-Agent", "MG4Launcher");
            try {
                if (check.getResponseCode() == 200) {
                    JSONObject existing = new JSONObject(new String(readAll(check.getInputStream()), "UTF-8"));
                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
                    digest.update(("blob " + data.length + "\0").getBytes("UTF-8"));
                    StringBuilder hash = new StringBuilder();
                    for (byte value : digest.digest(data)) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
                    if (hash.toString().equals(existing.optString("sha"))) return;
                }
            } finally {
                check.disconnect();
            }
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
