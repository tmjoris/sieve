#!/usr/bin/env python3
"""Per-tier results of the newest Gatling run.

Availability comes from Gatling (client view: a 503 or a timeout is a failure). Latency of
served requests is estimated from the server's Prometheus histogram, which only counts requests
that were actually served.
"""
import glob
import os
import re
import sys

TIERS = ("critical", "degraded", "best_effort", "bulk")
mode = sys.argv[1] if len(sys.argv) > 1 else "?"

reports = sorted(glob.glob("sieve-harness/build/reports/gatling/*/index.html"), key=os.path.getmtime)
if not reports:
    sys.exit("no gatling report found")
html = open(reports[-1]).read()

avail = {}
for m in re.finditer(r'class="ellipsed-name">([a-z_]+)</span>(.*?)</tr>', html, re.S):
    name, row = m.group(1), m.group(2)
    if name in TIERS:
        cols = dict(re.findall(r'col-(\d+)">([\d.]+)</td>', row))
        avail[name] = (int(cols["2"]), int(cols["3"]))


def quantile(buckets, q):
    """Prometheus-style histogram_quantile over [(upper_bound, cumulative_count)]."""
    total = buckets[-1][1]
    if total == 0:
        return None
    rank = q * total
    prev_bound, prev_count = 0.0, 0
    for bound, count in buckets:
        if count >= rank:
            if bound == float("inf"):
                return prev_bound
            span = count - prev_count
            frac = (rank - prev_count) / span if span else 1.0
            return prev_bound + (bound - prev_bound) * frac
        prev_bound, prev_count = bound, count
    return None


lat = {}
mfile = f"build/metrics-{mode}.txt"
if os.path.exists(mfile):
    for tier in TIERS:
        pts = []
        for m in re.finditer(
                rf'sieve_request_duration_seconds_bucket\{{tier="{tier}",le="([^"]+)"\}} (\d+)',
                open(mfile).read()):
            le = float("inf") if m.group(1) == "+Inf" else float(m.group(1))
            pts.append((le, int(m.group(2))))
        if pts:
            lat[tier] = (quantile(pts, 0.5), quantile(pts, 0.99))

print(f"mode={mode}")
print(f"{'tier':<12}{'requests':>10}{'success%':>10}{'served p50 ms':>15}{'served p99 ms':>15}")
for tier in TIERS:
    if tier not in avail:
        continue
    total, ok = avail[tier]
    p50, p99 = lat.get(tier, (None, None))
    f = lambda v: "-" if v is None else f"{v * 1000:.0f}"
    print(f"{tier:<12}{total:>10}{100.0 * ok / max(total, 1):>9.1f}%{f(p50):>15}{f(p99):>15}")

# Invariant: no lower tier may do better than critical (small tolerance for noise).
if "critical" in avail:
    def pct(t):
        total, ok = avail[t]
        return 100.0 * ok / max(total, 1)
    worse = [t for t in TIERS if t in avail and pct(t) > pct("critical") + 1.0]
    print("check: critical is never served less than another tier:",
          "OK" if not worse else "VIOLATED by " + ", ".join(worse))
