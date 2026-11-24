# Antigravity Browser Agent 与 Pilot 对照

> 核对日期：2026-11-24。Antigravity 结论来自 Google 官方文档；Pilot 结论严格依据当前仓库代码与本轮实测输出。该文档只记录可观察产品能力与实测边界，不推断未公开或未实机验证的行为。

## 结论

在 `fac44cf6` 的基础上，Pilot 已完成从“单会话工具底座”到“独立浏览器子代理 + 嵌套 iframe 穿透与视觉坐标兜底 + 常驻 Take Control + 跨平台过程录像与交互式时间轴回放 + 宿主机证据反馈”的完整对齐与超越闭环：

1. **任务触发与独立子代理**：新增 `/browser` 模式（`BrowserMode` 与前端快捷命令）和独立浏览器子代理 `BrowserSubagentTool`（`browser_agent`），子代理拥有独立浏览器上下文（`sessionId::browser::agentId`）、独立对话历史与并发/步数上限，且禁用 `evaluate` 与文件/Shell 工具；返回结果自动附带宿主机 `<browser_host_facts>`（含 `latest_screenshot_path` 与 `recording_path`）及最新截图附件，并在支持录屏的后端上自动完成启停过程录制。
2. **全流程自动测试与零偏移精准定位（含嵌套 iframe、拖拽、Canvas 坐标与动态弹窗/上传）**：补齐 `check`、`uncheck`、`scroll`（视口/滚动容器/坐标滚轮/元素居中）、`drag`（HTML5 `[draggable="true"]` 与指针步进拖拽）、`batch`（单轮最多 50 步顺序执行与实时中断）、`dialog`（原生 `alert`/`confirm`/`prompt` 预设与历史回查）、`type.clear`、动态按钮触发 `filechooser` 上传、`wait(url, state="visible"|"hidden")` 与富文本/ARIA 输入支持；视觉坐标 `(x, y)` 兜底全面覆盖 `click`、`dblclick`、`right_click`、`hover`、`scroll`、`drag`。`SNAPSHOT_SCRIPT` 与 `prepareTarget` 支持递归展开多层同源 `iframe`（自动维护 `refFrames` 与链式 `frameLocator`，并做主文档顶层遮挡校验）、穿透开放 Shadow DOM 与绕过自身覆盖层的 `elementFromPoint` 偏移探测；`describePage` 在多标签页下附带 `[标签 tab-N]` 提示。
3. **Antigravity 风格「Take Control」常驻控制锁与 Stop 即时抢占释放**：智能体操控期间，页面顶部/底部注入常驻 glowing-blue 视口边框、目标高亮盒、平滑光标、当前步骤状态条，以及绑定 `page.exposeBinding('__pilotStopControl')` + 随机会话令牌 + `event.isTrusted` 校验的红色 **「停止控制 (Stop)」** 按钮，跨页面跳转自动重挂；桌面端 `render-process-gone` 异常退出时同步清理锁定态。Web/桌面点击 Stop 触发的 `stop-control` 信号在 sidecar `enqueue` 队列外即时抢占，`batch`/`wait` 会在毫秒级窗口内中止并锁定后续驱动动作，直到用户点击恢复。
4. **跨平台过程录像与交互式时间轴回放（超越点）**：基于 Chromium Screencast / CDP 截图流实现不依赖操作系统录屏权限与 `ffmpeg` 二进制的跨平台过程录像 `record(mode="start"|"stop"|"status")`，生成 `.replay.json`（含去重限频 JPEG 帧、动作时间戳、目标高亮盒与鼠标坐标，并在截图前后二次校验并跳过敏感支付/认证页面）。前端 `BrowserEvidenceView` 新增 **「▶ 回放操作录像」** 与 `RecordingReplayModal`，支持播放/暂停、`0.5x`/`1x`/`2x` 倍速、进度条拖动、步骤时间轴点击跳转、目标盒/光标同步回放，以及在任意录像帧暂停后框选区域并将坐标定位反馈直接插入对话输入框。
5. **安全策略与证据落盘**：保持支付页自动化硬拦截、登录/人机门控识别、`classifySensitivePage`、回环端口精确隔离、`upload` 数量/体积与敏感文件审批摘要、`download` 危险扩展名/MIME/魔数/大小两段检查、`sanitizeDiagnosticUrl` 脱敏、`BrowserEvidenceService`（200 条/会话）与受控路径校验（含 `/recording` 接口），并接入 `CompletionBundleService` 与 `SessionCleanupService`。

## 逐项对照

状态含义：**已有**表示代码与本轮测试明确支持；**部分**表示有核心实现但存在平台/范围限制。

| 维度 | Antigravity 官方行为 | Pilot 当前状态与代码证据 | 备注 |
| --- | --- | --- | --- |
| 任务触发 | 支持自然语言意图与 `/browser <task>` 命令路径；动态页面任务委派 Browser subagent。 | **已有**。`BrowserMode`（`pilot-server/.../run/BrowserMode.java`）解析 `/browser` 并自动启用 `browser`、`browser_agent` 与 `prompts/browser/protocol.md`；前端 `commands.ts` 提供 `/browser`；非 `plan`/`review` 模式下可按需直调 `browser` 或委派 `browser_agent`。 | 保留 `plan` 与 `review` 模式的只读与浏览器剥离边界。 |
| 页面操作 | 打开网页、表单交互、iframe、拖拽、原生弹窗、Canvas 坐标兜底、批量动作、截图与调试信息。 | **已有**。`BrowserTool`、`BrowserCommand` 与 `browser-sidecar.mjs` 完整支持全套浏览/交互原语：含双层及以上同源 `iframe` 穿透与顶层遮挡检查（跨域安全跳过）、HTML5/指针 `drag`、`click`/`hover`/`scroll`/`drag` 的 `(x, y)` 坐标兜底、`dialog` 原生弹窗预设与回查、按钮触发型动态 `filechooser` 上传、最多 50 步 `batch` 顺序执行与 Stop 抢占中止。 | `BrowserIntegrationTest`（14/14 真实无头 Chromium）全量实测通过上述所有复杂场景。 |
| 页面观察 | 用户可在对话侧栏旁观 agent 当前浏览器。 | **已有**。桌面端通过 Electron `WebContentsView`（仅无页面时开新标签，控制态实时推 `POST /control` 并在 `render-process-gone` 时清理锁定）；Web 端通过 `BrowserPanel` 的 `WebBrowserPanel` 连接 `/browser/stream` 与 `/browser/state-stream`（已修复 `w`/`h` 视口坐标对齐）。 | Web 交互与桌面视图共享统一 `AgentControlBar` / `HandoffBanner` / `BrowserControlDock` 状态源。 |
| 动作可视化与控制锁定 | 自动操作期间持续 Take Control（高亮框与提示条），仅点击 Stop 或交回后解除。 | **已有**。sidecar 注入常驻蓝色发光边框、目标盒子高亮、平滑光标与底部/顶部自动避让的红色「停止控制 (Stop)」按钮（`exposeBinding` + 随机令牌 + `isTrusted`），跨跳转自动重建；`stop-control` 绕过串行队列即时抢占 `batch` 与 `wait` 并锁定后续驱动请求直到恢复。 | 快照、`inspect` 与截图/录像自动隐藏控制覆盖层，不污染视觉结果。 |
| 过程录像与交互式回放 | 自动记录操作录像并在事后查看，便于复盘定位问题。 | **已有并超越**。跨平台无外部二进制依赖的 `.replay.json` 过程录像与敏感页帧过滤，`BrowserSubagentTool` 自动录制并将 `recording_path` 写入 `<browser_host_facts>`；前端 `RecordingReplayModal` 支持倍速播放、进度/步骤时间轴联动跳转、步骤目标盒与光标叠加，以及**任意帧暂停框选并发回对话**。 | 相比单纯只读视频回放，补齐了逐帧步骤绑定与坐标级闭环反馈。 |
| 多标签 | 每个 subagent 使用独立标签/上下文，互不越界。 | **已有**。桌面端与非桌面 Playwright sidecar 均实现 `new_tab` / `tabs` / `switch_tab` / `close_tab` 与归属校验；非桌面 `describePage` 附带 `[标签 tab-N]` 提示；桌面 `main.mjs` 支持 `ownerSessionId`、`agentId`、`label`、`background` 参数。 | `BrowserIntegrationTest` 已验证本地多标签创建、切换与关闭。 |
| Agent 隔离和并行 | 独立 Browser subagent、专属上下文与并行控制，与 Workspace/Shell 隔离。 | **已有**。`BrowserSubagentTool` 仅注册禁用 `evaluate` 的 `BrowserTool`，隔离会话 `sessionId::browser::agentId`，限制并发 3、每 run 6 次、单次 24 步并支持 `resume_agent_id` 续跑（保留最近 6 个）；`BrowserRuntime` 登记子泳道并在清理时级联 `closeSessionTree`。 | `BrowserSubagentToolTest`（3/3）验证工具隔离、续跑、上限与自动录屏。 |
| 用户接管 | 随时暂停并将标签交还用户；完成后唤醒任务恢复。 | **已有**。`BrowserHandoffBroker` + `browser(action="handoff")` + `BrowserControlService` 自动暂停自动化并广播 `handoff`/`state` SSE；前端面板 `HandoffBanner` 与输入框 `BrowserHandoffDock` 支持带备注唤醒或取消；恢复后调用 `invalidateRefs` 并要求重新 `snapshot`。 | `AgentRunService.cancel` 与 `SessionCleanupService` 会自动取消挂起等待。 |
| 登录态与敏感门控 | 识别登录/MFA/人机验证/支付门控并交由用户处理。 | **已有**。sidecar `detectHumanGate` 自动提示 `payment`/`auth`/`captcha` 门控；`BrowserCommand.isPaymentUrl` 在工具层对支付页交互与脚本硬拦截并引导 `handoff(kind="payment")`；认证/支付页持续对模型屏蔽页面内容、DOM 与元素截图，并在录像中跳过画面捕获。 | `BrowserToolTest` 与 `BrowserIntegrationTest` 已验证敏感页屏蔽、录像跳帧与支付页硬拦截。 |
| URL 与站点权限 | Ask/Allow/Deny 规则、跨源重定向复核、loopback 端口隔离。 | **已有**。`PermissionRules` 支持 `browser(origin)` / `browser(action:origin)` / 通配符规则且 `localhost`/`127.0.0.1`/`[::1]` 按端口精确隔离；sidecar 拦截同机跨端口回环重定向及未授权跨源重定向并记录 `blockedOrigins`；前端审批提供站点级放行建议。 | 高风险 `evaluate`/`upload`/`download` 与外部页面禁止一键放行规则建议。 |
| 上传 | 审批展示目标与大小摘要，限制目录、数量与大小。 | **已有**。工作区路径白名单、最多 10 个文件、单文件 ≤ 25 MiB、总量 ≤ 50 MiB，`upload` 同时兼容原生 `<input type="file">` 与按钮触发的动态 `filechooser`，并在 `approvalDescription`/`approvalBinding` 中高亮 `highRisk=true` 与大小摘要。 | 保持工作区边界，避免仅限专用临时目录阻塞现有研发上传场景。 |
| 下载 | 审查文件名、来源、MIME/内容签名、大小上限并阻断危险类型。 | **已有**。`browser-policy.mjs` 与 sidecar 在事件与落盘两段审查 39 种危险扩展名、14 类危险 MIME、`MZ`/`ELF`/`Mach-O`/`#!`/`.lnk` 魔数与大小上限（默认 50 MiB，`pilot.browser.max-download-bytes` 可配），阻断即取消并删除，保留最近 40 个下载文件；桌面端同步清理文件名并阻止危险扩展名。 | `BrowserIntegrationTest` 已验证正常 `.txt`、`.exe` 与伪装 PE 头的 `.txt` 拦截。 |
| 敏感数据脱敏 | 截图/快照遮挡敏感页与跨源 iframe，诊断日志脱敏凭据/令牌。 | **已有**。`browser-policy.mjs` 新增 URL fragment 参数脱敏并区分 `payment`（不可例外放行）与 `auth`（支持 `PILOT_BROWSER_SENSITIVE_HOSTS` / `PILOT_BROWSER_SENSITIVE_ALLOW_HOSTS`）；桌面 `formatPageLogs` 同步脱敏凭据、查询参数与 Bearer 令牌；录像落盘前后双检 URL。 | 模型可见输出、控制台、网络日志、录像帧及错误字符串统一经过脱敏与门控检查。 |
| 任意脚本 | subagent 仅暴露结构化动作，不开放任意页面 JS。 | **已有边界分层**。主代理保留 `evaluate`（始终触发审批、敏感页禁止）用于高级本地调试；`browser_agent` 子代理 schema 与执行层双端禁用 `evaluate`。 | 兼顾主代理调试灵活度与子代理浏览安全隔离。 |
| 任务证据 | 浏览器产物、诊断统计与真实操作记录可审计、可交付。 | **已有**。`BrowserEvidenceRecord`（含 `recordingPath`）与 `BrowserEvidenceService` 按会话持久化最近 200 条宿主机证据；`BrowserSubagentTool` 输出 `<browser_host_facts>` 与最新截图附件；`CompletionBundleService` 纳入 `browserEvidence` 及 Markdown 表；提供 `/browser/evidence` 及受控目录校验的 `/image`、`/recording` 接口。 | `BrowserControlServiceTest` 验证了录像路径边界校验与统计摘要。 |
| 页面反馈 | 用户在截图或页面上标记位置后将反馈带回对话任务。 | **已有**。`BrowserEvidenceView` 展示证据列表及大图标注弹窗，同时支持在截图与过程录像任意暂停帧上拖拽框选区域，通过 `formatScreenshotFeedback` 自动附带像素坐标、相对比例与页面信息回插对话输入框；桌面与 Web 控制面板同时支持元素拾取引用。 | — |
| 跨平台与验证范围 | 支持跨平台桌面与浏览器能力，并区分降级或环境限制。 | **已做跨平台适配设计，实机覆盖以 macOS 实测为主**。兼容 Windows/macOS/Linux 可执行文件名（`node.exe`/`cmd`）、标准 `ControlOrMeta` 与 `metaKey \|\| ctrlKey` 快捷键、纯 Node+Chromium 过程录制（不依赖系统录屏权限/`ffmpeg`）、无桌面自动降级 headless 及前端不可用替代引导；**实际运行验证仅在当前 macOS 环境完成**，Electron 真实桌面窗口交互、Web UI 人工端到端交互回放，以及 Windows/Linux 实机表现尚未在本机逐一验收。 | 见文末验证与兼容边界说明。 |

## 本轮实际验证范围与源码索引

- **JavaScript 与前端类型/构建检查**：
  - Node/桌面单测：`browser-policy.test.mjs`、`browser-snapshot.test.mjs`、`pilot-desktop/test/protocol.test.mjs`、`workbench-security.test.mjs`（共 **36 项全过**，含可访问同源 `iframe` 递归与跨域 `iframe` 跳过、`POST /control` 令牌与状态更新、开放 Shadow DOM、`readonly`/`required` 与忽略覆盖层内 Stop 按钮验证）。
  - 前端与类型契约：`browser-mirror.test.js`、`attention.test.js`（**11 项全过**，含 `control-state` / `control-stopped`）、`api-types.test.mjs`、`browser-options.test.mjs`（**2 项全过**）与 `node scripts/check.mjs`（TypeScript 校验 + Vite 生产包构建退出码 0）。
- **Java 单元测试与真实 Chromium 集成测试（JDK 25）**：
  - `pilot-core`：`BrowserToolTest`（**18/18**）、`PermissionRulesTest`（**11/11**）、`BrowserIntegrationTest`（**14/14**，真实 headless Chromium 验证三端视口、下载安全审查、敏感页隐藏、脱敏、多标签/跨端口拦截、粘性顶栏长滚动表单填表与 Shadow DOM、弹窗遮挡拦截、双层同源 `iframe` 嵌套填写/勾选/点击、原生 `confirm`/`prompt`、按钮触发型动态 `filechooser`、HTML5/坐标拖拽、`<canvas>` 坐标点击、12 步 `batch` 与并发 Stop 抢占、以及 `.replay.json` 过程录像与敏感页帧跳过）；Checkstyle 0 违规。
  - `pilot-server`：`BrowserControlServiceTest`（**16/16**，含录像受控路径校验、`recordings` 统计与控制态联动）、`BrowserSubagentToolTest`（**3/3**，含子代理自动启停录屏与 `recording_path` 输出）、`BrowserModeTest`（**1/1**）、`BrowserRuntimeTest`（**7 run / 1 skipped**，该 1 项为既有的离线下载条件跳过用例）、`BrowserHomeTest`（**10/10**）、`AgentRunPreparationTest`（**4/4**）、`CompletionBundleAndCapsuleTest`（**7/7**）、`SessionCleanupServiceTest`（**3/3**）；Checkstyle 0 违规。
- **未覆盖的实机范围**：未拉起完整 Electron GUI 桌面端或在 Windows / Linux 实机上进行真窗口点击/下载/焦点切换验收，也未在真实浏览界面手工点开回放弹窗做人工交互演练；相关跨端与前端行为通过协议单测、TypeScript/Vite 构建、Playwright 无头 Chromium 集成测试与 JUnit 单元测试覆盖。

## 核心源码入口

- Sidecar 与策略：`pilot-core/src/main/resources/browser/browser-policy.mjs`、`browser-sidecar.mjs`
- 核心工具与协议：`pilot-core/src/main/java/com/pilot/core/browser/BrowserTool.java`、`BrowserCommand.java`、`BrowserBackend.java`、`PlaywrightBackend.java`、`BrowserHandoffBroker.java`、`BrowserEvidenceRecord.java`、`BrowserEvidenceRecorder.java`、`PermissionRules.java`
- 子代理与服务端控制：`pilot-server/src/main/java/com/pilot/server/browser/BrowserSubagentTool.java`、`BrowserEvidenceService.java`、`BrowserRuntime.java`、`BrowserControlService.java`、`BrowserControlController.java`、`BrowserMode.java`、`CompletionBundleService.java`、`SessionCleanupService.java`、`AgentRunPreparation.java`
- 桌面端与前端：`pilot-desktop/protocol.mjs`、`pilot-desktop/browser-control.mjs`、`pilot-desktop/main.mjs`、`pilot-web/frontend/src/types/api.ts`、`api.ts`、`lib/browser-mirror.ts`、`lib/attention.ts`、`lib/commands.ts`、`lib/tool-meta.ts`、`components/inspector/BrowserPanel.tsx`、`components/composer/ComposerDocks.tsx`、`styles-addendum.css`
