package com.mg4.launcher.svc;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diagnostica: ultimi valori letti dai servizi, errori ed eventi recenti.
 * Si vede dalle impostazioni del launcher, su logcat (tag MG4L) e si può salvare in un file di log.
 */
public final class Diag {
    private static final String TAG = "MG4L";
    private static final Map<String, String> VALUES = new TreeMap<>();
    private static final ArrayDeque<String> ERRORS = new ArrayDeque<>();
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();

    private Diag() {}

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date());
    }

    public static synchronized void put(String key, Object value) {
        String v = String.valueOf(value);
        String old = VALUES.put(key, v);
        if (!v.equals(old)) {
            Log.i(TAG, key + " = " + v);
            EVENTS.addLast(now() + "  " + key + " = " + v);
            while (EVENTS.size() > 400) EVENTS.removeFirst();
        }
    }

    /** Come put, ma senza riempire gli eventi (valori che cambiano di continuo, es. corrente). */
    public static synchronized void putQuiet(String key, Object value) {
        VALUES.put(key, String.valueOf(value));
    }

    public static synchronized void error(String where, String msg) {
        String line = now() + "  " + where + ": " + msg;
        Log.w(TAG, line);
        EVENTS.addLast(line);
        while (EVENTS.size() > 400) EVENTS.removeFirst();
        if (!ERRORS.isEmpty() && ERRORS.peekLast().endsWith(where + ": " + msg)) return;
        ERRORS.addLast(line);
        while (ERRORS.size() > 40) ERRORS.removeFirst();
    }

    /**
     * Errore di rete per il log: messaggio con la causa più interna (es. "Unacceptable certificate … ← motivo") e, per gli
     * errori dei certificati, l'orologio dell'auto se è indietro (con la data sbagliata nessun certificato risulta valido).
     */
    public static String netError(Throwable e) {
        StringBuilder sb = new StringBuilder(String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        Throwable c = e.getCause();
        for (int i = 0; c != null && i < 4; i++, c = c.getCause()) {
            String m = c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
            if (sb.indexOf(m) < 0) sb.append(" ← ").append(m);
        }
        boolean cert = false;
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof javax.net.ssl.SSLException || t instanceof java.security.cert.CertificateException) cert = true;
        if (cert && System.currentTimeMillis() < 1735689600000L)   // prima del 2025
            sb.append(" · orologio dell'auto al ").append(new SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(new Date()));
        return sb.toString();
    }

    /** Eccezione con le prime righe dello stack, per il log. */
    public static String trace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String[] lines = sw.toString().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length && i < 12; i++) sb.append(i == 0 ? "" : "\n      ").append(lines[i].trim());
        return sb.toString();
    }

    /** Salva la diagnostica in Android/data/com.mg4.launcher/files/diagnostica.txt (si recupera con adb pull). */
    public static void save(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            String text = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY).format(new Date()) + "\n\n" + dump();
            try (FileOutputStream o = new FileOutputStream(new File(dir, "diagnostica.txt"))) {
                o.write(text.getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "salvataggio diagnostica", e);
        }
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : VALUES.entrySet()) {
            sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        if (!ERRORS.isEmpty()) {
            sb.append("\nERRORI RECENTI\n");
            for (String s : ERRORS) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    private static synchronized String events() {
        StringBuilder sb = new StringBuilder();
        for (String s : EVENTS) sb.append(s).append('\n');
        return sb.toString();
    }

    /** Righe di logcat del launcher (un'app può sempre leggere le proprie). */
    private static String logcat() {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time", "-t", "1500",
                    "--pid=" + android.os.Process.myPid()});
            int skipped = 0, dropped = 0;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.contains("MG4L")) {
                        sb.append(l).append('\n');
                    } else if (l.contains("Skipped ") && l.contains(" frames")) {
                        if (skipped++ == 0) sb.append(l).append('\n'); else dropped++;   // solo il primo
                    } else if (noise(l)) {
                        dropped++;
                    } else {
                        sb.append(l).append('\n');
                    }
                }
            }
            p.waitFor();
            if (dropped > 0) sb.append("(").append(dropped).append(" righe di sistema ripetitive non incluse)\n");
        } catch (Exception e) {
            sb.append("logcat non disponibile: ").append(e).append('\n');
        }
        return sb.toString();
    }

    /** Righe di sistema che non servono a capire i problemi del launcher (si ripetono a centinaia). */
    private static final String[] NOISE = {
            "disconnect failed", "ioctl failed", "jdwp agent", "chatty", "gralloc", "Background concurrent copying",
            "Background young concurrent copying", "Explicit concurrent copying"};

    static boolean noise(String l) {
        for (String n : NOISE) if (l.contains(n)) return true;
        return false;
    }

    /** Crash recenti di tutte le app (buffer crash di logcat, serve READ_LOGS concesso dalla firma platform). */
    private static String crashes() {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-b", "crash", "-v", "time", "-t", "300"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            p.waitFor();
        } catch (Exception e) {
            sb.append("non disponibile: ").append(e).append('\n');
        }
        return sb.length() == 0 ? "nessuno\n" : sb.toString();
    }

    /** Testo completo del log: versione, valori attuali, eventi recenti, logcat del launcher (stesso formato della SWI69). */
    public static String buildLog(Context c) {
        StringBuilder sb = new StringBuilder();
        String version = "?";
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            version = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception ignored) {
        }
        sb.append("MG4 Launcher ").append(version).append('\n');
        sb.append(new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY).format(new Date())).append('\n');
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(") · ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · ").append(Build.DISPLAY).append("\n\n");
        sb.append("===== VALORI ATTUALI =====\n").append(dump()).append('\n');
        sb.append("===== EVENTI RECENTI =====\n").append(events()).append('\n');
        sb.append("===== CRASH RECENTI (tutte le app) =====\n").append(crashes()).append('\n');
        sb.append("===== LOGCAT DEL LAUNCHER =====\n").append(logcat());
        return sb.toString();
    }

    /** Risultato del salvataggio del log: percorsi scritti e testo da mostrare. */
    public static final class Saved {
        public final List<String> paths = new ArrayList<>();
        public boolean onUsb;
    }

    /**
     * Salva un log completo (valori, errori, eventi, logcat del launcher). Lo scrive nella cartella dell'app
     * su ogni memoria disponibile — compresa la chiavetta USB, se inserita — e, se possibile, in Download.
     * Va chiamato fuori dal thread principale.
     */
    public static Saved saveLog(Context c) {
        Saved out = new Saved();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ITALY).format(new Date());
        String name = "mg4-launcher-log-" + stamp + ".txt";
        String text = buildLog(c);
        byte[] data;
        try {
            data = text.getBytes("UTF-8");
        } catch (Exception e) {
            data = text.getBytes();
        }
        LogUploader.requestNow();   // con l'invio automatico attivo, il log va anche nel repository dei log

        List<File> dirs = new ArrayList<>();
        try {
            File[] ext = c.getExternalFilesDirs(null);
            if (ext != null) for (File f : ext) if (f != null) dirs.add(f);
        } catch (Exception ignored) {
        }
        dirs.add(c.getFilesDir());
        File primary = null;
        try {
            primary = c.getExternalFilesDir(null);
        } catch (Exception ignored) {
        }
        for (File dir : dirs) {
            try {
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(data);
                }
                out.paths.add(f.getAbsolutePath());
                boolean removable = false;
                try {
                    removable = Environment.isExternalStorageRemovable(dir);
                } catch (Exception ignored) {
                }
                if (removable || (primary != null && !dir.equals(primary) && dir.getAbsolutePath().startsWith("/storage/")
                        && !dir.getAbsolutePath().startsWith("/storage/emulated"))) out.onUsb = true;
            } catch (Exception e) {
                Log.w(TAG, "log non salvato in " + dir + ": " + e);
            }
        }
        put("log.salvato", out.paths.isEmpty() ? "nessuna cartella scrivibile" : out.paths.get(0));
        return out;
    }
}
