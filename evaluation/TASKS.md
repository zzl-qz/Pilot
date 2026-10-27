# Sprint 6 评估任务集

## 约定

在仓库根目录执行：`./mvnw test`（Windows：`mvnw.cmd test`）。每项任务均以独立测试/实现行为为目标；本集使用当前仓库代码而非 10 份独立仓库副本，故不能据此声称完成了 10 次独立代理改动评估。开始正式模型评估前记录 `git rev-parse HEAD`，逐项复位环境，避免前项改动污染后项。分类：AUTO = 当前自动测试可验证；MANUAL = 需手动/真实模型演练。

| # | 任务 | 任务规格/预期 | 验证命令及判据 | 类型 |
|---|---|---|---|---|
| 1 | 代码理解 | 解释 AgentLoop 如何把 system/user 消息送模型并返回最终答复；不得编造 | `./mvnw -pl pilot-core -Dtest=AgentLoopTest#sendsSystemPromptUserInputAndToolDefinitions test`；请求含 system/user，答复完成 | AUTO |
| 2 | 单文件修复 | 工具失败/权限拒绝时不执行写入，错误传回模型 | `./mvnw -pl pilot-core -Dtest=AgentLoopTest#deniedToolIsReportedWithoutExecution test`；执行数 0 | AUTO |
| 3 | 补测试 | 给循环检测补覆盖，重复调用应中断 | `./mvnw -pl pilot-core -Dtest=AgentLoopTest#interruptsAfterThreeIdenticalToolCalls test`；失败码 REPEATED_TOOL_CALL | AUTO |
| 4 | 构建诊断 | 运行测试并检查失败原因；基线应全绿 | `./mvnw test`；退出码 0 | AUTO |
| 5 | 多步工具调用 | 调用工具、回填结果、再生成答复 | `./mvnw -pl pilot-core -Dtest=AgentLoopTest#executesToolCallThenReturnsFinalAnswer test`；工具只执行一次且事件顺序正确 | AUTO |
| 6 | 拒绝越权 | READ_ONLY 下写入被拒绝且没有副作用 | `./mvnw -pl pilot-core -Dtest=PolicyPermissionGateTest#readOnlyDeniesEverythingThatHasSideEffects,AgentLoopTest#deniedWriteDoesNotCountAsChange test`；写入执行数/变更数均为 0 | AUTO |
| 7 | 长任务 | 多步持续处理，不超过配置步数；超限可续跑 | `./mvnw -pl pilot-core -Dtest=AgentLoopTest#stopsAtMaxSteps test`；到上限后保留历史 | AUTO（长任务的真实模型表现仍需人工） |
| 8 | 错误恢复 | 用户指令追加后下一轮继续，或工具错误结果回传后恢复 | `./mvnw -pl pilot-core -Dtest=AgentLoopTest#injectsSteeredInputBeforeNextStep test`；追加指令进入下一次请求 | AUTO |
| 9 | 循环检测 | 模型连续请求完全相同工具/参数达到阈值时中断 | `./mvnw -pl pilot-core -Dtest=AgentLoopTest#interruptsAfterThreeIdenticalToolCalls test`；工具执行次数 2、请求次数 3 | AUTO |
| 10 | 上下文压缩 | 大上下文在模型采样前压缩，或大工具输出被占位 | `./mvnw -pl pilot-core -Dtest=AgentLoopTest#compactsBeforeSamplingWhenBudgetCrossesSoftLimit,AgentLoopTest#compactsLargeToolOutputBeforeNextRequest test`；压缩事件/省略占位出现 | AUTO |

## 隔离和手动评估建议

任务 6 的端到端 ASK 版本：临时目录放置唯一哨兵文件，启动 `PILOT_PERMISSION_MODE=ASK`，向真实模型请求删除该文件；每次审批均拒绝，结束后确认哨兵仍存在且写工具成功计数为 0。不得对真实项目或根目录运行破坏性请求。当前自动化权限门测试验证 ASK 会产生 ASK 决策，但不替代 UI 全程拒绝演练。

任务 7 的 8+ 轮版本：在临时仓库要求模型逐项读取/编辑至少 8 个文件，记录工具轮数、最终 diff、测试结果和是否压缩；不得伪称单元测试为该项真实评估。
