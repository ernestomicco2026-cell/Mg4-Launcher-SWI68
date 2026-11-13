package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 1.0.4 (punto F): pacchetti dell'animazione di ricarica. Le clip NON sono nell'APK: si scarica solo lo zip del colore
 * scelto nelle impostazioni, da releases/animazioni.json di questo repository (pacchetti nella cartella animazioni/ del repository).
 * <ul>
 *   <li>in background, con ripresa se il download si interrompe (Range), controllo sha256 e dimensione;</li>
 *   <li>se si cambia colore si scarica il nuovo e si cancella il vecchio;</li>
 *   <li>se animazioni.json o il pacchetto non ci sono: niente animazione, nessun errore a schermo.</li>
 * </ul>
 * Le 8 clip si estraggono in files/animazione/&lt;colore&gt;/ (carica-&lt;colore&gt;-&lt;giorno|notte&gt;-&lt;fase&gt;.mp4).
 */
public final class ChargePack {
    public static final String[] PHASES = {"aggancio", "loop", "finita", "sgancio"};
    private static final String JSON_URL = Updater.OWN_RAW + "releases/animazioni.json";
    private static final long CHECK_EVERY_MS = 12 * 60 * 60_000L;

    private static volatile String state = "non controllato";
    private static volatile boolean busy;
    private static volatile boolean failed;

    private ChargePack() {}

    /** "Arctic White" → "arctic-white" (nomi dei file e degli zip). */
    public static String slug(String color) {
        return color.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    public static File root(Context c) {
        return new File(c.getFilesDir(), "animazione");
    }

    public static File dir(Context c, String color) {
        return new File(root(c), slug(color));
    }

    /** Clip di una fase, null se il pacchetto del colore non è completo. */
    public static File clip(Context c, String color, boolean day, String phase) {
        File f = new File(dir(c, color), "carica-" + slug(color) + "-" + (day ? "giorno" : "notte") + "-" + phase + ".mp4");
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /** True se ci sono tutte le 8 clip del colore. */
    public static boolean complete(Context c, String color) {
        for (String ph : PHASES) {
            if (clip(c, color, true, ph) == null || clip(c, color, false, ph) == null) return false;
        }
        return true;
    }

    public static String state() {
        return state;
    }

    public static boolean failed() {
        return failed;
    }

    public static boolean busy() {
        return busy;
    }

    /**
     * Controlla il pacchetto del colore scelto e, se serve, lo scarica (thread a parte). force = true salta l'attesa
     * tra un controllo e l'altro (cambio di colore).
     */
    public static synchronized void ensure(Context ctx, String color, boolean force) {
        Context c = ctx.getApplicationContext();
        SharedPreferences sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        if (busy) return;
        busy = true;
        // tutto (cartelle, controllo, download) fuori dal thread principale
        new Thread(() -> {
            try {
                removeOthers(c, color);
                if (complete(c, color)) {
                    state = "pacchetto " + color + " presente";
                    failed = false;
                    generation++;
                    return;
                }
                long last = sp.getLong("animControllo." + slug(color), 0);
                if (!force && System.currentTimeMillis() - last < CHECK_EVERY_MS && last > 0) return;
                sp.edit().putLong("animControllo." + slug(color), System.currentTimeMillis()).apply();
                download(c, color);
                generation++;
            } finally {
                busy = false;
            }
        }, "mg4-animazione").start();
    }

    /** Cresce quando il pacchetto cambia (scaricato, cancellato): chi tiene in memoria complete() lo ricalcola. */
    public static volatile int generation;

    /** Si tiene solo il colore scelto: le cartelle e gli zip parziali degli altri colori si cancellano. */
    private static void removeOthers(Context c, String color) {
        File[] list = root(c).listFiles();
        if (list == null) return;
        String keep = slug(color);
        for (File f : list) {
            String n = f.getName();
            if (n.equals(keep) || n.equals(keep + ".zip.part") || n.equals("prova")) continue;
            deleteTree(f);
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void download(Context c, String color) {
        JSONObject entry;
        try {
            JSONObject j = new JSONObject(Updater.getText(JSON_URL));
            JSONObject colors = j.optJSONObject("colori");
            entry = colors == null ? null : colors.optJSONObject(color);
        } catch (Exception e) {
            state = "pacchetto " + color + " assente (animazioni.json non disponibile)";
            Diag.put("ricarica.pacchetto", state);
            return;
        }
        if (entry == null) {
            state = "pacchetto " + color + " assente (non c'è in animazioni.json)";
            Diag.put("ricarica.pacchetto", state);
            return;
        }
        String url = entry.optString("url", ""), sha = entry.optString("sha256", "").toLowerCase(Locale.ROOT);
        long size = entry.optLong("size", -1);
        File root = root(c);
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        File part = new File(root, slug(color) + ".zip.part");
        state = "pacchetto " + color + " in download";
        Diag.put("ricarica.pacchetto", state);
        try {
            fetch(url, part, size);
            String got = sha256(part);
            if (!got.equals(sha) || size > 0 && part.length() != size) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();   // la prossima volta si riscarica da zero
                throw new java.io.IOException("sha256 o dimensione diversi");
            }
            File tmp = new File(root, slug(color) + ".tmp");
            deleteTree(tmp);
            extract(part, tmp, "carica-" + slug(color) + "-");
            File dst = dir(c, color);
            deleteTree(dst);
            if (!tmp.renameTo(dst)) throw new java.io.IOException("cartella non rinominata");
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            failed = !complete(c, color);
            state = failed ? "pacchetto " + color + " incompleto" : "pacchetto " + color + " presente (scaricato ora)";
        } catch (Exception e) {
            failed = true;
            state = "pacchetto " + color + " · errore di download: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()) + " (riprende la prossima volta)";
        }
        Diag.put("ricarica.pacchetto", state);
    }

    /** Download con ripresa: se c'è già una parte del file si chiede solo il resto (Range). */
    private static void fetch(String url, File part, long size) throws Exception {
        long have = part.isFile() ? part.length() : 0;
        if (size > 0 && have > size) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            have = 0;
        }
        if (size > 0 && have == size) return;
        String u = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(15_000);
            con.setReadTimeout(30_000);
            con.setRequestProperty("User-Agent", "MG4Launcher");
            if (have > 0) con.setRequestProperty("Range", "bytes=" + have + "-");
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) throw new java.io.IOException("redirect senza indirizzo");
                u = new URL(new URL(u), loc).toString();
                continue;
            }
            if (code == 416) {   // il file parziale è già completo (o sbagliato): lo decide lo sha256, altrimenti si riparte da zero
                con.disconnect();
                return;
            }
            boolean append = code == 206;
            if (code != 200 && code != 206) {
                con.disconnect();
                throw new java.io.IOException("HTTP " + code);
            }
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                con.disconnect();
            }
            return;
        }
        throw new java.io.IOException("troppi redirect");
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    /** Estrae solo le clip .mp4 con il prefisso atteso, senza cartelle (niente percorsi strani dentro lo zip). */
    static int extract(File zip, File to, String prefix) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        to.mkdirs();
        int n = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                String name = new File(e.getName()).getName();
                if (e.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".mp4")) continue;
                try (OutputStream out = new FileOutputStream(new File(to, name))) {
                    int r;
                    while ((r = zin.read(buf)) > 0) out.write(buf, 0, r);
                }
                n++;
            }
        }
        return n;
    }

    // ---- prova da chiavetta USB ---------------------------------------------------------------

    /**
     * Prova dalla modalità tecnica: cerca sulla chiavetta USB uno zip animazione-&lt;colore&gt;.zip (nella cartella
     * principale), lo estrae in files/animazione/prova/ e restituisce {cartella, colore}. Null se non c'è.
     */
    public static Object[] loadFromUsb(Context c) {
        java.util.List<File> roots = new java.util.ArrayList<>();
        File[] st = new File("/storage").listFiles();
        if (st != null) for (File f : st) roots.add(f);
        File[] mr = new File("/mnt/media_rw").listFiles();
        if (mr != null) for (File f : mr) roots.add(f);
        for (File r : roots) {
            File[] zips = r.listFiles((d, n) -> n.startsWith("animazione-") && n.endsWith(".zip"));
            if (zips == null || zips.length == 0) continue;
            File zip = zips[0];
            String sl = zip.getName().substring("animazione-".length(), zip.getName().length() - 4);
            File to = new File(root(c), "prova");
            deleteTree(to);
            try {
                int n = extract(zip, to, "carica-" + sl + "-");
                Diag.put("ricarica.prova", "zip " + zip.getName() + " dalla chiavetta (" + r.getName() + ") · " + n + " clip");
                if (n > 0) return new Object[]{to, sl};
            } catch (Exception e) {
                Diag.put("ricarica.prova", "zip " + zip.getName() + " non letto: " + e.getClass().getSimpleName());
            }
        }
        return null;
    }

    /** Clip di una fase nella cartella di prova (slug del colore già pronto). */
    public static File clipIn(File dir, String slug, boolean day, String phase) {
        File f = new File(dir, "carica-" + slug + "-" + (day ? "giorno" : "notte") + "-" + phase + ".mp4");
        return f.isFile() && f.length() > 0 ? f : null;
    }
}
