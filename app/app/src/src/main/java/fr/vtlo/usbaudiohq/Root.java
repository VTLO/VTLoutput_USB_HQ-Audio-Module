package fr.vtlo.usbaudiohq;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Minimal root shell helper: pipes a script into `su` and returns stdout+stderr. */
final class Root {
    static final String BASE = "/data/adb/vtlo_usbaudio";
    static final String MOD = "/data/adb/modules/vtlo_usb_audio_hq";

    static final class Result {
        final int code;
        final String out;
        Result(int code, String out) { this.code = code; this.out = out; }
    }

    static Result sh(String script, int timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder("su").redirectErrorStream(true).start();
            final Process proc = p;
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                } catch (Exception ignored) { }
            });
            reader.start();
            try (OutputStream os = p.getOutputStream()) {
                os.write((script + "\nexit\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                reader.join(500);
                return new Result(-2, sb + "\n[timeout]");
            }
            reader.join(2000);
            return new Result(p.exitValue(), sb.toString().trim());
        } catch (Exception e) {
            if (p != null) p.destroy();
            return new Result(-1, "su failed: " + e.getMessage());
        }
    }
}
