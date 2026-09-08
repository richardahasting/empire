package org.hastingtx.empire.engine.update;

import java.util.SplittableRandom;

/** One stream per (step, seed). String.hashCode is specified by the JLS, so this is stable. */
public final class Rng {
    private Rng() {}

    public static SplittableRandom stream(String step, long seed) {
        long h = seed * 0x9E3779B97F4A7C15L + (long) step.hashCode() * 0xC2B2AE3D27D4EB4FL;
        return new SplittableRandom(h);
    }

    /** Triangular sample from [min, mode, max]. */
    public static double triangular(SplittableRandom r, double min, double mode, double max) {
        if (max == min) return min;
        double u = r.nextDouble();
        double f = (mode - min) / (max - min);
        return u < f ? min + Math.sqrt(u * (max - min) * (mode - min))
                     : max - Math.sqrt((1 - u) * (max - min) * (max - mode));
    }
}
