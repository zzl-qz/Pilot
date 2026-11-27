# Pilot 基准评测报告 — Multi-SWE-bench-flash（Java / TypeScript）

[English](REPORT.md) | [简体中文](REPORT.zh-CN.md)

> **日期**：2026-11-27  
> **对照版本**：`v0-0caa2257`（Wave 1–4 与 Harness v1–v6 前基线）vs `v1-9b862d35`（当前 `master`）  
> **原始脱敏数据**：[summary.json](summary.json) · [per-task-matrix.json](per-task-matrix.json) · [recovery-smoke.json](recovery-smoke.json)

## 1. 实验设计与合规审计

- **基准题库**：官方 `ByteDance-Seed/Multi-SWE-bench-flash` 分层抽样 40 题（Java 20 + TypeScript 20，覆盖 7 个真实开源仓库：`jackson-databind` 9、`jackson-dataformat-xml` 5、`gson` 3、`jib` 3、`vuejs/core` 17、`darkreader` 2、`material-ui` 1），锁定清单 SHA-256 `3bc04867e0bf`。
- **Phase 0 基准自检（0-token）**：全部 **40/40（100%）** 题在跑真模型前完成本机 Docker Golden Patch 官方验收；评测前客观剔除项与回填记录锁定于 [flash-java-ts-40-verified.meta.json](../../datasets/flash-java-ts-40-verified.meta.json)。
- **固定变量与隔离**：同模型（`gpt-6.1-sol`）、同端点（`https://tokenmarket.cheap/`）、单题 30 分钟上限、零人工介入、按题严格交错（`--interleave`）、每题销毁型容器洗白、**0/104 作弊违规**、**0 官方评分器异常**（`--workers 1`）。
- **Phase 1 崩溃恢复冒烟（0-token）**：`evaluation/harness/recovery-smoke.mjs` 在 `pilot-9b862d35.jar` 上 **8/8 确定性断言全部通过**（双并发会话强杀恢复、已完成工具零副作用重放、SSE 断线游标回放无漏无重）。

## 2. 主实验交错 A/B 对照（40 题 × 双组 = 80 轮）

> **双口径透明披露**：**官方标准 `pass@1`** 按 Multi-SWE-bench 通用定义（提交补丁通过官方隐藏测试集且 0 作弊）；**严格端到端 `COMPLETED & Pass`** 额外要求 Pilot 自身以 `COMPLETED` 终态结束（不计入被内部门禁误拦为 `BLOCKED` 的有效补丁）。

| 核心指标 | `v0-0caa2257`（优化前） | `v1-9b862d35`（优化后） | 净变化 |
| --- | ---: | ---: | ---: |
| **官方标准 `pass@1` (95% Wilson CI)** | **50.0%** (20/40, `[35.2%, 64.8%]`) | **60.0%** (24/40, `[44.6%, 73.7%]`) | **+10.0 pp** |
| **严格端到端 `COMPLETED & Pass` (95% CI)** | **45.0%** (18/40, `[30.7%, 60.2%]`) | **57.5%** (23/40, `[42.2%, 71.5%]`) | **+12.5 pp** |
| — **Java 子集 (20 题)**（官方 = 严格） | 50.0% (10/20) | **75.0%** (15/20) | **+25.0 pp（5 胜 0 负）** |
| — **TypeScript 子集 (20 题)**（官方 / 严格） | 50.0% (10/20) / 40.0% (8/20) | 45.0% (9/20) / 40.0% (8/20) | -5.0 pp / 0.0 pp |
| **首次编辑前定位轮次** | 9.87（中位数 7） | **5.85**（中位数 5） | **-40.7%** |
| **平均模型请求轮次 / 题** | 29.0（总 1,160） | **23.1**（总 924） | **-20.3%** |
| **总输出 Token** | 222,260 | **182,995** | **-17.7%** |
| **Prompt Cache 命中率** | 59.2% | **61.8%** | **+2.5 pp** |
| **每成功任务未命中输入 Token**（严格 / 官方，含失败分摊） | 914,372 / 822,935 | 894,835 / 857,550 | -2.1% / +4.2% |
| **单题耗时 `p50 / p95`**（Java `p50`） | 210s / 1,285s（Java 436s） | 241s / 1,590s（Java **275s**） | Java p50 **-36.9%** |
| **上下文压缩 / 工具错误 / Shell 绕过读** | 20 / 159 / 10 | **0 / 132 / 2** | **-100% / -17% / -80%** |

### 配对统计检验（双侧精确 McNemar 检验）

| 维度 | `v0` | `v1` | 差值 | 2×2（双过 / 仅 `v0` / 仅 `v1` / 双挂） | 精确 McNemar `p` |
| --- | ---: | ---: | ---: | :---: | ---: |
| 全集 40 题（官方标准） | 20/40 | 24/40 | +10.0 pp | 18 / 2 / 6 / 14 | `0.28906` |
| 全集 40 题（严格端到端） | 18/40 | 23/40 | +12.5 pp | 16 / 2 / 7 / 15 | `0.17969` |
| **Java 20 题**（官方 = 严格） | 10/20 | **15/20** | **+25.0 pp** | **10 / 0 / 5 / 5** | **`0.06250`** |
| TypeScript 20 题（官方标准） | 10/20 | 9/20 | -5.0 pp | 8 / 2 / 1 / 9 | `1.00000` |

### 失败归因分布（Failure Taxonomy，40 题）

| 归因类别 | `v0-0caa2257` | `v1-9b862d35` | 说明 |
| --- | ---: | ---: | --- |
| `RESOLVED`（严格完成且官方通过） | 18 | **23** | 净增 +5 题 |
| `GATE_OVERBLOCKED_BUT_PATCH_PASS` | 2 | 1 | 补丁已过官方测试，被内部门禁误标 `BLOCKED`（`mui-23778` 漏识别 Mocha；`v0` 另有 `vue-8470`） |
| `HARNESS_NON_COMPLETED` | 4 | **2** | `v1` 在 `vue-8535` 诚实自报 `BLOCKED` 未盲改；`vue-11491` 达 30m 超时 |
| `HARNESS_EDIT_LOOP`（编辑空转） | 2 | **0** | 归零 |
| `HARNESS_NO_PATCH`（未产补丁） | 1 | **0** | 归零 |
| `HARNESS_UNVERIFIED_END` | 3 | 3 | 后续门禁可继续收紧 |
| `MODEL_LOGIC_OR_EDGE_CASE` | 10 | 11 | 模型逻辑或编译边界 |

## 3. 重复稳定性验证（`v1-9b862d35`，12 题 × `k=3` = 36 轮）

- **官方标准口径**：**`avg@3 = 55.6%`**（20/36）· **`pass^3`（3/3 全过一致性）= `50.0%`**（6/12）· **`pass@3`（≥1/3）= `58.3%`**（7/12）。
- **低方差 / 高确定性**：**11/12（91.7%）** 题三轮完全一致（6 题 `3/3`、5 题 `0/3`），仅 1 题波动（`vuejs__core-11491`：`TIMEOUT / PASS / PASS` = `2/3`）；其中 **Java 6 题零波动**（`avg@3 = pass^3 = pass@3 = 66.7%`）。
- **严格端到端口径**：`avg@3 = 47.2%` · `pass^3 = 41.7%` · `pass@3 = 50.0%`（差异全部源于 `mui__material-ui-23778` 连续 3/3 次补丁通过官方测试却被门禁误拦）。

详细逐题结果见 [per-task-matrix.json](per-task-matrix.json) 与英文版 [REPORT.md](REPORT.md)。

## 4. 透明边界说明

- 评测基于中转端点 `https://tokenmarket.cheap` 的 `gpt-6.1-sol`，双组严格同模型同端点交错对照，反映的是 Harness 工程增量而非跨模型横比结论。
- 40 题样本下全集 McNemar `p = 0.289`（官方）/ `0.180`（严格），Java 20 题 5 胜 0 负 `p = 0.0625`，按统计学规范归为强正向工程趋势，不宣称 `p < 0.05` 显著性。
- Apple Silicon OrbStack Rosetta 2 下，Phase 3 最后 3 槽对 git-ignored `node_modules/.bin/vitest`（Vitest ≥ 1.x）追加 `--pool=threads` 以绕过 Rosetta 2 `readlinkat` 断言崩溃；所有源码、`git diff` 与官方 Docker 验收容器均未作改动。
