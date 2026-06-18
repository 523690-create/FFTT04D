#!/usr/bin/env python3
"""Boundary-agreement analysis across the 7 fractionation methods.

For each audio file, each method yields a set of segment-start times (the cut points / boundaries).
Agreement between two methods on a file = F1 of matching boundaries within a time tolerance.
We report (a) each method ranked by mean F1 vs HuBERT (treated as the reference), and
(b) the full pairwise mean-F1 matrix to see which methods agree best overall.
"""
import json, glob, os
from collections import defaultdict

SRC = r"C:\Users\belil\FFTT04M_fractionation"
TOL_MS = 50          # a boundary matches if within this many ms
REF = "HuBERT K-Means Units"

# method -> file -> sorted list of boundary start times (excluding the trivial 0 start)
data = defaultdict(lambda: defaultdict(list))
for path in glob.glob(os.path.join(SRC, "*.jsonl")):
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            o = json.loads(line)
            m, f, s = o["method"], o["file"], int(o["startMs"])
            data[m][f].append(s)
methods = sorted(data.keys())
for m in data:
    for f in data[m]:
        data[m][f] = sorted(set(v for v in data[m][f] if v > 0))

def f1(a, b, tol):
    """F1 of boundary set `a` matched against `b` (greedy within tol)."""
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    used = [False] * len(b)
    tp = 0
    for x in a:
        best, bj = tol + 1, -1
        for j, y in enumerate(b):
            if not used[j] and abs(x - y) <= tol and abs(x - y) < best:
                best, bj = abs(x - y), j
        if bj >= 0:
            used[bj] = True
            tp += 1
    prec = tp / len(a)
    rec = tp / len(b)
    return 0.0 if prec + rec == 0 else 2 * prec * rec / (prec + rec)

def mean_f1(m1, m2, tol):
    files = set(data[m1]) & set(data[m2])
    if not files:
        return 0.0, 0
    vals = [f1(data[m1][f], data[m2][f], tol) for f in files]
    return sum(vals) / len(vals), len(files)

print(f"Boundary-agreement analysis  (tolerance ±{TOL_MS} ms, {len(methods)} methods)")
nfiles = len(set().union(*[set(data[m]) for m in methods]))
print(f"Files covered: {nfiles}\n")

print(f"Segment counts (total boundaries, mean per file):")
for m in methods:
    tot = sum(len(v) for v in data[m].values())
    nf = len(data[m])
    print(f"  {m:28s} {tot:6d} boundaries  {tot/max(1,nf):5.1f}/file")

print(f"\n=== Ranking by agreement with reference '{REF}' ===")
rank = []
for m in methods:
    if m == REF:
        continue
    val, n = mean_f1(m, REF, TOL_MS)
    rank.append((val, m, n))
rank.sort(reverse=True)
for i, (val, m, n) in enumerate(rank, 1):
    print(f"  {i}. {m:28s} F1={val:.3f}  (over {n} files)")

print(f"\n=== Full pairwise mean-F1 matrix ===")
hdr = "".join(f"{m[:8]:>9s}" for m in methods)
print(f"{'':28s}{hdr}")
for m1 in methods:
    row = "".join(f"{mean_f1(m1, m2, TOL_MS)[0]:9.2f}" for m2 in methods)
    print(f"  {m1:26s}{row}")

# Overall agreement score per method = mean F1 vs all others
print(f"\n=== Overall agreement (mean F1 vs all other methods) ===")
overall = []
for m1 in methods:
    vals = [mean_f1(m1, m2, TOL_MS)[0] for m2 in methods if m2 != m1]
    overall.append((sum(vals) / len(vals), m1))
overall.sort(reverse=True)
for val, m in overall:
    print(f"  {m:28s} {val:.3f}")
