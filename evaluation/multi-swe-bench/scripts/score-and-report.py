#!/usr/bin/env python3
"""
Official Multi-SWE-bench scorer + statistical report generator for Pilot evaluation runs.

Reads:
  - runs/<batch>/manifest.json
  - runs/<batch>/results.jsonl
  - runs/<batch>/patches/<arm>-a<attempt>.jsonl

Runs:
  - Official `multi_swe_bench.harness.run_evaluation` (mode=instance_only, max_workers=1 for zero timing flakiness)
  - Official `multi_swe_bench.harness.gen_report`

Computes & writes:
  - runs/<batch>/summary.json
  - runs/<batch>/report.md
    * pass@1 overall + Java vs TypeScript + 95% Wilson CI
    * avg@k and pass^k (consistency) when repeat > 1
    * Prompt Cache hit rate & Uncached Input Tokens / Resolved Task (amortizing failed tasks)
    * p50 / p95 latency and request counts
    * Harness engineering failure rate, edit mismatch stats, CompletionGate/LintGate catches
    * Paired exact two-sided McNemar test when comparing 2 arms on the same tasks
"""
import argparse
import json
import math
import os
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(os.environ.get('PILOT_MSB_ROOT') or Path(__file__).resolve().parent.parent)
PY = sys.executable


def wilson_ci(k: int, n: int, z: float = 1.959963984540054) -> tuple[float, float]:
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    denom = 1.0 + (z * z) / n
    center = (p + (z * z) / (2.0 * n)) / denom
    half = (z * math.sqrt((p * (1.0 - p) + (z * z) / (4.0 * n)) / n)) / denom
    return (max(0.0, center - half), min(1.0, center + half))


def exact_mcnemar_p(b: int, c: int) -> float:
    """Exact two-sided McNemar test via binomial distribution (n = b + c, p = 0.5)."""
    n = b + c
    if n == 0:
        return 1.0
    m = min(b, c)
    # Sum_{i=0..m} C(n, i) * 0.5^n, multiplied by 2 and capped at 1.0
    tail = sum(math.comb(n, i) for i in range(m + 1)) * (0.5 ** n)
    return min(1.0, 2.0 * tail)


def percentile(values: list[float], p: float):
    clean = sorted(v for v in values if v is not None)
    if not clean:
        return None
    idx = min(len(clean) - 1, math.floor((p / 100.0) * len(clean)))
    return clean[idx]


def pct(n: float, d: float) -> str:
    if not d:
        return '—'
    return f"{(100.0 * n / d):.1f}%"


def pr_key(org: str, repo: str, number) -> str:
    return f"{org}/{repo}:pr-{number}"


def score_patch_file(dataset_path: Path, patch_file: Path, score_dir: Path, workers: int, rescore: bool) -> set[str]:
    report_file = score_dir / 'out' / 'final_report.json'
    if report_file.exists() and not rescore:
        rep = json.loads(report_file.read_text(encoding='utf-8'))
        return set(rep.get('resolved_ids', []))

    work_dir = score_dir / 'work'
    out_dir = score_dir / 'out'
    log_dir = score_dir / 'logs'
    for d in (work_dir, out_dir, log_dir):
        d.mkdir(parents=True, exist_ok=True)

    # Deduplicate patch_file keeping last record per (org, repo, number)
    latest_by_id = {}
    for line in patch_file.read_text(encoding='utf-8').splitlines():
        if not line.strip():
            continue
        p = json.loads(line)
        latest_by_id[(p['org'], p['repo'], int(p['number']))] = p
    dedup_patch = score_dir / 'patches-dedup.jsonl'
    dedup_patch.write_text('\n'.join(json.dumps(p) for p in latest_by_id.values()) + '\n', encoding='utf-8')

    specifics = [pr_key(o, r, n) for (o, r, n) in latest_by_id.keys()]
    cfg = {
        'mode': 'instance_only',
        'workdir': str(work_dir),
        'patch_files': [str(dedup_patch)],
        'dataset_files': [str(dataset_path)],
        'force_build': False,
        'output_dir': str(out_dir),
        'specifics': specifics,
        'skips': [],
        'repo_dir': str(ROOT / 'smoke-eval' / 'repos'),
        'need_clone': False,
        'global_env': [],
        'clear_env': True,
        'stop_on_error': False,
        'max_workers': workers,
        'max_workers_build_image': workers,
        'max_workers_run_instance': workers,
        'log_dir': str(log_dir),
        'log_level': 'INFO',
        'log_to_console': True,
        'human_mode': True,
    }
    cfg_path = score_dir / 'msb-config.json'
    cfg_path.write_text(json.dumps(cfg, indent=2), encoding='utf-8')
    env = {**os.environ, 'PYTHONWARNINGS': 'ignore'}
    subprocess.run([PY, '-m', 'multi_swe_bench.harness.run_evaluation', '--config', str(cfg_path)], check=True, env=env)

    # Retry once ONLY for containers killed by timeout mid-run (e.g. Rosetta Vitest teardown hang), never genuine test failures
    hung = []
    for (o, r, n) in latest_by_id.keys():
        inst_dir = work_dir / o / r / 'evals' / f'pr-{n}'
        log_file = inst_dir / 'fix-patch-run.log'
        if log_file.exists():
            txt = log_file.read_text(encoding='utf-8', errors='replace')
            # A completed Vitest run prints "Duration "; if Vitest started ("RUN  v") and timed out before "Duration ", retry once
            if r == 'core' and 'Duration ' not in txt and ('RUN  v' in txt or len(txt) > 1000):
                hung.append((o, r, n, inst_dir))
    if hung:
        print(f"[score] Retrying {len(hung)} Rosetta-timed-out Vitest containers once: {[pr_key(o,r,n) for o,r,n,_ in hung]}", flush=True)
        for _, _, _, inst_dir in hung:
            for fname in ('report.json', 'fix-patch-run.log'):
                fp = inst_dir / fname
                if fp.exists():
                    fp.unlink()
        cfg_retry = dict(cfg, specifics=[pr_key(o, r, n) for o, r, n, _ in hung])
        cfg_retry_path = score_dir / 'msb-config-retry.json'
        cfg_retry_path.write_text(json.dumps(cfg_retry, indent=2), encoding='utf-8')
        subprocess.run([PY, '-m', 'multi_swe_bench.harness.run_evaluation', '--config', str(cfg_retry_path)], check=True, env=env)

    subprocess.run(
        [
            PY, '-m', 'multi_swe_bench.harness.gen_report',
            '--mode', 'evaluation',
            '--workdir', str(work_dir),
            '--output_dir', str(out_dir),
            '--dataset_files', str(dataset_path),
            '--specifics', *specifics,
            '--max_workers', str(workers),
            '--log_dir', str(log_dir),
            '--log_level', 'INFO',
        ],
        check=True,
        env=env,
    )
    rep = json.loads(report_file.read_text(encoding='utf-8'))
    return set(rep.get('resolved_ids', []))


def classify_failure(rec: dict) -> str:
    if rec.get('resolved'):
        return 'RESOLVED'
    ac = rec.get('antiCheat') or {}
    if ac.get('extraCommits') or ac.get('leakedArtifactsCount', 0) > 0:
        return 'ANTI_CHEAT_VIOLATION'
    status = rec.get('status')
    if status in ('INFRA_ERROR',):
        return 'INFRA_ERROR'
    if status in ('TIMEOUT', 'FAILED', 'INTERRUPTED', 'CANCELLED'):
        return 'HARNESS_NON_COMPLETED'
    ta = rec.get('traceAudit') or {}
    if (rec.get('patchBytes') or 0) == 0:
        return 'HARNESS_NO_PATCH'
    if (ta.get('editFailures') or 0) >= 5 and (ta.get('editTotal') or 0) > 0 and (ta.get('editFailures') / ta.get('editTotal')) > 0.5:
        return 'HARNESS_EDIT_LOOP'
    if ta.get('postLastEditCheckPassed') is False:
        return 'HARNESS_UNVERIFIED_END'
    return 'MODEL_LOGIC_OR_EDGE_CASE'


def summarize_arm(arm_name: str, recs: list[dict]) -> dict:
    # Keep latest record per (instance_id, attempt)
    latest = {}
    for r in recs:
        latest[(r['instance_id'], r['attempt'])] = r
    items = list(latest.values())
    a1 = [r for r in items if r['attempt'] == 1]

    resolved_a1 = [r for r in a1 if r['resolved']]
    ci_lo, ci_hi = wilson_ci(len(resolved_a1), len(a1))

    by_lang = {}
    for lang in ('java', 'typescript'):
        sub = [r for r in a1 if r['language'] == lang]
        by_lang[lang] = {'total': len(sub), 'resolved': sum(1 for r in sub if r['resolved'])}

    # Repeat stability (avg@k, pass^k, pass@k)
    by_task = defaultdict(list)
    for r in items:
        by_task[r['instance_id']].append(r)
    multi = {tid: lst for tid, lst in by_task.items() if len(lst) > 1}
    k_val = max((len(lst) for lst in multi.values()), default=1)
    stability = None
    if multi:
        avg_k = sum(sum(1 for r in lst if r['resolved']) / len(lst) for lst in multi.values()) / len(multi)
        pass_hat_k = sum(1 for lst in multi.values() if all(r['resolved'] for r in lst)) / len(multi)
        pass_at_k = sum(1 for lst in multi.values() if any(r['resolved'] for r in lst)) / len(multi)
        stability = {
            'tasks': len(multi), 'k': k_val,
            'avgAtK': round(avg_k, 4),
            'passHatK': round(pass_hat_k, 4),
            'passAtK': round(pass_at_k, 4),
        }

    def tok(r, field):
        ta = r.get('traceAudit') or {}
        d = r.get('diagnostics') or {}
        if field == 'in':
            return ta.get('totalInputTokens') if ta.get('totalInputTokens') is not None else (d.get('inputTokens') or 0)
        if field == 'cached':
            return ta.get('totalCachedInputTokens') if ta.get('totalCachedInputTokens') is not None else (d.get('cachedInputTokens') or 0)
        if field == 'out':
            return ta.get('totalOutputTokens') if ta.get('totalOutputTokens') is not None else (d.get('outputTokens') or 0)
        if field == 'reqs':
            return ta.get('totalModelRequests') if ta.get('totalModelRequests') is not None else (d.get('modelRequests') or 0)
        return 0

    total_in = sum(tok(r, 'in') for r in a1)
    total_cached = sum(tok(r, 'cached') for r in a1)
    uncached = max(0, total_in - total_cached)
    total_out = sum(tok(r, 'out') for r in a1)
    total_reqs = sum(tok(r, 'reqs') for r in a1)

    edit_total = sum((r.get('traceAudit') or {}).get('editTotal') or 0 for r in a1)
    edit_fail = sum((r.get('traceAudit') or {}).get('editFailures') or 0 for r in a1)
    compactions = sum((r.get('traceAudit') or {}).get('compactions') or 0 for r in a1)
    gate_blocks = sum((r.get('gateStats') or {}).get('gateBlocks') or 0 for r in a1)
    lint_blocks = sum((r.get('gateStats') or {}).get('lintBlocks') or 0 for r in a1)
    gate_rescued = sum(1 for r in a1 if r['resolved'] and (((r.get('gateStats') or {}).get('gateBlocks') or 0) + ((r.get('gateStats') or {}).get('lintBlocks') or 0)) > 0)
    gate_triggered_tasks = sum(1 for r in a1 if (((r.get('gateStats') or {}).get('gateBlocks') or 0) + ((r.get('gateStats') or {}).get('lintBlocks') or 0)) > 0)

    taxonomy = defaultdict(int)
    for r in a1:
        taxonomy[r['failureCategory']] += 1

    return {
        'arm': arm_name,
        'tasksA1': len(a1),
        'resolvedA1': len(resolved_a1),
        'passAt1': round(len(resolved_a1) / len(a1), 4) if a1 else 0.0,
        'wilson95': [round(ci_lo, 4), round(ci_hi, 4)],
        'byLanguage': by_lang,
        'stability': stability,
        'p50Seconds': percentile([r['runSeconds'] for r in a1], 50),
        'p95Seconds': percentile([r['runSeconds'] for r in a1], 95),
        'avgRequests': round(total_reqs / len(a1), 1) if a1 else 0.0,
        'totalInputTokens': total_in,
        'totalCachedInputTokens': total_cached,
        'uncachedInputTokens': uncached,
        'cacheHitRate': round(total_cached / total_in, 4) if total_in else None,
        'uncachedPerResolved': round(uncached / len(resolved_a1)) if resolved_a1 else None,
        'totalOutputTokens': total_out,
        'editTotal': edit_total,
        'editFailures': edit_fail,
        'compactions': compactions,
        'gateBlocks': gate_blocks,
        'lintBlocks': lint_blocks,
        'gateTriggeredTasks': gate_triggered_tasks,
        'gateRescuedTasks': gate_rescued,
        'failureTaxonomy': dict(taxonomy),
        'byInstanceA1': {r['instance_id']: r['resolved'] for r in a1},
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--batch', required=True)
    ap.add_argument('--workers', type=int, default=1, help='Official scorer concurrency (1 avoids timing flakes under Rosetta)')
    ap.add_argument('--rescore', action='store_true')
    args = ap.parse_args()

    batch_dir = ROOT / 'runs' / args.batch
    manifest = json.loads((batch_dir / 'manifest.json').read_text(encoding='utf-8'))
    dataset_path = Path(manifest['datasetPath'])
    records = [json.loads(l) for l in (batch_dir / 'results.jsonl').read_text(encoding='utf-8').splitlines() if l.strip()]

    # Score each (arm, attempt) patch file with official Multi-SWE-bench harness
    resolved_lookup = {}
    patches_dir = batch_dir / 'patches'
    for arm_info in manifest['arms']:
        arm = arm_info['name']
        for attempt in range(1, manifest['repeat'] + 1):
            pfile = patches_dir / f'{arm}-a{attempt}.jsonl'
            if not pfile.exists():
                continue
            print(f"\n=== Official Multi-SWE-bench Scoring: arm={arm} attempt={attempt} ===", flush=True)
            resolved_ids = score_patch_file(dataset_path, pfile, batch_dir / 'official-scores' / f'{arm}-a{attempt}', args.workers, args.rescore)
            resolved_lookup[(arm, attempt)] = resolved_ids

    # Attach official verdict & failure taxonomy to each record
    by_arm = defaultdict(list)
    for rec in records:
        key = pr_key(rec['org'], rec['repo'], rec['number'])
        rset = resolved_lookup.get((rec['arm'], rec['attempt']), set())
        ac = rec.get('antiCheat') or {}
        clean = not ac.get('extraCommits') and (ac.get('leakedArtifactsCount') or 0) == 0
        rec['officialResolved'] = key in rset
        rec['resolved'] = bool(rec['officialResolved'] and rec['status'] == 'COMPLETED' and clean)
        rec['failureCategory'] = classify_failure(rec)
        by_arm[rec['arm']].append(rec)

    arm_summaries = [summarize_arm(a['name'], by_arm[a['name']]) for a in manifest['arms']]

    # Paired McNemar comparison if >= 2 arms
    paired = None
    if len(arm_summaries) >= 2:
        a, b = arm_summaries[0], arm_summaries[1]
        shared = sorted(set(a['byInstanceA1']).intersection(b['byInstanceA1']))
        both_pass = sum(1 for tid in shared if a['byInstanceA1'][tid] and b['byInstanceA1'][tid])
        a_only = sum(1 for tid in shared if a['byInstanceA1'][tid] and not b['byInstanceA1'][tid])
        b_only = sum(1 for tid in shared if not a['byInstanceA1'][tid] and b['byInstanceA1'][tid])
        both_fail = sum(1 for tid in shared if not a['byInstanceA1'][tid] and not b['byInstanceA1'][tid])
        p_val = exact_mcnemar_p(a_only, b_only)
        paired = {
            'armA': a['arm'], 'armB': b['arm'], 'sharedTasks': len(shared),
            'bothPass': both_pass, 'aOnly': a_only, 'bOnly': b_only, 'bothFail': both_fail,
            'deltaPassAt1': round((b['passAt1'] - a['passAt1']) * 100.0, 1),
            'mcnemarExactP': round(p_val, 5),
            'significantAt005': p_val < 0.05,
        }

    summary = {'manifest': manifest, 'arms': arm_summaries, 'pairedComparison': paired}
    (batch_dir / 'summary.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')

    # Render Markdown report
    lines = [
        f"# Pilot Multi-SWE-bench 评测报告 · `{args.batch}`",
        '',
        f"- **数据集**：`{manifest['datasetPath']}`（SHA256 `{manifest['datasetSha256'][:12]}`，共 {manifest['taskCount']} 题）",
        f"- **模型与端点**：`{manifest['model']}` @ `{manifest['endpoint']}`（`promptCaching={manifest['promptCaching']}`）",
        f"- **实验规范**：交错运行 `{manifest['interleave']}` | 并发 `{manifest['parallel']}` | 单题上限 `{manifest['timeoutMinutes']}m` | 零人工介入 | 官方 `multi_swe_bench` Docker 验收",
        '',
        '## 一、核心 6 项指标总表',
        '',
        '| 指标 | ' + ' | '.join(f"`{s['arm']}`" for s in arm_summaries) + ' |',
        '| --- | ' + ' | '.join('---:' for _ in arm_summaries) + ' |',
        '| **1. `pass@1` (95% Wilson CI)** | ' + ' | '.join(
            f"**{pct(s['resolvedA1'], s['tasksA1'])}** ({s['resolvedA1']}/{s['tasksA1']}, [{s['wilson95'][0]*100:.1f}%, {s['wilson95'][1]*100:.1f}%])"
            for s in arm_summaries
        ) + ' |',
        '| — Java / TypeScript 分语言 | ' + ' | '.join(
            f"Java {s['byLanguage']['java']['resolved']}/{s['byLanguage']['java']['total']} · TS {s['byLanguage']['typescript']['resolved']}/{s['byLanguage']['typescript']['total']}"
            for s in arm_summaries
        ) + ' |',
        '| **2. 重复稳定性 (`avg@k` / `pass^k`)** | ' + ' | '.join(
            f"avg@{s['stability']['k']} {s['stability']['avgAtK']*100:.1f}% / pass^{s['stability']['k']} {s['stability']['passHatK']*100:.1f}%"
            if s['stability'] else '— (k=1)'
            for s in arm_summaries
        ) + ' |',
        '| **3. Prompt Cache 命中率** | ' + ' | '.join(
            pct(s['totalCachedInputTokens'], s['totalInputTokens']) for s in arm_summaries
        ) + ' |',
        '| — 每成功任务未命中输入 Token（含失败分摊） | ' + ' | '.join(
            f"{s['uncachedPerResolved']:,}" if s['uncachedPerResolved'] is not None else '—' for s in arm_summaries
        ) + ' |',
        '| **4. 耗时 `p50 / p95` · 平均请求轮次** | ' + ' | '.join(
            f"{s['p50Seconds']}s / {s['p95Seconds']}s · {s['avgRequests']} reqs" for s in arm_summaries
        ) + ' |',
        '| **5. 编辑失配次数 / 总编辑次数** | ' + ' | '.join(
            f"{s['editFailures']} / {s['editTotal']} · 压缩 {s['compactions']} 次" for s in arm_summaries
        ) + ' |',
        '| **6. 门禁拦截打回 & 最终挽救任务数** | ' + ' | '.join(
            f"拦截 {s['gateBlocks'] + s['lintBlocks']} 次（涉及 {s['gateTriggeredTasks']} 题，最终通过 {s['gateRescuedTasks']} 题）"
            for s in arm_summaries
        ) + ' |',
        '',
    ]

    if paired:
        lines += [
            '## 二、配对统计检验（McNemar Exact Test）',
            '',
            f"- 对比：`{paired['armA']}` vs `{paired['armB']}`（共同题目 {paired['sharedTasks']} 题）",
            f"- `pass@1` 差值：**{paired['deltaPassAt1']:+.1f} pp**",
            f"- 2×2 分布：双过 `{paired['bothPass']}` · 仅 `{paired['armA']}` 过 `{paired['aOnly']}` · 仅 `{paired['armB']}` 过 `{paired['bOnly']}` · 双挂 `{paired['bothFail']}`",
            f"- 双侧精确 McNemar `p` 值：**`{paired['mcnemarExactP']}`**（{'满足 p < 0.05 统计显著' if paired['significantAt005'] else '未达 p < 0.05 显著性阈值，按趋势解读'}）",
            '',
        ]

    lines += ['## 三、失败归因分布（Failure Taxonomy，Attempt 1）', '']
    cats = sorted({c for s in arm_summaries for c in s['failureTaxonomy'].keys()})
    lines.append('| 类别 | ' + ' | '.join(f"`{s['arm']}`" for s in arm_summaries) + ' |')
    lines.append('| --- | ' + ' | '.join('---:' for _ in arm_summaries) + ' |')
    for c in cats:
        lines.append(f"| `{c}` | " + ' | '.join(str(s['failureTaxonomy'].get(c, 0)) for s in arm_summaries) + ' |')

    lines += ['', '## 四、版本指纹（可复现审计）', '']
    for a in manifest['arms']:
        lines.append(f"- `{a['name']}` (`profile={a['profile']}`): `gitSha={a['pilotGitSha']}` · `jarSha256={a['jarSha256'][:16]}` · `extraArgs={a['extraArgs']}`")

    report_path = batch_dir / 'report.md'
    report_path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(f"\nReport written to {report_path}")


if __name__ == '__main__':
    main()
