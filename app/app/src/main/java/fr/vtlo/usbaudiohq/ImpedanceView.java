package fr.vtlo.usbaudiohq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.View;

/** Frequency-response change caused by the source impedance (solid) vs an ideal 0 ohm source (dashed), with the headphone impedance curve faint. */
final class ImpedanceView extends View {
    static final int POINTS = 200;
    final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), cur = new Paint(Paint.ANTI_ALIAS_FLAG),
            ref = new Paint(Paint.ANTI_ALIAS_FLAG), zc = new Paint(Paint.ANTI_ALIAS_FLAG),
            txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    final double[] db = new double[POINTS + 1], z = new double[POINTS + 1];
    double zMax = 1, lo = -3;
    int level = 0;
    String info = "";

    ImpedanceView(Context c) {
        super(c);
        grid.setColor(0x22FFFFFF);
        grid.setStrokeWidth(dp(1));
        cur.setStyle(Paint.Style.STROKE);
        cur.setStrokeWidth(dp(2));
        cur.setStrokeJoin(Paint.Join.ROUND);
        ref.setColor(0xFF8B93A1);
        ref.setStyle(Paint.Style.STROKE);
        ref.setStrokeWidth(dp(1.5f));
        ref.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(4)}, 0));
        zc.setColor(0x66FFB454);
        zc.setStyle(Paint.Style.STROKE);
        zc.setStrokeWidth(dp(1.5f));
        txt.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, getResources().getDisplayMetrics()));
        txt.setColor(0xFF8B93A1);
        setParams(1, 32, 0);
    }

    float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    void setParams(double zo, double zh, int type) {
        Impedance.Result r = Impedance.compute(zo, zh, 100, 1, type);
        level = r.level;
        zMax = r.zMax;
        double min = 0;
        for (int i = 0; i <= POINTS; i++) {
            double f = Impedance.F_MIN * Math.pow(Impedance.F_MAX / Impedance.F_MIN, i / (double) POINTS);
            z[i] = Impedance.zAt(type, zh, f);
            db[i] = Impedance.lossDb(zo, z[i]);
            min = Math.min(min, db[i]);
        }
        lo = Math.min(-3, Math.floor(min - 0.5));
        cur.setColor(level == 0 ? 0xFF7CF0C8 : level == 1 ? 0xFFFFB454 : 0xFFFF6B6B);
        info = String.format(java.util.Locale.US, "Zo %.1f ohm into %.0f ohm (%.0f-%.0f ohm over frequency)", zo, zh, r.zMin, r.zMax);
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize(0, w), (int) dp(170));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float l = dp(30), t = dp(8), r = w - dp(6), b = h - dp(34);
        double span = -lo + 0.5;
        double step = span <= 6 ? 1 : 3;
        for (double v = 0; v >= lo; v -= step) {
            float y = (float) (t + (0.5 - v) / span * (b - t));
            c.drawLine(l, y, r, y, grid);
            c.drawText((int) v + "", dp(4), y + dp(3), txt);
        }
        double[] ticks = {100, 1000, 10000};
        String[] tl = {"100", "1k", "10k"};
        double lr = Math.log(Impedance.F_MAX / Impedance.F_MIN);
        for (int i = 0; i < ticks.length; i++) {
            float x = (float) (l + Math.log(ticks[i] / Impedance.F_MIN) / lr * (r - l));
            c.drawLine(x, t, x, b, grid);
            c.drawText(tl[i] + " Hz", x - dp(10), b + dp(13), txt);
        }
        float y0 = (float) (t + 0.5 / span * (b - t));
        c.drawLine(l, y0, r, y0, ref);
        Path zp = new Path(), p = new Path();
        for (int i = 0; i <= POINTS; i++) {
            float x = l + (r - l) * i / POINTS;
            float yz = (float) (b - z[i] / (zMax * 1.15) * (b - t) * 0.6);
            float y = (float) (t + (0.5 - db[i]) / span * (b - t));
            if (i == 0) { zp.moveTo(x, yz); p.moveTo(x, y); } else { zp.lineTo(x, yz); p.lineTo(x, y); }
        }
        c.drawPath(zp, zc);
        c.drawPath(p, cur);
        c.drawText(info, l, h - dp(4), txt);
    }
}
