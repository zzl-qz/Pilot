# Pilot × Multi-SWE-bench Evaluation Suite

[English](README.md) | [简体中文](README.zh-CN.md)

This directory contains the reproducible offline evaluation harness, anti-cheating container entrypoint, stratified task manifests, and sanitized benchmark reports for evaluating **Pilot** on [ByteDance-Seed/Multi-SWE-bench-flash](https://huggingface.co/datasets/ByteDance-Seed/Multi-SWE-bench-flash) (Java & TypeScript).

## Design Principles

1. **Zero Data Leakage & Anti-Cheat**: Each task executes inside an ephemeral `mswebench/*` Docker container where `/home/*.patch` and `/home/*run.sh` are removed and `.git` history is destroyed and rebuilt into a single `baseline` commit before Pilot starts. Every run is automatically audited (`commitCount == 1`, `leakedArtifactsCount == 0`).
2. **Official Hidden-Test Scoring**: Submitted patches are evaluated solely by the upstream `multi_swe_bench` Docker harness (`mode=instance_only`, `--workers 1` to eliminate CPU timing flakes).
3. **Controlled Paired A/B (`--interleave`)**: Comparing two Pilot versions or harness profiles uses the exact same model, endpoint, and task set, interleaved task-by-task (`A(t1), B(t1), A(t2), B(t2), ...`) to neutralize time-of-day API drift.
4. **Full Cost & Stability Transparency**: Token accounting amortizes failed runs over resolved tasks; stability is evaluated via independent `k=3` repeats reporting `avg@3`, `pass^3` (3/3 consistency), and `pass@3`.
5. **Zero Secrets in Artifacts**: API keys are passed via ephemeral `chmod 0600` env files deleted immediately after `docker run`, and never written to manifests, logs, or reports.

## Directory Layout

```text
evaluation/multi-swe-bench/
├── README.md                                        # This guide (English)
├── README.zh-CN.md                                  # Chinese companion guide
├── scripts/
│   ├── build-pilot-jar.sh                           # Read-only git archive JAR builder
│   ├── container-pilot-entrypoint.sh                # Anti-cheat container entrypoint + native JDK pinning
│   ├── select-tasks.py                              # Stratified 40-task selector (Java 20 + TS 20)
│   ├── select-stability-12.py                       # Stratified 12-task k=3 subset selector
│   ├── preflight-images.py                          # Zero-token Golden Patch Docker preflight verifier
│   ├── run-msb-eval.mjs                             # Main interleaved evaluation runner
│   └── score-and-report.py                          # Official Multi-SWE-bench scorer + statistical report
├── datasets/
│   ├── flash-java-ts-40-verified.meta.json          # Locked 40-task provenance & Golden 40/40 verification
│   └── flash-stability-12.ids.json                  # Locked 12-task stability subset IDs
└── results/
    └── 2026-11-27-0caa2257-vs-9b862d35/
        ├── REPORT.md                                # Full benchmark report (English)
        ├── REPORT.zh-CN.md                          # Full benchmark report (Chinese)
        ├── summary.json                             # Machine-readable aggregate metrics
        ├── per-task-matrix.json                     # All 40 paired tasks + 12×3 stability verdicts
        └── recovery-smoke.json                      # Deterministic 8/8 crash & SSE recovery smoke report
```

## Latest Archived Results

See [2026-11-27 Vertical A/B & Stability Report](results/2026-11-27-0caa2257-vs-9b862d35/REPORT.md) (`v0-0caa2257` vs `v1-9b862d35`, fixed model `gpt-6.1-sol`, 40 verified Java/TS tasks + 12-task `k=3` stability + 8/8 recovery smoke).

## How to Reproduce

### Prerequisites
- Docker (Docker Desktop, OrbStack, or native Linux Docker Engine)
- Node.js 22+ and Python 3.10+
- JDK 25 (for building Pilot server JARs) + an Eclipse Temurin 25 Linux x64 JRE extracted to `evaluation/multi-swe-bench/pilot-jre25-linux-x64` (mounted read-only into `linux/amd64` task containers)

### Step-by-Step

```bash
cd evaluation/multi-swe-bench

# 1. Install official multi-swe-bench harness in an isolated virtualenv
python3 -m venv .venv
git clone https://github.com/multi-swe-bench/multi-swe-bench.git
.venv/bin/pip install -e ./multi-swe-bench

# 2. Download ByteDance-Seed/Multi-SWE-bench-flash JSONL into datasets/multi_swe_bench_flash.jsonl
#    and materialize the locked 40-task set (matching datasets/flash-java-ts-40-verified.meta.json)
.venv/bin/python scripts/select-tasks.py

# 3. Zero-token Golden Patch preflight (verifies all Docker images & tests pass locally)
.venv/bin/python scripts/preflight-images.py --dataset datasets/flash-java-ts-40.jsonl --workers 1

# 4. Build immutable Pilot JARs from any two git commits (read-only git archive)
bash scripts/build-pilot-jar.sh 0caa2257 pilot-0caa2257
bash scripts/build-pilot-jar.sh HEAD pilot-head

# 5. Run interleaved A/B evaluation (or same-version minimal vs full harness ablation)
export PILOT_MODEL_ENDPOINT="https://api.openai.com/v1/chat/completions"
export PILOT_MODEL_MODEL="your-model-id"
export PILOT_MODEL_API_KEY="your-api-key"

node scripts/run-msb-eval.mjs \
  --batch ab-run \
  --dataset datasets/flash-java-ts-40.jsonl \
  --arm baseline:jars/pilot-0caa2257.jar:full \
  --arm candidate:jars/pilot-head.jar:full \
  --interleave --parallel 2 --timeout 30

# 6. Score with official Multi-SWE-bench Docker harness & generate report
.venv/bin/python scripts/score-and-report.py --batch ab-run --workers 1
```

> **Note on Transient Runtime Artifacts**: Large artifacts (`runs/`, `jars/`, `datasets/*.jsonl`, `pilot-jre*/`, `.venv/`, `multi-swe-bench/`) are git-ignored so the repository stays clean and secret-free.
