package com.mg4.launcher.svc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.mg4.launcher.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.0.3 (punto 8): Controllo funzioni, come l'AutoTest di MG4 Clima e il controllo di MG4 Voice.
 * <p>
 * Tutto in SOLA LETTURA: si guardano i servizi collegati e i valori già letti, non si manda nessun comando all'auto
 * (niente clima, radio, play, chiamate). Ogni prova dà ✓ (funziona), ✗ (non funziona) o — (non presente su questa auto,
 * oppure saltata). Nel log: controllo.&lt;nome&gt; = ✓/✗/— dettaglio, più controllo.riepilogo.
 * <p>
 * Dopo ogni aggiornamento il controllo parte da solo, in silenzio, 60 s dopo l'avvio: solo log, senza la prova dei
 * tasti al volante (che chiede di premere un tasto).
 */
public final class SelfCheck {
    public static final int OK = 1, FAIL = 0, ABSENT = -1, SKIPPED = -2;

    public static final class Item {
        public final String key;
        public final int label;
        public int status = SKIPPED;
        /** Dettaglio in italiano per il log. */
        public String detail = "";
        /** Dettaglio senza parole (numeri, °C, porte) mostrato a schermo accanto alla frase tradotta. */
        public String shown = "";

        Item(String key, int label) {
            this.key = key;
            this.label = label;
        }

        public String mark() {
            return status == OK ? "✓" : status == FAIL ? "✗" : "—";
        }
    }

    public interface Progress {
        /** Una riga è cambiata (sul thread principale). */
        void onItem(Item it);

        /** Prova dei tasti: secondi che restano (sul thread principale); 0 = finita. */
        void onKeysCountdown(int seconds);

        void onDone(List<Item> items, int ok, int fail, int absent);
    }

    private static VehicleClient vehicle;
    private static MediaClient media;
    private static LogUploader logs;
    private static volatile boolean running;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private SelfCheck() {}

    /** Chiamato da MainActivity: i client già collegati (il controllo non ne apre di nuovi). */
    public static void bind(VehicleClient v, MediaClient m, LogUploader l) {
        vehicle = v;
        media = m;
        logs = l;
    }

    public static boolean running() {
        return running;
    }

    /** Elenco delle prove, nell'ordine in cui compaiono a schermo. */
    public static List<Item> items() {
        List<Item> l = new ArrayList<>();
        l.add(new Item("veicolo", R.string.chk_vehicle));
        l.add(new Item("clima", R.string.chk_climate));
        l.add(new Item("radio", R.string.chk_radio));
        l.add(new Item("media", R.string.chk_media));
        l.add(new Item("bms", R.string.chk_bms));
        l.add(new Item("carplayAA", R.string.chk_projection));
        l.add(new Item("tempEsterna", R.string.chk_out_temp));
        l.add(new Item("tempInterna", R.string.chk_in_temp));
        l.add(new Item("tema", R.string.chk_theme));
        l.add(new Item("usb", R.string.chk_usb));
        l.add(new Item("bluetooth", R.string.chk_bt));
        l.add(new Item("stazioni", R.string.chk_stations));
        l.add(new Item("tastiVolante", R.string.chk_keys));
        l.add(new Item("tastoClima", R.string.chk_sys_button));
        l.add(new Item("sediliVolante", R.string.chk_comfort));
        l.add(new Item("aggiornamento", R.string.chk_update));
        l.add(new Item("log", R.string.chk_logs));
        l.add(new Item("avvisi", R.string.chk_notices));
        return l;
    }

    /**
     * Avvia il controllo su un thread a parte. interactive = true: con la prova dei tasti al volante (10 s).
     * Progress può essere null (controllo silenzioso dopo un aggiornamento).
     */
    public static void run(Context ctx, boolean interactive, Progress p) {
        if (running) return;
        running = true;
        Context c = ctx.getApplicationContext();
        List<Item> items = items();
        new Thread(() -> {
            try {
                Diag.put("controllo.avvio", (interactive ? "dalle impostazioni" : "automatico dopo l'aggiornamento") + " · "
                        + new java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.ITALY).format(new java.util.Date()));
                for (Item it : items) {
                    try {
                        check(c, it, interactive, p);
                    } catch (Throwable e) {
                        it.status = FAIL;
                        it.detail = "errore " + e.getClass().getSimpleName();
                    }
                    Diag.put("controllo." + it.key, it.mark() + " " + it.detail);
                    if (p != null) main.post(() -> p.onItem(it));
                }
                int ok = 0, fail = 0, absent = 0;
                for (Item it : items) {
                    if (it.status == OK) ok++;
                    else if (it.status == FAIL) fail++;
                    else absent++;
                }
                Diag.put("controllo.riepilogo", ok + " ok · " + fail + " errori · " + absent + " assenti");
                int o = ok, f = fail, a = absent;
                if (p != null) main.post(() -> p.onDone(items, o, f, a));
            } finally {
                running = false;
            }
        }, "mg4-controllo").start();
    }

    private static void set(Item it, int status, String detail, String shown) {
        it.status = status;
        it.detail = detail;
        it.shown = shown == null ? "" : shown;
    }

    private static void check(Context c, Item it, boolean interactive, Progress p) throws InterruptedException {
        VehicleClient v = vehicle;
        MediaClient m = media;
        VehicleClient.State s = v == null ? null : v.last();
        switch (it.key) {
            case "veicolo": {
                CarProps cp = VehicleClient.carProps();
                boolean hub = s != null && s.connected;
                boolean props = cp != null && cp.ready();
                set(it, hub ? OK : FAIL, "servizio veicolo " + (hub ? "collegato" : "non collegato") + " · CarService "
                        + (props ? "pronto" : "non pronto"), "");
                break;
            }
            case "clima": {
                boolean read = s != null && (s.hvacPower >= 0 || s.acTemp > 0 || s.fanLevel >= 0);
                boolean app = installed(c, "com.saicmotor.hmi.hvac");
                if (read) set(it, OK, "stato letto (acceso " + s.hvacPower + ", temperatura " + s.acTemp + ", ventola " + s.fanLevel + ")",
                        s.acTemp > 0 ? s.acTemp + "°" : "");
                else set(it, FAIL, "stato del clima non letto" + (app ? "" : " · app Clima assente"), "");
                break;
            }
            case "radio": {
                if (!installed(c, "com.saicmotor.service.radio")) {
                    set(it, ABSENT, "servizio radio non installato", "");
                    break;
                }
                boolean ok = m != null && m.radioLinked();
                set(it, ok ? OK : FAIL, "servizio radio " + (ok ? "collegato" : "non collegato") + " · " + Diag.value("radio.codici"), "");
                break;
            }
            case "media": {
                boolean ok = m != null && m.mediaLinked();
                set(it, ok ? OK : FAIL, "servizio media (stato + lettore) " + (ok ? "collegato" : "non collegato"), "");
                break;
            }
            case "bms": {
                boolean ok = s != null && s.bmsAvailable && s.soc >= 0;
                set(it, ok ? OK : FAIL, ok ? "carica letta " + Math.round(s.soc) + "%" : "carica della batteria non letta",
                        ok ? Math.round(s.soc) + "%" : "");
                break;
            }
            case "carplayAA": {
                Boolean l = m == null ? Boolean.FALSE : m.projectionLinked();
                if (l == null) set(it, ABSENT, "Allgo (CarPlay/Android Auto) non installato", "");
                else set(it, l ? OK : FAIL, "servizio Allgo " + (l ? "collegato" : "non collegato")
                        + (m != null && m.last().deviceType != 0 ? " · telefono collegato" : " · nessun telefono"), "");
                break;
            }
            case "tempEsterna": {
                boolean ok = s != null && s.outsideTemp > -60 && s.outsideTemp < 70;
                set(it, ok ? OK : FAIL, ok ? "letta " + s.outsideTemp + " °C" : "non letta", ok ? Math.round(s.outsideTemp) + "°C" : "");
                break;
            }
            case "tempInterna": {
                CarProps cp = VehicleClient.carProps();
                if (cp != null && cp.ready() && !cp.has(CarProps.HVAC_TEMPERATURE_CURRENT)) {
                    set(it, ABSENT, "proprietà assente (l'opzione si nasconde da sola)", "");
                    // l'opzione "Automatico" si nasconde, come fa anche la lettura normale
                    c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).edit().putBoolean("tempInternaAssente", true).apply();
                    break;
                }
                boolean ok = s != null && !Float.isNaN(s.insideTemp) && !s.insideSource.isEmpty();
                set(it, ok ? OK : FAIL, ok ? "letta " + s.insideTemp + " °C da " + s.insideSource : "nessun valore valido · " + Diag.value("clima.tempInterna"),
                        ok ? Math.round(s.insideTemp) + "°C" : "");
                break;
            }
            case "tema": {
                DayNight dn = DayNight.get(c);
                Boolean day = dn.applied();
                double sun = dn.sunElevation();
                String mode = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getString("theme", "auto");
                String base = "fonte " + dn.source() + " · applicato " + (day == null ? "—" : day ? "giorno" : "notte")
                        + (Double.isNaN(sun) ? "" : " · sole a " + Math.round(sun) + "°") + (dn.uiStuck() ? " · uiMode bloccato: ignorato" : "");
                if (!"auto".equals(mode)) {
                    set(it, OK, "scelta manuale " + mode + " · " + base, "");
                } else if (day == null || Double.isNaN(sun)) {
                    set(it, FAIL, "tema non ancora deciso · " + base, "");
                } else {
                    boolean wrong = !day && sun > 6 || day && sun < -6;
                    set(it, wrong ? FAIL : OK, (wrong ? "non coerente col sole · " : "coerente col sole · ") + base, "");
                }
                break;
            }
            case "usb": {
                List<String> ports = m == null ? null : m.usbPortsSeen();
                if (ports == null) {
                    set(it, FAIL, "servizio media non risponde", "");
                    break;
                }
                StringBuilder names = new StringBuilder();
                for (String port : ports) names.append(names.length() == 0 ? "" : " · ")
                        .append("usb3".equals(port) ? "USB 1" : "usb1".equals(port) ? "USB 2" : port);
                String note = " (SWI68: un solo lettore per le due porte, USB 2 non è una sorgente separata)";
                if (ports.isEmpty()) set(it, ABSENT, "nessuna chiavetta inserita" + note, "");
                else set(it, OK, "viste " + names + note, names.toString());
                break;
            }
            case "bluetooth": {
                if (m == null || !m.btLinked()) {
                    set(it, FAIL, "servizio Bluetooth musica non collegato", "");
                    break;
                }
                int st = m.btState();
                if (st != 2) set(it, ABSENT, "nessun telefono collegato in Bluetooth (stato " + st + ")", "");
                else set(it, OK, "telefono collegato · titoli da " + (MediaClient.btMetaSource.isEmpty() ? "— (aprire la sorgente Bluetooth)"
                        : MediaClient.btMetaSource) + " · " + Diag.value("media.bt.attesa"), "");
                break;
            }
            case "stazioni": {
                if (m == null || !m.radioLinked()) {
                    set(it, FAIL, "servizio radio non collegato", "");
                    break;
                }
                MediaClient.State ms = m.last();
                int n = ms.stations == null ? 0 : ms.stations.size();
                int f = ms.favorites == null ? 0 : ms.favorites.size();
                String elenco = Diag.value("radio.elenco");
                if (n > 0 || f > 0) set(it, OK, n + " stazioni · " + f + " preferiti · " + elenco, String.valueOf(Math.max(n, f)));
                else set(it, FAIL, "nessuna stazione ricevuta (aprire la Radio e fare una ricerca) · " + elenco, "0");
                break;
            }
            case "tastiVolante": {
                if (!interactive) {
                    set(it, SKIPPED, "saltata (controllo automatico: serve premere un tasto)", "");
                    break;
                }
                final int[] seen = {-1};
                Object lock = new Object();
                WheelKeys.setProbe((code, down, lp) -> {
                    if (code == 4 || code == 5) synchronized (lock) {
                        seen[0] = code;
                        lock.notifyAll();
                    }
                });
                try {
                    for (int sec = 10; sec > 0 && seen[0] < 0; sec--) {
                        int left = sec;
                        if (p != null) main.post(() -> p.onKeysCountdown(left));
                        synchronized (lock) {
                            if (seen[0] < 0) lock.wait(1000);
                        }
                    }
                } finally {
                    WheelKeys.setProbe(null);
                    if (p != null) main.post(() -> p.onKeysCountdown(0));
                }
                if (seen[0] > 0) set(it, OK, "visto KEY_CODE " + seen[0] + " (" + WheelKeys.name(seen[0]) + ")", WheelKeys.name(seen[0]));
                else set(it, FAIL, "nessun tasto avanti/indietro visto in 10 s · " + Diag.value("media.tastiVolante"), "");
                break;
            }
            case "tastoClima": {
                boolean app = installed(c, "com.saicmotor.hmi.hvac") || installed(c, "com.mg4.clima");
                boolean panel = SysHvacPanel.supported(c);
                if (app || panel) set(it, OK, "apre " + (app ? "l'app Clima (o MG4 Clima)" : "il pannello clima di sistema"), "");
                else set(it, ABSENT, "né app Clima né pannello di sistema", "");
                break;
            }
            case "sediliVolante": {
                if (s == null) {
                    set(it, FAIL, "servizio veicolo non collegato", "");
                    break;
                }
                String cfg = "sedile sinistro " + (s.seatLeft >= 0 ? "sì" : "no") + " · sedile destro " + (s.seatRight >= 0 ? "sì" : "no")
                        + " · volante " + (s.wheelHeat >= 0 ? "sì" : "no");
                boolean any = s.seatLeft >= 0 || s.seatRight >= 0 || s.wheelHeat >= 0;
                set(it, any ? OK : ABSENT, cfg, "");
                break;
            }
            case "aggiornamento":
            case "avvisi": {
                String[] r = remote();
                if ("aggiornamento".equals(it.key)) {
                    if (r[0] != null) set(it, OK, "latest.json letto: versione " + r[0], r[0]);
                    else set(it, FAIL, "latest.json non raggiungibile (internet assente?)", "");
                } else {
                    if (r[1] != null) set(it, OK, "avvisi.json letto: " + r[1] + " avvisi", r[1]);
                    else set(it, FAIL, "avvisi.json non letto (internet assente?)", "");
                }
                break;
            }
            case "log": {
                if (!LogUploader.configured()) {
                    set(it, FAIL, "invio non configurato in questa versione", "");
                    break;
                }
                boolean on = logs == null || logs.enabled();
                String st = LogUploader.status();
                boolean bad = st != null && (st.contains("non riuscito") || st.contains("errore"));
                if (!on) set(it, ABSENT, "invio spento dall'utente", "");
                else set(it, bad ? FAIL : OK, "stato: " + (st == null || st.isEmpty() ? "—" : st), "");
                break;
            }
            default:
                set(it, SKIPPED, "", "");
        }
    }

    private static String[] remoteCache;
    private static long remoteAt;

    /** latest.json e avvisi.json letti una volta per controllo (sola lettura). */
    private static synchronized String[] remote() {
        long now = android.os.SystemClock.uptimeMillis();
        if (remoteCache == null || now - remoteAt > 30_000) {
            remoteCache = Updater.probeRemote();
            remoteAt = now;
        }
        return remoteCache;
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- controllo automatico dopo un aggiornamento --------------------------------------------

    private static final String K_DONE_FOR = "controlloFattoPer";

    /** Se questa versione non è ancora stata controllata, il controllo silenzioso parte 60 s dopo. */
    public static void scheduleAfterUpdate(Context ctx, String version) {
        Context c = ctx.getApplicationContext();
        android.content.SharedPreferences sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        if (version.equals(sp.getString(K_DONE_FOR, ""))) return;
        main.postDelayed(() -> {
            if (version.equals(sp.getString(K_DONE_FOR, "")) || running) return;
            sp.edit().putString(K_DONE_FOR, version).apply();
            run(c, false, null);
        }, 60_000);
    }
}
