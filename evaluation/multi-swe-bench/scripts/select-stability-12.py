#!/usr/bin/env python3
"""
Deterministic stratified 12-task subset (6 Java + 6 TypeScript, seed=42) drawn from
a verified 40-task manifest for Phase 3 repeat-consistency evaluation (k=3 -> avg@3 & pass^3).
"""
import argparse
import json
import random
from collections import Counter, defaultdict
from pathlib import Path


def patch_lines(row: dict) -> int:
    return len((row.get('fix_patch') or '').splitlines())


def size_bucket(lines: int) -> str:
    return 'S' if lines <= 35 else ('M' if lines <= 100 else 'L')


def pick_k(items: list[dict], k: int, key_fn, rng: random.Random) -> list[dict]:
    groups = defaultdict(list)
    for it in sorted(items, key=lambda r: r['instance_id']):
        groups[key_fn(it)].append(it)
    for g in groups.values():
        rng.shuffle(g)
    picked = []
    # Round-robin across strata so every repo/difficulty bucket is represented
    keys = sorted(groups.keys())
    idx = 0
    while len(picked) < k and any(groups[gk] for gk in keys):
        gk = keys[idx % len(keys)]
        if groups[gk]:
            picked.append(groups[gk].pop(0))
        idx += 1
    return sorted(picked, key=lambda r: r['instance_id'])


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--source', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--per-lang', type=int, default=6)
    ap.add_argument('--seed', type=int, default=42)
    args = ap.parse_args()

    rows = [json.loads(l) for l in Path(args.source).read_text(encoding='utf-8').splitlines() if l.strip()]
    rng = random.Random(args.seed)
    java = [r for r in rows if r['language'] == 'java']
    ts = [r for r in rows if r['language'] == 'typescript']

    j_pick = pick_k(java, args.per_lang, lambda r: f"{r['repo']}:{size_bucket(patch_lines(r))}", rng)
    t_pick = pick_k(ts, args.per_lang, lambda r: f"{r['repo']}:{r.get('difficulty') or 'unknown'}", rng)
    chosen = j_pick + t_pick
    Path(args.out).write_text('\n'.join(json.dumps(r, ensure_ascii=False) for r in chosen) + '\n', encoding='utf-8')
    print(f"Selected {len(chosen)} stability tasks -> {args.out}")
    print("By repo:", dict(Counter(f"{r['org']}/{r['repo']}" for r in chosen)))
    print("IDs:", [r['instance_id'] for r in chosen])


if __name__ == '__main__':
    main()
