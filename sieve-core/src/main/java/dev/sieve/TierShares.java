package dev.sieve;

import java.util.EnumMap;
import java.util.Map;

/**
 * Fraction of the adaptive concurrency limit guaranteed to each tier. A tier may borrow
 * unused capacity from the others, but under saturation it is held to its share, so
 * lower tiers lose capacity first.
 *
 * <p>A share of 0 is valid: the underlying limiter still gives every partition at least one
 * slot, so a zero-share tier can only use spare capacity plus a single guaranteed slot.
 */
public record TierShares(Map<Priority, Double> shares) {

    public TierShares {
        double sum = 0;
        for (Priority p : Priority.values()) {
            Double s = shares.get(p);
            if (s == null || s < 0 || s > 1) {
                throw new IllegalArgumentException("share for " + p + " must be in [0,1], got " + s);
            }
            sum += s;
        }
        if (sum > 1.0 + 1e-9) {
            throw new IllegalArgumentException("shares must sum to <= 1.0, got " + sum);
        }
        shares = Map.copyOf(shares);
    }

    /**
     * Netflix's Play API setup generalized to four tiers: critical is guaranteed the whole limit
     * and every other tier only uses spare capacity.
     */
    public static TierShares criticalFirst() {
        return of(1.0, 0.0, 0.0, 0.0);
    }

    /** A balanced split that reserves some capacity for each lower tier. */
    public static TierShares balanced() {
        return of(0.55, 0.20, 0.15, 0.10);
    }

    public static TierShares of(double critical, double degraded, double bestEffort, double bulk) {
        Map<Priority, Double> m = new EnumMap<>(Priority.class);
        m.put(Priority.CRITICAL, critical);
        m.put(Priority.DEGRADED, degraded);
        m.put(Priority.BEST_EFFORT, bestEffort);
        m.put(Priority.BULK, bulk);
        return new TierShares(m);
    }

    public static TierShares defaults() {
        return criticalFirst();
    }
}
