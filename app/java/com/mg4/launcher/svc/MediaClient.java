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
    private static final int RAD_NEXT = 13, RAD_PREV = 14, RAD_CURRENT = 19, RAD_PLAY = 27, RAD_PAUSE = 28;
    private static final int RAD_FAVORITES = 7, RAD_TUNE = 8, RAD_TUNE_DAB = 9, RAD_SET_MUTED = 18, RAD_DAB_LOGO = 52;
    private static final int RAD_LAST_INFO = 21, RAD_LIST = 22;
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
        String radioApk = "";
        try {
            radioApk = ctx.getPackageManager().getApplicationInfo(RADIO_PKG, 0).sourceDir;
        } catch (Exception ignored) {
        }
        boolean oldRadio = radioApk.contains("RadioService_eh32_P");
        radio.setAutoCreate(!oldRadio);
        Diag.put("radio.servizio", (radioApk.isEmpty() ? "?" : radioApk.substring(radioApk.lastIndexOf('/') + 1))
                + (oldRadio ? " · firmware vecchio: collegamento senza avvio" : ""));
        logos = new StationLogos(ctx);
        logos.setOnUpdate(this::refreshLogosSoon);
        rui = new ServiceLink(ctx, "carplay", ServiceLink.byComponent("com.allgo.rui", "com.allgo.rui.RemoteUIService", null));
        usb.setOnConnected(() -> post(this::registerUsbListener));
        bt.setOnConnected(() -> post(this::registerBtListener));
    }

    public void setListener(Listener l) {
        listener = l;
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
        bg.post(loop);
    }

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
        if (bg != null) bg.removeCallbacks(loop);
        status.stop();
        usb.stop();
        bt.stop();
        online.stop();
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
        String name, artist, album, playTime;
        int playState, playerType;
    }

    private static Audio readAudio(Parcel p) {
        Audio a = new Audio();
        p.readLong();                 // id
        a.duration = p.readLong();
        a.name = p.readString();
        p.readString();               // cover
        p.readString();               // path
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
        s.sources.add(SRC_ONLINE);
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

        try {
            fill(s, shown, active == shown);
        } catch (Exception e) {
            Diag.error("media." + sourceName(shown), e.toString());
        }

        Bundle t = tbt;
        if (t != null && (s.deviceType != 0)) {
            s.tbtActive = t.getBoolean("IS_TBT_ACTIVE", false);
            s.tbtDistance = (nz(String.valueOf(t.get("DIST_TO_NEXT_JUNCTION"))) + " "
                    + nz(String.valueOf(t.get("DIST_TO_NEXT_JUNCTION_UNIT")))).replace("null", "").trim();
            s.tbtRoad = nz(t.getString("ROAD_AFTER_JUNCTION"));
        }

        last = s;
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
                if (a != null) {
                    s.title = nz(a.name);
                    s.subtitle = join(a.artist, a.album);
                    s.durationMs = a.duration;
                }
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
                    s.subtitle = join(a.artist, "Spotify");
                    s.durationMs = a.duration;
                }
                if (s.title.isEmpty()) s.title = com.mg4.launcher.L.s(com.mg4.launcher.R.string.online_music);
                if (s.subtitle.isEmpty()) s.subtitle = "Spotify";
                break;
            }
            case SRC_RADIO: {
                IBinder b = radio.binder();
                if (radio.fragile()) {
                    s.title = com.mg4.launcher.L.s(com.mg4.launcher.R.string.src_radio);
                    s.subtitle = com.mg4.launcher.L.s(com.mg4.launcher.R.string.radio_protected);
                    return;
                }
                if (b == null) return;
                radio.lastCall = "stazione corrente (19)";
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
                        radio.lastCall = "logo DAB (52)";
                        Bitmap logo = Rpc.call(b, RAD, RAD_DAB_LOGO, Rpc.NONE, p -> Rpc.readNullable(p, Bitmap.CREATOR::createFromParcel));
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
        command("avvio " + sourceName(src), () -> startSource(src));
    }

    // ---- comandi con nuovo tentativo -------------------------------------------------------

    private interface Cmd {
        void run() throws Exception;
    }

    private static final int RETRIES = 6;
    private static final long RETRY_MS = 1500;

    /**
     * Esegue un comando media. Subito dopo l'accensione il servizio può non essere ancora pronto
     * ("binder non disponibile", "Result::NOT_INITIALIZED"): in quel caso si riprova finché non lo è, senza errori.
     * L'esito di play e salto finisce nella diagnostica (media.comando).
     */
    private void command(String what, Cmd cmd) {
        post(() -> {
            if (radio.fragile() && (last.source == SRC_RADIO || what.endsWith(sourceName(SRC_RADIO)))) {
                Diag.put("media.comando", what + " · radio protetta: comando non inviato (usa l'app Radio)");
                return;
            }
            attempt(what, cmd, 0);
        });
    }

    private void attempt(String what, Cmd cmd, int n) {
        State before = last;
        try {
            cmd.run();
            if (n > 0) Diag.put("media.attesa", what + ": eseguito al tentativo " + (n + 1));
            String src = sourceName(before.source);
            boolean wasPlaying = before.playing;
            String b = before.title;
            if (bg != null) bg.postDelayed(() -> {
                State a = last;
                Diag.put("media.comando", what + " · " + src + " · prima " + (wasPlaying ? "in riproduzione" : "in pausa")
                        + " \"" + b + "\" · dopo " + (a.playing ? "in riproduzione" : "in pausa") + " \"" + a.title + "\"");
            }, 1500);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()) + " " + e;
            boolean notReady = msg.contains("NOT_INITIALIZED") || msg.contains("binder non disponibile");
            if (notReady && n < RETRIES && bg != null) {
                Diag.put("media.attesa", what + ": servizio non ancora pronto, nuovo tentativo " + (n + 2));
                bg.postDelayed(() -> attempt(what, cmd, n + 1), RETRY_MS);
                return;
            }
            Diag.error("media." + what, e.toString());
        }
        poll();
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
            case SRC_BT: {
                IBinder b = bt.binder();
                Rpc.call(b, BT, BT_FOCUS, Rpc.NONE);
                Rpc.call(b, BT, BT_PLAY, Rpc.NONE, Parcel::readInt);
                break;
            }
            case SRC_ONLINE: {
                IBinder b = online.binder();
                Rpc.call(b, ONL, ONL_FOCUS, Rpc.NONE);
                Rpc.call(b, ONL, ONL_PLAY, Rpc.NONE, Parcel::readInt);
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
        Rpc.call(radio.binder(), RAD, RAD_SET_MUTED, p -> {
            p.writeInt(0);
            p.writeInt(band);
        });
        radioPlaying = false;
        radioHoldUntil = android.os.SystemClock.uptimeMillis() + 3000;
    }

    /** Ripresa: si risintonizza la stazione corrente (FM/AM per frequenza, DAB per servizio). */
    private void radioResume() throws RemoteException {
        IBinder b = radio.binder();
        Station cur = current;
        if (cur != null && cur.band == BAND_DAB && cur.sid != 0) {
            Rpc.call(b, RAD, RAD_TUNE_DAB, p -> {
                p.writeInt(cur.index);
                p.writeLong(cur.sid);
                p.writeInt(cur.eid);
                p.writeInt(cur.secId);
            });
        } else if (cur != null && cur.khz > 0) {
            Rpc.call(b, RAD, RAD_TUNE, p -> {
                p.writeInt(cur.band);
                p.writeInt(cur.khz);
            });
        } else {
            Rpc.call(b, RAD, RAD_PLAY, Rpc.NONE, Parcel::readInt);
        }
        radioPlaying = true;
        radioHoldUntil = android.os.SystemClock.uptimeMillis() + 3000;
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
                radio.lastCall = "preferiti (7)";
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
        try {
            radio.lastCall = "elenco stazioni (22)";
            List<Station> l = Rpc.call(b, RAD, RAD_LIST, p -> p.writeInt(band), MediaClient::readStations);
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
        // paese RDS dalle stazioni trovate (conferma o corregge il paese automatico dei loghi)
        LogoPacks.noteStations(band, out);
        if (band == BAND_DAB) {
            java.util.Collections.sort(out, (x, y) -> x.label().compareToIgnoreCase(y.label()));
        } else {
            java.util.Collections.sort(out, (x, y) -> Integer.compare(x.khz, y.khz));
        }
        Diag.put("radio.elenco", (band == BAND_DAB ? "DAB " : "FM ") + out.size() + " stazioni");
        stations = out;
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
        post(() -> {
            try {
                IBinder b = radio.binder();
                if (b == null) throw new RemoteException("servizio radio non collegato");
                Station st = null;
                try {
                    st = Rpc.call(b, RAD, RAD_LAST_INFO, p -> p.writeInt(band), p -> Rpc.readNullable(p, MediaClient::readStationHead));
                } catch (Exception e) {
                    Diag.error("radio.banda.ultima", e.toString());
                }
                if (!valid(st, band)) {
                    st = null;
                    for (Station f : favorites) if (f.band == band) { st = f; break; }
                }
                if (st == null) {
                    try {
                        List<Station> list = Rpc.call(b, RAD, RAD_LIST, p -> p.writeInt(band), MediaClient::readStations);
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
                if (st != null) tuneNow(b, st);
            } catch (Exception e) {
                Diag.error("radio.banda", e.toString());
            }
            poll();
        });
    }

    private static boolean valid(Station st, int band) {
        if (st == null || st.band != band) return false;
        return band == BAND_DAB ? st.sid != 0 : st.khz > 0;
    }

    private void tuneNow(IBinder b, Station st) throws RemoteException {
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
        post(() -> {
            try {
                tuneNow(radio.binder(), st);
            } catch (Exception e) {
                Diag.error("radio.sintonia", e.toString());
            }
            poll();
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
                    case SRC_BT:
                        Rpc.call(bt.binder(), BT, s.playing ? BT_PAUSE : BT_PLAY, Rpc.NONE, Parcel::readInt);
                        break;
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
                    case SRC_BT:
                        Rpc.call(bt.binder(), BT, forward ? BT_NEXT : BT_PREV, Rpc.NONE, Parcel::readInt);
                        break;
                    case SRC_ONLINE:
                        Rpc.call(online.binder(), ONL, forward ? ONL_NEXT : ONL_PREV, Rpc.NONE, Parcel::readInt);
                        break;
                    case SRC_RADIO: {
                        int band = s.radioBand > 0 ? s.radioBand : BAND_FM;
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
