package dev.sieve.spring;

import dev.sieve.Priority;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration under {@code sieve.*}. */
@ConfigurationProperties("sieve")
public class SieveProperties {

    private boolean enabled = true;
    private String priorityHeader = Priority.HEADER;
    private Priority defaultPriority = Priority.BEST_EFFORT;

    private int initialLimit = 20;
    private int minLimit = 20;
    private int maxLimit = 200;
    private boolean windowed = true;
    /** "gradient2" (default) or "vegas". */
    private String limitAlgorithm = "gradient2";
    /**
     * "graded" (default) or "none": lower tiers give way earlier as the limit fills up (bulk at
     * 50% of the limit, best-effort 70%, degraded 85%), so the limiter orders the tiers by itself.
     */
    private String tiers = "graded";
    /** Critical requests always admitted even when the limit is full, up to this many in flight. */
    private int criticalFloor = 0;
    /** Window of the latency signals. Longer rides out brief stalls; shorter reacts faster. */
    private Duration signalWindow = Duration.ofSeconds(5);

    /** If set, shed lower tiers when too many requests are slower than this. */
    private Duration latencyTarget;
    /** If set, shed everything when too many requests are slower than this (near timeout). */
    private Duration latencyMax;
    /** If set, shed on system CPU. Value is the CPU fraction the autoscaler targets (0.5). */
    private Double cpuAutoscaleTarget;
    /**
     * "system" (default) reads whole-machine CPU, the same signal most autoscalers use, so shedding
     * lines up with scaling. "process" reads only this JVM, which is right on a shared machine
     * where other processes (a load generator, a noisy neighbour) would otherwise trigger shedding.
     */
    private String cpuScope = "system";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPriorityHeader() { return priorityHeader; }
    public void setPriorityHeader(String priorityHeader) { this.priorityHeader = priorityHeader; }
    public Priority getDefaultPriority() { return defaultPriority; }
    public void setDefaultPriority(Priority defaultPriority) { this.defaultPriority = defaultPriority; }
    public int getInitialLimit() { return initialLimit; }
    public void setInitialLimit(int initialLimit) { this.initialLimit = initialLimit; }
    public int getMinLimit() { return minLimit; }
    public void setMinLimit(int minLimit) { this.minLimit = minLimit; }
    public int getMaxLimit() { return maxLimit; }
    public void setMaxLimit(int maxLimit) { this.maxLimit = maxLimit; }
    public boolean isWindowed() { return windowed; }
    public void setWindowed(boolean windowed) { this.windowed = windowed; }
    public String getLimitAlgorithm() { return limitAlgorithm; }
    public void setLimitAlgorithm(String limitAlgorithm) { this.limitAlgorithm = limitAlgorithm; }
    public String getTiers() { return tiers; }
    public void setTiers(String tiers) { this.tiers = tiers; }
    public int getCriticalFloor() { return criticalFloor; }
    public void setCriticalFloor(int criticalFloor) { this.criticalFloor = criticalFloor; }
    public Duration getSignalWindow() { return signalWindow; }
    public void setSignalWindow(Duration signalWindow) { this.signalWindow = signalWindow; }
    public Duration getLatencyTarget() { return latencyTarget; }
    public void setLatencyTarget(Duration latencyTarget) { this.latencyTarget = latencyTarget; }
    public Duration getLatencyMax() { return latencyMax; }
    public void setLatencyMax(Duration latencyMax) { this.latencyMax = latencyMax; }
    public String getCpuScope() { return cpuScope; }
    public void setCpuScope(String cpuScope) { this.cpuScope = cpuScope; }
    public Double getCpuAutoscaleTarget() { return cpuAutoscaleTarget; }
    public void setCpuAutoscaleTarget(Double cpuAutoscaleTarget) { this.cpuAutoscaleTarget = cpuAutoscaleTarget; }
}
