# Configuration

Library settings live in code or, for Spring, under `sieve.*` (see the README). Everything below
is for the demo server and the harness, set through `SIEVE_*` environment variables.

## Limiter

| Variable | Default | What it does |
|---|---|---|
| `SIEVE_LIMIT_ALGO` | `gradient2` | `gradient2` or `vegas`. |
| `SIEVE_INITIAL_LIMIT`, `SIEVE_MIN_LIMIT`, `SIEVE_MAX_LIMIT` | 10, 4, 200 | Starting, minimum and maximum concurrency of the adaptive limit. Size the minimum for the service. |
| `SIEVE_WINDOWED` | on | Set to `0` to use bare Gradient2 without `WindowedLimit`. |
| `SIEVE_TIERS` | `none` | `graded` makes lower tiers give way earlier as the limit fills (bulk at 50% of the limit, best-effort 70%, degraded 85%). |
| `SIEVE_TIER_THRESHOLDS` | | Overrides the graded fractions: four numbers for critical, degraded, best-effort and bulk, separated by `:` or `,`, for example `1.0:0.85:0.70:0.50`. Critical must be 1.0. |
| `SIEVE_CRITICAL_FLOOR` | 0 | Critical requests always admitted, up to this many in flight, even when the limit is full. Size it to the critical request rate times the longest stall to ride out (120 req/s and a 400 ms stall need about 50). |

## Shed curves (modes `shed`)

| Variable | Default | What it does |
|---|---|---|
| `SIEVE_TARGET_MS`, `SIEVE_TARGET_LO`, `SIEVE_TARGET_HI` | 60, 0.10, 0.40 | Target latency, and the fraction of requests over it at which lower tiers start (and finish) being shed. Never sheds critical. |
| `SIEVE_MAX_MS`, `SIEVE_MAX_LO`, `SIEVE_MAX_HI` | 300, 0.10, 0.40 | Same for the max latency, which sheds every tier. |
| `SIEVE_SIGNAL_WINDOW_MS` | 5000 | Window of the latency signals. A short window reacts faster but fires on brief stalls (1 s was worse than 5 s under a 400 ms stall). |
| `SIEVE_LATENCY` | on | Set to `0` to turn latency shedding off, to test the CPU signal alone. |
| `SIEVE_CPU_TARGET` | | The CPU fraction the autoscaler targets, for example `0.5`. Adds the CPU signal. |
| `SIEVE_CPU_SCOPE` | `system` | `process` reads only the demo JVM's CPU; `system` reads the whole machine. |

## Demo backend

| Variable | Default | What it does |
|---|---|---|
| `SIEVE_BACKEND` | `sleep` | `cpu` burns CPU instead of sleeping. |
| `SIEVE_WORKERS`, `SIEVE_SERVICE_MS` | 4, 20 | Capacity is workers times 1000 divided by service time (about 200 req/s). |
| `SIEVE_WARMUP_S` | 0 | Seconds of synthetic load served on a throwaway instance before the port opens. |
| `SIEVE_FIXED_LIMIT` | 32 | Static limit for the `fixed` mode, which has no priorities. |

## Harness

| Variable | Default | What it does |
|---|---|---|
| `SIEVE_PEAK_MULT`, `SIEVE_BASE_RPS`, `SIEVE_HOLD_S` | 6, 100, 30 | Spike shape: peak as a multiple of the baseline, the baseline in requests per second, and seconds held at the peak. |
| `SIEVE_PIN` | | `1` pins the server to cores 0 and 1 and Gatling to 2 and 3 (`SIEVE_SERVER_CPUS`, `SIEVE_LOAD_CPUS` override). |
| `SIEVE_SERVER_JVM` | | JVM flags for the server only. |
| `SIEVE_MAX_BUSY`, `SIEVE_WAIT_QUIET` | 15, 90 | Before a run the harness measures how busy the whole machine is for 3 seconds and, if that is above the limit in percent, waits up to the given seconds before running and flags the result as noisy. |

After each run `spike.sh` checks that critical was never served less than another tier and that
every arrived request is accounted for as served or shed.

## Experiment hooks (not for production)

| Variable | What it does |
|---|---|
| `SIEVE_STALL_MS`, `SIEVE_STALL_EVERY_S` | Freeze request processing for that long, that often (default every 8 s), like a GC pause. |
| `SIEVE_CONTENTION` | Run that many busy loops on the server's cores (use with `SIEVE_PIN=1`). |

## Workflow

`.github/workflows/benchmark.yml` is started by hand. Inputs: `rounds`, `modes`, `pin`,
`contention`, `hold_s`, `peak_mult`, `extra_env` (space separated `KEY=VALUE` settings for every
run) and `arms` (configurations run interleaved inside every round, written
`name=KEY=VAL,KEY=VAL;name2=...`).

`scripts/archive-evidence.sh <label>` bundles raw results with a manifest of the environment into
`docs/evidence/`.
