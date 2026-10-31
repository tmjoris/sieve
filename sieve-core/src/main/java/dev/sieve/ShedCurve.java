package dev.sieve;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps (utilization, priority) to the probability that a request is shed. Each tier has a ramp:
 * zero below {@code start}, one at or above {@code full}, linear in between. A lower tier is
 * never shed less than a higher one. The {@link #bands} presets go further and fully shed a lower
 * tier before the next one up starts, which is what Netflix describes for prioritized shedding.
 */
public final class ShedCurve {

    public record Ramp(double start, double full) {
        public Ramp {
            if (!(start >= 0 && start < full && full <= 1)) {
                throw new IllegalArgumentException(
                        "ramp needs 0 <= start < full <= 1, got " + start + ".." + full);
            }
        }

        double probability(double utilization) {
            if (utilization <= start) {
                return 0;
            }
            if (utilization >= full) {
                return 1;
            }
            return (utilization - start) / (full - start);
        }
    }

    private final Map<Priority, Ramp> ramps;

    /**
     * A tier without a ramp is never shed by this curve. A lower tier may never be shed less than
     * a higher tier, so a tier with a ramp needs every lower tier to have one that starts and
     * finishes no later.
     */
    public ShedCurve(Map<Priority, Ramp> ramps) {
        EnumMap<Priority, Ramp> m = new EnumMap<>(Priority.class);
        ramps.forEach(m::put);
        Priority[] order = Priority.values();
        for (int i = 0; i < order.length; i++) {
            Ramp higher = m.get(order[i]);
            if (higher == null) {
                continue;
            }
            for (int j = i + 1; j < order.length; j++) {
                Ramp lower = m.get(order[j]);
                if (lower == null) {
                    throw new IllegalArgumentException(order[j] + " is never shed but "
                            + order[i] + " is");
                }
                if (higher.start() < lower.start() || higher.full() < lower.full()) {
                    throw new IllegalArgumentException(order[j] + " must be shed at least as "
                            + "much as " + order[i] + " at every utilization");
                }
            }
        }
        this.ramps = m;
    }

    /** Probability in [0, 1] that a request of this priority is shed at this utilization. */
    public double probability(Priority priority, double utilization) {
        Ramp r = ramps.get(priority);
        return r == null ? 0 : r.probability(utilization);
    }

    /** The tier's ramp, or null if this curve never sheds it. */
    public Ramp ramp(Priority priority) {
        return ramps.get(priority);
    }

    /**
     * Three non-critical tiers share consecutive equal bands from {@code lo} to
     * {@code criticalStart}, bulk first. Critical ramps from {@code criticalStart} to {@code hi}.
     */
    public static ShedCurve bands(double lo, double criticalStart, double hi) {
        double w = (criticalStart - lo) / 3;
        Map<Priority, Ramp> m = new EnumMap<>(Priority.class);
        m.put(Priority.BULK, new Ramp(lo, lo + w));
        m.put(Priority.BEST_EFFORT, new Ramp(lo + w, lo + 2 * w));
        m.put(Priority.DEGRADED, new Ramp(lo + 2 * w, criticalStart));
        m.put(Priority.CRITICAL, new Ramp(criticalStart, hi));
        return new ShedCurve(m);
    }

    /**
     * CPU curve for a service that autoscales on CPU at {@code autoscaleTarget}. Shedding starts
     * 10 points above the target so autoscaling still gets its signal, non-critical traffic is
     * gone by 80%, and critical traffic is shed between 80% and 95%. These follow the
     * example thresholds in Netflix's talk (60% non-critical, 80% critical for a 50% target).
     */
    public static ShedCurve cpu(double autoscaleTarget) {
        double lo = autoscaleTarget + 0.10;
        if (lo >= 0.80) {
            throw new IllegalArgumentException(
                    "autoscale target too high for the default CPU curve: " + autoscaleTarget);
        }
        return bands(lo, 0.80, 0.95);
    }

    /**
     * Curve for a latency-SLO utilization (fraction of requests slower than the SLO). Non-critical
     * shedding starts at 10% of requests over SLO and critical at 40%, as in Netflix's example.
     */
    public static ShedCurve latencySlo() {
        return bands(0.10, 0.40, 0.80);
    }

    /**
     * Prioritized shedding that never touches critical traffic: the three lower tiers share
     * consecutive bands between {@code lo} and {@code hi}, bulk first. Use this with a "target"
     * utilization, such as the fraction of requests over the latency target.
     */
    public static ShedCurve prioritized(double lo, double hi) {
        double w = (hi - lo) / 3;
        Map<Priority, Ramp> m = new EnumMap<>(Priority.class);
        m.put(Priority.BULK, new Ramp(lo, lo + w));
        m.put(Priority.BEST_EFFORT, new Ramp(lo + w, lo + 2 * w));
        m.put(Priority.DEGRADED, new Ramp(lo + 2 * w, hi));
        return new ShedCurve(m);
    }

    /**
     * Every tier, critical included, is shed with the same probability between {@code lo} and
     * {@code hi}. Use this with a "max" utilization, such as the fraction of requests over the
     * timeout, where the service is failing and priorities no longer help.
     */
    public static ShedCurve unprioritized(double lo, double hi) {
        Map<Priority, Ramp> m = new EnumMap<>(Priority.class);
        for (Priority p : Priority.values()) {
            m.put(p, new Ramp(lo, hi));
        }
        return new ShedCurve(m);
    }
}
