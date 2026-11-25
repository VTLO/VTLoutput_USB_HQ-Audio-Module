package fr.vtlo.usbaudiohq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Combined EQ curve (AutoEq + 10 manual bands). Drag the dots to set the manual bands (+-12 dB). */
final class EqCurveView extends View {
    interface Listener { void onBand(int index, int tenthsDb, boolean done); }

    static final int MAXG = 120; // tenths of dB
    final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), cur = new Paint(Paint.ANTI_ALIAS_FLAG),
            auto = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
            txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    List<Eq.Filter> autoF = new ArrayList<>();
    int[] manual = new int[Eq.MANUAL_FC.length];
    int active = -1;
    Listener listener;
    float l, t, r, b;

    EqCurveView(Context c) {
        super(c);
        grid.setColor(0x22FFFFFF);
        grid.setStrokeWidth(dp(1));
        cur.setColor(0xFF7CF0C8);
        cur.setStyle(Paint.Style.STROKE);
        cur.setStrokeWidth(dp(2));
        cur.setStrokeJoin(Paint.Join.ROUND);
        auto.setColor(0xFF8B93A1);
        auto.setStyle(Paint.Style.STROKE);
        auto.setStrokeWidth(dp(1.5f));
        auto.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(4)}, 0));
        dot.setColor(0xFFFFB454);
        txt.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, getResources().getDisplayMetrics()));
        txt.setColor(0xFF8B93A1);
    }

    float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    void setData(AutoEq.Profile prof, int[] man) {
        autoF = prof == null ? new ArrayList<>() : prof.filters;
        System.arraycopy(man, 0, manual, 0, Math.min(man.length, manual.length));
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(resolveSize(0, w), (int) dp(230)); }

    float xOf(double f) { return (float) (l + Math.log(f / Eq.F_MIN) / Math.log(Eq.F_MAX / Eq.F_MIN) * (r - l)); }
    float yOf(double db) { return (float) (t + (15 - db) / 30.0 * (b - t)); }

    @Override protected void onDraw(Canvas c) {
        l = dp(30); t = dp(8); r = getWidth() - dp(8); b = getHeight() - dp(22);
        for (int db = -15; db <= 15; db += 5) {
            c.drawLine(l, yOf(db), r, yOf(db), grid);
            c.drawText(db + "", dp(4), yOf(db) + dp(3), txt);
        }
        double[] ticks = {100, 1000, 10000};
        String[] tl = {"100", "1k", "10k"};
        for (int i = 0; i < 3; i++) {
            c.drawLine(xOf(ticks[i]), t, xOf(ticks[i]), b, grid);
            c.drawText(tl[i], xOf(ticks[i]) - dp(8), b + dp(14), txt);
        }
        List<Eq.Filter> man = Eq.manualFilters(manual);
        List<Eq.Filter> all = new ArrayList<>(autoF);
        all.addAll(man);
        if (!autoF.isEmpty()) c.drawPath(path(autoF), auto);
        c.drawPath(path(all), cur);
        for (int i = 0; i < Eq.MANUAL_FC.length; i++) {
            float x = xOf(Eq.MANUAL_FC[i]), y = yOf(manual[i] / 10.0);
            c.drawCircle(x, y, dp(i == active ? 9 : 6), dot);
        }
        if (active >= 0)
            c.drawText(String.format(Locale.US, "%.0f Hz  %+.1f dB", Eq.MANUAL_FC[active], manual[active] / 10.0), l + dp(6), t + dp(12), txt);
    }

    Path path(List<Eq.Filter> fs) {
        Path p = new Path();
        for (int i = 0; i <= 160; i++) {
            double f = Eq.F_MIN * Math.pow(Eq.F_MAX / Eq.F_MIN, i / 160.0);
            double db = Math.max(-15, Math.min(15, Eq.sumDb(fs, f)));
            float x = xOf(f), y = yOf(db);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        return p;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            float best = Float.MAX_VALUE;
            active = -1;
            for (int i = 0; i < Eq.MANUAL_FC.length; i++) {
                float d = Math.abs(xOf(Eq.MANUAL_FC[i]) - e.getX());
                if (d < best) { best = d; active = i; }
            }
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (active >= 0 && (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP)) {
            double db = 15 - (e.getY() - t) / (b - t) * 30.0;
            int tenths = (int) Math.round(Math.max(-MAXG, Math.min(MAXG, db * 10)) / 5.0) * 5;
            manual[active] = tenths;
            if (listener != null) listener.onBand(active, tenths, a == MotionEvent.ACTION_UP);
            invalidate();
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { active = -1; invalidate(); }
        return true;
    }
}
