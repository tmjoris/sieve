package dev.sieve;

import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.limit.Gradient2Limit;
import com.netflix.concurrency.limits.limit.WindowedLimit;
import com.netflix.concurrency.limits.limiter.AbstractPartitionedLimiter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-tier adaptive concurrency limiting. Wraps Netflix's partitioned limiter: one adaptive
 * limit for the whole service, split into guaranteed shares per priority tier. A
 * {@link TierPolicy} can add graded thresholds (lower tiers give way earlier) and a reserved floor
 * for critical requests.
 */
public final class PriorityLimiter {

    private final Tiered delegate;

    public PriorityLimiter(Limit limit, TierShares shares) {
        this(limit, shares, TierPolicy.none());
    }

    public PriorityLimiter(Limit limit, TierShares shares, TierPolicy policy) {
        Builder builder = new Builder();
        builder.limit(limit);
        for (Priority p : Priority.values()) {
            builder.partition(p.id(), shares.shares().get(p));
        }
        this.delegate = new Tiered(builder, policy);
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

    /** The current adaptive limit. */
    public int limit() {
        return delegate.getLimit();
    }

    /** Requests admitted through the limiter and not yet finished. */
    public int inflight() {
        return delegate.getInflight();
    }

    /**
     * The partitioned limiter plus {@link TierPolicy}. It subclasses Netflix's class so it can read
     * the limit and the in flight count the library already maintains.
     */
    private static final class Tiered extends AbstractPartitionedLimiter<Priority> {
        private final TierPolicy policy;
        private final AtomicInteger floorInflight = new AtomicInteger();

        Tiered(Builder builder, TierPolicy policy) {
            super(builder);
            this.policy = policy;
        }

        @Override
        public Optional<Limiter.Listener> acquire(Priority priority) {
            double fraction = policy.threshold(priority);
            if (fraction < 1.0
                    && getInflight() >= Math.max(1, (int) Math.ceil(fraction * getLimit()))) {
                return createRejectedListener();
            }
            Optional<Limiter.Listener> admitted = super.acquire(priority);
            if (admitted.isPresent() || priority != Priority.CRITICAL
                    || policy.criticalFloor() == 0) {
                return admitted;
            }
            // The limit is full. Critical may still use its reserved floor.
            if (floorInflight.incrementAndGet() <= policy.criticalFloor()) {
                return Optional.of(new Limiter.Listener() {
                    @Override public void onSuccess() { floorInflight.decrementAndGet(); }
                    @Override public void onIgnore() { floorInflight.decrementAndGet(); }
                    @Override public void onDropped() { floorInflight.decrementAndGet(); }
                });
            }
            floorInflight.decrementAndGet();
            return admitted;
        }
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
