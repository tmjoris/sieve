package dev.sieve;

import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.limit.Gradient2Limit;
import com.netflix.concurrency.limits.limit.VegasLimit;
import com.netflix.concurrency.limits.limit.WindowedLimit;

/** Ready made adaptive limits, each wrapped in {@link WindowedLimit} like Netflix's gRPC example. */
public final class Limits {

    private Limits() {}

    /** Gradient2: compares a long and a short latency average to spot queueing. */
    public static Limit windowedGradient2(int initial, int min, int max) {
        return WindowedLimit.newBuilder().build(Gradient2Limit.newBuilder()
                .initialLimit(initial)
                .minLimit(min)
                .maxConcurrency(max)
                .build());
    }

    /**
     * Vegas: estimates the queue as {@code limit * (1 - minRtt / rtt)} and grows or shrinks the
     * limit by one. Netflix's README recommends it on servers. It has no minimum setting.
     */
    public static Limit windowedVegas(int initial, int max) {
        return WindowedLimit.newBuilder().build(VegasLimit.newBuilder()
                .initialLimit(initial)
                .maxConcurrency(max)
                .build());
    }
}
