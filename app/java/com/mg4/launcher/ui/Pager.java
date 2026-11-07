package com.mg4.launcher.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

/**
 * Pagine affiancate a scorrimento orizzontale (Home ↔ App). Il trascinamento verticale resta ai figli
 * (es. la griglia delle app che scorre in verticale).
 */
public final class Pager extends ViewGroup {
    public interface Listener {
        void onPageChanged(int page);
    }

    private final int slop;
    private final int minFling;
    private float downX, downY, lastX;
    private boolean dragging;
    private VelocityTracker vt;
    private int page;
    private float offset;          // 0 = pagina 0, larghezza = pagina 1, ...
    private ValueAnimator anim;
    private Listener listener;

    public Pager(Context c) {
        super(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop() * 2;
        minFling = vc.getScaledMinimumFlingVelocity() * 4;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public int page() {
        return page;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t);
        offset = page * w;
        apply();
    }

    private void apply() {
        setScrollX(Math.round(offset));
    }

    private float maxOffset() {
        return Math.max(0, (getChildCount() - 1) * getWidth());
    }

    /** Va alla pagina indicata, con animazione. */
    public void show(int p, boolean animate) {
        p = Math.max(0, Math.min(getChildCount() - 1, p));
        boolean changedPage = p != page;
        page = p;
        float target = p * getWidth();
        if (anim != null) anim.cancel();
        if (!animate || getWidth() == 0) {
            offset = target;
            apply();
            if (!dragging) layers(false);
        } else {
            // 1.0.2 (issue SWI69 #2): animazione più fluida. Le pagine diventano "livelli hardware" (disegnati una volta in
            // una texture e solo spostati durante lo scorrimento) e la pagina App si ricarica a fine animazione, non
            // all'inizio (prima il ricaricamento cadeva sui primi fotogrammi e dava lo scatto). Durata corta e morbida:
            // con 2 GB di RAM due texture 1920×720 sono circa 11 MB e si liberano subito dopo.
            float dist = Math.abs(target - offset);
            long dur = Math.max(180, Math.min(320, Math.round(320 * dist / Math.max(1f, getWidth()))));
            anim = ValueAnimator.ofFloat(offset, target);
            anim.setDuration(dur);
            anim.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
            anim.addUpdateListener(a -> {
                offset = (float) a.getAnimatedValue();
                apply();
            });
            final int newPage = p;
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationStart(android.animation.Animator a) {
                    layers(true);
                }

                @Override
                public void onAnimationCancel(android.animation.Animator a) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (!dragging) layers(false);
                    // notifica anche se un trascinamento aveva interrotto l'animazione che cambiava pagina
                    if (!cancelled && page == newPage && newPage != notified && listener != null) {
                        notified = newPage;
                        listener.onPageChanged(newPage);
                    }
                }
            });
            anim.start();
            return;
        }
        if (p != notified && listener != null) {
            notified = p;
            listener.onPageChanged(p);
        }
    }

    /** Ultima pagina notificata al listener. */
    private int notified;

    /** Livelli hardware sulle pagine durante animazione e trascinamento. */
    private void layers(boolean on) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            int type = on ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
            if (v.getLayerType() != type) {
                v.setLayerType(type, null);
                if (on) v.buildLayer();
            }
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = e.getY();
                dragging = false;
                track(e, true);
                if (anim != null && anim.isRunning()) {
                    anim.cancel();
                    dragging = true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                track(e, false);
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    dragging = true;
                    layers(true);
                    lastX = e.getX();
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) recycle();
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        track(e, false);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = lastX - e.getX();
                lastX = e.getX();
                offset = Math.max(0, Math.min(maxOffset(), offset + dx));
                apply();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int target = page;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    float v = vt.getXVelocity();
                    if (v < -minFling) target = page + 1;
                    else if (v > minFling) target = page - 1;
                    else target = Math.round(offset / Math.max(1, getWidth()));
                }
                dragging = false;
                recycle();
                show(target, true);
                return true;
            }
        }
        return true;
    }

    private void track(MotionEvent e, boolean reset) {
        if (vt == null || reset) {
            recycle();
            vt = VelocityTracker.obtain();
        }
        vt.addMovement(e);
    }

    private void recycle() {
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }

    @Override
    public void addView(View child) {
        super.addView(child, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }
}
