package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Riavvio automatico dei dongle CarPlay / Android Auto wireless che a volte non partono finché non si tolgono e
 * rimettono (es. "AutoKit-TBox Plus", "SmartBox").
 * <ul>
 *   <li>interviene solo con un dongle riconosciuto: produttore USB dei dongle (Carlinkit 1314) oppure il dispositivo USB
 *   il cui numero di serie ha già dato una sessione con un nome da dongle (il launcher lo impara da solo). Un iPhone o
 *   un telefono Android collegato via cavo e le chiavette di musica non vengono mai toccati;</li>
 *   <li>solo se dopo N secondi dall'accensione (20 di default) non è partita nessuna sessione CarPlay/AA;</li>
 *   <li>al massimo 2 tentativi per accensione: reset della porta USB del dongle (come toglierlo e rimetterlo), poi
 *   riavvio del servizio CarPlay/AA (Allgo);</li>
 *   <li>0.9.1: "porta media". Il launcher guarda le porte degli hub USB (stato di ogni porta, letto dall'hub): quando
 *   si inserisce il dongle o una chiavetta, la porta che si accende è quella media e viene memorizzata. Se all'accensione
 *   il dongle non parte, spegne e riaccende l'alimentazione di quella sola porta (come staccarlo e riattaccarlo), solo
 *   se l'hub comanda le porte una per una. Modem e STM32 sono sempre collegati e non possono mai essere "la porta
 *   appena accesa"; con una chiavetta inserita non interviene;</li>
 *   <li>0.9.3: alcuni dongle (es. SmartBox) si presentano sull'USB come un iPhone (05ac:12a8) e a CarPlay col nome
 *   "iPhone": non venivano mai riconosciuti. Ora, se dopo il ritardo non c'è nessuna sessione, conta come dongle qualsiasi
 *   dispositivo Apple o Android (AOA) collegato all'USB (anche un telefono col cavo: il riavvio è come staccarlo e
 *   riattaccarlo). La porta media si impara anche quando il dongle è già collegato all'accensione: se sul suo bus USB c'è
 *   una sola porta occupata, è quella;</li>
 *   <li>mai durante una chiamata.</li>
 * </ul>
 * Ogni passo va nella diagnostica (chiavi dongle.*).
 */
public final class DongleWatch {
    private static final String PREFS = "dongle";
    /** Produttori USB dei dongle (non di telefoni): Carlinkit / "Magic Communication Tec.". */
    private static final int[] DONGLE_VIDS = {0x1314};
    private static final int APPLE_VID = 0x05AC, GOOGLE_AOAP_VID = 0x18D1;
    /** Nomi con cui i dongle si presentano a CarPlay/AA (in minuscolo, senza spazi). */
    private static final String[] DONGLE_NAMES = {"autokit", "carlinkit", "cpc200", "tbox", "smartbox", "ottocast",
            "u2w", "u2aw", "aawireless", "ma1", "carsifi", "cplay2air", "magicbox", "aibox", "wireless", "dongle", "adapter",
            "carplaybox", "playbox", "bmbox", "okbox", "smartlink", "carlink", "cpbox", "aabox", "linkbox", "aiplay"};
    /** Ciclo di alimentazione della porta: non prima di questi secondi dall'accensione (il dongle deve avere il tempo di partire). */
    private static final int PORT_CYCLE_MIN_S = 40;
    private static final String[] ALLGO_PACKAGES = {"com.allgo.carplay.service", "com.allgo.app.androidauto", "com.allgo.rui"};

    private static DongleWatch instance;

    public static synchronized DongleWatch get(Context c, MediaClient media) {
        if (instance == null) instance = new DongleWatch(c.getApplicationContext(), media);
        return instance;
    }

    /** Istanza già creata dal launcher (null prima dell'avvio dei servizi). */
    public static synchronized DongleWatch peek() {
        return instance;
    }

    private final Context ctx;
    private final MediaClient media;
    private final SharedPreferences sp;
    private final Handler bg;
    private boolean started;

    private DongleWatch(Context c, MediaClient media) {
        ctx = c;
        this.media = media;
        sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        HandlerThread th = new HandlerThread("mg4-dongle");
        th.start();
        bg = new Handler(th.getLooper());
    }

    // ---- impostazioni ----------------------------------------------------------------------

    public boolean enabled() {
        return sp.getBoolean("attivo", true);
    }

    public void setEnabled(boolean on) {
        sp.edit().putBoolean("attivo", on).apply();
        Diag.put("dongle.automatico", on ? "acceso" : "spento");
    }

    /** Secondi dall'accensione prima di intervenire. */
    public int delaySeconds() {
        return sp.getInt("secondi", 20);
    }

    public void setDelaySeconds(int s) {
        sp.edit().putInt("secondi", s).apply();
    }

    // ---- avvio -----------------------------------------------------------------------------

    public synchronized void start() {
        if (started) return;
        started = true;
        bg.post(this::logUsb);
        long wait = delaySeconds() * 1000L - SystemClock.elapsedRealtime();
        bg.postDelayed(this::check, Math.max(3000, wait));
        bg.postDelayed(watch, 15_000);
    }

    private int watchTick;

    /**
     * Ogni 5 s: stato delle porte degli hub (per imparare la porta media); ogni 20 s: impara il dongle durante le
     * sessioni (anche quelle che partono tardi) e aggiorna l'elenco USB se cambia.
     */
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            try {
                learnPort();
                if (watchTick++ % 4 == 0) {
                    learn();
                    logUsb();
                }
            } catch (Throwable e) {
                Diag.error("dongle.watch", e.toString());
            }
            bg.postDelayed(this, 5_000);
        }
    };

    private int bootCount() {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Tentativi già fatti in questa accensione (resta giusto anche se il launcher si riavvia). */
    private int attempts() {
        return sp.getInt("boot", -1) == bootCount() ? sp.getInt("tentativi", 0) : 0;
    }

    private void setAttempts(int n) {
        sp.edit().putInt("boot", bootCount()).putInt("tentativi", n).apply();
    }

    private boolean sessionActive() {
        return media.last().deviceType != 0;
    }

    private void check() {
        if (sessionActive()) {
            Diag.put("dongle.esito", attempts() == 0 ? "sessione partita da sola" : "sessione partita dopo " + attempts() + " tentativi");
            learn();
            return;
        }
        if (!enabled()) {
            Diag.put("dongle.esito", "nessuna sessione, riavvio automatico spento");
            return;
        }
        int n = attempts();
        if (n >= 2) {
            Diag.put("dongle.esito", "nessuna sessione dopo 2 tentativi: niente altro in questa accensione");
            return;
        }
        if (inCall()) {
            Diag.put("dongle.esito", "chiamata in corso: rimando");
            bg.postDelayed(this::check, 15_000);
            return;
        }
        UsbDevice d = findDongle();
        String port = mediaPort();
        if (!port.isEmpty() && (usesDongle() || d != null)) {
            if (storagePresent()) {
                Diag.put("dongle.esito", "nessuna sessione, ma c'è una chiavetta USB collegata: non tocco la porta media");
                return;
            }
            long left = PORT_CYCLE_MIN_S * 1000L - SystemClock.elapsedRealtime();
            if (left > 0) {
                Diag.put("dongle.esito", "nessuna sessione: aspetto ancora " + (left / 1000) + " s prima di riaccendere la porta media");
                bg.postDelayed(this::check, left);
                return;
            }
            setAttempts(n + 1);
            if (n == 0) {
                String r = cyclePort(port);
                Diag.put("dongle.tentativo1", "riaccensione porta media " + port + (d == null ? " (dongle non visibile)" : " (" + describe(d) + ")") + ": " + r);
                if (!r.startsWith("fatto")) {
                    if (d != null) Diag.put("dongle.tentativo1b", "reset USB " + describe(d) + ": " + (resetUsb(d) ? "fatto" : "non riuscito"));
                    else Diag.put("dongle.tentativo1b", "riavvio servizio CarPlay/AA: " + restartAllgo());
                }
            } else {
                Diag.put("dongle.tentativo2", "riavvio servizio CarPlay/AA: " + restartAllgo());
            }
            bg.postDelayed(this::check, Math.max(delaySeconds(), 30) * 1000L);
            return;
        }
        if (d == null) {
            Diag.put("dongle.esito", port.isEmpty()
                    ? "nessuna sessione e porta media non ancora imparata (togli e rimetti il dongle ad auto accesa): non intervengo"
                    : "nessuna sessione, porta media nota ma nessun dongle visto finora: non intervengo");
            return;
        }
        setAttempts(n + 1);
        if (n == 0) {
            boolean ok = resetUsb(d);
            Diag.put("dongle.tentativo1", "reset porta USB " + describe(d) + ": " + (ok ? "fatto" : "non riuscito"));
            if (!ok) {   // passa subito al secondo
                bg.postDelayed(this::check, 2000);
                return;
            }
        } else {
            Diag.put("dongle.tentativo2", "riavvio servizio CarPlay/AA: " + restartAllgo());
        }
        bg.postDelayed(this::check, delaySeconds() * 1000L);
    }

    /** Pulsante "Riavvia CarPlay/AA": reset del dongle se riconosciuto, poi riavvio del servizio. */
    public void restartNow(Runnable onDone) {
        bg.post(() -> {
            String res;
            if (inCall()) {
                res = "chiamata in corso: non riavviato";
            } else {
                UsbDevice d = findDongle();
                StringBuilder sb = new StringBuilder();
                String port = mediaPort();
                String r = port.isEmpty() || storagePresent() ? null : cyclePort(port);
                if (r != null) sb.append("riaccensione porta media ").append(port).append(": ").append(r).append("; ");
                if ((r == null || !r.startsWith("fatto")) && d != null)
                    sb.append("reset USB ").append(describe(d)).append(": ").append(resetUsb(d) ? "fatto" : "non riuscito").append("; ");
                sb.append("servizio: ").append(restartAllgo());
                res = sb.toString();
            }
            Diag.put("dongle.manuale", res);
            if (onDone != null) new Handler(android.os.Looper.getMainLooper()).post(onDone);
        });
    }

    public boolean inCall() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int m = am == null ? 0 : am.getMode();
            if (m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE) return true;
        } catch (Throwable ignored) {
        }
        return media.remoteCallActive();
    }

    // ---- riconoscimento --------------------------------------------------------------------

    private static boolean dongleName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.isEmpty() || n.contains("iphone") || n.contains("ipad")) return false;
        for (String d : DONGLE_NAMES) if (n.contains(d)) return true;
        return false;
    }

    private Set<String> learned() {
        return new HashSet<>(sp.getStringSet("seriali", new HashSet<>()));
    }

    /** Sessione attiva con un nome da dongle: si ricorda il numero di serie dei dispositivi USB Apple/AOAP collegati. */
    private void learn() {
        MediaClient.State m = media.last();
        if (m.deviceType == 0 || !dongleName(m.deviceName)) return;
        if (!usesDongle()) {
            sp.edit().putBoolean("usaDongle", true).apply();
            Diag.put("dongle.inUso", "sessione con " + m.deviceName + ": quest'auto usa un dongle");
        }
        Set<String> s = learned();
        boolean changed = false;
        for (UsbDevice d : devices()) {
            int vid = d.getVendorId();
            if (vid != APPLE_VID && vid != GOOGLE_AOAP_VID && !isDongleVid(vid)) continue;
            String serial = serial(d);
            if (serial.isEmpty()) {
                Diag.putQuiet("dongle.seriale", describe(d) + ": numero di serie non leggibile");
                continue;
            }
            changed |= s.add(serial);
        }
        if (changed) {
            sp.edit().putStringSet("seriali", s).apply();
            Diag.put("dongle.riconosciuto", m.deviceName + " (" + s.size() + " numeri di serie memorizzati"
                    + (mediaPort().isEmpty() ? ", porta media non ancora imparata" : ", porta media " + mediaPort()) + ")");
        }
    }

    private static boolean isDongleVid(int vid) {
        for (int v : DONGLE_VIDS) if (v == vid) return true;
        return false;
    }

    private UsbDevice findDongle() {
        Set<String> s = learned();
        for (UsbDevice d : devices()) {
            if (isDongleVid(d.getVendorId())) return d;
            if (d.getVendorId() == GOOGLE_AOAP_VID && dongleName(product(d))) return d;   // es. "OKBox", "SmartBox", "smartlinkBox"
            String serial = serial(d);
            if (!serial.isEmpty() && s.contains(serial)) return d;
        }
        // 0.9.3: riconoscimento automatico (dongle che si presentano come iPhone o telefono Android)
        for (UsbDevice d : devices()) {
            int vid = d.getVendorId();
            if (vid == APPLE_VID || vid == GOOGLE_AOAP_VID) return d;
        }
        return null;
    }

    private Iterable<UsbDevice> devices() {
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um != null) return um.getDeviceList().values();
        } catch (Throwable e) {
            Diag.error("dongle.usb", e.toString());
        }
        return new java.util.ArrayList<>();
    }

    private String serial(UsbDevice d) {
        try {
            grant(d);
            String s = d.getSerialNumber();
            return s == null ? "" : s;
        } catch (Throwable e) {
            return "";
        }
    }

    private static String describe(UsbDevice d) {
        String p = "";
        try {
            p = d.getProductName();
        } catch (Throwable ignored) {
        }
        return String.format(Locale.ROOT, "%04x:%04x %s", d.getVendorId(), d.getProductId(), p == null ? "" : p).trim();
    }

    private String lastUsb = "";

    private static String product(UsbDevice d) {
        try {
            String p = d.getProductName();
            return p == null ? "" : p;
        } catch (Throwable e) {
            return "";
        }
    }

    private void logUsb() {
        StringBuilder sb = new StringBuilder();
        for (UsbDevice d : devices()) {
            String m = "";
            try {
                m = d.getManufacturerName();
            } catch (Throwable ignored) {
            }
            String where = d.getDeviceName().replace("/dev/bus/usb/", "");
            sb.append(sb.length() == 0 ? "" : " | ").append(describe(d)).append(m == null || m.isEmpty() ? "" : " (" + m + ")")
                    .append(" classe ").append(d.getDeviceClass()).append(where == null ? "" : " @" + where);
        }
        String v = sb.length() == 0 ? "nessuno" : sb.toString();
        if (v.equals(lastUsb)) return;
        lastUsb = v;
        Diag.put("usb.dispositivi", v);
    }

    // ---- porta media (stato delle porte letto dagli hub USB) -------------------------------

    private static final int HUB_GET_STATUS = 0x00, CLEAR_FEATURE = 0x01, SET_FEATURE = 0x03, PORT_POWER = 8;

    private boolean usesDongle() {
        return sp.getBoolean("usaDongle", false);
    }

    /** Porta media imparata: "vid:pid@bus#porta" dell'hub ("" se non ancora imparata). */
    private String mediaPort() {
        return sp.getString("portaMedia", "");
    }

    private static int bus(UsbDevice d) {
        try {
            String[] p = d.getDeviceName().split("/");   // /dev/bus/usb/001/005
            return Integer.parseInt(p[p.length - 2]);
        } catch (Throwable e) {
            return -1;
        }
    }

    private static String hubKey(UsbDevice d) {
        return String.format(Locale.ROOT, "%04x:%04x@%d", d.getVendorId(), d.getProductId(), bus(d));
    }

    /** Dispositivi di sistema sempre collegati (root hub, modem, STM32) o hub: non sono mai "la porta media". */
    private static boolean systemDevice(UsbDevice d) {
        int v = d.getVendorId();
        return d.getDeviceClass() == 9 || v == 0x1D6B || v == 0x05C6 || v == 0x0483;
    }

    /** C'è una chiavetta o un disco USB (classe mass storage) collegato. */
    private boolean storagePresent() {
        for (UsbDevice d : devices()) {
            if (systemDevice(d)) continue;
            if (d.getDeviceClass() == 8) return true;
            try {
                for (int i = 0; i < d.getInterfaceCount(); i++) if (d.getInterface(i).getInterfaceClass() == 8) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Porte con qualcosa collegato, per ogni hub: "vid:pid@bus#porta". */
    private Set<String> connectedPorts(StringBuilder info) {
        Set<String> out = new HashSet<>();
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (um == null) return out;
        for (UsbDevice h : devices()) {
            if (h.getDeviceClass() != 9) continue;
            UsbDeviceConnection c = null;
            try {
                grant(h);
                c = um.openDevice(h);
                if (c == null) continue;
                byte[] desc = new byte[9];
                int n = c.controlTransfer(0xA0, 0x06, 0x2900, 0, desc, desc.length, 500);
                int ports = n >= 3 ? desc[2] & 0xFF : 0;
                if (ports <= 0 || ports > 15) {   // hub USB3: descrittore diverso
                    n = c.controlTransfer(0xA0, 0x06, 0x2A00, 0, desc, desc.length, 500);
                    ports = n >= 3 ? desc[2] & 0xFF : 0;
                }
                StringBuilder on = new StringBuilder();
                for (int p = 1; p <= ports && p <= 15; p++) {
                    byte[] st = new byte[4];
                    if (c.controlTransfer(0xA3, HUB_GET_STATUS, 0, p, st, 4, 500) >= 2 && (st[0] & 1) != 0) {
                        out.add(hubKey(h) + "#" + p);
                        on.append(on.length() == 0 ? "" : ",").append(p);
                    }
                }
                if (info != null) info.append(info.length() == 0 ? "" : " | ").append(hubKey(h)).append(" porte ").append(ports)
                        .append(" comando ").append(n >= 5 ? (desc[3] & 3) == 1 ? "singolo" : (desc[3] & 3) == 0 ? "comune" : "assente" : "?")
                        .append(" occupate [").append(on).append(']');
            } catch (Throwable ignored) {
            } finally {
                if (c != null) try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private Set<String> prevPorts;
    private Set<String> prevDevs;
    private String pendingPort;
    private long pendingAt;
    private String lastPortInfo = "";

    /** Impara la porta media: una sola porta appena accesa insieme a un dispositivo nuovo (dongle, telefono, chiavetta). */
    private void learnPort() {
        StringBuilder info = new StringBuilder();
        Set<String> ports = connectedPorts(info);
        Set<String> devs = new HashSet<>();
        UsbDevice newest = null;
        for (UsbDevice d : devices()) {
            if (systemDevice(d)) continue;
            devs.add(d.getDeviceName());
            if (prevDevs != null && !prevDevs.contains(d.getDeviceName())) newest = d;
        }
        String in = info.toString();
        if (!in.equals(lastPortInfo)) {
            lastPortInfo = in;
            Diag.put("usb.porte", in.isEmpty() ? "nessun hub leggibile" : in);
        }
        long now = SystemClock.elapsedRealtime();
        if (prevPorts != null) {
            Set<String> added = new HashSet<>(ports);
            added.removeAll(prevPorts);
            if (added.size() == 1) {
                pendingPort = added.iterator().next();
                pendingAt = now;
            } else if (added.size() > 1) {
                pendingPort = null;   // più porte insieme (accensione): nessun dubbio va lasciato
            }
        }
        if (mediaPort().isEmpty()) inferPortAtBoot(ports);
        if (newest != null && pendingPort != null && now - pendingAt < 12_000 && ports.contains(pendingPort)) {
            if (!pendingPort.equals(mediaPort())) {
                sp.edit().putString("portaMedia", pendingPort).apply();
                Diag.put("dongle.portaMedia", "imparata " + pendingPort + " inserendo " + describe(newest));
            }
            pendingPort = null;
        }
        prevPorts = ports;
        prevDevs = devs;
    }

    /**
     * 0.9.3: dongle già collegato all'accensione (nessun "inserimento" da vedere). Se c'è un dispositivo Apple/AOAP/dongle
     * e sul suo bus USB gli hub hanno una sola porta occupata, quella è la porta media. Sul bus del modem e dell'STM32
     * (più porte occupate) non si impara nulla.
     */
    private void inferPortAtBoot(Set<String> ports) {
        UsbDevice cand = null;
        for (UsbDevice d : devices()) {
            int vid = d.getVendorId();
            if (vid == APPLE_VID || vid == GOOGLE_AOAP_VID || isDongleVid(vid)) {
                if (cand != null && bus(cand) != bus(d)) return;   // due candidati su bus diversi: niente dubbi
                cand = d;
            }
        }
        if (cand == null) return;
        int b = bus(cand);
        if (b < 0) return;
        String found = null;
        int count = 0;
        for (String p : ports) {
            int at = p.lastIndexOf('@'), hash = p.lastIndexOf('#');
            if (at < 0 || hash < at) continue;
            if (parse(p.substring(at + 1, hash)) != b) continue;
            count++;
            found = p;
        }
        if (count == 1) {
            sp.edit().putString("portaMedia", found).apply();
            Diag.put("dongle.portaMedia", "imparata " + found + " (già collegato all'accensione: " + describe(cand) + ")");
        }
    }

    /**
     * Spegne e riaccende l'alimentazione della sola porta media (come staccare e riattaccare il dongle), solo se l'hub
     * comanda le porte una per una (con l'alimentazione in comune si spegnerebbero anche le altre).
     */
    private String cyclePort(String key) {
        int hash = key.lastIndexOf('#');
        if (hash < 0) return "porta non valida";
        String hub = key.substring(0, hash);
        int port;
        try {
            port = Integer.parseInt(key.substring(hash + 1));
        } catch (Throwable e) {
            return "porta non valida";
        }
        UsbDevice h = null;
        for (UsbDevice d : devices()) if (d.getDeviceClass() == 9 && hubKey(d).equals(hub)) h = d;
        if (h == null) return "hub " + hub + " non trovato";
        UsbDeviceConnection c = null;
        try {
            grant(h);
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            c = um.openDevice(h);
            if (c == null) return "hub non apribile (permesso?)";
            byte[] desc = new byte[9];
            int n = c.controlTransfer(0xA0, 0x06, 0x2900, 0, desc, desc.length, 1000);
            if (n < 5 || (desc[2] & 0xFF) < 1 || (desc[2] & 0xFF) > 15) n = c.controlTransfer(0xA0, 0x06, 0x2A00, 0, desc, desc.length, 1000);
            if (n < 5) return "descrittore hub non leggibile";
            int ports = desc[2] & 0xFF, mode = desc[3] & 0x03;
            if (port > ports) return "porta " + port + " oltre le " + ports + " dell'hub";
            if (mode != 1) return "l'hub non comanda le porte una per una (" + (mode == 0 ? "alimentazione in comune" : "senza comando") + "): non la tocco";
            int off = c.controlTransfer(0x23, CLEAR_FEATURE, PORT_POWER, port, null, 0, 1000);
            if (off < 0) return "spegnimento porta non riuscito";
            SystemClock.sleep(3000);
            int on = -1;
            for (int i = 0; i < 3 && on < 0; i++) {
                on = c.controlTransfer(0x23, SET_FEATURE, PORT_POWER, port, null, 0, 1000);
                if (on < 0) SystemClock.sleep(500);
            }
            return on < 0 ? "porta spenta ma riaccensione non confermata" : "fatto (" + describe(h) + " porta " + port + ")";
        } catch (Throwable e) {
            return "errore " + e;
        } finally {
            if (c != null) try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable e) {
            return -1;
        }
    }

    // ---- azioni ----------------------------------------------------------------------------

    /** Permesso USB concesso dal launcher stesso (MANAGE_USB, firma di sistema), senza finestra di conferma. */
    private void grant(UsbDevice d) {
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um == null || um.hasPermission(d)) return;
            UsbManager.class.getMethod("grantPermission", UsbDevice.class).invoke(um, d);
        } catch (Throwable ignored) {
        }
    }

    /** Reset della porta USB (come staccare e riattaccare), lo stesso che usa il servizio Allgo. */
    private boolean resetUsb(UsbDevice d) {
        UsbDeviceConnection c = null;
        try {
            grant(d);
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            c = um.openDevice(d);
            if (c == null) {
                Diag.error("dongle.reset", "openDevice non riuscito (permesso?)");
                return false;
            }
            Object r = UsbDeviceConnection.class.getMethod("resetDevice").invoke(c);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            Diag.error("dongle.reset", t.toString());
            return false;
        } finally {
            if (c != null) try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Ferma i servizi CarPlay/AA e riavvia RemoteUIService (il launcher poi si ricollega da solo). */
    private String restartAllgo() {
        StringBuilder sb = new StringBuilder();
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            java.lang.reflect.Method fs = android.app.ActivityManager.class.getMethod("forceStopPackage", String.class);
            for (String p : ALLGO_PACKAGES) {
                try {
                    ctx.getPackageManager().getPackageInfo(p, 0);
                } catch (Exception e) {
                    continue;
                }
                fs.invoke(am, p);
                sb.append(p.substring(p.lastIndexOf('.') + 1)).append(" fermato; ");
            }
        } catch (Throwable e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            return "arresto non riuscito: " + t;
        }
        SystemClock.sleep(1500);
        try {
            ctx.startService(new Intent().setComponent(new ComponentName("com.allgo.rui", "com.allgo.rui.RemoteUIService")));
            sb.append("RemoteUIService riavviato");
        } catch (Throwable e) {
            sb.append("riavvio: ").append(e.getClass().getSimpleName()).append(" (si riavvia al ricollegamento)");
        }
        return sb.toString();
    }
}
