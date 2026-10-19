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

    private final Context ctx;
    private Object car, propertyManager;
    private volatile boolean connected;
    private final Map<Integer, Integer> areas = new HashMap<>();
    private boolean areasRead;
    private String lastError = "";
    /** Proprietà che l'auto rifiuta (IllegalArgumentException): non si rileggono più. */
    private final java.util.Set<Integer> unsupported = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public CarProps(Context c) {
        ctx = c.getApplicationContext();
    }

    /** Si collega al CarService (va chiamato da un thread in background). */
    public synchronized void connect() {
        if (car != null) return;
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
        if (unsupported.contains(prop)) return Float.NaN;
        try {
            Object p = pm();
            if (p == null) return Float.NaN;
            Method m = p.getClass().getMethod("getFloatProperty", int.class, int.class);
            return (Float) m.invoke(p, prop, area(prop));
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            if (c instanceof IllegalArgumentException) {
                unsupported.add(prop);
                Diag.put("auto.proprietàNonSupportata." + Integer.toHexString(prop), "non disponibile su questa auto (" + c.getClass().getSimpleName() + ")");
                return Float.NaN;
            }
            error("lettura " + prop, e);
            return Float.NaN;
        }
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
