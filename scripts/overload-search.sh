#!/usr/bin/env bash
# How far does critical traffic hold? Runs the Gatling spike at increasing peak multiples of the
# 100 rps baseline and prints per tier success for each. Critical is 20% of traffic, so on the
# 200 rps demo backend critical alone fills capacity at 10x; expect it to fail beyond that.
# usage: scripts/overload-search.sh <mode> [multiples...]    default multiples: 6 8 10 12
set -u
cd "$(dirname "$0")/.."
mode=${1:?usage: overload-search.sh noshed|limiter|shed [multiples...]}
shift
mults=${*:-6 8 10 12}
for m in $mults; do
  echo "=== $mode peak ${m}x baseline ($((m * 100)) rps offered at peak)"
  SIEVE_PEAK_MULT=$m scripts/spike.sh "$mode"
done
