#!/usr/bin/env python3
"""
Deterministic stratified task selector for ByteDance-Seed/Multi-SWE-bench-flash (Java + TypeScript).

Produces reproducible manifests under evaluation/multi-swe-bench/datasets/:
  - flash-java-ts-85.jsonl      All 40 Java + 45 TypeScript tasks from Multi-SWE-bench-flash
  - flash-java-ts-40.jsonl      Balanced 40-task subset (20 Java + 20 TypeScript, seed=42)
  - flash-java-ts-40.meta.json  Full provenance, stratification counts, and task summary
"""
import json
import random
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'datasets' / 'multi_swe_bench_flash.jsonl'
OUT_DIR = ROOT / 'datasets'
SEED = 42


def patch_lines(row: dict) -> int:
    return len((row.get('fix_patch') or '').splitlines())


def size_bucket(lines: int) -> str:
    if lines <= 35:
        return 'S'
    if lines <= 100:
        return 'M'
    return 'L'


def image_name(row: dict) -> str:
    return f"mswebench/{row['org'].lower()}_m_{row['repo'].lower()}:pr-{row['number']}"


def stratified_sample(items: list[dict], k: int, key_fn, rng: random.Random) -> list[dict]:
    if k >= len(items):
        return list(items)
    groups = defaultdict(list)
    for item in sorted(items, key=lambda r: r['instance_id']):
        groups[key_fn(item)].append(item)
    for g in groups.values():
        rng.shuffle(g)

    # Proportional allocation with deterministic remainder assignment
    total = len(items)
    alloc = {}
    remainders = []
    for gk in sorted(groups.keys()):
        exact = (len(groups[gk]) * k) / total
        base = int(exact)
        alloc[gk] = min(base, len(groups[gk]))
        remainders.append((exact - base, gk))
    left = k - sum(alloc.values())
    for _, gk in sorted(remainders, key=lambda x: (-x[0], x[1])):
        if left <= 0:
            break
        if alloc[gk] < len(groups[gk]):
            alloc[gk] += 1
            left -= 1

    picked = []
    for gk in sorted(groups.keys()):
        picked.extend(groups[gk][: alloc[gk]])
    return sorted(picked, key=lambda r: r['instance_id'])


def main() -> None:
    rows = [json.loads(line) for line in SRC.read_text(encoding='utf-8').splitlines() if line.strip()]
    java_all = sorted([r for r in rows if r['language'] == 'java'], key=lambda r: r['instance_id'])
    ts_all = sorted([r for r in rows if r['language'] == 'typescript'], key=lambda r: r['instance_id'])

    all_85 = java_all + ts_all
    (OUT_DIR / 'flash-java-ts-85.jsonl').write_text(
        '\n'.join(json.dumps(r, ensure_ascii=False) for r in all_85) + '\n', encoding='utf-8'
    )

    rng = random.Random(SEED)

    # Java 20: take all tasks from smaller repos (gson 5, jackson-dataformat-xml 5, jib 3 = 13)
    # plus 7 stratified by fix_patch size bucket from jackson-databind (27)
    java_small_repos = [r for r in java_all if r['repo'] != 'jackson-databind']
    java_databind = [r for r in java_all if r['repo'] == 'jackson-databind']
    java_20 = sorted(
        java_small_repos + stratified_sample(java_databind, 20 - len(java_small_repos), lambda r: size_bucket(patch_lines(r)), rng),
        key=lambda r: r['instance_id'],
    )

    # TypeScript 20: take all tasks from smaller repos (darkreader 2, material-ui 1 = 3)
    # plus 17 stratified by official difficulty from vuejs/core, excluding instances whose
    # f2p_tests include the unrelated 10ms/20ms setTimeout race in Suspense.spec.ts that fails
    # golden-patch verification under Rosetta 2 emulation.
    ts_small_repos = [r for r in ts_all if r['repo'] != 'core']
    ts_vue_clean = [
        r for r in ts_all
        if r['repo'] == 'core'
        and not any('Suspense.spec.ts > Suspense > combined usage' in k for k in (r.get('f2p_tests') or {}))
    ]
    ts_vue_excluded = [r['instance_id'] for r in ts_all if r['repo'] == 'core' and r not in ts_vue_clean]
    ts_20 = sorted(
        ts_small_repos + stratified_sample(ts_vue_clean, 20 - len(ts_small_repos), lambda r: r.get('difficulty') or 'unknown', rng),
        key=lambda r: r['instance_id'],
    )

    selected_40 = java_20 + ts_20
    assert len(java_20) == 20 and len(ts_20) == 20 and len(selected_40) == 40

    (OUT_DIR / 'flash-java-ts-40.jsonl').write_text(
        '\n'.join(json.dumps(r, ensure_ascii=False) for r in selected_40) + '\n', encoding='utf-8'
    )

    meta = {
        'sourceDataset': 'ByteDance-Seed/Multi-SWE-bench-flash',
        'seed': SEED,
        'excludedFlakyTimerTasks': ts_vue_excluded,
        'totalTasks': len(selected_40),
        'byLanguage': dict(Counter(r['language'] for r in selected_40)),
        'byRepo': dict(Counter(f"{r['org']}/{r['repo']}" for r in selected_40)),
        'bySizeBucket': {
            'java': dict(Counter(size_bucket(patch_lines(r)) for r in java_20)),
            'typescript': dict(Counter(size_bucket(patch_lines(r)) for r in ts_20)),
        },
        'byOfficialDifficulty': {
            'typescript': dict(Counter(r.get('difficulty') or 'unknown' for r in ts_20)),
        },
        'tasks': [
            {
                'instance_id': r['instance_id'],
                'language': r['language'],
                'org': r['org'],
                'repo': r['repo'],
                'number': r['number'],
                'image': image_name(r),
                'difficulty': r.get('difficulty'),
                'fix_patch_lines': patch_lines(r),
                'size_bucket': size_bucket(patch_lines(r)),
                'title': r.get('title', ''),
            }
            for r in selected_40
        ],
    }
    (OUT_DIR / 'flash-java-ts-40.meta.json').write_text(json.dumps(meta, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    (OUT_DIR / 'images-flash-40.txt').write_text('\n'.join(t['image'] for t in meta['tasks']) + '\n', encoding='utf-8')

    print(f"Wrote flash-java-ts-85.jsonl ({len(all_85)} tasks) and flash-java-ts-40.jsonl ({len(selected_40)} tasks)")
    print("Repos (40):", meta['byRepo'])
    print("Size buckets (40):", meta['bySizeBucket'])
    print("TS official difficulty (40):", meta['byOfficialDifficulty'])


if __name__ == '__main__':
    main()
