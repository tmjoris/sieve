package dev.sieve.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sieve.Priority;
import dev.sieve.Sieve;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

class SieveAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SieveAutoConfiguration.class));

    @Test
    void registersSieveAndFilterByDefault() {
        runner.run(ctx -> {
            assertTrue(ctx.containsBean("sieve"));
            assertTrue(ctx.containsBean("sieveFilter"));
        });
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("sieve.enabled=false").run(ctx -> {
            assertEquals(0, ctx.getBeansOfType(Sieve.class).size());
            assertEquals(0, ctx.getBeansOfType(FilterRegistrationBean.class).size());
        });
    }

    @Test
    void bindsLatencyAndLimitProperties() {
        runner.withPropertyValues("sieve.latency-target=60ms", "sieve.latency-max=300ms",
                "sieve.initial-limit=10", "sieve.min-limit=4", "sieve.default-priority=bulk")
                .run(ctx -> {
                    SieveProperties p = ctx.getBean(SieveProperties.class);
                    assertEquals(60, p.getLatencyTarget().toMillis());
                    assertEquals(300, p.getLatencyMax().toMillis());
                    assertEquals(10, p.getInitialLimit());
                    assertEquals(Priority.BULK, p.getDefaultPriority());
                    assertTrue(ctx.getBean(Sieve.class) != null);
                });
    }

    @Test
    void userDefinedSieveBackedOff() {
        runner.withBean("sieve", Sieve.class, () -> Sieve.builder(
                        dev.sieve.PriorityLimiter.withGradient2(dev.sieve.TierShares.criticalFirst()))
                .build())
                .run(ctx -> assertEquals(1, ctx.getBeansOfType(Sieve.class).size()));
    }

    @Test
    void filterBeanIsAServletFilterRegistration() {
        runner.run(ctx -> assertTrue(
                ctx.getBean("sieveFilter", FilterRegistrationBean.class).getFilter() instanceof Filter));
    }

    @Test
    void cpuScopeDefaultsToSystemAndCanBeProcess() {
        runner.run(ctx -> assertEquals("system", ctx.getBean(SieveProperties.class).getCpuScope()));
        runner.withPropertyValues("sieve.cpu-scope=process", "sieve.cpu-autoscale-target=0.5")
                .run(ctx -> {
                    assertEquals("process", ctx.getBean(SieveProperties.class).getCpuScope());
                    assertTrue(ctx.getBean(Sieve.class) != null);
                });
    }
}
