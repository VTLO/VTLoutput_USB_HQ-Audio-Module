package fr.vtlo.usbaudiohq;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LaunchTest {
    static int count(View v) {
        if (!(v instanceof ViewGroup)) return 1;
        int n = 1;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) n += count(g.getChildAt(i));
        return n;
    }

    @Test
    public void launchesAndBuildsUi() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        ShadowLooper.idleMainLooper();
        Thread.sleep(1500);
        ShadowLooper.idleMainLooper();
        View root = a.findViewById(android.R.id.content);
        assertNotNull(root);
        int n = count(root);
        System.out.println("VIEW_COUNT=" + n + " bannerState=" + a.bannerState + " profile=" + a.profile);
        assertTrue("UI should have many views", n > 40);
        c.pause().resume().pause().stop().destroy();
    }
}
