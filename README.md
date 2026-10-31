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

## Results

Everything below was measured on one 4-core machine against the demo backend, with the load
generator on the same machine. The numbers show how the pieces behave relative to each other;
they are not claims about production capacity.

### 6x spike (Gatling, `scripts/spike.sh`)

100 rps baseline, ramp to 600 rps, hold 30 s, recover. The sleep-based backend serves about
200 rps, so the peak is about 3x its capacity. Success is the client view (a 503 or a timeout is a
failure). Latency is for served requests, estimated from the server's histogram. Three rounds per
mode, interleaved to cancel drift.

| Mode | Critical success | Critical p50 / p99 | Degraded / best-effort / bulk success |
|---|---|---|---|
| No protection | 20.4% | timeouts (5 s) | 20.5 / 20.4 / 20.4% |
| Limiter only (windowed Gradient2) | 100% (3/3) | 68 / 99 ms | 36.5 / 35.6 / 39.5%, no ordering |
| Shed curves + limiter, default thresholds | 99.4% (98.4-100) | 23 / 151-239 ms | 46.2 / 21.6 / 18.5%, ordered |
| Shed curves + limiter, tuned thresholds | 100% (3/3) | 21 / 194-216 ms | 43.3 / 20.7 / 17.7%, ordered |

What this shows:

- Without protection, every tier fails equally and latency runs into the client timeout.
- The windowed adaptive limiter alone already keeps critical traffic available on this backend.
- The shed curves add two things: about 3x lower median latency for critical requests, and a real
  priority order among the lower tiers (degraded is served more than best-effort, more than bulk).
  The limiter alone treats those three the same.
- The tuned thresholds are not distinguishable from the hand-picked defaults on goodput.

### Dashboards

Prometheus and Grafana (`monitoring/`, `docker compose up -d`) with a provisioned dashboard.
The same spike, without protection and with Sieve:

![Spike without protection](docs/grafana-noshed.png)

Without protection, goodput is stuck at the backend's capacity while latency climbs to tens of
seconds. The server keeps finishing a backlog of requests whose clients gave up after 5 s, which
is why "availability" in the server's own view rises above 100% at the end; the client view in the
table above is the real one.

![Spike with Sieve](docs/grafana-shed.png)

With Sieve, offered load reaches 600 req/s while goodput holds near capacity. All critical
traffic (120 req/s) is served and the lower tiers absorb the shedding. The flat 100 ms p99 is the
histogram's bucket boundary, not a measurement of exactly 100 ms.

### Retry storms (`scripts/retry-storm.sh`)

A sudden 600 rps for 10 s against the shedding server, with clients retrying three ways. "Load"
is calls sent to the server per original request. Three runs each.

| Client retries | Critical success | Critical p99 | Degraded success | Load, degraded tier |
|---|---|---|---|---|
| None | 98.4% | 196 ms | 34.5% | 1.00x |
| Naive: 2 immediate retries | 93.7% (90.0-97.4) | 1.6 s | 18.8% | 2.77x |
| `RetryPolicy` (budget) | 100.0% (99.9-100) | 1.2 s | 35.0% | 1.00x |

Naive retries make the overload worse: they nearly triple the load from the lower tiers and cost
critical traffic availability. The retry budget adds only 3% load, because the lower tiers'
`Retry-After` hints (2-10 s) exceed the 1 s it is willing to wait. The cost is that a retried
critical request waits out its 1 s `Retry-After`, which is why its p99 is about 1.2 s.

Against a server with no shedding, nothing helps (about 22% success whichever way clients retry),
but naive retries still send 2.5x the calls.

### CPU-bound backend

The demo can burn CPU instead of sleeping (`SIEVE_BACKEND=cpu`). 4 spinning workers serve about
200 rps; load goes from 50 to 300 rps. Two rounds per mode, system CPU as the signal with a 50%
autoscaling target (non-critical shedding from 60% CPU, critical from 80%).

| Mode | Critical success | Critical p99 | Degraded / best-effort / bulk success |
|---|---|---|---|
| No protection | 41% | timeouts (5 s) | 41 / 41 / 41% |
| CPU signal only | 95.5-96.2% | 98-139 ms | 60 / 40 / 24% |
| CPU + latency signals | 95.8-96.3% | 99-100 ms | 61 / 40 / 23% |

The CPU signal alone orders the tiers and keeps latency low. Critical traffic is not fully
protected (about 96%): at saturation the CPU sits above 80%, where the curve starts shedding
critical requests, and the machine-wide reading includes the load generator. This matches the
design in Netflix's talk, which also notes that CPU saturates and stops reflecting load once a
service is shedding.

### Auto-tuner (`sieve-demo`, `Tuner`)

Coordinate descent over the shedding thresholds, each trial a real server process under a real
spike, averaged over two runs. The score requires critical success of at least 99% and p99 under
400 ms, then rewards the weighted goodput of the other tiers. Two search runs agreed on the
shape of the landscape:

- A 40 ms latency target sheds too much (best-effort success falls to 2%).
- A 90 ms target or looser admits more lower-tier traffic (17-20% each) but critical drops to
  about 98% and the trial is rejected.
- Around 60 ms the score is a plateau, so the tuned configuration (+8% score) did not validate as
  better than the defaults in the full spike above.

### Limitations

- The backend is simulated and the machine is shared with the load generator.
- Latency figures are sensitive to machine load: two further shed runs taken while the Prometheus
  and Grafana containers were running had critical p50 of 62-65 ms (against 21-24 ms), with
  similar availability.
- Per-tier p99 comes from histogram buckets and is coarse.
- The limiter's tight floor and starting limit (4 and 10) are tuned to this 4-worker backend and
  are not general defaults. The library's own defaults (20 and 20) are looser.

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
