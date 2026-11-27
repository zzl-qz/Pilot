#!/usr/bin/env python3
"""
Zero-token preflight verifier for Multi-SWE-bench tasks on local Docker (OrbStack).

For each task in the target dataset:
  1. Ensures the official mswebench/* Docker image is pulled locally.
  2. Runs official Multi-SWE-bench evaluation (mode=instance_only) with:
       - golden fix_patch  -> MUST be resolved
       - empty fix_patch   -> MUST be unresolved (optional with --check-empty)
  3. Writes a preflight summary JSON listing verified vs broken tasks.

Usage:
  .venv/bin/python scripts/preflight-images.py --dataset datasets/flash-java-ts-40.jsonl --workers 2
"""
import argparse
import concurrent.futures
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PY = sys.executable


def image_name(row: dict) -> str:
    return f"mswebench/{row['org'].lower()}_m_{row['repo'].lower()}:pr-{row['number']}"


def image_exists(img: str) -> bool:
    r = subprocess.run(['docker', 'image', 'inspect', img], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return r.returncode == 0


def pull_image(img: str) -> bool:
    print(f"[pull] {img}", flush=True)
    r = subprocess.run(['docker', 'pull', img])
    return r.returncode == 0


def run_msb_eval(dataset_path: Path, patch_path: Path, work_dir: Path, out_dir: Path, log_dir: Path, workers: int) -> dict:
    work_dir.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    log_dir.mkdir(parents=True, exist_ok=True)
    cfg_path = out_dir / 'msb-config.json'
    cfg = {
        'mode': 'instance_only',
        'workdir': str(work_dir),
        'patch_files': [str(patch_path)],
        'dataset_files': [str(dataset_path)],
        'force_build': False,
        'output_dir': str(out_dir),
        'specifics': [],
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
    cfg_path.write_text(json.dumps(cfg, indent=2), encoding='utf-8')
    env = {'PYTHONWARNINGS': 'ignore'}
    import os
    full_env = {**os.environ, **env}
    subprocess.run([PY, '-m', 'multi_swe_bench.harness.run_evaluation', '--config', str(cfg_path)], check=True, env=full_env)
    subprocess.run(
        [
            PY, '-m', 'multi_swe_bench.harness.gen_report',
            '--mode', 'evaluation',
            '--workdir', str(work_dir),
            '--output_dir', str(out_dir),
            '--dataset_files', str(dataset_path),
            '--max_workers', str(workers),
            '--log_dir', str(log_dir),
            '--log_level', 'INFO',
        ],
        check=True,
        env=full_env,
    )
    return json.loads((out_dir / 'final_report.json').read_text(encoding='utf-8'))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--dataset', required=True)
    ap.add_argument('--workers', type=int, default=2)
    ap.add_argument('--pull-parallel', type=int, default=3)
    ap.add_argument('--limit', type=int, default=0)
    ap.add_argument('--pull-only', action='store_true')
    ap.add_argument('--check-empty', action='store_true')
    ap.add_argument('--tag', default='preflight-40')
    args = ap.parse_args()

    ds_path = Path(args.dataset).resolve()
    rows = [json.loads(line) for line in ds_path.read_text(encoding='utf-8').splitlines() if line.strip()]
    if args.limit > 0:
        rows = rows[: args.limit]

    base = ROOT / 'preflight' / args.tag
    base.mkdir(parents=True, exist_ok=True)

    to_pull = []
    for i, r in enumerate(rows, 1):
        img = image_name(r)
        if image_exists(img):
            print(f"[{i}/{len(rows)}] cached: {img}", flush=True)
        else:
            to_pull.append((r['instance_id'], img))

    missing = []
    if to_pull:
        print(f"Pulling {len(to_pull)} images with concurrency={args.pull_parallel}...", flush=True)
        def _pull_one(item):
            iid, img = item
            r = subprocess.run(['docker', 'pull', '-q', img], capture_output=True, text=True)
            ok = r.returncode == 0
            print(f"[pull {'OK' if ok else 'FAIL'}] {img}", flush=True)
            return iid, ok
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.pull_parallel) as ex:
            for iid, ok in ex.map(_pull_one, to_pull):
                if not ok:
                    missing.append(iid)

    if missing:
        print(f"ERROR: failed to pull {len(missing)} images: {missing}", file=sys.stderr)
        sys.exit(1)

    if args.pull_only:
        print(f"All {len(rows)} images cached locally.")
        return

    sub_ds = base / 'dataset.jsonl'
    sub_ds.write_text('\n'.join(json.dumps(r, ensure_ascii=False) for r in rows) + '\n', encoding='utf-8')

    golden_patch = base / 'golden.jsonl'
    golden_patch.write_text(
        '\n'.join(
            json.dumps({'org': r['org'], 'repo': r['repo'], 'number': r['number'], 'fix_patch': r['fix_patch']})
            for r in rows
        ) + '\n',
        encoding='utf-8',
    )
    golden_report = run_msb_eval(sub_ds, golden_patch, base / 'work-golden', base / 'out-golden', base / 'logs-golden', args.workers)
    print(f"\n[golden] resolved={golden_report.get('resolved_instances')}/{len(rows)}")

    empty_report = None
    if args.check_empty:
        empty_patch = base / 'empty.jsonl'
        empty_patch.write_text(
            '\n'.join(
                json.dumps({'org': r['org'], 'repo': r['repo'], 'number': r['number'], 'fix_patch': ''})
                for r in rows
            ) + '\n',
            encoding='utf-8',
        )
        empty_report = run_msb_eval(sub_ds, empty_patch, base / 'work-empty', base / 'out-empty', base / 'logs-empty', args.workers)
        print(f"[empty ] resolved={empty_report.get('resolved_instances')}/{len(rows)} (expect 0)")

    summary = {
        'dataset': str(ds_path),
        'total': len(rows),
        'golden': golden_report,
        'empty': empty_report,
    }
    (base / 'preflight-summary.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(f"Summary saved to {base / 'preflight-summary.json'}")


if __name__ == '__main__':
    main()
