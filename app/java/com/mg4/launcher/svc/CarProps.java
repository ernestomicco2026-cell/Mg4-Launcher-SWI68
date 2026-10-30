package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Proprietà del veicolo lette dal CarService di Android Automotive (android.car), via reflection perché
 * android.car non fa parte dell'SDK pubblico. Serve per pressione pneumatici (TPMS) e temperatura interna,
 * che il servizio veicolo SAIC non espone. ID delle proprietà dal firmware SWI68 (YFVehicleProperty).
 */
public final class CarProps {
    // pressione pneumatici (INT32, area globale): AS, AD, PS, PD
    public static final int TIRE_FL = 557847891, TIRE_FR = 557847892, TIRE_RL = 557847893, TIRE_RR = 557847894;
    // temperatura abitacolo (FLOAT, area sedile/zona): corrente e "ambient" del clima
    public static final int HVAC_TEMPERATURE_CURRENT = 358614274, HVAC_AMBIENT_TEMPERATURE = 358622506;
    /** Validità della temperatura esterna (0 = valida, 1 = non valida), come la usa il SystemUI originale. */
    public static final int HVAC_TEMPERATURE_OUTCARV = 356525352;

    private final Context ctx;
    private Object car, propertyManager;
    private volatile boolean connected;
    private final Map<Integer, Integer> areas = new HashMap<>();
    /** Tutte le aree di ogni proprietà (per la sonda della temperatura interna). */
    private final Map<Integer, int[]> allAreas = new HashMap<>();
    private boolean areasRead;
    private String lastError = "";
    /** Proprietà che l'auto rifiuta (IllegalArgumentException): non si rileggono più. */
    private final java.util.Set<Integer> unsupported = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** Proprietà negate per un permesso che l'auto non concede (SecurityException): non si rileggono più. */
    private final java.util.Set<Integer> denied = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** Velocità del veicolo (FLOAT, m/s) e permesso che la protegge. */
    public static final int PERF_VEHICLE_SPEED = 291504647;
    public static final String PERM_CAR_SPEED = "android.car.permission.CAR_SPEED";

    public CarProps(Context c) {
        ctx = c.getApplicationContext();
    }

    /**
     * Permesso del CarService (es. CAR_SPEED): sull'auto è un permesso "a richiesta", che il launcher si concede da solo
     * grazie alla firma platform (GRANT_RUNTIME_PERMISSIONS). Restituisce true se alla fine è concesso.
     */
    public boolean ensurePermission(String perm) {
        String name = perm.substring(perm.lastIndexOf('.') + 1);
        if (ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Diag.put("auto.permesso." + name, "concesso");
            return true;
        }
        String why;
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                    .invoke(pm, ctx.getPackageName(), perm, android.os.Process.myUserHandle());
            why = "";
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            why = c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
        }
        boolean ok = ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        Diag.put("auto.permesso." + name, ok ? "concesso ora" : "non concesso dall'auto" + (why.isEmpty() ? "" : " (" + why + ")"));
        return ok;
    }

    /** Si collega al CarService (va chiamato da un thread in background). */
    public synchronized void connect() {
        if (car != null) return;
        // velocità (assistente di guida e pedale solo ad auto ferma): senza permesso non si prova nemmeno a leggerla
        if (!ensurePermission(PERM_CAR_SPEED)) {
            denied.add(PERF_VEHICLE_SPEED);
            Diag.put("auto.velocità", "non letta: l'auto non concede il permesso CAR_SPEED (scritto una sola volta)");
        }
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName n, IBinder b) {
                    connected = true;
                }

                @Override
                public void onServiceDisconnected(ComponentName n) {
                    connected = false;
                    propertyManager = null;
                }
            };
            car = carCls.getMethod("createCar", Context.class, ServiceConnection.class).invoke(null, ctx, conn);
            carCls.getMethod("connect").invoke(car);
            Diag.put("auto.carservice", "collegamento…");
        } catch (Throwable e) {
            error("collegamento", e);
        }
    }

    public boolean ready() {
        return connected;
    }

    private Object pm() throws Exception {
        if (propertyManager == null && car != null && connected) {
            propertyManager = car.getClass().getMethod("getCarManager", String.class).invoke(car, "property");
            Diag.put("auto.carservice", propertyManager != null ? "collegato" : "nessun property manager");
        }
        return propertyManager;
    }

    /** Area da usare per una proprietà (0 per le globali, la prima area configurata per le altre). */
    private int area(int prop) {
        if (!areasRead) {
            areasRead = true;
            try {
                Object p = pm();
                if (p != null) {
                    List<?> cfgs = (List<?>) p.getClass().getMethod("getPropertyList").invoke(p);
                    for (Object cfg : cfgs) {
                        int id = (Integer) cfg.getClass().getMethod("getPropertyId").invoke(cfg);
                        int[] ids = (int[]) cfg.getClass().getMethod("getAreaIds").invoke(cfg);
                        areas.put(id, ids != null && ids.length > 0 ? ids[0] : 0);
                        allAreas.put(id, ids != null && ids.length > 0 ? ids : new int[]{0});
                    }
                    Diag.put("auto.proprietà", cfgs.size() + " disponibili; TPMS " + (areas.containsKey(TIRE_FL) ? "sì" : "no")
                            + ", temp. interna " + (areas.containsKey(HVAC_TEMPERATURE_CURRENT) || areas.containsKey(HVAC_AMBIENT_TEMPERATURE) ? "sì" : "no"));
                }
            } catch (Throwable e) {
                areasRead = false;
                error("elenco proprietà", e);
            }
        }
        Integer a = areas.get(prop);
        return a == null ? 0 : a;
    }

    /** Intero o Integer.MIN_VALUE se non leggibile. */
    public int getInt(int prop) {
        try {
            Object p = pm();
            if (p == null) return Integer.MIN_VALUE;
            Method m = p.getClass().getMethod("getIntProperty", int.class, int.class);
            return (Integer) m.invoke(p, prop, area(prop));
        } catch (Throwable e) {
            error("lettura " + prop, e);
            return Integer.MIN_VALUE;
        }
    }

    /** Come getInt, ma senza scrivere errori nella diagnostica (proprietà che potrebbero non esistere). */
    public int getIntQuiet(int prop) {
        try {
            Object p = pm();
            if (p == null) return Integer.MIN_VALUE;
            area(prop);
            if (!areas.containsKey(prop)) return Integer.MIN_VALUE;
            Method m = p.getClass().getMethod("getIntProperty", int.class, int.class);
            return (Integer) m.invoke(p, prop, area(prop));
        } catch (Throwable e) {
            return Integer.MIN_VALUE;
        }
    }

    /** Decimale o NaN se non leggibile. Dopo il primo rifiuto dell'auto la proprietà non si rilegge più. */
    public float getFloat(int prop) {
        if (unsupported.contains(prop) || denied.contains(prop)) return Float.NaN;
        try {
            Object p = pm();
            if (p == null) return Float.NaN;
            Method m = p.getClass().getMethod("getFloatProperty", int.class, int.class);
            return (Float) m.invoke(p, prop, area(prop));
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            if (c instanceof SecurityException) {
                // permesso negato dall'auto (es. CAR_SPEED): una sola riga nel log, poi la proprietà non si rilegge più
                denied.add(prop);
                Diag.put("auto.permessoNegato." + Integer.toHexString(prop), "non letta più in questa sessione (" + c.getMessage() + ")");
                return Float.NaN;
            }
            if (c instanceof IllegalArgumentException) {
                unsupported.add(prop);
                Diag.put("auto.proprietàNonSupportata." + Integer.toHexString(prop), "non disponibile su questa auto (" + c.getClass().getSimpleName() + ")");
                return Float.NaN;
            }
            error("lettura " + prop, e);
            return Float.NaN;
        }
    }

    // ---- sonda della temperatura interna (0.9.3) ------------------------------------------------
    // L'auto non espone la temperatura dell'abitacolo con le proprietà standard: HVAC_TEMPERATURE_CURRENT vale sempre 0 e
    // HVAC_AMBIENT_TEMPERATURE è la temperatura esterna. L'app iSmart però la mostra, quindi passa da qualche parte.
    // La sonda legge tutte le proprietà decimali (tipo FLOAT) dell'auto, in tutte le aree, e scrive nei log quelle con
    // valori da temperatura e quanto variano: una che cambia col clima acceso e non segue l'esterna è quella giusta.

    private static final int TYPE_MASK = 0x00FF0000, TYPE_FLOAT = 0x00600000;
    /** Già note (impostazioni del clima, esterna): non servono alla ricerca. */
    private static final int[] PROBE_SKIP = {358622475, 358622476, 358614275, 358622481, 291505923};
    private final Map<String, float[]> probe = new java.util.TreeMap<>();

    /** Una lettura della sonda; restituisce il riassunto per la diagnostica ("" se il CarService non è pronto). */
    public synchronized String probeInsideTemp(float outside) {
        area(0);
        if (!areasRead) return "";
        StringBuilder all = new StringBuilder();
        int n = 0;
        try {
            Object p = pm();
            if (p == null) return "";
            Method m = p.getClass().getMethod("getFloatProperty", int.class, int.class);
            for (Map.Entry<Integer, int[]> e : allAreas.entrySet()) {
                int id = e.getKey();
                if ((id & TYPE_MASK) != TYPE_FLOAT || unsupported.contains(id)) continue;
                boolean skip = false;
                for (int k : PROBE_SKIP) if (k == id) skip = true;
                if (skip) continue;
                for (int a : e.getValue()) {
                    float v;
                    try {
                        v = (Float) m.invoke(p, id, a);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (Float.isNaN(v) || v <= -30f || v >= 70f || v == 0f) continue;
                    String key = "0x" + Integer.toHexString(id) + "/" + a;
                    float[] r = probe.get(key);
                    if (r == null) probe.put(key, new float[]{v, v, v});
                    else {
                        r[0] = Math.min(r[0], v);
                        r[1] = Math.max(r[1], v);
                        r[2] = v;
                    }
                    n++;
                }
            }
        } catch (Throwable e) {
            return "errore " + e;
        }
        // candidati: valori da abitacolo che non sono l'esterna
        for (Map.Entry<String, float[]> e : probe.entrySet()) {
            float[] r = e.getValue();
            boolean outsideLike = !Float.isNaN(outside) && Math.abs(r[2] - outside) < 0.6f;
            all.append(all.length() == 0 ? "" : " | ").append(e.getKey()).append('=').append(r[2])
                    .append(" (").append(r[0]).append('…').append(r[1]).append(')').append(outsideLike ? " =est" : "");
        }
        return n + " letture · " + (all.length() == 0 ? "nessun valore da temperatura" : all);
    }

    /** False se la proprietà non è nell'elenco dell'auto o è stata rifiutata; null se non si sa ancora. */
    public Boolean available(int prop) {
        if (unsupported.contains(prop)) return false;
        if (!connected) return null;
        area(prop);
        if (!areasRead) return null;
        return areas.containsKey(prop);
    }

    private void error(String what, Throwable e) {
        Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
        String msg = what + ": " + c;
        if (!msg.equals(lastError)) {
            lastError = msg;
            Diag.error("auto.carservice", msg);
        }
    }

    /**
     * Pressione in bar dal valore grezzo: i sensori danno kPa (es. 250) oppure decimi/centesimi di bar.
     * Restituisce NaN per valori fuori scala.
     */
    public static float toBar(int raw) {
        if (raw == Integer.MIN_VALUE || raw <= 0) return Float.NaN;
        if (raw >= 100 && raw <= 500) return raw / 100f;        // kPa
        if (raw >= 10 && raw < 100) return raw / 10f;           // decimi di bar
        if (raw > 500 && raw <= 5000) return raw / 1000f;       // millibar
        return Float.NaN;
    }
}
