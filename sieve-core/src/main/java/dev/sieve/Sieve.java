package dev.sieve;

import com.netflix.concurrency.limits.Limiter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Entry point. For each request: first the shed curve (probabilistic, driven by utilization),
 * then the partitioned adaptive concurrency limit as a backstop.
 */
public final class Sieve {

    private final PriorityLimiter limiter;
    private final ShedPolicy policy;
    private final List<LatencySloSignal> latency;
    private final LongSupplier clock;
    private final DoubleSupplier random;

    private Sieve(Builder b) {
        this.limiter = b.limiter;
        this.policy = b.policy.build();
        this.latency = List.copyOf(b.latency);
        this.clock = b.clock;
        this.random = b.random;
    }

    public static Builder builder(PriorityLimiter limiter) {
        return new Builder(limiter);
    }

    public Admission admit(Priority priority) {
        if (policy.shouldShed(priority, random.getAsDouble())) {
            return new Admission.Shed(priority, Admission.Reason.UTILIZATION, retryAfter(priority));
        }
        Optional<Limiter.Listener> listener = limiter.acquire(priority);
        if (listener.isEmpty()) {
            return new Admission.Shed(
                    priority, Admission.Reason.CONCURRENCY_LIMIT, retryAfter(priority));
        }
        return new Admission.Admitted(new TrackedPermit(listener.get()));
    }

    /**
     * Lower tiers are told to stay away longer. Critical requests may retry at once: the client's
     * jittered backoff spreads them out, and Netflix has higher priorities retry more aggressively.
     */
    static Duration retryAfter(Priority priority) {
        return switch (priority) {
            case CRITICAL -> Duration.ZERO;
            case DEGRADED -> Duration.ofSeconds(2);
            case BEST_EFFORT -> Duration.ofSeconds(5);
            case BULK -> Duration.ofSeconds(10);
        };
    }

    private final class TrackedPermit implements Admission.Permit {
        private final Limiter.Listener listener;
        private final long startNanos = clock.getAsLong();

        TrackedPermit(Limiter.Listener listener) {
            this.listener = listener;
        }

        @Override
        public void success() {
            listener.onSuccess();
            long took = clock.getAsLong() - startNanos;
            latency.forEach(sig -> sig.record(took));
        }

        @Override
        public void dropped() {
            listener.onDropped();
            latency.forEach(sig -> sig.record(Long.MAX_VALUE));
        }

        @Override
        public void ignore() {
            listener.onIgnore();
        }
    }

    public static final class Builder {
        private final PriorityLimiter limiter;
        private final ShedPolicy.Builder policy = ShedPolicy.builder();
        private final List<LatencySloSignal> latency = new ArrayList<>();
        private LongSupplier clock = System::nanoTime;
        private DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();

        private Builder(PriorityLimiter limiter) {
            this.limiter = limiter;
        }

        public Builder utilization(UtilizationSignal signal, ShedCurve curve) {
            policy.signal(signal, curve);
            return this;
        }

        /**
         * Shed on the fraction of requests slower than a latency threshold; latencies are
         * recorded for you. Call it twice for Netflix's pair: a target threshold with
         * {@link ShedCurve#prioritized}, and a max (timeout) threshold with
         * {@link ShedCurve#unprioritized}.
         */
        public Builder latencySlo(LatencySloSignal signal, ShedCurve curve) {
            this.latency.add(signal);
            policy.signal(signal, curve);
            return this;
        }

        Builder clock(LongSupplier clock) {
            this.clock = clock;
            return this;
        }

        Builder random(DoubleSupplier random) {
            this.random = random;
            return this;
        }

        public Sieve build() {
            return new Sieve(this);
        }
    }
}
