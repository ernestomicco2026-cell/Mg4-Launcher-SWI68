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
            "carplaybox", "playbox", "bmbox"};
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
        bg.postDelayed(this::learn, 15_000);
    }

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
        if (d == null) {
            Diag.put("dongle.esito", "nessuna sessione, ma nessun dongle riconosciuto collegato: non intervengo");
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
                if (d != null) sb.append("reset USB ").append(describe(d)).append(": ").append(resetUsb(d) ? "fatto" : "non riuscito").append("; ");
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
        Set<String> s = learned();
        boolean changed = false;
        for (UsbDevice d : devices()) {
            int vid = d.getVendorId();
            if (vid != APPLE_VID && vid != GOOGLE_AOAP_VID && !isDongleVid(vid)) continue;
            String serial = serial(d);
            if (serial.isEmpty()) continue;
            changed |= s.add(serial);
        }
        if (changed) {
            sp.edit().putStringSet("seriali", s).apply();
            Diag.put("dongle.riconosciuto", m.deviceName + " (" + s.size() + " numeri di serie memorizzati)");
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
            String serial = serial(d);
            if (!serial.isEmpty() && s.contains(serial)) return d;
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

    private void logUsb() {
        StringBuilder sb = new StringBuilder();
        for (UsbDevice d : devices()) {
            String m = "";
            try {
                m = d.getManufacturerName();
            } catch (Throwable ignored) {
            }
            sb.append(sb.length() == 0 ? "" : " | ").append(describe(d)).append(m == null || m.isEmpty() ? "" : " (" + m + ")")
                    .append(" classe ").append(d.getDeviceClass());
        }
        Diag.put("usb.dispositivi", sb.length() == 0 ? "nessuno" : sb.toString());
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
