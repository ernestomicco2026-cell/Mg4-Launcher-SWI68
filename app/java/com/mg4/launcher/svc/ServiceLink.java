package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

/** Collegamento a un servizio di sistema con riconnessione automatica (servizio riavviato o non ancora pronto). */
public final class ServiceLink implements ServiceConnection, IBinder.DeathRecipient {
    private static final String TAG = "MG4L.Link";
    private static final long RETRY_MS = 3000;

    private final Context context;
    private final Intent intent;
    private final String name;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile IBinder binder;
    private boolean wanted;
    /** False: ci si collega solo se il servizio è già avviato da qualcun altro (non lo si fa partire noi). */
    private boolean autoCreate = true;
    private Runnable onConnected;
    // protezione: servizio che muore subito dopo il collegamento (es. radio su firmware SWI68 più vecchi)
    private long connectedAt;
    private int quickDeaths;
    private long firstQuickDeath;
    private volatile boolean fragile;
    /** Ultima chiamata fatta dal launcher a questo servizio (per capire quale lo fa morire). */
    public volatile String lastCall = "";
    private static final int FRAGILE_DEATHS = 3;
    private static final long QUICK_MS = 6000, FRAGILE_WINDOW_MS = 60_000;

    public ServiceLink(Context context, String name, Intent intent) {
        this.context = context.getApplicationContext();
        this.name = name;
        this.intent = intent;
    }

    public static Intent byAction(String pkg, String action) {
        return new Intent(action).setPackage(pkg);
    }

    public static Intent byComponent(String pkg, String cls, String action) {
        Intent i = new Intent().setComponent(new ComponentName(pkg, cls));
        if (action != null) i.setAction(action);
        return i;
    }

    public void setAutoCreate(boolean b) {
        autoCreate = b;
    }

    public void setOnConnected(Runnable r) {
        onConnected = r;
    }

    public IBinder binder() {
        IBinder b = binder;
        return (b != null && b.isBinderAlive()) ? b : null;
    }

    public boolean isConnected() {
        return binder() != null;
    }

    /**
     * True se il servizio è morto almeno 3 volte in un minuto, ogni volta pochi secondi dopo il collegamento: il
     * launcher smette di collegarsi e di interrogarlo, così il servizio dell'auto resta in piedi.
     */
    public boolean fragile() {
        return fragile;
    }

    public void start() {
        if (fragile) return;
        wanted = true;
        bind();
    }

    public void stop() {
        wanted = false;
        handler.removeCallbacksAndMessages(null);
        try {
            context.unbindService(this);
        } catch (Exception ignored) {
        }
        binder = null;
    }

    private void bind() {
        if (!wanted) return;
        boolean ok;
        try {
            ok = context.bindService(intent, this, autoCreate ? Context.BIND_AUTO_CREATE : 0);
        } catch (Exception e) {
            ok = false;
            Diag.error(name, "bindService: " + e);
        }
        Diag.put(name + ".bind", ok ? "ok" : "fallito");
        if (!ok) handler.postDelayed(this::bind, RETRY_MS * 3);
    }

    @Override
    public void onServiceConnected(ComponentName cn, IBinder service) {
        binder = service;
        connectedAt = android.os.SystemClock.uptimeMillis();
        Diag.put(name + ".stato", "collegato");
        try {
            service.linkToDeath(this, 0);
        } catch (RemoteException e) {
            Log.w(TAG, name + " linkToDeath", e);
        }
        if (onConnected != null) onConnected.run();
    }

    @Override
    public void onServiceDisconnected(ComponentName cn) {
        binder = null;
        Diag.put(name + ".stato", "scollegato");
    }

    @Override
    public void binderDied() {
        binder = null;
        Diag.put(name + ".stato", "servizio riavviato" + (lastCall.isEmpty() ? "" : " (ultima chiamata: " + lastCall + ")"));
        long now = android.os.SystemClock.uptimeMillis();
        if (now - connectedAt < QUICK_MS) {
            if (quickDeaths == 0 || now - firstQuickDeath > FRAGILE_WINDOW_MS) {
                quickDeaths = 0;
                firstQuickDeath = now;
            }
            quickDeaths++;
            if (quickDeaths >= FRAGILE_DEATHS && !fragile) {
                fragile = true;
                Diag.error(name + ".protezione", "il servizio muore subito dopo il collegamento (" + quickDeaths
                        + " volte, ultima chiamata: " + lastCall + "): il launcher non lo usa più fino al prossimo avvio");
            }
        }
        if (fragile) {
            wanted = false;
            handler.post(() -> {
                try {
                    context.unbindService(this);
                } catch (Exception ignored) {
                }
            });
            return;
        }
        handler.post(() -> {
            try {
                context.unbindService(this);
            } catch (Exception ignored) {
            }
            handler.postDelayed(this::bind, RETRY_MS);
        });
    }
}
