# X2（`R-03`）Pre-flight 预定位评估报告（离线 10 任务全量审计 + 预算止损实机归档）

> **样本量与预算止损声明（防过拟合纪律）**：本实验原计划对冻结任务 `T002`（`BundledRuntime`）执行 3 对 3（`ON` x3 vs `OFF` x3）实机 A/B 对照。执行期间用户明确反馈外部模型 API token 余额接近耗尽、要求立即停止非必需 LLM 消耗，因此本实验在产出有限实机样本后**立即止损**。按 `defect-lifecycle-and-ab` 规范：**在线 3v3 对照样本不足（证据不足）**，本文绝不夸大为 3v3 显著结论；全部已落盘实机样本与零成本 10 任务确定性离线审计数据如实归档如下。

---

## 1. 零成本确定性离线审计（全 10 张冻结任务卡 `T001`–`T010`）

在隔离工作区（已通过 `sparse-checkout` 排除 `evaluation/dogfood/`，与真实自举任务完全同构）直接调用生产类 `PreFlightLocator.locate()` 评测全部 10 张冻结任务卡原话（零 LLM 调用，100% 可复现）：

| 任务 | 级别 / 类型 | 耗时 (ms) | 超时 | 锚点数 | 候选数 | 实际编辑目标/直接上下文命中 | 噪声槽位 | 精确率@6 | 主要噪声来源 |
|---|---|---:|:---:|---:|---:|---:|---:|---:|---|
| `T001` | L1 明确（带路径） | 94 | 否 | 8 | 6 | 3 (`BrowserHome`/`BrowserRuntime`/`BrowserHomeTest`) | 3 | 50.0% | `git commit/push/remote` (`RunCommand*Test`) |
| `T002` | L1 明确（带路径） | 115 | 否 | 8 | 6 | 2 (`BundledRuntime`/`BundledRuntimeTest`) | 4 | 33.3% | `git commit/push/remote` (`RunCommand*Test`) |
| `T003` | L1 **无路径定位** | 81 | 否 | 8 | 6 | **0** | 6 | **0.0%** | `pilot-server-0.0.1-SNAPSHOT.jar` 冒烟脚本 |
| `T004` | L1 S4 模糊（带路径） | 106 | 否 | 7 | 6 | 1 (`IsolatedWorkspace`) | 5 | 16.7% | `quality-gate`/`long-method`/`git commit` |
| `T005` | L1 明确（带路径） | 106 | 否 | 8 | 6 | 1 (`ModelUsageParser`) | 5 | 16.7% | `spotless:check`/`checkstyle:check`/`git commit` |
| `T006` | L3 多文件（带符号） | 80 | 否 | 8 | 6 | 4 (`CacheWireTest`/`Message`/`AgentLoop`/`AgentRunService`) | 2 | 66.7% | `configured()` 泛方法名 |
| `T007` | L2 **无路径定位** | 79 | 否 | 8 | 6 | **0** | 6 | **0.0%** | `high-cyclomatic-complexity`/`spotless:check`/`git commit` |
| `T008` | L2 跨端多文件 | 79 | 否 | 8 | 6 | 1 (`MeteredModelClient`) | 5 | 16.7% | `spotless:check`/`git commit` 挤占 `MAX_ANCHORS=8` 导致其余目标类名丢失 |
| `T009` | L2 S4 模糊（带路径） | 94 | 否 | 5 | 6 | 1 (`ProjectLint`) | 5 | 16.7% | `git commit`/`git push` |
| `T010` | L2 离线回放套件 | 80 | 否 | 8 | 6 | 3 (`AgentLoop`/`ContextIntegrityTest`/`Message`) | 3 | 50.0% | `spotless:check`/`git commit` |
| **合计** | **10 任务** | **79–115ms** | **0/10** | — | **60** | **16 / 60** | **44 / 60** | **26.7%** | 噪声占比 **73.3%**；无路径任务 **0/12 (0%)** |

### 离线审计关键发现
1. **延迟与超时（`D-019` 验收稳固）**：全部 10 任务耗时 79–115ms，**0/10 超时**，不会阻塞首轮启动。
2. **结构性噪声根因（非过拟合、跨任务共性）**：`PreFlightLocator.BACKTICK_OR_QUOTED` 将反引号包裹的通用校验命令与纪律词（`git commit`、`git push`、`git remote`、`spotless:check`、`checkstyle:check`、`quality-gate`）按 `TIER_LITERAL` 提取并计入 `MAX_ANCHORS=8`，既把 6 个候选槽位中的 3–6 个塞满 `RunCommandPolicyTest.java` / `scripts/verify-reliability.mjs` 等噪声文件，还在 `T008` 这类多符号任务中把真实类名挤出 `MAX_ANCHORS=8`。
3. **无路径中文描述盲区**：`T003`、`T007` 这类不给路径与类名的中文故障/重构任务中，当前词法锚点命中率为 **0/12**。

---

## 2. 已有实机遥测与止损前 `X2` 样本归档（仅 DeepSeek `deepseek-v4.1-flash-1017`）

### 2.1 自举实机遥测（`C005`–`C008`，均开启 `preflight=true` + `D-022`）

| 周期 / 任务 | 耗时 (ms) | 候选数 | `hitRead` | `hitEdit` | 首编前请求 | 稳态缓存中位 | 整体缓存命中 | 备注 |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| `C005` / `T004` | 110 | 6 | 3 | 1 | 11 | 95.5% | 64.0%（含休眠重连） | 0 次连续 `phaseHint` 移位 |
| `C006` / `T005` | 116 | 6 | 2 | 1 | 13 | 97.4% | 92.8% | 目标已在原话显式给出，另 5 槽为噪声 |
| `C007` / `T007`（无路径） | 83 | 6 | **0** | **0** | **10** | **98.1%** | **96.8%** | **6/6 全噪声下 `D-022` 首轮即清退，无缓存回退** |
| `C008` / `T009` | 100 | 6 | 1 | 1 | 10 | 98.5% | 95.2% | 目标已在原话显式给出，另 5 槽为噪声 |

### 2.2 `X2`（`T002` `BundledRuntime`，基线 `6ea2c270`）止损前实机样本（全部保留，零丢弃）

| 样本标签 | 组别 | Runner SHA | 状态 | 独立验收 | 墙钟耗时 | `req-1` 注入 | `hitRead / hitEdit` | 首编前请求 | 主请求数 | 未命中输入 token | 稳态缓存中位 | 备注 |
|---|---|---|---|---:|---:|---|---|---:|---:|---:|---:|---|
| `X2-ON-1`（热更新后） | `ON` | `316a12e6` | `COMPLETED` | **PASS** | 269.2s | 是 (6) | `2 / 2` | **7** | 40 | 81,529 | 96.5% | 完整通过 `BundledRuntimeTest` + `PilotDataDirTest` + spotless + checkstyle + quality-gate |
| `X2-ON-1`（热更新前） | `ON` | `9fbaca42` | `COMPLETED` | **PASS** | 738.9s | 是 (6) | `1 / 1` | **8** | 73 | 112,013 | 98.0% | 完整通过（模型自行额外跑了全模块 `pilot-server` 测试） |
| `X2-OFF-1`（热更新前） | `OFF` | `9fbaca42` | `CANCELLED` | 未完结 | 取消于 req 69 | **否 (0)** | `n/a` | **9** | 69（未完） | 291,467（未完） | 98.1% | 为优先响应用户热更新需求于 req 69 主动取消，不作为完结样本参与显著性判定 |
| `X2-ON-2`（热更新后） | `ON` | `316a12e6` | `CANCELLED` | 未完结 | 立即止损 | 是 (6) | — | — | — | — | — | 收到用户 token 预算止损指令后立即取消 |

---

## 3. 结论与后续建议（严谨口径）

1. **当前开关决策：维持默认 `pilot.agent.preflight-localization-enabled=true` 不变**。
   - 在线 3v3 实机样本因预算止损未凑齐（**证据不足**），按反过拟合纪律不凭少量样本关闭或调参；
   - 已有证据表明：① 命中目标时（如 `T002` `X2-ON-1` `hitEdit=2`，首编前仅 7–8 轮、耗时 269s）具备正向定位价值；② 即使在 `T007` 无路径任务 0/6 命中（100% 噪声）的最坏场景下，得益于已通过 V4 关闭的 **`D-022`**（首轮有效读取后立刻清退 `<candidate-targets>`），稳态缓存命中仍达 **98.1%**（整体 96.8%），无明显负作用或缓存回退。
2. **后续零模型成本优化建议（非阻断项，留待后续迭代）**：
   - 在 `PreFlightLocator` 中过滤常见 CLI/Git/构建校验命令字面量（如 `git commit/push/remote`、`spotless:check`、`checkstyle:check`），并在锚点排序时让 `PATH` 与 `IDENTIFIER` 严格优先于 `LITERAL`，防止噪声挤占 `MAX_ANCHORS=8`；该改进可完全通过 `PreFlightLocatorTest` 与上述 10 任务确定性离线回归（目标：精确率@6 从 26.7% 显著提升、零外网 API 成本）验证后再按预算评估是否补跑 3v3。
