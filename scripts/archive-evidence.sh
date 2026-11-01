#!/usr/bin/env bash
# Bundle raw results with a manifest of the environment, so a run can be checked later.
# usage: scripts/archive-evidence.sh <label>   (writes docs/evidence/<label>.tar.gz)
set -eu
cd "$(dirname "$0")/.."
label=${1:?usage: archive-evidence.sh <label>}
mkdir -p docs/evidence results
tmp=$(mktemp -d)
python3 - "$label" > "$tmp/MANIFEST.json" <<'PY'
import json, os, subprocess, sys, datetime
def run(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    return (r.stdout or r.stderr).strip()
cpu = [l.split(":", 1)[1].strip() for l in run("lscpu").splitlines() if l.startswith("Model name")]
json.dump({
    "label": sys.argv[1],
    "date": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "git_commit": run("git", "rev-parse", "HEAD"),
    "git_dirty": run("git", "status", "--porcelain") != "",
    "java": run("java", "-version").splitlines()[0],
    "kernel": run("uname", "-r"),
    "cpu": cpu[0] if cpu else "",
    "cpus": os.cpu_count(),
    "gatling_plugin": "io.gatling.gradle 3.16.0",
    "sieve_env": {k: v for k, v in os.environ.items() if k.startswith("SIEVE_")},
    "note": "Result files may come from several commits; the README says which experiment ran on which code. Raw outputs are the per tier summaries and server metrics, not the Gatling HTML reports.",
}, sys.stdout, indent=2)
PY
cp -r results "$tmp/results" 2>/dev/null || true
rm -f "$tmp"/results/*.sh "$tmp"/results/run-*.log
cp build/metrics-*.txt "$tmp/results/" 2>/dev/null || true
tar -czf "docs/evidence/$label.tar.gz" -C "$tmp" .
rm -rf "$tmp"
echo "wrote docs/evidence/$label.tar.gz"
