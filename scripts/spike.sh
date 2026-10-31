#!/usr/bin/env bash
# Run the Gatling spike against the demo server in one mode and print per-tier results.
# usage: scripts/spike.sh noshed|limiter|shed
# Set SIEVE_PIN=1 to pin the server and the load generator to separate cores (defaults: server
# on 0,1 and Gatling on 2,3; override with SIEVE_SERVER_CPUS / SIEVE_LOAD_CPUS). That removes the
# main source of run-to-run noise on a small machine.
set -u
cd "$(dirname "$0")/.."
mode=${1:?usage: spike.sh noshed|limiter|shed}

./gradlew -q :sieve-demo:installDist :sieve-harness:compileGatlingJava || exit 1
mkdir -p build
server_pin=""; load_pin=""; gradle_flags=""
if [ -n "${SIEVE_PIN:-}" ]; then
  server_pin="taskset -c ${SIEVE_SERVER_CPUS:-0,1}"
  load_pin="taskset -c ${SIEVE_LOAD_CPUS:-2,3}"
  gradle_flags="--no-daemon"   # a daemon would fork Gatling outside the pinned cores
fi
$server_pin sieve-demo/build/install/sieve-demo/bin/sieve-demo "$mode" 8080 > "build/server-$mode.log" 2>&1 &
server=$!
trap 'kill $server 2>/dev/null' EXIT
sleep 3

$load_pin ./gradlew $gradle_flags -q :sieve-harness:gatlingRun --simulation dev.sieve.harness.SpikeSimulation > "build/gatling-$mode.log" 2>&1 \
  || { echo "gatling failed, see build/gatling-$mode.log"; exit 1; }
curl -s localhost:8080/metrics > "build/metrics-$mode.txt"
python3 scripts/summarize.py "$mode"
