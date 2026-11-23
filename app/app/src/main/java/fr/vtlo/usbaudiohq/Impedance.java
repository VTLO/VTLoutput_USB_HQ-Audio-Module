package fr.vtlo.usbaudiohq;

/**
 * Output-impedance model: the DAC/amp output impedance (Zo) and the headphone impedance (Zh) form
 * a voltage divider. Pure maths, no Android dependencies. The impedance curves per headphone type
 * are illustrative shapes, not measurements of any specific model.
 */
final class Impedance {
    static final String[] TYPES = {"Dynamic", "Planar", "BA / multi-driver"};
    static final double F_MIN = 20, F_MAX = 20000;

    private Impedance() { }

    static double bump(double f, double fc, double widthOct) {
        double x = Math.log(f / fc) / Math.log(2);
        return Math.exp(-x * x / (2 * widthOct * widthOct));
    }

    /** Headphone impedance at frequency f (Hz) for a nominal impedance zh. */
    static double zAt(int type, double zh, double f) {
        switch (type) {
            case 0: return zh * (1 + 1.5 * bump(f, 80, 0.4));                       // bass resonance
            case 2: return zh * (0.7 + 1.6 * bump(f, 3000, 0.35) + 0.5 * bump(f, 9000, 0.3)); // crossover/driver peaks
            default: return zh;                                                      // planar: ~flat
        }
    }

    /** Level change (dB) caused by source impedance zo into a load z. */
    static double lossDb(double zo, double z) {
        return 20 * Math.log10(z / (z + zo));
    }

    static double damping(double zo, double zh) {
        return zo <= 0 ? Double.POSITIVE_INFINITY : zh / zo;
    }

    /** Power (mW) delivered into zh from an ideal source of vrms volts with series zo. */
    static double powerMw(double vrms, double zo, double zh) {
        return vrms * vrms * zh / ((zh + zo) * (zh + zo)) * 1000.0;
    }

    static double spl(double sensDbPerMw, double powerMw) {
        return sensDbPerMw + 10 * Math.log10(Math.max(powerMw, 1e-9));
    }

    static final class Result {
        double lossNomDb, damping, powerMw, splDb, rippleDb, zMin, zMax, maxZoRule;
        int level; // 0 good, 1 caution, 2 poor
        String verdict;
    }

    static Result compute(double zo, double zh, double sens, double vrms, int type) {
        Result r = new Result();
        r.lossNomDb = lossDb(zo, zh);
        r.damping = damping(zo, zh);
        r.powerMw = powerMw(vrms, zo, zh);
        r.splDb = spl(sens, r.powerMw);
        r.maxZoRule = zh / 8.0;
        double lo = 1e9, hi = -1e9, zl = 1e9, zh2 = -1e9;
        for (int i = 0; i <= 200; i++) {
            double f = F_MIN * Math.pow(F_MAX / F_MIN, i / 200.0);
            double z = zAt(type, zh, f);
            double d = lossDb(zo, z);
            lo = Math.min(lo, d); hi = Math.max(hi, d);
            zl = Math.min(zl, z); zh2 = Math.max(zh2, z);
        }
        r.rippleDb = hi - lo;
        r.zMin = zl; r.zMax = zh2;
        if (r.damping < 2 || r.rippleDb >= 1.0) r.level = 2;
        else if (r.damping < 8 || r.rippleDb >= 0.3) r.level = 1;
        else r.level = 0;
        String[] head = {"Good match", "Audible risk", "Poor match"};
        StringBuilder sb = new StringBuilder(head[r.level]).append(": ");
        sb.append(String.format(java.util.Locale.US, "damping %.1f, response varies %.2f dB. ", Math.min(r.damping, 999), r.rippleDb));
        if (r.level > 0) sb.append(String.format(java.util.Locale.US, "Aim for an output impedance under %.1f ohm (Zh/8). ", r.maxZoRule));
        if (r.splDb < 95) sb.append("Low headroom: max level may be too quiet.");
        else if (r.splDb > 125) sb.append("Very high max level: keep the volume low.");
        r.verdict = sb.toString().trim();
        return r;
    }
}
