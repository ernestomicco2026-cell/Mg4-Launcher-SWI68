package com.mg4.launcher.svc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Avvio all'accensione (LOCKED_BOOT_COMPLETED / BOOT_COMPLETED). Controlla la scadenza dell'officina e registra i
 * tempi. Se il servizio di accessibilità è attivo, la sostituzione della home MG la fa lui (prima e senza
 * controlli periodici); altrimenti, come riserva, si guarda l'app in primo piano ogni 400 ms.
 */
public final class BootWatcher extends BroadcastReceiver {
    public static final String MG_HOME = "com.saicmotor.hmi.launcher";
    private static final long PERIOD_MS = 400, MAX_MS = 55_000;   // il limite di un receiver in background è 60 s

    private static volatile boolean running;

    /** Da chiamare quando l'utente apre apposta la home MG. */
    public static void userOpenedOriginal(Context c) {
        StartGuard.userOpenedOriginal(c);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean locked = Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);
        if (!locked && !Intent.ACTION_BOOT_COMPLETED.equals(action)) return;
        Context c = context.getApplicationContext();
        if (Firmware.wrong(c)) return;   // versione per SWI68 installata su un altro firmware: non si tocca nulla
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date());
        Diag.put("avvio.boot", "ricevuto alle " + time + " (" + (locked ? "locked" : "normale") + ")"
                + " · " + (SystemClock.elapsedRealtime() / 1000) + " s dall'accensione");
        StartGuard.markBoot();
        HomeMode.checkExpiry(c);
        HomeMode.diag(c);
        BootAccessibility.ensureEnabled(c);
        if (!HomeMode.active(c)) {
            Diag.put("avvio.homeRipresa", "no (" + HomeMode.reason(c) + ")");
            return;
        }
        if (StartGuard.a11yConnected) return;   // ci pensa il servizio di accessibilità
        if (running) return;
        running = true;
        StartGuard.openWindow(c, "accensione (riserva)");
        PendingResult pending = goAsync();
        HandlerThread thread = new HandlerThread("mg4-boot");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        long start = SystemClock.elapsedRealtime();
        h.post(new Runnable() {
            @Override
            public void run() {
                if (!StartGuard.a11yConnected) StartGuard.onForeground(c, StartGuard.topPackage(c));
                if (StartGuard.a11yConnected || !StartGuard.windowOpen() || SystemClock.elapsedRealtime() - start >= MAX_MS) {
                    running = false;
                    thread.quitSafely();
                    try {
                        pending.finish();
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                h.postDelayed(this, PERIOD_MS);
            }
        });
    }
}
