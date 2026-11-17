package com.mg4.launcher.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import com.mg4.launcher.R;

/**
 * La MG4 disegnata dal render 3D (1.0.4): la stessa auto, nella stessa posa e con gli stessi colori del primo
 * fotogramma dell'animazione di ricarica, così il passaggio immagine ferma → animazione non si vede.
 * <ul>
 *   <li>Un'immagine per colore (car_&lt;colore&gt;.webp, 958×368: riquadro 958×360 + 8 righe sotto per l'ombra), con
 *       vetri trasparenti e ombra a terra già nel render; si carica solo quella del colore scelto.</li>
 *   <li>Tetto nero opzionale: car_roof.webp (ombreggiatura del tetto in grigio + maschera), moltiplicato per 1A1B1E.</li>
 *   <li>Cerchi a scelta disegnati sopra (il render ha l'Aero 17", che non si ridisegna): 16" un po' più piccoli,
 *       18" un po' più grandi.</li>
 *   <li>Fari accesi: car_lights.webp (fari e fanali accesi presi dal render di notte) più il fascio morbido davanti.</li>
 * </ul>
 * Geometria: il riquadro 958×360 corrisponde ai pixel (177, 12)–(1135, 372) dei fotogrammi delle clip (1312×400),
 * quindi i centri ruota sono quelli delle clip (358 e 973, y 288) meno lo scostamento.
 */
public final class CarView extends View {
    /** Nomi dei file per colore, nello stesso ordine di {@link Prefs#CAR_COLORS}. */
    private static final int[] BODY_RES = {R.drawable.car_arctic_white, R.drawable.car_black_pearl, R.drawable.car_camden_grey,
            R.drawable.car_cosmic_silver, R.drawable.car_ealing_green, R.drawable.car_piccadilly_blue,
            R.drawable.car_dynamic_red, R.drawable.car_volcano_orange};
    private static Bitmap body, roof, lightsBmp;
    private static int bodyIdx = -1;

    private final Paint roofLayer = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint plain = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint floor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private boolean blackRoof;
    private int glow;
    private int color = 5;
    /** Cerchi (indice di {@link Prefs#WHEEL_RES}); quello del render non si ridisegna. */
    private int wheel = Prefs.WHEEL_PHOTO;
    private static final android.util.SparseArray<Bitmap> RIMS = new android.util.SparseArray<>();
    /** Riquadro di riferimento (px dell'immagine) e centri/raggio dei cerchi. */
    private static final float PHOTO_W = 958, PHOTO_H = 360;
    private static final float[][] WHEEL_CENTERS = {{181f, 276f}, {796f, 276f}};
    private static final float RIM_R = 54.7f;
    private final Paint rimPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint rimShade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rimDst = new RectF();
    /** Fari accesi (anabbaglianti o posizione) disegnati sull'auto. */
    private boolean lights;
    private final Paint beamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CarView(Context c) {
        super(c);
        roofLayer.setColorFilter(new PorterDuffColorFilter(0xFF1A1B1E, PorterDuff.Mode.MULTIPLY));
    }

    private static Bitmap decode(Context c, int res) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        return BitmapFactory.decodeResource(c.getResources(), res, o);
    }

    /** Immagine del colore scelto (una sola in memoria, condivisa tra le CarView). */
    private static synchronized void load(Context c, int idx) {
        if (roof == null) roof = decode(c, R.drawable.car_roof);
        if (lightsBmp == null) lightsBmp = decode(c, R.drawable.car_lights);
        if (body != null && bodyIdx == idx) return;
        body = decode(c, BODY_RES[idx]);
        bodyIdx = idx;
    }

    public void setLook(int bodyColor, boolean blackRoof, int wheel, Theme t) {
        this.wheel = wheel;
        setLook(bodyColor, blackRoof, t);
    }

    private Bitmap rim(int idx) {
        synchronized (RIMS) {
            Bitmap b = RIMS.get(idx);
            if (b == null) {
                b = decode(getContext(), Prefs.WHEEL_RES[idx]);
                if (b != null) RIMS.put(idx, b);
            }
            return b;
        }
    }

    /** bodyColor = uno dei {@link Prefs#CAR_COLOR_VALUES}: sceglie l'immagine di quel colore. */
    public void setLook(int bodyColor, boolean blackRoof, Theme t) {
        int idx = 0;
        for (int i = 0; i < Prefs.CAR_COLOR_VALUES.length; i++) if (Prefs.CAR_COLOR_VALUES[i] == bodyColor) idx = i;
        color = Math.min(idx, BODY_RES.length - 1);
        this.blackRoof = blackRoof;
        glow = t.glow;
        floor.setShader(null);
        invalidate();
    }

    /** Fari accesi sull'auto disegnata; se lo stato non si legge l'auto resta com'è (false). */
    public void setLights(boolean on) {
        if (on == lights) return;
        lights = on;
        invalidate();
    }

    // ---- geometria: serve anche all'animazione di ricarica (stesso riquadro, stessi centri ruota) --

    /** Scala del riquadro in una vista w×h (stessa formula di onDraw). */
    public static float photoScale(int w, int h) {
        return Math.min(w / PHOTO_W, (h * 0.9f) / PHOTO_H);
    }

    /** Centro della ruota anteriore (a sinistra) in px della vista w×h. */
    public static float[] wheelCenter(int w, int h) {
        float s = photoScale(w, h);
        float dw = PHOTO_W * s, dh = PHOTO_H * s;
        float left = (w - dw) / 2f, top = (h - dh) / 2f - h * 0.02f;
        return new float[]{left + WHEEL_CENTERS[0][0] * s, top + WHEEL_CENTERS[0][1] * s};
    }

    /** Distanza tra i centri delle ruote (px dell'immagine). */
    public static float wheelDistance() {
        return WHEEL_CENTERS[1][0] - WHEEL_CENTERS[0][0];
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        load(getContext(), color);
        if (body == null) return;
        float s = photoScale(w, h);
        float dw = PHOTO_W * s, dh = PHOTO_H * s;
        float left = (w - dw) / 2f, top = (h - dh) / 2f - h * 0.02f;
        // l'immagine ha 8 righe in più sotto il riquadro (ombra a terra)
        dst.set(left, top, left + dw, top + body.getHeight() * s);

        // alone sul "pavimento" come nelle clip (l'ombra sotto l'auto è già nel render)
        float cy = top + dh - dh * 0.035f;
        if (floor.getShader() == null) {
            floor.setShader(new RadialGradient(w / 2f, cy, dw * 0.55f, glow, glow & 0x00FFFFFF, Shader.TileMode.CLAMP));
        }
        c.save();
        c.scale(1f, 0.09f, w / 2f, cy);
        c.drawCircle(w / 2f, cy, dw * 0.55f, floor);
        c.restore();

        c.drawBitmap(body, null, dst, plain);
        if (blackRoof && roof != null) c.drawBitmap(roof, null, dst, roofLayer);
        drawWheels(c, s, left, top);
        if (lights) drawLights(c, s, left, top);
    }

    /** Fari e fanali accesi dal render di notte, più un fascio morbido davanti al faro (muso a sinistra). */
    private void drawLights(Canvas c, float s, float left, float top) {
        float fx = left + 73 * s, fy = top + 171 * s;
        beamPaint.setShader(new RadialGradient(fx - 50 * s, fy + 8 * s, 188 * s, new int[]{0x66FFF6D8, 0x22FFF6D8, 0x00FFF6D8},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1f, 0.38f, fx - 50 * s, fy + 8 * s);
        c.drawCircle(fx - 50 * s, fy + 8 * s, 188 * s, beamPaint);
        c.restore();
        if (lightsBmp != null) c.drawBitmap(lightsBmp, null, dst, plain);
    }

    private void drawWheels(Canvas c, float s, float left, float top) {
        if (wheel == Prefs.WHEEL_PHOTO || wheel < 0 || wheel >= Prefs.WHEEL_RES.length) return;
        Bitmap b = rim(wheel);
        if (b == null) return;
        float r = RIM_R * Prefs.WHEEL_SCALE[wheel] * s;
        for (float[] wc : WHEEL_CENTERS) {
            float cx = left + wc[0] * s, cy = top + wc[1] * s;
            rimDst.set(cx - r, cy - r, cx + r, cy + r);
            c.drawBitmap(b, null, rimDst, rimPaint);
            // ombra in basso come nel render (luce dall'alto)
            rimShade.setShader(new android.graphics.LinearGradient(0, cy - r, 0, cy + r, 0x00000000, 0x55000000, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, rimShade);
        }
    }
}
