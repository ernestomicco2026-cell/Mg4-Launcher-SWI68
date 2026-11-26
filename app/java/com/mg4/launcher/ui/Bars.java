package com.mg4.launcher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Barre orizzontali arrotondate (batteria, avanzamento brano) e sfondo stilizzato della mappa. */
public final class Bars {
    private Bars() {}

    public static final class Progress extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float value;          // 0..1, < 0 = sconosciuto
        private float limit = -1;     // tacca opzionale (limite di ricarica)
        private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Progress(Context c) {
            super(c);
        }

        public void setColors(int trackColor, int fillColor) {
            track.setColor(trackColor);
            fill.setColor(fillColor);
            mark.setColor(fillColor);
            mark.setAlpha(160);
            invalidate();
        }

        public void setValue(float v) {
            if (v != value) {
                value = v;
                invalidate();
            }
        }

        public void setLimit(float l) {
            limit = l;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            c.drawRoundRect(0, 0, w, h, r, r, track);
            if (value > 0) {
                float fw = Math.max(h, w * Math.min(1f, value));
                c.drawRoundRect(0, 0, fw, h, r, r, fill);
            }
            if (limit > 0 && limit < 1) {
                float x = w * limit;
                c.drawRect(x - 1.5f, -h * 0.6f, x + 1.5f, h * 1.6f, mark);
            }
        }
    }

    /** Sfondo decorativo "mappa" (strade stilizzate) per il pannello di navigazione. */
    public static final class MapDecor extends View {
        private final Paint major = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint minor = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path p = new Path();
        private int bg;

        public MapDecor(Context c) {
            super(c);
            major.setStyle(Paint.Style.STROKE);
            major.setStrokeCap(Paint.Cap.ROUND);
            minor.setStyle(Paint.Style.STROKE);
        }

        public void setColors(int background, int road, int roadMinor) {
            bg = background;
            major.setColor(road);
            minor.setColor(roadMinor);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawColor(bg);
            float k = w / 1170f;
            minor.setStrokeWidth(6 * k);
            major.setStrokeWidth(14 * k);
            float[][] minorLines = {{120, -20, 180, 520}, {520, -20, 560, 520}, {940, -20, 980, 520},
                    {-20, 230, 1200, 250}, {-20, 450, 1200, 430}, {640, -20, 620, 520}};
            for (float[] l : minorLines) c.drawLine(l[0] * k, l[1] * k, l[2] * k, l[3] * k, minor);
            p.reset();
            p.moveTo(-20 * k, 360 * k);
            p.cubicTo(200 * k, 330 * k, 380 * k, 380 * k, 560 * k, 300 * k);
            p.cubicTo(740 * k, 220 * k, 880 * k, 180 * k, 1200 * k, 210 * k);
            c.drawPath(p, major);
            c.drawLine(300 * k, -20 * k, 360 * k, 520 * k, major);
            p.reset();
            p.moveTo(760 * k, -20 * k);
            p.cubicTo(740 * k, 160 * k, 800 * k, 330 * k, 720 * k, 520 * k);
            c.drawPath(p, major);
            c.drawLine(-20 * k, 120 * k, 1200 * k, 90 * k, major);
            if (h > 520 * k) c.drawLine(-20 * k, 600 * k, 1200 * k, 620 * k, minor);
        }
    }

    /** Tacche del segnale (rete mobile): 4 barre crescenti, "level" piene. */
    public static final class Signal extends View {
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint off = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int level;

        public Signal(Context c) {
            super(c);
        }

        public void setColors(int fg, int dim) {
            on.setColor(fg);
            off.setColor(dim);
            invalidate();
        }

        public void setLevel(int l) {
            if (l != level) {
                level = l;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            int n = 4;
            float gap = w * 0.08f, bw = (w - gap * (n - 1)) / n, r = bw / 3f;
            for (int i = 0; i < n; i++) {
                float bh = h * (0.35f + 0.65f * i / (n - 1));
                float x = i * (bw + gap);
                c.drawRoundRect(x, h - bh, x + bw, h, r, r, i < level ? on : off);
            }
        }
    }

    /**
     * 1.0.7: consumo istantaneo. Barra con lo zero a un quarto della larghezza: verso destra il consumo (fino a +150 kW),
     * verso sinistra il recupero (fino a -50 kW). Colori dalla palette: consumo con text, recupero con battery.
     */
    public static final class Power extends View {
        private static final float MAX_USE = 150f, MAX_REGEN = 50f, ZERO = MAX_REGEN / (MAX_REGEN + MAX_USE);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint use = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint regen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint zero = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float kw = Float.NaN;

        public Power(Context c) {
            super(c);
        }

        public void setColors(int trackColor, int useColor, int regenColor, int zeroColor) {
            track.setColor(trackColor);
            use.setColor(useColor);
            regen.setColor(regenColor);
            zero.setColor(zeroColor);
            invalidate();
        }

        public void setKw(float v) {
            if (Float.compare(v, kw) != 0) {
                kw = v;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            c.drawRoundRect(0, 0, w, h, r, r, track);
            float x0 = w * ZERO;
            if (!Float.isNaN(kw) && Math.abs(kw) >= 0.1f) {
                if (kw > 0) {
                    float x1 = x0 + (w - x0) * Math.min(1f, kw / MAX_USE);
                    c.drawRoundRect(x0, 0, Math.max(x1, x0 + h), h, r, r, use);
                } else {
                    float x1 = x0 - x0 * Math.min(1f, -kw / MAX_REGEN);
                    c.drawRoundRect(Math.min(x1, x0 - h), 0, x0, h, r, r, regen);
                }
            }
            c.drawRect(x0 - 1, 0, x0 + 1, h, zero);
        }
    }
}
