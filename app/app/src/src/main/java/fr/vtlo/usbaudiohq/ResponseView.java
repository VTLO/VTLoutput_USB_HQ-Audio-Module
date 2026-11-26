package fr.vtlo.usbaudiohq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.View;

/**
 * Model of the resampler's anti-imaging filter: a Kaiser-windowed sinc designed with the same
 * formulas as AOSP's AudioResamplerFirGen (beta from stopband attenuation, normalized transition
 * bandwidth (A-7.95)/(2*14.36*halfLength)). Drawn for a 44.1 kHz source, with the AOSP default
 * (90 dB / 32 taps) as a grey reference. A schematic of the trade-off, not a measurement.
 */
final class ResponseView extends View {
    static final int POINTS = 240;
    static final double FS_IN = 44100.0, F_MAX = 48000.0, FLOOR = -220.0;
    static final int LP = 4; // prototype oversampling for the numeric model

    final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), cur = new Paint(Paint.ANTI_ALIAS_FLAG),
            ref = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG),
            fill = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    double[] curDb = new double[POINTS], refDb = new double[POINTS];
    int sb = 179, hl = 408, tbw = 99;
    boolean active = true;
    String info = "";

    ResponseView(Context c) {
        super(c);
        grid.setColor(0x22FFFFFF);
        grid.setStrokeWidth(dp(1));
        cur.setColor(0xFF1BC4CE);
        cur.setStyle(Paint.Style.STROKE);
        cur.setStrokeWidth(dp(2));
        cur.setStrokeJoin(Paint.Join.ROUND);
        ref.setColor(0xFF8F8F8F);
        ref.setStyle(Paint.Style.STROKE);
        ref.setStrokeWidth(dp(1.5f));
        ref.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(4)}, 0));
        fill.setStyle(Paint.Style.FILL);
        mark.setColor(0xFFFF9A1F);
        mark.setStrokeWidth(dp(1));
        mark.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(3)}, 0));
        txt.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, getResources().getDisplayMetrics()));
        txt.setColor(0xFF8F8F8F);
        compute(90, 32, 100, refDb);
        setParams(179, 408, 99, true);
    }

    float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    void setParams(int sbDb, int halfLen, int tbwPct, boolean on) {
        sb = sbDb; hl = halfLen; tbw = tbwPct; active = on;
        compute(sb, hl, tbw, curDb);
        double dn = (sb - 7.95) / (2 * 14.36 * hl);
        info = on ? String.format(java.util.Locale.US, "-%d dB stopband  |  transition ~%.0f Hz  |  %d taps/phase",
                sb, dn * FS_IN, 2 * hl) : "Android default resampler (profile Off)";
        invalidate();
    }

    static double i0(double x) {
        double sum = 1, term = 1, q = x * x / 4;
        for (int k = 1; k < 200; k++) {
            term *= q / ((double) k * k);
            sum += term;
            if (term < 1e-14 * sum) break;
        }
        return sum;
    }

    static double beta(double a) {
        if (a > 50) return 0.1102 * (a - 8.7);
        if (a > 21) return 0.5842 * Math.pow(a - 21, 0.4) + 0.07886 * (a - 21);
        return 0;
    }

    static void compute(int sbDb, int halfLen, int tbwPct, double[] out) {
        int n = LP * halfLen;
        double b = beta(sbDb), ib = i0(b);
        double dn = (sbDb - 7.95) / (2 * 14.36 * halfLen);          // normalized to FS_IN
        double fcr = 0.5 - dn * (tbwPct / 100.0) / 2;               // mid transition, FS_IN units
        double fp = fcr / LP;                                       // normalized to prototype rate
        double[] h = new double[n + 1];
        double dc = 0;
        for (int i = 0; i <= n; i++) {
            double r = (double) i / n;
            double w = i0(b * Math.sqrt(Math.max(0, 1 - r * r))) / ib;
            double s = i == 0 ? 2 * fp : Math.sin(2 * Math.PI * fp * i) / (Math.PI * i);
            h[i] = s * w;
            dc += (i == 0 ? h[i] : 2 * h[i]);
        }
        for (int k = 0; k < out.length; k++) {
            double fx = (F_MAX / FS_IN) * k / (out.length - 1);     // FS_IN units
            double wv = 2 * Math.PI * fx / LP;
            double c1 = Math.cos(wv), cPrev = 1, cCur = c1, sum = h[0];
            if (n >= 1) sum += 2 * h[1] * c1;
            for (int i = 2; i <= n; i++) {
                double cNext = 2 * c1 * cCur - cPrev;
                sum += 2 * h[i] * cNext;
                cPrev = cCur; cCur = cNext;
            }
            double mag = Math.abs(sum / dc);
            out[k] = Math.max(FLOOR, 20 * Math.log10(Math.max(mag, 1e-11)));
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(190));
    }

    @Override
    protected void onDraw(Canvas c) {
        float l = dp(34), t = dp(8), r = getWidth() - dp(6), b = getHeight() - dp(34);
        fill.setColor(0xFF181818);
        c.drawRect(l, t, r, b, fill);
        // grid + y labels
        for (int db = 0; db >= -200; db -= 50) {
            float y = t + (b - t) * (float) (-db / 220.0);
            c.drawLine(l, y, r, y, grid);
            c.drawText(db + "", dp(2), y + dp(3), txt);
        }
        for (int k = 0; k <= 48; k += 8) {
            float x = l + (r - l) * (float) (k * 1000.0 / F_MAX);
            c.drawLine(x, t, x, b, grid);
            c.drawText(k + "k", x - dp(6), b + dp(14), txt);
        }
        float xn = l + (r - l) * (float) (FS_IN / 2 / F_MAX);
        c.drawLine(xn, t, xn, b, mark);
        c.drawText("22.05k", xn + dp(3), t + dp(10), txt);
        // reference curve then current
        c.drawPath(path(refDb, l, t, r, b), ref);
        if (active) {
            Path p = path(curDb, l, t, r, b);
            Path area = new Path(p);
            area.lineTo(r, b);
            area.lineTo(l, b);
            area.close();
            fill.setColor(0x401BC4CE);
            c.drawPath(area, fill);
            c.drawPath(p, cur);
        }
        txt.setColor(active ? 0xFF1BC4CE : 0xFF8F8F8F);
        c.drawText(info, l, getHeight() - dp(4), txt);
        txt.setColor(0xFF8F8F8F);
        c.drawText("dashed: AOSP default (90 dB, 32 taps)", r - dp(190), t + dp(22), txt);
    }

    Path path(double[] d, float l, float t, float r, float b) {
        Path p = new Path();
        for (int i = 0; i < d.length; i++) {
            float x = l + (r - l) * i / (d.length - 1);
            float y = t + (b - t) * (float) (-d[i] / 220.0);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        return p;
    }
}
