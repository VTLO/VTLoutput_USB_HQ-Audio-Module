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
        assertEquals("v1.1", a.bundledVer);
        // impedance card
        a.zoSlider.set(10); a.zhSlider.set(32); a.sensSlider.set(100); a.vSlider.set(10); a.impType = 0; a.impChanged();
        assertEquals("-0.27 dB", a.tileLoss.getText().toString().replace(',', '.'));
        a.zoSlider.set(100); a.impType = 2; a.impChanged();
        assertTrue(a.impVerdict.getText().toString().startsWith("Poor match"));
        assertEquals(100, a.prefs.getInt("imp_zo", -1));
        assertEquals(2, a.prefs.getInt("imp_type", -1));
        a.zoSlider.set(1); a.zhSlider.set(300); a.impType = 1; a.impChanged();
        assertTrue(a.impVerdict.getText().toString().startsWith("Good match"));
        a.zoSlider.set(5); a.restoreImpedance();
        assertEquals(1, a.zoSlider.get());
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
    public void eqMaths() {
        Eq.Filter pk = new Eq.Filter(Eq.PK, 1000, 6, 1.41);
        assertEquals(6.0, Eq.magDb(pk, 1000), 0.05);
        assertEquals(0.0, Eq.magDb(pk, 20), 0.2);
        assertEquals(6.0, Eq.magDb(new Eq.Filter(Eq.LS, 100, 6, 0.707), 20), 0.3);
        assertEquals(0.0, Eq.magDb(new Eq.Filter(Eq.LS, 100, 6, 0.707), 10000), 0.1);
        assertEquals(6.0, Eq.magDb(new Eq.Filter(Eq.HS, 8000, 6, 0.707), 20000), 0.5);
        Eq.Plan flat = Eq.plan(null, new int[10], 0, true, 128);
        assertEquals(0.0, flat.inputGainDb, 1e-9);
        assertEquals(0.0, flat.gainDb[64], 1e-9);
        int[] man = new int[10]; man[5] = 60; // +6 dB at 1 kHz
        Eq.Plan p = Eq.plan(null, man, 0, true, 128);
        assertEquals(6.0, p.peakDb, 0.1);
        assertEquals(-p.peakDb, p.inputGainDb, 1e-6);
        assertEquals(128, p.cutoff.length);
        assertEquals(24000.0, p.cutoff[127], 1e-9);
        for (int i = 1; i < 128; i++) assertTrue(p.cutoff[i] > p.cutoff[i - 1]);
        Eq.Plan nohead = Eq.plan(null, man, 0, false, 32);
        assertEquals(0.0, nohead.inputGainDb, 1e-9);
    }

    @Test
    public void autoEqParsing() {
        String prof = "Preamp: -6.4 dB\nFilter 1: ON PK Fc 21 Hz Gain 5.8 dB Q 0.40\nFilter 2: ON LSC Fc 105 Hz Gain 2.4 dB Q 0.70\nFilter 3: OFF PK Fc 500 Hz Gain 1.0 dB Q 1.0\nFilter 4: ON HSC Fc 10000 Hz Gain -3.5 dB Q 0.70\n";
        AutoEq.Profile pr = AutoEq.Profile.parse(prof);
        assertEquals(-6.4, pr.preamp, 1e-9);
        assertEquals(3, pr.filters.size());
        assertEquals(Eq.LS, pr.filters.get(1).type);
        assertEquals(Eq.HS, pr.filters.get(2).type);
        java.util.List<AutoEq.Entry> idx = AutoEq.parseIndex("- [Apple AirPods Pro](./crinacle/711 in-ear/Apple%20AirPods%20Pro) by crinacle on 711\n- [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990 on GRAS\n".replace("711 in-ear", "711%20in-ear"));
        assertEquals(2, idx.size());
        assertEquals(AutoEq.RAW + "/oratory1990/over-ear/Sennheiser%20HD%20600/Sennheiser%20HD%20600%20ParametricEQ.txt", idx.get(1).url());
        assertEquals("Sennheiser HD 600", AutoEq.search(idx, "hd 600", 5).get(0).name);
        assertTrue(AutoEq.search(idx, "zzz", 5).isEmpty());
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
        assertTrue(a.gainBox.getChildCount() >= 3);
        a.renderGain(new java.util.ArrayList<>(), "no cards");
        assertEquals(0, a.gainBox.getChildCount());
    }

    @Test
    public void eqUi() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        a.eqProfile = AutoEq.Profile.parse("Preamp: -4 dB\nFilter 1: ON PK Fc 100 Hz Gain 4 dB Q 1.0\n");
        a.eqNameStr = "Test";
        a.eqCurve.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY), 0);
        a.eqCurve.layout(0, 0, 1000, a.eqCurve.getMeasuredHeight());
        a.eqChanged(true);
        // draw (builds the paths) and drag a band
        a.eqCurve.draw(new android.graphics.Canvas(android.graphics.Bitmap.createBitmap(1000, a.eqCurve.getMeasuredHeight(), android.graphics.Bitmap.Config.ARGB_8888)));
        float x = a.eqCurve.xOf(Eq.MANUAL_FC[5]), y = a.eqCurve.yOf(6.0);
        long t = android.os.SystemClock.uptimeMillis();
        a.eqCurve.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0));
        a.eqCurve.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 10, android.view.MotionEvent.ACTION_UP, x, y, 0));
        assertEquals(60, a.eqMan[5], 6);
        assertTrue(a.prefs.getString("eq_manual", "").contains("6"));
        assertTrue(a.eqPlanText.getText().toString().contains("input gain"));
        // preamp + service start must not crash even without the real effect
        a.preSlider.set(-30); a.eqChanged(true);
        assertEquals(-30, a.prefs.getInt("eq_pre", 0));
        a.eqSwitch.setChecked(true);
        ShadowLooper.idleMainLooper();
        a.eqSwitch.setChecked(false);
        // service plan from prefs
        Eq.Plan p = EqService.planFromPrefs(a.prefs, 128);
        assertEquals(128, p.bands);
        assertTrue(p.inputGainDb < 0);
    }
}
