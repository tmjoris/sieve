package dev.sieve;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps (utilization, priority) to the probability that a request is shed. Each tier has a ramp:
 * zero below {@code start}, one at or above {@code full}, linear in between. Ramps never overlap
 * in the wrong direction: a lower tier is shed with probability 1 before a higher tier starts
 * to be shed.
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

    public ShedCurve(Map<Priority, Ramp> ramps) {
        EnumMap<Priority, Ramp> m = new EnumMap<>(Priority.class);
        for (Priority p : Priority.values()) {
            Ramp r = ramps.get(p);
            if (r == null) {
                throw new IllegalArgumentException("missing ramp for " + p);
            }
            m.put(p, r);
        }
        Priority[] order = Priority.values();
        for (int i = 0; i < order.length - 1; i++) {
            Ramp higher = m.get(order[i]);
            Ramp lower = m.get(order[i + 1]);
            if (higher.start() < lower.full()) {
                throw new IllegalArgumentException(order[i + 1] + " must be fully shed (at "
                        + lower.full() + ") before " + order[i] + " starts (at "
                        + higher.start() + ")");
            }
        }
        this.ramps = m;
    }

    /** Probability in [0, 1] that a request of this priority is shed at this utilization. */
    public double probability(Priority priority, double utilization) {
        return ramps.get(priority).probability(utilization);
    }

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
}
