# Results in detail

The headline is in the [README](../README.md#at-a-glance). Everything here was measured against
the demo backend (a simulated service that sleeps 20 ms per request on 4 workers, so it serves about
200 requests per second) with the Gatling spike harness: a 100 requests per second baseline, a
ramp to a peak, 30 s at the peak, then recovery. Traffic is 20% critical, 30% degraded, 30%
best-effort and 20% bulk. Success is the client view; a 503 or a timeout counts as a failure.
The main comparisons ran on GitHub Actions runners (4 vCPU, idle before each run) and were
interleaved: every round runs each configuration once, so they share the same conditions.

## Recommended setup

- Windowed Gradient2 limit (initial 10, minimum 4, maximum 200), critical-first tier shares.
- Graded tier thresholds (`TierPolicy.graded()`: bulk gives way at 50% of the limit, best-effort at
  70%, degraded at 85%, critical never). They order the lower tiers from the limiter alone.
- A critical floor sized to the critical request rate times the longest stall you want to ride out.
- The latency and CPU shed curves are optional. They help when the limiter cannot see the overload
  (an IO-bound service, a slow downstream) and at extreme overload.

The thresholds are the main knob and were not tuned: how aggressive they are trades critical
availability against the lower tiers' share (see the threshold sensitivity below).

## Headline: 6x spike, five interleaved rounds

| Configuration | Critical success | Critical p50 / p99 | Degraded / best-effort / bulk |
|---|---|---|---|
| Limiter only | 100% (5/5) | 53 / 77 ms | 39 / 36 / 39%, no order |
| Limiter + graded tiers | 100% (5/5) | 49 / 69 ms | 61 / 26 / 20%, ordered |
| Shed curves + limiter | 100% (5/5) | 55 / 76 ms | 45 / 37 / 27% |
| Shed curves + graded tiers | 100% (5/5) | 50 / 69 ms | 61 / 26 / 20%, ordered |

Critical is 100% everywhere at 6x, so this peak cannot separate the configurations on availability.
Graded tiers separate them on ordering and latency, in every round. With graded tiers on, the shed
curves change nothing at this peak. For comparison, with no protection every tier gets about 20%
and latency runs into the client timeout (3 rounds on the same type of runner).

## Overload: 10x, six interleaved rounds

At a 10x peak (1,000 requests per second) critical traffic alone is about 200 requests per second,
the backend's whole capacity, so these numbers measure behaviour at saturation, not policy quality.

| Configuration | Limiter only | With shed curves |
|---|---|---|
| Baseline | 80.8% (sd 0.8) | 86.9% (sd 1.3) |
| Graded tiers | 90.5% (sd 1.0) | 90.4% (sd 1.1) |
| Critical floor 8 | 88.7% (sd 0.6) | 99.6% (sd 0.1) |
| Graded tiers + floor 8 | 97.0% (sd 0.8) | 99.5% (sd 0.1) |

Critical median p99 is 69 ms with graded tiers alone and 98 to 108 ms with a floor, because requests
the floor admits above the limit wait. In every configuration the losses were requests rejected by
the concurrency limit, never by a curve. At this saturation the floor is what keeps critical near
100% when the curves are on; the graded tiers add nothing for critical there but keep the ordering.

A sweep of the baseline from 3x to 16x (2 rounds each, separate batch) shows where it starts to
fail:

| Peak | Limiter only | Shed curves + limiter |
|---|---|---|
| 3x, 6x | 100% | 100% |
| 8x | 92.3% | 96.2% |
| 10x | 80.7% | 87.3% |
| 12x | 71.7% | 77.5% |
| 16x | 57.0% | 60.7% |

## Threshold sensitivity

Three threshold sets for degraded / best-effort / bulk, limiter only, at 10x, three interleaved
rounds. Critical is always 1.0.

| Thresholds | Critical success | Degraded / best-effort / bulk |
|---|---|---|
| 0.95 / 0.85 / 0.70 | 86.4% (sd 1.8) | 28 / 16 / 13% |
| 0.85 / 0.70 / 0.50 (default) | 93.3% (sd 2.4) | 26 / 14 / 12% |
| 0.70 / 0.50 / 0.30 | 99.3% (sd 0.5) | 22 / 14 / 10% |

The ordering never flips, but critical availability at saturation depends strongly on how early the
lower tiers give way. More aggressive thresholds buy critical at the price of the lower tiers.

## A second backend

A different service: 8 workers at 50 ms (capacity about 160 requests per second), a 50 requests per
second baseline and an 8x peak, three interleaved rounds. Critical was 100% in every configuration,
so this load did not stress it. The tier ordering did transfer: limiter only gave 50 / 37 / 42%
(no order) and graded tiers gave 78 / 24 / 18%, with similar latency (p99 133 against 135 ms).

## Stalls, and the low critical runs

Some runs on my own laptop had lower critical success than the clean runner, with no client
timeouts. The outcome breakdown showed where the lost critical requests went: a few per cent
rejected by the concurrency limit and about 1% shed by the max-latency curve. A stall reproduces it:
`SIEVE_STALL_MS=400 SIEVE_STALL_EVERY_S=8` freezes request processing for 400 ms every 8 s, like a
GC pause or a CPU starvation burst (6x peak, three rounds).

| Configuration under the stall | Critical success | Critical rejected per run (limiter / curve) | Critical p99 |
|---|---|---|---|
| Baseline, limiter only | 97.0% | 194 / 0 | 363 to 378 ms |
| Baseline, curves, 5 s window | 97.4% | 166 / 0 | 372 to 388 ms |
| Baseline, curves, 2 s window | 97.1% | about 170 / about 16 | 382 to 384 ms |
| Baseline, curves, 1 s window | 90.3% | about 160 / about 463 | 392 to 417 ms |
| Critical floor 64, limiter only | 100% (3/3) | 0 / 0 | 445 to 458 ms |
| Critical floor 64, curves | 100% (3/3) | 0 / 0 | 428 to 454 ms |
| Graded tiers + floor 64 | 100% (3/3) | 0 / 0 | 448 to 462 ms |

- A stall freezes the backend, latency spikes, the adaptive limit shrinks, and the limiter rejects
  critical requests. That is the main loss (about 3%).
- A shorter signal window makes it worse: the stall is a bigger fraction of a 1 s window, so the max
  curve fires and sheds critical traffic too. The default 5 s window absorbs it.
- A floor sized to the stall's burst removes the loss completely: 120 critical requests per second
  times 0.4 s is about 48 requests in flight, so a floor of 64 covers it and a floor of 8 does not.
  The price is latency: those requests wait out the stall instead of being rejected (p99 about
  75 ms higher).
- On the laptop, a floor of 8 halved the limiter's rejections of critical requests (596 to 218 over
  four runs) without removing them, which fits the sizing rule.

## Vegas against Gradient2

Netflix's README recommends Vegas on servers, so I compared them (6x, three interleaved rounds):
critical was 100% for both. Vegas had about 10 to 15% lower latency (limiter only: p50 47 to 48
against 52 to 54 ms, p99 73 to 77 against 80 to 83 ms), but with the shed curves it lost the tier
ordering (41 / 34 / 37% against 48 / 35 / 25%), because latency stayed under the curves'
thresholds. Three rounds do not support a ranking.

### What these results establish

On a simulated backend and clean runners: graded tier thresholds order the lower tiers from the
limiter alone and lower tail latency, without any latency or CPU signal; they raise critical
availability under heavy overload; a critical floor sized to a burst removes losses from stalls; and
the ordering transferred to a second backend.

They do not establish production capacity, behaviour over a real network or against a real
downstream service, any ranking between Gradient2 and Vegas, that the default thresholds are
optimal, or that a floor of any particular size generalizes. Critical availability beyond about
8x measures saturation.

### Static limit baseline

A bounded queue that fails fast when full is the usual way to shed load, so I compared against one:
a single static concurrency limit with no priorities (`fixed` mode). Two rounds per limit, with the
adaptive limiter and full Sieve as controls from the same batch.

| Mode | Critical success | Other tiers' success | p50 / p99 | Little's law: limit / 200 req/s |
|---|---|---|---|---|
| Static limit 8 | 49.2% | 48 to 51% | 35 / 53 ms | 40 ms |
| Static limit 32 | 50.0% | 48 to 50% | 143 / 210 ms | 160 ms |
| Static limit 128 | 49.3% | 48 to 50% | 660 / 805 ms | 640 ms |
| Adaptive limiter | 99.8% | 35 to 39% | 64 / 87 ms | |
| Sieve (shed curves + limiter) | 98.9% | 40 / 20 / 18% | 22 / 133 ms | |

Total goodput is the same in every row (about half of the offered load, which is what the backend
can serve). A static limit only changes how long the admitted requests wait, and the wait follows
Little's law almost exactly: limit divided by capacity, so 8, 32 and 128 give about 40, 160 and
640 ms. The limit has to be picked by hand for the backend, and it gives every tier the same share.
The adaptive limiter finds a good operating point without being told, and the priorities decide who
gets the capacity.

### Retry storms (`scripts/retry-storm.sh`)

A sudden 600 rps for 10 s against the shedding server, with clients retrying three ways. "Load"
is calls sent to the server per original request. Three runs each.

| Client retries | Critical success | Critical p99 | Degraded success | Load, degraded tier |
|---|---|---|---|---|
| None | 97.6% | 251 ms | 35.0% | 1.00x |
| Naive: 2 immediate retries | 90.0% (87.5-93.6) | 2.7 s | 11.5% | 2.87x |
| `RetryPolicy` (budget) | 99.4% (99.2-99.7) | 302 ms | 33.6% | 1.00x |

Naive retries make the overload worse: they nearly triple the load from the lower tiers and cost
critical traffic availability. The retry budget adds 6% to the critical tier's calls and nothing measurable elsewhere, because
the lower tiers' `Retry-After` hints (2-10 s) exceed the 1 s it is willing to wait.

Shed critical requests are told `Retry-After: 0` and the client's full-jitter backoff spreads
them out. An earlier version told them to wait 1 s, which put the critical p99 at about 1.2 s; the
change cut it to 0.3 s.

Against a server with no shedding, nothing helps (about 22% success whichever way clients retry),
but naive retries still send 2.5x the calls.

### CPU-bound backend

The demo can burn CPU instead of sleeping (`SIEVE_BACKEND=cpu`). 4 spinning workers serve about
200 rps; load goes from 50 to 300 rps. CPU is the only shedding signal, with a 50% autoscaling
target (non-critical shedding from 60% CPU, critical from 80%). Two rounds per mode.

| Mode | Critical success | Critical p99 | Degraded / best-effort / bulk success |
|---|---|---|---|
| No protection | 41% | timeouts (5 s) | 41 / 41 / 41% |
| System-wide CPU (earlier batch) | 95.5-96.2% | 98-139 ms | 60 / 40 / 24% |
| System-wide CPU (later batch) | 78.6-89.2% | 68-79 ms | 44 / 26 / 16% |
| Process CPU (`CpuSignal.process()`) | 100% | 105 ms | 72.5 / 64.7 / 57.0% |

System-wide CPU includes the load generator and anything else on the machine, so the same
configuration gave 96% critical in one batch and 84% in another. Reading only this process's CPU
removed that: critical was fully protected and the lower tiers kept much more of their traffic,
because they were no longer shed for CPU the server did not use. On a shared machine, use
`CpuSignal.process()`; where the signal must line up with an autoscaler that scales on instance
CPU, use `system()` (Spring: `sieve.cpu-scope`).

### Dashboards

Prometheus and Grafana (`monitoring/`, `docker compose up -d`) with a provisioned dashboard.
The same spike, without protection and with Sieve:

![Spike without protection](grafana-noshed.png)

Without protection, goodput is stuck at the backend's capacity while latency climbs to tens of
seconds. The server keeps finishing a backlog of requests whose clients gave up after 5 s, which
is why "availability" in the server's own view rises above 100% at the end; the client view in the
table above is the real one.

![Spike with Sieve](grafana-shed.png)

With Sieve, offered load reaches 600 req/s while goodput holds near capacity. All critical
traffic (120 req/s) is served and the lower tiers absorb the shedding. The flat 100 ms p99 is the
histogram's bucket boundary, not a measurement of exactly 100 ms.

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

### Controls

Checks run to see which results hold up, each against the same spike.

- **Core pinning.** Server on cores 0-1 and Gatling on 2-3 (`SIEVE_PIN=1`). With both modes
  pinned, critical p50 is 51-55 ms with shedding against 57-58 ms limiter-only, so the 3x latency
  gap seen unpinned was the server having spare CPU, not the curves. Both keep critical at 100%.
  What stays: the tier ordering (degraded 57% against 37% limiter-only, bulk 21% against 40%).
  Pinning also tightened the tail: shed p99 was 84-97 ms against 151-239 ms unpinned.
- **Limiter limits.** The library's own windowed Gradient2 limits (initial 20, minimum 20) against
  the tight 10 / 4 used for this 4-worker backend: critical availability is the same (99.2% against
  99.9%), limiter-only critical p50 is 100 ms against 60 ms, and degraded goodput under shedding is
  36% against 48%. The defaults are acceptable; sizing the floor to the service buys latency and
  lower-tier goodput, not critical availability.
- **Hot path.** `Sieve.admit()` with two latency signals, 4 threads: about 1.9M admits/s before
  the signals became lock-free and cached, about 3.9M after (one thread: about 1.0M to 2.1M). That
  is about 0.5 microseconds per request, and it was never the bottleneck at the 600 rps used here.

### Decisions and rejected ideas

| Idea | What I measured | Decision |
|---|---|---|
| One latency threshold for every tier | Critical was shed at 87 to 90% success in the 400 rps phase | Replaced by a target threshold (lower tiers only) and a max threshold (everything) |
| Bare Gradient2 limiter | Limiter-only critical success 79.6% | Wrapped in `WindowedLimit`: 99.9% |
| Library limits (20 / 20) against tight limits (10 / 4) | Same critical availability; limiter-only critical p50 100 ms against 60 ms | Library keeps its defaults; the demo uses 10 / 4 |
| Latency curves to order the tiers | Weak ordering (45 / 37 / 27%), only when latency crosses the thresholds | Graded thresholds on the limit instead: 61 / 26 / 20% with no signal |
| Short signal window (1 s) | Under a 400 ms stall critical fell to 90.3% | Keep 5 s |
| Probe fraction to break the low traffic latch | Not run: the latch is bounded by the window | Rejected on reasoning |
| Tuned curve thresholds | Score +8% in the tuner, not separable from defaults | Defaults kept |
| Critical `Retry-After` of 1 s | Retried critical p99 1.2 s | Changed to 0 with client jitter: 0.3 s |
| System wide CPU signal | Critical 79 to 96% depending on what else ran on the machine | `CpuSignal.process()` added: 100% |
| Lock held on every signal read | 1.9M admits/s on 4 threads | Lock free and cached: 3.9M |
| `admit()` allocated on every call | 125 to 152 bytes per admit, 48 per curve shed, 128 per limiter shed | Cached ids and shed results: 59 to 96, 0 and 32 |
| Cold start sheds lower tiers | First 6 s: degraded 88%, best-effort 84%, bulk 77% | Optional warm-up (`SIEVE_WARMUP_S`): 98%, 97%, 98% |
| Critical floor of 8 | Halves limiter rejections on a noisy laptop, no effect at 6x, 99.5% at 10x | Optional; size it to the critical rate times the stall |
| Vegas | Same critical as Gradient2, 10 to 15% lower latency, no ranking supported | Offered as an option |
| io_uring | Not measured; syscall cost is negligible at these rates | Not pursued |

### Limitations

- Two simulated backends and one spike shape. Numbers compare configurations against each other.
- The 10x results measure saturation: critical traffic alone fills the backend there.
- The thresholds (0.85 / 0.70 / 0.50) and the floor sizes were not tuned. The sensitivity runs show
  critical availability at saturation depends strongly on them.
- The main claims rest on 5 to 6 interleaved rounds; the sweeps and the stall tests on 2 to 3.
- GitHub's runner CPU varies between runs (an AMD EPYC 7763 and a 9V45 were seen).
- My laptop results, which are older and noisier, are only used for the stall diagnosis.

### Evidence

Raw per-run output and an environment manifest (commit, JDK, kernel, CPU) for each GitHub run are in
`docs/evidence/ci-<run id>.tar.gz`. Headline (6x, five rounds): 41275236127, 41275248281, 41275259885, 41275271506, 41275282720. 10x interleaved (six rounds): 41275077054, 41275087565, 41275097284, 41275106702, 41275115067, 41275124573. Threshold sensitivity: 41275149987, 41275160508, 41275169409. Second backend: 41275177856, 41275188160, 41275201320. Stall tests: 41268718945, 41268732308, 41268745067, 41274725486, 41274736283.
