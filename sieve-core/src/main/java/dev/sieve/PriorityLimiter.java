package dev.sieve;

import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.limit.Gradient2Limit;
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

    /** Gradient2 with library defaults. */
    public static PriorityLimiter withGradient2(TierShares shares) {
        return new PriorityLimiter(Gradient2Limit.newBuilder().build(), shares);
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
