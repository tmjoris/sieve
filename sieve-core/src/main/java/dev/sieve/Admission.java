package dev.sieve;

import java.time.Duration;

/** Outcome of asking Sieve whether a request may run. */
public sealed interface Admission {

    /** Request admitted. Call exactly one of the permit's methods when it finishes. */
    record Admitted(Permit permit) implements Admission {}

    /** Request shed. {@code retryAfter} is a hint for the client. */
    record Shed(Priority priority, Reason reason, Duration retryAfter) implements Admission {}

    enum Reason {
        /** The shed curve dropped it because a utilization signal (CPU, latency) is high. */
        UTILIZATION,
        /** The adaptive concurrency limit has no room for this tier. */
        CONCURRENCY_LIMIT
    }

    interface Permit {
        /** Request finished normally; its latency feeds the adaptive limit and SLO signal. */
        void success();

        /** Request failed or timed out in a way that signals overload. */
        void dropped();

        /** Request finished but should not influence the limit (e.g. client error). */
        void ignore();
    }
}
