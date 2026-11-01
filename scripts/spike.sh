#!/usr/bin/env bash
# Run the Gatling spike against the demo server in one mode and print per-tier results.
# usage: scripts/spike.sh noshed|fixed|limiter|shed   (fixed: SIEVE_FIXED_LIMIT, default 32)
# SIEVE_SERVER_JVM sets JVM flags for the server only (e.g. "-XX:+UseSerialGC -Xmx512m").
# Set SIEVE_PIN=1 to pin the server and the load generator to separate cores (defaults: server
# on 0,1 and Gatling on 2,3; override with SIEVE_SERVER_CPUS / SIEVE_LOAD_CPUS). That removes the
# main source of run-to-run noise on a small machine.
set -u
cd "$(dirname "$0")/.."
mode=${1:?usage: spike.sh noshed|fixed|limiter|shed}

./gradlew -q :sieve-demo:installDist :sieve-harness:compileGatlingJava || exit 1

# Busy check: a busy machine makes every number here unreliable. Measure how busy the whole machine
# is over 3 seconds and, if it is above SIEVE_MAX_BUSY percent (default 15), wait up to
# SIEVE_WAIT_QUIET seconds (default 90) for it to settle before starting; otherwise run anyway and
# flag the result. (The load average is not used: it includes this script's own earlier runs.)
cpu_busy() {
  local u1 n1 s1 i1 w1 q1 sq1 u2 n2 s2 i2 w2 q2 sq2
  read -r _ u1 n1 s1 i1 w1 q1 sq1 _ < /proc/stat; sleep "$1"
  read -r _ u2 n2 s2 i2 w2 q2 sq2 _ < /proc/stat
  awk -v b="$(( (u2+n2+s2+q2+sq2) - (u1+n1+s1+q1+sq1) ))" -v i="$(( (i2+w2) - (i1+w1) ))" \
    'BEGIN { t = b + i; printf "%.0f", t ? 100 * b / t : 0 }'
}
max_busy=${SIEVE_MAX_BUSY:-15}
waited=0
busy_before=$(cpu_busy 3)
while [ "$busy_before" -gt "$max_busy" ] && [ "$waited" -lt "${SIEVE_WAIT_QUIET:-90}" ]; do
  waited=$((waited + 3)); busy_before=$(cpu_busy 3)
done
mkdir -p build
server_pin=""; load_pin=""; gradle_flags=""
if [ -n "${SIEVE_PIN:-}" ]; then
  server_pin="taskset -c ${SIEVE_SERVER_CPUS:-0,1}"
  load_pin="taskset -c ${SIEVE_LOAD_CPUS:-2,3}"
  gradle_flags="--no-daemon"   # a daemon would fork Gatling outside the pinned cores
fi
JAVA_OPTS="${SIEVE_SERVER_JVM:-}" $server_pin sieve-demo/build/install/sieve-demo/bin/sieve-demo "$mode" 8080 > "build/server-$mode.log" 2>&1 &
server=$!
trap 'kill $server 2>/dev/null' EXIT
sleep 3

$load_pin ./gradlew $gradle_flags -q :sieve-harness:gatlingRun --simulation dev.sieve.harness.SpikeSimulation > "build/gatling-$mode.log" 2>&1 \
  || { echo "gatling failed, see build/gatling-$mode.log"; exit 1; }
curl -s localhost:8080/metrics > "build/metrics-$mode.txt"
python3 scripts/summarize.py "$mode"
noisy=$([ "$busy_before" -gt "$max_busy" ] && echo "NOISY" || echo "quiet")
echo "host CPU busy before the run: ${busy_before}% (limit ${max_busy}%): $noisy"
