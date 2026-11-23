package fr.vtlo.usbaudiohq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

/** App -> Android mixer (resampler) -> USB DAC, animated while the DAC stream is live. */
final class PathView extends View {
    boolean live;
    int rate, channels;
    String format = "", profileLabel = "", mixerSub = "";
    long t0 = System.currentTimeMillis();
    final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            title = new Paint(Paint.ANTI_ALIAS_FLAG), sub = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF rf = new RectF();

    PathView(Context c) {
        super(c);
        box.setColor(0xFF222730);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.5f));
        title.setColor(0xFFE8EAED);
        title.setFakeBoldText(true);
        title.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
        sub.setColor(0xFF8B93A1);
        sub.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, getResources().getDisplayMetrics()));
        arrow.setStrokeWidth(dp(2));
    }

    float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    void set(boolean live, int rate, String format, int channels, String profileLabel) {
        this.live = live; this.rate = rate; this.format = format; this.channels = channels;
        this.profileLabel = profileLabel;
        if (!live) mixerSub = "idle";
        else if (rate == 44100) mixerSub = "native 44.1 kHz";
        else mixerSub = "SRC to " + trim(rate / 1000f) + " kHz";
        invalidate();
    }

    static String trim(float f) {
        return f == (int) f ? String.valueOf((int) f) : String.format(java.util.Locale.US, "%.1f", f);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(92));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float bw = (w - dp(56)) / 3f, bh = dp(62), y = (h - bh) / 2f, gap = dp(28);
        int accent = live ? 0xFF7CF0C8 : 0xFF3A414C;
        String[] t = {"APP", "ANDROID MIXER", "USB DAC"};
        String[] s = {"Deezer, Termux...", mixerSub + (live && rate != 44100 && !profileLabel.isEmpty() ? " | " + profileLabel : ""),
                live ? (rate + " Hz | " + format + " | " + channels + "ch") : "no stream"};
        for (int i = 0; i < 3; i++) {
            float x = i * (bw + gap);
            rf.set(x, y, x + bw, y + bh);
            c.drawRoundRect(rf, dp(10), dp(10), box);
            stroke.setColor(i == 1 && live && rate != 44100 ? 0xFFC45A36 : accent);
            c.drawRoundRect(rf, dp(10), dp(10), stroke);
            c.drawText(t[i], x + dp(8), y + dp(20), title);
            drawFit(c, s[i], x + dp(8), y + dp(38), bw - dp(12));
        }
        for (int i = 0; i < 2; i++) {
            float x1 = i * (bw + gap) + bw + dp(3), x2 = x1 + gap - dp(6), cy = h / 2f;
            arrow.setColor(accent);
            c.drawLine(x1, cy, x2, cy, arrow);
            c.drawLine(x2 - dp(5), cy - dp(4), x2, cy, arrow);
            c.drawLine(x2 - dp(5), cy + dp(4), x2, cy, arrow);
            if (live) {
                float ph = ((System.currentTimeMillis() - t0) % 900) / 900f;
                dot.setColor(0xFFC45A36);
                c.drawCircle(x1 + (x2 - x1) * ph, cy, dp(2.5f), dot);
            }
        }
        if (live) postInvalidateDelayed(40);
    }

    void drawFit(Canvas c, String s, float x, float y, float maxW) {
        Paint p = new Paint(sub);
        while (p.measureText(s) > maxW && p.getTextSize() > dp(7)) p.setTextSize(p.getTextSize() - 1);
        c.drawText(s, x, y, p);
        // second line is intentionally not used: keep the box compact
    }
}
