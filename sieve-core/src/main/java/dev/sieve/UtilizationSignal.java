package dev.sieve;

/**
 * A measure of how loaded the service is, normalized to [0, 1]. It must increase
 * monotonically with load for a {@link ShedCurve} to behave well.
 */
@FunctionalInterface
public interface UtilizationSignal {
    double utilization();
}
