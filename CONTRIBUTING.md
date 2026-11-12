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

本轮可复查的原始测量见 [2026-11-11 报告](evaluation/event-streams/2026-11-11/environment.json)。独立 SQLite 10 万条回放从约 41.87 秒降到 0.63 秒。真实 HTTP 使用内存仓储隔离传输成本，每客户端读取 10 万条 64 字符历史及 100 条并发新事件：

| 客户端数 | 追平 p99（秒） | 发布 p99（毫秒） |
| ---: | ---: | ---: |
| 1 | 0.71 | 0.195 |
| 10 | 2.99 | 0.190 |
| 50 | 11.68 | 0.137 |

指定的 1000 次连续 `中🙂` 增量，在默认 50ms / 8KiB 下合成 1 条，约 55.5ms 刷出；这不代表低速逐字输出也有同样比例。完整 Maven 验证 1454 项通过、16 项因环境或配置条件跳过；另行启用的压测、前端 400 项、桌面 41 项、Android 单测与 lint、Linux 容器 86 项重点测试通过。所有检查均不调用外部模型。

### 推理重置与旧历史回放

模型重试和重新采样时的 `assistant.reasoning` 重置与正文预览统一进入临时通道，不写入历史。旧版本已落库的推理片段和重置由 `EventCodec` 兼容读取，保留原有记录与序号，无需删库或重建会话。不同会话可以并行运行（`pilot.agent.run-threads` 默认 4），同一会话仍只允许一个活动运行。

2026-11-11 回归：修改前复现推理重置误入库及旧记录解码失败；修复后 macOS Java 25 完整验证 **1,478 项通过、16 项按条件跳过**，格式与静态检查通过。macOS 与 Linux Java 25 容器分别通过 **57 项**重点测试，覆盖模型先输出推理再返回 503、重试成功、临时重置实时交付且不落库、SQLite 重开及分页/订阅回放、历史与实时衔接、真实 HTTP/SSE，以及同工作区双会话并发和单会话互斥。实际数据库的临时副本中 **14,523 条**事件均可解码，两个受影响会话的 **240 / 153 条**事件完整有序回放，原库字节未改动。测试不调用外部模型。本次改动仅使用平台无关 Java 接口；Windows 实机、Linux 桌面及修复后实际桌面界面未验证。已经运行的旧服务需要重启后再刷新会话，代码提交本身不会替换进程中的类。

重点测试命令（Java 25；Windows 使用 `mvnw.cmd`）：

```sh
sh mvnw -B -ntp -pl pilot-server -DskipFrontend=true -Dtest=EventCodecNewEventsTest,JdbcSessionRepositoryTest,AgentRunServiceTest,RunRegistryTest,SessionEventHttpTest test
```

### 附件请求、原件与会话读取

附件 JSON 入口 `POST /api/sessions/{id}/messages` 同时检查声明长度和实际输入流字节数，未知长度或分块请求也受限，超限返回 413。限制在移动回执读取和 Jackson 解析之前生效。服务层先检查整批文件数、完整编码长度、Base64 格式及累计原始字节，全部通过才解码和落盘。

Web 与桌面从 `/api/settings` 的 `attachmentLimits` 读取数量、单文件、总字节和请求体预算；默认分别是 64 个、2 MiB、25 MiB。请求体预算包含 Base64 放大、每文件 1 KiB 元数据余量和 2,000,000 字节正文余量。移动端从工作台能力读取配置与传输上限的交集，最多 16 个、单文件 2 MiB、总计 6 MiB；后台对移动请求执行同一限制。客户端在读取文件前和发送草稿前校验，服务器始终保留最终校验。缺少或无效的能力值会明确反馈；旧手机协议的缺失字段采用原有移动上限。

文本、图片和二进制均先保存完整原始字节。长文本默认仅内联 20,000 字符的头尾，省略部分仍可取回；二进制附件可以单独发送。`read_attachment` 在每次调用时验证当前会话上传或仍被历史引用的文件，允许合法的分叉继承；删除原会话不破坏存活引用，移除继承引用后旧工具立即失去读取权限。工具也支持运行中首次追加附件，不依赖运行开始时附件目录是否存在。普通 `read_file` 的工作区边界不变。

可重复验证命令（Java 25；Windows 使用 `mvnw.cmd`；移动测试使用 Java 21 与 Android SDK）：

```sh
sh mvnw -B -ntp -pl pilot-core,pilot-server -DskipFrontend=true verify
sh mvnw -B -ntp -pl pilot-core,pilot-server -DskipFrontend=true -Dtest=AttachmentServiceTest,AttachmentBodyLimitFilterTest,AttachmentReferencesTest,ReadAttachmentToolTest,AttachmentHttpTest,CheckpointApiTest,SettingsApiTest,MobileWorkbenchControllerTest,ReadFileToolTest,ReadFileMediaTest -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix pilot-web/frontend run test:unit
npm --prefix pilot-web/frontend run check
npm --prefix pilot-web/frontend run test:composer
npm --prefix pilot-desktop test
node scripts/mobile.mjs test
```

2026-11-11 验证范围：macOS 后端完整验证 1,469 项通过、16 项按环境或配置跳过，前端 403 项、桌面 41 项及 Chrome/Electron 真实输入流程通过；Android JVM 单测 58 项与 Lint 通过；Linux 容器 Java 25 执行附件、真实 HTTP、SQLite 与文件读取与事件流关闭相关的 86 项测试通过。包括普通/编码路径的固定长度与分块 413、混合空附件拒绝、精确预算边界、较小配置、草稿重新校验、附件单独发送、原件完整性、分叉与引用撤销、数据库重开、中文空格路径、Windows 分隔符模拟和符号链接拒绝。Windows 实机、Linux 桌面和 Android 真机本轮未执行；附件浏览器回归已接入三平台 CI，不能将配置了 CI 表述为这些平台已通过。测试使用本地服务与模拟 API，不调用外部模型。

推送前复核又补齐四个边界：新运行存在待发缓冲时忽略旧运行回调；恢复高水位采用实际保留事件而非分配计数器；等待维护锁的操作获得锁后重新检查存储降级状态；准备日志写入后失败立即进入恢复清理。每项先加入失败复现，再验证修复；清空历史后客户端从零恢复，同时继续接受不复用旧序号的新事件。

分页和合并后，当前负载无需引入持久化完整历史状态快照；该项保留为有测量依据时的后续优化。当前输出的临时快照已经实现。

### 提交回执、导航同步与前端模块

Web/桌面的主对话、侧边对话、文件批注，以及引导、继续、历史重发和重新生成，在发送前申请服务端签名请求标识并保存本地凭据。消息正文和附件不存入回执；浏览器仅保存标识、会话、时间与短摘要。服务端复用数据目录下 `mobile/receipts` 的原子持久回执，先保存占位再执行；同一标识、方法、路径和正文只能执行一次，冲突标识拒绝执行。Web 消息使用下发的附件预算，移动端继续使用移动限额。

`GET /api/submissions/result` 只核对结果，不重新执行写请求。网络失败、超时、响应体读取失败、服务端 5xx，以及未找到或执行中断的回执都保留为“结果待确认”，不会自动重发。输入区提供查询与核对历史后的手动解除入口；解除本身不会发送草稿。业务执行与回执完成并非同一事务，进程在两者之间崩溃时保留未知结果，不能宣称自动确认业务完成。已知结果的确认请求失败时，本地保留确认凭据，重开、聚焦或联网后只重试确认。浏览器无法持久保存凭据时拒绝发送并提示原因；切换会话后旧提交的迟到成功不得删除新草稿。

书签使用服务端记录作为事实源。同源窗口通过存储事件通知刷新，后台暂停轮询，恢复可见或联网后刷新；通知会作废在途旧响应并合并补读。存储通知不可用时仍有聚焦和定时刷新兜底。旧书签迁移失败保留原始记录，成功后保存备份；分页定位使用动画帧与定时器共同等待布局，避免后台挂起。

前端 `store/` 仍共享同一 Zustand/Immer 状态，通过 `StoreContext` 注入访问入口，不反向导入全局 store。`sessions.ts` 管理会话创建、激活和列表；`history.ts` 管理分页、预取及请求失效；`streams.ts` 管理 SSE、帧批处理、重连和面板轮询；`run-controls.ts` 管理带回执的运行入口；`run-state.ts` 管理计划、队列和契约同步。输入草稿、模型配置、侧边会话和面板动作各自独立。`components/inspector/` 的各面板独立维护，主 Inspector 只负责装配。

异步操作返回时核对会话及代次；改变运行状态的查询还须核对正式事件游标和当前运行。切换会话要同时取消订阅、作废历史预取、清理忙碌状态，不能只比较会话 ID。创建请求去重只在同一草稿代次生效；未知消息提交仍沿用持久回执规则。

相关回归命令：

```sh
npm --prefix pilot-web/frontend run test:state-sync
npm --prefix pilot-web/frontend run test:unit
npm --prefix pilot-web/frontend run check
npm --prefix pilot-web/frontend run test:composer
npm --prefix pilot-web/frontend run test:navigation
npm --prefix pilot-web/frontend run test:refresh
npm --prefix pilot-web/frontend run test:interaction
npm --prefix pilot-web/frontend run test:models
npm --prefix pilot-web/frontend run test:sessions
sh mvnw -B -ntp -pl pilot-core,pilot-server -DskipFrontend=true verify
sh mvnw -B -ntp -pl pilot-server -DskipFrontend=true -Dtest=SubmissionControllerTest,SessionApiTest,MobileRequestFilterTest,MobileReceiptControllerTest,MobileReceiptStoreTest,AttachmentBodyLimitFilterTest,MessageLibraryTest test
```

`test:state-sync` 串行执行前端单测、类型及构建检查、桌面单测，以及导航、输入、SSE、工作台刷新、会话管理和历史滚动六组 Chrome/Electron 回归。报告、日志和界面产物统一保存在 `pilot-web/frontend/.tmp-check/migration/`，三平台 CI 会保留失败结果。默认使用已安装的 Chrome；ARM Linux 等环境可通过 `PILOT_TEST_CHROMIUM_EXECUTABLE` 指定 Chromium 可执行文件，路径直接传给浏览器启动接口。Linux 虚拟显示测试使用 `xvfb-run -a`，需要正常的桌面运行库及 Chromium 沙箱支持，不代表物理桌面或系统休眠已验证。

2026-11-12 追加验证：前端状态同步套件在 macOS 实机 Chrome/Electron 和 Linux ARM64 容器 Chromium/Electron 均通过，包括前端 456 项、macOS 桌面 46 项及 Linux 桌面 45 项（1 项 macOS 专属测试跳过），以及六组界面回归。整合 Java 验证 1,562 项通过、16 项跳过。Linux Chromium 原生文件剪贴板内容不可读时，已验证明确提示及文件选择器替代操作；Electron 直接文件粘贴通过。Windows 实机仍未执行，Linux 使用 Xvfb，不等于物理桌面验证。详见[状态同步验证记录](evaluation/results/frontend-state-boundaries-2026-11-12/validation.json)。

2026-11-11 本轮验证：macOS Java 完整验证 **1,474 项通过、16 项按条件跳过**；前端 **417 项**、桌面 **41 项**通过，TypeScript 与生产构建通过；Android JVM **58 项**与 lint 强制重新执行通过（未使用任务缓存）。Chrome/Electron 实际进程覆盖输入、断线刷新且不重发、侧边对话、模型保存、会话切换、书签双窗口并发、旧数据迁移失败/重试、跨两页定位，以及本地 HTTP/SSE 工作台刷新、原生菜单与 WebContentsView。导航的后台可见性和暂停帧是模拟测试，桌面刷新包含实际窗口和嵌入浏览器；不能据此推断操作系统休眠或物理断网均已验证。Linux Java 25 容器完成 **57 项**回执、附件限额、消息接口及 SQLite 书签重点测试。Windows 实机与 Linux 桌面本轮未运行，相关浏览器回归已补入三平台 CI；配置 CI 不代表远程任务已执行。测试使用本地服务和模拟 API，不调用外部模型。

## MCP 接入、调用边界与本地信任

Pilot 当前支持 MCP stdio：工作区 `.mcp.json` 覆盖数据目录中的用户级 `mcp.json`。`mcp_tools` 按需发现服务器及工具，`mcp_call` 使用已连接的服务器；不支持 HTTP/SSE 服务器配置，也不自动下载资源链接。

### 时限、关闭与结果未确认

启动握手默认 40 秒，工具发现默认 20 秒，调用默认 120 秒。发送序列化、写入队列、管道写入、flush 和响应等待共用绝对时限；分页发现也共用一份时限。最多同时等待 64 个请求，写入队列最多 64 项。

写入由单独的串行线程处理，读取线程不会为了回复服务器 ping 而同步等待写入。超时、中断、异常响应或连接关闭会唤醒等待方，并通过标准 `ProcessHandle` 接口终止子进程及其仍可发现的后代，再异步关闭管道，避免卡在输出流锁上。

已经开始发送的 `tools/call` 若没有完整结果，返回 `ERROR: 结果未确认`，执行证据状态为 `RESULT_UNKNOWN`，不能作为成功证据或可自动重试的普通超时。关闭本地进程不能证明远端副作用已撤销。必须先核对外部状态，不自动重发原操作。连接失效后，调用入口不会隐式重启；需要重新通过工具发现入口及当前权限模式连接。

### 配置更新的生效规则

- 尚未启动的服务器：首次发现时按当前配置启动。
- 已启动的服务器：比较 command、args、env、附加 PATH 和实际工作目录。变化后显示“配置已变更，重启 Pilot 服务后生效”，不再接受新调用，也不展示旧缓存为新能力。已发出的调用不会因为配置文件改动而被盲目重试。
- 本轮采用明确提示重启服务的方案，不提供热重启按钮。重启后重新发现工具，缓存来自新进程。
- 工作区 `disabled: true` 可以禁用同名用户级服务器。配置损坏时拒绝执行，不悄悄退回其他启动配置。
- 发现审批记录当时的配置快照；等待批准期间配置变化时拒绝启动。只读检查之后进程退出时，也不会凭旧检查启动新进程。
- `notifications/tools/list_changed` 会作废缓存及旧只读依据；重新发现后再使用新 schema。重复分页游标、过多页数或不完整列表不会缓存为完整发现结果。

### 数据边界

| 对象 | 限制与超限行为 |
| --- | --- |
| stdio 单条消息 | 8 MiB 原始字节，读取时计数，不等待整行或换行后才检查；超限关闭连接 |
| JSON 解析 | 深度 64、令牌 100000、字段名 4096 字符、数字 128 字符；在构建完整树前检查 |
| 配置文件 | 1 MiB，读取和解析前受限 |
| 工具发现 | 最多 20 页、1000 个工具、累计序列化数据 8 MiB；超限拒绝不完整结果 |
| 图片 | 每张及每次调用累计 5 MiB 解码字节，最多 8 张；先检查编码对应的字节数再解码 |
| 文本预览 | 30000 字符；预览截断不替代传输与解析限制 |

合规图片保存为工具产物后引用。超量原始响应被拒绝，不宣称已经保存完整内容；服务器可以改用小响应或资源链接，链接仅展示，不自动获取。

### 本地信任与权限覆盖

服务器提供的 `readOnlyHint` 默认不可信。工作区 `.mcp.json` 中的权限声明不会授予本地信任。用户可在数据目录的 `mcp.json` 中，为自己管理的服务器配置 `pilotPermissions`：

```json
{
  "mcpServers": {
    "example": {
      "command": "node",
      "args": ["./mcp/server.mjs"],
      "env": {},
      "pilotPermissions": {
        "workspaces": ["<替换为本机工作区绝对路径>"],
        "trustReadOnlyHints": false,
        "readOnlyTools": ["lookup"],
        "requireApprovalTools": ["publish"]
      }
    }
  }
}
```

`workspaces` 必须显式列出本机工作区绝对路径，按标准路径接口核对实际目录；不支持通配符或默认信任全部工作区。当前生效的 command、args、env 必须与用户级配置相同。仅服务器同名不够，相同的相对命令在其他工作区也不能继承信任。

`trustReadOnlyHints: true` 表示用户信任该服务器的只读声明；默认 false。`readOnlyTools` 是用户明确指定的只读工具，必须已被发现；`requireApprovalTools` 优先于两种只读放行，即使 AUTO 模式也要明确批准。没有这些本地授权时，ASK/AUTO_EDIT 仍需批准，READ_ONLY 拒绝；AUTO 仍按用户选择的模式放行，不把它冒充成服务器已通过只读验证。

本地策略撤销、连接替换、工具列表变化之后，旧只读判定不能继续执行。信任策略只影响 Pilot 的权限判断，**不是操作系统沙箱，也不证明路径指向的程序内容永远不变**。服务器启动后具备操作系统赋予该进程的权限。

依据：[MCP 2025-06-18 工具规范](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)要求客户端将未经信任的服务器工具注解视为不可信输入。

### 回归

```sh
sh mvnw -B -ntp -pl pilot-core -Dtest=McpClientTransportTest,McpContentLimitsTest,McpConfigurationTest,McpPermissionsTest,McpToolsTest,ToolOutcomeUncertaintyTest,ExplicitApprovalTest test
sh mvnw -B -ntp -pl pilot-core,pilot-server -DskipFrontend=true verify
```

阻塞管道、中断、关闭、超量响应、配置更新及权限测试使用当前 JDK 启动真实子进程，不依赖 shell 命令或真实外部服务；原有发现、调用和图片测试使用本地 Node fixture。平台实测范围与结果见[本轮验证记录](evaluation/results/mcp-reliability-2026-11-12/validation.json)，不能将 CI 配置或模拟路径测试当作 Windows 实机通过。

## 许可证

Pilot 自有源码和文档采用 [Apache License 2.0](LICENSE)。提交贡献的许可条款见许可证第 5 节。
