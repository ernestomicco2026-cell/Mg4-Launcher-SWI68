package com.mg4.launcher.ui;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Debug;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import com.mg4.launcher.svc.Diag;

/**
 * 1.0.5 (punto 6, SPERIMENTALE): il navigatore MG (Telenav) dentro il pannello mappe, su uno schermo virtuale del
 * launcher grande quanto il pannello (come ActivityView di Android 9: densità 160, PUBLIC + OWN_CONTENT_ONLY +
 * DESTROY_CONTENT_ON_REMOVAL). Solo da guardare: i tocchi non passano al navigatore; un tocco lo apre a tutto schermo.
 * <p>
 * Quando lo schermo virtuale si chiude (opzione spenta, launcher chiuso o in pausa per più di 60 s, tocco per il
 * tutto schermo) Android chiude le attività che c'erano sopra (DESTROY_CONTENT_ON_REMOVAL): il navigatore non può
 * comparire da solo a tutto schermo sopra la home. Log pip.prova / pip.stato / pip.memoria.
 * Se Android rifiuta l'avvio, l'opzione viene segnata come non disponibile e il pannello torna quello di sempre.
 */
public final class NavWindow extends FrameLayout implements TextureView.SurfaceTextureListener {
    public static final String NAV_PKG = "com.telenav.app.arp";
    /** DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL (nascosto in Android 9). */
    private static final int FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int DENSITY = 160;
    private static final long PAUSE_RELEASE_MS = 60_000;

    public interface Listener {
        /** Android ha rifiutato: il pannello torna quello di sempre. */
        void refused();

        /** Tocco sul riquadro: aprire il navigatore a tutto schermo. */
        void openFull();
    }

    private final TextureView tv;
    private final Prefs prefs;
    private final Listener listener;
    private VirtualDisplay vd;
    private Surface surface;
    private int w, h;
    private final Runnable pauseRelease = () -> release("launcher in pausa da più di 60 s");
    /** Riquadro attivo (al massimo uno): va chiuso prima di aprire il navigatore in altro modo. */
    private static java.lang.ref.WeakReference<NavWindow> current;

    public NavWindow(Context c, Prefs prefs, Listener l) {
        super(c);
        this.prefs = prefs;
        this.listener = l;
        tv = new TextureView(c);
        tv.setSurfaceTextureListener(this);
        addView(tv, new LayoutParams(-1, -1));
        setClickable(true);
        setOnClickListener(v -> {
            release("tocco: navigatore a tutto schermo");
            listener.openFull();
        });
    }

    /** true se il navigatore MG è installato e la prova non è già stata rifiutata. */
    public static boolean available(Context c, Prefs p) {
        return !p.pipRefused() && c.getPackageManager().getLaunchIntentForPackage(NAV_PKG) != null;
    }

    /** Chiude il riquadro, se c'è, prima di aprire il navigatore a tutto schermo da un altro comando. */
    public static void releaseActive(String why) {
        NavWindow n = current == null ? null : current.get();
        if (n != null) n.release(why);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        w = width;
        h = height;
        surface = new Surface(st);
        start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        w = width;
        h = height;
        if (vd != null) vd.resize(width, height, DENSITY);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release("superficie chiusa");
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    private void start() {
        if (vd != null || surface == null || w <= 0 || h <= 0) return;
        long t0 = SystemClock.uptimeMillis();
        long pss0 = Debug.getPss();
        try {
            DisplayManager dm = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
            vd = dm.createVirtualDisplay("mg4-navigatore", w, h, DENSITY, surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            | FLAG_DESTROY_CONTENT_ON_REMOVAL);
            int id = vd.getDisplay().getDisplayId();
            Intent i = getContext().getPackageManager().getLaunchIntentForPackage(NAV_PKG);
            if (i == null) throw new IllegalStateException("navigatore MG non installato");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(id);
            getContext().startActivity(i, o.toBundle());
            current = new java.lang.ref.WeakReference<>(this);
            Diag.put("pip.prova", "riuscita · schermo " + id + " · " + w + "×" + h + " · " + (SystemClock.uptimeMillis() - t0) + " ms");
            Diag.put("pip.stato", "navigatore nel riquadro");
            postDelayed(() -> Diag.put("pip.memoria", "launcher PSS " + pss0 / 1024 + " → " + Debug.getPss() / 1024
                    + " MB · superficie circa " + (w * h * 4 * 3) / (1024 * 1024) + " MB (3 buffer da " + w + "×" + h + ")"), 5000);
        } catch (Throwable e) {
            Diag.put("pip.prova", "rifiutata (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage())
                    + ") · " + w + "×" + h + " · " + (SystemClock.uptimeMillis() - t0) + " ms");
            release("prova rifiutata");
            prefs.setPipRefused(true);
            prefs.setMapPanelWindow(false);
            listener.refused();
        }
    }

    /** Chiude lo schermo virtuale: il navigatore che c'era sopra si chiude con lui, non va a tutto schermo. */
    public void release(String why) {
        removeCallbacks(pauseRelease);
        if (vd == null) return;
        try {
            vd.release();
        } catch (Throwable ignored) {
        }
        vd = null;
        Diag.put("pip.stato", "riquadro chiuso: " + why);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            removeCallbacks(pauseRelease);
            if (vd == null && surface != null) start();
        } else {
            postDelayed(pauseRelease, PAUSE_RELEASE_MS);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        release("riquadro tolto dalla home");
        super.onDetachedFromWindow();
    }
}
