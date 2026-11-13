package com.mg4.launcher.ui;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import com.mg4.launcher.svc.ChargePack;
import com.mg4.launcher.svc.Diag;

import java.io.File;

/**
 * 1.0.4 (punto F): animazione di ricarica sopra l'auto disegnata.
 * <p>
 * Quattro fasi: aggancio (una volta) → loop "in carica" (ripetuto) → finita (una volta, l'ultimo fotogramma resta
 * fermo) → sgancio (al distacco del cavo, poi torna l'immagine ferma). Se il cavo è già inserito quando si accende lo
 * schermo si parte dal loop (o dalla fase finita). Mai in READY o con l'auto in movimento: si torna subito all'immagine
 * ferma. Foto → clip e clip → foto con dissolvenza di 0,3 s; cavo staccato durante il loop: 0,2 s.
 * <p>
 * Riproduzione con il decoder hardware (MediaPlayer su TextureView), due lettori al massimo: quello che si vede e
 * quello della fase successiva, preparata mentre gira la fase attuale. Il fotogramma finale di una fase è uguale al
 * primo della successiva (verificato nella chat di rendering), quindi il passaggio non si vede. I lettori si
 * rilasciano quando la clip non è visibile.
 * <p>
 * Posizione: la clip (1312×400) è il doppio del riquadro dell'auto nel layout di ricarica (circa 656×200); i centri
 * ruota della clip (x 358 e 973, y 288) si allineano a quelli della foto disegnata da CarView.
 */
public final class ChargeAnimView extends FrameLayout {
    public static final int IDLE = 0, AGGANCIO = 1, LOOP = 2, FINITA = 3, SGANCIO = 4;
    private static final String[] NAMES = {"ferma", "aggancio", "loop", "finita", "sgancio"};
    private static final float CLIP_W = 1312, CLIP_H = 400, CLIP_WHEEL_X1 = 358, CLIP_WHEEL_X2 = 973, CLIP_WHEEL_Y = 288;

    /** Dove prendere le clip: pacchetto del colore scelto oppure cartella di prova (chiavetta). */
    public interface Source {
        File clip(boolean day, String phase);
    }

    private final TextureView[] tv = new TextureView[2];
    private final MediaPlayer[] mp = new MediaPlayer[2];
    private final String[] mpPhase = new String[2];
    private int showing = -1;                // slot che si vede
    private int state = IDLE;
    private boolean day = true;
    private boolean fresh = true;            // prima valutazione dopo l'accensione dello schermo
    private boolean finishAfterLoop;
    private Source source;
    private String reason = "";
    private Runnable afterTest;

    public ChargeAnimView(Context c) {
        super(c);
        for (int i = 0; i < 2; i++) {
            TextureView v = new TextureView(c);
            v.setOpaque(false);
            v.setAlpha(0f);
            tv[i] = v;
            addView(v, new FrameLayout.LayoutParams(1, 1));
        }
        setClickable(false);
        setVisibility(GONE);
    }

    public int state() {
        return state;
    }

    public static String name(int st) {
        return NAMES[st];
    }

    /** Lo schermo si è riacceso (o la home è nuova): se il cavo è già inserito si parte dal loop. */
    public void markFresh() {
        fresh = true;
    }

    // ---- geometria: stessa foto e stessi centri ruota di CarView ------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0 && h > 0) {
            float s = CarView.photoScale(w, h);
            float[] wc = CarView.wheelCenter(w, h);   // centro della ruota anteriore della foto, in px della vista
            float k = s * CarView.wheelDistance() / (CLIP_WHEEL_X2 - CLIP_WHEEL_X1);
            int cw = Math.round(CLIP_W * k), ch = Math.round(CLIP_H * k);
            int cx = Math.round(wc[0] - CLIP_WHEEL_X1 * k), cy = Math.round(wc[1] - CLIP_WHEEL_Y * k);
            for (TextureView v : tv) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                if (lp.width != cw || lp.height != ch || lp.leftMargin != cx || lp.topMargin != cy) {
                    lp.width = cw;
                    lp.height = ch;
                    lp.leftMargin = cx;
                    lp.topMargin = cy;
                    v.setLayoutParams(lp);
                }
            }
        }
    }

    // ---- logica delle fasi ---------------------------------------------------------------------

    /**
     * Stato dell'auto (dal thread principale, a ogni lettura del veicolo).
     *
     * @param stopWhy motivo per cui l'animazione non deve girare ("" = può girare): spenta, pacchetto assente, READY...
     */
    public void update(Source src, boolean dayTheme, boolean plugged, boolean done, String stopWhy) {
        if (afterTest != null) return;   // durante la prova si ignorano i segnali dell'auto
        boolean themeChanged = dayTheme != day;
        day = dayTheme;
        source = src;
        if (!stopWhy.isEmpty()) {
            reason = stopWhy;
            // con il cavo già inserito, alla ripartenza si va dritti al loop; l'aggancio solo per un inserimento nuovo
            fresh = plugged;
            if (!plugged) failedThisPlug = false;
            if (state != IDLE) toPhoto(0.3f);
            return;
        }
        // 3: dopo un errore di riproduzione l'animazione resta ferma fino al distacco del cavo (niente tentativi a ripetizione)
        if (!plugged) failedThisPlug = false;
        if (failedThisPlug) {
            reason = "errore di riproduzione: ferma fino al distacco del cavo";
            if (state != IDLE) toPhoto(0.3f);
            writeLog();
            return;
        }
        reason = "";
        boolean wasFresh = fresh;
        fresh = false;
        switch (state) {
            case IDLE:
                if (!plugged) break;
                if (wasFresh) play(done ? FINITA : LOOP, true);
                else play(AGGANCIO, true);
                break;
            case AGGANCIO:
                break;   // si decide alla fine dell'aggancio
            case LOOP:
                if (!plugged) play(SGANCIO, false);
                else if (done && !finishAfterLoop) {
                    finishAfterLoop = true;   // la fase "finita" parte alla fine del giro del loop (stesso fotogramma)
                    MediaPlayer m = cur();
                    if (m != null) m.setLooping(false);
                    prepare(FINITA);
                } else if (themeChanged) play(LOOP, false);
                break;
            case FINITA:
                if (!plugged) play(SGANCIO, false);
                else if (!done) play(LOOP, false);
                else if (themeChanged) play(FINITA, false);
                break;
            case SGANCIO:
                break;   // alla fine torna la foto
        }
        lastPlugged = plugged;
        lastDone = done;
    }

    private boolean lastPlugged, lastDone, failedThisPlug;

    /** Schermo spento o launcher in secondo piano: si torna alla foto e si rilasciano i decoder. */
    public void pause() {
        afterTest = null;
        fresh = true;
        if (state != IDLE || showing >= 0) {
            state = IDLE;
            finishAfterLoop = false;
            writeLog();
        }
        release();
    }

    /** Fine di una fase (dal lettore). */
    private void onPhaseEnd(int phase) {
        if (afterTest != null) {
            testNext(phase);
            return;
        }
        switch (phase) {
            case AGGANCIO:
                if (!lastPlugged) play(SGANCIO, false);
                else play(lastDone ? FINITA : LOOP, false);
                break;
            case LOOP:   // solo con il loop non più ripetuto: arriva la fase "finita"
                finishAfterLoop = false;
                if (!lastPlugged) play(SGANCIO, false);
                else play(FINITA, false);
                break;
            case FINITA:
                break;   // l'ultimo fotogramma resta fermo
            case SGANCIO:
                toPhoto(0.3f);
                break;
        }
    }

    // ---- prova dalla modalità tecnica ----------------------------------------------------------

    private int testLoops;

    /** Prova: aggancio → loop (3 giri) → finita → sgancio, poi di nuovo la foto. */
    public void playTest(Source src, boolean dayTheme, Runnable done) {
        release();
        source = src;
        day = dayTheme;
        afterTest = done;
        testLoops = 0;
        play(AGGANCIO, true);
    }

    private void testNext(int phase) {
        switch (phase) {
            case AGGANCIO:
                play(LOOP, false);
                break;
            case LOOP:
                play(FINITA, false);   // dopo i 3 giri del loop
                break;
            case FINITA:
                postDelayed(() -> {
                    if (afterTest != null) play(SGANCIO, false);
                }, 2000);
                break;
            case SGANCIO:
                Runnable r = afterTest;
                afterTest = null;
                toPhoto(0.3f);
                if (r != null) r.run();
                break;
        }
    }

    // ---- riproduzione --------------------------------------------------------------------------

    private MediaPlayer cur() {
        return showing >= 0 ? mp[showing] : null;
    }

    /** Prepara una fase sul lettore libero (senza farla partire). */
    private void prepare(int phase) {
        int slot = showing < 0 ? 0 : 1 - showing;
        String ph = NAMES[phase];
        if (mp[slot] != null && ph.equals(mpPhase[slot])) return;
        open(slot, phase, null);
    }

    /** Fa partire una fase: sul lettore libero, e quando il primo fotogramma è pronto la mostra al posto dell'altra. */
    private void play(int phase, boolean fromPhoto) {
        int slot = showing < 0 ? 0 : 1 - showing;
        state = phase;
        writeLog();
        if (mp[slot] != null && NAMES[phase].equals(mpPhase[slot]) && prepared[slot]) {
            startSlot(slot, phase, fromPhoto);
            return;
        }
        open(slot, phase, () -> startSlot(slot, phase, fromPhoto));
    }

    private final boolean[] prepared = new boolean[2];
    private final Surface[] surfaces = new Surface[2];

    /** Fine anticipata della prova (errore): la callback finale si chiama comunque e i segnali dell'auto tornano validi. */
    private void endTest() {
        Runnable r = afterTest;
        afterTest = null;
        if (r != null) r.run();
    }

    private void open(int slot, int phase, Runnable onReady) {
        releaseSlot(slot);
        File f = source == null ? null : source.clip(day, NAMES[phase]);
        if (f == null) {
            reason = "clip " + NAMES[phase] + " mancante";
            if (afterTest == null) failedThisPlug = true;
            endTest();
            toPhoto(0.3f);
            return;
        }
        TextureView v = tv[slot];
        setVisibility(VISIBLE);
        Runnable go = () -> {
            SurfaceTexture st = v.getSurfaceTexture();
            if (st == null) return;
            try {
                MediaPlayer m = new MediaPlayer();
                mp[slot] = m;
                mpPhase[slot] = NAMES[phase];
                prepared[slot] = false;
                Surface sf = new Surface(st);
                surfaces[slot] = sf;
                m.setSurface(sf);
                m.setDataSource(f.getAbsolutePath());
                m.setLooping(phase == LOOP && afterTest == null);
                m.setVolume(0f, 0f);
                m.setOnPreparedListener(x -> {
                    prepared[slot] = true;
                    if (onReady != null) onReady.run();
                });
                m.setOnCompletionListener(x -> {
                    if (mp[slot] != x) return;
                    if (afterTest != null && phase == LOOP && ++testLoops < 3) {
                        x.seekTo(0);
                        x.start();
                        return;
                    }
                    // la fase dopo, se già nota, si prepara subito: il passaggio resta sul fotogramma comune
                    onPhaseEnd(phase);
                });
                m.setOnErrorListener((x, what, extra) -> {
                    Diag.put("ricarica.animazione", "errore di riproduzione " + NAMES[phase] + " (" + what + "/" + extra + ")");
                    errors++;
                    if (afterTest == null) failedThisPlug = true;
                    endTest();
                    toPhoto(0.3f);
                    return true;
                });
                m.prepareAsync();
            } catch (Exception e) {
                Diag.put("ricarica.animazione", "errore di riproduzione " + NAMES[phase] + ": " + e.getClass().getSimpleName());
                errors++;
                if (afterTest == null) failedThisPlug = true;
                endTest();
                toPhoto(0.3f);
            }
        };
        if (v.getSurfaceTexture() != null) go.run();
        else v.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                go.run();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture s) {
            }
        });
    }

    /** Errori di riproduzione dall'avvio (per il Controllo funzioni). */
    public static int errors;

    private void startSlot(int slot, int phase, boolean fromPhoto) {
        MediaPlayer m = mp[slot];
        if (m == null) return;
        int old = showing;
        m.setOnInfoListener((x, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                TextureView v = tv[slot];
                v.animate().cancel();
                // da foto: dissolvenza 0,3 s; tra due fasi: subito (stesso fotogramma), cavo staccato nel loop 0,2 s
                long fade = fromPhoto ? 300 : phase == SGANCIO && old >= 0 && LOOP_NAME.equals(mpPhase[old]) ? 200 : 0;
                if (fade > 0) v.animate().alpha(1f).setDuration(fade).start();
                else v.setAlpha(1f);
                if (old >= 0 && old != slot) {
                    MediaPlayer oldMp = mp[old];
                    postDelayed(() -> {
                        // solo se in quello slot c'è ancora il lettore vecchio (non uno ripartito nel frattempo)
                        if (showing != old && mp[old] == oldMp) {
                            tv[old].setAlpha(0f);
                            releaseSlot(old);
                        }
                    }, fade + 80);
                }
                // la fase successiva già nota si prepara mentre gira questa
                if (phase == AGGANCIO) postDelayed(() -> prepare(lastDone ? FINITA : LOOP), 300);
            }
            return false;
        });
        showing = slot;
        try {
            m.start();
        } catch (Exception e) {
            toPhoto(0.3f);
        }
    }

    private static final String LOOP_NAME = "loop";
    private int fadeToken;

    /** Torna all'immagine ferma (dissolvenza) e rilascia i lettori. */
    private void toPhoto(float seconds) {
        state = IDLE;
        finishAfterLoop = false;
        writeLog();
        int s = showing;
        if (s < 0) {
            release();
            return;
        }
        tv[s].animate().cancel();
        final int token = ++fadeToken;
        tv[s].animate().alpha(0f).setDuration(Math.round(seconds * 1000)).withEndAction(() -> {
            // se nel frattempo è ripartita una fase non si tocca niente
            if (token == fadeToken && state == IDLE && showing < 0) release();
        }).start();
        showing = -1;
    }

    private void releaseSlot(int i) {
        MediaPlayer m = mp[i];
        mp[i] = null;
        mpPhase[i] = null;
        prepared[i] = false;
        if (m != null) {
            try {
                m.release();
            } catch (Exception ignored) {
            }
        }
        Surface sf = surfaces[i];
        surfaces[i] = null;
        if (sf != null) sf.release();
    }

    /** Rilascia tutti i lettori (clip non visibile). */
    public void release() {
        for (int i = 0; i < 2; i++) {
            releaseSlot(i);
            tv[i].setAlpha(0f);
        }
        showing = -1;
        if (state == IDLE) setVisibility(GONE);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        afterTest = null;
        state = IDLE;
        release();
    }

    private String lastLog = "";

    private void writeLog() {
        String txt = "fase " + NAMES[state] + (reason.isEmpty() ? "" : " · " + reason) + (afterTest != null ? " · prova" : "");
        if (!txt.equals(lastLog)) {
            lastLog = txt;
            phaseTxt = txt;
        }
    }

    /** Testo della fase per il log (lo compone HomeView con il resto). */
    public static volatile String phaseTxt = "fase ferma";

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        return false;
    }

    @SuppressWarnings("unused")
    private static boolean visible(View v) {
        return v.getVisibility() == VISIBLE;
    }
}
