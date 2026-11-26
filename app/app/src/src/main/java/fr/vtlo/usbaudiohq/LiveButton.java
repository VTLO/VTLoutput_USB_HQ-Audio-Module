package fr.vtlo.usbaudiohq;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.CompoundButton;

/** Orange-when-on toggle: BOX (labelled button) or DOT (device power button). */
final class LiveButton extends CompoundButton {
    static final int BOX = 0, DOT = 1;
    static final int ORANGE = 0xFFFF9A1F, OFF = 0xFF3A3A3A;
    int style;

    LiveButton(Context c, String label, int style) {
        super(c);
        this.style = style;
        setButtonDrawable(null);
        setText(label);
        setGravity(Gravity.CENTER);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        int p = style == BOX ? dp(10) : 0;
        setPadding(dp(12), p, dp(12), p);
        restyle();
    }

    int dp(int v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    @Override public void setChecked(boolean checked) {
        super.setChecked(checked);
        restyle();
    }

    void restyle() {
        GradientDrawable g = new GradientDrawable();
        boolean on = isChecked();
        if (style == DOT) {
            g.setShape(GradientDrawable.OVAL);
            g.setColor(on ? ORANGE : 0xFF555555);
            g.setStroke(dp(2), 0xFF1A1A1A);
        } else {
            g.setCornerRadius(dp(4));
            g.setColor(on ? ORANGE : OFF);
            setTextColor(on ? 0xFF1A1100 : 0xFFB5B5B5);
        }
        setBackground(g);
    }
}
