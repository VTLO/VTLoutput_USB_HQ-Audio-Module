package fr.vtlo.usbaudiohq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.View;

/** 48-band log spectrum with peak caps and a dBFS peak meter. Fed by Android's Visualizer. */
final class SpectrumView extends View {
    static final int BANDS = 48;
    final float[] bars = new float[BANDS], caps = new float[BANDS];
    float peakDb = -90f, peakHold = -90f;
    String message = "Spectrum off";
    final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), cap = new Paint(Paint.ANTI_ALIAS_FLAG),
            txt = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(), meterBg = new Paint(Paint.ANTI_ALIAS_FLAG),
            meter = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF rf = new RectF();

    SpectrumView(Context c) {
        super(c);
        cap.setColor(0xFFC45A36);
        txt.setColor(0xFF8B93A1);
        txt.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, getResources().getDisplayMetrics()));
        grid.setColor(0x22FFFFFF);
        meterBg.setColor(0xFF222730);
    }

    float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    void setMessage(String m) {
        message = m;
        for (int i = 0; i < BANDS; i++) { bars[i] = 0; caps[i] = 0; }
        peakDb = -90f;
        invalidate();
    }

    /** levels 0..1 per band, peak in dBFS. */
    void push(float[] levels, float peak) {
        message = null;
        for (int i = 0; i < BANDS; i++) {
            bars[i] = Math.max(levels[i], bars[i] * 0.82f);
            caps[i] = Math.max(bars[i], caps[i] - 0.012f);
        }
        peakDb = Math.max(peak, peakDb - 2.5f);
        peakHold = Math.max(peak, peakHold - 0.4f);
        postInvalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(170));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        bar.setShader(new LinearGradient(0, dp(8), 0, h - dp(40), 0xFFC45A36, 0xFF7CF0C8, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), top = dp(6), bot = getHeight() - dp(44);
        for (int i = 1; i < 4; i++) {
            float y = top + (bot - top) * i / 4f;
            c.drawLine(0, y, w, y, grid);
        }
        float gap = dp(2), bw = (w - gap * (BANDS - 1)) / BANDS;
        for (int i = 0; i < BANDS; i++) {
            float x = i * (bw + gap);
            float h = Math.max(dp(2), (bot - top) * Math.min(1f, bars[i]));
            rf.set(x, bot - h, x + bw, bot);
            c.drawRoundRect(rf, dp(2), dp(2), bar);
            float cy = bot - (bot - top) * Math.min(1f, caps[i]) - dp(3);
            rf.set(x, cy, x + bw, cy + dp(2));
            c.drawRect(rf, cap);
        }
        c.drawText("30", 0, bot + dp(12), txt);
        c.drawText("1k", w * 0.40f, bot + dp(12), txt);
        c.drawText("20k", w - dp(22), bot + dp(12), txt);
        // peak meter -60..0 dBFS
        float my = getHeight() - dp(18), mh = dp(8);
        rf.set(0, my, w, my + mh);
        c.drawRoundRect(rf, dp(4), dp(4), meterBg);
        float frac = Math.max(0f, Math.min(1f, (peakDb + 60f) / 60f));
        meter.setColor(peakDb > -3 ? 0xFFFF6B6B : (peakDb > -12 ? 0xFFFFB454 : 0xFF7CF0C8));
        rf.set(0, my, w * frac, my + mh);
        c.drawRoundRect(rf, dp(4), dp(4), meter);
        float hx = w * Math.max(0f, Math.min(1f, (peakHold + 60f) / 60f));
        c.drawRect(hx - dp(1), my - dp(2), hx + dp(1), my + mh + dp(2), cap);
        c.drawText(String.format(java.util.Locale.US, "peak %.1f dBFS", Math.max(-90f, peakHold)), 0, getHeight() - dp(2), txt);
        if (message != null) {
            Paint p = new Paint(txt);
            p.setTextSize(dp(13));
            c.drawText(message, (w - p.measureText(message)) / 2f, (top + bot) / 2f, p);
        }
    }
}
