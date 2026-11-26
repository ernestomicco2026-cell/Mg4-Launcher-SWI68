package com.mg4.launcher.svc;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 1.0.7: "Diagnostica completa" (Impostazioni → Sistema). Un solo pulsante che raccoglie tutto quello che serve per
 * capire i problemi di oggi e preparare le versioni future, e lo invia come rapporto "diagnostica" insieme al log.
 * <p>
 * SOLO LETTURE: nessun comando all'auto, nessun servizio fermato, nessuna impostazione cambiata. Si può usare anche in
 * marcia, ma dura circa un minuto. Niente dati personali: niente telaio, numeri, contatti, posizione, titoli; i numeri
 * di serie USB diventano un'impronta corta; delle preferenze del launcher solo sì/no e numeri, mai i testi.
 */
public final class DeepCheck {
    public interface Listener {
        /** Passo raggiunto (sul thread principale). */
        void step(int done, int total, String name);

        /** Fine: esito dell'invio (LogUploader.ICON_*), sul thread principale. */
        void done(int result);
    }

    private static volatile boolean running;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DeepCheck() {}

    public static boolean running() {
        return running;
    }

    private static final String[] STEPS = {"controllo funzioni", "sistema", "schermi e app davanti", "permessi",
            "pacchetti", "servizi attivi", "servizi di sistema", "USB e dongle", "media", "servizi veicolo",
            "riscaldamento batteria", "proprietà CarService", "tema e fari", "impostazioni di sistema", "video (MediaCodec)",
            "navigatore in finestra", "rete", "preferenze del launcher"};

    public static void run(Context ctx, Listener l) {
        run(ctx, l, "a mano");
    }

    /** origin: "a mano" oppure "automatico (versione X, tentativo N)": va in cima al file. */
    public static void run(Context ctx, Listener l, String origin) {
        if (running) return;
        running = true;
        Context c = ctx.getApplicationContext();
        Thread th = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            StringBuilder r = new StringBuilder("avvio: ").append(origin).append('\n');
            long t0 = SystemClock.uptimeMillis();
            try {
                Diag.put("diagnostica.avvio", new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date()));
                for (int i = 0; i < STEPS.length; i++) {
                    int n = i;
                    MAIN.post(() -> l.step(n, STEPS.length, STEPS[n]));
                    r.append("\n===== ").append(STEPS[i].toUpperCase(Locale.ITALY)).append(" =====\n");
                    try {
                        step(c, i, r);
                    } catch (Throwable e) {
                        r.append("ERRORE ").append(e).append('\n');
                    }
                }
                r.append("\ndurata ").append((SystemClock.uptimeMillis() - t0) / 1000).append(" s\n");
                Diag.put("diagnostica.esito", "completata in " + (SystemClock.uptimeMillis() - t0) / 1000 + " s · "
                        + r.length() / 1024 + " kB");
            } catch (Throwable e) {
                r.append("ERRORE ").append(e).append('\n');
            }
            // il rapporto va PRIMA del log: se il file è troppo grande si taglia la coda del log, non la diagnostica.
            // running resta true fino all'esito dell'invio: così una seconda accensione non ne avvia un'altra nel frattempo
            try {
                String body = "#################### DIAGNOSTICA COMPLETA ####################\n" + r
                        + "\n\n#################### LOG ####################\n" + Diag.buildLog(c);
                LogUploader.sendReport("diagnostica", body, res -> {
                    running = false;
                    l.done(res);
                });
            } catch (Throwable e) {
                Diag.error("diagnostica.invio", e.toString());
                MAIN.post(() -> {
                    running = false;
                    l.done(LogUploader.ICON_ERROR);
                });
            }
        }, "mg4-diagnostica");
        th.setPriority(Thread.MIN_PRIORITY);
        th.start();
    }

    // ---- 1.0.7: diagnostica automatica, una volta per versione -------------------------------------------------------
    private static final String K_AUTO = "diagnosticaAutomatica", K_DONE = "deep.auto.versione", K_TRIES = "deep.auto.tentativi",
            K_TRIES_VER = "deep.auto.tentativiVersione", K_QUEUED = "deep.auto.inCoda";
    private static final int MAX_TRIES = 3;
    private static final long AFTER_START_MS = 3 * 60_000, POLL_MS = 30_000, WINDOW_MS = 10 * 60_000;
    private static final Handler AUTO = new Handler(Looper.getMainLooper());
    private static Runnable autoTask;
    private static String lastAutoLog = "";

    public static boolean autoEnabled(Context c) {
        return c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getBoolean(K_AUTO, true);
    }

    public static void setAutoEnabled(Context c, boolean on) {
        c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).edit().putBoolean(K_AUTO, on).apply();
        autoLog(on ? "opzione accesa" : "opzione spenta");
        if (on) onPowerOn(c, "opzione accesa");
        else cancelAuto();
    }

    private static int versionCode(Context c) {
        try {
            return (int) c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return -1;
        }
    }

    private static void autoLog(String txt) {
        if (txt.equals(lastAutoLog)) return;
        lastAutoLog = txt;
        Diag.put("diagnostica.auto", txt);
    }

    private static synchronized void cancelAuto() {
        if (autoTask != null) AUTO.removeCallbacks(autoTask);
        autoTask = null;
    }

    /**
     * Accensione (avvio del launcher o risveglio dello schermo): se per questa versione la diagnostica non è ancora stata
     * consegnata, dopo almeno 3 minuti dall'avvio del launcher si aspetta che ci siano le condizioni (servizi dell'auto
     * pronti, rete, auto ferma, niente chiamata, retromarcia o animazione di ricarica) per al massimo 10 minuti; se non
     * arrivano, si riprova alla prossima accensione. Al massimo 3 diagnostiche avviate per versione.
     */
    public static synchronized void onPowerOn(Context ctx, String why) {
        Context c = ctx.getApplicationContext();
        SharedPreferences sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        int ver = versionCode(c);
        if (!sp.getBoolean(K_AUTO, true)) {
            autoLog("spenta nelle impostazioni");
            return;
        }
        if (ver <= 0 || sp.getInt(K_DONE, -1) == ver) {
            autoLog("già consegnata per la versione " + ver);
            return;
        }
        if (sp.getInt(K_TRIES_VER, -1) != ver) sp.edit().putInt(K_TRIES_VER, ver).putInt(K_TRIES, 0).apply();
        int tries = sp.getInt(K_TRIES, 0);
        if (tries >= MAX_TRIES) {
            autoLog("rinuncia dopo " + MAX_TRIES + " tentativi per la versione " + ver + " (fino alla prossima versione)");
            return;
        }
        if (sp.getInt(K_QUEUED, -1) == ver) {
            if (LogUploader.reportQueued(c, "-diagnostica.txt")) {
                autoLog("diagnostica della versione " + ver + " in coda: parte con il prossimo invio dei log");
                return;
            }
            sp.edit().remove(K_QUEUED).apply();   // tolta dalla coda senza arrivare (coda piena): si rifà
        }
        cancelAuto();
        long startAt = android.os.Process.getStartElapsedRealtime() + AFTER_START_MS;
        long first = Math.max(5_000, startAt - SystemClock.elapsedRealtime());
        long until = SystemClock.elapsedRealtime() + first + WINDOW_MS;
        autoLog("attesa (" + why + "): controllo delle condizioni tra " + first / 1000 + " s");
        final Runnable[] task = new Runnable[1];
        task[0] = () -> {
            String no = notReady(c);
            if (no != null) {
                if (SystemClock.elapsedRealtime() + POLL_MS > until) {
                    autoLog("rimandata alla prossima accensione: " + no);
                    synchronized (DeepCheck.class) {
                        autoTask = null;
                    }
                    return;
                }
                autoLog("in attesa: " + no);
                AUTO.postDelayed(task[0], POLL_MS);
                return;
            }
            synchronized (DeepCheck.class) {
                autoTask = null;
            }
            if (sp.getInt(K_DONE, -1) == ver || sp.getInt(K_QUEUED, -1) == ver) return;   // fatta nel frattempo
            int n = sp.getInt(K_TRIES, 0) + 1;
            sp.edit().putInt(K_TRIES, n).apply();
            autoLog("avviata (versione " + ver + ", tentativo " + n + ")");
            run(c, new Listener() {
                @Override
                public void step(int done, int total, String name) {
                }

                @Override
                public void done(int result) {
                    if (result == LogUploader.ICON_SENT) {
                        sp.edit().putInt(K_DONE, ver).remove(K_QUEUED).apply();
                        autoLog("inviata (versione " + ver + ", tentativo " + n + ")");
                    } else if (result == LogUploader.ICON_QUEUED || result == LogUploader.ICON_RETRY) {
                        sp.edit().putInt(K_QUEUED, ver).apply();
                        autoLog("in coda (versione " + ver + ", tentativo " + n + "): si segna fatta quando arriva");
                    } else {
                        autoLog("non riuscita (esito " + result + ", tentativo " + n + " di " + MAX_TRIES + ")");
                    }
                }
            }, "automatico (versione " + ver + ", tentativo " + n + ")");
        };
        autoTask = task[0];
        AUTO.postDelayed(task[0], first);
    }

    /** Un rapporto "-diagnostica" in coda è arrivato (201/422): la versione per cui era in coda è fatta. */
    static void delivered(Context c, String path) {
        if (!path.endsWith("-diagnostica.txt")) return;
        SharedPreferences sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        int q = sp.getInt(K_QUEUED, -1);
        if (q <= 0) return;
        sp.edit().putInt(K_DONE, q).remove(K_QUEUED).apply();
        autoLog("inviata dalla coda (versione " + q + ")");
    }

    /** null se si può partire, altrimenti il motivo. */
    private static String notReady(Context c) {
        if (running) return "diagnostica già in corso";
        VehicleClient v = SelfCheck.vehicle();
        VehicleClient.State s = v == null ? null : v.last();
        if (s == null || !s.connected) return "servizi dell'auto non pronti";
        if (Float.isNaN(s.speedKmh) || Math.abs(s.speedKmh) >= 1f) return "auto non ferma (velocità " + (Float.isNaN(s.speedKmh) ? "non letta" : Math.round(s.speedKmh) + " km/h") + ")";
        if (s.gearRaw == 2) return "retromarcia";
        try {
            android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int m = am.getMode();
            if (m == android.media.AudioManager.MODE_IN_CALL || m == android.media.AudioManager.MODE_IN_COMMUNICATION
                    || m == android.media.AudioManager.MODE_RINGTONE) return "chiamata in corso";
        } catch (Throwable ignored) {
        }
        if (!com.mg4.launcher.ui.ChargeAnimView.phaseTxt.startsWith("fase ferma")) return "animazione di ricarica in corso";
        if (!Offline.canTry()) return "rete assente (pausa dopo errori di rete)";
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return "rete assente";
        } catch (Throwable ignored) {
        }
        if (!LogUploader.configured()) return "invio dei log non configurato in questa versione";
        return null;
    }

    private static void step(Context c, int i, StringBuilder r) throws Exception {
        switch (i) {
            case 0: selfCheck(c, r); break;
            case 1: system(c, r); break;
            case 2: displays(c, r); break;
            case 3: permissions(c, r); break;
            case 4: packages(c, r); break;
            case 5: runningServices(c, r); break;
            case 6: binderServices(r); break;
            case 7: usb(c, r); break;
            case 8: media(c, r); break;
            case 9: vehicleGetters(r); break;
            case 10: batteryHeat(r); break;
            case 11: carProps(r); break;
            case 12: themeLights(c, r); break;
            case 13: settings(c, r); break;
            case 14: codecs(r); break;
            case 15: pip(c, r); break;
            case 16: network(c, r); break;
            case 17: prefs(c, r); break;
            default:
        }
    }

    // ---- 0. Controllo funzioni (quello di sempre, senza le prove con i tasti) ----------------------------------
    private static void selfCheck(Context c, StringBuilder r) throws InterruptedException {
        if (SelfCheck.running()) {
            r.append("controllo già in corso: saltato\n");
            return;
        }
        Object lock = new Object();
        // risultato in un testo a parte: se il controllo finisse dopo i 60 s non deve scrivere mentre la diagnostica va avanti
        final String[] out = {null};
        MAIN.post(() -> SelfCheck.run(c, false, new SelfCheck.Progress() {
            @Override
            public void onItem(SelfCheck.Item it) {
            }

            @Override
            public void onKeysCountdown(int seconds) {
            }

            @Override
            public void onDone(List<SelfCheck.Item> items, int ok, int fail, int absent) {
                StringBuilder sb = new StringBuilder();
                for (SelfCheck.Item it : items) sb.append(it.mark()).append(' ').append(it.key).append(": ").append(it.detail).append('\n');
                sb.append(ok).append(" ok · ").append(fail).append(" errori · ").append(absent).append(" assenti\n");
                synchronized (lock) {
                    out[0] = sb.toString();
                    lock.notifyAll();
                }
            }
        }, "dalla diagnostica completa"));
        long until = SystemClock.uptimeMillis() + 60_000;
        synchronized (lock) {
            while (out[0] == null && SystemClock.uptimeMillis() < until) lock.wait(1000);
            r.append(out[0] != null ? out[0] : "controllo non finito entro 60 s\n");
        }
    }

    // ---- 1. Sistema ---------------------------------------------------------------------------------------------
    private static void system(Context c, StringBuilder r) {
        r.append("display ").append(Build.DISPLAY).append('\n');
        r.append("fingerprint ").append(Build.FINGERPRINT).append('\n');
        r.append("incremental ").append(Build.VERSION.INCREMENTAL).append(" · patch ").append(Build.VERSION.SECURITY_PATCH).append('\n');
        r.append("hardware ").append(Build.HARDWARE).append(" · board ").append(Build.BOARD).append(" · prodotto ").append(Build.PRODUCT).append('\n');
        r.append("firmware riconosciuto ").append(Firmware.kind(c) == Firmware.SWI68 ? "SWI68" : Firmware.kind(c) == Firmware.SWI69 ? "SWI69" : "altro").append('\n');
        r.append("acceso da ").append(SystemClock.elapsedRealtime() / 1000).append(" s · senza sospensioni ").append(SystemClock.uptimeMillis() / 1000)
                .append(" s · launcher partito a ").append(android.os.Process.getStartElapsedRealtime() / 1000).append(" s\n");
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        r.append("RAM libera ").append(mi.availMem >> 20).append(" / ").append(mi.totalMem >> 20).append(" MB · bassa ").append(mi.lowMemory)
                .append(" · launcher PSS ").append(android.os.Debug.getPss() >> 10).append(" MB\n");
        android.os.StatFs fs = new android.os.StatFs(c.getFilesDir().getAbsolutePath());
        r.append("spazio libero dati ").append(fs.getAvailableBytes() >> 20).append(" MB\n");
        r.append("lingua ").append(Locale.getDefault()).append('\n');
    }

    // ---- 2. Schermi e app davanti ---------------------------------------------------------------------------------
    private static void displays(Context c, StringBuilder r) {
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        for (Display d : dm.getDisplays()) {
            android.graphics.Point p = new android.graphics.Point();
            d.getRealSize(p);
            r.append("schermo ").append(d.getDisplayId()).append(" \"").append(d.getName()).append("\" ").append(p.x).append('×').append(p.y)
                    .append(" flag ").append(d.getFlags()).append(" stato ").append(d.getState()).append('\n');
        }
        r.append("app davanti (schermo centrale): ").append(StartGuard.topPackage(c)).append(" · metodo ").append(Diag.value("avvio.primoPianoMetodo")).append('\n');
        try {
            Object am = ActivityManager.class.getMethod("getService").invoke(null);
            List<?> l = (List<?>) am.getClass().getMethod("getAllStackInfos").invoke(am);
            for (Object si : l) {
                Class<?> k = si.getClass();
                android.content.ComponentName top = (android.content.ComponentName) k.getField("topActivity").get(si);
                r.append("stack ").append(k.getField("stackId").getInt(si)).append(" · schermo ").append(k.getField("displayId").getInt(si))
                        .append(" · visibile ").append(k.getField("visible").getBoolean(si)).append(" · ")
                        .append(top == null ? "—" : top.flattenToShortString()).append('\n');
            }
        } catch (Throwable e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            r.append("stack non leggibili: ").append(t.getClass().getSimpleName()).append('\n');
        }
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            @SuppressWarnings("deprecation")
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(12);
            for (ActivityManager.RunningTaskInfo ti : tasks)
                r.append("task ").append(ti.id).append(" · ").append(ti.topActivity == null ? "—" : ti.topActivity.flattenToShortString()).append('\n');
        } catch (Throwable e) {
            r.append("task non leggibili: ").append(e.getClass().getSimpleName()).append('\n');
        }
    }

    // ---- 3. Permessi ------------------------------------------------------------------------------------------------
    private static void permissions(Context c, StringBuilder r) throws Exception {
        PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_PERMISSIONS);
        if (pi.requestedPermissions == null) return;
        for (int i = 0; i < pi.requestedPermissions.length; i++) {
            boolean granted = (pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
            r.append(granted ? "✓ " : "✗ ").append(pi.requestedPermissions[i]).append('\n');
        }
        r.append("uid ").append(android.os.Process.myUid()).append('\n');
    }

    // ---- 4. Pacchetti -------------------------------------------------------------------------------------------------
    private static final String[] PKG_PREFIXES = {"com.saicmotor.", "com.allgo.", "com.telenav.", "com.mg4.", "com.android.systemui",
            "com.android.car", "android.car", "com.mediatek.", "com.nforetek.", "com.google.android.webview", "com.android.webview"};

    private static void packages(Context c, StringBuilder r) {
        PackageManager pm = c.getPackageManager();
        Map<String, String> m = new TreeMap<>();
        for (PackageInfo p : pm.getInstalledPackages(0)) {
            for (String pre : PKG_PREFIXES) {
                if (p.packageName.startsWith(pre)) {
                    boolean on = p.applicationInfo == null || p.applicationInfo.enabled;
                    m.put(p.packageName, p.versionName + " (" + p.getLongVersionCode() + ")" + (on ? "" : " DISATTIVATO"));
                    break;
                }
            }
        }
        for (Map.Entry<String, String> e : m.entrySet()) r.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        r.append(m.size()).append(" pacchetti di sistema/auto (le app installate dall'utente non si elencano)\n");
    }

    // ---- 5. Servizi attivi (solo auto e sistema) ------------------------------------------------------------------
    private static void runningServices(Context c, StringBuilder r) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        @SuppressWarnings("deprecation")
        List<ActivityManager.RunningServiceInfo> l = am.getRunningServices(400);
        int n = 0;
        for (ActivityManager.RunningServiceInfo s : l) {
            if (s.service == null) continue;
            String p = s.service.getPackageName();
            boolean ours = false;
            for (String pre : PKG_PREFIXES) if (p.startsWith(pre)) ours = true;
            if (!ours) continue;
            n++;
            r.append(s.service.flattenToShortString()).append(" · processo ").append(s.process).append(" · avviato ").append(s.started)
                    .append(" · in primo piano ").append(s.foreground).append(" · client ").append(s.clientCount).append('\n');
        }
        r.append(n).append(" servizi\n");
        for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
            if (p.processName == null) continue;
            boolean ours = false;
            for (String pre : PKG_PREFIXES) if (p.processName.startsWith(pre)) ours = true;
            if (ours) r.append("processo ").append(p.processName).append(" · importanza ").append(p.importance).append('\n');
        }
    }

    // ---- 6. Servizi di sistema (ServiceManager) ------------------------------------------------------------------------
    private static void binderServices(StringBuilder r) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            String[] names = (String[]) sm.getMethod("listServices").invoke(null);
            java.util.Arrays.sort(names);
            for (String n : names) r.append(n).append('\n');
            r.append(names.length).append(" servizi di sistema\n");
        } catch (Throwable e) {
            r.append("elenco non leggibile: ").append(e.getClass().getSimpleName()).append('\n');
        }
    }

    // ---- 7. USB e dongle ------------------------------------------------------------------------------------------------
    private static String shortHash(String s) {
        if (s == null || s.isEmpty()) return "—";
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            return String.format(Locale.US, "%02x%02x%02x%02x", d[0], d[1], d[2], d[3]);
        } catch (Exception e) {
            return "?";
        }
    }

    private static void usb(Context c, StringBuilder r) {
        UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        for (UsbDevice d : um.getDeviceList().values()) {
            String serial = "";
            try {
                serial = d.getSerialNumber();
            } catch (Throwable ignored) {
            }
            StringBuilder ifs = new StringBuilder();
            for (int i = 0; i < d.getInterfaceCount(); i++) ifs.append(i == 0 ? "" : ",").append(d.getInterface(i).getInterfaceClass());
            r.append(String.format(Locale.US, "%s %04x:%04x classe %d interfacce [%s] · %s / %s · seriale %s%n", d.getDeviceName(),
                    d.getVendorId(), d.getProductId(), d.getDeviceClass(), ifs, d.getManufacturerName(), d.getProductName(), shortHash(serial)));
        }
        SharedPreferences sp = c.getSharedPreferences("dongle", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : new TreeMap<>(sp.getAll()).entrySet()) {
            Object v = e.getValue();
            if (v instanceof java.util.Set) {
                java.util.Set<?> set = (java.util.Set<?>) v;
                if ("dongleId".equals(e.getKey())) r.append("dongle.").append(e.getKey()).append(" = ").append(set).append('\n');
                else r.append("dongle.").append(e.getKey()).append(" = ").append(set.size()).append(" voci\n");
            } else r.append("dongle.").append(e.getKey()).append(" = ").append(v).append('\n');
        }
        try {
            android.os.storage.StorageManager st = (android.os.storage.StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
            for (android.os.storage.StorageVolume v : st.getStorageVolumes())
                r.append("volume rimovibile ").append(v.isRemovable()).append(" · stato ").append(v.getState()).append(" · primario ").append(v.isPrimary()).append('\n');
        } catch (Throwable e) {
            r.append("volumi non leggibili\n");
        }
    }

    // ---- 8. Media (nessun titolo) --------------------------------------------------------------------------------------
    private static void media(Context c, StringBuilder r) {
        MediaClient m = SelfCheck.media();
        if (m != null) {
            MediaClient.State s = m.last();
            r.append("sorgente attiva ").append(s.activeSrc).append(" · in riproduzione ").append(s.playing).append(" · telefono ").append(s.deviceType)
                    .append(" · radio ").append(m.radioLinked()).append(" · media ").append(m.mediaLinked()).append(" · Bluetooth ").append(m.btLinked())
                    .append(" (stato ").append(m.btState()).append(") · AllGo ").append(m.projectionLinked()).append('\n');
        } else r.append("servizi media del launcher non avviati\n");
        try {
            MediaSessionManager msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController mc : msm.getActiveSessions(null)) {
                android.media.session.PlaybackState ps = mc.getPlaybackState();
                r.append("sessione ").append(mc.getPackageName()).append(" · stato ").append(ps == null ? "—" : ps.getState())
                        .append(" · azioni ").append(ps == null ? "—" : Long.toHexString(ps.getActions()))
                        .append(" · dati brano ").append(mc.getMetadata() != null ? "sì" : "no").append('\n');
            }
        } catch (Throwable e) {
            r.append("sessioni non leggibili: ").append(e.getClass().getSimpleName()).append('\n');
        }
        android.media.AudioManager au = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        r.append("volume musica ").append(au.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)).append('/')
                .append(au.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)).append(" · musica attiva ").append(au.isMusicActive()).append('\n');
    }

    // ---- 9. Servizi veicolo: tutte le letture senza argomenti -----------------------------------------------------------
    private static void vehicleGetters(StringBuilder r) {
        VehicleClient v = SelfCheck.vehicle();
        if (v == null) {
            r.append("servizio veicolo non collegato\n");
            return;
        }
        Map<String, IBinder> subs = new java.util.HashMap<>();
        int ok = 0, err = 0;
        for (String[] g : GetterTable.ROWS) {
            IBinder b = subs.containsKey(g[0]) ? subs.get(g[0]) : v.subService(g[0]);
            subs.put(g[0], b);
            String desc = "com.saicmotor.sdk.vehiclesettings." + g[1];
            String val;
            if (b == null) val = "servizio " + g[0] + " assente";
            else {
                try {
                    int code = Integer.parseInt(g[2]);
                    val = "I".equals(g[4]) ? String.valueOf(Rpc.callInt(b, desc, code)) : "F".equals(g[4])
                            ? String.valueOf(Rpc.callFloat(b, desc, code)) : String.valueOf(Rpc.callBool(b, desc, code));
                    ok++;
                } catch (Throwable e) {
                    val = "errore " + e.getClass().getSimpleName();
                    err++;
                }
            }
            r.append(g[1].replace("Service", "").replace("IVehicle", "").replace("I", "")).append(' ').append(g[2]).append(' ')
                    .append(g[3]).append(" = ").append(val).append('\n');
        }
        r.append(ok).append(" letture riuscite · ").append(err).append(" errori\n");
    }

    // ---- 10. Riscaldamento intelligente della batteria (studio del comando 38) ---------------------------------------
    private static void batteryHeat(StringBuilder r) throws InterruptedException {
        VehicleClient v = SelfCheck.vehicle();
        CarProps cp = VehicleClient.carProps();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            int a = v == null ? Integer.MIN_VALUE : v.batteryHeat();
            int p = cp == null ? Integer.MIN_VALUE : cp.getIntQuiet(0x2140f429);
            sb.append(i == 0 ? "" : " · ").append(a == Integer.MIN_VALUE ? "—" : String.valueOf(a)).append('/').append(p == Integer.MIN_VALUE ? "—" : String.valueOf(p));
            Thread.sleep(1000);
        }
        r.append("getDrivingBatteryHeat (37) / proprietà 0x2140f429, una lettura al secondo per 10 s: ").append(sb).append('\n');
        r.append("proprietà 0x2140f429 nell'elenco del CarService: ").append(cp == null ? "?" : cp.available(0x2140f429)).append('\n');
        VehicleClient.State s = v == null ? null : v.last();
        if (s != null) r.append("contesto: READY ").append(s.ready).append(" · marcia ").append(s.gearRaw).append(" · accensione ").append(s.ignition)
                .append(" · carica ").append(Math.round(s.soc)).append("% · temperatura esterna ").append(s.outsideTemp).append('\n');
        r.append("comando setDrivingBatteryHeat (38) NON inviato (solo studio)\n");
    }

    // ---- 11. Proprietà del CarService -----------------------------------------------------------------------------
    private static void carProps(StringBuilder r) {
        CarProps cp = VehicleClient.carProps();
        r.append(cp == null ? "CarService non collegato\n" : cp.dumpAll());
    }

    // ---- 12. Tema e fari ------------------------------------------------------------------------------------------------
    private static void themeLights(Context c, StringBuilder r) {
        DayNight dn = DayNight.get(c);
        r.append("fonte ").append(dn.source()).append(" · applicato ").append(dn.applied()).append(" · sole ").append(Math.round(dn.sunElevation()))
                .append("° · ").append(dn.systemTxt()).append('\n');
        r.append("fari ").append(VehicleClient.lights()).append(" · ").append(VehicleClient.lightsDescription()).append('\n');
        android.app.UiModeManager ui = (android.app.UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        r.append("UiModeManager nightMode ").append(ui.getNightMode()).append(" · configurazione notte ")
                .append((c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES).append('\n');
        CarProps cp = VehicleClient.carProps();
        if (cp != null) r.append("NIGHT_MODE ").append(cp.getBoolQuiet(CarProps.NIGHT_MODE)).append(" · HEADLIGHTS_STATE ").append(cp.getIntSafe(CarProps.HEADLIGHTS_STATE))
                .append(" · HEADLIGHTS_SWITCH ").append(cp.getIntSafe(CarProps.HEADLIGHTS_SWITCH)).append(" · luci di posizione ").append(cp.getIntSafe(CarProps.VEH_SIDE_LGHT))
                .append(" · anabbaglianti ").append(cp.getIntSafe(CarProps.DIPD_BEAM_LGHT)).append(" · interruttore ").append(cp.getIntSafe(CarProps.LIGHT_SWITCH_POS_STS))
                .append(" · luminosità schermo ").append(cp.getIntSafe(CarProps.DISPLAY_BRIGHTNESS)).append('\n');
    }

    // ---- 13. Impostazioni di sistema scelte (niente nomi di reti o dispositivi) ---------------------------------------
    private static void settings(Context c, StringBuilder r) {
        android.content.ContentResolver cr = c.getContentResolver();
        String[][] keys = {{"global", "policy_control"}, {"global", "adb_enabled"}, {"global", "stay_on_while_plugged_in"},
                {"global", "window_animation_scale"}, {"global", "transition_animation_scale"}, {"global", "animator_duration_scale"},
                {"secure", "ui_night_mode"}, {"secure", "enabled_accessibility_services"}, {"secure", "accessibility_enabled"},
                {"secure", "default_input_method"}, {"secure", "immersive_mode_confirmations"}, {"system", "screen_brightness"},
                {"system", "screen_brightness_mode"}, {"system", "screen_off_timeout"}, {"system", "font_scale"}};
        for (String[] k : keys) {
            String v;
            try {
                v = "global".equals(k[0]) ? Settings.Global.getString(cr, k[1]) : "secure".equals(k[0])
                        ? Settings.Secure.getString(cr, k[1]) : Settings.System.getString(cr, k[1]);
            } catch (Throwable e) {
                v = "non leggibile";
            }
            r.append(k[0]).append('.').append(k[1]).append(" = ").append(v).append('\n');
        }
        r.append("home predefinita: ");
        try {
            android.content.pm.ResolveInfo ri = c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY);
            r.append(ri == null || ri.activityInfo == null ? "—" : ri.activityInfo.packageName + "/" + ri.activityInfo.name);
        } catch (Throwable e) {
            r.append("?");
        }
        r.append('\n');
    }

    // ---- 14. Decodificatori video (fotogrammi dell'animazione) ----------------------------------------------------------
    private static void codecs(StringBuilder r) {
        for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (ci.isEncoder()) continue;
            for (String type : ci.getSupportedTypes()) {
                if (!type.startsWith("video/")) continue;
                StringBuilder cf = new StringBuilder();
                try {
                    MediaCodecInfo.CodecCapabilities cap = ci.getCapabilitiesForType(type);
                    for (int f : cap.colorFormats) cf.append(cf.length() == 0 ? "" : ",").append(f);
                    MediaCodecInfo.VideoCapabilities vc = cap.getVideoCapabilities();
                    if (vc != null) cf.append(" · max ").append(vc.getSupportedWidths().getUpper()).append('×').append(vc.getSupportedHeights().getUpper());
                } catch (Throwable ignored) {
                }
                r.append(ci.getName()).append(' ').append(type).append(" [").append(cf).append("]\n");
            }
        }
        r.append("ultimo uso: ").append(Diag.value("ricarica.fotogrammi")).append('\n');
    }

    // ---- 15. Navigatore in finestra: app ridimensionabili ---------------------------------------------------------
    private static final String[] NAV = {"com.telenav.app.arp", "com.google.android.apps.maps", "com.waze", "com.sygic.aura",
            "com.here.app.maps", "net.osmand", "net.osmand.plus", "com.iternio.abrpapp", "com.tomtom.gplay.navapp"};

    private static void pip(Context c, StringBuilder r) {
        PackageManager pm = c.getPackageManager();
        for (String p : NAV) {
            Intent i = pm.getLaunchIntentForPackage(p);
            if (i == null) continue;
            String info = "?";
            try {
                ActivityInfo ai = pm.getActivityInfo(i.getComponent(), 0);
                java.lang.reflect.Field f = ActivityInfo.class.getField("resizeMode");
                info = "resizeMode " + f.getInt(ai) + " · ridimensionabile " + EmbedCheck.resizeable(c, p);
            } catch (Throwable e) {
                info = "non leggibile (" + e.getClass().getSimpleName() + ")";
            }
            r.append(p).append(" · ").append(info).append('\n');
        }
        r.append("supporta più finestre: ").append(pm.hasSystemFeature("android.software.freeform_window_management") ? "freeform sì" : "freeform no")
                .append(" · PiP ").append(pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)).append('\n');
    }

    // ---- 16. Rete ------------------------------------------------------------------------------------------------------
    @SuppressWarnings("deprecation")   // NetworkInfo: su Android 9 è ancora il modo semplice
    private static void network(Context c, StringBuilder r) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            r.append("rete attiva ").append(ni == null ? "nessuna" : ni.getTypeName() + " · collegata " + ni.isConnected()).append('\n');
        } catch (Throwable e) {
            r.append("rete non leggibile\n");
        }
        r.append("invio log: ").append(LogUploader.status()).append(" · ").append(Diag.value("rete.offline")).append('\n');
    }

    // ---- 17. Preferenze del launcher: solo sì/no e numeri ---------------------------------------------------------
    private static void prefs(Context c, StringBuilder r) {
        SharedPreferences sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        List<String> textKeys = new ArrayList<>();
        for (Map.Entry<String, ?> e : new TreeMap<>(sp.getAll()).entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Float)
                r.append(e.getKey()).append(" = ").append(v).append('\n');
            else textKeys.add(e.getKey());
        }
        // i testi possono contenere nomi o numeri: si dice solo che ci sono, tranne pochi valori tecnici sicuri
        String[] safe = {"theme", "temaAutoFonte", "barraRicarica", "navApp", "tasto360", "lastNewsShown"};
        for (String k : safe) if (sp.contains(k) && sp.getAll().get(k) instanceof String) r.append(k).append(" = ").append(sp.getString(k, "")).append('\n');
        r.append("altre impostazioni di testo (valori non scritti): ").append(textKeys.size()).append('\n');
    }
}
