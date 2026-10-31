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

    /** If set, shed lower tiers when too many requests are slower than this. */
    private Duration latencyTarget;
    /** If set, shed everything when too many requests are slower than this (near timeout). */
    private Duration latencyMax;
    /** If set, shed on system CPU. Value is the CPU fraction the autoscaler targets (0.5). */
    private Double cpuAutoscaleTarget;

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
    public Duration getLatencyTarget() { return latencyTarget; }
    public void setLatencyTarget(Duration latencyTarget) { this.latencyTarget = latencyTarget; }
    public Duration getLatencyMax() { return latencyMax; }
    public void setLatencyMax(Duration latencyMax) { this.latencyMax = latencyMax; }
    public Double getCpuAutoscaleTarget() { return cpuAutoscaleTarget; }
    public void setCpuAutoscaleTarget(Double cpuAutoscaleTarget) { this.cpuAutoscaleTarget = cpuAutoscaleTarget; }
}
