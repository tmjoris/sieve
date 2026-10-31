package dev.sieve;

import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.limit.Gradient2Limit;
import com.netflix.concurrency.limits.limit.WindowedLimit;
import com.netflix.concurrency.limits.limiter.AbstractPartitionedLimiter;
import java.util.Optional;

/**
 * Per-tier adaptive concurrency limiting. Wraps Netflix's partitioned limiter: one adaptive
 * limit for the whole service, split into guaranteed shares per priority tier.
 */
public final class PriorityLimiter {

    private final Limiter<Priority> delegate;

    public PriorityLimiter(Limit limit, TierShares shares) {
        Builder builder = new Builder();
        builder.limit(limit);
        for (Priority p : Priority.values()) {
            builder.partition(p.id(), shares.shares().get(p));
        }
        this.delegate = builder.build();
    }

    /**
     * Gradient2 with library defaults (initial and minimum limit of 20, maximum 200). The minimum
     * is a floor: a service whose real concurrency is below it will queue up to that many
     * requests, so size it for the service.
     */
    public static PriorityLimiter withGradient2(TierShares shares) {
        return new PriorityLimiter(Gradient2Limit.newBuilder().build(), shares);
    }

    /** Gradient2 with explicit starting, minimum and maximum concurrency. */
    public static PriorityLimiter withGradient2(
            TierShares shares, int initialLimit, int minLimit, int maxLimit) {
        return new PriorityLimiter(
                Gradient2Limit.newBuilder()
                        .initialLimit(initialLimit)
                        .minLimit(minLimit)
                        .maxConcurrency(maxLimit)
                        .build(),
                shares);
    }

    /**
     * Gradient2 wrapped in WindowedLimit, as in Netflix's gRPC example. The wrapper hands Gradient2
     * one averaged sample per window (at least one second, at least 10 requests) instead of one
     * per request, which makes the limit much less jumpy.
     */
    public static PriorityLimiter withWindowedGradient2(
            TierShares shares, int initialLimit, int minLimit, int maxLimit) {
        Gradient2Limit gradient = Gradient2Limit.newBuilder()
                .initialLimit(initialLimit)
                .minLimit(minLimit)
                .maxConcurrency(maxLimit)
                .build();
        return new PriorityLimiter(WindowedLimit.newBuilder().build(gradient), shares);
    }

    public Optional<Limiter.Listener> acquire(Priority priority) {
        return delegate.acquire(priority);
    }

    private static final class Builder
            extends AbstractPartitionedLimiter.Builder<Builder, Priority> {
        Builder() {
            partitionResolver(Priority::id);
        }

        @Override
        protected Builder self() {
            return this;
        }
    }
}
