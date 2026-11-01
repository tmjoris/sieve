package dev.sieve.spring;

import dev.sieve.CpuSignal;
import dev.sieve.LatencySloSignal;
import dev.sieve.PriorityLimiter;
import dev.sieve.ShedCurve;
import dev.sieve.Sieve;
import dev.sieve.TierShares;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/** Registers a {@link Sieve} and the {@link SieveFilter} in front of every servlet request. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "sieve", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(SieveProperties.class)
public class SieveAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Sieve sieve(SieveProperties p) {
        TierShares shares = TierShares.criticalFirst();
        PriorityLimiter limiter = p.isWindowed()
                ? PriorityLimiter.withWindowedGradient2(
                        shares, p.getInitialLimit(), p.getMinLimit(), p.getMaxLimit())
                : PriorityLimiter.withGradient2(
                        shares, p.getInitialLimit(), p.getMinLimit(), p.getMaxLimit());
        Sieve.Builder b = Sieve.builder(limiter);
        if (p.getLatencyTarget() != null) {
            b.latencySlo(LatencySloSignal.of(p.getLatencyTarget()), ShedCurve.prioritized(0.10, 0.40));
        }
        if (p.getLatencyMax() != null) {
            b.latencySlo(LatencySloSignal.of(p.getLatencyMax()), ShedCurve.unprioritized(0.10, 0.40));
        }
        if (p.getCpuAutoscaleTarget() != null) {
            CpuSignal cpu = "process".equalsIgnoreCase(p.getCpuScope())
                    ? CpuSignal.process() : CpuSignal.system();
            b.utilization(cpu, ShedCurve.cpu(p.getCpuAutoscaleTarget()));
        }
        return b.build();
    }

    @Bean
    public FilterRegistrationBean<Filter> sieveFilter(Sieve sieve, SieveProperties p) {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>(new SieveFilter(sieve,
                SieveFilter.fromHeader(p.getPriorityHeader(), p.getDefaultPriority())));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }
}
