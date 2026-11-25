package fr.vtlo.usbaudiohq;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure EQ maths: RBJ biquad magnitude responses (PK / low shelf / high shelf) for AutoEq profiles
 * and a 10-band manual EQ, plus the plan handed to the DynamicsProcessing service: N log-spaced
 * bands (default 128) sampled from the exact response, and an automatic headroom (input gain) so
 * boosts can never clip - no limiter or dither is needed, which keeps the signal path transparent.
 */
final class Eq {
    static final int PK = 0, LS = 1, HS = 2;
    static final double FS = 96000.0;
    static final double[] MANUAL_FC = {31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final double MANUAL_Q = 1.41;
    static final double F_MIN = 20, F_MAX = 20000;

    private Eq() { }

    static final class Filter {
        final int type;
        final double fc, gain, q;
        Filter(int type, double fc, double gain, double q) { this.type = type; this.fc = fc; this.gain = gain; this.q = q; }
    }

    /** Magnitude (dB) of one filter at frequency f. */
    static double magDb(Filter f, double freq) {
        double A = Math.pow(10, f.gain / 40.0);
        double w0 = 2 * Math.PI * Math.min(f.fc, FS / 2.2) / FS;
        double cs = Math.cos(w0), sn = Math.sin(w0);
        double al = sn / (2 * f.q);
        double b0, b1, b2, a0, a1, a2;
        if (f.type == PK) {
            b0 = 1 + al * A; b1 = -2 * cs; b2 = 1 - al * A;
            a0 = 1 + al / A; a1 = -2 * cs; a2 = 1 - al / A;
        } else {
            double be = 2 * Math.sqrt(A) * al;
            if (f.type == LS) {
                b0 = A * ((A + 1) - (A - 1) * cs + be); b1 = 2 * A * ((A - 1) - (A + 1) * cs); b2 = A * ((A + 1) - (A - 1) * cs - be);
                a0 = (A + 1) + (A - 1) * cs + be; a1 = -2 * ((A - 1) + (A + 1) * cs); a2 = (A + 1) + (A - 1) * cs - be;
            } else {
                b0 = A * ((A + 1) + (A - 1) * cs + be); b1 = -2 * A * ((A - 1) + (A + 1) * cs); b2 = A * ((A + 1) + (A - 1) * cs - be);
                a0 = (A + 1) - (A - 1) * cs + be; a1 = 2 * ((A - 1) - (A + 1) * cs); a2 = (A + 1) - (A - 1) * cs - be;
            }
        }
        double w = 2 * Math.PI * freq / FS;
        double c1 = Math.cos(w), s1 = Math.sin(w), c2 = Math.cos(2 * w), s2 = Math.sin(2 * w);
        double nr = b0 + b1 * c1 + b2 * c2, ni = -(b1 * s1 + b2 * s2);
        double dr = a0 + a1 * c1 + a2 * c2, di = -(a1 * s1 + a2 * s2);
        double num = nr * nr + ni * ni, den = dr * dr + di * di;
        return 10 * Math.log10(Math.max(num, 1e-30) / Math.max(den, 1e-30));
    }

    static List<Filter> manualFilters(int[] tenths) {
        List<Filter> l = new ArrayList<>();
        for (int i = 0; i < MANUAL_FC.length; i++) {
            double g = (tenths != null && i < tenths.length ? tenths[i] : 0) / 10.0;
            if (g != 0) l.add(new Filter(PK, MANUAL_FC[i], g, MANUAL_Q));
        }
        return l;
    }

    static double sumDb(List<Filter> fs, double f) {
        double s = 0;
        for (Filter x : fs) s += magDb(x, f);
        return s;
    }

    static double peakDb(List<Filter> fs) {
        double p = -1e9;
        for (int i = 0; i <= 400; i++) p = Math.max(p, sumDb(fs, F_MIN * Math.pow(F_MAX / F_MIN, i / 400.0)));
        return p;
    }

    static final class Plan {
        double[] cutoff, gainDb;
        double inputGainDb, peakDb, headroomDb;
        int bands;
    }

    /**
     * @param autoProfile AutoEq profile or null
     * @param manualTenths 10 manual band gains in 0.1 dB
     * @param userPreDb extra preamp (dB)
     * @param autoHeadroom lower the input gain so the summed response never exceeds 0 dB
     */
    static Plan plan(AutoEq.Profile autoProfile, int[] manualTenths, double userPreDb, boolean autoHeadroom, int bands) {
        List<Filter> all = new ArrayList<>();
        double base = userPreDb;
        if (autoProfile != null) { all.addAll(autoProfile.filters); base += autoProfile.preamp; }
        all.addAll(manualFilters(manualTenths));
        Plan p = new Plan();
        p.bands = bands;
        p.peakDb = all.isEmpty() ? 0 : peakDb(all);
        double extra = 0;
        if (autoHeadroom) extra = Math.max(0, p.peakDb + base);
        p.headroomDb = extra;
        p.inputGainDb = base - extra;
        p.cutoff = new double[bands];
        p.gainDb = new double[bands];
        double ratio = Math.pow(F_MAX / F_MIN, 1.0 / bands);
        for (int i = 0; i < bands; i++) {
            double lo = F_MIN * Math.pow(ratio, i);
            double center = lo * Math.sqrt(ratio);
            p.cutoff[i] = lo * ratio;
            p.gainDb[i] = all.isEmpty() ? 0 : sumDb(all, center);
        }
        p.cutoff[bands - 1] = 24000;
        return p;
    }
}
