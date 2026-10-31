#!/usr/bin/env bash
# Retry-storm experiment: a 6x spike hits the demo server while clients retry in three ways.
#   none   - failed requests are not retried
#   naive  - two immediate retries, ignoring Retry-After
#   budget - Sieve's RetryPolicy (backoff, Retry-After, priority-aware retry budget)
# usage: scripts/retry-storm.sh [server-mode=shed] [repeats=3]
set -u
cd "$(dirname "$0")/.."
smode=${1:-shed}
repeats=${2:-3}
./gradlew -q :sieve-demo:installDist || exit 1
BIN=sieve-demo/build/install/sieve-demo/bin/sieve-demo
CP="sieve-demo/build/install/sieve-demo/lib/*"
mkdir -p results

printf '%-8s %-7s %-12s %7s %7s %9s\n' server retry tier ok% amplif p99ms
for r in $(seq "$repeats"); do
  for retry in none naive budget; do
    $BIN "$smode" 8080 > /dev/null 2>&1 &
    pid=$!
    sleep 2
    java -cp "$CP" dev.sieve.demo.LoadProbe 8080 100 6 none > /dev/null 2>&1   # warm up
    java -cp "$CP" dev.sieve.demo.LoadProbe 8080 600 10 "$retry" 2>/dev/null \
      | awk -v s="$smode" -v rt="$retry" '$1==600 {
          printf "%-8s %-7s %-12s %6.1f%% %8.2fx %9s\n", s, rt, $2, $4+0, $5/$3, $7 }'
    kill $pid 2>/dev/null; wait $pid 2>/dev/null
  done
done
