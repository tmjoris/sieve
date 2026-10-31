package dev.sieve;

import java.util.EnumMap;
import java.util.Map;

/**
 * Fraction of the adaptive concurrency limit guaranteed to each tier. A tier may borrow
 * unused capacity from the others, but under saturation it is held to its share, so
 * lower tiers lose capacity first.
 */
public record TierShares(Map<Priority, Double> shares) {

    public TierShares {
        double sum = 0;
        for (Priority p : Priority.values()) {
            Double s = shares.get(p);
            if (s == null || s <= 0 || s > 1) {
                throw new IllegalArgumentException("share for " + p + " must be in (0,1], got " + s);
            }
            sum += s;
        }
        if (sum > 1.0 + 1e-9) {
            throw new IllegalArgumentException("shares must sum to <= 1.0, got " + sum);
        }
        shares = Map.copyOf(shares);
    }

    public static TierShares defaults() {
        Map<Priority, Double> m = new EnumMap<>(Priority.class);
        m.put(Priority.CRITICAL, 0.55);
        m.put(Priority.DEGRADED, 0.20);
        m.put(Priority.BEST_EFFORT, 0.15);
        m.put(Priority.BULK, 0.10);
        return new TierShares(m);
    }
}
