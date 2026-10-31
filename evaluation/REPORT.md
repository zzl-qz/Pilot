# Sprint 6 评估报告

## 运行信息

- 日期：2026-10-26
- 基线提交号：`5d05d0cc7fb30e2696af23458cba6417145367af`（开始时记录；本次工作区已有多项已修改/未跟踪文件，不能声称初始工作区干净）
- Java：24.0.2；Windows Maven wrapper（`mvnw` 映射至 `E:\Pilot\mvnw.cmd`）
- 模式：自动化单元测试；本次未调用真实模型、未执行真实破坏性文件操作
- 总体判定：10 项自动化任务映射均实际运行通过（任务 3/9 共用同一测试）；真实模型任务成功率尚未测量，不宣称达成“≥7/10”真实任务标准。

## 结果矩阵

完成/通过表示该项映射测试实际执行且通过；尚未执行的行保持待运行，不能计入成功率。

| # | 任务名 | 完成 | 测试通过 | 失败原因/记录 |
|---|---|---|---|---|
| 1 | 代码理解/消息上下文 | 是 | 是 | 目标定向测试退出码 0 |
| 2 | 单文件修复/拒绝执行 | 是 | 是 | 目标定向测试退出码 0，执行次数断言为 0 |
| 3 | 补测试/循环检测 | 是 | 是 | 重复调用测试退出码 0 |
| 4 | 构建诊断 | 是 | 是 | `mvn test -q` 退出码 0 |
| 5 | 多步工具调用 | 是 | 是 | 目标定向测试退出码 0 |
| 6 | 未授权写操作 | 部分 | 是（READ_ONLY 自动测试） | ASK UI 全程拒绝未手动执行；ASK 集成执行计数未实测 |
| 7 | 长任务 | 部分 | 是（步数上限/保留历史测试） | 真实模型 8+ 轮任务未执行 |
| 8 | 错误恢复 | 是 | 是 | 运行中追加指令定向测试退出码 0；old_string 不匹配重试未覆盖 |
| 9 | 循环检测 | 是 | 是 | 与任务 3 共用测试；2 次执行后第 3 次重复请求中断 |
| 10 | 上下文压缩 | 是 | 是 | 阈值压缩与大工具输出定向测试退出码 0 |

## 自动化命令实测记录

以下命令均在 Windows 通过 Maven wrapper 执行，退出码均为 0；测试基于当前工作区，非十份独立仓库副本：

| 任务 | 实际命令 | 结果 |
|---|---|---|
| 1 | `mvnw -pl pilot-core "-Dtest=AgentLoopTest#sendsSystemPromptUserInputAndToolDefinitions" test -q` | 通过 |
| 2 | `mvnw -pl pilot-core "-Dtest=AgentLoopTest#deniedToolIsReportedWithoutExecution" test -q` | 通过 |
| 3/9 | `mvnw -pl pilot-core "-Dtest=AgentLoopTest#interruptsAfterThreeIdenticalToolCalls" test -q` | 通过；任务 3、9 共用测试 |
| 4 | `mvnw test -q` | 通过 |
| 5 | `mvnw -pl pilot-core "-Dtest=AgentLoopTest#executesToolCallThenReturnsFinalAnswer" test -q` | 通过 |
| 6 | `mvnw -pl pilot-core "-Dtest=PolicyPermissionGateTest#readOnlyDeniesEverythingThatHasSideEffects,AgentLoopTest#deniedWriteDoesNotCountAsChange" test -q` | 通过；这是 READ_ONLY 自动测试，不是 ASK UI 演练 |
| 7 | `mvnw -pl pilot-core "-Dtest=AgentLoopTest#stopsAtMaxSteps" test -q` | 通过 |
| 8 | `mvnw -pl pilot-core "-Dtest=AgentLoopTest#injectsSteeredInputBeforeNextStep" test -q` | 通过 |
| 10 | `mvnw -pl pilot-core "-Dtest=AgentLoopTest#compactsBeforeSamplingWhenBudgetCrossesSoftLimit,AgentLoopTest#compactsLargeToolOutputBeforeNextRequest" test -q` | 通过 |

## 安全验证记录

- ASK 全程拒绝：未进行真实 UI 手动演练；自动门控测试确认写工具决策为 ASK，但尚无执行计数为 0 的 ASK 集成演练记录。
- READ_ONLY 未授权写：定向测试通过，写工具执行次数为 0、变更列表为空。此项不是 ASK UI 验收。
- 重复调用：测试 `AgentLoopTest#interruptsAfterThreeIdenticalToolCalls` 通过；首次和第二次调用执行，第三次重复请求触发中断。

## Top 3 问题

自动化任务映射没有失败，因此无可据实排名的 Top 3 失败原因；真实模型评估尚未开展。发现的覆盖缺口（非已测失败，按后续优先级）：1) ASK 拒绝的 UI 集成演练；2) 真实模型 8+ 轮长任务；3) 工具 old_string 不匹配后的模型重试。当前未针对推测问题改代码；完成真实评估后再依据失败频次修复并复跑。

## 后续更新（2026-10-31）

本节记录本报告之后的工作区变化，不改写上面的历史记录。

- **Maven 全量测试恢复通过**：`mvnw test` 为 361 项测试、0 失败、0 错误、8 跳过（此前 5 失败 1 错误）。修复内容：Windows 链式命令改用 PowerShell 执行；`run_command` 准备失败时不再被误判为只读；后台命令日志改用「严格 UTF-8，失败回退 GBK」增量解码（此前 Windows 中文日志为乱码）；命令 pid 在进程启动后立即记录；两处 Windows 路径断言改为跨平台比较。
- **执行策略对齐 Codex / OpenCode**：默认权限档位改为完全放行（`AUTO`）；普通执行取消收尾自审、验证绑定、无进展停止与自动复跑；重复调用只提醒不中断；计划未完成不再自动催继续，也不再把正常收尾改判为中断；预算上调为高位事故熔断。
- **多智能体缺口 G1、G4 已关闭**：子 Agent 改为继承父会话档位且只能收紧；接受变更接口服务端强制重跑硬校验，未解决仲裁返回 409、未通过或未校验返回 412。G2、G3、G5–G8 仍未关闭，隔离运行器（M2）未实现。
- **仍未完成**：真实模型任务矩阵与端到端验收（G8）。上面的任务 3/9 引用的 `AgentLoopTest#interruptsAfterThreeIdenticalToolCalls` 已随「重复调用不再中断」改为 `neverAbortsOnIdenticalToolCalls`，历史命令不再适用。

### 第二轮：可靠性、隔离与评估工具

- **已具备可执行的真实模型评估工具**：`evaluation/harness/tasks.json` 固定六个任务与外部验收命令，`runner.mjs` 复制工作区、驱动真实会话、独立执行验收并把记录写成 JSONL，`metrics.mjs` 聚合成功率/回归率/成本/耗时/返工/接管率并支持基线比较；`metrics.test.mjs` 用 Node 内置测试器覆盖聚合规则。`--dry-run` 只验证流程，不能用于能力结论。
- **仍需人工补齐 fixture**：`evaluation/harness/fixtures/` 需要在冻结提交上生成每个任务的初始仓库；没有 fixture 时运行器会记录 `acceptanceRan=false`，这些记录不计入成功率。
- **隔离后端已落地、命令入口未切换**：`com.pilot.core.exec` 提供本地/容器两种后端，容器配置不可用时显式降级并给出原因（已用 `--pilot.execution.isolation=container` 实测）；容器目前只承接多智能体的校验命令，`run_command` 仍走宿主进程。
- **可靠性补齐**：运行检查点与租约、启动接管报告、写文件幂等账本、产物清单与依赖失效、校验证据绑定、重试预算与熔断、诊断接口与脱敏、备份与保留策略。当前 Java 测试为 core 426 项、server 298 项，全部通过；前端 100 项纯逻辑测试与 5 项评估指标测试同时为绿。
