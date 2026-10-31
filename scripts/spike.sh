#!/usr/bin/env bash
# Run the Gatling spike against the demo server in one mode and print per-tier results.
# usage: scripts/spike.sh noshed|limiter|shed
set -u
cd "$(dirname "$0")/.."
mode=${1:?usage: spike.sh noshed|limiter|shed}

./gradlew -q :sieve-demo:installDist :sieve-harness:compileGatlingJava || exit 1
mkdir -p build
sieve-demo/build/install/sieve-demo/bin/sieve-demo "$mode" 8080 > "build/server-$mode.log" 2>&1 &
server=$!
trap 'kill $server 2>/dev/null' EXIT
sleep 3

./gradlew -q :sieve-harness:gatlingRun --simulation dev.sieve.harness.SpikeSimulation > "build/gatling-$mode.log" 2>&1 \
  || { echo "gatling failed, see build/gatling-$mode.log"; exit 1; }
curl -s localhost:8080/metrics > "build/metrics-$mode.txt"
python3 scripts/summarize.py "$mode"
