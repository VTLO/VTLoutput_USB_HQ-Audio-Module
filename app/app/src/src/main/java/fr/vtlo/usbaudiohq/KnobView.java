package fr.vtlo.usbaudiohq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/** Rotary knob in the style of a DAW device: drag up/down to change, tap left/right of centre for +-1. */
final class KnobView extends View {
    interface Fmt { String f(int v); }
    interface Listener { void onChange(int v, boolean fromUser, boolean finished); }

    final String name;
    final int min, max;
    int val;
    int color = 0xFF1BC4CE;
    Fmt fmt;
    Listener listener;
    final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG),
            face = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG),
            lab = new Paint(Paint.ANTI_ALIAS_FLAG), ptr = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF rf = new RectF();
    float downY, downX, startVal;
    boolean moved;

    KnobView(Context c, String name, int min, int max, Fmt fmt) {
        super(c);
        this.name = name; this.min = min; this.max = max; this.fmt = fmt; this.val = min;
        track.setStyle(Paint.Style.STROKE); track.setStrokeWidth(dp(3)); track.setColor(0xFF3C3C3C); track.setStrokeCap(Paint.Cap.ROUND);
        arc.setStyle(Paint.Style.STROKE); arc.setStrokeWidth(dp(3)); arc.setStrokeCap(Paint.Cap.ROUND);
        face.setColor(0xFF1A1A1A);
        ptr.setStrokeWidth(dp(2)); ptr.setColor(0xFFE0E0E0); ptr.setStrokeCap(Paint.Cap.ROUND);
        txt.setColor(0xFFDADADA); txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, getResources().getDisplayMetrics()));
        lab.setColor(0xFF8F8F8F); lab.setTextAlign(Paint.Align.CENTER);
        lab.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
    }

    float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    int get() { return val; }

    void set(int v) {
        val = Math.max(min, Math.min(max, v));
        invalidate();
    }

    @Override public void setEnabled(boolean e) { super.setEnabled(e); setAlpha(e ? 1f : 0.4f); }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(resolveSize((int) dp(80), w), (int) dp(102)); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth();
        float cx = w / 2f, r = dp(24), cy = dp(18) + r;
        c.drawText(name, cx, dp(12), lab);
        c.drawCircle(cx, cy, r, face);
        float ar = r - dp(4);
        rf.set(cx - ar, cy - ar, cx + ar, cy + ar);
        c.drawArc(rf, 135, 270, false, track);
        float frac = max == min ? 0 : (val - min) / (float) (max - min);
        arc.setColor(isEnabled() ? color : 0xFF666666);
        c.drawArc(rf, 135, 270 * frac, false, arc);
        double a = Math.toRadians(135 + 270 * frac);
        c.drawLine(cx + (float) Math.cos(a) * dp(6), cy + (float) Math.sin(a) * dp(6),
                cx + (float) Math.cos(a) * (ar - dp(4)), cy + (float) Math.sin(a) * (ar - dp(4)), ptr);
        c.drawText(fmt == null ? String.valueOf(val) : fmt.f(val), cx, cy + r + dp(17), txt);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY(); downX = e.getX(); startVal = val; moved = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = downY - e.getY();
                if (Math.abs(dy) > dp(6)) moved = true;
                if (moved) {
                    int nv = (int) Math.round(startVal + dy / dp(220) * (max - min));
                    nv = Math.max(min, Math.min(max, nv));
                    if (nv != val) { val = nv; invalidate(); if (listener != null) listener.onChange(val, true, false); }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!moved) {
                    int nv = Math.max(min, Math.min(max, val + (e.getX() < getWidth() / 2f ? -1 : 1)));
                    val = nv; invalidate();
                }
                if (listener != null) listener.onChange(val, true, true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (listener != null) listener.onChange(val, true, true);
                return true;
            default: return true;
        }
    }
}
