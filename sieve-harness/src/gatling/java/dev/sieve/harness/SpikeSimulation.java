package dev.sieve.harness;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays a live-event traffic spike: steady baseline, a ramp to PEAK_MULT times the baseline, a
 * hold, then recovery. Four request tiers arrive in a fixed mix. A 503 counts as a failed
 * request, so the Gatling report's success rate per request name is the availability per tier.
 *
 * <p>Environment: SIEVE_TARGET (default http://localhost:8080), SIEVE_BASE_RPS (100),
 * SIEVE_PEAK_MULT (6), SIEVE_HOLD_S (30).
 */
public class SpikeSimulation extends Simulation {

    private static final String TARGET = env("SIEVE_TARGET", "http://localhost:8080");
    private static final int BASE_RPS = Integer.parseInt(env("SIEVE_BASE_RPS", "100"));
    private static final int PEAK_MULT = Integer.parseInt(env("SIEVE_PEAK_MULT", "6"));

    private static final int WARMUP_S = 15;
    private static final int BASELINE_S = 20;
    private static final int RAMP_S = 15;
    private static final int HOLD_S = Integer.parseInt(env("SIEVE_HOLD_S", "30"));
    private static final int RAMPDOWN_S = 10;
    private static final int RECOVER_S = 20;

    private record Tier(String name, double share) {}

    private static final List<Tier> TIERS = List.of(
            new Tier("critical", 0.20),
            new Tier("degraded", 0.30),
            new Tier("best_effort", 0.30),
            new Tier("bulk", 0.20));

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static ScenarioBuilder scenarioFor(Tier t) {
        return scenario(t.name())
                .exec(http(t.name())
                        .get("/work")
                        .requestTimeout(Duration.ofSeconds(5))
                        .header("X-Sieve-Priority", t.name())
                        .check(status().is(200)));
    }

    private static List<OpenInjectionStep> profile(Tier t) {
        double base = BASE_RPS * t.share();
        double peak = base * PEAK_MULT;
        List<OpenInjectionStep> steps = new ArrayList<>();
        steps.add(constantUsersPerSec(base).during(WARMUP_S + BASELINE_S));
        steps.add(rampUsersPerSec(base).to(peak).during(RAMP_S));
        steps.add(constantUsersPerSec(peak).during(HOLD_S));
        steps.add(rampUsersPerSec(peak).to(base).during(RAMPDOWN_S));
        steps.add(constantUsersPerSec(base).during(RECOVER_S));
        return steps;
    }

    {
        HttpProtocolBuilder protocol = http.baseUrl(TARGET).shareConnections();
        setUp(TIERS.stream()
                        .map(t -> scenarioFor(t).injectOpen(profile(t)))
                        .toList())
                .protocols(protocol);
    }
}
