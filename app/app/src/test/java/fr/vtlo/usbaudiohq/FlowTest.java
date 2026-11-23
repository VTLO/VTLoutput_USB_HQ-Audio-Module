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
}
