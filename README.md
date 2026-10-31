# Sieve

Prioritized load shedding for Java 21 services, built on Netflix's
[`concurrency-limits`](https://github.com/Netflix/concurrency-limits).

Work in progress. Numbers will be added here only once they are measured.

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

## Known issues

- **Cold start.** In `shed` mode the first seconds after startup can shed most traffic: JIT
  warm-up makes early requests slow, the latency signal reads high, and when everything is
  shed no fast requests arrive to bring it back down until the 5 s window expires. A repeat of
  the same phase is clean. Not fixed yet.
- Results vary between runs; one run proves little.

## Design

Requests carry one of four priorities: `CRITICAL`, `DEGRADED`, `BEST_EFFORT`, `BULK`.
Two mechanisms work together:

1. **Utilization-based shedding.** A per-tier shed probability, driven by CPU or by the
   fraction of requests slower than a latency SLO. Shedding starts above the autoscaling
   target so autoscaling still sees the signal.
2. **Partitioned adaptive concurrency limit** (Gradient2) as a backstop. A tier is only held
   to its share once the global limit is full; below that, tiers borrow freely.

Clients get a 503 with `Retry-After` and a per-priority retry budget so shedding does not
turn into a retry storm.

## References

Netflix engineering posts and talk this is based on:

- [Performance Under Load](https://netflixtechblog.medium.com/performance-under-load-3e6fa9a60581) (2018): adaptive concurrency limits, Little's law, Gradient/Vegas.
- [Keeping Netflix Reliable Using Prioritized Load Shedding](https://netflixtechblog.com/keeping-netflix-reliable-using-prioritized-load-shedding-6cc827b02f94) (2020): API-gateway shedding, cubic shedding curve, retry hints.
- [Enhancing Netflix Reliability with Service-Level Prioritized Load Shedding](https://netflixtechblog.com/enhancing-netflix-reliability-with-service-level-prioritized-load-shedding-e735e6ce8f7d) (2024): the four priority buckets, CPU and latency utilization, the 6x experiment.
- [Enhancing Reliability Using Service-Level Prioritized Load Shedding at Netflix](https://infoq.com/presentations/service-level-prioritized-load-shedding) (QCon SF 2025): shed-probability functions, success and failure buffers, auto-tuning, prioritized retry budget.
