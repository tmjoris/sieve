package dev.sieve;

import java.util.Locale;
import java.util.Optional;

/** Request priority, most important first. Lower ordinal = shed later. */
public enum Priority {
    CRITICAL,
    DEGRADED,
    BEST_EFFORT,
    BULK;

    private final String id = name().toLowerCase(Locale.ROOT);

    public static final String HEADER = "X-Sieve-Priority";

    /** Lower case name, computed once: the limiter looks it up on every request. */
    public String id() {
        return id;
    }

    public static Optional<Priority> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (Priority p : values()) {
            if (p.name().equals(v)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
