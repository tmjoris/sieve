package dev.sieve;

import java.util.Arrays;

/**
 * Extra admission rules layered on the shared concurrency limit.
 *
 * <p><b>Thresholds.</b> A tier is refused once the number of requests in flight reaches its
 * fraction of the current limit. With the graded defaults, bulk gives way at 50% of the limit,
 * best-effort at 70%, degraded at 85% and critical never, so lower tiers are shed in order by the
 * limiter alone, with no latency or CPU signal to tune.
 *
 * <p><b>Critical floor.</b> A number of critical requests that are always admitted even when the
 * limit is full. An adaptive limit can shrink under noisy latency to fewer slots than critical
 * traffic needs in a burst; the floor keeps those requests from being rejected. The service may
 * run up to {@code criticalFloor} requests above its limit.
 */
public record TierPolicy(double[] thresholds, int criticalFloor) {

    public TierPolicy {
        if (thresholds.length != Priority.values().length) {
            throw new IllegalArgumentException("need one threshold per tier");
        }
        double prev = 1.0;
        for (int i = 0; i < thresholds.length; i++) {
            double t = thresholds[i];
            if (!(t > 0 && t <= 1.0)) {
                throw new IllegalArgumentException("threshold for " + Priority.values()[i]
                        + " must be in (0, 1], got " + t);
            }
            if (i == 0 && t != 1.0) {
                throw new IllegalArgumentException("critical must keep a threshold of 1.0");
            }
            if (t > prev) {
                throw new IllegalArgumentException("a lower tier may not have a higher threshold");
            }
            prev = t;
        }
        if (criticalFloor < 0) {
            throw new IllegalArgumentException("criticalFloor must be >= 0");
        }
        thresholds = thresholds.clone();
    }

    @Override
    public double[] thresholds() {
        return thresholds.clone();
    }

    double threshold(Priority p) {
        return thresholds[p.ordinal()];
    }

    /** No extra rules: only the partitioned limit applies. */
    public static TierPolicy none() {
        return new TierPolicy(new double[] {1, 1, 1, 1}, 0);
    }

    /** Critical 100%, degraded 85%, best-effort 70%, bulk 50% of the limit. */
    public static TierPolicy graded() {
        return new TierPolicy(new double[] {1.0, 0.85, 0.70, 0.50}, 0);
    }

    public TierPolicy withCriticalFloor(int floor) {
        return new TierPolicy(thresholds, floor);
    }

    @Override
    public String toString() {
        return "TierPolicy" + Arrays.toString(thresholds) + " floor=" + criticalFloor;
    }
}
