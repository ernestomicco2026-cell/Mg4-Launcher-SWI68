package com.mg4.launcher.svc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loghi delle stazioni radio. Si cerca, in quest'ordine:
 * <ol>
 *   <li>cartella dell'utente {@code MG4Launcher/loghi/NOME.png} (memoria interna o chiavetta USB): ha la priorità;</li>
 *   <li>pacchetti dei paesi scaricati (LogoPacks, con i nomi alternativi RDS/DAB): funzionano senza internet;</li>
 *   <li>loghi già salvati nella memoria dell'app (trasmessi dalla stazione DAB o scaricati in passato);</li>
 *   <li>solo con la rete: ricerca per nome su radio-browser.info, poi salvato in modo permanente.</li>
 * </ol>
 * NOME è il nome della stazione normalizzato: minuscole, solo lettere e cifre (es. "RTL 102.5" → rtl1025.png).
 * Senza rete i tentativi si fermano (pausa crescente fino a 30 minuti) e i loghi non trovati si ricordano per 3 giorni.
 */
public final class StationLogos {
    private static final long RETRY_MS = 3L * 24 * 3600 * 1000;   // nuovo tentativo dopo 3 giorni se non trovato
    private static final long USER_SCAN_MS = 60_000;               // la cartella dell'utente si rilegge ogni minuto
    private static final long OFFLINE_MIN_MS = 2 * 60_000L, OFFLINE_MAX_MS = 30 * 60_000L;
    private static final String API = "https://de1.api.radio-browser.info/json/stations/search";
    public static final String USER_FOLDER = "MG4Launcher/loghi";

    private final Context ctx;
    private final File dir;
    private final Map<String, Bitmap> mem = new HashMap<>();
    private final Map<String, Long> missUntil = new HashMap<>();   // chiavi senza logo: non si ricontrolla il disco per 30 s
    private final Set<String> pending = new HashSet<>();
    private final Set<String> savedThisSession = new HashSet<>();
    private final HandlerThread thread = new HandlerThread("mg4-logos");
    private final Handler bg;
    private volatile Runnable onUpdate;

    private final LogoPacks packs;
    /** Nomi delle stazioni viste, con e senza logo (per la diagnostica: i nomi RDS da aggiungere agli alias). */
    private final Set<String> seenWith = new java.util.TreeSet<>(), seenWithout = new java.util.TreeSet<>();
    private volatile Map<String, File> user = new HashMap<>();
    private long userScanAt;
    private long offlineUntil, offlineStep = OFFLINE_MIN_MS;

    public StationLogos(Context c) {
        ctx = c.getApplicationContext();
        dir = new File(ctx.getFilesDir(), "loghi");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        thread.start();
        bg = new Handler(thread.getLooper());
        packs = LogoPacks.get(ctx);
        packs.addListener(() -> {
            synchronized (this) {
                mem.clear();
                missUntil.clear();
            }
            Runnable r = onUpdate;
            if (r != null) r.run();
        });
        bg.post(this::grantStorage);
        bg.post(this::scanUser);
    }

    public void setOnUpdate(Runnable r) {
        onUpdate = r;
    }

    public static String key(int band, long sid, int khz, String name) {
        if (band == MediaClient.BAND_DAB && sid > 0) return "dab_" + Long.toHexString(sid);
        String n = norm(name);
        return n.isEmpty() ? "fm_" + khz : "fm_" + n;
    }

    /** Nome normalizzato per i file dei loghi: minuscole, solo lettere e cifre. */
    public static String norm(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    // ---- cartella dell'utente --------------------------------------------------------------

    /**
     * Lettura della memoria e delle chiavette (cartella MG4Launcher/loghi): il permesso si concede da solo grazie alla
     * firma platform (GRANT_RUNTIME_PERMISSIONS). Se non riesce resta la cartella dell'app, che non lo richiede.
     */
    private void grantStorage() {
        String perm = android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Diag.put("loghi.permessoMemoria", "concesso");
            return;
        }
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                    .invoke(pm, ctx.getPackageName(), perm, android.os.Process.myUserHandle());
            Diag.put("loghi.permessoMemoria", "concesso ora");
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            Diag.put("loghi.permessoMemoria", "non concesso (" + c.getClass().getSimpleName() + "): solo la cartella dell'app");
        }
    }

    /** Cartelle MG4Launcher/loghi: memoria interna, chiavette USB e cartella dell'app (questa senza permessi). */
    private List<File> userDirs() {
        List<File> out = new ArrayList<>();
        out.add(new File(Environment.getExternalStorageDirectory(), USER_FOLDER));
        File[] vols = new File("/storage").listFiles();
        if (vols != null) for (File v : vols) {
            if (v.getName().equals("emulated") || v.getName().equals("self")) continue;
            out.add(new File(v, USER_FOLDER));
        }
        File[] app = ctx.getExternalFilesDirs(null);
        if (app != null) for (File a : app) if (a != null) out.add(new File(a, "loghi"));
        return out;
    }

    private void scanUser() {
        userScanAt = SystemClock.uptimeMillis();
        Map<String, File> found = new HashMap<>();
        StringBuilder where = new StringBuilder();
        for (File d : userDirs()) {
            File[] files = d.listFiles();
            if (files == null) continue;
            int n = 0;
            for (File f : files) {
                String name = f.getName();
                String low = name.toLowerCase(Locale.ROOT);
                if (!(low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".webp"))) continue;
                String k = norm(name.substring(0, name.lastIndexOf('.')));
                if (!k.isEmpty() && !found.containsKey(k)) {
                    found.put(k, f);
                    n++;
                }
            }
            if (n > 0) where.append(where.length() == 0 ? "" : ", ").append(d.getPath()).append(" (").append(n).append(')');
        }
        boolean changed = !found.keySet().equals(user.keySet());
        user = found;
        Diag.put("loghi.utente", found.isEmpty() ? "nessuno (cartella " + USER_FOLDER + ")" : found.size() + " in " + where);
        if (changed) {
            synchronized (this) {
                mem.clear();
                missUntil.clear();
            }
            Runnable r = onUpdate;
            if (r != null) r.run();
        }
    }

    private Bitmap packLogo(String n) {
        File f = packs.find(n);
        return f == null ? null : decode(f);
    }

    private void seen(String name, boolean has) {
        String nm = clean(name);
        if (nm.length() < 2 || nm.matches("[0-9.,\\s]+( FM| MHz)?")) return;
        boolean changed = has ? seenWith.add(nm) | seenWithout.remove(nm) : !seenWith.contains(nm) && seenWithout.add(nm);
        if (!changed) return;
        Diag.put("loghi.stazioni", seenWith.size() + " con logo, " + seenWithout.size() + " senza");
        StringBuilder sb = new StringBuilder();
        for (String s : seenWithout) {
            if (sb.length() > 600) {
                sb.append("…");
                break;
            }
            sb.append(sb.length() == 0 ? "" : " | ").append(s);
        }
        Diag.put("loghi.senzaLogo", sb.length() == 0 ? "nessuna" : sb.toString());
    }

    private static Bitmap decode(File f) {
        try {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            return b == null ? null : scale(b);
        } catch (Throwable e) {
            return null;
        }
    }

    // ---- ricerca ---------------------------------------------------------------------------

    /** Logo della stazione (cartella utente, pacchetto integrato, memoria); se manca avvia il download quando c'è rete. */
    public synchronized Bitmap get(String key, String name) {
        long now = SystemClock.uptimeMillis();
        if (now - userScanAt > USER_SCAN_MS) {
            userScanAt = now;
            bg.post(this::scanUser);
        }
        if (mem.containsKey(key)) return mem.get(key);
        Long miss = missUntil.get(key);
        if (miss != null && now < miss) return null;
        String n = norm(clean(name));
        String k = key.startsWith("fm_") ? key.substring(3) : n;
        Bitmap b = null;
        File uf = user.get(k);
        if (uf == null && !n.isEmpty()) uf = user.get(n);
        if (uf != null) b = decode(uf);
        if (b == null) b = packLogo(k);
        if (b == null && !n.isEmpty() && !n.equals(k)) b = packLogo(n);
        if (b == null) {
            File f = new File(dir, key + ".png");
            if (f.exists()) b = BitmapFactory.decodeFile(f.getPath());
        }
        seen(name, b != null);
        if (b != null) {
            mem.put(key, b);
            return b;
        }
        missUntil.put(key, now + 30_000);
        File none = new File(dir, key + ".none");
        boolean recentMiss = none.exists() && System.currentTimeMillis() - none.lastModified() < RETRY_MS;
        String nm = clean(name);
        if (!recentMiss && nm.length() >= 2 && !nm.matches("[0-9.,\\s]+( FM| MHz)?") && now >= offlineUntil && online() && pending.add(key)) {
            bg.post(() -> lookup(key, nm));
        }
        return null;
    }

    /** Logo ricevuto dalla stazione DAB: salvato in modo permanente (se l'utente non ne ha messo uno suo). */
    public synchronized void put(String key, Bitmap b) {
        if (b == null || b.getWidth() < 8) return;
        if (!mem.containsKey(key)) mem.put(key, b);
        missUntil.remove(key);
        if (!savedThisSession.add(key)) return;   // salvato su disco una volta per avvio
        Bitmap copy = b;
        bg.post(() -> save(key, copy));
    }

    private void save(String key, Bitmap b) {
        try (FileOutputStream o = new FileOutputStream(new File(dir, key + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            //noinspection ResultOfMethodCallIgnored
            new File(dir, key + ".none").delete();
        } catch (Exception e) {
            Diag.error("loghi.salva", e.toString());
        }
    }

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

    private void lookup(String key, String name) {
        Bitmap found = null;
        try {
            // stazioni del paese dell'auto (lingua di sistema), le più ascoltate per prime
            String country = L_country();
            String url = API + "?name=" + URLEncoder.encode(name, "UTF-8")
                    + (country.isEmpty() ? "" : "&countrycode=" + country)
                    + "&hidebroken=true&order=clickcount&reverse=true&limit=8";
            JSONArray arr = new JSONArray(new String(fetch(url, 200_000), "UTF-8"));
            for (int i = 0; i < arr.length() && found == null; i++) {
                JSONObject o = arr.getJSONObject(i);
                String fav = o.optString("favicon", "");
                if (!fav.startsWith("http")) continue;
                try {
                    byte[] img = fetch(fav, 1_500_000);
                    Bitmap b = BitmapFactory.decodeByteArray(img, 0, img.length);
                    if (b != null && b.getWidth() >= 32 && b.getHeight() >= 32) found = scale(b);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            // senza rete (o server irraggiungibile): pausa crescente prima di riprovare qualunque logo
            synchronized (this) {
                pending.remove(key);
                offlineUntil = SystemClock.uptimeMillis() + offlineStep;
                Diag.put("loghi.rete", "non raggiungibile (" + e.getClass().getSimpleName() + "): pausa " + offlineStep / 60_000 + " min");
                offlineStep = Math.min(OFFLINE_MAX_MS, offlineStep * 2);
            }
            return;
        }
        synchronized (this) {
            offlineStep = OFFLINE_MIN_MS;
            pending.remove(key);
            if (found != null) {
                mem.put(key, found);
                missUntil.remove(key);
                save(key, found);
            } else {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    new File(dir, key + ".none").createNewFile();
                    //noinspection ResultOfMethodCallIgnored
                    new File(dir, key + ".none").setLastModified(System.currentTimeMillis());
                } catch (Exception ignored) {
                }
            }
        }
        Diag.put("loghi.scaricati." + key, found != null ? "trovato e salvato" : "non trovato (riprovo tra 3 giorni)");
        Runnable r = onUpdate;
        if (found != null && r != null) r.run();
    }

    private static String L_country() {
        try {
            String c = android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0).getCountry();
            return c == null ? "" : c.toUpperCase(Locale.ROOT);
        } catch (Throwable e) {
            return "";
        }
    }

    private static Bitmap scale(Bitmap b) {
        int max = 192;
        if (b.getWidth() <= max && b.getHeight() <= max) return b;
        float k = max / (float) Math.max(b.getWidth(), b.getHeight());
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
    }

    private static byte[] fetch(String url, int maxBytes) throws Exception {
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "MG4Launcher/1.0");
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) break;
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new java.io.IOException("HTTP " + code);
            }
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
            } finally {
                c.disconnect();
            }
        }
        throw new java.io.IOException("troppi redirect");
    }
}
