# 历史提交回放任务集

用本仓库自己的**真实合并提交**做评测任务，替代手写的迷你 fixture。

## 为什么这么做

`evaluation/harness/fixtures/` 里的任务是零依赖 Node 小工程加人为注入的 bug，跑得便宜，但离真实开发太远：代码量小、没有既有约束、没有跨文件依赖。回放真实提交解决三个问题：

- **任务真实**：题目就是当时真实发生的一次改动，带真实上下文、真实约束、真实依赖。
- **验收真实**：验收命令是仓库自己的测试；测试由当时的作者编写，不是事后为评测补的。
- **可解性天然成立**：提交已合并、CI 已通过，说明任务有解。

## 核心机制：提交自带的测试就是隐藏用例

```
工作区   = 父提交的完整检出（独立 git 仓库，只有一个 baseline 提交）
任务说明 = 提交标题 + 提交正文（需求与现象）+ 约束；不含 diff、文件清单和实现细节
隐藏用例 = 该提交改动过的测试文件，验收时才注入
参考解   = 该提交的 diff，永不进入工作区
通过条件 = 注入隐藏用例后该测试类通过
```

任务说明必须包含提交正文，不能只有标题。实测过一版只用标题的批次：`S-05` 的标题是「修复部分交付提示重复」，模型把“提示重复”理解成重复发送事件、删掉了一处 `AssistantDelta`，而真实需求是“模型答复已说明未完成步骤时不再追加部分交付提示”。两种理解都合理，题面却只有一个答案——那就不是能力评测，是猜谜。补上正文后需求才唯一。

模型看到的是**改动前**的测试；能证明它做对的断言在验收时才注入。因此：

- 删测试、改断言、加 `@Disabled` 都无效——验收前会先删掉测试文件再写回原始版本；
- 无法从 git 历史里翻到答案——工作区是新建的仓库，只有 1 个提交；
- 也不存在“对着可见测试硬编码”之外的空间，而这本身就能被既有测试和回归发现。

## 候选是怎么筛出来的

从 `2026-10-01` 以来的提交里，按下列规则取候选：

1. 改动 1~4 个 `src/main` 文件，新增 ≤200 行；
2. 至少改动 1 个 `src/test` 下的测试类，且新增 ≥5 行（要有新断言，不是纯适配重构）；
3. 只落在一个 Maven 模块内（构建简单、可复现）；
4. 排除依赖浏览器、操作系统沙箱、本机命令隔离的测试类（环境相关，换机器就不成立）。

候选池 129 个，按改动规模分三档：

| 档位 | 判据 | 数量 |
| --- | --- | --- |
| S | 单文件、新增 ≤30 行 | 49 |
| M | 新增 ≤80 行、≤3 文件 | 50 |
| L | 其余（≤200 行、≤4 文件） | 30 |

## A/B 校验（不花 token）

每个候选都要通过两项检查才进任务集：

- **A 项**：父提交 + 只放隐藏用例 → 必须**失败**。全绿说明这个提交没有区分力（改动无法被测试观察到），剔除。
- **B 项**：父提交 + 该提交的全部改动（即参考解）→ 必须**通过**。失败说明任务在当前环境下不可解，剔除。

A 的失败分两种：断言失败（题好，能验证行为）和编译失败（题合格但只能证明“符号存在”）。本任务集优先收断言失败的。

## 任务集

| 编号 | 档 | 提交 | 问题 | 模块 | 改动规模 | 验收用例 | 校验 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| S-01 | S | `fc9da309` | 为自动续跑发布新一轮开始事件 | pilot-server | 1 行 / 1 文件 | AgentRunServiceTest | A=assertion-fail / B=pass |
| S-02 | S | `972d571b` | 在落盘前拒绝缺失内容的混合附件 | pilot-server | 2 行 / 1 文件 | AttachmentServiceTest | A=assertion-fail / B=pass |
| S-03 | S | `0a374180` | 保留独立核验调用消耗的请求与用量 | pilot-server | 2 行 / 1 文件 | GoalExecutionPolicyTest | A=assertion-fail / B=pass |
| S-04 | S | `6daaa4bf` | fix(core): 带 /v1 前缀的端点一律按兼容层基址补全 chat/completions | pilot-core | 4 行 / 1 文件 | OpenAiCompatibleModelClientTest | A=assertion-fail / B=pass |
| S-05 | S | `03d97bc3` | 修复部分交付提示重复 | pilot-core | 7 行 / 1 文件 | AgentLoopTest | A=assertion-fail / B=pass |
| S-06 | S | `3bc979fa` | fix(server): 启动恢复不再重复上报上次已经收敛过的运行 | pilot-server | 8 行 / 1 文件 | RunStateTrackerTest | A=assertion-fail / B=pass |
| M-01 | M | `1d43404e` | 将 code_diagnostics 升为核心可见工具并补齐改后自检指引 | pilot-core | 2 行 / 2 文件 | ToolRegistryTest, CodeDiagnosticsToolTest | A=assertion-fail / B=pass |
| M-02 | M | `02edcb05` | 统一测试命令指引并明确管道结果不能作为校验证据 | pilot-core | 3 行 / 2 文件 | CompletionGateTest | A=assertion-fail / B=pass |
| M-03 | M | `86c028f8` | 让明确审批替代旧的 MCP 只读判断 | pilot-core | 10 行 / 2 文件 | McpPermissionsTest | A=assertion-fail / B=pass |
| M-04 | M | `ebfef42a` | 增强命令状态工具的失败尾部输出与浏览器嵌套沙箱识别 | pilot-core | 31 行 / 2 文件 | ManagedCommandToolsTest, RunCommandToolTest | A=assertion-fail / B=pass |
| M-05 | M | `23672554` | 超窗时折叠较早对话 | pilot-core | 31 行 / 1 文件 | AgentLoopTest | A=assertion-fail / B=pass |
| M-06 | M | `0cfdc0b1` | 修复 CommandChain 双引号内转义引号与反斜杠解析（D-015） | pilot-core | 31 行 / 2 文件 | CommandChainTest, RunCommandToolTest | A=assertion-fail / B=pass |
| L-01 | L | `e725d096` | 排除审计工作区生成目录 | pilot-core | 21 行 / 4 文件 | CodebaseAuditorTest | A=assertion-fail / B=pass |
| L-02 | L | `733a18e8` | 完全放行时直接执行普通 git 命令。 | pilot-core | 23 行 / 4 文件 | ManagedCommandToolsTest, RunCommandToolTest, SandboxCommandToolTest | A=assertion-fail / B=pass |
| L-03 | L | `cf3d0418` | 超窗时先交接摘要，再丢较早对话。 | pilot-core | 35 行 / 4 文件 | AgentLoopTest, ContextCompactorTest, ModelProfileTest | A=assertion-fail / B=pass |
| L-04 | L | `1b4a368c` | 修复 RequestBudget.enforce 就地修改不可变消息列表导致 UnsupportedOperationException | pilot-core | 21 行 / 4 文件 | RequestBudgetRepairTest | A=compile-fail / B=pass |
| L-05 | L | `3ad9bef7` | 没有任何写入时，只读查看超过 16 次就不再返回新内容。 | pilot-core | 25 行 / 4 文件 | RunCommandPolicyTest | A=compile-fail / B=pass |
| L-06 | L | `57071939` | 将非法工具调用参数 JSON 归类为有界可纠偏重试错误（D-020） | pilot-core | 26 行 / 4 文件 | AgentLoopTest, OpenAiCompatibleModelClientTest, OpenAiResponsesModelClientTest | A=compile-fail / B=pass |

## 已跑结果（r2 批次，2026-11-26）

模型 `gpt-6.1-sol`，权限档 `AUTO`，18 个任务各跑一次，完整数据见 [results-r2.md](results-r2.md)。

| 指标 | 值 |
| --- | --- |
| pass@1 | **55.6%**（10 / 18） |
| 分档通过 | S 5/6　M 3/6　L 2/6 |
| 正常收尾 | 18 / 18 |
| 误报完成率 | **44.4%**（8 / 18） |
| 耗时 p50 / p95 | 321s / 808s |
| 模型请求 | 356 次（平均 20 次/任务） |
| 输入 token | 22,462,727（缓存命中 71.1%） |
| 未命中输入 | 6,488,482（每成功任务 648,848） |

三点观察：

1. **难度梯度成立**：S 5/6、M 3/6、L 2/6。档位是按改动行数和文件数划分的，不是按模型表现事后贴标签。
2. **误报完成是主要失败形态**：18 次全部正常收尾，8 次验收不通过。若只看“任务完成”状态，通过率会虚高到 100%。
3. **改错方向多于改不对**：典型如 `S-05`，模型把“提示重复”理解成重复发送事件、删掉一处 `AssistantDelta`，而需求是“模型已说明未完成时不再追加提示”。这也是题面必须带提交正文的原因。

作弊审计：18 个工作区都没有修改 `src/test` 下的文件，也没有 `pilot-core` / `pilot-server` 之外的改动，全部停留在注入前的 1 个 baseline 提交。

## 工具

| 脚本 | 作用 |
| --- | --- |
| `replay.mjs` | `list` / `verify`（A、B 校验）/ `setup`（导工作区）/ `accept`（还原隐藏用例后验收） |
| `run-eval.mjs` | 批量跑 Pilot：建会话、发任务、轮询终态、逐个验收，写 `results.jsonl` 与 `summary.json` |
| `reconcile.mjs` | 对账：断连或命令问题导致的记录，按会话真实终态与重新验收修正 |
| `merge.mjs` | 把补跑批次并回主批次并重算汇总 |
| `report.mjs` | 生成 Markdown 报告 |
| `build-tasks.mjs` | 把筛选结果转成 `tasks.json` |

踩过的两个坑，写在这里免得重犯：

- Windows 上 `os.tmpdir()` 可能返回 8.3 短路径（`ZHUANG~1`），Maven 在短路径下解析 `-pl` 会报 `Could not find the selected project in the reactor`。验收前必须 `realpathSync`。
- 用 `-pl pilot-server -am` 时，`-Dtest=XxxTest` 会同时作用于 `pilot-core`，匹配不到用例会让整批失败。必须加 `-Dsurefire.failIfNoSpecifiedTests=false`。

## 怎么跑

先确认环境：JDK 25（`JAVA_HOME` 指向它）、Node 22、本地 Maven 仓库里已有本项目依赖。

```bash
# 1. 列出任务
node evaluation/replay/replay.mjs list

# 2. 复验 A/B（可选，不花模型 token，每个任务约 1 分钟）
node evaluation/replay/replay.mjs verify --task S-01
node evaluation/replay/replay.mjs verify --all

# 3. 准备一个任务的工作区（父提交的干净检出）
node evaluation/replay/replay.mjs setup --task S-01 --out ../ws-S-01

# 4. 让 Pilot 在 ../ws-S-01 上完成任务，任务说明见 ../ws-S-01.task.md

# 5. 验收：还原隐藏用例后跑测试
node evaluation/replay/replay.mjs accept --task S-01 --workspace ../ws-S-01

# 6. 整批跑：自动建会话、发任务、轮询终态、逐个验收
node evaluation/replay/run-eval.mjs --batch r2 --parallel 2 --timeout 40

# 7. 断连等基础设施问题用对账修正，补跑批次用合并并回
node evaluation/replay/reconcile.mjs --batch r2
node evaluation/replay/merge.mjs --into r2 --from r3

# 8. 出报告
node evaluation/replay/report.mjs --batch r2
```

`accept` 会把工作区复制到临时目录、删掉 `src/test` 下被改动过的测试、写回提交里的原始版本，再执行该模块的测试。工作区里模型留下的任何测试改动都不会影响判定。

## 成本

- A/B 校验：**0 token**，只消耗编译时间。单次校验约 15~40 秒（增量编译）。
- 正式跑：每个任务一次模型运行即可产出 `pass@1`。按本仓库实测，单任务输入 token 与任务规模、定位往返次数正相关，建议先跑 2 个任务量出单价，再决定批次规模。

## 指标口径

| 指标 | 定义 |
| --- | --- |
| `pass@1` | 运行终态 COMPLETED **且** `accept` 通过 |
| 误报完成率 | 正常收尾但 `accept` 失败 / 正常收尾数 |
| 每成功任务 token | 全批输入 token（区分总输入与未命中输入）/ 成功数 |
| 耗时 | `setup` → 运行终态，报告 p50 / p95 |

## 边界

- 任务来自**一个仓库**（本仓库）的提交，只代表这个仓库的工作类型，不能外推成通用编码能力。
- 单次运行、单一模型，不能得出统计显著性结论；需要对照时必须固定任务集、提示词版本、工具版本和环境。
- 部分提交在改动实现的同时也调整了测试的结构，这类任务对“改实现”的约束更松，记录里单独标注。
- 父提交必须能在当前环境构建通过；环境变化（JDK、依赖、平台）会让任务失效，重跑前先 `verify --all`。
