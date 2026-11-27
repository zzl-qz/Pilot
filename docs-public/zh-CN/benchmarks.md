# 基准评测与工程验证

[English](../benchmarks.md) | [简体中文](benchmarks.md)

> 最后更新：2026-11-27  
> 可复现评测套件与原始脱敏产物：[evaluation/multi-swe-bench/](../../evaluation/multi-swe-bench/README.zh-CN.md) · [完整报告（2026-11-27）](../../evaluation/multi-swe-bench/results/2026-11-27-0caa2257-vs-9b862d35/REPORT.zh-CN.md)

Pilot 使用**可复现、销毁型容器隔离、官方隐藏单测验收**的基准流水线检验编码能力与运行时可靠性，而不依赖精选演示。

## 1. 评测方法论

- **官方题库**：[ByteDance-Seed/Multi-SWE-bench-flash](https://huggingface.co/datasets/ByteDance-Seed/Multi-SWE-bench-flash) 分层抽样 40 题（**Java 20 + TypeScript 20**，覆盖 Jackson、Gson、Jib、Vue 3、DarkReader、Material-UI 共 7 个真实开源仓库）。
- **评测前 Golden 自检（0-token）**：全部 **40/40（100%）** 题在本机 Docker 通过官方 Golden Patch 测试后才锁定题集（见 [flash-java-ts-40-verified.meta.json](../../evaluation/multi-swe-bench/datasets/flash-java-ts-40-verified.meta.json)）。
- **零泄题与反作弊**：每题在独立销毁型 `mswebench/*` 容器内删除 `/home/*.patch` 与 `/home/*run.sh`，销毁未来 git 历史并重建单提交 `baseline` 仓库。全部 104 轮真模型运行反作弊审计 **0 违规**。
- **同模型严格交错 A/B（`--interleave`）**：固定同一模型（`gpt-6.1-sol`）与端点，按题交替对比 `v0-0caa2257`（优化前基线）与 `v1-9b862d35`（当前 `master`），单题上限 30 分钟、零人工介入，补丁全量交由官方 `multi_swe_bench` 串行验收（`--workers 1`，**0 评分器异常**）。

## 2. 主实验核心结果（40 题 × 双组 = 80 轮）

| 核心指标 | `v0-0caa2257`（优化前） | `v1-9b862d35`（优化后） | 变化 |
| --- | ---: | ---: | ---: |
| **官方标准 `pass@1` (95% Wilson CI)** | **50.0%** (20/40, `[35.2%, 64.8%]`) | **60.0%** (24/40, `[44.6%, 73.7%]`) | **+10.0 pp** |
| **严格端到端 `COMPLETED & Pass` (95% CI)** | **45.0%** (18/40, `[30.7%, 60.2%]`) | **57.5%** (23/40, `[42.2%, 71.5%]`) | **+12.5 pp** |
| — **Java 子集（20 题）** | 50.0% (10/20) | **75.0%** (15/20) | **+25.0 pp（5 胜 0 负）** |
| — **TypeScript 子集（20 题）**（官方 / 严格） | 50.0% / 40.0% | 45.0% / 40.0% | -5.0 pp / 0.0 pp |
| **首次编辑前定位轮次** | 9.87 | **5.85** | **-40.7%** |
| **平均模型请求轮次 / 题** | 29.0 | **23.1** | **-20.3%** |
| **总输出 Token** | 222,260 | **182,995** | **-17.7%** |
| **Prompt Cache 命中率** | 59.2% | **61.8%** | **+2.5 pp** |
| **编辑空转 / 空补丁类工程故障** | 2 / 1 | **0 / 0** | **归零** |

- **配对精确 McNemar 检验**：全集 40 题官方 2×2 为 `18 / 2 / 6 / 14`（`p = 0.289`），严格口径 `16 / 2 / 7 / 15`（`p = 0.180`）；**Java 20 题为 `10 / 0 / 5 / 5`（零回退，`p = 0.0625`）**。

## 3. 重复稳定性验证（`k=3`，12 题分层子集 = 36 轮独立运行）

基于 `v1-9b862d35` 在 6 Java + 6 TypeScript 分层子集上执行 3 轮独立容器运行（[flash-stability-12.ids.json](../../evaluation/multi-swe-bench/datasets/flash-stability-12.ids.json)）：

- **官方标准口径**：**`avg@3 = 55.6%`**（20/36）· **`pass^3`（3/3 全过一致性）= `50.0%`**（6/12）· **`pass@3`（≥1/3）= `58.3%`**（7/12）。
- **低方差表现**：**11/12（91.7%）** 题三轮结果完全一致（其中 **Java 6 题零波动**，`avg@3 = pass^3 = 66.7%`），仅 `vuejs__core-11491` 出现一次超时波动（`TIMEOUT / PASS / PASS`）。

## 4. 崩溃与断线恢复确定性验收（0-Token）

`evaluation/harness/recovery-smoke.mjs` 在 `pilot-9b862d35.jar` 上 **8/8 断言全部通过**（[recovery-smoke.json](../../evaluation/multi-swe-bench/results/2026-11-27-0caa2257-vs-9b862d35/recovery-smoke.json)）：双并发活跃会话在硬杀进程后独立接管恢复、已持久化工具结果直接复用且副作用零重放、SSE 断线游标回放无漏无重。

## 5. 透明边界与 Trace 驱动的后续优化

- 本轮评测基于中转端点 `https://tokenmarket.cheap` 的 `gpt-6.1-sol`，聚焦固定同模型下的 Harness 工程增量，不作跨模型榜单横比；40 题样本下按统计学规范不宣称 `p < 0.05` 显著性。
- Trace 审计已直接定位出下一批高价值工程修复项：① `CompletionGate` 补齐 Mocha / 自定义 npm script 测试命令识别（`mui-23778` 3/3 有效补丁被误拦）；② `edit_file` 自动 lint 优先走仓库本地 `node_modules/.bin` / `pnpm exec`。

完整逐题矩阵与复现指南见 [evaluation/multi-swe-bench/README.zh-CN.md](../../evaluation/multi-swe-bench/README.zh-CN.md) 与 [per-task-matrix.json](../../evaluation/multi-swe-bench/results/2026-11-27-0caa2257-vs-9b862d35/per-task-matrix.json)。
