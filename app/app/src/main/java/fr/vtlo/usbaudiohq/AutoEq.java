package fr.vtlo.usbaudiohq;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** AutoEq (github.com/jaakkopasanen/AutoEq, MIT) index + ParametricEQ.txt handling. */
final class AutoEq {
    static final String RAW = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results";
    static final Pattern IDX = Pattern.compile("^- \\[(.+?)\\]\\(\\./(.+?)\\) by (.+?) on (.+)$");
    static final Pattern FLT = Pattern.compile(
            "Filter\\s*\\d*:\\s*ON\\s+(PK|PEAKING|LSC|LS|HSC|HS)\\s+Fc\\s+([\\d.]+)\\s*Hz(?:\\s+Gain\\s+(-?[\\d.]+)\\s*dB)?(?:\\s+Q\\s+([\\d.]+))?",
            Pattern.CASE_INSENSITIVE);
    static final Pattern PRE = Pattern.compile("Preamp:\\s*(-?[\\d.]+)\\s*dB", Pattern.CASE_INSENSITIVE);

    private AutoEq() { }

    static final class Entry {
        final String name, path, source, rig;
        Entry(String name, String path, String source, String rig) {
            this.name = name; this.path = path; this.source = source; this.rig = rig;
        }
        /** URL of the ParametricEQ.txt of this entry. */
        String url() {
            String last = path.substring(path.lastIndexOf('/') + 1);
            return RAW + "/" + path + "/" + last + "%20ParametricEQ.txt";
        }
        String label() { return name + "  (" + source + ", " + rig + ")"; }
    }

    static final class Profile {
        double preamp;
        final List<Eq.Filter> filters = new ArrayList<>();

        static Profile parse(String text) {
            Profile p = new Profile();
            if (text == null) return p;
            Matcher m = PRE.matcher(text);
            if (m.find()) p.preamp = Double.parseDouble(m.group(1));
            m = FLT.matcher(text);
            while (m.find()) {
                String t = m.group(1).toUpperCase(Locale.US);
                int type = t.startsWith("L") ? Eq.LS : t.startsWith("H") ? Eq.HS : Eq.PK;
                double fc = Double.parseDouble(m.group(2));
                double g = m.group(3) == null ? 0 : Double.parseDouble(m.group(3));
                double q = m.group(4) == null ? (type == Eq.PK ? 1.0 : 0.707) : Double.parseDouble(m.group(4));
                if (fc > 0 && q > 0) p.filters.add(new Eq.Filter(type, fc, g, q));
            }
            return p;
        }
    }

    static List<Entry> parseIndex(String md) {
        List<Entry> out = new ArrayList<>();
        for (String line : md.split("\n")) {
            Matcher m = IDX.matcher(line.trim());
            if (m.matches()) out.add(new Entry(m.group(1), m.group(2), m.group(3), m.group(4)));
        }
        return out;
    }

    static List<Entry> search(List<Entry> all, String query, int limit) {
        List<Entry> hits = new ArrayList<>();
        String[] tok = query.toLowerCase(Locale.US).trim().split("\\s+");
        if (query.trim().isEmpty()) return hits;
        for (Entry e : all) {
            String hay = (e.name + " " + e.source + " " + e.rig).toLowerCase(Locale.US);
            boolean ok = true;
            for (String t : tok) if (!hay.contains(t)) { ok = false; break; }
            if (ok) hits.add(e);
        }
        final String q0 = tok[0];
        hits.sort((a, b) -> {
            boolean sa = a.name.toLowerCase(Locale.US).startsWith(q0), sb = b.name.toLowerCase(Locale.US).startsWith(q0);
            if (sa != sb) return sa ? -1 : 1;
            return Integer.compare(a.name.length(), b.name.length());
        });
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    static String fetch(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", "VTLoutput_HQ");
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 8_000_000) throw new Exception("too large");
            }
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
