package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Pacchetti dei loghi radio per paese (loghi/&lt;CC&gt;.zip + loghi/indice.json del repository).
 * <ul>
 *   <li>si scaricano appena c'è rete per i paesi scelti (automatico = paese del sistema), si controlla lo SHA-256,
 *   si estraggono nella memoria dell'app e restano per sempre: poi funzionano senza rete;</li>
 *   <li>gli aggiornamenti si controllano al massimo una volta alla settimana, oppure con "Aggiorna";</li>
 *   <li>da chiavetta: MG4Launcher/loghi/&lt;CC&gt;.zip si installa senza internet (dopo conferma).</li>
 * </ul>
 * Ogni pacchetto contiene un file per stazione (nome normalizzato, .webp o .png) e alias.txt (nome trasmesso = file).
 */
public final class LogoPacks {
    /** Indirizzo dei pacchetti: le altre versioni del launcher cambiano solo questa riga. */
    public static final String BASE_URL = "https://raw.githubusercontent.com/ernestomicco2026-cell/Mg4-Launcher-SWI68/main/loghi/";
    public static final String AUTO = "auto";
    private static final long WEEK_MS = 7L * 24 * 3600 * 1000;
    private static final long RETRY_MS = 30 * 60_000L;
    private static final String PREFS = "loghi_pacchetti";

    /** Lingua del launcher -> paese, quando il sistema non indica la regione. */
    private static final Map<String, String> LANG_COUNTRY = new HashMap<>();

    static {
        String[][] m = {{"it", "IT"}, {"en", "GB"}, {"es", "ES"}, {"de", "DE"}, {"pt", "PT"}, {"fr", "FR"}, {"tr", "TR"}, {"pl", "PL"}};
        for (String[] x : m) LANG_COUNTRY.put(x[0], x[1]);
    }

    /** Paesi disponibili (stesso elenco dei pacchetti; i nomi si prendono dal sistema nella lingua del launcher). */
    public static final String[] COUNTRIES = {"AD", "AL", "AT", "AX", "BA", "BE", "BG", "BY", "CH", "CY", "CZ", "DE", "DK",
            "EE", "ES", "FI", "FO", "FR", "GB", "GI", "GR", "HR", "HU", "IE", "IM", "IS", "IT", "LI", "LT", "LU", "LV", "MC",
            "MD", "ME", "MK", "MT", "NL", "NO", "PL", "PT", "RO", "RS", "RU", "SE", "SI", "SK", "SM", "TR", "UA", "VA", "XK"};

    public interface Listener {
        void changed();
    }

    /** Pacchetto installato. */
    public static final class Pack {
        public String cc;
        public int version;
        public long size;
        public int logos;
        public String source = "";
        final Map<String, File> files = new HashMap<>();
        final Map<String, String> alias = new HashMap<>();
    }

    private static LogoPacks instance;

    public static synchronized LogoPacks get(Context c) {
        if (instance == null) instance = new LogoPacks(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final File root;
    private final SharedPreferences sp;
    private final Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile Map<String, Pack> packs = new HashMap<>();
    private volatile String status = "";
    private volatile boolean busy;
    private long nextTry;
    /** Zip trovati su chiavetta e non ancora proposti (percorso + dimensione + data). */
    private final Set<String> offered = new LinkedHashSet<>();

    private LogoPacks(Context c) {
        ctx = c;
        root = new File(c.getFilesDir(), "pacchetti-loghi");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        HandlerThread th = new HandlerThread("mg4-logo-packs");
        th.start();
        bg = new Handler(th.getLooper());
        bg.post(this::loadAll);
    }

    public void addListener(Listener l) {
        synchronized (listeners) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    private void notifyChanged() {
        main.post(() -> {
            List<Listener> copy;
            synchronized (listeners) {
                copy = new ArrayList<>(listeners);
            }
            for (Listener l : copy) l.changed();
        });
    }

    // ---- scelta del paese ------------------------------------------------------------------

    /** "auto" oppure codici separati da virgola (es. "IT,FR"). */
    public String choice() {
        return sp.getString("paesi", AUTO);
    }

    public void setChoice(String v) {
        sp.edit().putString("paesi", v == null || v.isEmpty() ? AUTO : v).apply();
        Diag.put("loghi.paesi", v);
        refresh(true);
    }

    /** Paese automatico: regione del sistema, altrimenti lingua del launcher. */
    public static String autoCountry(String launcherLang) {
        try {
            String c = android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0).getCountry();
            if (c != null && Arrays.asList(COUNTRIES).contains(c.toUpperCase(Locale.ROOT))) return c.toUpperCase(Locale.ROOT);
        } catch (Throwable ignored) {
        }
        String lang = launcherLang == null ? "" : launcherLang;
        if (lang.isEmpty() || lang.equals("auto")) lang = Locale.getDefault().getLanguage();
        String cc = LANG_COUNTRY.get(lang);
        return cc == null ? "IT" : cc;
    }

    public List<String> chosen() {
        String v = choice();
        List<String> out = new ArrayList<>();
        if (AUTO.equals(v)) {
            out.add(autoCountry(com.mg4.launcher.L.locale().getLanguage()));
        } else {
            for (String s : v.split(",")) if (s.trim().length() == 2) out.add(s.trim().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    public static String countryName(String cc) {
        String n = new Locale("", cc).getDisplayCountry(com.mg4.launcher.L.locale());
        return n == null || n.isEmpty() || n.equals(cc) ? cc : n;
    }

    // ---- stato -----------------------------------------------------------------------------

    public Map<String, Pack> installed() {
        return packs;
    }

    public String status() {
        return status;
    }

    public boolean isBusy() {
        return busy;
    }

    // ---- ricerca dei loghi -----------------------------------------------------------------

    /** File del logo per il nome normalizzato (anche tramite alias), nei pacchetti dei paesi scelti per primi. */
    public File find(String norm) {
        if (norm == null || norm.isEmpty()) return null;
        Map<String, Pack> p = packs;
        List<String> order = new ArrayList<>(chosen());
        for (String cc : p.keySet()) if (!order.contains(cc)) order.add(cc);
        for (String cc : order) {
            Pack pk = p.get(cc);
            if (pk == null) continue;
            File f = pk.files.get(norm);
            if (f == null) {
                String a = pk.alias.get(norm);
                if (a != null) f = pk.files.get(a);
            }
            if (f == null && norm.startsWith("radio")) f = pk.files.get(norm.substring(5));
            if (f == null) f = pk.files.get("radio" + norm);
            if (f != null) return f;
        }
        return null;
    }

    // ---- caricamento -----------------------------------------------------------------------

    private void loadAll() {
        Map<String, Pack> out = new HashMap<>();
        File[] dirs = root.listFiles();
        if (dirs != null) for (File d : dirs) {
            if (!d.isDirectory() || d.getName().length() != 2) continue;
            Pack p = load(d);
            if (p != null) out.put(p.cc, p);
        }
        packs = out;
        report();
        notifyChanged();
    }

    private Pack load(File d) {
        Pack p = new Pack();
        p.cc = d.getName();
        File[] fs = d.listFiles();
        if (fs == null) return null;
        long size = 0;
        for (File f : fs) {
            String n = f.getName();
            size += f.length();
            int dot = n.lastIndexOf('.');
            if (dot <= 0) continue;
            String ext = n.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ext.equals("webp") || ext.equals("png") || ext.equals("jpg")) p.files.put(n.substring(0, dot), f);
        }
        File al = new File(d, "alias.txt");
        if (al.exists()) {
            try (InputStream in = new FileInputStream(al)) {
                for (String line : new String(readAll(in), "UTF-8").split("\n")) {
                    line = line.trim();
                    int eq = line.indexOf('=');
                    if (line.isEmpty() || line.startsWith("#") || eq <= 0) continue;
                    p.alias.put(StationLogos.norm(line.substring(0, eq)), StationLogos.norm(line.substring(eq + 1)));
                }
            } catch (Exception ignored) {
            }
        }
        if (p.files.isEmpty()) return null;
        p.logos = p.files.size();
        p.size = size;
        p.version = sp.getInt("ver_" + p.cc, 0);
        p.source = sp.getString("src_" + p.cc, "");
        return p;
    }

    private void report() {
        StringBuilder sb = new StringBuilder();
        for (Pack p : packs.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.cc).append(" v").append(p.version).append(' ').append(p.logos).append(" loghi ")
                    .append(String.format(Locale.ROOT, "%.1f MB", p.size / 1048576f))
                    .append(p.source.isEmpty() ? "" : " (" + p.source + ")");
        }
        Diag.put("loghi.paesi", choice() + " → " + chosen());
        Diag.put("loghi.pacchetti", sb.length() == 0 ? "nessuno" : sb.toString());
    }

    // ---- download --------------------------------------------------------------------------

    /** Controllo dei pacchetti: subito se manca un paese scelto, altrimenti al massimo una volta alla settimana. */
    public void refresh(boolean force) {
        bg.post(() -> check(force));
    }

    /** Da chiamare ogni tanto (es. ogni minuto): scarica quando la rete arriva. */
    public void tick() {
        bg.post(() -> {
            boolean missing = false;
            for (String cc : chosen()) if (!packs.containsKey(cc)) missing = true;
            long last = sp.getLong("controllo", 0);
            if ((missing || System.currentTimeMillis() - last > WEEK_MS) && System.currentTimeMillis() >= nextTry) check(false);
        });
    }

    private void check(boolean force) {
        if (busy) return;
        if (!online()) {
            setStatus("offline");
            return;
        }
        busy = true;
        notifyChanged();
        try {
            JSONObject idx = new JSONObject(new String(fetch(BASE_URL + "indice.json", 1_000_000), "UTF-8"));
            JSONArray arr = idx.getJSONArray("paesi");
            Map<String, JSONObject> byCc = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                byCc.put(o.getString("codice"), o);
            }
            StringBuilder res = new StringBuilder();
            for (String cc : chosen()) {
                JSONObject o = byCc.get(cc);
                if (o == null) {
                    res.append(cc).append(": nessun pacchetto; ");
                    continue;
                }
                Pack cur = packs.get(cc);
                String sha = o.optString("sha256", "");
                if (!force && cur != null && sha.equals(sp.getString("sha_" + cc, ""))) {
                    res.append(cc).append(": aggiornato; ");
                    continue;
                }
                if (cur != null && sha.equals(sp.getString("sha_" + cc, ""))) {
                    res.append(cc).append(": già all'ultima versione; ");
                    continue;
                }
                byte[] zip = fetch(BASE_URL + o.optString("file", cc + ".zip"), 40_000_000);
                String got = sha256(zip);
                if (!got.equalsIgnoreCase(sha)) {
                    res.append(cc).append(": hash diverso, scartato; ");
                    Diag.error("loghi.download", cc + " hash " + got + " invece di " + sha);
                    continue;
                }
                int n = install(cc, zip);
                sp.edit().putString("sha_" + cc, sha).putInt("ver_" + cc, o.optInt("versione", 1)).putString("src_" + cc, "internet").apply();
                res.append(cc).append(": scaricato (").append(n).append(" loghi); ");
            }
            sp.edit().putLong("controllo", System.currentTimeMillis()).apply();
            Diag.put("loghi.download", res.toString().trim());
            setStatus("ok");
            nextTry = 0;
        } catch (Exception e) {
            nextTry = System.currentTimeMillis() + RETRY_MS;
            Diag.error("loghi.download", e.toString());
            setStatus("error");
        } finally {
            busy = false;
            loadAll();
        }
    }

    private void setStatus(String s) {
        status = s;
        notifyChanged();
    }

    /** Estrae lo zip in una cartella nuova e poi la sostituisce a quella vecchia (mai a metà). */
    private int install(String cc, byte[] zip) throws Exception {
        File tmp = new File(root, cc + ".tmp");
        deleteTree(tmp);
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        int n = 0;
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = new File(e.getName()).getName();   // niente percorsi dentro lo zip
                String low = name.toLowerCase(Locale.ROOT);
                boolean img = low.endsWith(".webp") || low.endsWith(".png") || low.endsWith(".jpg");
                if (!img && !low.equals("alias.txt")) continue;
                try (FileOutputStream o = new FileOutputStream(new File(tmp, name))) {
                    int r;
                    long total = 0;
                    while ((r = z.read(buf)) > 0) {
                        total += r;
                        if (total > 2_000_000) throw new IllegalStateException("file troppo grande: " + name);
                        o.write(buf, 0, r);
                    }
                }
                if (img) n++;
            }
        }
        if (n == 0) {
            deleteTree(tmp);
            throw new IllegalStateException("nessun logo nello zip");
        }
        File dst = new File(root, cc);
        deleteTree(dst);
        if (!tmp.renameTo(dst)) throw new IllegalStateException("rinomina non riuscita");
        return n;
    }

    public void delete(String cc) {
        bg.post(() -> {
            deleteTree(new File(root, cc));
            sp.edit().remove("sha_" + cc).remove("ver_" + cc).remove("src_" + cc).apply();
            Diag.put("loghi.eliminato", cc);
            loadAll();
        });
    }

    // ---- chiavetta USB ---------------------------------------------------------------------

    /** Zip dei pacchetti trovati su memoria o chiavetta (MG4Launcher/loghi/CC.zip), non ancora proposti. */
    public List<File> usbPacksToOffer() {
        List<File> out = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(Environment.getExternalStorageDirectory(), StationLogos.USER_FOLDER));
        File[] vols = new File("/storage").listFiles();
        if (vols != null) for (File v : vols) {
            if (v.getName().equals("emulated") || v.getName().equals("self")) continue;
            dirs.add(new File(v, StationLogos.USER_FOLDER));
        }
        for (File d : dirs) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName();
                if (!n.toLowerCase(Locale.ROOT).endsWith(".zip") || n.length() != 6) continue;
                String id = f.getPath() + ":" + f.length() + ":" + f.lastModified();
                if (sp.getBoolean("usb_" + id, false)) continue;
                synchronized (offered) {
                    if (!offered.add(id)) continue;
                }
                out.add(f);
            }
        }
        return out;
    }

    /** Installa (o rifiuta, install = false) lo zip trovato su chiavetta; non verrà più proposto. */
    public void installUsb(File f, boolean install) {
        String id = f.getPath() + ":" + f.length() + ":" + f.lastModified();
        sp.edit().putBoolean("usb_" + id, true).apply();
        if (!install) return;
        bg.post(() -> {
            String cc = f.getName().substring(0, 2).toUpperCase(Locale.ROOT);
            try (InputStream in = new FileInputStream(f)) {
                byte[] zip = readAll(in);
                int n = install(cc, zip);
                sp.edit().putString("sha_" + cc, sha256(zip)).putString("src_" + cc, "chiavetta").apply();
                Diag.put("loghi.chiavetta", cc + ": installato (" + n + " loghi) da " + f.getPath());
            } catch (Exception e) {
                Diag.error("loghi.chiavetta", cc + ": " + e);
            }
            loadAll();
        });
    }

    // ---- utilità ---------------------------------------------------------------------------

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            @SuppressWarnings("deprecation")
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable e) {
            return true;
        }
    }

    private static void deleteTree(File f) {
        if (!f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) deleteTree(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static String sha256(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static byte[] fetch(String url, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "MG4Launcher/1.0");
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code + " " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) throw new IllegalStateException("troppo grande");
                    bo.write(buf, 0, n);
                }
                return bo.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
