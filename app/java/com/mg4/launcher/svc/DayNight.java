package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 1.0.2: tema automatico giorno/notte uguale per tutte le versioni, con una catena di fonti:
 * <ol>
 *   <li>il sistema (uiMode della configurazione, UiModeManager, proprietà NIGHT_MODE), solo se è "affidabile": nelle
 *       ultime 72 ore ha detto "notte" almeno una volta (sulla SWI68 lo fa; su SWI69/SWI132 dice sempre "giorno");</li>
 *   <li>i fari (anabbaglianti o posizione, proprietà dell'auto): accesi con il sole basso = notte;</li>
 *   <li>l'orario: alba e tramonto calcolati senza internet dall'ultima posizione nota (se manca, fuso orario e Italia).</li>
 * </ol>
 * Un cambio si applica solo se resta uguale per 60 s (in galleria il tema non lampeggia). La scelta manuale Giorno/Notte
 * delle impostazioni ha sempre la precedenza (la controlla Theme).
 */
public final class DayNight {
    public interface Listener {
        void onThemeChange(boolean day);
    }

    private static final long CONFIRM_MS = 60_000, TICK_MS = 20_000, CANDIDATES_EVERY_MS = 10 * 60_000L;
    private static final long RELIABLE_WINDOW_MS = 72L * 60 * 60 * 1000;
    private static final String K_SYS_NIGHT_AT = "temaNotteSistemaAlle", K_LAT = "temaUltimaLat", K_LON = "temaUltimaLon";
    /** Italia, centro: riferimento se non c'è nessuna posizione. */
    private static final double ITALY_LAT = 42.5, ITALY_LON = 12.5;

    private static DayNight instance;

    private final Context ctx;
    private final SharedPreferences sp;
    private final Handler h = new Handler(Looper.getMainLooper());
    /** Le letture (CarService, posizione) si fanno su questo thread, mai sul thread principale. */
    private final Handler work;
    private Listener listener;
    private Boolean applied;            // tema automatico applicato (true = giorno)
    private Boolean pending;
    private long pendingSince;
    private String lastChange = "—", source = "—", detail = "";
    private int lastUiNight = -1;
    private long candidatesAt;

    private DayNight(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        android.os.HandlerThread t = new android.os.HandlerThread("mg4-tema");
        t.start();
        work = new Handler(t.getLooper());
    }

    public static synchronized DayNight get(Context c) {
        if (instance == null) instance = new DayNight(c);
        return instance;
    }

    public void start(Listener l) {
        listener = l;
        work.removeCallbacks(tick);
        work.post(tick);
        new Thread(this::ensureLocationPermission, "mg4-posizione").start();
    }

    public void stop() {
        work.removeCallbacksAndMessages(null);
        listener = null;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            evaluate(false);
            work.postDelayed(this, TICK_MS);
        }
    };

    /** Il sistema ha cambiato uiMode: si rivaluta subito e di nuovo dopo il tempo di conferma. */
    public void onConfigurationChanged() {
        work.post(() -> evaluate(false));
        work.postDelayed(() -> evaluate(false), CONFIRM_MS + 1000);
    }

    /** Tema automatico da applicare adesso (alla prima chiamata si calcola subito, senza attesa). */
    public synchronized boolean isDay() {
        if (applied == null) evaluate(true);
        return applied == null || applied;
    }

    /** Valuta le fonti; con immediate = true applica subito (avvio), altrimenti dopo 60 s di conferma. */
    public synchronized void evaluate(boolean immediate) {
        boolean target;
        try {
            target = compute();
        } catch (Throwable e) {
            Diag.error("tema.stato", e.toString());
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (applied == null || immediate && applied != target) {
            applyNow(target, applied == null ? "avvio" : "subito");
            return;
        }
        if (applied == target) {
            pending = null;
        } else if (pending == null || pending != target) {
            pending = target;
            pendingSince = now;
        } else if (now - pendingSince >= CONFIRM_MS) {
            pending = null;
            applyNow(target, "confermato dopo 60 s");
            Listener l = listener;
            // la ricostruzione della home si fa sul thread principale
            if (l != null) h.post(() -> l.onThemeChange(target));
        }
        writeDiag();
        if (now - candidatesAt > CANDIDATES_EVERY_MS || candidatesAt == 0) {
            candidatesAt = now;
            new Thread(this::candidates, "mg4-tema-candidati").start();   // letture lente fuori dal thread principale
        }
    }

    // ---- 1.0.3: letture per il Controllo funzioni ------------------------------------------------

    private volatile double lastSunElevation = Double.NaN;

    /** Fonte usata adesso (testo del log). */
    public String source() {
        return source;
    }

    /** Tema automatico applicato: true giorno, false notte, null non ancora deciso. */
    public Boolean applied() {
        return applied;
    }

    public double sunElevation() {
        return lastSunElevation;
    }

    public boolean uiStuck() {
        return sp.getBoolean(K_UI_STUCK, false);
    }

    private void applyNow(boolean day, String how) {
        applied = day;
        lastChange = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY).format(new Date()) + " (" + how + ")";
        writeDiag();
    }

    // ------------------------------------------------------------------------------------------

    private String uiTxt = "?", mgrTxt = "?", nightPropTxt = "?", lightsTxt = "?", sunTxt = "?";

    private boolean compute() {
        // a) sistema
        int ui = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int uiNight = ui == Configuration.UI_MODE_NIGHT_YES ? 1 : ui == Configuration.UI_MODE_NIGHT_NO ? 0 : -1;
        uiTxt = uiNight == 1 ? "notte" : uiNight == 0 ? "giorno" : "non definito";
        int mgr = -1;
        try {
            android.app.UiModeManager um = (android.app.UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
            int m = um.getNightMode();
            mgr = m == android.app.UiModeManager.MODE_NIGHT_YES ? 1 : m == android.app.UiModeManager.MODE_NIGHT_NO ? 0 : -1;
            mgrTxt = mgr == 1 ? "notte" : mgr == 0 ? "giorno" : m == android.app.UiModeManager.MODE_NIGHT_AUTO ? "auto" : String.valueOf(m);
        } catch (Throwable e) {
            mgrTxt = "?";
        }
        int nightProp = Integer.MIN_VALUE;
        CarProps p = VehicleClient.carProps();
        if (p != null && p.ready()) nightProp = p.getBoolQuiet(CarProps.NIGHT_MODE);
        nightPropTxt = nightProp == 1 ? "notte" : nightProp == 0 ? "giorno" : "non c'è";
        boolean sysNight = uiNight == 1 || mgr == 1 || nightProp == 1;
        long wall = System.currentTimeMillis();
        trackUiChanges(uiNight, wall);
        long prevSeen = sp.getLong(K_SYS_NIGHT_AT, 0);
        boolean changed = lastUiNight >= 0 && uiNight >= 0 && uiNight != lastUiNight;
        // si scrive solo quando serve (al più una volta l'ora), non a ogni giro
        if ((sysNight || changed) && wall - prevSeen > 60 * 60_000L) sp.edit().putLong(K_SYS_NIGHT_AT, wall).apply();
        if (uiNight >= 0) lastUiNight = uiNight;
        long seen = sp.getLong(K_SYS_NIGHT_AT, 0);
        boolean reliable = seen > 0 && wall - seen < RELIABLE_WINDOW_MS && wall >= seen;

        // c) sole (calcolato sempre, serve anche ai fari e alla diagnostica)
        double[] loc = location();
        Sun sun = sun(loc[0], loc[1], wall);
        lastSunElevation = sun.elevation;
        sunTxt = "alba " + sun.rise + " · tramonto " + sun.set + " · sole a " + Math.round(sun.elevation) + "° (" + (loc[2] == 1 ? "posizione"
                : loc[2] == 2 ? "ultima posizione salvata" : "fuso orario/Italia") + ")";

        // b) fari
        int lights = VehicleClient.lights();
        lightsTxt = lights == 1 ? "accesi" : lights == 0 ? "spenti" : "non leggibili";

        // 1.0.3: su alcune SWI68 uiMode (e UiModeManager) restano su "notte" per sempre. Un uiMode che non cambia
        // da 12 ore di accensione e dice il contrario del sole è bloccato; alla prima accensione senza storico basta
        // "notte" con il sole sopra +10°. Una volta bloccato resta ignorato finché uiMode non cambia davvero.
        uiStuckTxt = "";
        if (uiNight >= 0) {
            boolean contradicts = uiNight == 1 ? sun.elevation > 6 : sun.elevation < -6;
            long onMs = sp.getLong(K_UI_ON_MS, 0) + onPending;
            boolean noHistory = sp.getLong(K_UI_CHANGED_AT, 0) == 0 && onMs < UI_STUCK_ON_MS;
            if (!sp.getBoolean(K_UI_STUCK, false)) {
                if (onMs >= UI_STUCK_ON_MS && contradicts) {
                    markStuck("mai cambiato da 12 h, contraddice il sole");
                } else if (noHistory && uiNight == 1 && sun.elevation > 10) {
                    markStuck("prima accensione, notte con il sole a " + Math.round(sun.elevation) + "°");
                }
            }
            if (sp.getBoolean(K_UI_STUCK, false)) {
                uiStuckTxt = "uiMode " + uiTxt + " (" + sp.getString(K_UI_STUCK_WHY, "bloccato") + ": ignorato)";
                reliable = false;
            }
        }

        if (reliable) {
            source = "sistema (" + (nightProp == 1 ? "NIGHT_MODE" : uiNight >= 0 ? "uiMode" : "UiModeManager") + ", affidabile)";
            return !sysNight;
        }
        boolean sunNight = sun.elevation < -0.833;
        if (lights >= 0) {
            // con il sole alto i fari accesi sono quelli diurni o la galleria: resta giorno
            boolean night = sunNight || (lights == 1 && sun.elevation < 6);
            source = "fari (" + lightsTxt + ") + sole";
            return !night;
        }
        source = "orario (alba/tramonto)";
        return !sunNight;
    }

    // ---- uiMode bloccato (1.0.3) ----------------------------------------------------------------

    /** Tempo di accensione senza cambi di uiMode oltre il quale, se contraddice il sole, uiMode è considerato bloccato. */
    private static final long UI_STUCK_ON_MS = 12L * 60 * 60 * 1000;
    private static final String K_UI_LAST = "temaUiUltimo", K_UI_CHANGED_AT = "temaUiCambiatoAlle", K_UI_ON_MS = "temaUiAccesoMs",
            K_UI_STUCK = "temaUiBloccato", K_UI_STUCK_WHY = "temaUiBloccatoPerche";
    private long onTickAt, onPending, onSavedAt;
    private String uiStuckTxt = "";

    /**
     * Conta il tempo di accensione da quando uiMode è cambiato l'ultima volta (giorno↔notte), anche tra un'accensione e
     * l'altra. Il tempo a auto spenta non conta: tra due giri si aggiunge al massimo 3 volte l'intervallo del controllo.
     */
    private void trackUiChanges(int uiNight, long wall) {
        long now = SystemClock.elapsedRealtime();
        if (onTickAt > 0) onPending += Math.max(0, Math.min(now - onTickAt, 3 * TICK_MS));
        onTickAt = now;
        if (uiNight < 0) return;
        int last = sp.getInt(K_UI_LAST, -1);
        SharedPreferences.Editor e = null;
        if (last != uiNight) {
            e = sp.edit().putInt(K_UI_LAST, uiNight);
            if (last >= 0) {
                // cambio reale: si azzera il conteggio e uiMode torna affidabile
                e.putLong(K_UI_CHANGED_AT, wall).putLong(K_UI_ON_MS, 0).putBoolean(K_UI_STUCK, false).remove(K_UI_STUCK_WHY);
                onPending = 0;
                onSavedAt = now;
            }
        }
        if (onPending > 0 && (now - onSavedAt > 5 * 60_000L || e != null)) {
            if (e == null) e = sp.edit();
            e.putLong(K_UI_ON_MS, sp.getLong(K_UI_ON_MS, 0) + onPending);
            onPending = 0;
            onSavedAt = now;
        }
        if (e != null) e.apply();
    }

    private void markStuck(String why) {
        sp.edit().putBoolean(K_UI_STUCK, true).putString(K_UI_STUCK_WHY, why).apply();
    }

    private String uiHistoryTxt() {
        long at = sp.getLong(K_UI_CHANGED_AT, 0);
        long onH = (sp.getLong(K_UI_ON_MS, 0) + onPending) / 3_600_000L;
        return "ultimo cambio uiMode " + (at == 0 ? "mai visto" : new SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(new Date(at)))
                + " (" + onH + " h di accensione da allora)";
    }

    private void writeDiag() {
        String mode = sp.getString("theme", "auto");
        String manual = "giorno".equals(mode) ? "scelta manuale Giorno (ha la precedenza) · automatico: " : "notte".equals(mode)
                ? "scelta manuale Notte (ha la precedenza) · automatico: " : "";
        Diag.put("tema.stato", manual + "fonte " + source + (uiStuckTxt.isEmpty() ? "" : " · " + uiStuckTxt) + " · " + uiHistoryTxt()
                + " · uiMode " + uiTxt + " · UiModeManager " + mgrTxt + " · NIGHT_MODE " + nightPropTxt
                + " · fari " + lightsTxt + " · " + sunTxt + " · applicato " + (applied == null ? "—" : applied ? "giorno" : "notte")
                + (pending != null ? " (in conferma: " + (pending ? "giorno" : "notte") + ")" : "") + " · ultimo cambio " + lastChange);
    }

    // ---- posizione ---------------------------------------------------------------------------

    private void ensureLocationPermission() {
        for (String perm : new String[]{"android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"}) {
            if (ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) continue;
            try {
                android.content.pm.PackageManager pm = ctx.getPackageManager();
                pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                        .invoke(pm, ctx.getPackageName(), perm, android.os.Process.myUserHandle());
            } catch (Throwable ignored) {
            }
        }
    }

    /** {lat, lon, tipo}: tipo 1 = posizione attuale, 2 = ultima salvata, 0 = fuso orario/Italia. */
    private double[] location() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            android.location.Location best = null;
            if (lm != null) {
                for (String prov : lm.getProviders(false)) {
                    try {
                        android.location.Location l = lm.getLastKnownLocation(prov);
                        if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (best != null && (best.getLatitude() != 0 || best.getLongitude() != 0)) {
                sp.edit().putFloat(K_LAT, (float) best.getLatitude()).putFloat(K_LON, (float) best.getLongitude()).apply();
                return new double[]{best.getLatitude(), best.getLongitude(), 1};
            }
        } catch (Throwable ignored) {
        }
        if (sp.contains(K_LAT)) return new double[]{sp.getFloat(K_LAT, 0), sp.getFloat(K_LON, 0), 2};
        TimeZone tz = TimeZone.getDefault();
        String id = tz.getID();
        if (id == null || id.equals("Europe/Rome") || id.equals("GMT") || id.equals("UTC")) return new double[]{ITALY_LAT, ITALY_LON, 0};
        // longitudine dal fuso (15° per ora), latitudine dell'Italia
        double lon = tz.getRawOffset() / 3_600_000.0 * 15.0;
        return new double[]{ITALY_LAT, lon, 0};
    }

    // ---- sole (formule NOAA semplificate, precisione di qualche minuto) ------------------------

    static final class Sun {
        double elevation;
        String rise = "—", set = "—";
    }

    static Sun sun(double lat, double lon, long wallMs) {
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        u.setTimeInMillis(wallMs);
        int doy = u.get(Calendar.DAY_OF_YEAR);
        double hour = u.get(Calendar.HOUR_OF_DAY) + u.get(Calendar.MINUTE) / 60.0 + u.get(Calendar.SECOND) / 3600.0;
        double g = 2 * Math.PI / 365.0 * (doy - 1 + (hour - 12) / 24.0);
        double eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g)
                - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double tst = hour * 60 + eq + 4 * lon;
        double ha = Math.toRadians(tst / 4.0 - 180);
        double la = Math.toRadians(lat);
        double cosZ = Math.sin(la) * Math.sin(decl) + Math.cos(la) * Math.cos(decl) * Math.cos(ha);
        Sun s = new Sun();
        s.elevation = 90 - Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosZ))));
        double c = Math.cos(Math.toRadians(90.833)) / (Math.cos(la) * Math.cos(decl)) - Math.tan(la) * Math.tan(decl);
        if (c >= -1 && c <= 1) {
            double ha0 = Math.toDegrees(Math.acos(c));
            s.rise = localTime(u, 720 - 4 * (lon + ha0) - eq);
            s.set = localTime(u, 720 - 4 * (lon - ha0) - eq);
        }
        return s;
    }

    private static String localTime(Calendar utcDay, double minutesUtc) {
        Calendar c = (Calendar) utcDay.clone();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long ms = c.getTimeInMillis() + Math.round(minutesUtc * 60_000);
        return new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date(ms));
    }

    // ---- tema.candidati: tutti i segnali giorno/notte che si riescono a leggere -------------------

    private float lastLux = Float.NaN;
    private boolean luxAsked;

    private void candidates() {
        StringBuilder sb = new StringBuilder();
        CarProps p = VehicleClient.carProps();
        if (p != null && p.ready()) {
            sb.append("NIGHT_MODE ").append(val(p.getBoolQuiet(CarProps.NIGHT_MODE)));
            sb.append(" · HEADLIGHTS_STATE ").append(val(p.getIntSafe(CarProps.HEADLIGHTS_STATE)));
            sb.append(" · HEADLIGHTS_SWITCH ").append(val(p.getIntSafe(CarProps.HEADLIGHTS_SWITCH)));
            sb.append(" · DISPLAY_BRIGHTNESS ").append(val(p.getIntSafe(CarProps.DISPLAY_BRIGHTNESS)));
        } else {
            sb.append("CarService non pronto");
        }
        sb.append(" · fari (servizio) ").append(VehicleClient.lightsDescription());
        // sensore di luce
        try {
            android.hardware.SensorManager sm = (android.hardware.SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            android.hardware.Sensor light = sm == null ? null : sm.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT);
            if (light == null) sb.append(" · sensore di luce assente");
            else {
                sb.append(" · sensore di luce ").append(Float.isNaN(lastLux) ? "in lettura" : Math.round(lastLux) + " lux");
                if (!luxAsked) {
                    luxAsked = true;
                    sm.registerListener(new android.hardware.SensorEventListener() {
                        @Override
                        public void onSensorChanged(android.hardware.SensorEvent e) {
                            lastLux = e.values[0];
                            sm.unregisterListener(this);   // una lettura basta per la diagnostica
                            luxAsked = false;
                        }

                        @Override
                        public void onAccuracyChanged(android.hardware.Sensor s, int a) {
                        }
                    }, light, android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
                }
            }
        } catch (Throwable e) {
            sb.append(" · sensore di luce errore");
        }
        // impostazioni di sistema legate a giorno/notte o luminosità
        sb.append(" · impostazioni ").append(settingsMatching());
        try {
            sb.append(" · ui_night_mode ").append(android.provider.Settings.Secure.getString(ctx.getContentResolver(), "ui_night_mode"));
        } catch (Throwable ignored) {
        }
        sb.append(" · broadcast: cambio di configurazione (uiMode) ascoltato dall'activity");
        Diag.put("tema.candidati", sb.toString());
    }

    private static String val(int v) {
        return v == Integer.MIN_VALUE ? "non c'è" : String.valueOf(v);
    }

    private String settingsMatching() {
        StringBuilder sb = new StringBuilder();
        for (String table : new String[]{"system", "secure", "global"}) {
            android.database.Cursor c = null;
            try {
                c = ctx.getContentResolver().query(android.net.Uri.parse("content://settings/" + table), new String[]{"name", "value"}, null, null, null);
                if (c == null) continue;
                while (c.moveToNext()) {
                    String n = c.getString(0);
                    if (n == null) continue;
                    String l = n.toLowerCase(Locale.ROOT);
                    if (l.contains("night") || l.contains("day_") || l.contains("dark") || l.contains("brightness") || l.contains("daynight")
                            || l.contains("theme") || l.contains("lamp") || l.contains("headlight")) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(table).append('/').append(n).append('=').append(c.getString(1));
                        if (sb.length() > 900) return sb.append("…").toString();
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (c != null) c.close();
            }
        }
        return sb.length() == 0 ? "nessuna" : sb.toString();
    }
}
