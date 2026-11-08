package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

/**
 * Sorgenti audio dell'auto, come nel launcher MG:
 *  - servizio media SAIC (USB, Bluetooth, musica online) — com.saicmotor.service.media
 *  - servizio radio SAIC (FM/AM/DAB) — com.saicmotor.service.radio
 *  - CarPlay / Android Auto (allgo) — com.allgo.rui + MediaBrowser per i metadati
 */
public final class MediaClient {
    // --- sorgenti del launcher -------------------------------------------------------------
    public static final int SRC_RADIO = 1, SRC_USB = 2, SRC_ONLINE = 3, SRC_BT = 4, SRC_CARPLAY = 5, SRC_AAUTO = 6;

    private static volatile int lastActiveSrc;

    /** Sorgente che suona adesso (per il log dei tasti al volante). */
    public static String currentSourceName() {
        return lastActiveSrc == 0 ? "nessuna" : sourceName(lastActiveSrc);
    }

    public static String sourceName(int s) {
        switch (s) {
            case SRC_RADIO: return "RADIO";
            case SRC_USB: return "MUSICA USB";
            case SRC_ONLINE: return "MUSICA ONLINE";
            case SRC_BT: return "BLUETOOTH";
            case SRC_CARPLAY: return "CARPLAY";
            case SRC_AAUTO: return "ANDROID AUTO";
            default: return "MEDIA";
        }
    }

    // --- servizio media SAIC ------------------------------------------------------------------
    private static final String MEDIA_PKG = "com.saicmotor.service.media";
    private static final String MEDIA_CLS = "com.saicmotor.service.media.MediaService";
    private static final String ST = "com.saicmotor.sdk.media.IPlayStatusBinderInterface";
    private static final int ST_GET_PLAYER = 1, ST_GET_SOURCE = 9;
    private static final String USB = "com.saicmotor.sdk.media.IMusicPlayerBinderInterface";
    private static final int USB_PLAY_OR_PAUSE = 12, USB_PLAY = 14, USB_PREV = 25, USB_NEXT = 26, USB_IS_PLAYING = 30,
            USB_DURATION = 31, USB_CURRENT = 33, USB_ADD_LISTENER = 40, USB_FOCUS = 50;
    private static final String BT = "com.saicmotor.sdk.media.IBtMusicBinderInterface";
    private static final int BT_PAUSE = 1, BT_PLAY = 2, BT_PREV = 4, BT_NEXT = 5, BT_META = 6, BT_STATE = 9,
            BT_CONN = 11, BT_REGISTER = 13, BT_FOCUS = 15;
    private static final String ONL = "com.saicmotor.sdk.media.IOnlineMusicBinderInterface";
    private static final int ONL_PAUSE = 1, ONL_PLAY = 2, ONL_PREV = 3, ONL_NEXT = 4, ONL_MUSIC = 5, ONL_STATUS = 6, ONL_FOCUS = 9;
    // callback
    private static final String USB_EVT = "com.saicmotor.sdk.media.callback.IMusicPlayerEventListener";
    private static final String BT_CB = "com.saicmotor.sdk.media.callback.IBtMusicCallback";

    // --- radio --------------------------------------------------------------------------------
    private static final String RADIO_PKG = "com.saicmotor.service.radio";
    private static final String RADIO_ACTION = "com.saicmotor.service.radio.radioservice";
    private static final String RAD = "com.saicmotor.sdk.radio.IRadioAppService";
    /** 1.0.3: callback del servizio radio (elenco stazioni e preferiti cambiati). Codici 1/2 = add/removeListener. */
    private static final String RAD_EVT = "com.saicmotor.sdk.radio.IRadioListener";
    private static final int RAD_ADD_LISTENER = 1, RAD_REMOVE_LISTENER = 2;
    // codici fino a 20: uguali in tutte le versioni del servizio radio SWI68 analizzate
    private static final int RAD_NEXT = 13, RAD_PREV = 14, RAD_CURRENT = 19;
    private static final int RAD_FAVORITES = 7, RAD_TUNE = 8, RAD_TUNE_DAB = 9, RAD_SET_MUTED = 18;
    /**
     * 0.9.1: dal codice 21 in poi la tabella dipende dalla versione del servizio radio (-1 = non usare).
     * In RadioService.3A111 manca getLastRadioInfo (21) e tutti i codici successivi sono spostati di uno: il "22"
     * delle altre versioni (elenco stazioni) lì è orderProgram, e la chiamata del launcher salvava un promemoria DAB
     * vuoto che faceva crashare il servizio radio (e riavviare l'infotainment) a ogni avvio.
     */
    private final int radLastInfo, radList, radPlay, radDabLogo;
    private final String radTable;
    public static final int BAND_AM = 1, BAND_FM = 2, BAND_DAB = 4;

    // --- CarPlay / Android Auto ---------------------------------------------------------------
    private static final String RUI = "com.allgo.rui.IRemoteUIService";
    private static final int RUI_LAUNCH_APP = 8, RUI_ACTIVE_DEVICE = 15, RUI_GET_STATUS = 17, RUI_MEDIA_KEY = 18;
    public static final int DEV_CARPLAY = 1, DEV_AAUTO = 2;

    /** Stazione radio (preferiti FM / DAB). */
    public static final class Station {
        public int band, khz, eid, secId, index;
        public long sid;
        public String name = "";
        public String key = "";
        public Bitmap logo;

        /** Nome breve da mostrare: nome RDS/DAB o frequenza. */
        public String label() {
            if (!name.isEmpty()) return name;
            if (band == BAND_AM) return khz + " kHz";
            return String.format(com.mg4.launcher.L.locale(), "%.1f", khz / 1000f);
        }
    }

    /** Stato media mostrato dal launcher. */
    public static final class State {
        public int source;                 // sorgente attiva o scelta
        public boolean playing;
        public String title = "";
        public String subtitle = "";
        public long positionMs = -1, durationMs = -1;
        public int radioBand = BAND_FM;
        public String radioFreq = "";      // es. "101,5"
        public String radioUnit = "MHz";
        public String stationKey = "";     // stazione in onda (per evidenziarla tra i preferiti)
        public Bitmap logo;                // logo della stazione in onda, se disponibile
        public List<Station> favorites = new ArrayList<>();
        public List<Station> stations = new ArrayList<>();   // stazioni trovate sulla banda attuale
        public int deviceType;             // 0 nessuno, 1 CarPlay, 2 Android Auto
        public String deviceName = "";
        public final List<Integer> sources = new ArrayList<>();
        // indicazioni di navigazione da CarPlay/AA
        public boolean tbtActive;
        public String tbtDistance = "", tbtRoad = "";
        // ---- 1.0.2 ----
        /** Sorgente che suona davvero secondo il servizio media (0 = nessuna / non nota). */
        public int activeSrc;
        /** Copertina del brano (Bluetooth: dal telefono, se la manda), null se non c'è. */
        public Bitmap art;
    }

    public interface Listener {
        void onMediaState(State s);
    }

    private final Context ctx;
    private final ServiceLink status, usb, bt, online, radio, rui;
    private final HandlerThread thread = new HandlerThread("mg4-media");
    private Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile State last = new State();
    private volatile int chosen;               // sorgente scelta dall'utente con le frecce
    private volatile boolean radioPlaying;
    private volatile int radioState = -1;      // dal servizio radio: 1 in onda, 2 in pausa
    private volatile Station current;          // stazione in onda (per riprendere dopo la pausa)
    private volatile long radioHoldUntil;
    private volatile long usbPos = -1, usbDur = -1, btPos = -1;
    private boolean running;
    private MediaBrowser browser;
    private MediaController controller;
    private volatile Bundle tbt;
    private boolean remoteWasPlaying;
    private final StationLogos logos;
    private volatile List<Station> favorites = new ArrayList<>();
    private long favoritesAt;
    private volatile List<Station> stations = new ArrayList<>();
    private long stationsAt;
    private int stationsBand = -1;
    /**
     * Profilo radio del firmware. Vecchio (R46/R59, RadioService_eh32_P): il servizio apre i sintonizzatori una sola
     * volta all'avvio e, se fallisce, resta senza fino al riavvio; si comanda solo quando è partito da un po'.
     * Nuovo (R69, radioservice_eh32_eu_P): riapre i sintonizzatori da solo, comportamento normale.
     */
    private final boolean oldRadio;
    private volatile long radioUpAt;
    /** Firmware vecchio: il servizio radio crasha sul promemoria DAB rotto e la riparazione non è ancora fatta. */
    private volatile boolean radioBroken;
    private static final long REPAIR_EVERY_MS = 120_000;
    private static final long OLD_RADIO_SETTLE_MS = 30_000;

    public MediaClient(Context c) {
        ctx = c.getApplicationContext();
        status = new ServiceLink(ctx, "media", ServiceLink.byComponent(MEDIA_PKG, MEDIA_CLS,
                "com.saicmotor.service.media.PLAY_STATUS_ACTION"));
        usb = new ServiceLink(ctx, "media.usb", ServiceLink.byComponent(MEDIA_PKG, MEDIA_CLS,
                "com.saicmotor.service.media.MUSIC_PLAYER_ACTION"));
        bt = new ServiceLink(ctx, "media.bt", ServiceLink.byComponent(MEDIA_PKG, MEDIA_CLS,
                "com.saicmotor.service.media.BT_MUSIC_ACTION"));
        online = new ServiceLink(ctx, "media.online", ServiceLink.byComponent(MEDIA_PKG, MEDIA_CLS,
                "com.saicmotor.service.media.ONLINE_MUSIC_ACTION"));
        radio = new ServiceLink(ctx, "radio", ServiceLink.byAction(RADIO_PKG, RADIO_ACTION));
        // firmware SWI68 più vecchi (R46/R59, RadioService_eh32_P): il servizio radio cade se è il launcher a farlo
        // ripartire; lì ci si collega solo quando l'auto l'ha già avviato
        String radioApk = "", radioVer = "";
        try {
            radioApk = ctx.getPackageManager().getApplicationInfo(RADIO_PKG, 0).sourceDir;
        } catch (Exception ignored) {
        }
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(RADIO_PKG, 0);
            radioVer = nz(pi.versionName) + " (" + pi.versionCode + ")";
        } catch (Exception ignored) {
        }
        oldRadio = radioApk.contains("RadioService_eh32_P") || radioVer.startsWith("RadioService.");
        String vName = radioVer.contains(" (") ? radioVer.substring(0, radioVer.indexOf(" (")) : radioVer;
        if ("RadioService.3A111".equals(vName)) {
            radTable = "3A111 (codici da 21 spostati di uno)";
            radLastInfo = -1; radList = 21; radPlay = 26; radDabLogo = 51;
        } else if ("RadioService.44231".equals(vName) || "RadioService.4C051".equals(vName) || vName.startsWith("1.0.0_EH32_SOP_")) {
            radTable = "standard (verificata)";
            radLastInfo = 21; radList = 22; radPlay = 27; radDabLogo = 52;
        } else {
            // versione non analizzata: niente codici da 21 in su (potrebbero essere altri metodi, come in 3A111)
            radTable = "sconosciuta: elenco, play e logo DAB disattivati";
            radLastInfo = -1; radList = -1; radPlay = -1; radDabLogo = -1;
        }
        radio.setAutoCreate(!oldRadio);
        Diag.put("radio.servizio", (radioApk.isEmpty() ? "?" : radioApk.substring(radioApk.lastIndexOf('/') + 1))
                + (oldRadio ? " · firmware vecchio: collegamento senza avvio" : ""));
        Diag.put("radio.versione", radioVer.isEmpty() ? "?" : radioVer);
        Diag.put("radio.codici", radTable);
        Diag.put("radio.profilo", oldRadio
                ? "vecchio (R46/R59): comandi solo " + (OLD_RADIO_SETTLE_MS / 1000) + " s dopo l'avvio del servizio, altrimenti app Radio"
                : "normale");
        radio.setOnConnected(() -> {
            radioUpAt = android.os.SystemClock.uptimeMillis();
            Diag.put("radio.collegatoAlle", clock());
            post(this::registerRadioListener);
        });
        logos = new StationLogos(ctx);
        logos.setOnUpdate(this::refreshLogosSoon);
        rui = new ServiceLink(ctx, "carplay", ServiceLink.byComponent("com.allgo.rui", "com.allgo.rui.RemoteUIService", null));
        usb.setOnConnected(() -> post(() -> {
            registerUsbListener();
            try {
                usbPorts(usb.binder(), null, true);
            } catch (Exception e) {
                Diag.error("media.usb2", e.toString());
            }
        }));
        bt.setOnConnected(() -> post(this::registerBtListener));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    // ---- 1.0.3: letture per il Controllo funzioni (solo lettura, nessun comando) ----------------

    public boolean radioLinked() {
        return radio.binder() != null;
    }

    public boolean mediaLinked() {
        return status.binder() != null && usb.binder() != null;
    }

    /** Da dove arrivano titolo e artista Bluetooth (ultima lettura). */
    public static volatile String btMetaSource = "";

    public boolean btLinked() {
        return bt.binder() != null;
    }

    /** null = CarPlay/Android Auto (Allgo) non installato su questa auto. */
    public Boolean projectionLinked() {
        try {
            ctx.getPackageManager().getPackageInfo("com.allgo.rui", 0);
        } catch (Exception e) {
            return null;
        }
        return rui.binder() != null;
    }

    /** Stato Bluetooth dal servizio media (2 = telefono collegato), -1 se non si legge. */
    public int btState() {
        try {
            IBinder b = bt.binder();
            return b == null ? -1 : Rpc.callInt(b, BT, BT_CONN);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Porte USB viste: [porta, …] (es. "usb3", "usb1"); null se il servizio media non risponde. */
    public List<String> usbPortsSeen() {
        try {
            IBinder b = usb.binder();
            if (b == null) return null;
            return Rpc.call(b, USB, USB_DEVICES, Rpc.NONE, p -> {
                List<String> out = new ArrayList<>();
                int n = p.readInt();
                for (int i = 0; i < n && i < 8; i++) {
                    if (p.readInt() == 0) continue;
                    p.readString();
                    p.readString();
                    out.add(nz(p.readString()));
                    p.readString();
                    p.readInt();
                }
                return out;
            });
        } catch (Exception e) {
            return null;
        }
    }

    /** Chiamata in corso su CarPlay/Android Auto (stato di Allgo: com.allgo.extra.CallStatus diverso da 0). */
    public boolean remoteCallActive() {
        IBinder r = rui.binder();
        if (r == null) return false;
        try {
            android.os.Bundle b = Rpc.call(r, RUI, RUI_GET_STATUS, Rpc.NONE,
                    p -> Rpc.readNullable(p, q -> android.os.Bundle.CREATOR.createFromParcel(q)));
            return b != null && b.getInt("com.allgo.extra.CallStatus", 0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    public State last() {
        return last;
    }

    public void start() {
        if (running) return;
        running = true;
        if (bg == null) {
            thread.start();
            bg = new Handler(thread.getLooper());
        }
        status.start();
        usb.start();
        bt.start();
        online.start();
        radio.start();
        rui.start();
        connectBrowser();
        WheelKeys.start(ctx);
        bg.post(loop);
        String pw = pendingPowerOn;
        if (pw != null) {
            pendingPowerOn = null;
            bg.postDelayed(() -> resumeAfterPowerOn(pw), 8000);
        }
        if (oldRadio) {
            bg.removeCallbacks(repairLoop);
            bg.postDelayed(repairLoop, 15_000);
        }
    }

    /** Firmware vecchio: controlla il crash dei promemoria DAB e ripara il servizio radio (vedi RadioRepair). */
    private final Runnable repairLoop = new Runnable() {
        @Override
        public void run() {
            try {
                radioBroken = RadioRepair.check(ctx);
            } catch (Throwable e) {
                Diag.error("radio.riparazione", e.toString());
            }
            if (bg != null) bg.postDelayed(this, REPAIR_EVERY_MS);
        }
    };

    /** Sospende le letture (launcher non visibile) senza scollegarsi dai servizi. */
    public void pause() {
        running = false;
        if (bg != null) bg.removeCallbacks(loop);
    }

    public void resume() {
        if (bg == null || running) return;
        running = true;
        bg.post(loop);
    }

    public void stop() {
        running = false;
        btGen++;
        if (bg != null) {
            bg.removeCallbacks(loop);
            bg.removeCallbacks(repairLoop);
        }
        status.stop();
        usb.stop();
        bt.stop();
        online.stop();
        unregisterRadioListener();
        radio.stop();
        rui.stop();
        if (browser != null) {
            try {
                browser.disconnect();
            } catch (Exception ignored) {
            }
            browser = null;
            controller = null;
        }
    }

    private void post(Runnable r) {
        if (bg != null) bg.post(r);
    }

    /**
     * Musica online della SWI68 (0.9): sulle auto europee recenti è Amazon Music (servizio di sistema
     * com.saicmotor.onlinemedia.amazon.v2, con login sulla pagina Amazon al primo uso), su altre è Spotify
     * (com.saicmotor.mediacenter). I comandi passano comunque dall'interfaccia "musica online" del servizio media.
     */
    public static final String ONLINE_AMAZON = "com.saicmotor.onlinemedia.amazon.v2", ONLINE_SPOTIFY = "com.saicmotor.mediacenter";
    private volatile String onlinePkg = null, onlineName = "Spotify";
    private volatile boolean onlineChecked;

    /** App della musica online presente sull'auto (Amazon Music prima di Spotify), null se nessuna. */
    public String onlinePackage() {
        return onlinePkg;
    }

    private void detectOnline() {
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        String pkg = null, name = "Spotify";
        for (String[] c : new String[][]{{ONLINE_AMAZON, "Amazon Music"}, {ONLINE_SPOTIFY, "Spotify"}}) {
            try {
                pm.getPackageInfo(c[0], 0);
                pkg = c[0];
                name = c[1];
                break;
            } catch (Exception ignored) {
            }
        }
        onlinePkg = pkg;
        onlineName = name;
        onlineChecked = true;
        Diag.put("media.online.servizio", pkg == null ? "nessuno (né Amazon Music né Spotify)" : name + " · " + pkg);
    }

    /** Apre l'app della musica online (login, libreria): si usa quando non suona nulla. */
    public void openOnlineApp(String why) {
        String pkg = onlinePkg;
        Diag.put("media.online.appAperta", (pkg == null ? "nessuna app" : pkg) + " · " + why + " · " + clock());
        if (pkg == null) return;
        main.post(() -> {
            try {
                Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable e) {
                Diag.error("media.online.app", e.toString());
            }
        });
    }

    private boolean hasOnlineTrack() {
        try {
            Audio a = audio(online.binder(), ONL, ONL_MUSIC);
            return a != null && a.name != null && !a.name.trim().isEmpty();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Diagnostica: app di musica sull'auto e sessioni media attive (ogni 10 minuti). */
    private long musicAppsAt = -1;

    private void musicAppsDiag() {
        long now = android.os.SystemClock.uptimeMillis();
        if (musicAppsAt >= 0 && now - musicAppsAt < 600_000) return;
        musicAppsAt = now;
        detectOnline();
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            StringBuilder sb = new StringBuilder();
            for (android.content.pm.PackageInfo pi : pm.getInstalledPackages(0)) {
                String p = pi.packageName.toLowerCase(java.util.Locale.ROOT);
                if (!(p.contains("amazon") || p.contains("music") || p.contains("spotify") || p.contains("mediacenter")
                        || p.contains("online") || p.contains("deezer") || p.contains("tidal") || p.contains("audio"))) continue;
                if (sb.length() > 0) sb.append(" | ");
                sb.append(pi.packageName).append(' ').append(pi.versionName)
                        .append(pm.getLaunchIntentForPackage(pi.packageName) != null ? " (apribile)" : "");
            }
            Diag.put("media.appMusica", sb.length() == 0 ? "nessuna" : sb.toString());
        } catch (Throwable e) {
            Diag.error("media.appMusica", e.toString());
        }
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            StringBuilder sb = new StringBuilder();
            for (MediaController mc : msm.getActiveSessions(null)) {
                if (sb.length() > 0) sb.append(" | ");
                android.media.session.PlaybackState ps = mc.getPlaybackState();
                android.media.MediaMetadata md = mc.getMetadata();
                sb.append(mc.getPackageName()).append(" stato ").append(ps == null ? "-" : String.valueOf(ps.getState()));
                if (md != null) {
                    CharSequence t = md.getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
                    if (t != null) sb.append(" \"").append(t).append('"');
                }
            }
            Diag.put("media.sessioni", sb.length() == 0 ? "nessuna" : sb.toString());
        } catch (Throwable e) {
            Diag.error("media.sessioni", e.toString());
        }
    }

    private static String clock() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date());
    }

    /** Annota l'ultima chiamata al servizio radio, con l'ora (diagnostica: quale chiamata lo fa cadere). */
    private void markRadio(String what) {
        radio.lastCall = what + " alle " + clock();
        // 0.8.8-beta: senza evento (ogni secondo riempiva il registro eventi e copriva l'avvio)
        Diag.putQuiet("radio.ultimaChiamata", radio.lastCall);
    }

    /**
     * Firmware vecchio: motivo per cui ora la radio non va comandata (servizio non avviato o avviato da meno di 30 s),
     * oppure null se si può. Sui firmware nuovi sempre null.
     */
    private String oldRadioBlock() {
        if (!oldRadio) return null;
        if (radioBroken) return "servizio radio da riparare (promemoria DAB rotto), riparazione in corso";
        if (radio.binder() == null) return "servizio radio non avviato";
        long left = radioUpAt + OLD_RADIO_SETTLE_MS - android.os.SystemClock.uptimeMillis();
        if (left > 0) return "servizio radio avviato da poco (ancora " + (left / 1000 + 1) + " s)";
        return null;
    }

    /** Apre l'app Radio originale: è lei ad avviare il servizio radio, come fa la home MG. */
    private void openRadioApp(String why) {
        if (radioBroken) {
            // aprire l'app avvierebbe il servizio radio, che crasha subito e può far riavviare l'infotainment
            Diag.put("radio.appAperta", "no (riparazione in corso): " + why + " · " + clock());
            return;
        }
        Diag.put("radio.appAperta", why + " · " + clock());
        main.post(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName("com.saicmotor.hmi.radio",
                        "com.saicmotor.hmi.radio.app.RadioHomeActivity"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception e) {
                Diag.error("radio.app", e.toString());
            }
        });
    }

    /** True se il comando radio va fermato (firmware vecchio, servizio non pronto): al suo posto si apre l'app Radio. */
    private boolean radioBlocked(String what) {
        String why = oldRadioBlock();
        if (why == null) return false;
        Diag.put("media.comando", what + " · " + why + (radioBroken ? ": comando non inviato" : ": comando non inviato, aperta l'app Radio"));
        openRadioApp(what + " · " + why);
        return true;
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            poll();
            bg.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------------------------------
    // Lettura stato
    // ------------------------------------------------------------------------------------------

    private static final class Audio {
        long duration;
        String name, artist, album, playTime, cover, path;
        int playState, playerType;
    }

    private static Audio readAudio(Parcel p) {
        Audio a = new Audio();
        p.readLong();                 // id
        a.duration = p.readLong();
        a.name = p.readString();
        a.cover = p.readString();     // copertina (percorso o URL)
        a.path = p.readString();      // percorso del file (dice da quale porta USB suona)
        a.artist = p.readString();
        p.readString();               // userId
        p.readString();               // avatar
        a.album = p.readString();
        p.readLong();                 // addTime
        p.readLong();                 // lastPlayTime
        a.playTime = p.readString();
        a.playState = p.readInt();
        a.playerType = p.readInt();
        return a;
    }

    private static Audio audio(IBinder b, String desc, int code) throws RemoteException {
        return Rpc.call(b, desc, code, Rpc.NONE, p -> Rpc.readNullable(p, MediaClient::readAudio));
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String join(String a, String b) {
        a = nz(a);
        b = nz(b);
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " · " + b;
    }

    private void poll() {
        State s = new State();

        // CarPlay / Android Auto collegato?
        IBinder r = rui.binder();
        if (r != null) {
            try {
                int[] dev = Rpc.call(r, RUI, RUI_ACTIVE_DEVICE, Rpc.NONE, p -> Rpc.readNullable(p, q -> {
                    int session = q.readInt();
                    String name = q.readString();
                    q.readString();
                    q.readString();
                    int type = q.readInt();
                    s.deviceName = nz(name);
                    return new int[]{type, session};
                }));
                s.deviceType = dev == null ? 0 : dev[0];
                Diag.put("carplay.dispositivo", dev == null ? "nessuno" : ("tipo " + dev[0] + ", sessione " + dev[1] + ", " + s.deviceName));
            } catch (Exception e) {
                Diag.error("carplay", e.toString());
            }
        }

        // sorgenti disponibili (come la lista dell'originale)
        s.sources.add(SRC_RADIO);
        s.sources.add(SRC_USB);
        s.sources.add(SRC_BT);
        musicAppsDiag();
        // 0.9.1: "Musica online" solo se l'auto ha Amazon Music o Spotify (i firmware vecchi non hanno nessuno dei due)
        if (!onlineChecked || onlinePkg != null) s.sources.add(SRC_ONLINE);
        if (s.deviceType == DEV_CARPLAY) s.sources.add(SRC_CARPLAY);
        if (s.deviceType == DEV_AAUTO) s.sources.add(SRC_AAUTO);

        // sorgente attiva secondo il servizio media
        int active = 0;
        IBinder st = status.binder();
        if (st != null) {
            try {
                int player = Rpc.callInt(st, ST, ST_GET_PLAYER);
                int source = Rpc.callInt(st, ST, ST_GET_SOURCE);
                Diag.put("media.player", player);
                Diag.put("media.sorgenteAudio", source);
                active = mapSource(source, player);
            } catch (Exception e) {
                Diag.error("media.stato", e.toString());
            }
        }
        if (active == 0 && radioPlaying) active = SRC_RADIO;
        // CarPlay / Android Auto: il servizio media continua a dire "radio" (sorgente 3, player 4) anche quando suona il
        // telefono. Se la sessione di RemoteUIMediaBrowserService è in riproduzione, quella è la sorgente attiva (come SWI69).
        boolean remotePlaying = false;
        MediaController rc = controller;
        if (s.deviceType != 0 && rc != null) {
            try {
                PlaybackState ps = rc.getPlaybackState();
                remotePlaying = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
                Diag.put("carplay.riproduzione", ps == null ? "nessuno stato" : "stato " + ps.getState());
            } catch (Exception ignored) {
            }
        }
        if (remotePlaying) {
            int remoteSrc = s.deviceType == DEV_AAUTO ? SRC_AAUTO : SRC_CARPLAY;
            if (!remoteWasPlaying && chosen != remoteSrc) chosen = 0;   // il telefono ha appena iniziato a suonare: si mostra lui
            active = remoteSrc;
        }
        remoteWasPlaying = remotePlaying;
        Diag.put("media.attiva", sourceName(active) + (remotePlaying ? " (sessione CarPlay/AA in riproduzione)" : ""));
        // di default si vede la sorgente in riproduzione, altrimenti la radio; con i pulsanti se ne sceglie un'altra
        int shown = chosen != 0 ? chosen : (active != 0 ? active : SRC_RADIO);
        if (!s.sources.contains(shown)) shown = active != 0 && s.sources.contains(active) ? active : SRC_RADIO;
        s.source = shown;
        s.activeSrc = active;

        try {
            fill(s, shown, active == shown);
        } catch (Exception e) {
            Diag.error("media." + sourceName(shown), e.toString());
        }
        noteRealSource(s);

        Bundle t = tbt;
        if (t != null && (s.deviceType != 0)) {
            s.tbtActive = t.getBoolean("IS_TBT_ACTIVE", false);
            s.tbtDistance = (nz(String.valueOf(t.get("DIST_TO_NEXT_JUNCTION"))) + " "
                    + nz(String.valueOf(t.get("DIST_TO_NEXT_JUNCTION_UNIT")))).replace("null", "").trim();
            s.tbtRoad = nz(t.getString("ROAD_AFTER_JUNCTION"));
        }

        last = s;
        lastActiveSrc = s.activeSrc;
        main.post(() -> {
            if (listener != null) listener.onMediaState(s);
        });
    }

    /** Codici sorgente del servizio media → sorgente del launcher (dall'analisi dell'originale). */
    private static int mapSource(int audioSource, int player) {
        switch (audioSource) {
            case 3: return SRC_RADIO;   // MEDIA_SOURCE_RAIDO
            case 6: return SRC_USB;
            case 5: return SRC_BT;
            case 14: return SRC_ONLINE;
            case 50: return SRC_CARPLAY;
            case 70: return SRC_AAUTO;
            default:
                break;
        }
        // sorgente nota ma non gestita (es. video USB): non indovinare dal tipo di player
        if (audioSource > 0) return 0;
        switch (player) {
            case 2: return SRC_USB;
            case 3: return SRC_ONLINE;
            case 4: return SRC_BT;
            default: return 0;
        }
    }

    private void fill(State s, int src, boolean isActive) throws RemoteException {
        switch (src) {
            case SRC_USB: {
                IBinder b = usb.binder();
                if (b == null) return;
                Audio a = audio(b, USB, USB_CURRENT);
                s.playing = Rpc.callBool(b, USB, USB_IS_PLAYING);
                usbPorts(b, a == null ? null : a.path, false);
                if (a != null) {
                    s.title = nz(a.name);
                    s.subtitle = join(a.artist, a.album);
                }
                long dur = Rpc.call(b, USB, USB_DURATION, Rpc.NONE, Parcel::readLong);
                s.durationMs = usbDur > 0 ? usbDur : dur;
                s.positionMs = usbPos;
                if (s.title.isEmpty()) s.title = com.mg4.launcher.L.s(com.mg4.launcher.R.string.no_track);
                if (s.subtitle.isEmpty()) s.subtitle = com.mg4.launcher.L.s(com.mg4.launcher.R.string.plug_usb);
                break;
            }
            case SRC_BT: {
                IBinder b = bt.binder();
                if (b == null) return;
                Audio a = audio(b, BT, BT_META);
                s.playing = Rpc.callBool(b, BT, BT_STATE);
                int conn = Rpc.callInt(b, BT, BT_CONN);
                Diag.put("media.bt.connessione", conn);
                // 1.0.3: titolo e artista prima dalla sessione Bluetooth (A2DP/AVRCP), poi dal servizio media SAIC
                btInfo(s, a);
                s.positionMs = btPos;
                if (s.title.isEmpty()) s.title = com.mg4.launcher.L.s(com.mg4.launcher.R.string.bt_music);
                if (s.subtitle.isEmpty()) s.subtitle = com.mg4.launcher.L.s(com.mg4.launcher.R.string.bt_start);
                break;
            }
            case SRC_ONLINE: {
                IBinder b = online.binder();
                if (b == null) return;
                Audio a = audio(b, ONL, ONL_MUSIC);
                int stt = Rpc.callInt(b, ONL, ONL_STATUS);
                Diag.put("media.online.stato", stt);
                s.playing = isActive && stt == 1;
                if (a != null) {
                    s.title = nz(a.name);
                    s.subtitle = join(a.artist, onlineName);
                    s.durationMs = a.duration;
                }
                if (s.title.isEmpty()) s.title = com.mg4.launcher.L.s(com.mg4.launcher.R.string.online_music);
                if (s.subtitle.isEmpty()) s.subtitle = onlineName;
                break;
            }
            case SRC_RADIO: {
                IBinder b = radio.binder();
                if (radio.fragile()) {
                    s.title = com.mg4.launcher.L.s(com.mg4.launcher.R.string.src_radio);
                    s.subtitle = com.mg4.launcher.L.s(com.mg4.launcher.R.string.radio_protected);
                    return;
                }
                if (oldRadio && oldRadioBlock() != null) {
                    s.title = com.mg4.launcher.L.s(com.mg4.launcher.R.string.src_radio);
                    s.subtitle = com.mg4.launcher.L.s(radioBroken ? com.mg4.launcher.R.string.radio_repairing
                            : com.mg4.launcher.R.string.radio_waiting);
                    s.radioUnit = "";
                    Diag.put("radio.attesa", oldRadioBlock());
                    return;
                }
                if (oldRadio) Diag.put("radio.attesa", "nessuna: servizio pronto, comandi attivi");   // 0.8.7-beta
                if (b == null) return;
                markRadio("stazione corrente (19)");
                Rpc.call(b, RAD, RAD_CURRENT, Rpc.NONE, p -> Rpc.readNullable(p, q -> {
                    q.readInt();                       // enable (byte)
                    String name = q.readString();
                    q.readInt();                       // isRds (byte)
                    q.readString();                    // cover
                    int khz = q.readInt();
                    int type = q.readInt();
                    int state = q.readInt();           // 1 = in onda, 2 = in pausa
                    q.readInt();                       // isCollection (byte)
                    String sub = q.readString();
                    q.readInt();                       // rssi
                    int eid = q.readInt();             // ensembleId
                    long sid = q.readLong();           // serviceId
                    q.readInt();                       // pty
                    int secId = q.readInt();           // secondaryServiceId
                    int fIndex = q.readInt();          // frequencyIndex
                    Station cur = new Station();
                    cur.band = type;
                    cur.khz = khz;
                    cur.sid = sid;
                    cur.eid = eid;
                    cur.secId = secId;
                    cur.index = fIndex;
                    current = cur;
                    radioState = state;
                    Diag.put("radio.stato", state);
                    char[] service = q.createCharArray();
                    char[] ensemble = q.createCharArray();
                    q.createCharArray();               // dlPlus
                    char[] dls = q.createCharArray();
                    s.radioBand = type;
                    String stName = type == BAND_DAB && service != null ? new String(service).trim() : nz(name);
                    s.stationKey = StationLogos.key(type, sid, khz, stName);
                    if (type == BAND_DAB) {
                        s.radioFreq = "DAB";
                        s.radioUnit = ensemble != null ? new String(ensemble).trim() : "";
                        s.title = service != null ? new String(service).trim() : nz(name);
                    } else if (type == BAND_AM) {
                        s.radioFreq = String.valueOf(khz);
                        s.radioUnit = "kHz";
                        s.title = nz(name);
                    } else {
                        s.radioFreq = String.format(com.mg4.launcher.L.locale(), "%.1f", khz / 1000f);
                        s.radioUnit = "MHz";
                        s.title = nz(name);
                    }
                    String text = dls != null ? new String(dls).trim() : "";
                    s.subtitle = !text.isEmpty() ? text : nz(sub);
                    Diag.put("radio.corrente", "banda " + type + ", " + khz + " kHz, " + s.title);
                    return null;
                }));
                // stato reale del servizio radio (1 = in onda); il comando appena dato prevale per 3 s
                long now = android.os.SystemClock.uptimeMillis();
                s.playing = now < radioHoldUntil ? radioPlaying : (radioState > 0 ? radioState == 1 : isActive || radioPlaying);
                if (s.radioBand == BAND_DAB) {
                    try {
                        markRadio("logo DAB (52)");
                        Bitmap logo = radDabLogo < 0 ? null
                                : Rpc.call(b, RAD, radDabLogo, Rpc.NONE, p -> Rpc.readNullable(p, Bitmap.CREATOR::createFromParcel));
                        if (logo != null) logos.put(s.stationKey, logo);
                    } catch (Exception e) {
                        Diag.error("radio.logo", e.toString());
                    }
                }
                s.logo = logos.get(s.stationKey, s.title);
                loadFavorites(b);
                s.favorites = favorites;
                loadStations(b, s.radioBand);
                s.stations = stations;
                if (s.title.isEmpty()) s.title = s.radioFreq.isEmpty() ? com.mg4.launcher.L.s(com.mg4.launcher.R.string.src_radio) : s.radioFreq + " " + s.radioUnit;
                break;
            }
            case SRC_CARPLAY:
            case SRC_AAUTO: {
                MediaController c = controller;
                if (c != null) {
                    MediaMetadata m = c.getMetadata();
                    PlaybackState ps = c.getPlaybackState();
                    if (m != null) {
                        s.title = nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
                        s.subtitle = join(m.getString(MediaMetadata.METADATA_KEY_ARTIST), m.getString(MediaMetadata.METADATA_KEY_ALBUM));
                        s.durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    }
                    if (ps != null) {
                        s.playing = ps.getState() == PlaybackState.STATE_PLAYING;
                        s.positionMs = ps.getPosition();
                    }
                }
                if (s.title.isEmpty()) s.title = src == SRC_CARPLAY ? "CarPlay" : "Android Auto";
                if (s.subtitle.isEmpty()) s.subtitle = s.deviceName.isEmpty() ? com.mg4.launcher.L.s(com.mg4.launcher.R.string.from_phone) : s.deviceName;
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Comandi
    // ------------------------------------------------------------------------------------------

    /** Frecce ‹ ›: passa alla sorgente precedente/successiva e la avvia, come l'originale. */
    public void cycleSource(int dir) {
        State s = last;
        List<Integer> list = s.sources;
        if (list.isEmpty()) return;
        int i = list.indexOf(s.source);
        int next = list.get(((i < 0 ? 0 : i) + dir + list.size()) % list.size());
        selectSource(next);
    }

    public void selectSource(int src) {
        chosen = src;
        btGen++;   // 1.0.3: un avvio Bluetooth ancora in attesa non deve più rubare l'audio alla sorgente scelta
        command("avvio " + sourceName(src), () -> startSource(src));
    }

    // ---- comandi con nuovo tentativo -------------------------------------------------------

    private interface Cmd {
        void run() throws Exception;
    }

    /** 1.0.2 (punto 3): nuovi tentativi con attesa crescente (1, 2, 4 s) se il servizio non è ancora pronto. */
    private static final long[] RETRY_WAIT_MS = {1000, 2000, 4000};

    /**
     * Esegue un comando media. Subito dopo l'accensione il servizio può non essere ancora pronto
     * ("binder non disponibile", "servizio radio non collegato", "Result::NOT_INITIALIZED"): si riprova in background
     * con attesa crescente, senza bloccare l'interfaccia e senza errori se un tentativo successivo riesce.
     * L'esito di play e salto finisce nella diagnostica (media.comando); i tentativi in media.attesa / radio.tentativi.
     */
    private void command(String what, Cmd cmd) {
        post(() -> {
            // comando per la radio: "avvio RADIO", oppure play/salto con la radio come sorgente mostrata
            // (passare dalla radio a USB/Bluetooth non è un comando radio)
            boolean toRadio = what.startsWith("avvio ") ? what.endsWith(sourceName(SRC_RADIO)) : last.source == SRC_RADIO;
            if (toRadio && radio.fragile()) {
                Diag.put("media.comando", what + " · radio protetta: comando non inviato (usa l'app Radio)");
                return;
            }
            if (toRadio && radioBlocked(what)) return;
            // "sorgente radio su" al nuovo tentativo solo per i comandi che fanno partire la radio, mai per pausa o salto
            boolean startLike = toRadio && (what.startsWith("avvio ") || what.equals("play") && !last.playing);
            attempt(what, cmd, 0, toRadio, startLike, null);
        });
    }

    /** Comando radio (banda, sintonia) con lo stesso nuovo tentativo. */
    private void radioCommand(String what, Cmd cmd) {
        post(() -> {
            if (radio.fragile()) {
                Diag.put("media.comando", what + " · radio protetta: comando non inviato (usa l'app Radio)");
                return;
            }
            if (radioBlocked(what)) return;
            attempt(what, cmd, 0, true, true, null);
        });
    }

    private static boolean notReady(Exception e) {
        String msg = String.valueOf(e.getMessage()) + " " + e;
        return msg.contains("NOT_INITIALIZED") || msg.contains("binder non disponibile") || msg.contains("non collegato");
    }

    /** onDone: chiamato con true/false a fine tentativi (sul thread di lavoro), può essere null. */
    private void attempt(String what, Cmd cmd, int n, boolean isRadio, boolean startLike, java.util.function.Consumer<Boolean> onDone) {
        State before = last;
        Boolean result = null;
        try {
            // dal secondo tentativo di un avvio radio si riattiva la sorgente radio (srcPlayRadio), che apre il
            // sintonizzatore; se la sorgente attiva non è la radio ci pensa già il comando stesso (niente doppio invio)
            if (startLike && n > 0 && last.activeSrc == SRC_RADIO) radioSourceUp("nuovo tentativo " + (n + 1));
            cmd.run();
            if (n > 0) {
                Diag.put("media.attesa", what + ": eseguito al tentativo " + (n + 1));
                if (isRadio) Diag.put("radio.tentativi", what + ": riuscito al tentativo " + (n + 1) + " · " + clock());
            } else if (isRadio) {
                Diag.put("radio.tentativi", what + ": riuscito al primo tentativo · " + clock());
            }
            String src = sourceName(before.source);
            boolean wasPlaying = before.playing;
            String b = before.title;
            if (bg != null) bg.postDelayed(() -> {
                State a = last;
                Diag.put("media.comando", what + " · " + src + " · prima " + (wasPlaying ? "in riproduzione" : "in pausa")
                        + " \"" + b + "\" · dopo " + (a.playing ? "in riproduzione" : "in pausa") + " \"" + a.title + "\"");
            }, 1500);
            result = true;
        } catch (Exception e) {
            if (notReady(e) && n < RETRY_WAIT_MS.length && bg != null) {
                Diag.put("media.attesa", what + ": servizio non ancora pronto, nuovo tentativo " + (n + 2) + " tra " + (RETRY_WAIT_MS[n] / 1000) + " s");
                bg.postDelayed(() -> attempt(what, cmd, n + 1, isRadio, startLike, onDone), RETRY_WAIT_MS[n]);
                return;
            }
            if (isRadio) Diag.put("radio.tentativi", what + ": non riuscito dopo " + (n + 1) + " tentativi (" + e.getMessage() + ") · " + clock());
            Diag.error(what.startsWith("radio.") ? what : "media." + what, e.toString());
            result = false;
        }
        // l'esito si comunica una sola volta, fuori dal try (un errore nella callback non lo ribalta)
        if (onDone != null) {
            try {
                onDone.accept(result);
            } catch (Exception e) {
                Diag.error("media." + what + ".esito", e.toString());
            }
        }
        poll();
    }

    /**
     * Sorgente radio "su": srcPlayRadio del servizio radio (IRadioAppService 27, 26 su 3A111), lo stesso metodo con cui il
     * sistema passa l'audio alla radio. Serve quando prima suonava un'altra sorgente (Bluetooth, USB): la sola sintonia
     * non prende l'audio e il servizio risponde NOT_INITIALIZED finché il sintonizzatore non è aperto.
     */
    private void radioSourceUp(String why) throws RemoteException {
        IBinder b = radio.binder();
        if (b == null) throw new RemoteException("servizio radio non collegato");
        if (radPlay < 0) return;
        markRadio("sorgente radio (" + radPlay + ") · " + why);
        Rpc.call(b, RAD, radPlay, Rpc.NONE, Parcel::readInt);
    }

    private void startSource(int src) throws RemoteException {
        if (src != SRC_RADIO && radioPlaying) radioPlaying = false;
        switch (src) {
            case SRC_USB: {
                IBinder b = usb.binder();
                Rpc.call(b, USB, USB_FOCUS, Rpc.NONE);
                Rpc.call(b, USB, USB_PLAY, Rpc.NONE, Parcel::readInt);
                break;
            }
            case SRC_BT:
                btStart(0, true, null);
                break;
            case SRC_ONLINE: {
                IBinder b = online.binder();
                Rpc.call(b, ONL, ONL_FOCUS, Rpc.NONE);
                Rpc.call(b, ONL, ONL_PLAY, Rpc.NONE, Parcel::readInt);
                // 0.9: se dopo qualche secondo non suona nulla (es. Amazon Music senza login) si apre l'app
                if (bg != null) bg.postDelayed(() -> {
                    State a = last;
                    if (chosen == SRC_ONLINE && !a.playing && !hasOnlineTrack()) openOnlineApp("nessun brano dopo la scelta");
                }, 3500);
                break;
            }
            case SRC_RADIO:
                radioResume();
                break;
            case SRC_CARPLAY:
            case SRC_AAUTO:
                remotePlay(true);
                break;
            default:
                break;
        }
    }

    // ---- radio: pausa e ripresa come la home originale ---------------------------------------

    /** Pausa: setMuted(false, banda), lo stesso comando usato dal launcher MG. */
    private void radioPause() throws RemoteException {
        Station cur = current;
        int band = cur != null ? cur.band : BAND_FM;
        markRadio("pausa (18)");
        Rpc.call(radio.binder(), RAD, RAD_SET_MUTED, p -> {
            p.writeInt(0);
            p.writeInt(band);
        });
        radioPlaying = false;
        radioHoldUntil = android.os.SystemClock.uptimeMillis() + 3000;
    }

    /**
     * Avvio/ripresa della radio (1.0.2, punto 9b): se prima suonava un'altra sorgente si riattiva la sorgente radio
     * (srcPlayRadio: cambio sorgente e fuoco audio, come fa il sistema), poi si risintonizza l'ultima stazione
     * (FM/AM per frequenza, DAB per servizio). Con un servizio non ancora pronto ci pensa attempt() a riprovare.
     */
    private void radioResume() throws RemoteException {
        IBinder b = radio.binder();
        if (b == null) throw new RemoteException("servizio radio non collegato");
        Station cur = current;
        boolean otherSource = last.activeSrc != SRC_RADIO;
        if (otherSource && radPlay >= 0) radioSourceUp("prima suonava " + sourceName(last.activeSrc));
        if (cur != null && cur.band == BAND_DAB && cur.sid != 0) {
            markRadio("ripresa DAB (9)");
            Rpc.call(b, RAD, RAD_TUNE_DAB, p -> {
                p.writeInt(cur.index);
                p.writeLong(cur.sid);
                p.writeInt(cur.eid);
                p.writeInt(cur.secId);
            });
        } else if (cur != null && cur.khz > 0) {
            markRadio("ripresa FM/AM (8)");
            Rpc.call(b, RAD, RAD_TUNE, p -> {
                p.writeInt(cur.band);
                p.writeInt(cur.khz);
            });
        } else {
            if (radPlay < 0) {
                // servizio radio non verificato: niente codice di play; su richiesta dell'utente si apre l'app Radio
                if (!autoRadio) openRadioApp("play · servizio radio non verificato");
                return;
            }
            if (!otherSource) radioSourceUp("play senza stazione nota");
        }
        radioPlaying = true;
        radioHoldUntil = android.os.SystemClock.uptimeMillis() + 3000;
    }

    // ---- 1.0.2 (punto 9): Bluetooth affidabile, comandi e informazioni del brano --------------------

    private static final long[] BT_RETRY_MS = {1000, 2000};

    /**
     * Avvio del Bluetooth come la home originale: fuoco audio Bluetooth (requestBtAudioFocus), una breve attesa perché il
     * servizio media prenda l'audio, poi play. Dopo 2,5 s si controlla che suoni: se no si riprova (attesa 1 e 2 s) e solo
     * alla fine, se l'ha chiesto l'utente, si apre l'app Musica originale. onDone (può essere null) riceve l'esito.
     */
    private void btStart(int n, boolean byUser, java.util.function.Consumer<Boolean> onDone) {
        // 1.0.3: al primo tentativo si aspetta che il telefono sia collegato davvero (A2DP e AVRCP): all'accensione al
        // massimo 30 s, se l'ha chiesto l'utente al massimo 5 s (poi, se non va, si apre l'app Musica come prima)
        int gen = ++btGen;   // un nuovo avvio annulla quello eventualmente ancora in attesa
        if (n == 0) {
            long max = byUser ? 5000 : BT_WAIT_MAX_MS;
            post(() -> btWaitConnected(gen, android.os.SystemClock.uptimeMillis(), -1, max, () -> btStartNow(gen, n, byUser, onDone)));
            return;
        }
        btStartNow(gen, n, byUser, onDone);
    }

    /** 1.0.3: cresce a ogni avvio Bluetooth, a ogni scelta di sorgente e a stop(): i passi vecchi si fermano da soli. */
    private volatile int btGen;

    /** True (e nel log) se questo avvio Bluetooth è stato superato da un'altra scelta. */
    private boolean btCancelled(int gen, java.util.function.Consumer<Boolean> onDone) {
        if (gen == btGen) return false;
        Diag.put("media.bt.esito", "avvio annullato: nel frattempo è stata scelta un'altra sorgente · " + clock());
        if (onDone != null) resuming = false;   // ripresa all'accensione: niente ripiego sulla radio, decide l'utente
        return true;
    }

    private static final long BT_WAIT_MAX_MS = 30_000, BT_WAIT_STEP_MS = 1000, BT_AVRCP_GRACE_MS = 3000;
    /** Profili Bluetooth lato auto (costanti nascoste di BluetoothProfile): A2DP sink 11, AVRCP controller 12. */
    private static final int PROFILE_A2DP_SINK = 11, PROFILE_AVRCP_CTRL = 12;

    /**
     * Aspetta (sul thread di lavoro, a passi di 1 s) che il servizio media dica "Bluetooth collegato" (getBluetoothState
     * = 2) e che anche il profilo AVRCP risulti collegato (o non leggibile, o 3 s dopo A2DP). Al massimo 30 s, poi si
     * prova comunque. In media.bt.attesa: quanti secondi ha aspettato e perché si è fermato.
     */
    private void btWaitConnected(int gen, long since, long a2dpAt, long max, Runnable then) {
        if (gen != btGen) {
            then.run();   // btStartNow se ne accorge e si ferma (scrive il log)
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        int conn = -1;
        try {
            IBinder b = bt.binder();
            if (b != null) conn = Rpc.callInt(b, BT, BT_CONN);
        } catch (Exception ignored) {
        }
        int a2dp = profileState(PROFILE_A2DP_SINK), avrcp = profileState(PROFILE_AVRCP_CTRL);
        boolean a2dpOk = conn == 2 || a2dp == 2;
        if (a2dpOk && a2dpAt < 0) a2dpAt = now;
        boolean avrcpOk = avrcp == 2 || avrcp < 0 || a2dpAt >= 0 && now - a2dpAt >= BT_AVRCP_GRACE_MS;
        long waited = now - since;
        String states = "servizio media " + conn + " · A2DP " + profileTxt(a2dp) + " · AVRCP " + profileTxt(avrcp);
        if (a2dpOk && avrcpOk || waited >= max) {
            Diag.put("media.bt.attesa", "aspettato " + Math.round(waited / 1000.0) + " s · "
                    + (a2dpOk && avrcpOk ? "collegato" : "non collegato dopo " + (max / 1000) + " s: provo lo stesso") + " · " + states + " · " + clock());
            then.run();
            return;
        }
        final long at = a2dpAt;
        bg.postDelayed(() -> btWaitConnected(gen, since, at, max, then), BT_WAIT_STEP_MS);
    }

    /** Stato di un profilo Bluetooth: 2 collegato, 0 scollegato, -1 non leggibile. */
    private static int profileState(int profile) {
        try {
            android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) return ad == null ? -1 : 0;
            return ad.getProfileConnectionState(profile);
        } catch (Throwable e) {
            return -1;
        }
    }

    private static String profileTxt(int st) {
        return st == 2 ? "collegato" : st == 1 ? "in collegamento" : st == 0 ? "scollegato" : st < 0 ? "non leggibile" : String.valueOf(st);
    }

    /** Controller della sessione media Bluetooth attiva (A2DP sink), null se non c'è. */
    private MediaController btSession() {
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController mc : msm.getActiveSessions(null)) {
                if (mc.getPackageName().toLowerCase(java.util.Locale.ROOT).contains("bluetooth")) return mc;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void btStartNow(int gen, int n, boolean byUser, java.util.function.Consumer<Boolean> onDone) {
        post(() -> {
            if (btCancelled(gen, onDone)) return;
            String err = null;
            try {
                IBinder b = bt.binder();
                if (b == null) throw new RemoteException("binder non disponibile");
                Rpc.call(b, BT, BT_FOCUS, Rpc.NONE);
                Thread.sleep(500);
                // 1.0.3: play dalla sessione media Bluetooth (come fa il servizio media), altrimenti dal servizio SAIC
                MediaController mc = btSession();
                if (mc != null) {
                    mc.getTransportControls().play();
                    btCommand("play (avvio, sessione Bluetooth)", null);
                } else {
                    Integer r = Rpc.call(b, BT, BT_PLAY, Rpc.NONE, Parcel::readInt);
                    btCommand("play (avvio, servizio media)", r);
                }
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            }
            final String error = err;
            bg.postDelayed(() -> {
                if (btCancelled(gen, onDone)) return;
                boolean playing = false;
                try {
                    IBinder b = bt.binder();
                    playing = b != null && Rpc.callBool(b, BT, BT_STATE);
                } catch (Exception ignored) {
                }
                if (playing) {
                    Diag.put("media.bt.esito", "partito al tentativo " + (n + 1) + " · " + clock());
                    if (onDone != null) onDone.accept(true);
                    poll();
                    return;
                }
                if (n < BT_RETRY_MS.length) {
                    Diag.put("media.bt.esito", "non partito al tentativo " + (n + 1) + (error == null ? "" : " (" + error + ")")
                            + ": riprovo tra " + (BT_RETRY_MS[n] / 1000) + " s");
                    bg.postDelayed(() -> btStartNow(gen, n + 1, byUser, onDone), BT_RETRY_MS[n]);
                    return;
                }
                Diag.put("media.bt.esito", "non partito dopo " + (n + 1) + " tentativi" + (error == null ? "" : " (" + error + ")")
                        + (byUser ? ": apro l'app Musica" : ""));
                if (byUser) {
                    main.post(() -> {
                        try {
                            ctx.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName("com.saicmotor.hmi.music",
                                    "com.saicmotor.hmi.music.ui.activity.MusicActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception e) {
                            Diag.error("media.bt.app", e.toString());
                        }
                    });
                }
                if (onDone != null) onDone.accept(false);
                poll();
            }, 2500);
        });
    }

    private final java.util.Map<String, String> btCmds = new java.util.TreeMap<>();

    /** media.bt.comandi: esito dell'ultimo play/pausa/avanti/indietro (valore restituito dal servizio media). */
    private void btCommand(String what, Integer result) {
        synchronized (btCmds) {
            btCmds.put(what, (result == null ? "inviato" : "risposta " + result) + " alle " + clock());
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : btCmds.entrySet()) sb.append(sb.length() == 0 ? "" : " · ").append(e.getKey()).append(' ').append(e.getValue());
            Diag.put("media.bt.comandi", sb.toString());
        }
    }

    private String btArtKey = "";
    private Bitmap btArt;
    private long btSessionAt;
    private String btSessTitle = "", btSessArtist = "", btSessAlbum = "";
    private long btSessDur;
    private Bitmap btSessArt;

    /**
     * Titolo, artista e copertina del brano Bluetooth: dal servizio media (metadati AVRCP) e, se manca qualcosa, dalla
     * sessione media del Bluetooth di Android. Se il telefono non manda nulla la card mostra l'icona Bluetooth.
     */
    private void btInfo(State s, Audio a) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - btSessionAt > 3000) {
            btSessionAt = now;
            btSessTitle = "";
            btSessArtist = "";
            btSessAlbum = "";
            btSessDur = 0;
            btSessArt = null;
            try {
                android.media.session.MediaSessionManager msm =
                        (android.media.session.MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
                for (MediaController mc : msm.getActiveSessions(null)) {
                    String pkg = mc.getPackageName().toLowerCase(java.util.Locale.ROOT);
                    if (!pkg.contains("bluetooth")) continue;
                    MediaMetadata md = mc.getMetadata();
                    if (md == null) continue;
                    btSessTitle = nz(md.getString(MediaMetadata.METADATA_KEY_TITLE));
                    btSessArtist = nz(md.getString(MediaMetadata.METADATA_KEY_ARTIST));
                    btSessAlbum = nz(md.getString(MediaMetadata.METADATA_KEY_ALBUM));
                    btSessDur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    btSessArt = art;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        String metaSrc;
        if (!btSessTitle.isEmpty()) {
            s.title = btSessTitle;
            s.subtitle = join(btSessArtist, btSessAlbum);
            if (btSessDur > 0) s.durationMs = btSessDur;
            metaSrc = "sessione Bluetooth";
        } else if (a != null && !nz(a.name).isEmpty()) {
            s.title = nz(a.name);
            s.subtitle = join(a.artist, a.album);
            s.durationMs = a.duration;
            metaSrc = "servizio media SAIC (la sessione non li dà)";
        } else {
            metaSrc = "nessuna";
        }
        String artSrc = "no";
        String cover = a == null ? "" : nz(a.cover);
        if (!cover.isEmpty() && !cover.startsWith("http")) {
            if (!cover.equals(btArtKey)) {
                btArtKey = cover;
                btArt = null;
                try {
                    java.io.File f = new java.io.File(cover);
                    if (f.isFile() && f.length() < 4_000_000) {
                        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                        o.inSampleSize = f.length() > 600_000 ? 2 : 1;
                        btArt = android.graphics.BitmapFactory.decodeFile(cover, o);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (btArt != null) {
                s.art = btArt;
                artSrc = "sì (servizio media)";
            }
        }
        if (s.art == null && btSessArt != null) {
            s.art = btSessArt;
            artSrc = "sì (sessione Bluetooth)";
        }
        boolean title = a != null && !nz(a.name).isEmpty() || !btSessTitle.isEmpty();
        boolean artist = a != null && !nz(a.artist).isEmpty() || !btSessArtist.isEmpty();
        btMetaSource = metaSrc;
        Diag.put("media.bt.info", "titolo " + (title ? "sì" : "no") + " · artista " + (artist ? "sì" : "no") + " · copertina " + artSrc
                + " · titolo/artista da " + metaSrc);
    }

    // ---- 1.0.2 (punto 9a): ultima sorgente usata davvero, ripresa all'accensione -------------------

    private static final String K_LAST_SRC = "ultimaSorgenteAudio";
    private int realCandidate;
    private long realSince;

    /** Una sorgente che suona per almeno 5 s diventa "l'ultima usata davvero" (salvata per la prossima accensione). */
    private void noteRealSource(State s) {
        int src = s.activeSrc;
        boolean playing = src != 0 && src == s.source && s.playing;
        long now = android.os.SystemClock.uptimeMillis();
        if (!playing || resuming) {
            realCandidate = 0;
            return;
        }
        if (src != realCandidate) {
            realCandidate = src;
            realSince = now;
            return;
        }
        if (now - realSince >= 5000) {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
            if (sp.getInt(K_LAST_SRC, 0) != src) {
                sp.edit().putInt(K_LAST_SRC, src).apply();
                Diag.put("media.ultimaSorgente", sourceName(src) + " · " + clock());
            }
        }
    }

    private volatile boolean resuming;
    private long lastPowerOn;

    /**
     * Accensione (avvio del launcher o risveglio dello schermo): dopo 8 s, se non suona già niente, si riprende l'ultima
     * sorgente usata davvero, come fa l'app Media originale. Se non è disponibile (telefono non collegato) si passa alla
     * radio, avviata davvero (sorgente, fuoco audio, ultima stazione).
     */
    public void onPowerOn(String why) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastPowerOn < 30_000) return;
        lastPowerOn = now;
        if (bg == null) {
            pendingPowerOn = why;   // i servizi partono subito dopo il primo disegno: la ripresa si programma in start()
            return;
        }
        bg.postDelayed(() -> resumeAfterPowerOn(why), 8000);
    }

    private volatile String pendingPowerOn;

    private void resumeAfterPowerOn(String why) {
        State s = last;
        int saved = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getInt(K_LAST_SRC, 0);
        String head = "sorgente salvata " + (saved == 0 ? "nessuna" : sourceName(saved)) + " (" + why + ")";
        if (s.activeSrc != 0 && s.playing) {
            Diag.put("media.avvio", head + " · ripresa: già in riproduzione " + sourceName(s.activeSrc) + " (lasciata) · " + clock());
            return;
        }
        if (saved == 0) {
            Diag.put("media.avvio", head + " · ripresa: niente da riprendere · " + clock());
            return;
        }
        resuming = true;
        chosen = saved;   // si mostra subito la sorgente giusta, non la radio
        switch (saved) {
            case SRC_BT:
                btStart(0, false, ok -> {
                    if (ok) finishResume(head, "BLUETOOTH", "riuscita", 0);
                    else fallbackRadio(head, "Bluetooth non disponibile (telefono non collegato?)");
                });
                break;
            case SRC_CARPLAY:
            case SRC_AAUTO:
                if (s.deviceType == 0) {
                    fallbackRadio(head, "CarPlay/Android Auto non collegato");
                } else {
                    try {
                        remotePlay(true);
                        finishResume(head, sourceName(saved), "inviato", 0);
                    } catch (Exception e) {
                        fallbackRadio(head, "comando non riuscito: " + e.getClass().getSimpleName());
                    }
                }
                break;
            case SRC_USB:
            case SRC_ONLINE:
                attempt("avvio " + sourceName(saved), () -> startSource(saved), 0, false, false, ok -> bg.postDelayed(() -> {
                    if (last.playing && last.activeSrc == saved) finishResume(head, sourceName(saved), "riuscita", 0);
                    else fallbackRadio(head, sourceName(saved) + " non parte");
                }, 3000));
                break;
            default:
                startRadioForResume(head, "radio");
                break;
        }
    }

    private void fallbackRadio(String head, String why) {
        // il sistema potrebbe aver ripreso da solo qualcosa nel frattempo: in quel caso non si tocca
        State s = last;
        if (s.activeSrc != 0 && s.playing) {
            finishResume(head, sourceName(s.activeSrc), "ripresa dal sistema", 0);
            return;
        }
        chosen = SRC_RADIO;
        startRadioForResume(head, why + " → radio");
    }

    private void startRadioForResume(String head, String what) {
        startRadioForResume(head, what, 0);
    }

    private void startRadioForResume(String head, String what, int waits) {
        if (radio.fragile()) {
            finishResume(head, "RADIO", "non avviata: radio protetta", 0);
            return;
        }
        String block = oldRadioBlock();
        if (block != null) {
            // firmware vecchio: il servizio radio va comandato solo dopo 30 s dal suo avvio; si riprova quando è pronto
            if (waits < 12 && bg != null) {
                Diag.put("media.avvio", head + " · ripresa RADIO in attesa: " + block);
                bg.postDelayed(() -> startRadioForResume(head, what, waits + 1), 10_000);
            } else {
                finishResume(head, "RADIO", "non avviata: " + block, 0);
            }
            return;
        }
        final int[] tries = {0};
        attempt("avvio RADIO", () -> {
            tries[0]++;
            autoRadio = true;
            try {
                radioResume();
            } finally {
                autoRadio = false;
            }
        }, 0, true, true, ok -> finishResume(head, "RADIO (" + what + ")", ok ? "riuscita" : "non riuscita", tries[0]));
    }

    /** Avvio radio automatico (accensione): mai aprire l'app Radio sopra il launcher. */
    private volatile boolean autoRadio;

    private void finishResume(String head, String resumed, String result, int tries) {
        resuming = false;
        Diag.put("media.avvio", head + " · ripresa " + resumed + " · esito " + result + (tries > 0 ? " · tentativi " + tries : "") + " · " + clock());
        poll();
    }

    // ---- preferiti radio -------------------------------------------------------------------

    /** Legge i preferiti FM e DAB dal servizio radio (al massimo ogni 30 s). */
    private void loadFavorites(IBinder b) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - favoritesAt < 30_000 && !favorites.isEmpty()) {
            for (Station st : favorites) if (st.logo == null) st.logo = logos.get(st.key, st.name);
            return;
        }
        favoritesAt = now;
        List<Station> out = new ArrayList<>();
        for (int band : new int[]{BAND_FM, BAND_DAB}) {
            try {
                markRadio("preferiti (7)");
                List<Station> l = Rpc.call(b, RAD, RAD_FAVORITES, p -> p.writeInt(band), MediaClient::readStations);
                if (l != null) out.addAll(l);
            } catch (Exception e) {
                Diag.error("radio.preferiti", e.toString());
            }
        }
        for (Station st : out) {
            st.key = StationLogos.key(st.band, st.sid, st.khz, st.name);
            st.logo = logos.get(st.key, st.name);
        }
        Diag.put("radio.preferiti", out.size() + " stazioni");
        favorites = out;
    }

    /**
     * Stazioni trovate dalla radio sulla banda attuale (getRadioList), al massimo ogni 30 s o al cambio di banda.
     * FM in ordine di frequenza, DAB in ordine alfabetico; doppioni tolti.
     */
    private void loadStations(IBinder b, int band) {
        long now = android.os.SystemClock.uptimeMillis();
        if (band == stationsBand && now - stationsAt < 30_000) {
            for (Station st : stations) if (st.logo == null) st.logo = logos.get(st.key, st.name);
            return;
        }
        stationsAt = now;
        stationsBand = band;
        List<Station> out = new ArrayList<>();
        String src = "elenco della ricerca (" + radList + ")";
        if (radList < 0) src = "nessuna (codici sconosciuti)";
        else try {
            markRadio("elenco stazioni (" + radList + ")");
            List<Station> l = Rpc.call(b, RAD, radList, p -> p.writeInt(band), MediaClient::readStations);
            java.util.Set<String> seen = new java.util.HashSet<>();
            if (l != null) {
                for (Station st : l) {
                    if (st.band != band) continue;
                    if (band == BAND_DAB ? st.sid == 0 : st.khz <= 0) continue;
                    st.key = StationLogos.key(st.band, st.sid, st.khz, st.name);
                    if (!seen.add(st.key)) continue;
                    st.logo = logos.get(st.key, st.name);
                    out.add(st);
                }
            }
        } catch (Exception e) {
            Diag.error("radio.elenco", e.toString());
        }
        // 1.0.3: elenco della ricerca vuoto (mai fatta una ricerca, o codici sconosciuti) → i preferiti di questa banda
        if (out.isEmpty()) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Station st : favorites) if (st.band == band && st.key != null && seen.add(st.key)) out.add(st);
            if (!out.isEmpty()) src = "preferiti (elenco della ricerca vuoto)";
        }
        // paese RDS dalle stazioni trovate (conferma o corregge il paese automatico dei loghi)
        LogoPacks.noteStations(band, out);
        if (band == BAND_DAB) {
            java.util.Collections.sort(out, (x, y) -> x.label().compareToIgnoreCase(y.label()));
        } else {
            java.util.Collections.sort(out, (x, y) -> Integer.compare(x.khz, y.khz));
        }
        Diag.put("radio.elenco", "fonte " + src + " · " + (band == BAND_DAB ? "DAB " : "FM ") + out.size() + " stazioni · aggiornato: " + radioListWhy);
        stations = out;
    }

    /**
     * 1.0.3: rilegge elenco stazioni e preferiti al prossimo giro (ritorno dall'app Radio nel launcher, callback del
     * servizio radio). Prima l'elenco restava quello vecchio fino a 30 s.
     */
    public void refreshRadioLists(String why) {
        radioListWhy = why;
        Handler h = bg;
        if (h == null) return;
        // durante una ricerca il servizio radio avvisa a ogni stazione trovata: si rilegge una volta sola, 0,5 s dopo
        h.removeCallbacks(radioRefresh);
        h.postDelayed(radioRefresh, 500);
    }

    private final Runnable radioRefresh = () -> {
        stationsAt = 0;
        favoritesAt = 0;
        if (running) poll();
    };

    private volatile String radioListWhy = "avvio";
    private volatile boolean radioListenerOn;

    /**
     * Callback IRadioListener (solo con la tabella codici verificata): 2 = onProgramListChanged (elenco della ricerca),
     * 5 = onCollectionListChange (preferiti). Non si legge il contenuto: si rilegge l'elenco con i codici 22 e 7.
     * Agli altri codici si risponde senza leggere niente.
     */
    private final Binder radioListener = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(RAD_EVT);
                return true;
            }
            if (code == 2) refreshRadioLists("servizio radio: elenco cambiato");
            else if (code == 5) refreshRadioLists("servizio radio: preferiti cambiati");
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    private void registerRadioListener() {
        if (radList != 22) {
            Diag.put("radio.ascolto", "non registrato: tabella codici " + radTable + " (elenco riletto ogni 30 s e al ritorno nel launcher)");
            return;
        }
        try {
            Rpc.call(radio.binder(), RAD, RAD_ADD_LISTENER, p -> p.writeStrongBinder(radioListener));
            radioListenerOn = true;
            Diag.put("radio.ascolto", "registrato (aggiornamento automatico di elenco e preferiti)");
        } catch (Exception e) {
            Diag.error("radio.ascolto", e.toString());
        }
    }

    private void unregisterRadioListener() {
        if (!radioListenerOn) return;
        radioListenerOn = false;
        IBinder b = radio.binder();
        if (b == null) return;
        new Thread(() -> {
            try {
                Rpc.call(b, RAD, RAD_REMOVE_LISTENER, p -> p.writeStrongBinder(radioListener));
            } catch (Exception ignored) {
            }
        }, "mg4-radio-ascolto").start();
    }

    // ---- 1.0.3: seconda porta USB --------------------------------------------------------------

    /** getUsbDevices() di IMusicPlayerBinderInterface: lista di UsbDevicesInfoBean (uuid, label, port, path, scanState). */
    private static final int USB_DEVICES = 3;
    private long usbPortsAt;
    private String usbPortsLast = "";

    /**
     * Sulla SWI68 non esiste un servizio USB2 separato (né com.saicmotor.media né Usb2MediaBrowserService): un solo
     * lettore del servizio media suona da entrambe le porte. USB 2 quindi non diventa una sorgente a parte; si scrive
     * nel log quali porte sono montate e da quale suona il brano (dal percorso del file). Porte del firmware: "usb3" è
     * USB 1 e "usb1" è USB 2 secondo l'SDK SAIC (da confermare in auto con questo log). Il nome del disco non si scrive.
     */
    private void usbPorts(IBinder b, String playingPath, boolean force) throws RemoteException {
        long now = android.os.SystemClock.uptimeMillis();
        if (b == null || !force && now - usbPortsAt < 60_000) return;
        usbPortsAt = now;
        List<String[]> devs = Rpc.call(b, USB, USB_DEVICES, Rpc.NONE, p -> {
            List<String[]> out = new ArrayList<>();
            int n = p.readInt();
            for (int i = 0; i < n && i < 8; i++) {
                if (p.readInt() == 0) continue;
                p.readString();                         // uuid
                p.readString();                         // etichetta del disco (non si scrive)
                String port = p.readString(), path = p.readString();
                int scan = p.readInt();
                out.add(new String[]{nz(port), nz(path), String.valueOf(scan)});
            }
            return out;
        });
        StringBuilder sb = new StringBuilder("SWI68: nessun servizio USB2 separato (un solo lettore per le due porte): USB 2 non mostrata a parte");
        sb.append(" · dispositivi ");
        String playing = "—";
        if (devs == null || devs.isEmpty()) sb.append("nessuno");
        else for (int i = 0; i < devs.size(); i++) {
            String[] d = devs.get(i);
            String name = "usb3".equals(d[0]) ? "USB 1" : "usb1".equals(d[0]) ? "USB 2" : "porta ?";
            sb.append(i == 0 ? "" : ", ").append(name).append(" (porta ").append(d[0]).append(", scansione ").append(d[2]).append(')');
            if (playingPath != null && !d[1].isEmpty() && playingPath.startsWith(d[1])) playing = name + " (porta " + d[0] + ")";
        }
        sb.append(" · brano da ").append(playingPath == null ? "—" : playing);
        String txt = sb.toString();
        if (!txt.equals(usbPortsLast)) {
            usbPortsLast = txt;
            Diag.put("media.usb2", txt);
        }
    }

    /** Lista di RadioBean (createTypedArrayList): legge ogni elemento per intero. */
    private static List<Station> readStations(Parcel p) {
        List<Station> out = new ArrayList<>();
        int n = p.readInt();
        for (int i = 0; i < n && i < 60; i++) {
            if (p.readInt() == 0) continue;
            Station st = readStationHead(p);
            p.createCharArray();                      // ensemble
            p.createCharArray();                      // dlPlus
            p.createCharArray();                      // dls
            p.readInt();                              // isTitle
            int slides = p.readInt();                 // slideshow DAB (Bitmap)
            for (int k = 0; k < slides; k++) if (p.readInt() != 0) Bitmap.CREATOR.createFromParcel(p);
            int subs = p.readInt();                   // sotto-programmi DAB
            for (int k = 0; k < subs; k++) {
                if (p.readInt() == 0) continue;
                p.readString();
                p.readString();
                p.readInt();
                p.readLong();
                p.readInt();
                p.readInt();
                p.readInt();
            }
            p.readParcelable(null);                   // RadioMetadata (classe di sistema)
            out.add(st);
        }
        return out;
    }

    /** Prima parte di un RadioBean, fino al nome del servizio DAB compreso (il resto non serve). */
    private static Station readStationHead(Parcel p) {
        Station st = new Station();
        p.readInt();                              // enable
        String name = p.readString();
        p.readInt();                              // isRds
        p.readString();                           // cover
        st.khz = p.readInt();
        st.band = p.readInt();
        p.readInt();                              // state
        p.readInt();                              // isCollection
        p.readString();                           // subStationName
        p.readInt();                              // rssi
        st.eid = p.readInt();
        st.sid = p.readLong();
        p.readInt();                              // pty
        st.secId = p.readInt();
        st.index = p.readInt();
        char[] service = p.createCharArray();
        String sv = service != null ? new String(service).trim() : "";
        st.name = st.band == BAND_DAB && !sv.isEmpty() ? sv : nz(name);
        return st;
    }

    /**
     * Cambia banda (FM / DAB) come l'app Radio: riprende l'ultima stazione ascoltata su quella banda
     * (getLastRadioInfo), altrimenti il primo preferito o la prima stazione trovata.
     */
    public void switchBand(int band) {
        chosen = SRC_RADIO;
        radioCommand("radio.banda", () -> {
            {
                IBinder b = radio.binder();
                if (b == null) throw new RemoteException("servizio radio non collegato");
                Station st = null;
                try {
                    st = radLastInfo < 0 ? null
                            : Rpc.call(b, RAD, radLastInfo, p -> p.writeInt(band), p -> Rpc.readNullable(p, MediaClient::readStationHead));
                } catch (Exception e) {
                    Diag.error("radio.banda.ultima", e.toString());
                }
                if (!valid(st, band)) {
                    st = null;
                    for (Station f : favorites) if (f.band == band) { st = f; break; }
                }
                if (st == null) {
                    try {
                        List<Station> list = radList < 0 ? null
                                : Rpc.call(b, RAD, radList, p -> p.writeInt(band), MediaClient::readStations);
                        if (list != null) for (Station f : list) if (valid(f, band)) { st = f; break; }
                    } catch (Exception e) {
                        Diag.error("radio.banda.elenco", e.toString());
                    }
                }
                Diag.put("radio.banda.scelta", band + (st == null ? " (nessuna stazione)" : " → " + st.label()));
                if (st == null && band == BAND_FM) {
                    Station fm = new Station();
                    fm.band = BAND_FM;
                    fm.khz = 87500;
                    st = fm;
                }
                if (last.activeSrc != SRC_RADIO) radioSourceUp("cambio banda");
                if (st != null) tuneNow(b, st);
            }
        });
    }

    private static boolean valid(Station st, int band) {
        if (st == null || st.band != band) return false;
        return band == BAND_DAB ? st.sid != 0 : st.khz > 0;
    }

    private void tuneNow(IBinder b, Station st) throws RemoteException {
        markRadio(st.band == BAND_DAB ? "sintonia DAB (9)" : "sintonia FM/AM (8)");
        if (st.band == BAND_DAB) {
            Rpc.call(b, RAD, RAD_TUNE_DAB, p -> {
                p.writeInt(st.index);
                p.writeLong(st.sid);
                p.writeInt(st.eid);
                p.writeInt(st.secId);
            });
        } else {
            Rpc.call(b, RAD, RAD_TUNE, p -> {
                p.writeInt(st.band);
                p.writeInt(st.khz);
            });
        }
        radioPlaying = true;
        radioHoldUntil = android.os.SystemClock.uptimeMillis() + 3000;
    }

    /** Sintonizza una stazione dei preferiti e passa alla radio. */
    public void tune(Station st) {
        chosen = SRC_RADIO;
        radioCommand("radio.sintonia", () -> {
            IBinder b = radio.binder();
            if (b == null) throw new RemoteException("servizio radio non collegato");
            if (last.activeSrc != SRC_RADIO) radioSourceUp("sintonia stazione");
            tuneNow(b, st);
        });
    }

    private void refreshLogosSoon() {
        post(() -> {
            for (Station st : favorites) if (st.logo == null) st.logo = logos.get(st.key, st.name);
            for (Station st : stations) if (st.logo == null) st.logo = logos.get(st.key, st.name);
            poll();
        });
    }

    public void playPause() {
        command("play", () -> {
            State s = last;
            {
                switch (s.source) {
                    case SRC_USB:
                        Rpc.call(usb.binder(), USB, USB_PLAY_OR_PAUSE, Rpc.NONE);
                        break;
                    case SRC_BT: {
                        if (!s.playing && s.activeSrc != SRC_BT) {
                            // il Bluetooth non è la sorgente attiva: play = avvio vero (fuoco audio + play, con verifica)
                            btStart(0, true, null);
                            break;
                        }
                        Integer r = Rpc.call(bt.binder(), BT, s.playing ? BT_PAUSE : BT_PLAY, Rpc.NONE, Parcel::readInt);
                        btCommand(s.playing ? "pausa" : "play", r);
                        break;
                    }
                    case SRC_ONLINE:
                        Rpc.call(online.binder(), ONL, s.playing ? ONL_PAUSE : ONL_PLAY, Rpc.NONE, Parcel::readInt);
                        break;
                    case SRC_RADIO:
                        if (s.playing) radioPause();
                        else radioResume();
                        break;
                    case SRC_CARPLAY:
                    case SRC_AAUTO:
                        remotePlay(!s.playing);
                        break;
                    default:
                        break;
                }
            }
        });
    }

    public void skip(boolean forward) {
        command(forward ? "salto avanti" : "salto indietro", () -> {
            State s = last;
            {
                switch (s.source) {
                    case SRC_USB:
                        Rpc.call(usb.binder(), USB, forward ? USB_NEXT : USB_PREV, Rpc.NONE);
                        break;
                    case SRC_BT: {
                        Integer r = Rpc.call(bt.binder(), BT, forward ? BT_NEXT : BT_PREV, Rpc.NONE, Parcel::readInt);
                        btCommand(forward ? "avanti" : "indietro", r);
                        break;
                    }
                    case SRC_ONLINE:
                        Rpc.call(online.binder(), ONL, forward ? ONL_NEXT : ONL_PREV, Rpc.NONE, Parcel::readInt);
                        break;
                    case SRC_RADIO: {
                        int band = s.radioBand > 0 ? s.radioBand : BAND_FM;
                        markRadio(forward ? "avanti (13)" : "indietro (14)");
                        Rpc.call(radio.binder(), RAD, forward ? RAD_NEXT : RAD_PREV, p -> p.writeInt(band), Parcel::readInt);
                        break;
                    }
                    case SRC_CARPLAY:
                    case SRC_AAUTO:
                        remoteKey(forward ? 7 : 8, forward ? 11 : 12, forward ? "avanti" : "indietro");
                        break;
                    default:
                        break;
                }
            }
        });
    }

    /** True se la sessione CarPlay/Android Auto dice di essere in riproduzione. */
    private boolean remoteIsPlaying() {
        MediaController c = controller;
        if (c == null) return false;
        try {
            PlaybackState ps = c.getPlaybackState();
            return ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        } catch (Exception e) {
            return false;
        }
    }

    /** Play/pausa di CarPlay/AA: il tasto è uno solo (commuta), quindi si preme solo se serve. */
    private void remotePlay(boolean play) throws RemoteException {
        if (play == remoteIsPlaying() && controller != null) {
            Diag.put("carplay.tasto", (play ? "play" : "pausa") + ": già in quello stato, nessun tasto");
            return;
        }
        remoteKey(20, 21, play ? "play" : "pausa");
    }

    /**
     * Tasti multimediali di CarPlay/Android Auto come il launcher MG originale: sendMediaPlayControlKey del servizio
     * RemoteUI (premuto e poi rilasciato: play/pausa 20/21, avanti 7/11, indietro 8/12). Il controller multimediale di
     * Android con alcuni adattatori wireless viene ignorato, quindi si usa solo se il servizio RemoteUI non c'è.
     */
    private void remoteKey(int down, int up, String what) throws RemoteException {
        IBinder r = rui.binder();
        MediaController c = controller;
        if (r == null && c != null) {
            MediaController.TransportControls tc = c.getTransportControls();
            if (down == 7) tc.skipToNext();
            else if (down == 8) tc.skipToPrevious();
            else if (remoteIsPlaying()) tc.pause();
            else tc.play();
            Diag.put("carplay.tasto", what + " · controller Android (servizio RemoteUI assente)");
            return;
        }
        int a = Rpc.call(r, RUI, RUI_MEDIA_KEY, p -> p.writeInt(down), Parcel::readInt);
        int b = Rpc.call(r, RUI, RUI_MEDIA_KEY, p -> p.writeInt(up), Parcel::readInt);
        Diag.put("carplay.tasto", what + " · sendMediaPlayControlKey " + down + "/" + up + " → " + a + "/" + b);
    }

    /** Apre CarPlay o Android Auto a schermo intero. */
    public boolean openProjection() {
        int type = last.deviceType;
        Intent i = new Intent(Intent.ACTION_MAIN);
        if (type == DEV_AAUTO) {
            i.setComponent(new ComponentName("com.allgo.app.androidauto", "com.allgo.app.androidauto.ProjectionActivity"));
        } else {
            i.setComponent(new ComponentName("com.allgo.carplay.service", "com.allgo.carplay.service.CarPlayActivity"));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Diag.error("carplay.apri", e.toString());
            return false;
        }
    }

    /** Apre l'app di navigazione di CarPlay/AA (launchApp 2, tipo dispositivo). */
    public void openRemoteNavigation() {
        int type = last.deviceType;
        post(() -> {
            try {
                Rpc.call(rui.binder(), RUI, RUI_LAUNCH_APP, p -> {
                    p.writeInt(2);
                    p.writeInt(type);
                }, Parcel::readInt);
            } catch (Exception e) {
                Diag.error("carplay.navigazione", e.toString());
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Callback dal servizio media (avanzamento brano)
    // ------------------------------------------------------------------------------------------

    private final Binder usbListener = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(USB_EVT);
                return true;
            }
            try {
                data.enforceInterface(USB_EVT);
                if (code == 6) {           // onTaskRuntime(total, current, buffer, info)
                    usbDur = data.readLong();
                    usbPos = data.readLong();
                } else if (code == 2) {    // onPrepared(total)
                    usbDur = data.readLong();
                    usbPos = 0;
                }
            } catch (Exception ignored) {
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    private final Binder btListener = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(BT_CB);
                return true;
            }
            try {
                data.enforceInterface(BT_CB);
                if (code == 2) btPos = data.readLong();   // onPlayProgressChanged(progress, info)
            } catch (Exception ignored) {
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    private void registerUsbListener() {
        try {
            Rpc.call(usb.binder(), USB, USB_ADD_LISTENER, p -> p.writeStrongBinder(usbListener));
        } catch (Exception e) {
            Diag.error("media.usb.listener", e.toString());
        }
    }

    private void registerBtListener() {
        try {
            Rpc.call(bt.binder(), BT, BT_REGISTER, p -> p.writeStrongBinder(btListener));
        } catch (Exception e) {
            Diag.error("media.bt.listener", e.toString());
        }
    }

    // ------------------------------------------------------------------------------------------
    // CarPlay / Android Auto: metadati via MediaBrowser (come il launcher originale)
    // ------------------------------------------------------------------------------------------

    private void connectBrowser() {
        main.post(() -> {
            if (browser != null) return;
            ComponentName cn = new ComponentName("com.allgo.remoteui.mediabrowserservice",
                    "com.allgo.remoteui.mediabrowserservice.RemoteUIMediaBrowserService");
            browser = new MediaBrowser(ctx, cn, new MediaBrowser.ConnectionCallback() {
                @Override
                public void onConnected() {
                    try {
                        controller = new MediaController(ctx, browser.getSessionToken());
                        controller.registerCallback(sessionCallback, main);
                        Diag.put("carplay.media", "collegato");
                    } catch (Exception e) {
                        Diag.error("carplay.media", e.toString());
                    }
                }

                @Override
                public void onConnectionSuspended() {
                    controller = null;
                    Diag.put("carplay.media", "sospeso");
                }

                @Override
                public void onConnectionFailed() {
                    Diag.put("carplay.media", "non disponibile");
                    browser = null;
                    if (running) main.postDelayed(MediaClient.this::connectBrowser, 15000);
                }
            }, null);
            try {
                browser.connect();
            } catch (Exception e) {
                Diag.error("carplay.media", e.toString());
            }
        });
    }

    private final MediaController.Callback sessionCallback = new MediaController.Callback() {
        @Override
        public void onSessionEvent(String event, Bundle extras) {
            if ("ACTION_CARPLAY_NAVI_METADATA".equals(event) && extras != null) {
                tbt = extras;
                Diag.put("carplay.navigazione", extras.toString());
            }
        }

        @Override
        public void onExtrasChanged(Bundle extras) {
            if (extras != null && extras.containsKey("IS_TBT_ACTIVE")) tbt = extras;
        }

        @Override
        public void onSessionDestroyed() {
            controller = null;
            browser = null;
            if (running) main.postDelayed(MediaClient.this::connectBrowser, 5000);
        }
    };
}
