# 真实任务评估执行器

固定任务集、重复运行、独立验收。发任务前保存原始 `test/`；结束后将候选代码复制到独立目录并恢复这些测试，再执行验收。删改断言不能制造成功，模型自述也不作为验收证据。参考解位于 `references/`，不会复制进模型任务的 fixture。

## 先验证任务可解

```bash
node evaluation/harness/reference-verifier.mjs
node --test evaluation/harness/reference-verifier.test.mjs
```

逐项要求初始 fixture 的测试断言实际失败、参考解在相同原始测试下通过；命令启动失败、超时、没有测试断言不能算有分辨力。24 项参考解按 `references/<task.id>/src/` 存放，文件迁移用可选 `reference.json` 的 `remove` 列表。验证器拒绝测试覆盖、路径逃逸和符号链接，在临时目录执行，使用 `process.execPath` 和 argv，无需平台专用 shell。

`node evaluation/harness/runner.mjs --verify-fixtures` 保留为仅验证初始失败的轻量入口；完整可解性检查使用上面的 reference-verifier。

## 场景任务集（scenarios/）

等待唤醒、上下文增长与预算收尾三个场景位于 `evaluation/harness/scenarios/`，任务定义与主任务集分开（`--tasks evaluation/harness/scenarios/tasks.json`），带 `scenario` 标记：

- `wait-condition`：后台检查约 8 秒后写出 `src/state.json`；测试用 SHA256 守卫 `scripts/` 与 `data/`，并要求 `state.json` 的 `waitedMs >= 7000` 证明真实等待过（验收环境 `PILOT_EVAL=1` 快进重跑同一检查核对阈值）。跳过等待、手写 state.json 或改脚本都过不了。
- `long-chain`：16 个模块批量重构，开局约束（不引依赖、不建目录、保留 legacyFormatter、不改断言）由验收测试一直核到结束；执行器按 `scenarioMinModelRequests` 把模型请求数不足标记为场景未覆盖。
- `budget-wind`：40 项逐个清洗、禁止批处理；验收核对 `data/` 输入 SHA256、progress 台账与输出一致（done 的必须有输出且合规，remaining 的不得有输出）。任务全部做完反而标记场景未覆盖（未经历预算耗尽），应收紧预算复测。

可解性验证同样适用：`node evaluation/harness/reference-verifier.mjs --tasks evaluation/harness/scenarios/tasks.json`。

## 网络错误注入（support/fault-proxy.mjs 与 --fault-*）

故障注入代理夹在 Pilot 服务与真实模型上游之间，**逐分片流式转发**（不像预算代理缓冲整包），因此能在流中途制造真实故障：

- 模式：`mid-eof`（转发首分片后干净结束、不发 `[DONE]`，对准“流提前 EOF 被当成完成”）、`stall`（响应头+首分片后静默 `stallMs` 再结束，对准“收到响应头后 body 卡在半开连接”）、`http-503`（可重试 5xx，不打上游）、`reset`（直接销毁连接）、`none`（干净对照）。
- 触发：每第 `every` 个请求注入一次，其余干净转发；客户端应把注入当可重试错误处理后继续完成任务。
- 控制接口：`POST /fault/config`、`POST /fault/reset`（`stopped` 硬停粘滞不被清洗）、`GET /fault/state`（请求/注入/usage/花费估算）。
- 硬停：`maxRequests`（请求数上限）与 `maxQuota`（按 usage 分片累计的全价估算）任一达到即 402 停机。
- 启动：`PILOT_FAULT_UPSTREAM`（origin，缺省取 BASE_URL）、`PILOT_FAULT_API_KEY`（缺省取 API_KEY）只用于上游转发，绝不写日志或状态；`PILOT_FAULT_PROXY_PORT`、`PILOT_FAULT_LOCAL_TOKEN` 可选。
- 局域网自部署模型只有 HTTP 时，可显式设置 `PILOT_FAULT_ALLOW_PRIVATE_HTTP=true`；仅对配置的 RFC 1918 IPv4 地址生效，不放行公网、链路本地地址或域名。默认仍要求 HTTPS 或回环地址，代理始终只监听本机。

执行器侧：

```bash
# 代理就绪后（把 Pilot 的 BASE_URL 指到 http://127.0.0.1:<port><原路径>）：
node evaluation/harness/runner.mjs --base http://127.0.0.1:8787 \
  --tasks evaluation/harness/tasks.json \
  --fault-proxy http://127.0.0.1:18086 --fault-profile mid-eof,stall,http-503,reset \
  --fault-every 3 --seed 20261111 --repeat 3 --task <8 个任务逗号分隔> \
  --out evaluation/harness/runs/network-errors.jsonl
```

- 每次尝试前执行器 `reset` 代理并下发本次轮换到的故障模式；轮换索引 `seed + 任务位置 × repeat + (attempt-1)` 可复算：同一任务多次尝试轮遍不同 profile，全计划索引恰为 0..N×repeat-1。
- 记录新增字段：`faultScenario/faultRotationIndex/faultProfile/faultEvery/faultRequestCount/faultInjectedCount/faultInjectionModes/faultProxyStopped/faultUsage/faultSpendEstimateUnits/faultRunDurationSeconds`。
- **门控**：本次尝试没有任何实际注入 → `scenarioCovered=false`，不算成功；故障组与干净基线互不可比（门禁报 `insufficient`）；两组或组内混合不同 fault-profile/every 亦不可比；故障记录缺请求数观测直接拒绝。
- `--resume` 会重算轮换方案，与已记录的 `faultScenario/faultProfile/faultEvery` 不一致即拒绝续跑。
- 代理计数是全局的：跑故障组期间，该 Pilot 服务不得有其他会话（其他会话的模型请求会混入计数，`injectedRequests` 就不再只属于本次尝试）。

指标口径新增：`faultInjectionCoverage`（有注入观测的尝试中实际注入 ≥1 的比例）、`faultSurvivalRate`（确认实际注入的尝试中“正常收尾+外部验收通过+场景覆盖”的比例）、`faultScenarioSuccessRate`（全部故障尝试的通过率，未注入或未知覆盖不能计成功）、`injectedFaultCount`、`faultProxyStops`；注入观测缺失保持 `null`，不当零。

## 运行与对照

先在独立数据目录启动接入真实模型的 Pilot。A/B 使用同一模型、供应商端点、权限、工具开关、预算和任务集；只改变本轮计划评估的因素。`--model` **核对**服务模型，不替换全局模型设置。未传时读取服务配置。当前服务尚未暴露上游解析后的模型别名，因此 `requestedModel` 有值、`resolvedModel` 为 `null`。

```bash
# 完整基线：24 个任务，每项 3 次，共 72 次运行
node evaluation/harness/runner.mjs --base http://127.0.0.1:8787 \
  --model <服务中已配置的模型> --repeat 3 \
  --prompt-version P0 --tool-version T0 --environment <固定环境与预算标签> \
  --prompt-dir <服务实际加载的prompts目录> \
  --runtime-source <服务对应的pilot-core/src/main/java目录> \
  --runtime-source <服务对应的pilot-server/src/main/java目录> \
  --out evaluation/harness/runs/baseline.jsonl

# 候选：切换到候选服务及其对应源码，再使用相同任务集与重复次数
node evaluation/harness/runner.mjs --base http://127.0.0.1:8788 \
  --model <同一模型> --repeat 3 --prompt-version P1 --tool-version T1 \
  --environment <同一环境与预算标签> \
  --baseline evaluation/harness/runs/baseline.jsonl --strict \
  --out evaluation/harness/runs/candidate.jsonl

# 有界试跑或将任务拆给多个进程（每个进程必须有独立输出路径）
node evaluation/harness/runner.mjs --repeat 3 \
  --task fix-failing-unit-test,async-race-condition \
  --out evaluation/harness/runs/smoke.jsonl

# 只检查输出流程；没有模型调用、成本或能力结论
node evaluation/harness/runner.mjs --dry-run --repeat 3 --task fix-failing-unit-test \
  --out evaluation/harness/runs/dry-run.jsonl
```

`--task` 支持逗号分隔或重复传入。`--repeat` 为 1–100，默认 1；不同任务按重复轮次交错，避免先完整跑完一个任务的所有重复再处理下一个。默认任务文件为 `evaluation/harness/tasks.json`，默认 fixture 根目录为任务文件所在目录；缺 fixture、未知任务、任务 ID 重复在付费运行前报错。

`--prompt-dir` 默认项目的 prompts 目录，`--runtime-source` 默认 core/server 的 Java 源码目录。记录相对文件名和内容 SHA256，而不依赖机器的绝对路径。操作者须保证这些目录与服务实际加载的构建和提示词一致；本地源码 hash 不能证明远程服务加载了它。环境记录包含 OS、架构、Node 版本及 `--environment` 标签；Java 版本、模型推理参数、并发度和服务预算等未由接口暴露的条件应写入标签并保持一致。运行时 hash 不同会明确标为 `combined`，不能将组合变化归因于提示词。

每次任务完成立即追加 JSONL；后续中断不会丢失先前记录。已有输出文件拒绝覆盖，summary 使用独立路径。错误记录保留失败事实，未知成本不填 0。超时尝试取消本执行器创建的运行，不会继续验收正在修改的工作区。现有固定 Node 验收使用 argv；自定义字符串验收仍交系统 shell 执行，需要按目标平台编写。

故障代理控制接口配置了 `PILOT_FAULT_LOCAL_TOKEN` 时，执行器也从同名环境变量读取独立凭据，不复用 Pilot 的访问令牌。未传 `--fault-max-requests` 时保留代理启动上限；reset 只清当前试验窗口，不清代理生命周期累计请求、已观测用量及硬停状态。停顿时长、上限和 seed 纳入故障方案指纹，改变条件必须另开记录，严格对照也核验该指纹。费用阈值仍只是基于已观测 usage 的估算停止条件，缺失 usage 不能当零，也不是预付费硬保证。

## 安全续跑与预算规划

中断后可以在**同一任务合同、模型配置、提示词/运行时代码、环境和版本标签**下给原命令加 `--resume`，并保留原 `--out`。它只补缺少的 attempt；失败、超时和基础设施错误不会被重跑成功替换。更改版本要新建输出，不能往旧 screening 混入当前源码的结果。

执行器用 `.lock` 阻止同一输出并发运行，每次付费任务前写 `.inflight.json`。异常退出留下的锁或在途标记必须先核对原进程、服务终态和费用，再人工补齐真实记录；不会自动再次请求。截断尾行、重复 attempt/会话、混合版本都拒绝续跑。汇总文件由完整 JSONL 重建，历史记录不覆盖。

一次基础设施异常、等待超时或取消状态未确认就停止整批派发并返回非零退出码，摘要标记 `stoppedEarly`。异常记录保留已创建的 `sessionId`、已观测的运行 ID 和是否请求取消，未确认的终态仍为 `null`。轮询发现超过正常请求等待范围的长时间停调度时，会标记 `evaluationInterrupted`；这只能提示宿主休眠或严重阻塞，不能直接归因于模型。复核环境与在途会话后可用 `--resume` 补齐尚未执行的空位，原失败尝试保留。长跑时应让宿主持续运行；本工具不会更改系统电源设置。

`node evaluation/harness/budget-plan.mjs <plan-input.json>` 只做离线规划，不调用模型。输入包含原预算保护 `state`（initialUsed/lastUsed/maxQuota/pendingReserved/locked/busy）、最新已观测累计额度 `latestUsed`、真实 `records`、配置全价 `inputQuotaPerMillion`/`outputQuotaPerMillion` 和计划补跑数量 `additionalRuns`；可选 `quotaPerCny` 与 `headroom`（默认 1.5）。未知用量不算零，失败样本也计入；用观测用量的全价 p95 加余量估计下一批规模。它不降低运行时单请求预留，不能用缓存折扣推断费用硬上界。

新账单增量最多结算等额预留。计数不动不代表欠费已结算；不得重置累计额度基线或丢弃未知费用预留。若存在锁定、未结算或预算不足，结果为 `needs_reconciliation`。本轮[历史预算复核](baselines/2026-11-03-reliability/budget-review.json)仍需核对，不追加付费请求；原 18/36 screening 保持证据不足。

## 场景覆盖

任务可声明 `allowedChangeRoots`（工作区相对路径数组，例如 `["src", "test"]`），将整个执行过程的已记录变更范围纳入验收。执行器会把该范围明确追加到初始任务说明，避免隐藏验收要求；再检查结构化 `run.completed.changedFiles` 的累积记录，先在根目录创建临时文件再删除也会判为违反范围；缺失轨迹不能当作通过。该规则进入任务指纹，不能与未启用规则的旧成绩混用。它检查服务记录的变更，不声称能观测任意子进程内部创建后立即删除、从未留下变更记录的文件。对中途才提出限制的历史样本，应另行核对违规动作是否发生在引导之后。

只有运行状态 `COMPLETED`、外部验收通过、必需场景实际触发时才记成功。中途引导通过当前 `runId` 的 `/steer` 投递，不排队创建新任务。记录引导是否投递、接收的运行 ID、投递时步数和实际延迟；若模型在投递前已经完成，即使最终代码通过测试，也不能声称测过中途引导。两条内置引导场景在 1 秒后尝试，使用更短轮询；未投递的记录为场景未覆盖，需要重新设计或复测该场景。

### 并发、进程重启与事件补发

构建当前后端 JAR，并让 `JAVA_HOME` 指向 Java 25，再运行：

```bash
node evaluation/harness/recovery-smoke.mjs pilot-server/target/pilot-server-0.0.1-SNAPSHOT.jar
```

脚本使用本地确定性 HTTP 模型、临时工作区和独立端口，不读取用户模型密钥，不连接用户正在运行的 Pilot。它让两个会话同时完成第一步文件操作后等待，强制结束自己启动的 JVM，使用原数据重启，再分别恢复两个会话；检查已完成工具不重做、历史工具结果仍进入模型请求、完成诊断正确，以及 SSE 断开后按游标补发无遗漏和重复。它关闭自动完成检查以隔离恢复机制，不能作为真实模型能力或完成门验证。日志与 JSON 报告保留在打印出的临时目录。

使用标准 Node 子进程和路径 API；Linux 容器应带 `--init`，让退出父进程的子进程得到正常回收。macOS 与 Linux 已实际执行此测试；Windows 尚需实机验证。真实模型评测仍使用上面的固定任务、重复与外部验收流程。

## 指标口径

场景覆盖必须有对应观测：长链路使用服务诊断中的 `modelRequests`；预算场景同时要求结构化预算停止事件及诚实的剩余台账，不能仅凭“还有文件没做”推断预算耗尽。预算保护生效且保留了部分进度仍不等于整个任务完成，通用 `success` 继续要求 `COMPLETED` 与外部验收通过。场景名称及最低请求数进入任务指纹，变更门槛后不能与旧尝试混合续跑。

| 指标 | 口径 |
|---|---|
| 成功率 | 运行完成且独立验收通过、必需场景已覆盖 / 全部尝试 |
| 正常收尾但外部验收失败率 | 运行终态为 COMPLETED 但外部验收失败 / 正常收尾且执行外部验收的样本；同时报告收尾验收覆盖率 |
| 每成功任务成本 | 全部尝试成本（包含失败尝试）/ 成功尝试数；任何价格缺失或无成功任务时为 `null` |
| p50/p95 耗时 | 观测到的总执行与验收耗时分位数；另保留模型执行阶段耗时 |
| 工具调用与模型重试 | 分页读取结构化历史事件计数；历史不可用时为 `null` |
| 失败命令数 | 有明确非零退出码或 FAILED 状态的命令，不等于返工次数 |
| 人工接管、返工 | 当前没有可靠自动观测，保持 `null`；非完成、脚本引导或工具失败不能伪装成人工接管/返工 |
| 缓存命中率 | 只使用有真实缓存观测的输入 token；缺失不当零命中 |

`falseCompletionRate` / `falseCompletionCount` 保留为兼容字段名，`reportedComplete` 仅表示运行正常收尾，不表示模型文字声称成功。模型可能明确说明“未完成”后正常结束；本指标不判断诚实性。汇总同时提供明确的中文标签；该比率上升需要复核任务结果和终态语义，不能直接称为模型说谎。

金额全部可观测时才给总金额和成功成本；部分已知金额单独放 `knownCostUsd`，不能当总成本。没有价格时，门禁可使用完整输入 token 作为代理指标，明确它不等于账单成本。

## 对照门禁

严格门禁要求相同的任务合同、fixture、环境和模型配置指纹，每个任务两组至少 3 次独立运行、至少 20 个不同任务。重复 `attempt`、复用同一 `sessionId` 冒充独立运行、结果字段互相矛盾、混合版本、缺元数据的旧记录、样本不足、重复次数不一致、必需场景未触发均不能得到 `pass`。旧记录仍能汇总查看。

- 缺掉基线任务、混入 dry-run、降低外部验收执行率：拒绝。
- 每个任务比较两组成功率。单次失败或历史偶然成功仅形成待复测信号；成功率下降但证据不足时返回 `insufficient`，不会放行。
- 使用单侧 Fisher 精确检验，并按可比任务数做 Bonferroni 校正；重复样本显示显著退化时拒绝，其他任务的进步不能抵消该任务退化。
- 两组全部未成功不能建立可用能力基线。所有样本必须有外部验收结果；任务失败或基础设施异常不可通过删记录隐藏。
- 比较每项任务的平均成本，而非总运行次数；默认金额或 token 代理允许 5% 波动。部分成本未知时不能只比较便宜的已知子集。
- `pass` 仅表示当前可比样本未观察到退化，并非证明模型在所有任务上能力不下降。三次重复是最小操作门槛，不是统计充分性的保证；边界结果要增加重复次数。

`--strict` 必须指定已满足完整版本与重复条件的基线，不能与 dry-run 同用；门禁非 pass 时进程非零退出。小规模试跑应不加 strict，结果明确为证据不足。

本轮自动化测试在 macOS 运行；路径、命令调用和临时目录使用 Node 跨平台 API，Windows/Linux 尚未实机验证。

> AI生成
