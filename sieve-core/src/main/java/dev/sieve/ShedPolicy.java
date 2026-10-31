package dev.sieve;

import java.util.ArrayList;
import java.util.List;

/** Combines several utilization signals: a request is shed if any signal says to shed it. */
public final class ShedPolicy {

    private record Binding(UtilizationSignal signal, ShedCurve curve) {}

    private final List<Binding> bindings;

    private ShedPolicy(List<Binding> bindings) {
        this.bindings = List.copyOf(bindings);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Highest shed probability across all signals for this tier. */
    public double probability(Priority priority) {
        double p = 0;
        for (Binding b : bindings) {
            p = Math.max(p, b.curve().probability(priority, b.signal().utilization()));
        }
        return p;
    }

    /** {@code roll} is a uniform random number in [0, 1). */
    public boolean shouldShed(Priority priority, double roll) {
        return roll < probability(priority);
    }

    public static final class Builder {
        private final List<Binding> bindings = new ArrayList<>();

        public Builder signal(UtilizationSignal signal, ShedCurve curve) {
            bindings.add(new Binding(signal, curve));
            return this;
        }

        public ShedPolicy build() {
            return new ShedPolicy(bindings);
        }
    }
}
