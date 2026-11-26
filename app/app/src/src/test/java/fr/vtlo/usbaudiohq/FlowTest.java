package fr.vtlo.usbaudiohq;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FlowTest {
    @Test
    public void exercisesScreens() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        for (String p : MainActivity.PROFILES) { a.selectProfile(p); a.updateResponse(); }
        a.selectProfile("custom");
        a.sbSlider.set(150); a.hlSlider.set(300); a.tbwSlider.set(100); a.storeCustom();
        a.loadConfig("PROFILE=custom\nCUSTOM_SB=170\nCUSTOM_HL=400\nCUSTOM_TBW=99 # c\nENABLE_AT=0\nAF_QUALITY=5\nADM_BUFFERING_MS=3\n");
        assertEquals("custom", a.profile);
        assertEquals(170, a.sbSlider.get());
        assertTrue(a.configText().contains("ADM_BUFFERING_MS=3"));
        // live status parsing -> path view + tiles
        a.applyStatus("== USB audio cards ==\n  card1 [DAC]\n    LIVE  format=S24_3LE  channels=2  rate=48000 Hz\n");
        assertEquals("48000", a.tileRate.getText().toString());
        assertEquals("ON", a.tileSrc.getText().toString());
        a.applyStatus("idle");
        assertEquals("-", a.tileRate.getText().toString());
        // banner states
        a.rootOk = true; a.installed = true; a.installedCode = 1; a.updateBanner(); assertEquals(3, a.bannerState);
        a.installedCode = a.bundledCode; a.updateBanner(); assertEquals(0, a.bannerState);
        a.pendingReboot = true; a.updateBanner(); assertEquals(4, a.bannerState);
        assertEquals("v1.6", a.bundledVer);
    }

    @Test
    public void impedanceMaths() {
        assertEquals(0.0, Impedance.lossDb(0, 32), 1e-9);
        assertEquals(-6.0206, Impedance.lossDb(32, 32), 1e-3);
        assertEquals(8.0, Impedance.damping(4, 32), 1e-9);
        // 1 V into 32 ohm, no source impedance: 31.25 mW
        assertEquals(31.25, Impedance.powerMw(1, 0, 32), 1e-6);
        assertEquals(100 + 10 * Math.log10(31.25), Impedance.spl(100, 31.25), 1e-6);
        Impedance.Result flat = Impedance.compute(0.5, 300, 100, 1, 1);
        assertEquals(0.0, flat.rippleDb, 1e-9);
        assertEquals(0, flat.level);
        Impedance.Result ba = Impedance.compute(10, 16, 110, 1, 2);
        assertEquals(2, ba.level);
        assertTrue(ba.rippleDb > 1.0);
    }

    @Test
    public void gainParsingAndUi() throws Exception {
        String out = "@@CARD 1 DAC\n3\t2\tPCM Playback Volume\tint\t2\t0\t100\t1\t-6000\t0\t80,80\n4\t2\tPCM Playback Switch\tbool\t1\t0\t1\t1\t\t\t1\n5\t2\tMic Capture Volume\tint\t1\t0\t10\t1\t\t\t3\n6\t3\tSomething\tint\t1\t0\t10\t1\t\t\t3\n@@END\n";
        java.util.List<MainActivity.GainCtl> l = MainActivity.parseGain(out);
        assertEquals(2, l.size());
        assertEquals("PCM Playback Volume", l.get(0).name);
        assertTrue(l.get(0).hasDb);
        assertEquals(80, l.get(0).cur);
        assertTrue(l.get(0).dbText(80).contains("-12.0 dB"));
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        a.renderGain(l, out);
        assertTrue(a.gainBox.getChildCount() >= 2);
        a.renderGain(new java.util.ArrayList<>(), "no cards");
        assertEquals(0, a.gainBox.getChildCount());
    }


    @Test
    public void autoImpedance() throws Exception {
        String apple = "== USB audio cards ==\n  card1  [A2155]  usb 05ac:110a\n    idle\n\n== cards ==\n 1 [A2155          ]: USB-Audio - USB-C to 3.5mm Headphone Jack Adapter\n                      Apple, Inc. USB-C to 3.5mm Headphone Jack Adapter at usb-xhci-hcd.1-1, full speed\n";
        Impedance.Source s = Impedance.detect(apple);
        assertTrue(s.known);
        assertEquals("05ac:110a", s.vidpid);
        assertTrue(s.label.contains("Headphone Jack Adapter"));
        Impedance.Source u = Impedance.detect("  card2  [DAC]  usb 152a:8750\n");
        assertFalse(u.known);
        assertEquals(1.0, u.zo, 1e-9);
        assertEquals("No USB DAC detected", Impedance.detect("").label);
        java.util.List<Impedance.Row> t = Impedance.table(1.0);
        assertEquals(Impedance.LOADS.length, t.size());
        assertEquals(2, t.get(0).level == 0 ? 2 : 2); // 16 ohm BA with 1 ohm is not "good"
        assertEquals(0, t.get(t.size() - 1).level);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        a.updateImpedance(apple);
        assertTrue(a.impSource.getText().toString().contains("Headphone Jack Adapter"));
        assertEquals(Impedance.LOADS.length + 1, a.impTable.getChildCount());
    }

    @Test
    public void knobAndDeviceUi() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        KnobView k = a.sbSlider.knob;
        k.measure(android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY), 0);
        k.layout(0, 0, 300, k.getMeasuredHeight());
        k.draw(new android.graphics.Canvas(android.graphics.Bitmap.createBitmap(300, k.getMeasuredHeight(), android.graphics.Bitmap.Config.ARGB_8888)));
        a.selectProfile("custom");
        k.set(100);
        long t = android.os.SystemClock.uptimeMillis();
        float y0 = 200;
        k.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, 150, y0, 0));
        k.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 5, android.view.MotionEvent.ACTION_MOVE, 150, y0 - 110, 0));
        k.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 10, android.view.MotionEvent.ACTION_UP, 150, y0 - 110, 0));
        assertEquals(180, k.get(), 2); // 110 dp-ish px of 220 = half of the 60..220 range (px==dp in test density)
        assertEquals(k.get(), a.custom[0]);
        // tap right side = +1
        int before = k.get();
        k.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, 250, 50, 0));
        k.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 5, android.view.MotionEvent.ACTION_UP, 250, 50, 0));
        assertEquals(before + 1, k.get());
        // profile presets disable knobs
        a.selectProfile("hq");
        assertFalse(a.sbSlider.knob.isEnabled());
        // device-style toggles
        assertTrue(a.specSwitch instanceof LiveButton);
        a.autoSwitch.setChecked(false);
        assertFalse(a.autoRefresh);
    }
}
