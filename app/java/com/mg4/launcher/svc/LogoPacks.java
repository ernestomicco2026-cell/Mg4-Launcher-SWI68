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

    private static volatile LogoPacks instance;

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

    // ---- paese automatico ------------------------------------------------------------------
    //
    // Il firmware MG usa regioni della lingua che non corrispondono al paese (auto italiane "it-CH", francesi "fr-BE"),
    // quindi l'ordine è: fuso orario del sistema → lingua del launcher → regione della lingua di sistema.
    // Le stazioni DAB (identificativo del servizio: paese RDS nei 4 bit alti, ECC se presente) confermano la scelta,
    // oppure la correggono quando il fuso non indica un paese dei pacchetti.

    /** Fuso orario del sistema -> paese (tutti i paesi dei pacchetti; il Kosovo usa Europe/Belgrade). */
    private static final Map<String, String> TZ_COUNTRY = new HashMap<>();

    static {
        String[][] tz = {
                {"AD", "Europe/Andorra"}, {"AL", "Europe/Tirane"}, {"AT", "Europe/Vienna"}, {"AX", "Europe/Mariehamn"},
                {"BA", "Europe/Sarajevo"}, {"BE", "Europe/Brussels"}, {"BG", "Europe/Sofia"}, {"BY", "Europe/Minsk"},
                {"CH", "Europe/Zurich"}, {"CY", "Asia/Nicosia", "Asia/Famagusta", "Europe/Nicosia"},
                {"CZ", "Europe/Prague"}, {"DE", "Europe/Berlin", "Europe/Busingen"}, {"DK", "Europe/Copenhagen"},
                {"EE", "Europe/Tallinn"}, {"ES", "Europe/Madrid", "Africa/Ceuta", "Atlantic/Canary"},
                {"FI", "Europe/Helsinki"}, {"FO", "Atlantic/Faroe", "Atlantic/Faeroe"}, {"FR", "Europe/Paris"},
                {"GB", "Europe/London", "Europe/Belfast", "GB", "GB-Eire"}, {"GI", "Europe/Gibraltar"},
                {"GR", "Europe/Athens"}, {"HR", "Europe/Zagreb"}, {"HU", "Europe/Budapest"},
                {"IE", "Europe/Dublin", "Eire"}, {"IM", "Europe/Isle_of_Man"}, {"IS", "Atlantic/Reykjavik", "Iceland"},
                {"IT", "Europe/Rome"}, {"LI", "Europe/Vaduz"}, {"LT", "Europe/Vilnius"}, {"LU", "Europe/Luxembourg"},
                {"LV", "Europe/Riga"}, {"MC", "Europe/Monaco"}, {"MD", "Europe/Chisinau", "Europe/Tiraspol"},
                {"ME", "Europe/Podgorica"}, {"MK", "Europe/Skopje"}, {"MT", "Europe/Malta"},
                {"NL", "Europe/Amsterdam"}, {"NO", "Europe/Oslo", "Arctic/Longyearbyen", "Atlantic/Jan_Mayen"},
                {"PL", "Europe/Warsaw", "Poland"}, {"PT", "Europe/Lisbon", "Atlantic/Madeira", "Atlantic/Azores", "Portugal"},
                {"RO", "Europe/Bucharest"}, {"RS", "Europe/Belgrade"},
                {"RU", "Europe/Moscow", "W-SU", "Europe/Kaliningrad", "Europe/Samara", "Europe/Volgograd", "Europe/Saratov",
                        "Europe/Ulyanovsk", "Europe/Astrakhan", "Europe/Kirov", "Asia/Yekaterinburg", "Asia/Omsk",
                        "Asia/Novosibirsk", "Asia/Krasnoyarsk", "Asia/Irkutsk", "Asia/Vladivostok"},
                {"SE", "Europe/Stockholm"}, {"SI", "Europe/Ljubljana"}, {"SK", "Europe/Bratislava"},
                {"SM", "Europe/San_Marino"}, {"TR", "Europe/Istanbul", "Asia/Istanbul", "Turkey"},
                {"UA", "Europe/Kiev", "Europe/Kyiv", "Europe/Uzhgorod", "Europe/Zaporozhye"}, {"VA", "Europe/Vatican"}};
        for (String[] r : tz) for (int i = 1; i < r.length; i++) TZ_COUNTRY.put(r[i], r[0]);
    }

    /** Paese RDS/DAB: chiave "ECC:cifra" (es. "E0:5" = Italia), per i paesi dei pacchetti. */
    private static final Map<String, String> RDS_COUNTRY = new HashMap<>();

    static {
        String[] m = {
                "E0:1=DE", "E0:3=AD", "E0:5=IT", "E0:6=BE", "E0:7=RU", "E0:9=AL", "E0:A=AT", "E0:B=HU", "E0:C=MT", "E0:D=DE",
                "E1:1=GR", "E1:2=CY", "E1:3=SM", "E1:4=CH", "E1:6=FI", "E1:7=LU", "E1:8=BG", "E1:9=DK", "E1:A=GI", "E1:C=GB",
                "E1:E=RO", "E1:F=FR",
                "E2:2=CZ", "E2:3=PL", "E2:4=VA", "E2:5=SK", "E2:9=LI", "E2:A=IS", "E2:B=MC", "E2:C=LT", "E2:D=RS", "E2:E=ES",
                "E2:F=NO",
                "E3:1=ME", "E3:2=IE", "E3:3=TR", "E3:4=MK", "E3:8=NL", "E3:9=LV", "E3:C=HR", "E3:E=SE", "E3:F=BY",
                "E4:1=MD", "E4:2=EE", "E4:6=UA", "E4:7=XK", "E4:8=PT", "E4:9=SI", "E4:F=BA"};
        for (String s : m) RDS_COUNTRY.put(s.substring(0, 4), s.substring(5));
    }

    /** Cifra del paese RDS di un paese (i territori usano quella del paese a cui appartengono le stazioni). */
    static char rdsDigit(String cc) {
        if ("AX".equals(cc)) cc = "FI";
        else if ("FO".equals(cc)) cc = "DK";
        else if ("IM".equals(cc)) cc = "GB";
        for (Map.Entry<String, String> e : RDS_COUNTRY.entrySet()) if (e.getValue().equals(cc)) return e.getKey().charAt(3);
        return 0;
    }

    /** Paesi dei pacchetti con quella cifra RDS. */
    static List<String> rdsCandidates(char digit) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : RDS_COUNTRY.entrySet())
            if (e.getKey().charAt(3) == digit && !out.contains(e.getValue())) out.add(e.getValue());
        java.util.Collections.sort(out);
        return out;
    }

    private static boolean known(String cc) {
        return cc != null && Arrays.asList(COUNTRIES).contains(cc);
    }

    /** Fonti del paese automatico, per la scelta e la diagnostica. */
    public static final class AutoInfo {
        public String tz = "", tzCountry, lang = "", langCountry, region = "", regionCountry;
        /** RDS: cifra più frequente (0 = nessun dato), paese esatto se c'è l'ECC, numero di servizi. */
        public char rdsDigit;
        public String rdsExact;
        public int rdsServices;
        public String country, reason, rdsNote = "";

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("fuso ").append(tz.isEmpty() ? "?" : tz).append(" → ").append(tzCountry == null ? "—" : tzCountry);
            sb.append(" · lingua ").append(lang.isEmpty() ? "?" : lang).append(" → ").append(langCountry == null ? "—" : langCountry);
            sb.append(" · regione ").append(region.isEmpty() ? "?" : region).append(" → ").append(regionCountry == null ? "—" : regionCountry);
            sb.append(" · RDS ");
            if (rdsDigit == 0) sb.append("nessun dato");
            else {
                sb.append(rdsDigit).append(" (").append(rdsExact != null ? rdsExact : String.join("/", rdsCandidates(rdsDigit)))
                        .append(", ").append(rdsServices).append(" servizi DAB)");
            }
            sb.append(" ⇒ ").append(country).append(" (").append(reason).append(rdsNote.isEmpty() ? "" : ", " + rdsNote).append(')');
            return sb.toString();
        }
    }

    /** Dati RDS raccolti dalle stazioni DAB (identificativi distinti -> cifra/ECC). */
    private final Map<Long, String> rdsSeen = new HashMap<>();
    private volatile AutoInfo lastAuto;
    private volatile String lastAutoDiag = "";

    /** Paese automatico (compatibilità: senza dati RDS). */
    public static String autoCountry(String launcherLang) {
        LogoPacks p = instance;
        return p != null ? p.auto(launcherLang).country : resolve(launcherLang, (char) 0, null, 0).country;
    }

    private AutoInfo auto(String launcherLang) {
        char digit = 0;
        String exact = null;
        int services;
        synchronized (rdsSeen) {
            services = rdsSeen.size();
            if (services == 0) {
                // ultimo dato RDS sicuro delle accensioni precedenti
                String saved = sp.getString("rds_dato", "");
                if (saved.length() >= 1) {
                    digit = saved.charAt(0);
                    exact = saved.length() >= 4 ? saved.substring(2) : null;
                    services = sp.getInt("rds_servizi", 0);
                }
            } else {
                Map<Character, Integer> count = new HashMap<>();
                Map<Character, String> ex = new HashMap<>();
                for (String v : rdsSeen.values()) {
                    char d = v.charAt(0);
                    count.put(d, count.containsKey(d) ? count.get(d) + 1 : 1);
                    if (v.length() >= 4) ex.put(d, v.substring(2));
                }
                int best = 0;
                for (Map.Entry<Character, Integer> e : count.entrySet()) if (e.getValue() > best) {
                    best = e.getValue();
                    digit = e.getKey();
                }
                // dato sicuro: almeno 3 servizi e 3 su 4 con la stessa cifra
                if (best < 3 || best * 4 < services * 3) digit = 0;
                exact = digit == 0 ? null : ex.get(digit);
                if (digit != 0) {
                    String v = digit + (exact == null ? "" : ":" + exact);
                    if (!v.equals(sp.getString("rds_dato", "")) || sp.getInt("rds_servizi", 0) != services)
                        sp.edit().putString("rds_dato", v).putInt("rds_servizi", services).apply();
                }
            }
        }
        AutoInfo a = resolve(launcherLang, digit, exact, services);
        lastAuto = a;
        String d = a.describe();
        if (!d.equals(lastAutoDiag)) {
            lastAutoDiag = d;
            Diag.put("loghi.paeseAuto", d);
        }
        return a;
    }

    /** Scelta: fuso → lingua del launcher → regione; RDS conferma, o corregge quando il fuso non indica il paese. */
    static AutoInfo resolve(String launcherLang, char digit, String exact, int services) {
        AutoInfo a = new AutoInfo();
        try {
            java.util.TimeZone z = java.util.TimeZone.getDefault();
            a.tz = z == null ? "" : z.getID();
        } catch (Throwable ignored) {
        }
        a.tzCountry = TZ_COUNTRY.get(a.tz);
        String lang = launcherLang == null ? "" : launcherLang;
        if (lang.isEmpty() || lang.equals("auto")) lang = Locale.getDefault().getLanguage();
        a.lang = lang;
        a.langCountry = LANG_COUNTRY.get(lang);
        try {
            Locale sys;
            try {
                sys = android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0);
            } catch (Throwable e) {
                sys = Locale.getDefault();
            }
            a.region = sys.toLanguageTag();
            String c = sys.getCountry() == null ? "" : sys.getCountry().toUpperCase(Locale.ROOT);
            a.regionCountry = known(c) ? c : null;
        } catch (Throwable ignored) {
        }
        a.rdsDigit = digit;
        a.rdsExact = exact;
        a.rdsServices = services;

        if (known(a.tzCountry)) {
            a.country = a.tzCountry;
            a.reason = "fuso";
        } else if (known(a.langCountry)) {
            a.country = a.langCountry;
            a.reason = "lingua";
        } else if (a.regionCountry != null) {
            a.country = a.regionCountry;
            a.reason = "regione";
        } else {
            a.country = "IT";
            a.reason = "predefinito";
        }
        if (digit == 0) return a;

        List<String> cand = exact != null ? java.util.Collections.singletonList(exact) : rdsCandidates(digit);
        if (cand.contains(a.country) || rdsDigit(a.country) == digit && exact == null) {
            a.rdsNote = "confermato da RDS";
            return a;
        }
        if ("fuso".equals(a.reason)) {
            // il fuso è un dato del paese: un RDS diverso di solito vuol dire che l'auto è all'estero
            a.rdsNote = "RDS diverso (all'estero?), resta il fuso";
            return a;
        }
        // fuso assente: si corregge con RDS, preferendo il paese di un'altra fonte che RDS conferma
        String pick = null;
        for (String s : new String[]{a.langCountry, a.regionCountry}) if (s != null && cand.contains(s)) { pick = s; break; }
        if (pick == null && cand.size() == 1) pick = cand.get(0);
        if (pick != null) {
            a.rdsNote = "corretto da RDS (era " + a.country + ")";
            a.country = pick;
            a.reason = "RDS";
        } else {
            a.rdsNote = "RDS ambiguo, resta " + a.reason;
        }
        return a;
    }

    /**
     * Stazioni DAB trovate dalla radio: dall'identificativo del servizio si ricava il paese RDS
     * (4 bit alti dei 16 bit; con 32 bit c'è anche l'ECC). Le FM si scrivono solo nella diagnostica.
     */
    public static void noteStations(int band, List<MediaClient.Station> list) {
        LogoPacks p = instance;
        if (p == null || list == null || list.isEmpty()) return;
        if (band == MediaClient.BAND_FM) {
            StringBuilder sb = new StringBuilder();
            for (MediaClient.Station st : list) {
                if (st.sid <= 0) continue;
                if (sb.length() > 120) { sb.append('…'); break; }
                sb.append(sb.length() == 0 ? "" : " ").append(Long.toHexString(st.sid).toUpperCase(Locale.ROOT));
            }
            Diag.put("loghi.rdsFM", sb.length() == 0 ? "nessun codice" : sb.toString());
            return;
        }
        if (band != MediaClient.BAND_DAB) return;
        boolean changed = false;
        synchronized (p.rdsSeen) {
            for (MediaClient.Station st : list) {
                long sid = st.sid;
                if (sid <= 0 || p.rdsSeen.containsKey(sid)) continue;
                String v;
                if (sid > 0xFFFFL) {
                    // servizio a 32 bit: ECC (8 bit) + cifra del paese (4 bit) + numero
                    int ecc = (int) ((sid >> 24) & 0xFF);
                    char d = Character.toUpperCase(Character.forDigit((int) ((sid >> 20) & 0xF), 16));
                    String cc = RDS_COUNTRY.get(String.format(Locale.ROOT, "%02X:%c", ecc, d));
                    v = d + (cc == null ? "" : ":" + cc);
                } else {
                    v = String.valueOf(Character.toUpperCase(Character.forDigit((int) ((sid >> 12) & 0xF), 16)));
                }
                if (v.charAt(0) == '0') continue;
                p.rdsSeen.put(sid, v);
                changed = true;
            }
        }
        if (changed) {
            String before = p.lastAuto == null ? "" : p.lastAuto.country;
            AutoInfo a = p.auto(com.mg4.launcher.L.locale().getLanguage());
            if (AUTO.equals(p.choice()) && !a.country.equals(before)) {
                Diag.put("loghi.paesi", AUTO + " → [" + a.country + "]");
                p.refresh(false);
            }
        }
    }

    public List<String> chosen() {
        String v = choice();
        List<String> out = new ArrayList<>();
        if (AUTO.equals(v)) {
            out.add(auto(com.mg4.launcher.L.locale().getLanguage()).country);
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
        // varianti di una stazione senza logo proprio ("RTL 102.5 Traffic" → rtl1025): solo nei paesi scelti,
        // con un nome di almeno 7 caratteri e mai tra due cifre ("radio10" non vale per "radio101")
        for (String cc : chosen()) {
            Pack pk = p.get(cc);
            if (pk == null) continue;
            String best = null;
            for (String k : pk.files.keySet()) {
                if (k.length() < 7 || k.length() >= norm.length() || !norm.startsWith(k)) continue;
                if (Character.isDigit(k.charAt(k.length() - 1)) && Character.isDigit(norm.charAt(k.length()))) continue;
                if (best == null || k.length() > best.length()) best = k;
            }
            if (best != null) return pk.files.get(best);
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
        AutoInfo a = auto(com.mg4.launcher.L.locale().getLanguage());
        if (!AUTO.equals(choice())) Diag.put("loghi.paeseAuto", a.describe() + " · non usato: paesi scelti a mano");
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
            migrate083();
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

    /**
     * 0.8.3, una volta sola: chi era su "Automatico" aveva il pacchetto del paese sbagliato (regione del firmware, es. CH
     * per le auto italiane). Appena c'è quello giusto, si tolgono gli altri pacchetti scaricati in automatico.
     * Chi ha scelto i paesi a mano non viene toccato.
     */
    private void migrate083() {
        if (sp.getBoolean("migrazione083", false)) return;
        if (!AUTO.equals(choice())) {
            sp.edit().putBoolean("migrazione083", true).apply();
            return;
        }
        List<String> ch = chosen();
        for (String cc : ch) if (!new File(root, cc).isDirectory()) return;   // prima deve esserci quello giusto
        StringBuilder gone = new StringBuilder();
        for (String cc : new ArrayList<>(packs.keySet())) {
            if (ch.contains(cc) || !"internet".equals(sp.getString("src_" + cc, ""))) continue;
            deleteTree(new File(root, cc));
            sp.edit().remove("sha_" + cc).remove("ver_" + cc).remove("src_" + cc).apply();
            gone.append(gone.length() == 0 ? "" : ", ").append(cc);
        }
        sp.edit().putBoolean("migrazione083", true).apply();
        Diag.put("loghi.migrazione", gone.length() == 0 ? "niente da cambiare (" + ch + ")" : "tolto " + gone + ", ora " + ch);
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
