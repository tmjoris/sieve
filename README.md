# Sieve

[![CI](https://github.com/tmjoris/sieve/actions/workflows/ci.yml/badge.svg)](https://github.com/tmjoris/sieve/actions/workflows/ci.yml)

Prioritized load shedding for Java 21 services, built on Netflix's
[`concurrency-limits`](https://github.com/Netflix/concurrency-limits).

Sieve keeps a Java service useful when it is overloaded. Each request carries a priority, and
under load the lowest priorities are shed first, so the requests users care about keep working.
It follows the design Netflix described for service level prioritized load shedding and builds on
their open source adaptive concurrency limits.

What is here: the core library, a Spring Boot filter, a gRPC interceptor, a client retry policy,
a Gatling load harness with Prometheus and Grafana, and a threshold tuner. Everything has been
measured only against a simulated backend on one machine; read "Results" and "Limitations" before
quoting a number.

## At a glance

A 6x traffic spike (100 to 600 requests per second) against a simulated backend that serves about
200 per second. Three rounds per mode on a GitHub Actions runner (4 vCPU, idle before each run),
current code, clean tree:

| | Critical success | Degraded / best-effort / bulk |
|---|---|---|
| No protection | 20% | 20 / 20 / 20% |
| Adaptive limiter only | 100% | 40 / 35 / 39%, no order |
| Sieve (shed curves + limiter) | 100% | 45 / 34 / 31%, in priority order |

![Offered load and goodput under the spike, with Sieve](docs/grafana-shed.png)

- Under overload, critical requests stay available and the lower tiers are shed in order.
- The adaptive limiter alone already protects critical traffic; the shed curves add the ordering
  among the lower tiers. On my laptop the gap was wider (46 / 22 / 18%).
- Client retries matter: two immediate retries cut critical success to 90% and nearly tripled the
  load from the lower tiers, while Sieve's retry budget kept it at 99.4%.
- This is a simulated backend. Details, the corrections I made along the way, and the runs that
  did not behave are in [Results](#results) and [docs/results.md](docs/results.md).

## Quick start

```bash
git clone https://github.com/tmjoris/sieve.git && cd sieve
./gradlew build                 # needs JDK 21
scripts/spike.sh shed           # Gatling spike against the demo server, per tier results
```

The scripts need Python 3 and `curl`; Docker is only needed for the Prometheus and Grafana stack
in `monitoring/`. Other scripts: `scripts/retry-storm.sh`, `scripts/overload-search.sh`,
`scripts/archive-evidence.sh`, and the `Tuner` in `sieve-demo`. Every `SIEVE_*` setting is listed
in [docs/configuration.md](docs/configuration.md).

## Modules

| Module | What it is |
|---|---|
| `sieve-core` | The library: priorities, shed curves, utilization signals, the partitioned limiter, `Sieve`, and the client `RetryPolicy`. No framework dependencies. |
| `sieve-spring` | Servlet filter and Spring Boot auto-configuration. |
| `sieve-grpc` | gRPC server interceptor. |
| `sieve-demo` | Demo backend with a fixed capacity, a load probe, and the auto-tuner. |
| `sieve-harness` | Gatling simulation that replays a traffic spike. |
| `monitoring/` | Prometheus and Grafana (docker compose) with a provisioned dashboard. |
| `scripts/` | `spike.sh`, `retry-storm.sh`, `overload-search.sh`, `archive-evidence.sh`, and the summarizer. |
| `docs/` | Dashboard screenshots and archived raw results with an environment manifest. |

## Using it

### Plain Java

```java
Sieve sieve = Sieve.builder(PriorityLimiter.withWindowedGradient2(
                TierShares.criticalFirst(), /*initial*/ 10, /*min*/ 4, /*max*/ 200))
        // lower tiers shed as requests pass the 60 ms target, never critical
        .latencySlo(LatencySloSignal.of(Duration.ofMillis(60)), ShedCurve.prioritized(0.10, 0.40))
        // everything shed as requests approach the 300 ms timeout
        .latencySlo(LatencySloSignal.of(Duration.ofMillis(300)), ShedCurve.unprioritized(0.10, 0.40))
        // CPU-bound service that autoscales at 50% CPU (CpuSignal.process() reads only this JVM)
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
  windowed: true                # wrap Gradient2 in WindowedLimit (default)
  cpu-autoscale-target: 0.5     # optional
  cpu-scope: system             # or process; see docs/results.md, "CPU-bound backend"
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
retries are dropped first. The server's hints do part of the work: critical requests are told
`Retry-After: 0` and retry at once with jitter, while degraded, best-effort and bulk are told 2 s,
5 s and 10 s, which is longer than the client's 1 s limit, so they are not retried.

## Results

The 6x spike (100 to 600 requests per second, backend capacity about 200), three rounds per mode,
on a GitHub Actions runner. Success is the client view; a 503 or a timeout counts as a failure.
The workflow is [`benchmark.yml`](.github/workflows/benchmark.yml); the raw results are in
`docs/evidence/`.

| Mode | Critical success | Critical p50 / p99 | Degraded / best-effort / bulk |
|---|---|---|---|
| No protection | 20.2% | timeouts | 20.2 / 20.2 / 20.2% |
| Limiter only (windowed Gradient2) | 100% (3/3) | 52 / 70 to 81 ms | 39.6 / 35.2 / 39.0%, no order |
| Shed curves + limiter | 100% (3/3) | 52 to 57 / 70 to 81 ms | 45.1 / 34.4 / 31.0%, ordered |

On my own machine, the same spike gave critical 98.4 to 100% for the shed mode and a wider tier
ordering (46 / 22 / 18%).

And a retry storm (a sudden 600 rps for 10 s), three runs each:

| Client retries | Critical success | Critical p99 | Degraded success | Load, degraded tier |
|---|---|---|---|---|
| None | 97.6% | 251 ms | 35.0% | 1.00x |
| Two immediate retries | 90.0% | 2.7 s | 11.5% | 2.87x |
| `RetryPolicy` (budget) | 99.4% | 302 ms | 33.6% | 1.00x |

The CPU-bound backend, the threshold tuner, the control experiments (core pinning, limiter
limits, hot path), the Grafana screenshots and the table of ideas I tried and rejected are in
[docs/results.md](docs/results.md).

## Limitations

- The backend is simulated and the load generator shares the machine. The numbers compare modes
  against each other; they are not production capacity.
- The machine was shared with other work, and a few runs gave lower critical success than the
  table above. See [docs/results.md](docs/results.md#limitations) for what I found.
- Trial order was fixed within each round, so slow drift could favour a later arm.

## Known issues

- During JVM warm-up the first requests are slow, so the lower tiers are shed for a few seconds
  (critical is not). The demo can warm up before serving (`SIEVE_WARMUP_S`), which cut that from
  12 to 23% of lower-tier requests to about 2%.
- The CPU curve starts shedding critical requests at 80% CPU by design.
- The tuner found no configuration better than the defaults on this backend.
- There is no Spring Boot demo application; the Spring module is covered by unit and end to end
  tests, and the benchmarks use a small JDK HTTP server.

## Design

Requests carry one of four priorities: `CRITICAL`, `DEGRADED`, `BEST_EFFORT`, `BULK`.
Three mechanisms work together:

1. **Utilization-based shedding.** A per-tier shed probability, driven by CPU or by the
   fraction of requests slower than a latency SLO. Shedding starts above the autoscaling
   target so autoscaling still sees the signal.
2. **Two latency measures**, as in Netflix's `slo_target` / `slo_max`: the fraction of requests
   slower than a target sheds the lower tiers only, and the fraction slower than a max
   (timeout-like) threshold sheds every tier. Critical traffic is only touched when requests
   are close to timing out.
3. **Partitioned adaptive concurrency limit** (Gradient2) as a backstop. A tier is only held
   to its share once the global limit is full; below that, tiers borrow freely.

Latency signals record lock free and publish a cached value, so a request pays about a clock read
and a volatile read to find out how loaded the service is, in the spirit of Netflix's out of band
utilization files. In the Spring module a priority can be downgraded inside a service but never
upgraded.

Clients get a 503 (or gRPC `UNAVAILABLE`) with `Retry-After` and a per-priority retry budget so
shedding does not turn into a retry storm.

## References

Netflix engineering posts and talk this is based on:

- [Performance Under Load](https://netflixtechblog.medium.com/performance-under-load-3e6fa9a60581) (2018): adaptive concurrency limits, Little's law, Gradient/Vegas.
- [Keeping Netflix Reliable Using Prioritized Load Shedding](https://netflixtechblog.com/keeping-netflix-reliable-using-prioritized-load-shedding-6cc827b02f94) (2020): API-gateway shedding, cubic shedding curve, retry hints.
- [Enhancing Netflix Reliability with Service-Level Prioritized Load Shedding](https://netflixtechblog.com/enhancing-netflix-reliability-with-service-level-prioritized-load-shedding-e735e6ce8f7d) (2024): the four priority buckets, CPU and latency utilization, the 6x experiment.
- [How Netflix handles sudden load spikes in the cloud](https://reinvent.awsevents.com/content/dam/reinvent/2024/slides/nfx/NFX301_How-Netflix-handles-sudden-load-spikes-in-the-cloud.pdf) (re:Invent 2024 slides): target versus max utilization, success and failure buffers, retry settings.
- [Enhancing Reliability Using Service-Level Prioritized Load Shedding at Netflix](https://infoq.com/presentations/service-level-prioritized-load-shedding) (QCon SF 2025): shed-probability functions, success and failure buffers, auto-tuning, prioritized retry budget.

## License

Apache License 2.0. See [LICENSE](LICENSE).
