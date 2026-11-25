package fr.vtlo.usbaudiohq;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.os.IBinder;
import java.util.HashMap;
import java.util.Map;

/**
 * Applies the EQ plan with Android's DynamicsProcessing effect: a floating-point, frequency-domain
 * multiband pre-EQ (128 bands) plus input gain for headroom. Limiter, MBC and post-EQ stay off and
 * the stream format / sample rate is never touched, so USB hi-res output is preserved.
 * Attaches to the global mix (session 0) and to every session an app announces.
 */
public class EqService extends Service {
    static final String ACTION_APPLY = "fr.vtlo.usbaudiohq.EQ_APPLY";
    static final String ACTION_STOP = "fr.vtlo.usbaudiohq.EQ_STOP";
    static final int[] BAND_TRIES = {128, 64, 32};
    static volatile String status = "Equalizer off";
    static volatile boolean running;

    final Map<Integer, DynamicsProcessing> fx = new HashMap<>();
    int bands = BAND_TRIES[0];
    String note = "";
    BroadcastReceiver rx;

    static Eq.Plan planFromPrefs(SharedPreferences p, int bands) {
        AutoEq.Profile prof = null;
        String t = p.getString("eq_text", "");
        if (!t.isEmpty()) prof = AutoEq.Profile.parse(t);
        int[] man = new int[Eq.MANUAL_FC.length];
        String[] parts = p.getString("eq_manual", "").split(",");
        for (int i = 0; i < man.length && i < parts.length; i++) {
            try { man[i] = Integer.parseInt(parts[i].trim()); } catch (NumberFormatException ignored) { }
        }
        return Eq.plan(prof, man, p.getInt("eq_pre", 0) / 10.0, p.getBoolean("eq_head", true), bands);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        running = true;
        if (rx == null) register();
        apply();
        return START_STICKY;
    }

    void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("eq", "Equalizer", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "eq")
                .setContentTitle("VTLoutput_HQ equalizer active")
                .setContentText("Float multiband EQ - no resampling")
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(42, n);
    }

    void register() {
        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                int sid = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
                if (sid <= 0) return;
                if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) { attach(sid); publish(); }
                else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) { detach(sid); publish(); }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(rx, f);
    }

    DynamicsProcessing.Eq buildEq(Eq.Plan p) {
        DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, p.bands);
        for (int i = 0; i < p.bands; i++) eq.setBand(i, new DynamicsProcessing.EqBand(true, (float) p.cutoff[i], (float) p.gainDb[i]));
        return eq;
    }

    void apply() {
        SharedPreferences prefs = getSharedPreferences("vtlo", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT < 28) { status = "DynamicsProcessing needs Android 9+"; return; }
        if (fx.isEmpty()) {
            attach(0);
        } else {
            for (DynamicsProcessing dp : fx.values()) {
                try {
                    Eq.Plan p = planFromPrefs(prefs, bands);
                    dp.setPreEqAllChannelsTo(buildEq(p));
                    dp.setInputGainAllChannelsTo((float) p.inputGainDb);
                } catch (Throwable t) { note = "update failed: " + t.getMessage(); }
            }
        }
        publish();
    }

    void attach(int session) {
        if (fx.containsKey(session)) return;
        SharedPreferences prefs = getSharedPreferences("vtlo", MODE_PRIVATE);
        for (int b : BAND_TRIES) {
            try {
                Eq.Plan p = planFromPrefs(prefs, b);
                DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2, true, b, false, 0, false, 0, false)
                        .setPreEqAllChannelsTo(buildEq(p)).build();
                DynamicsProcessing dp = new DynamicsProcessing(0, session, cfg);
                dp.setInputGainAllChannelsTo((float) p.inputGainDb);
                dp.setEnabled(true);
                if (!dp.hasControl()) { dp.release(); note = "no control of session " + session; return; }
                bands = b;
                fx.put(session, dp);
                return;
            } catch (Throwable t) {
                note = (session == 0 ? "global mix refused: " : "session " + session + ": ") + t.getClass().getSimpleName();
            }
        }
    }

    void detach(int session) {
        DynamicsProcessing dp = fx.remove(session);
        if (dp != null) try { dp.release(); } catch (Throwable ignored) { }
    }

    void publish() {
        SharedPreferences prefs = getSharedPreferences("vtlo", MODE_PRIVATE);
        Eq.Plan p = planFromPrefs(prefs, bands);
        StringBuilder sb = new StringBuilder();
        if (fx.isEmpty()) sb.append("NOT attached. ").append(note).append("\nOpen a player that announces its audio session, or try a different app.");
        else {
            sb.append("Attached: ").append(fx.containsKey(0) ? "global mix + " : "").append(fx.size() - (fx.containsKey(0) ? 1 : 0)).append(" app session(s)");
            sb.append("\n").append(bands).append(" float bands, input gain ")
                    .append(String.format(java.util.Locale.US, "%.1f dB (headroom %.1f dB)", p.inputGainDb, p.headroomDb));
            if (!note.isEmpty()) sb.append("\n").append(note);
        }
        status = sb.toString();
    }

    @Override public void onDestroy() {
        for (Integer s : fx.keySet().toArray(new Integer[0])) detach(s);
        if (rx != null) { try { unregisterReceiver(rx); } catch (Exception ignored) { } rx = null; }
        running = false;
        status = "Equalizer off";
        super.onDestroy();
    }
}
