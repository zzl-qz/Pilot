# 贡献指南

感谢你关注 Pilot。仓库目前处于开发预览阶段；提交缺陷报告、文档改进或代码变更前，请先查看[首次启动指南](docs-public/getting-started.md)、[安全与隐私](docs-public/security.md)和[当前已知边界](docs-public/user-guide.md#当前边界)。

## 提交问题或建议

- 先搜索现有 Issues，避免重复报告。
- 缺陷报告请说明复现步骤、预期行为、实际结果、操作系统和相关运行时版本。
- 先检查日志与截图，再删除访问令牌、模型密钥、个人路径、会话正文及其他私人信息。
- 安全漏洞不要公开报告；按 [SECURITY.md](SECURITY.md) 联系维护者。

## 提交代码或文档

- 小步提交并聚焦一个问题；描述改动动机、行为变化和验证结果。
- 新提交的标题和正文使用中文；文件路径、代码标识符和专有名词可保留原文。
- 涉及 Web/桌面快捷键、剪贴板、路径、进程启动或构建脚本时，考虑 Windows、macOS 和 Linux 的差异；使用标准跨平台接口，必要时做能力检测。
- 浏览器不支持所需能力时，应提供明确反馈和可用替代操作，不要静默失败或把路径文本当作文件上传成功。
- 测试正常流程、失败和边界情况。注明本机验证、模拟测试、CI 覆盖和未做的实机验证；不要把单一系统的成功描述成三平台兼容。
- 文档中的启动、测试和配置说明应能在当前仓库复现。涉及界面的截图使用非敏感示例数据，并说明是否为模拟场景。

通过 GitHub Pull Request 提交改动时，请在说明中附上关联问题、变更摘要、实际运行的命令，以及尚未验证的平台或边界。PR 是否合并由维护者决定。

## 本地验证

### 服务端与完整 Maven 验证

根目录执行完整服务端构建与测试。Java 25 由项目源码运行时准备流程使用；Android 构建另需 Java 21。

macOS/Linux：

```sh
sh mvnw -B -ntp verify
```

Windows PowerShell：

```powershell
.\mvnw.cmd -B -ntp verify
```

### 桌面端与前端

安装桌面端依赖并运行其测试：

```sh
npm --prefix pilot-desktop ci
npm --prefix pilot-desktop test
```

前端单元与迁移检查在 `pilot-web/frontend/` 下执行：

```sh
node scripts/verify-migration.mjs --unit-only
```

前端构建与类型检查：

```sh
npm --prefix pilot-web/frontend run check
```

### Android

Android 修改需要安装 Android SDK，并使用 Java 21 与 Node.js 22。CI 使用以下项目入口运行移动端验证：

```sh
node scripts/mobile.mjs test
```

构建预览包：

```sh
node scripts/mobile.mjs build
```

完整平台矩阵和额外隔离测试见 [CI 工作流](.github/workflows/ci.yml)。本机没有 Android SDK 或对应操作系统时，请如实注明未覆盖项。

### README 截图

README 中的桌面截图由工作台成果与执行结果 UI 回归场景生成。要在本机重新生成，可运行：

```sh
npm --prefix pilot-web/frontend run test:results -- --production --chromium-only
npm --prefix pilot-web/frontend run test:products -- --web-only
```

这些场景使用模拟 API 和示例任务内容，截图路径会打印在命令输出中；发布前请人工检查截图，不要将测试结果描述成真实模型任务。

### 会话事件流与历史回退

事件流新客户端使用 `protocol=2`，携带最后成功应用的正式事件序号 `after` 与 `revision`。`stream.ready` 声明历史版本，`stream.reset` 要求丢弃旧版本的展示与分页缓存后重建；`stream.event` 是大事件引用，先通过 `/api/sessions/{id}/events/{sequence}?revision=...` 获取并应用，再确认游标。`stream.error` 明确停止自动恢复，不能跳过坏事件继续推进。

历史回放在会话锁外分页执行；捕获高水位时同时注册实时队列。`stream.preview` 在对应正式历史之后、后续增量之前发送，携带当前正文、推理、输出代次、偏移与完整性标志。临时事件只更新预览，不进入正式游标和历史窗口。正式文本、重试、结束与历史编辑会结算或清理当前输出。尚未落库的 token 只保证服务进程存活期间恢复，缓存不足会明确提示等待正式结果。

默认阈值可通过 `pilot.events.*` 配置；字节估算是保守预算，不等于 JVM 总堆内存：

| 配置后缀 | 默认值 | 作用 |
| --- | ---: | --- |
| `queue-items` / `queue-bytes` | 4096 / 8 MiB | core 每连接等待及正在交付的消息、快照预算 |
| `total-bytes` | 128 MiB | core 交付队列全局预算 |
| `connections` / `session-connections` | 256 / 32 | 全局与单会话订阅数量 |
| `page-size` | 256 | 每次读取的历史条数，最大 512 |
| `queue-wait-ms` / `send-timeout-ms` / `replay-timeout-ms` | 30000 / 30000 / 300000 | 排队、发送与整个回放的期限 |
| `reference-bytes` | 256 KiB | 正式事件超过保守估算阈值时改发引用；单条获取上限 8 MiB |
| `merge-ms` / `merge-bytes` | 50 / 8 KiB | 相邻正文或推理增量的发送窗口和 UTF-8 上限 |
| `preview-bytes` / `total-preview-bytes` / `preview-sessions` | 128 KiB / 16 MiB / 256 | 临时快照的保守字节和会话预算 |

Servlet 编码队列另有每连接 8 MiB（沿用 `queue-bytes`）、全局固定 128 MiB、4096 条上限。到达半个字节预算或 1024 条时，只暂停该连接的投递线程，等待非阻塞网络写入释放容量；Agent 发布仍走独立有界队列。SQLite 每页原始负载约 1 MiB，单条最多 8 MiB；同时解码的页数按 `total-bytes / 16 MiB` 限制在 1–16，默认 8。分页缓冲、仓储对象、JSON 编解码临时对象和 socket 缓冲不属于上述队列字节计数，需要连同 JVM 堆监控评估。

历史修改在同一个 SQLite 事务内提交事件、会话状态、工作记忆、计划和版本。涉及工作区或任务文件的回退先写持久化操作记录，以数据库提交标记决定重启时回滚或补齐文件；检测到外部修改会保留现场并阻止继续运行。该记录属于数据目录备份内容。进程中断测试覆盖准备、逐文件写入、数据库提交和再次恢复中断；不将其表述为断电、磁盘硬件故障保证。

可重复验证命令（Windows 使用 `mvnw.cmd`）：

```sh
sh mvnw -B -ntp -pl pilot-core,pilot-server -DskipFrontend=true -Dtest=SessionServiceTest,EventStreamBoundsTest,PreviewMergeTest,LiveOutputTest,HistoryEditCoordinatorTest,HistoryTransactionTest,SessionEventHttpTest -Dsurefire.failIfNoSpecifiedTests=false test
sh mvnw -B -ntp -pl pilot-core,pilot-server -DskipFrontend=true -Dtest=PreviewBenchmarkTest,EventReplayBenchmarkTest,SessionEventLoadTest -Dpilot.events.benchmark=true -Dsurefire.failIfNoSpecifiedTests=false test
node pilot-web/frontend/scripts/event-stream-smoke.mjs
```

压测报告输出到两个 Java 模块的 `target/event-benchmarks/`，包括 10 万条 SQLite 回放、真实 HTTP 的 1/10/50 客户端、并发发布延迟、接收条数、字节与资源峰值，以及 20/50/100ms 和 2/8/16KiB 的文字合并对比。`/api/sessions/stream-metrics` 提供累计发送、回放、缓存和稳定断开原因指标，不记录正文或推理内容。

2026-11-11 本轮已在 macOS 运行 Chrome 无头标签切换与暂停帧回调模拟、Electron 实际隐藏/显示窗口，验证真实 SSE、前端 store、重复事件和断线补发；Linux 容器运行后端事件流、SQLite、文件回退及真实子进程中断恢复。Windows 与 Linux 桌面验证接入现有三平台 CI，当前本地会话没有执行这些实机任务。OS 休眠/唤醒、Windows 文件占用与物理断电仍需相应实机验证；不能用定时器模拟或 Linux 容器替代这些结论。旧 Web 页面和 APK 需刷新或升级后使用新恢复控制消息。

本轮可复查的原始测量见 [2026-11-11 报告](evaluation/event-streams/2026-11-11/environment.json)。独立 SQLite 10 万条回放从约 41.87 秒降到 0.65 秒。真实 HTTP 使用内存仓储隔离传输成本，每客户端读取 10 万条 64 字符历史及 100 条并发新事件：

| 客户端数 | 追平 p99（秒） | 发布 p99（毫秒） |
| ---: | ---: | ---: |
| 1 | 0.75 | 0.140 |
| 10 | 2.89 | 0.266 |
| 50 | 12.00 | 0.142 |

指定的 1000 次连续 `中🙂` 增量，在默认 50ms / 8KiB 下合成 1 条，约 52ms 刷出；这不代表低速逐字输出也有同样比例。完整 Maven 验证 1449 项通过、15 项因环境或配置条件跳过；另行启用的压测、前端 399 项、桌面 41 项、Android 单测与 lint、Linux 容器 81 项重点测试通过。所有检查均不调用外部模型。

分页和合并后，当前负载无需引入持久化完整历史状态快照；该项保留为有测量依据时的后续优化。当前输出的临时快照已经实现。

## 许可证

当前仓库没有 `LICENSE` 文件，也没有发布贡献者许可或 CLA 条款。公开仓库不等于代码已按开源许可证授权；在维护者确定许可证前，请先联系维护者确认复用与贡献条款。
