# Sieve

Prioritized load shedding for Java 21 services, built on Netflix's
[`concurrency-limits`](https://github.com/Netflix/concurrency-limits).

Work in progress. Numbers will be added here only once they are measured.

## Modules

| Module | What it is |
|---|---|
| `sieve-core` | The library: priorities, shed curves, utilization signals, the partitioned limiter, `Sieve`, and the client `RetryPolicy`. No framework dependencies. |
| `sieve-spring` | Servlet filter and Spring Boot auto-configuration. |
| `sieve-grpc` | gRPC server interceptor. |
| `sieve-demo` | Demo backend with a fixed capacity, a load probe, and the auto-tuner. |
| `sieve-harness` | Gatling simulation that replays a traffic spike. |
| `monitoring/` | Prometheus and Grafana (docker compose) with a provisioned dashboard. |

## Using it

### Plain Java

```java
Sieve sieve = Sieve.builder(PriorityLimiter.withWindowedGradient2(
                TierShares.criticalFirst(), /*initial*/ 10, /*min*/ 4, /*max*/ 200))
        // lower tiers shed as requests pass the 60 ms target, never critical
        .latencySlo(LatencySloSignal.of(Duration.ofMillis(60)), ShedCurve.prioritized(0.10, 0.40))
        // everything shed as requests approach the 300 ms timeout
        .latencySlo(LatencySloSignal.of(Duration.ofMillis(300)), ShedCurve.unprioritized(0.10, 0.40))
        // CPU-bound service that autoscales at 50% CPU
        .utilization(CpuSignal.system(), ShedCurve.cpu(0.50))
        .build();

switch (sieve.admit(priority)) {
    case Admission.Shed s -> respond503(s.retryAfter());
    case Admission.Admitted a -> {
        try { handle(); a.permit().success(); }
        catch (Exception e) { a.permit().dropped(); throw e; }
    }
}
```

### Spring Boot

Add `sieve-spring` and configure it; the filter runs before everything else and answers `503`
with `Retry-After` for shed requests.

```yaml
sieve:
  initial-limit: 10
  min-limit: 4
  latency-target: 60ms
  latency-max: 300ms
  cpu-autoscale-target: 0.5     # optional
  default-priority: best_effort
```

Clients send `X-Sieve-Priority: critical|degraded|best_effort|bulk`. Inside a request,
`SievePriorityContext.downgrade(...)` lowers the priority for outgoing calls; it can never raise it.

### gRPC

```java
ServerInterceptors.intercept(service, new SieveServerInterceptor(sieve, Priority.BEST_EFFORT));
```

Shed calls close with `UNAVAILABLE` and a `retry-after` trailer. The priority comes from the
`x-sieve-priority` metadata key.

### Clients: retry budget

`RetryPolicy` is the client half. It retries at most twice, with full-jitter exponential backoff,
never sooner than the server's `Retry-After`, and gives up when that hint is longer than it is
willing to wait. A budget tracks retries per first attempt; as the ratio climbs, low-priority
retries are dropped first. Bulk and best-effort are told to stay away for 10 s and 5 s, so they
are not retried at all.

## Requirements

- JDK 21
- Nothing else to install. The Gradle wrapper downloads Gradle on first use.

## Build and test

```bash
git clone https://github.com/tmjoris/sieve.git
cd sieve
./gradlew build
```

## Run the demo

The demo is a small HTTP server with a simulated backend that can serve about 200 requests per
second (4 workers, 20 ms per request). `LoadProbe` sends load at a fixed rate with a mix of
20% critical, 30% degraded, 30% best-effort and 20% bulk, and prints success rate and latency
per tier.

```bash
./gradlew :sieve-demo:installDist
BIN=sieve-demo/build/install/sieve-demo/bin/sieve-demo
CP="sieve-demo/build/install/sieve-demo/lib/*"

# The first phase doubles as JVM warm-up, so ignore it (see "Known issues").
RATES=100,100,200,400,800

# 1. Baseline, no protection
$BIN noshed 8080 &
java -cp "$CP" dev.sieve.demo.LoadProbe 8080 $RATES 6
kill %1

# 2. Adaptive concurrency limit only
$BIN limiter 8080 &
java -cp "$CP" dev.sieve.demo.LoadProbe 8080 $RATES 6
kill %1

# 3. Shed curve on latency-SLO utilization, plus the limiter as backstop
$BIN shed 8080 &
java -cp "$CP" dev.sieve.demo.LoadProbe 8080 $RATES 6
kill %1
```

`LoadProbe` arguments are the port, a comma-separated list of request rates (one phase each),
and the seconds per phase. Clients choose a tier with the `X-Sieve-Priority` header
(`critical`, `degraded`, `best_effort`, `bulk`); requests without it are treated as best-effort.
Shed requests get a `503` with `Retry-After: 1`.

## Results (demo backend, one machine)

Spike profile (`scripts/spike.sh`): 100 rps baseline, ramp to 600 rps (6x), hold 30 s, recover.
The demo backend serves about 200 rps, so the peak is about 3x its capacity. Success is the
client view (a 503 or a timeout is a failure). Latency is the p99 of requests that were served,
estimated from the server's histogram.

| Mode | Critical | Degraded | Best-effort | Bulk | Critical p99 |
|---|---|---|---|---|---|
| No protection (1 run) | 20% | 20% | 20% | 20% | 5 s+ (timeouts) |
| Shed curves + windowed limiter (3 runs) | 99.8-100% | 47-49% | 21-22% | 18-19% | 116-234 ms |

Caveats: the backend is simulated (sleep-based, not CPU-bound), the load generator and server
share one machine, and the baseline is a single run. The CPU signal has not been exercised
against a CPU-bound workload yet.

## Known issues

- **Cold start.** During JVM warm-up the first requests are slow, so the lower tiers are shed
  heavily for a few seconds (critical is not). If a service only ever receives low-priority
  traffic, a burst of slow requests can keep the signal high until its 5 s window expires,
  because shed requests produce no fresh samples.
- Results vary between runs; one run proves little.

## Design

Requests carry one of four priorities: `CRITICAL`, `DEGRADED`, `BEST_EFFORT`, `BULK`.
Two mechanisms work together:

1. **Utilization-based shedding.** A per-tier shed probability, driven by CPU or by the
   fraction of requests slower than a latency SLO. Shedding starts above the autoscaling
   target so autoscaling still sees the signal.
2. **Two latency measures**, as in Netflix's `slo_target` / `slo_max`: the fraction of requests
   slower than a target sheds the lower tiers only, and the fraction slower than a max
   (timeout-like) threshold sheds every tier. Critical traffic is only touched when requests
   are close to timing out.
3. **Partitioned adaptive concurrency limit** (Gradient2) as a backstop. A tier is only held
   to its share once the global limit is full; below that, tiers borrow freely.

Clients get a 503 with `Retry-After` and a per-priority retry budget so shedding does not
turn into a retry storm.

## References

Netflix engineering posts and talk this is based on:

- [Performance Under Load](https://netflixtechblog.medium.com/performance-under-load-3e6fa9a60581) (2018): adaptive concurrency limits, Little's law, Gradient/Vegas.
- [Keeping Netflix Reliable Using Prioritized Load Shedding](https://netflixtechblog.com/keeping-netflix-reliable-using-prioritized-load-shedding-6cc827b02f94) (2020): API-gateway shedding, cubic shedding curve, retry hints.
- [Enhancing Netflix Reliability with Service-Level Prioritized Load Shedding](https://netflixtechblog.com/enhancing-netflix-reliability-with-service-level-prioritized-load-shedding-e735e6ce8f7d) (2024): the four priority buckets, CPU and latency utilization, the 6x experiment.
- [How Netflix handles sudden load spikes in the cloud](https://reinvent.awsevents.com/content/dam/reinvent/2024/slides/nfx/NFX301_How-Netflix-handles-sudden-load-spikes-in-the-cloud.pdf) (re:Invent 2024 slides): target versus max utilization, success and failure buffers, retry settings.
- [Enhancing Reliability Using Service-Level Prioritized Load Shedding at Netflix](https://infoq.com/presentations/service-level-prioritized-load-shedding) (QCon SF 2025): shed-probability functions, success and failure buffers, auto-tuning, prioritized retry budget.
