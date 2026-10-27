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
