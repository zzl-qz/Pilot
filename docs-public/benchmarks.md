# Benchmarks & Verification

[English](benchmarks.md) | [简体中文](zh-CN/benchmarks.md)

> Last updated: 2026-11-27  
> Reproducible suite & raw sanitized artifacts: [evaluation/multi-swe-bench/](../evaluation/multi-swe-bench/README.md) · [Full Report (2026-11-27)](../evaluation/multi-swe-bench/results/2026-11-27-0caa2257-vs-9b862d35/REPORT.md)

Pilot evaluates coding capability and runtime reliability using **reproducible, container-isolated, hidden-test benchmarks** rather than cherry-picked demos.

## 1. Benchmark Methodology

- **Dataset**: Official [ByteDance-Seed/Multi-SWE-bench-flash](https://huggingface.co/datasets/ByteDance-Seed/Multi-SWE-bench-flash), stratified 40-task subset (**20 Java + 20 TypeScript** across 7 real open-source repositories: `fasterxml/jackson-databind`, `fasterxml/jackson-dataformat-xml`, `google/gson`, `googlecontainertools/jib`, `vuejs/core`, `darkreader/darkreader`, `mui/material-ui`).
- **Pre-Eval Golden Verification (0-token)**: **40/40 (100%)** tasks verified to pass official Docker golden-patch tests locally before running any LLM calls ([flash-java-ts-40-verified.meta.json](../evaluation/multi-swe-bench/datasets/flash-java-ts-40-verified.meta.json)).
- **Anti-Cheat Isolation**: Every run boots an ephemeral `mswebench/*` Docker container with all `/home/*.patch` and `/home/*run.sh` files deleted and git history rebuilt into a single `baseline` commit. Audit result across all 104 runs: **0 violations**.
- **Strict Paired A/B (`--interleave`)**: Compares `v0-0caa2257` (baseline before Wave 1–4 & Harness v1–v6) vs. `v1-9b862d35` (`master`) using the **exact same model (`gpt-6.1-sol`) and endpoint**, interleaved task-by-task (`A(t1), B(t1), ...`) with a 30-minute cap and zero human intervention.
- **Official Docker Scoring**: Every patch is graded strictly by upstream `multi_swe_bench` (`--workers 1`, **0 scorer anomalies**).

## 2. Headline Results (Fixed Model `gpt-6.1-sol`, 40 Verified Tasks)

| Metric | `v0-0caa2257` (Before) | `v1-9b862d35` (After) | Change |
| --- | ---: | ---: | ---: |
| **Official `pass@1` (95% Wilson CI)** | **50.0%** (20/40, `[35.2%, 64.8%]`) | **60.0%** (24/40, `[44.6%, 73.7%]`) | **+10.0 pp** |
| **Strict `COMPLETED & Pass` (95% CI)** | **45.0%** (18/40, `[30.7%, 60.2%]`) | **57.5%** (23/40, `[42.2%, 71.5%]`) | **+12.5 pp** |
| — **Java Subset (20 tasks)** | 50.0% (10/20) | **75.0%** (15/20) | **+25.0 pp (5W / 0L)** |
| — **TypeScript Subset (20 tasks)** (Official / Strict) | 50.0% / 40.0% | 45.0% / 40.0% | -5.0 pp / 0.0 pp |
| **Avg Requests Before First Edit** | 9.87 | **5.85** | **-40.7%** |
| **Avg Model Requests / Task** | 29.0 | **23.1** | **-20.3%** |
| **Total Output Tokens** | 222,260 | **182,995** | **-17.7%** |
| **Prompt Cache Hit Rate** | 59.2% | **61.8%** | **+2.5 pp** |
| **Harness Edit-Loop / No-Patch Failures** | 2 / 1 | **0 / 0** | **Eliminated** |

### Paired Significance (Exact Two-Sided McNemar Test)

- **All 40 tasks**: Official 2×2 = `18 both / 2 v0-only / 6 v1-only / 14 neither` (`p = 0.289`); Strict 2×2 = `16 / 2 / 7 / 15` (`p = 0.180`).
- **Java 20 tasks**: `10 both / 0 v0-only / 5 v1-only / 5 neither` (**zero regressions**, `p = 0.0625`).

## 3. Repeat Consistency (`k=3`, 12 Stratified Tasks = 36 Independent Runs)

Evaluated on `v1-9b862d35` across 6 Java + 6 TypeScript tasks ([flash-stability-12.ids.json](../evaluation/multi-swe-bench/datasets/flash-stability-12.ids.json)):

- **Official Standard**: **`avg@3 = 55.6%`** (20/36) · **`pass^3` (3/3 consistent) = `50.0%`** (6/12) · **`pass@3` (≥1/3) = `58.3%`** (7/12).
- **Determinism**: **11/12 (91.7%)** tasks produced identical pass/fail outcomes across all 3 independent runs (**6/6 Java tasks had 0 variance**, `avg@3 = pass^3 = 66.7%`). Only `vuejs__core-11491` varied (`TIMEOUT / PASS / PASS`).

## 4. Deterministic Crash & SSE Recovery Verification (0-Token)

Running `evaluation/harness/recovery-smoke.mjs` against `pilot-9b862d35.jar` passed **8/8 assertions** ([recovery-smoke.json](../evaluation/multi-swe-bench/results/2026-11-27-0caa2257-vs-9b862d35/recovery-smoke.json)):
- Two concurrently active sessions survived hard process termination and resumed independently.
- Completed durable tool results were injected on resume with **zero replayed side effects**.
- SSE disconnects replayed strictly monotonic cursors with **no missing or duplicate events**.

## 5. Honest Boundaries & Known Follow-Ups

- **Model/Endpoint Scope**: Evaluated on `gpt-6.1-sol` via `https://tokenmarket.cheap`. Paired interleaved deltas measure Pilot's harness engineering impact under a fixed model; absolute scores should not be compared across different models or endpoints.
- **Statistical Power**: With `n = 40`, full-set McNemar `p` is above `0.05`; results are reported transparently alongside 95% Wilson CIs, `k=3` variance, and process metrics.
- **Trace-Driven Next Fixes**: Trace audits directly identified (1) `CompletionGate` missing Mocha/custom script invocations on `mui__material-ui-23778` (3/3 passing patches marked `BLOCKED`), and (2) `edit_file` post-edit lint invoking global `eslint` instead of local `node_modules/.bin` / `pnpm exec` in monorepos.

To inspect every single task verdict or reproduce the benchmark locally, see [evaluation/multi-swe-bench/README.md](../evaluation/multi-swe-bench/README.md) and [per-task-matrix.json](../evaluation/multi-swe-bench/results/2026-11-27-0caa2257-vs-9b862d35/per-task-matrix.json).
