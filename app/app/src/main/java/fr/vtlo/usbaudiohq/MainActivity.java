package fr.vtlo.usbaudiohq;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    static final int BG = 0xFF0D0F12, CARD = 0xFF171A1F, CARD2 = 0xFF222730, ACC = 0xFF7CF0C8,
            TXT = 0xFFE8EAED, SUB = 0xFF8B93A1, WARN = 0xFFFFB454, BAD = 0xFFFF6B6B;

    static final String[] PROFILES = {"hq", "ultra", "custom", "off"};
    static final String[] PROFILE_LABELS = {"HQ", "Ultra", "Custom", "Off"};
    static final String[] PROFILE_HINTS = {
            "Stopband 179 dB, half-length 408, tbw 99. Recommended.",
            "Stopband 194 dB, half-length 520, tbw 98. Heaviest filter, more CPU.",
            "Your own values. The module ignores out-of-range values (stopband 60-220, half-length 16-800, tbw 50-150).",
            "Leave Android's default resampler untouched (applies at next reboot)."};
    static final int[][] PRESETS = {{179, 408, 99}, {194, 520, 98}};
    static final int[] ENABLE_RATES = {0, 44100, 48000, 96000};
    static final String[] ENABLE_LABELS = {"Always (0 Hz)", "44100 Hz and up", "48000 Hz and up", "96000 Hz and up"};

    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    final ExecutorService net = Executors.newSingleThreadExecutor();
    List<AutoEq.Entry> eqIndex;
    AutoEq.Profile eqProfile;
    String eqNameStr = "";
    final int[] eqMan = new int[Eq.MANUAL_FC.length];
    EqCurveView eqCurve;
    Switch eqSwitch, headSwitch;
    Slider preSlider;
    TextView eqStatus, eqInfo, eqPlanText, gainStatus;
    EditText eqSearch;
    LinearLayout eqResults, gainBox;
    boolean eqRestoring;
    final List<GainCtl> gainCtls = new ArrayList<>();
    Slider zoSlider, zhSlider, sensSlider, vSlider;
    TextView[] typeChips = new TextView[3];
    int impType = 0;
    ImpedanceView impView;
    TextView impVerdict, tileLoss, tileDf, tileMw, tileSpl;
    LinearLayout bannerCard;
    TextView bannerText, bannerBtn, modBtn;
    String bundledVer = "?", installedVer = "";
    int bundledCode = 0, installedCode = 0, bannerState = 0;
    boolean pendingReboot = false;

    boolean rootOk, installed, disabled, loading = true, autoRefresh;
    String profile = "hq";
    final int[] custom = {179, 408, 99};

    TextView moduleLine, profileHint, console, statusView;
    TextView[] chips = new TextView[4];
    Slider sbSlider, hlSlider, tbwSlider, afSlider, admSlider;
    Spinner enableSpinner;
    Switch admSwitch, moduleSwitch, autoSwitch, specSwitch;
    ResponseView respView;
    PathView pathView;
    SpectrumView specView;
    Visualizer viz;
    TextView tileRate, tileFmt, tileCh, tileSrc;

    // ---------------------------------------------------------------- UI helpers
    int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 18));
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        parent.addView(c, lp);
        TextView t = text(title.toUpperCase(), 12, ACC, true);
        t.setLetterSpacing(0.12f);
        c.addView(t);
        return c;
    }

    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    TextView mono(int sp) {
        TextView t = text("", sp, TXT, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setBackground(round(0xFF0F1216, 10));
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    TextView button(LinearLayout parent, String label, int color, Runnable action) {
        TextView b = text(label, 14, color == ACC ? 0xFF06251C : TXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = round(color == ACC ? ACC : CARD2, 12);
        if (color == WARN || color == BAD) bg.setStroke(dp(1), color);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        if (color == WARN || color == BAD) b.setTextColor(color);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        parent.addView(b, lp);
        return b;
    }

    Switch toggle(LinearLayout parent, String label) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(TXT);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        parent.addView(s, lp);
        return s;
    }

    final class Slider {
        final TextView val;
        final SeekBar bar;
        final int min;
        final String unit;

        Slider(LinearLayout parent, String name, int min, int max, String unit, Runnable onUser) {
            this.min = min;
            this.unit = unit;
            LinearLayout row = new LinearLayout(this_());
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView n = text(name, 13, SUB, false);
            val = text("", 13, TXT, true);
            row.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(val);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(10);
            parent.addView(row, lp);
            bar = new SeekBar(this_());
            bar.setMax(max - min);
            parent.addView(bar, new LinearLayout.LayoutParams(-1, -2));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    upd();
                    if (fromUser && onUser != null) onUser.run();
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
        }

        int get() { return bar.getProgress() + min; }
        void set(int v) { bar.setProgress(Math.max(0, Math.min(bar.getMax(), v - min))); upd(); }
        void upd() { val.setText(get() + unit); }
        void enable(boolean e) { bar.setEnabled(e); bar.setAlpha(e ? 1f : 0.4f); }
    }

    MainActivity this_() { return this; }

    // ---------------------------------------------------------------- lifecycle
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("vtlo", MODE_PRIVATE);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(32));
        sv.addView(root, new ViewGroup.LayoutParams(-1, -2));
        setContentView(sv);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        head.addView(logo, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        titles.addView(text("VTLoutput_HQ", 26, TXT, true));
        titles.addView(text("USB DAC resampler control + live visuals", 13, SUB, false));
        head.addView(titles);
        root.addView(head);

        // --- Setup banner (module install / update / reboot)
        bannerCard = card(root, "Setup");
        bannerText = text("", 14, TXT, false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(6);
        bannerCard.addView(bannerText, blp);
        bannerBtn = button(bannerCard, "...", ACC, this::bannerAction);
        readBundled();

        // --- Signal path card
        LinearLayout sp = card(root, "Signal path");
        pathView = new PathView(this);
        sp.addView(pathView, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tileRate = tile(tiles, "RATE Hz");
        tileFmt = tile(tiles, "FORMAT");
        tileCh = tile(tiles, "CH");
        tileSrc = tile(tiles, "SRC");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(10);
        sp.addView(tiles, tlp);

        // --- Spectrum card
        LinearLayout sc = card(root, "Spectrum (beta)");
        specView = new SpectrumView(this);
        specView.setMessage("Spectrum off");
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, -2);
        svlp.topMargin = dp(8);
        sc.addView(specView, svlp);
        specSwitch = toggle(sc, "Live spectrum + peak meter");
        specSwitch.setOnCheckedChangeListener((v, on) -> { if (on) startSpectrum(); else stopSpectrum(); });
        sc.addView(text("Uses Android's Visualizer on the output mix, which requires the microphone permission. Nothing is recorded or stored.", 11, SUB, false));

        // --- Resampler card
        LinearLayout rs = card(root, "Resampler profile");
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView c = text(PROFILE_LABELS[i], 14, TXT, true);
            c.setGravity(Gravity.CENTER);
            c.setPadding(0, dp(10), 0, dp(10));
            c.setOnClickListener(v -> selectProfile(PROFILES[idx]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = i < 3 ? dp(6) : 0;
            chipRow.addView(c, lp);
            chips[i] = c;
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(10);
        rs.addView(chipRow, clp);
        profileHint = text("", 12, SUB, false);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(8);
        rs.addView(profileHint, hlp);
        respView = new ResponseView(this);
        LinearLayout.LayoutParams rvlp = new LinearLayout.LayoutParams(-1, -2);
        rvlp.topMargin = dp(8);
        rs.addView(respView, rvlp);

        sbSlider = new Slider(rs, "Stopband attenuation", 60, 220, " dB", () -> storeCustom());
        hlSlider = new Slider(rs, "Filter half-length", 16, 800, " taps", () -> storeCustom());
        tbwSlider = new Slider(rs, "Transition bandwidth (tbw)", 50, 150, " %", () -> storeCustom());

        TextView er = text("Apply filter when output rate is", 13, SUB, false);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = dp(14);
        rs.addView(er, elp);
        enableSpinner = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ENABLE_LABELS);
        enableSpinner.setAdapter(ad);
        enableSpinner.setSelection(1);
        rs.addView(enableSpinner, new LinearLayout.LayoutParams(-1, -2));

        afSlider = new Slider(rs, "af.resampler.quality (fallback, 7 = best)", 0, 7, "", null);
        afSlider.set(7);
        rs.addView(text("Read only when audioserver starts: use \"Restart audio server\" or reboot.", 11, SUB, false));

        admSwitch = toggle(rs, "Override Tensor audio buffering");
        admSlider = new Slider(rs, "vendor.audio.adm.buffering.ms", 0, 20, " ms", null);
        admSlider.set(2);
        admSwitch.setOnCheckedChangeListener((v, on) -> admSlider.enable(on));
        admSlider.enable(false);
        rs.addView(text("Lower buffering can cut jitter but may cause dropouts. Leave off unless you hear problems.", 11, SUB, false));

        buildEqCard(root);
        buildGainCard(root);
        buildImpedanceCard(root);

        // --- Actions card
        LinearLayout ac = card(root, "Actions");
        button(ac, "Save & apply now", ACC, () -> saveAndApply(true));
        button(ac, "Save only (applies at next boot)", CARD2, () -> saveAndApply(false));
        button(ac, "Restart audio server", WARN, () -> confirm(
                "Restart audioserver? All audio stops for a moment; apps may need to restart playback.", this::restartAudio));
        button(ac, "Reboot", BAD, () -> confirm("Reboot the phone now?", this::reboot));
        console = mono(12);
        console.setText("Ready.");
        ac.addView(console);
        ac.addView(text("After \"Save & apply now\", pause and restart the track in Deezer to hear the change.", 11, SUB, false));

        // --- Module card
        LinearLayout mod = card(root, "Module");
        moduleLine = text("Checking root...", 15, TXT, true);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = dp(6);
        mod.addView(moduleLine, mlp);
        moduleSwitch = toggle(mod, "Module enabled");
        moduleSwitch.setOnCheckedChangeListener((v, on) -> { if (!loading) setModuleEnabled(on); });
        modBtn = button(mod, "Install / reinstall module", CARD2, this::installModule);
        button(mod, "Save module zip to Downloads (manual flash)", CARD2, this::saveModuleZip);

        // --- Status card
        LinearLayout st = card(root, "Live USB status");
        autoSwitch = toggle(st, "Auto-refresh (2 s)");
        autoSwitch.setOnCheckedChangeListener((v, on) -> {
            autoRefresh = on;
            if (on) tick();
        });
        button(st, "Refresh now", CARD2, this::refreshStatus);
        statusView = mono(11);
        statusView.setText("Play music, then refresh.");
        st.addView(statusView);
        autoSwitch.setChecked(true);

        // --- Log card
        LinearLayout lg = card(root, "Module log");
        button(lg, "Show log", CARD2, () -> runToConsole("tail -n 40 " + Root.BASE + "/log.txt 2>&1 || echo 'no log yet'"));
        button(lg, "Clear log", CARD2, () -> runToConsole(": > " + Root.BASE + "/log.txt && echo cleared"));

        selectProfile("hq");
        detect();
        restoreImpedance();
        restoreEq();
        loadIndex(false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (autoRefresh) tick();
        if (specSwitch != null && specSwitch.isChecked()) startSpectrum();
    }
    @Override protected void onDestroy() { super.onDestroy(); stopViz(); io.shutdownNow(); net.shutdownNow(); }

    // ---------------------------------------------------------------- profile logic
    void storeCustom() {
        if (!"custom".equals(profile)) return;
        custom[0] = sbSlider.get();
        custom[1] = hlSlider.get();
        custom[2] = tbwSlider.get();
        updateResponse();
    }

    void updateResponse() {
        if (respView == null) return;
        respView.setParams(sbSlider.get(), hlSlider.get(), tbwSlider.get(), !"off".equals(profile));
    }

    void selectProfile(String p) {
        profile = p;
        int idx = 0;
        for (int i = 0; i < PROFILES.length; i++) if (PROFILES[i].equals(p)) idx = i;
        for (int i = 0; i < 4; i++) {
            boolean sel = i == idx;
            chips[i].setBackground(round(sel ? ACC : CARD2, 10));
            chips[i].setTextColor(sel ? 0xFF06251C : TXT);
        }
        profileHint.setText(PROFILE_HINTS[idx]);
        boolean isCustom = "custom".equals(p);
        int[] vals = isCustom ? custom : (idx < 2 ? PRESETS[idx] : custom);
        sbSlider.set(vals[0]);
        hlSlider.set(vals[1]);
        tbwSlider.set(vals[2]);
        sbSlider.enable(isCustom);
        hlSlider.enable(isCustom);
        tbwSlider.enable(isCustom);
        enableSpinner.setEnabled(!"off".equals(p));
        afSlider.enable(!"off".equals(p));
        updateResponse();
    }

    String configText() {
        int er = ENABLE_RATES[enableSpinner.getSelectedItemPosition()];
        return "# USB Audio HQ config (written by the app)\n"
                + "PROFILE=" + profile + "\n"
                + "CUSTOM_SB=" + custom[0] + "\n"
                + "CUSTOM_HL=" + custom[1] + "\n"
                + "CUSTOM_TBW=" + custom[2] + "\n"
                + "ENABLE_AT=" + er + "\n"
                + "AF_QUALITY=" + afSlider.get() + "\n"
                + "ADM_BUFFERING_MS=" + (admSwitch.isChecked() ? String.valueOf(admSlider.get()) : "") + "\n";
    }

    void loadConfig(String text) {
        int[] c = custom.clone();
        String prof = "hq";
        int enableAt = 44100, afq = 7, adm = -1;
        for (String raw : text.split("\n")) {
            String line = raw;
            int h = line.indexOf('#');
            if (h >= 0) line = line.substring(0, h);
            line = line.trim();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
            try {
                switch (k) {
                    case "PROFILE": prof = v; break;
                    case "CUSTOM_SB": c[0] = Integer.parseInt(v); break;
                    case "CUSTOM_HL": c[1] = Integer.parseInt(v); break;
                    case "CUSTOM_TBW": c[2] = Integer.parseInt(v); break;
                    case "ENABLE_AT": enableAt = Integer.parseInt(v); break;
                    case "AF_QUALITY": afq = Integer.parseInt(v); break;
                    case "ADM_BUFFERING_MS": adm = v.isEmpty() ? -1 : Integer.parseInt(v); break;
                    default: break;
                }
            } catch (NumberFormatException ignored) { }
        }
        System.arraycopy(c, 0, custom, 0, 3);
        int sel = 1;
        for (int i = 0; i < ENABLE_RATES.length; i++) if (ENABLE_RATES[i] == enableAt) sel = i;
        enableSpinner.setSelection(sel);
        afSlider.set(afq);
        admSwitch.setChecked(adm >= 0);
        if (adm >= 0) admSlider.set(adm);
        boolean known = false;
        for (String p : PROFILES) if (p.equals(prof)) known = true;
        selectProfile(known ? prof : "hq");
    }

    // ---------------------------------------------------------------- root actions
    void bg(Runnable r) { try { io.execute(r); } catch (Exception ignored) { } }
    void onUi(Runnable r) { ui.post(r); }

    void detect() {
        bg(() -> {
            String script = "echo uid=$(id -u)\n"
                    + "[ -f " + Root.MOD + "/module.prop ] && echo installed=1\n"
                    + "[ -f " + Root.MOD + "/disable ] && echo disabled=1\n"
                    + "[ -f " + Root.MOD + "/remove ] && echo removing=1\n"
                    + "grep -E '^version(Code)?=' " + Root.MOD + "/module.prop 2>/dev/null\n"
                    + "echo ---CFG---\n"
                    + "cat " + Root.BASE + "/config 2>/dev/null";
            Root.Result r = Root.sh(script, 20);
            onUi(() -> {
                String[] parts = r.out.split("---CFG---", 2);
                String head = parts[0];
                rootOk = head.contains("uid=0");
                installed = head.contains("installed=1");
                disabled = head.contains("disabled=1");
                String ver = "";
                installedCode = 0;
                for (String l : head.split("\n")) {
                    if (l.startsWith("version=")) ver = l.substring(8).trim();
                    if (l.startsWith("versionCode=")) {
                        try { installedCode = Integer.parseInt(l.substring(12).trim()); } catch (NumberFormatException ignored) { }
                    }
                }
                installedVer = ver;
                loading = true;
                if (!rootOk) {
                    moduleLine.setText("Root access denied");
                    moduleLine.setTextColor(BAD);
                } else if (!installed) {
                    moduleLine.setText("Module not installed");
                    moduleLine.setTextColor(WARN);
                } else {
                    moduleLine.setText("Module " + ver + (disabled ? " - disabled (reboot to apply)" : " - active"));
                    moduleLine.setTextColor(disabled ? WARN : ACC);
                }
                moduleSwitch.setChecked(installed && !disabled);
                moduleSwitch.setEnabled(installed);
                if (parts.length > 1 && !parts[1].trim().isEmpty()) loadConfig(parts[1]);
                loading = false;
                updateBanner();
                if (rootOk && installed) tick();
            });
        });
    }

    void runToConsole(String script) {
        console.setText("...");
        bg(() -> {
            Root.Result r = Root.sh(script, 30);
            onUi(() -> console.setText(r.out.isEmpty() ? "(no output, exit " + r.code + ")" : r.out));
        });
    }

    void saveAndApply(boolean live) {
        if (!rootOk) { toast("Root access needed"); return; }
        if (!installed) { toast("Install the module first"); return; }
        String script = "mkdir -p " + Root.BASE + "\n"
                + "cat > " + Root.BASE + "/config <<'VTLO_EOF'\n" + configText() + "VTLO_EOF\n"
                + "echo 'config saved'\n"
                + (live ? "sh " + Root.MOD + "/apply.sh\ntail -n 12 " + Root.BASE + "/log.txt\n" : "");
        runToConsole(script);
        if (live && "off".equals(profile)) toast("Off takes effect at next reboot");
    }

    void setModuleEnabled(boolean on) {
        if (!rootOk || !installed) return;
        runToConsole((on ? "rm -f " : "touch ") + Root.MOD + "/disable && echo 'module "
                + (on ? "enabled" : "disabled") + " - reboot to apply'");
        moduleLine.setText(on ? "Enabled (reboot to apply)" : "Disabled (reboot to apply)");
        moduleLine.setTextColor(WARN);
    }

    void installModule() {
        if (!rootOk) { toast("Root access needed"); return; }
        console.setText("Installing...");
        bg(() -> {
            String msg;
            final boolean[] ok = {false};
            try {
                File z = new File(getCacheDir(), "module.zip");
                try (InputStream in = getAssets().open("module.zip"); OutputStream out = new FileOutputStream(z)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                String t = "/data/local/tmp/vtlo_usb_audio_hq.zip";
                Root.Result r = Root.sh("cp '" + z.getAbsolutePath() + "' " + t + " && chmod 644 " + t + "\n"
                        + "if command -v magisk >/dev/null 2>&1; then magisk --install-module " + t + "\n"
                        + "elif command -v ksud >/dev/null 2>&1; then ksud module install " + t + "\n"
                        + "elif command -v apd >/dev/null 2>&1; then apd module install " + t + "\n"
                        + "else echo 'No Magisk / KernelSU / APatch binary found'; fi\n"
                        + "rm -f " + t, 120);
                ok[0] = r.code == 0 && !r.out.contains("No Magisk");
                msg = r.out + (ok[0] ? "\n\nModule installed - reboot to activate." : "\n\nInstall did not complete.");
            } catch (Exception e) {
                msg = "Install failed: " + e.getMessage();
            }
            final String m = msg;
            onUi(() -> { console.setText(m); pendingReboot = ok[0]; detect(); });
        });
    }

    void readBundled() {
        try (ZipInputStream z = new ZipInputStream(getAssets().open("module.zip"))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.getName().equals("module.prop")) continue;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = z.read(buf)) > 0) bo.write(buf, 0, n);
                for (String l : bo.toString("UTF-8").split("\n")) {
                    if (l.startsWith("version=")) bundledVer = l.substring(8).trim();
                    if (l.startsWith("versionCode=")) bundledCode = Integer.parseInt(l.substring(12).trim());
                }
            }
        } catch (Exception ignored) { }
    }

    /** bannerState: 0 hidden, 1 retry root, 2 install, 3 update, 4 reboot, 5 enable */
    void updateBanner() {
        if (pendingReboot) {
            bannerState = 4;
            bannerText.setText("Module " + bundledVer + " installed. Reboot to activate it.");
            bannerBtn.setText("Reboot now");
        } else if (!rootOk) {
            bannerState = 1;
            bannerText.setText("Root access denied. Grant root to this app in Magisk, then retry.");
            bannerBtn.setText("Retry");
        } else if (!installed) {
            bannerState = 2;
            bannerText.setText("The Magisk module is not installed. This app includes module " + bundledVer + ".");
            bannerBtn.setText("Install module " + bundledVer);
        } else if (installedCode < bundledCode) {
            bannerState = 3;
            bannerText.setText("Module update available: " + (installedVer.isEmpty() ? "old" : installedVer) + " -> " + bundledVer);
            bannerBtn.setText("Update module to " + bundledVer);
        } else if (disabled) {
            bannerState = 5;
            bannerText.setText("The module is disabled.");
            bannerBtn.setText("Enable module");
        } else {
            bannerState = 0;
        }
        bannerCard.setVisibility(bannerState == 0 ? View.GONE : View.VISIBLE);
        if (modBtn != null) modBtn.setText("Install / reinstall module (" + bundledVer + ")");
    }

    void bannerAction() {
        switch (bannerState) {
            case 1: detect(); break;
            case 2: case 3: installModule(); break;
            case 4: confirm("Reboot the phone now?", this::reboot); break;
            case 5: setModuleEnabled(true); break;
            default: break;
        }
    }

    void saveModuleZip() {
        String fname = "vtlo_usb_audio_hq_" + bundledVer + ".zip";
        try (InputStream in = getAssets().open("module.zip")) {
            OutputStream o;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, fname);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
                o = getContentResolver().openOutputStream(getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv));
            } else {
                o = new FileOutputStream(new File(getExternalFilesDir(null), fname));
            }
            try (OutputStream out = o) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            toast("Saved: " + fname);
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
    }

    void restartAudio() {
        runToConsole("killall audioserver 2>/dev/null || pkill -x audioserver; sleep 2; pidof audioserver && echo 'audioserver restarted'");
    }

    void reboot() {
        bg(() -> Root.sh("svc power reboot || reboot", 10));
    }

    void refreshStatus() {
        if (statusView == null || pathView == null) return;
        if (!rootOk || !installed) {
            statusView.setText("Module not installed / no root.");
            pathView.set(false, 0, "", 0, "");
            return;
        }
        bg(() -> {
            Root.Result r = Root.sh("sh " + Root.MOD + "/system/bin/usbaudio-status", 15);
            onUi(() -> { statusView.setText(r.out); applyStatus(r.out); });
        });
    }

    final Runnable tickRunnable = this::tick;

    void tick() {
        ui.removeCallbacks(tickRunnable);
        if (eqStatus != null) eqStatus.setText(EqService.status);
        if (!autoRefresh) return;
        refreshStatus();
        ui.postDelayed(tickRunnable, 2000);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tickRunnable);
        stopViz();
    }

    // ---------------------------------------------------------------- Impedance
    static final double[] ZO_PRESETS = {0.1, 1, 5, 10};

    void buildImpedanceCard(LinearLayout root) {
        LinearLayout ic = card(root, "Output impedance");
        ic.addView(text("Source (DAC/amp) output impedance vs headphone impedance. Android cannot measure or change a USB DAC's output impedance: take it from the DAC's spec sheet.", 11, SUB, false));
        impView = new ImpedanceView(this);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, -2);
        vlp.topMargin = dp(10);
        ic.addView(impView, vlp);

        LinearLayout tr = new LinearLayout(this);
        tr.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView ch = text(Impedance.TYPES[i], 12, TXT, true);
            ch.setGravity(Gravity.CENTER);
            ch.setPadding(dp(6), dp(9), dp(6), dp(9));
            ch.setOnClickListener(v -> { impType = idx; impChanged(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = dp(6);
            tr.addView(ch, lp);
            typeChips[i] = ch;
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(10);
        ic.addView(tr, tlp);

        zoSlider = new Slider(ic, "Source output impedance", 1, 1000, "", this::impChanged);
        zhSlider = new Slider(ic, "Headphone impedance", 8, 600, " ohm", this::impChanged);
        sensSlider = new Slider(ic, "Sensitivity (dB SPL / mW)", 70, 130, " dB", this::impChanged);
        vSlider = new Slider(ic, "DAC max output (Vrms)", 1, 40, "", this::impChanged);

        LinearLayout pr = new LinearLayout(this);
        pr.setOrientation(LinearLayout.HORIZONTAL);
        for (double zv : ZO_PRESETS) {
            final int tenth = (int) Math.round(zv * 10);
            TextView ch = text(zv + " ohm", 12, TXT, false);
            ch.setGravity(Gravity.CENTER);
            ch.setBackground(round(CARD2, 10));
            ch.setPadding(dp(6), dp(8), dp(6), dp(8));
            ch.setOnClickListener(v -> { zoSlider.set(tenth); impChanged(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = dp(6);
            pr.addView(ch, lp);
        }
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(10);
        ic.addView(pr, plp);

        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tileLoss = tile(tiles, "LEVEL LOSS");
        tileDf = tile(tiles, "DAMPING");
        tileMw = tile(tiles, "POWER");
        tileSpl = tile(tiles, "MAX SPL");
        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(-1, -2);
        tlp2.topMargin = dp(12);
        ic.addView(tiles, tlp2);

        impVerdict = text("", 13, TXT, true);
        LinearLayout.LayoutParams vlp2 = new LinearLayout.LayoutParams(-1, -2);
        vlp2.topMargin = dp(10);
        ic.addView(impVerdict, vlp2);
        ic.addView(text("Zo is in 0.1 ohm steps (slider 1 = 0.1 ohm). Rule of thumb: Zo below Zh/8 keeps level and tone changes inaudible; multi-driver IEMs with a varying impedance are the most sensitive. The curves per headphone type are illustrative shapes, not measurements.", 11, SUB, false));
    }

    double zoOhm() { return zoSlider.get() / 10.0; }

    void impChanged() {
        if (impView == null || zoSlider == null || vSlider == null) return;
        zoSlider.val.setText(String.format(java.util.Locale.US, "%.1f ohm", zoOhm()));
        vSlider.val.setText(String.format(java.util.Locale.US, "%.1f V", vSlider.get() / 10.0));
        for (int i = 0; i < 3; i++) {
            boolean sel = i == impType;
            typeChips[i].setBackground(round(sel ? ACC : CARD2, 10));
            typeChips[i].setTextColor(sel ? 0xFF06251C : TXT);
        }
        Impedance.Result r = Impedance.compute(zoOhm(), zhSlider.get(), sensSlider.get(), vSlider.get() / 10.0, impType);
        impView.setParams(zoOhm(), zhSlider.get(), impType);
        tileLoss.setText(String.format(java.util.Locale.US, "%.2f dB", r.lossNomDb));
        tileDf.setText(r.damping > 999 ? ">999" : String.format(java.util.Locale.US, "%.1f", r.damping));
        tileMw.setText(String.format(java.util.Locale.US, "%.0f mW", r.powerMw));
        tileSpl.setText(String.format(java.util.Locale.US, "%.0f dB", r.splDb));
        int[] cols = {ACC, WARN, BAD};
        tileDf.setTextColor(cols[r.damping >= 8 ? 0 : r.damping >= 2 ? 1 : 2]);
        impVerdict.setText(r.verdict);
        impVerdict.setTextColor(cols[r.level]);
        if (prefs != null) prefs.edit().putInt("imp_zo", zoSlider.get()).putInt("imp_zh", zhSlider.get())
                .putInt("imp_sens", sensSlider.get()).putInt("imp_v", vSlider.get()).putInt("imp_type", impType).apply();
    }

    void restoreImpedance() {
        zoSlider.set(prefs.getInt("imp_zo", 10));
        zhSlider.set(prefs.getInt("imp_zh", 32));
        sensSlider.set(prefs.getInt("imp_sens", 100));
        vSlider.set(prefs.getInt("imp_v", 10));
        impType = Math.max(0, Math.min(2, prefs.getInt("imp_type", 0)));
        impChanged();
    }

    // ---------------------------------------------------------------- Equalizer + AutoEq
    void buildEqCard(LinearLayout root) {
        LinearLayout ec = card(root, "Equalizer - AutoEq + 10 bands");
        ec.addView(text("Float multiband EQ (128 frequency bands, Android DynamicsProcessing). It never resamples or reduces bit depth, the USB output keeps its rate and format. Drag the orange dots (31 Hz - 16 kHz, +-12 dB); the grey dashed curve is the AutoEq profile.", 11, SUB, false));
        eqCurve = new EqCurveView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(10);
        ec.addView(eqCurve, clp);
        eqCurve.listener = (i, tenths, done) -> { eqMan[i] = tenths; eqChanged(done); };

        eqSwitch = toggle(ec, "Equalizer active");
        eqSwitch.setOnCheckedChangeListener((v, on) -> { if (!eqRestoring) setEqRunning(on); });
        headSwitch = toggle(ec, "Auto headroom (lowers input gain so boosts cannot clip)");
        headSwitch.setChecked(true);
        headSwitch.setOnCheckedChangeListener((v, on) -> eqChanged(true));
        preSlider = new Slider(ec, "Preamp", -120, 60, "", () -> eqChanged(false));
        preSlider.bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { preSlider.upd(); if (fromUser) eqChanged(false); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { eqChanged(true); }
        });
        preSlider.set(0);
        button(ec, "Reset 10 manual bands", CARD2, () -> { java.util.Arrays.fill(eqMan, 0); eqChanged(true); });
        eqPlanText = text("", 12, TXT, false);
        ec.addView(eqPlanText);
        eqStatus = mono(11);
        eqStatus.setText(EqService.status);
        ec.addView(eqStatus);

        TextView h = text("AUTOEQ PROFILE", 11, ACC, true);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(16);
        ec.addView(h, hl);
        eqInfo = text("Loading AutoEq index...", 12, SUB, false);
        ec.addView(eqInfo);
        eqSearch = new EditText(this);
        eqSearch.setHint("Search headphone / IEM (e.g. HD 600)");
        eqSearch.setHintTextColor(SUB);
        eqSearch.setTextColor(TXT);
        eqSearch.setSingleLine(true);
        eqSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        eqSearch.setBackground(round(CARD2, 10));
        eqSearch.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(8);
        ec.addView(eqSearch, slp);
        eqSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { showEqResults(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        eqResults = new LinearLayout(this);
        eqResults.setOrientation(LinearLayout.VERTICAL);
        ec.addView(eqResults);
        button(ec, "Clear AutoEq profile", CARD2, () -> { eqProfile = null; eqNameStr = ""; eqChanged(true); eqInfo.setText("AutoEq profile cleared"); });
        button(ec, "Refresh AutoEq index", CARD2, () -> loadIndex(true));
        ec.addView(text("Profiles come from github.com/jaakkopasanen/AutoEq (MIT). Global effects are best-effort on Android: the EQ attaches to the global mix and to every player that announces an audio session. The status box shows what really attached. Apps using Android's bit-perfect USB path bypass effects. EQ stops if the phone reboots or the app is force-stopped.", 11, SUB, false));
    }

    void eqChanged(boolean persist) {
        if (eqCurve == null || preSlider == null) return;
        preSlider.val.setText(String.format(java.util.Locale.US, "%+.1f dB", preSlider.get() / 10.0));
        eqCurve.setData(eqProfile, eqMan);
        Eq.Plan p = Eq.plan(eqProfile, eqMan, preSlider.get() / 10.0, headSwitch.isChecked(), 32);
        eqPlanText.setText(String.format(java.util.Locale.US, "Peak boost %+.1f dB -> input gain %+.1f dB%s", p.peakDb, p.inputGainDb,
                eqNameStr.isEmpty() ? "" : "  |  AutoEq: " + eqNameStr));
        if (!persist) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < eqMan.length; i++) sb.append(i > 0 ? "," : "").append(eqMan[i]);
        prefs.edit().putString("eq_manual", sb.toString()).putInt("eq_pre", preSlider.get())
                .putBoolean("eq_head", headSwitch.isChecked()).apply();
        if (EqService.running) {
            try { startService(new Intent(this, EqService.class).setAction(EqService.ACTION_APPLY)); } catch (RuntimeException ignored) { }
            ui.postDelayed(() -> { if (eqStatus != null) eqStatus.setText(EqService.status); }, 800);
        }
    }

    void setEqRunning(boolean on) {
        prefs.edit().putBoolean("eq_on", on).apply();
        Intent i = new Intent(this, EqService.class);
        try {
            if (on) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 8);
                eqChanged(true);
                startForegroundService(i.setAction(EqService.ACTION_APPLY));
                ui.postDelayed(() -> { if (eqStatus != null) eqStatus.setText(EqService.status); }, 1200);
            } else {
                stopService(i);
                eqStatus.setText("Equalizer off");
            }
        } catch (RuntimeException e) {
            eqStatus.setText("Could not start the service: " + e.getMessage());
        }
    }

    void restoreEq() {
        eqRestoring = true;
        String t = prefs.getString("eq_text", "");
        eqProfile = t.isEmpty() ? null : AutoEq.Profile.parse(t);
        eqNameStr = prefs.getString("eq_name", "");
        String[] parts = prefs.getString("eq_manual", "").split(",");
        for (int i = 0; i < eqMan.length && i < parts.length; i++) {
            try { eqMan[i] = Integer.parseInt(parts[i].trim()); } catch (NumberFormatException ignored) { }
        }
        preSlider.set(prefs.getInt("eq_pre", 0));
        headSwitch.setChecked(prefs.getBoolean("eq_head", true));
        eqSwitch.setChecked(EqService.running);
        eqRestoring = false;
        eqChanged(false);
    }

    void loadIndex(boolean force) {
        final java.io.File cache = new java.io.File(getFilesDir(), "autoeq_index.md");
        if (!force && cache.exists()) {
            try {
                byte[] b = java.nio.file.Files.readAllBytes(cache.toPath());
                eqIndex = AutoEq.parseIndex(new String(b, java.nio.charset.StandardCharsets.UTF_8));
                eqInfo.setText(eqIndex.size() + " profiles available (cached)");
                if (System.currentTimeMillis() - cache.lastModified() < 7L * 86400000L) return;
            } catch (Exception ignored) { }
        }
        eqInfo.setText("Downloading AutoEq index...");
        net.execute(() -> {
            try {
                String md = AutoEq.fetch(AutoEq.RAW + "/INDEX.md", 30000);
                List<AutoEq.Entry> list = AutoEq.parseIndex(md);
                if (list.isEmpty()) throw new Exception("empty index");
                java.nio.file.Files.write(cache.toPath(), md.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                onUi(() -> { eqIndex = list; eqInfo.setText(list.size() + " profiles available"); showEqResults(); });
            } catch (Exception e) {
                onUi(() -> eqInfo.setText((eqIndex == null ? "Index unavailable: " : "Refresh failed: ") + e.getMessage() + " (check internet)"));
            }
        });
    }

    void showEqResults() {
        if (eqResults == null) return;
        eqResults.removeAllViews();
        if (eqIndex == null) return;
        for (AutoEq.Entry e : AutoEq.search(eqIndex, eqSearch.getText().toString(), 12)) {
            TextView b = text(e.label(), 13, TXT, false);
            b.setBackground(round(CARD2, 10));
            b.setPadding(dp(12), dp(10), dp(12), dp(10));
            b.setOnClickListener(v -> selectEq(e));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(6);
            eqResults.addView(b, lp);
        }
    }

    void selectEq(AutoEq.Entry e) {
        eqInfo.setText("Downloading " + e.name + "...");
        net.execute(() -> {
            try {
                String txt = AutoEq.fetch(e.url(), 20000);
                AutoEq.Profile p = AutoEq.Profile.parse(txt);
                if (p.filters.isEmpty()) throw new Exception("no filters in profile");
                onUi(() -> {
                    eqProfile = p;
                    eqNameStr = e.name;
                    prefs.edit().putString("eq_text", txt).putString("eq_name", e.name).apply();
                    eqInfo.setText("Selected: " + e.name + " (" + p.filters.size() + " filters, preamp " + p.preamp + " dB)");
                    eqResults.removeAllViews();
                    eqChanged(true);
                });
            } catch (Exception ex) {
                onUi(() -> eqInfo.setText("Download failed: " + ex.getMessage()));
            }
        });
    }

    // ---------------------------------------------------------------- USB card hardware gain
    static final class GainCtl {
        int card, numid, min, max, count, cur;
        String cardId, name, type;
        long dbMin, dbMax;
        boolean hasDb;
        TextView label;
        String dbText(int v) {
            if (!hasDb || max == min) return String.valueOf(v);
            return String.format(java.util.Locale.US, "%d (%.1f dB)", v, (dbMin + (double) (v - min) / (max - min) * (dbMax - dbMin)) / 100.0);
        }
    }

    static List<GainCtl> parseGain(String out) {
        List<GainCtl> list = new ArrayList<>();
        int card = -1;
        String cardId = "";
        for (String line : out.split("\n")) {
            if (line.startsWith("@@CARD ")) {
                String[] a = line.split(" ", 3);
                try { card = Integer.parseInt(a[1]); } catch (Exception e) { card = -1; }
                cardId = a.length > 2 ? a[2].trim() : "";
                continue;
            }
            String[] f = line.split("\t", -1);
            if (card < 0 || f.length < 11) continue;
            try {
                if (!f[1].equals("2")) continue; // mixer controls only
                String type = f[3];
                if (!type.equals("int") && !type.equals("bool")) continue;
                String nm = f[2];
                String low = nm.toLowerCase(java.util.Locale.US);
                if (low.contains("capture") || !(low.contains("volume") || low.contains("gain") || low.contains("mute") || low.contains("switch") || low.contains("level"))) continue;
                GainCtl g = new GainCtl();
                g.card = card; g.cardId = cardId; g.numid = Integer.parseInt(f[0]); g.name = nm; g.type = type;
                g.count = Integer.parseInt(f[4]); g.min = Integer.parseInt(f[5]); g.max = Integer.parseInt(f[6]);
                if (!f[8].isEmpty() && !f[9].isEmpty()) { g.hasDb = true; g.dbMin = Long.parseLong(f[8]); g.dbMax = Long.parseLong(f[9]); }
                g.cur = f[10].isEmpty() ? g.min : Integer.parseInt(f[10].split(",")[0].trim());
                list.add(g);
            } catch (NumberFormatException ignored) { }
        }
        return list;
    }

    String toolPath() { return Root.BASE + "/vtlo-alsactl"; }

    void buildGainCard(LinearLayout root) {
        LinearLayout gc = card(root, "USB card hardware gain");
        gc.addView(text("Controls the DAC's own volume/gain registers (ALSA mixer, root). Keep Android's volume high and trim here: the attenuation then happens in the DAC instead of throwing away digital bits. Only DACs that expose a USB volume control show up; many do not. Values reset when the DAC is unplugged.", 11, SUB, false));
        button(gc, "Scan USB audio card", ACC, this::scanGain);
        gainStatus = mono(11);
        gainStatus.setText("Plug in the DAC, then scan.");
        gc.addView(gainStatus);
        gainBox = new LinearLayout(this);
        gainBox.setOrientation(LinearLayout.VERTICAL);
        gc.addView(gainBox);
        button(gc, "Set all volumes to maximum", CARD2, () -> {
            for (GainCtl g : gainCtls) if (g.type.equals("int") && g.name.toLowerCase(java.util.Locale.US).contains("volume")) setGain(g, g.max);
        });
        button(gc, "Restore last used levels", CARD2, () -> {
            for (GainCtl g : gainCtls) if (prefs.contains("hw_" + g.name)) setGain(g, prefs.getInt("hw_" + g.name, g.cur));
        });
    }

    void scanGain() {
        if (!rootOk) { gainStatus.setText("Root not available."); return; }
        gainStatus.setText("Scanning...");
        io.execute(() -> {
            try {
                java.io.File tmp = new java.io.File(getCacheDir(), "vtlo-alsactl");
                try (java.io.InputStream in = getAssets().open("vtlo-alsactl"); java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                }
                String script = "mkdir -p " + Root.BASE + "; cp " + tmp.getAbsolutePath() + " " + toolPath() + " && chmod 755 " + toolPath() + "\n"
                        + "for d in /proc/asound/card*; do [ -e \"$d/usbid\" ] || continue; n=${d##*card}; echo \"@@CARD $n $(cat $d/id)\"; " + toolPath() + " list $n 2>&1; done\n"
                        + "echo @@END";
                Root.Result r = Root.sh(script.replace("\\n", "\n"), 20);
                List<GainCtl> list = parseGain(r.out);
                onUi(() -> renderGain(list, r.out));
            } catch (Exception e) {
                onUi(() -> gainStatus.setText("Scan failed: " + e.getMessage()));
            }
        });
    }

    void renderGain(List<GainCtl> list, String raw) {
        gainCtls.clear();
        gainCtls.addAll(list);
        gainBox.removeAllViews();
        if (!raw.contains("@@CARD")) { gainStatus.setText("No USB audio card detected (is the DAC plugged in?)"); return; }
        if (list.isEmpty()) { gainStatus.setText("USB card found but it exposes no playback volume/gain control to Android (typical for DACs with fixed output or buttons only).\n" + raw.replaceAll("(?m)^\\d+\\t.*$", "").trim()); return; }
        gainStatus.setText(list.size() + " control(s) on " + list.get(0).cardId);
        for (GainCtl g : list) {
            if (g.type.equals("bool")) {
                Switch sw = toggle(gainBox, g.name);
                sw.setChecked(g.cur != 0);
                sw.setOnCheckedChangeListener((v, on) -> setGain(g, on ? 1 : 0));
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView n = text(g.name, 13, SUB, false);
            g.label = text(g.dbText(g.cur), 13, TXT, true);
            row.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(g.label);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = dp(12);
            gainBox.addView(row, rlp);
            SeekBar bar = new SeekBar(this);
            bar.setMax(g.max - g.min);
            bar.setProgress(g.cur - g.min);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { g.label.setText(g.dbText(p + g.min)); }
                @Override public void onStartTrackingTouch(SeekBar sb) { }
                @Override public void onStopTrackingTouch(SeekBar sb) { setGain(g, sb.getProgress() + g.min); }
            });
            gainBox.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    void setGain(GainCtl g, int v) {
        g.cur = v;
        prefs.edit().putInt("hw_" + g.name, v).apply();
        io.execute(() -> {
            Root.Result r = Root.sh(toolPath() + " set " + g.card + " " + g.numid + " " + v, 10);
            onUi(() -> {
                if (r.code != 0) gainStatus.setText("Set failed: " + r.out);
                else gainStatus.setText(g.name + " = " + r.out.trim());
            });
        });
    }

    // ---------------------------------------------------------------- visuals
    TextView tile(LinearLayout parent, String label) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(round(CARD2, 10));
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        t.addView(text(label, 10, SUB, false));
        TextView v = text("-", 15, TXT, true);
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.rightMargin = dp(6);
        parent.addView(t, lp);
        return v;
    }

    static final Pattern LIVE = Pattern.compile("LIVE\\s+format=(\\S+)\\s+channels=(\\d+)\\s+rate=(\\d+)");

    void applyStatus(String out) {
        Matcher m = LIVE.matcher(out);
        int idx = 0;
        for (int i = 0; i < PROFILES.length; i++) if (PROFILES[i].equals(profile)) idx = i;
        String plabel = "off".equals(profile) ? "" : PROFILE_LABELS[idx] + " " + sbSlider.get() + "dB";
        if (m.find()) {
            String fmt = m.group(1);
            int ch = Integer.parseInt(m.group(2)), rate = Integer.parseInt(m.group(3));
            pathView.set(true, rate, fmt, ch, plabel);
            tileRate.setText(String.valueOf(rate));
            tileFmt.setText(fmt);
            tileCh.setText(String.valueOf(ch));
            tileSrc.setText(rate == 44100 ? "BYPASS" : "ON");
            tileSrc.setTextColor(rate == 44100 ? ACC : WARN);
        } else {
            pathView.set(false, 0, "", 0, plabel);
            tileRate.setText("-");
            tileFmt.setText("-");
            tileCh.setText("-");
            tileSrc.setText("-");
            tileSrc.setTextColor(TXT);
        }
    }

    void stopViz() {
        if (viz != null) {
            try { viz.setEnabled(false); viz.release(); } catch (Throwable ignored) { }
            viz = null;
        }
    }

    void startSpectrum() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        stopViz();
        try {
            viz = new Visualizer(0);
            viz.setEnabled(false);
            viz.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            final int size = viz.getCaptureSize();
            viz.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                float peak = -90f;
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] wf, int sr) {
                    int pk = 0;
                    for (byte b : wf) { int a = Math.abs((b & 0xFF) - 128); if (a > pk) pk = a; }
                    peak = pk == 0 ? -90f : (float) (20 * Math.log10(pk / 128.0));
                }
                @Override public void onFftDataCapture(Visualizer v, byte[] fft, int sr) {
                    specView.push(bands(fft, sr, size), peak);
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, true);
            viz.setEnabled(true);
            specView.setMessage("Play something...");
        } catch (Throwable t) {
            stopViz();
            specSwitch.setChecked(false);
            specView.setMessage("Spectrum unavailable (" + t.getClass().getSimpleName() + ")");
        }
    }

    void stopSpectrum() {
        stopViz();
        specView.setMessage("Spectrum off");
    }

    float[] bands(byte[] fft, int srMilli, int size) {
        float[] out = new float[SpectrumView.BANDS];
        float fs = srMilli / 1000f;
        if (fs <= 0) fs = 48000f;
        int nb = fft.length / 2;
        double fMin = 30, fMax = Math.min(20000, fs / 2 * 0.98);
        for (int b = 0; b < out.length; b++) {
            double lo = fMin * Math.pow(fMax / fMin, b / (double) out.length);
            double hi = fMin * Math.pow(fMax / fMin, (b + 1) / (double) out.length);
            int k0 = Math.max(1, (int) Math.floor(lo * size / fs));
            int k1 = Math.max(k0, (int) Math.ceil(hi * size / fs));
            k1 = Math.min(k1, nb - 1);
            double mx = 0;
            for (int k = k0; k <= k1; k++) {
                double re = fft[2 * k], im = fft[2 * k + 1];
                mx = Math.max(mx, Math.hypot(re, im));
            }
            double db = 20 * Math.log10(Math.max(mx, 0.5) / 128.0);
            out[b] = (float) Math.max(0, Math.min(1, (db + 60) / 60));
        }
        return out;
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        super.onRequestPermissionsResult(rc, perms, res);
        if (rc == 7) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startSpectrum();
            else { specSwitch.setChecked(false); specView.setMessage("Permission denied"); }
        }
    }

    // ---------------------------------------------------------------- misc
    void confirm(String msg, Runnable ok) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage(msg)
                .setPositiveButton("Yes", (d, w) -> ok.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
