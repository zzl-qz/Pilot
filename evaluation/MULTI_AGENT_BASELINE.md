# 多 Agent 改造前后对照基线（P0-2）

用途：给「多 Agent 是否真的缩短了关键路径」提供可复核的数据，不靠讨论拍板。
每项任务在**冻结的仓库副本**上跑，改造前跑一遍、每个阶段结束后再跑一遍，填同一张表对比。

## 约定

- 记录 `git rev-parse HEAD`，并使用 JDK 25（`pom.xml` 的 `java.version` 为 25；`JAVA_HOME` 指向 17 会出现
  `class file version 69.0` 编译错误）。
- 每项任务跑之前复位工作区（`git status` 干净、`git stash list` 为空），避免上一项的改动污染下一项。
- 判据只能是**可执行的命令结果或文件事实**，不接受“模型说完成了”。
- 分类 AUTO = 现有自动化测试可验证；MODEL = 需要真实模型演练；MANUAL = 需要人工确认。

## 当前基线（2026-11-03）

- 基线提交：`6657b82`
- 环境：Windows 11 / JDK 25（corretto-25.0.5）/ Maven 3.9.6
- 全量测试：`pilot-core` 511 例、`pilot-server` 425 例
- per-agent 用量：`GET /api/sessions/{id}/multi/dispatches` 的 `attempts` 里按任务给出
  输入/输出 token 与请求数；`exact=false` 表示端点没返回 usage，是本地估算，不能当 0

既有失败（**环境限制，与本次改动无关，不要当成回归**）：

| 用例 | 数量 | 原因 |
| --- | --- | --- |
| `BrowserIntegrationTest` | 3 | 本机浏览器运行时不可用 |
| `OsSandboxTest` | 2 | Windows 无 seatbelt / bubblewrap |
| `ManagedCommandToolsTest` | 1 | 本机无法创建隔离令牌 |
| `GitWorktreesTest` | 1 | Windows `core.autocrlf` 把 `one\n` 变成 `one\r\n` |

## 任务集

| # | 类别 | 任务 | 判据 | 类型 |
| --- | --- | --- | --- | --- |
| 1 | 单文件改 | 给 `AgentReport.parse` 补一条空结论的兜底，并附测试 | `mvn -pl pilot-core -Dtest=AgentReportTest test` 退出码 0 | AUTO |
| 2 | 单文件改 | 修复 `SandboxEditFileTool` 对同一文件连续改两处 | 沙箱里两处改动都在；`baseHash` 等于原文指纹 | AUTO |
| 3 | 单文件改 | 让只读子任务无法执行通用命令 | 只读 scope 的工具表里没有 `run_command` | AUTO |
| 4 | 跨文件接口 | 上游新增接口文件，下游按新接口实现适配 | 下游 `read_file` 读到上游沙箱内容，而不是「文件不存在」 | AUTO |
| 5 | 跨文件接口 | 两个互不依赖的任务写同一批文件 | 派发被拒绝并提示 `depends_on` | AUTO |
| 6 | 跨文件接口 | 改公共接口后，硬校验在合并候选上执行而非单个沙箱 | 接受一份变更后，其余沙箱的校验状态变为需重新校验 | AUTO |
| 7 | 并行 | 前端与后端各自独立实现（文件不重叠） | 两个任务同时进入 RUNNING，合并预检无冲突 | MODEL |
| 8 | 并行 | 上游改接口、下游适配的 DAG | 下游拿到上游产物后才 READY | MODEL |
| 9 | 调研 | 跨目录查调用链，不写任何文件 | 真实工作区零改动，报告含可核验证据 | MODEL |
| 10 | 调研 | 只读会话下派发可写任务 | 派发直接失败并说明需要切换权限档位 | AUTO |

## 每次运行要记录的字段

| 字段 | 说明 |
| --- | --- |
| 成功率 | 10 项里满足判据的项数 |
| 总 token | 模型返回的用量；未知标为估算，不能当 0 |
| 墙钟 | 从派发到拿到报告的时间 |
| 人工介入次数 | 仲裁、审批、手工修复的次数 |
| 失败类别 | 上下文不足 / 产物没传 / 文件冲突 / 校验失败 / 权限被拒 / 工具缺失 |
| 工作区状态 | 结束时 `git status` 是否干净 |

## 判定规则

| 结果 | 决策 |
| --- | --- |
| 并行任务成功率提升，且 token 增幅 ≤ 2 倍 | 继续投入异步派发、`wait_agents`、`steer_agent` |
| 并行任务无提升或成本翻倍 | 收缩：`dispatch_agents` 降级为只读调研并行 |
| 单 Worker 成功率 < 80% | 停止扩展，预算投向编辑可靠性与验证 |

后两种都是有效结论，不是失败。
