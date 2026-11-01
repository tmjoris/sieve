# Configuration

## Demo server

The demo server reads `SIEVE_*` environment variables: `SIEVE_BACKEND=cpu` burns CPU instead of
sleeping, `SIEVE_WORKERS`, `SIEVE_SERVICE_MS`, `SIEVE_CPU_TARGET` (adds the CPU signal),
`SIEVE_CPU_SCOPE=process|system`, `SIEVE_LATENCY=0` (turn latency shedding off), `SIEVE_WARMUP_S` (serve that many seconds of
synthetic load on a throwaway instance before opening the port), the threshold
settings `SIEVE_TARGET_MS`, `SIEVE_TARGET_LO`, `SIEVE_TARGET_HI`, `SIEVE_MAX_MS`, `SIEVE_MAX_LO`,
`SIEVE_MAX_HI`, and the limiter settings `SIEVE_INITIAL_LIMIT`, `SIEVE_MIN_LIMIT`,
`SIEVE_MAX_LIMIT`, `SIEVE_WINDOWED=0`.

## Harness scripts

`scripts/spike.sh` honours `SIEVE_PIN=1` (server on cores 0 and 1, Gatling on 2 and 3),
`SIEVE_SERVER_JVM` (JVM flags for the server only), `SIEVE_PEAK_MULT`, `SIEVE_HOLD_S` (seconds at peak, default 30) and `SIEVE_BASE_RPS`, and
`SIEVE_FIXED_LIMIT` for the `fixed` mode. Before each run it measures how busy the whole machine is
over 3 seconds and, if that is above `SIEVE_MAX_BUSY` percent (default 15), waits up to
`SIEVE_WAIT_QUIET` seconds (default 90) before running and flags the result as noisy. After each
run it checks that critical was never served less than another tier, and that every arrived
request is accounted for as served or shed.

`scripts/archive-evidence.sh <label>` bundles raw results with a manifest of the environment into
`docs/evidence/`.
