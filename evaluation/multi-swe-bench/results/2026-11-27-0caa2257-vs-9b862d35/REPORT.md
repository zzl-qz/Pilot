# Pilot Benchmark Report — Multi-SWE-bench-flash (Java & TypeScript)

[English](REPORT.md) | [简体中文](REPORT.zh-CN.md)

> **Date**: 2026-11-27  
> **Comparison**: `v0-0caa2257` (baseline before Wave 1–4 & Harness v1–v6) vs. `v1-9b862d35` (current `master`)  
> **Raw Artifacts**: [summary.json](summary.json) · [per-task-matrix.json](per-task-matrix.json) · [recovery-smoke.json](recovery-smoke.json)

## 1. Experimental Protocol & Audit

- **Benchmark**: Official `ByteDance-Seed/Multi-SWE-bench-flash` stratified 40-task split (20 Java + 20 TypeScript across 7 real open-source repositories: `fasterxml/jackson-databind` 9, `fasterxml/jackson-dataformat-xml` 5, `google/gson` 3, `googlecontainertools/jib` 3, `vuejs/core` 17, `darkreader/darkreader` 2, `mui/material-ui` 1), locked manifest SHA-256 `3bc04867e0bf`.
- **Phase 0 Golden Preflight (0-token)**: All **40/40 (100%)** tasks passed official Docker golden-patch verification locally prior to running any LLM calls. Pre-evaluation exclusions (Rosetta timer-flaky `Suspense.spec.ts` and an upstream Gson parser issue) were locked before evaluation in [flash-java-ts-40-verified.meta.json](../../datasets/flash-java-ts-40-verified.meta.json).
- **Controlled Variables**: Identical model (`gpt-6.1-sol`), identical endpoint (`https://tokenmarket.cheap/`), 30-minute per-task timeout, zero human intervention, strict task-level interleaving (`--interleave`), ephemeral sanitized Docker containers, **0/104 anti-cheat violations**, and **0 official scorer anomalies** (`--workers 1`).
- **Phase 1 Crash & SSE Recovery Smoke (0-token)**: `evaluation/harness/recovery-smoke.mjs` passed **8/8** deterministic assertions on `pilot-9b862d35.jar` (concurrent active sessions, hard process kill & resume, zero replayed side effects for completed tools, and lossless/deduplicated SSE cursor replay).

## 2. Main Interleaved A/B Experiment (40 Tasks × 2 Arms = 80 Runs)

> **Metric Definitions**: **Official `pass@1`** follows the standard Multi-SWE-bench definition (submitted patch passes the hidden Docker test suite with 0 anti-cheat violations). **Strict `COMPLETED & Pass`** additionally requires Pilot's own internal completion status to be `COMPLETED` (penalizing valid patches over-blocked as `BLOCKED` by internal gates). Both are reported transparently.

| Metric | `v0-0caa2257` (Before) | `v1-9b862d35` (After) | Delta |
| --- | ---: | ---: | ---: |
| **Official `pass@1` (95% Wilson CI)** | **50.0%** (20/40, `[35.2%, 64.8%]`) | **60.0%** (24/40, `[44.6%, 73.7%]`) | **+10.0 pp** |
| **Strict `COMPLETED & Pass` (95% CI)** | **45.0%** (18/40, `[30.7%, 60.2%]`) | **57.5%** (23/40, `[42.2%, 71.5%]`) | **+12.5 pp** |
| — **Java Subset (20 tasks)** (Official = Strict) | 50.0% (10/20) | **75.0%** (15/20) | **+25.0 pp (5W / 0L)** |
| — **TypeScript Subset (20 tasks)** (Official / Strict) | 50.0% (10/20) / 40.0% (8/20) | 45.0% (9/20) / 40.0% (8/20) | -5.0 pp / 0.0 pp |
| **Avg Requests Before First Edit** | 9.87 (median 7) | **5.85** (median 5) | **-40.7%** |
| **Avg Model Requests / Task** | 29.0 (total 1,160) | **23.1** (total 924) | **-20.3%** |
| **Total Output Tokens** | 222,260 | **182,995** | **-17.7%** |
| **Prompt Cache Hit Rate** | 59.2% | **61.8%** | **+2.5 pp** |
| **Uncached Input Tokens / Resolved** (Strict / Official, failed amortized) | 914,372 / 822,935 | 894,835 / 857,550 | -2.1% / +4.2% |
| **Latency `p50 / p95`** (Java `p50`) | 210s / 1,285s (Java 436s) | 241s / 1,590s (Java **275s**) | Java p50 **-36.9%** |
| **Context Compactions / Tool Errors / Shell-Read Workarounds** | 20 / 159 / 10 | **0 / 132 / 2** | **-100% / -17% / -80%** |

### Paired Statistical Analysis (Exact Two-Sided McNemar Test)

| Slice | `v0` | `v1` | Delta | 2×2 (Both / `v0` only / `v1` only / Neither) | Exact McNemar `p` |
| --- | ---: | ---: | ---: | :---: | ---: |
| All 40 (Official `pass@1`) | 20/40 | 24/40 | +10.0 pp | 18 / 2 / 6 / 14 | `0.28906` |
| All 40 (Strict `COMPLETED & Pass`) | 18/40 | 23/40 | +12.5 pp | 16 / 2 / 7 / 15 | `0.17969` |
| **Java 20** (Official = Strict) | 10/20 | **15/20** | **+25.0 pp** | **10 / 0 / 5 / 5** | **`0.06250`** |
| TypeScript 20 (Official) | 10/20 | 9/20 | -5.0 pp | 8 / 2 / 1 / 9 | `1.00000` |

- **`v1`-only Official Wins (6)**: `fasterxml__jackson-databind-4131`, `4228`, `4257`, `fasterxml__jackson-dataformat-xml-544`, `590`, `vuejs__core-10416` (plus `vuejs__core-8470` under Strict mode, where `v0` was over-blocked by gates while `v1` completed cleanly).
- **`v0`-only Official Wins (2)**: `vuejs__core-11491` (`v1` hit the 30m timeout on attempt 1, then passed **2/2** independent runs in Phase 3) and `vuejs__core-8911` (`v1` dropped the `/g` flag on `escapeSymbolsRE`, causing 1/2743 failure in `cssVars.spec.ts`).

### Per-Repository Breakdown

| Repository | Tasks | `v0` Official (Strict) | `v1` Official (Strict) |
| --- | ---: | ---: | ---: |
| `fasterxml/jackson-databind` | 9 | 4 (4) | **7 (7)** |
| `fasterxml/jackson-dataformat-xml` | 5 | 1 (1) | **3 (3)** |
| `google/gson` | 3 | 3 (3) | **3 (3)** |
| `googlecontainertools/jib` | 3 | 2 (2) | **2 (2)** |
| `darkreader/darkreader` | 2 | 1 (1) | 1 (1) |
| `mui/material-ui` | 1 | 1 (0) | 1 (0) |
| `vuejs/core` | 17 | 8 (7) | 7 (7) |

### Failure Taxonomy (40 Tasks)

| Category | `v0-0caa2257` | `v1-9b862d35` | Notes |
| --- | ---: | ---: | --- |
| `RESOLVED` (Strict `COMPLETED` + Official Pass) | 18 | **23** | +5 resolved |
| `GATE_OVERBLOCKED_BUT_PATCH_PASS` | 2 | 1 | Patch passed official hidden tests, but internal gate marked `BLOCKED` (`mui-23778` Mocha command missed; `v0` also over-blocked `vue-8470`) |
| `HARNESS_NON_COMPLETED` | 4 | **2** | `v1` cleanly self-reported `BLOCKED / TASK_INCOMPLETE` on `vue-8535` instead of guessing; 1 timeout on `vue-11491` |
| `HARNESS_EDIT_LOOP` | 2 | **0** | Eliminated |
| `HARNESS_NO_PATCH` | 1 | **0** | Eliminated |
| `HARNESS_UNVERIFIED_END` | 3 | 3 | Follow-up gate tightening opportunity |
| `MODEL_LOGIC_OR_EDGE_CASE` | 10 | 11 | Model semantic/compilation boundary |

## 3. Repeat Consistency Evaluation (`v1-9b862d35`, 12 Tasks × `k=3` = 36 Runs)

Stratified 12-task subset (6 Java + 6 TypeScript, `seed=42`; attempt 1 from Phase 2 + attempts 2 & 3 executed independently in Phase 3):

- **Official Standard**: **`avg@3 = 55.6%`** (20/36) · **`pass^3` (3/3 consistent) = `50.0%`** (6/12) · **`pass@3` (≥1/3) = `58.3%`** (7/12).
- **Low Variance**: **11/12 (91.7%)** tasks were 100% deterministic across all 3 runs (`6` tasks `3/3` pass, `5` tasks `0/3` fail); only **1/12** task varied (`vuejs__core-11491`: `TIMEOUT / PASS / PASS` = `2/3`). All **6/6 Java tasks** had **zero variance** (`avg@3 = pass^3 = pass@3 = 66.7%`).
- **Strict Completed**: `avg@3 = 47.2%` · `pass^3 = 41.7%` · `pass@3 = 50.0%` (the entire gap comes from `mui__material-ui-23778` passing official tests 3/3 times while being marked `BLOCKED` by `CompletionGate`).

| Instance | Language | Run 1 | Run 2 | Run 3 | Official | Strict |
| --- | --- | :---: | :---: | :---: | :---: | :---: |
| `fasterxml__jackson-databind-4159` | Java | FAIL | FAIL (`INTERRUPTED`) | FAIL | **0/3** | 0/3 |
| `fasterxml__jackson-databind-4360` | Java | PASS | PASS | PASS | **3/3** | 3/3 |
| `fasterxml__jackson-databind-4615` | Java | FAIL | FAIL | FAIL | **0/3** | 0/3 |
| `google__gson-1093` | Java | PASS | PASS | PASS | **3/3** | 3/3 |
| `google__gson-1391` | Java | PASS | PASS | PASS | **3/3** | 3/3 |
| `google__gson-1555` | Java | PASS | PASS | PASS | **3/3** | 3/3 |
| `darkreader__darkreader-6747` | TypeScript | FAIL | FAIL | FAIL | **0/3** | 0/3 |
| `mui__material-ui-23778` | TypeScript | PASS (`BLOCKED`) | PASS (`BLOCKED`) | PASS (`BLOCKED`) | **3/3** | 0/3 |
| `vuejs__core-11491` | TypeScript | FAIL (`TIMEOUT`) | PASS | PASS | **2/3** | 2/3 |
| `vuejs__core-11761` | TypeScript | PASS | PASS | PASS | **3/3** | 3/3 |
| `vuejs__core-11854` | TypeScript | FAIL | FAIL | FAIL | **0/3** | 0/3 |
| `vuejs__core-11899` | TypeScript | FAIL | FAIL | FAIL | **0/3** | 0/3 |

## 4. Actionable Engineering Findings & Scope Boundaries

### Findings Surfaced Directly by Trace Audit
1. **`CompletionGate` Mocha / Custom Script Detection**: On `mui__material-ui-23778`, `v1` passed 74 targeted unit tests and resolved the bug 3/3 times, yet `CompletionGate` failed to recognize the Mocha test invocation and marked all 3 runs `BLOCKED`.
2. **Monorepo Post-Edit Lint Invocation**: On `vuejs/core`, post-edit auto-lint attempted global `eslint` instead of local `node_modules/.bin/eslint` / `pnpm exec eslint`, adding noise to tool outputs.
3. **Cross-Package Regression Coverage in TS Monorepos**: `vuejs__core-8911` failed 1/2743 tests in a sibling package (`cssVars.spec.ts`) because only the locally edited file's tests were executed.

### Transparent Boundaries
- Conducted via proxy endpoint `https://tokenmarket.cheap` (`gpt-6.1-sol`). Results reflect paired **Harness engineering delta** under a fixed model/endpoint, not cross-model leaderboard claims.
- With `n = 40`, exact two-sided McNemar `p = 0.289` (official) / `0.180` (strict) and `p = 0.0625` on Java (`n = 20`, 5W/0L); interpreted honestly as a strong positive engineering trend rather than `p < 0.05` significance.
- On Apple Silicon OrbStack Rosetta 2, the final 3 Vue slots in Phase 3 wrapped git-ignored `node_modules/.bin/vitest` (Vitest ≥ 1.x) with `--pool=threads` to avoid a Rosetta 2 `readlinkat(/proc/<pid>/exe)` assertion crash; repository source code, `git diff`, and official Docker scoring containers were untouched.
