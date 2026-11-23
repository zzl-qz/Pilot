# Antigravity Browser Agent 与 Pilot 对照

> 核对日期：2026-11-24。Antigravity 结论来自 Google 官方文档；Pilot 结论来自当前源码和测试。该文档比较可观察到的产品能力，不推断未公开实现。

## 结论

Pilot 已有完整的 Playwright 浏览器操作底座：页面导航、结构化 DOM 快照、点击和表单输入、上传下载、截图、console/network 诊断、标签切换、权限门控、会话隔离、镜像和用户接管都已落代码。Pilot 不是缺少“让 AI 控制浏览器”的核心能力。

主要差距在编排和信任体验：Antigravity 把浏览器作为独立子代理运行，每个代理拿独立标签和上下文，用户能实时看到动作并随时收回控制；文件传输、敏感页面、诊断数据和任务产物也有专门的权限与处理规则。Pilot 当前有部分底层零件，但还没有把它们连成同样完整的浏览器任务流程。

首轮已落地 agent 动作状态提示，以及模型快照/截图中的高置信度字段和跨源 iframe 遮挡。本轮补上敏感登录/支付页内容隐藏、诊断信息脱敏和下载文件名/危险扩展名审查。仍需补全登录等待/续跑、任务证据回看和浏览器子代理隔离。

## 逐项对照

状态含义：**已有**表示代码和测试明确支持；**部分**表示有基础能力但缺产品流程或只在特定运行模式成立；**缺少**表示当前未找到实现；**不同定位**表示能力不应照搬。

| 维度 | Antigravity 官方行为 | Pilot 当前状态与代码证据 | 建议 |
| --- | --- | --- | --- |
| 任务触发 | App 可依据自然语言任务使用浏览器；CLI/企业路径提供 `/browser <task>`。静态页面问题可直接读 URL，动态页面任务委派给 Browser subagent。 | **部分**。模型能调用 `browser` 工具，但调用由主 Agent 规划；工具提示要求先启动应用再 navigate。没有浏览器专属 subagent/命令路由。`WorkspaceToolFactory.sessionExtras` 为会话注册 `BrowserTool`。 | 借鉴“任务意图触发浏览器能力”和静态/动态任务分流；不要增加独立的“执行”按钮。沿用现有 Plan 模式，浏览器调用作为计划中的工具步骤。 |
| 页面操作 | Browser Agent 可打开网页、交互应用、处理表单，并检查 console、网络失败和截图。 | **已有**。`BrowserTool` 提供 navigate、snapshot、click、fill、type、select、upload、download、screenshot、console、network、inspect、resize 等动作；Playwright 集成测试覆盖页面读写和上传。 | 复用现有动作集。`evaluate` 是 Pilot 的额外能力，当前始终需要审批；继续保持这个边界，避免为了界面相似而开放任意脚本。 |
| 页面观察 | 用户可在对话侧栏旁观 agent 当前浏览器。 | **部分**。Pilot 有页面镜像、浏览器面板、导航状态流和用户接管；无头/桌面路径的展示方式不同。 | 复用现有镜像、侧栏和 SSE 事件，不另建浏览器工作台。 |
| 动作可视化 | 显示 agent 光标和状态胶囊；用户可在 agent 运行时继续使用其他标签。 | **部分**。sidecar 现在会在目标元素旁显示动作状态，且快照和 agent 截图会排除提示；`agent-action` SSE 仍未接入前端，因此无可见页面或 headless 观察场景尚未闭环。 | 已补页面内状态提示。后续仅在需要镜像叠层或 headless 实时观察时接通动作 SSE；避免重复渲染两套提示。 |
| 多标签 | 每个 Browser subagent 使用自己的标签，可多个 subagent 并行；不能检查或操作另一个 agent 或用户的标签。 | **部分**。桌面浏览器有按会话归属的标签列表、切换和关闭，切换前检查标签归属；非桌面 Playwright context 无多标签工具。标签由当前会话的 `BrowserTool` 选择，没有 per-subagent tab lease。 | 复用标签枚举、所有权检查和切换 API。若要并行跑浏览器任务，需自行增加每个运行/子代理的标签租约、并发限制与清理；不能只复制标签 UI。 |
| Agent 隔离和并行 | 浏览器任务由独立 Browser subagent 承担；自己的上下文和专属标签，不与父代理或并行浏览器任务共享控制权。 | **部分**。本地 BrowserContext 按 Pilot session 隔离 cookie/storage；但浏览器工具注册以主会话 ID 为 key。桌面模式使用工作区浏览器分区，文档明确 cookie/storage 沿用该分区。Pilot 有通用多 Agent DAG，但未证实子 Agent 获得独立 browser runner/context。 | 可借鉴独立 runner 的产品边界；需要 Pilot 自行设计按 run/subagent 隔离的 BrowserContext、事件归属、生命周期回收和权限继承。先检查现有多 Agent 需要，再决定是否做，不必为了对标强行并行。 |
| 用户接管 | Stop 可停止 agent，并把对应标签控制权交还用户；并行代理彼此独立。 | **部分**。Pilot 有 pause/resume API；暂停后 BrowserTool 会拦住交互与导航，观察动作仍可用。它不是完整的浏览器子任务取消/恢复协议，也没有登录完成后自动唤醒和复验的明确状态机。 | 复用暂停、恢复和手动操作入口；自行补“等待用户完成登录/验证码 → 用户继续 → agent 重新检查登录态”的任务级协议。 |
| 登录态 | Browser Agent 可在受控的浏览器标签里协作处理需要登录的页面；敏感身份服务有独立限制。 | **部分**。已有 pause/resume；本轮明确接管时由用户完成登录/MFA，恢复后 Agent 必须重新 snapshot 并放弃旧 ref。敏感认证页面内容对模型隐藏。仍无登录墙自动识别、显式等待态或用户恢复后自动唤醒任务。 | 当前沿用现有接管按钮与状态流；后续若需要长时间等待，应在任务生命周期上增加可取消等待态与恢复后复验，不能让工具调用无限阻塞。 |
| URL 与站点权限 | 有 Ask/Allow/Deny 规则；跨源重定向重新检查；loopback 按端口隔离；敏感 Google/身份/支付站点有专门限制。 | **部分**。Pilot 有 `allow-hosts`/`deny-hosts`、公开 HTTPS 检查、外网 `purpose` 和绑定页面的 `expected_url`。默认本机地址策略较宽；未找到 loopback 端口级授权，也未找到身份/支付站点策略。完全放行档位可以直接操作外网页面。 | 借鉴按站点授权、跨源重定向复核和高敏站点策略。把当前端口策略与本机开发工作流一起设计，不能只复制默认值。 |
| 上传 | 上传前申请批准；限制来源为任务临时文件，并限制文件数量/单文件及总大小。 | **部分**。上传路径限制在工作区，最多 10 个；作为交互动作受 Pilot 权限档位控制。未见专门的任务临时区、大小策略和独立审批摘要。 | 复用路径校验；需要更强保护时增加显式选择文件/目标页面/大小信息的审批，而非采用 Antigravity 的具体额度照搬。 |
| 下载 | 审批呈现文件名、来源、重定向、MIME、目标目录；脚本和可执行文件等类型可自动拦截。 | **部分**。下载可保存到 Pilot artifact 目录，保留浏览器建议文件名，回执包含来源、扩展名判断和路径；常见可执行/脚本扩展名会拦截。独立下载审批卡片、响应 MIME/内容签名校验、重定向链审查和大小限制尚未实现。 | 已补下载回执、扩展名拦截与 Playwright 下载事件支持；后续根据真实风险决定 MIME/签名校验与大小上限。 |
| 敏感数据脱敏 | 敏感页面及未授权 iframe 的截图/快照遮挡；console/network 诊断会过滤 Cookie、认证头及 OAuth/JWT 等内容。 | **部分**。snapshot/text/inspect/links 对登录、身份、支付类 URL 隐藏内容；agent 截图遮挡整页；普通页面仍遮挡敏感字段、`data-sensitive` 控件及跨源/srcdoc iframe。console/network、导航 URL、错误、模型工具回执和本地日志会脱敏常见认证头、令牌、密钥和敏感查询参数。页面语义中的任意个人信息无法仅靠通用规则可靠识别。 | 本轮已在模型输出与日志边界增加规则脱敏，并对登录/支付页 fail-closed；后续可扩展敏感站点清单与用户可配置例外，需避免误把页面内容交给模型。 |
| 任意脚本 | Browser Agent 使用受限的浏览器动作，不开放任意页面 JavaScript；诊断能力与 shell/workspace 隔离。 | **不同定位**。Pilot 提供 `evaluate`，任何权限档位都要审批；browser 工具和 shell/workspace 工具由同一主 Agent 调用。 | 不必照搬整套进程架构；如果将来引入 browser subagent，应给它独立工具集和权限，避免 browser 页面内容直接扩大 shell/workspace 权限。 |
| 任务证据 | 截图可作为 artifact 和任务材料，用户可查看 agent 进展；官方说明录屏可能不总能生成。 | **部分**。`screenshot` 返回图片附件，工具调用写入通用 trace；镜像帧是瞬态数据，不落盘；未找到浏览器会话录屏或统一浏览器任务报告。 | 复用图片附件和 trace 机制；补充任务级证据索引（关键截图、页面 URL、检查结果、失败诊断）。录屏不是第一阶段必需，也不要承诺每次都生成。 |
| 页面反馈 | 用户可在截图/页面选区上评论并把定位信息带回任务。 | **缺少浏览器专属回流**。现有用户可以接管页面或在对话中发消息，但未找到截图区域/DOM 段落锚定为浏览器反馈的流程。 | 适合后续借鉴，优先级低于动作状态、脱敏和登录续跑；可复用 Pilot 现有消息和附件入口。 |
| 跨平台/部署 | 新版内置 Browser Agent 逐步向个人订阅和桌面平台开放；官方文档列出企业、remote/SSH 等环境的限制及替代方案。 | **有跨平台设计，实机范围待逐平台确认**。Pilot 用 Chromium/Playwright 和 Electron 桌面浏览器，并提供 headless/外部 CDP 配置；当前仓库证据不能证明 Windows/macOS/Linux 的完整等价行为。 | 只借鉴按运行环境选择能力的原则。逐平台验证窗口、下载、CDP、缩放、输入法和无桌面降级；不能把本机开发体验当三平台结论。 |

## 可直接借鉴与需要自研

**可以直接借鉴的产品机制**（接入现有 Pilot UI 和任务流）：

- 根据任务内容决定是否调用浏览器，并把浏览器操作显示为普通任务步骤；保留 Pilot 现有 Plan 模式，不增加执行模式按钮。
- 实时显示“正在检查/点击/填写什么”，并在用户接管时清掉 agent 动作提示。
- 明确的 Stop、接管、恢复状态，以及可读的登录等待提示。
- 把关键截图和检查结论附在任务结果中；对截图或页面段落的反馈能够回流到当前会话。
- 权限请求展示站点、操作、文件、目标和影响，支持会话范围内的选择。

**需要结合 Pilot 自身架构实现**：

- 独立 Browser runner 与按 run/subagent 隔离的 context、标签归属、并发控制和取消回收。
- 页面敏感信息识别及模型出站脱敏策略，涵盖截图和诊断，而不是只修 UI。
- 站点、重定向、loopback 端口、身份/支付站点规则，以及上传下载校验和审批。
- 登录等待与续跑协议，确保用户接管后 Agent 重新观察页面再继续。
- 把瞬态镜像、trace、截图、诊断和最终验证连接为有明确保留策略的任务证据。

## 建议次序

1. **已落地动作可见性**：页面内显示点击/悬停/填写/输入/选择/上传提示；快照与 agent 截图排除提示。Node 快照测试和首轮 Chromium 截图检查已通过。
2. **已落地隐私、下载与登录交接首批保护**：登录/身份/支付 URL 的模型快照、文本、布局和链接隐藏，模型截图遮挡；诊断文本/URL/日志脱敏；Playwright 下载可保存到独立会话目录，危险扩展名阻止，回执显示来源和文件名；接管提示要求用户完成登录/MFA，恢复后重新 snapshot 并放弃旧 ref。Node 测试及独立真实 Chromium 下载/截图冒烟通过；新增 Java 集成测试待 JDK 25 环境运行。
3. **下一步补任务证据回看**：把浏览器关键截图、页面 URL、检查结论和失败诊断绑定到任务结果；评估是否需要显式等待/自动唤醒状态。当前截图已经作为工具图片附件进入通用 trace，但没有浏览器任务级证据索引。
4. **按真实需求决定并行浏览器**：只有需要多个子代理同时操作浏览器时，再做 per-run context/tab 隔离和多代理并行。现有桌面标签是同一会话下的标签管理，不等于 Browser subagent 隔离。

## 来源

### Google Antigravity 官方资料

- [Browser](https://antigravity.google/docs/browser)：Browser Agent 可处理动态页面交互、表单、截图和 console/network 检查；描述 Browser subagent、并行标签、动作状态、用户接管和浏览器截图产物。
- [Browser command](https://antigravity.google/docs/commands/browser)：CLI/企业环境的 `/browser` 任务入口及适用场景。
- [Permissions](https://antigravity.google/docs/permissions/)：Ask/Allow/Deny、浏览器站点权限、重定向、上传下载审批和敏感目标限制。
- [Side panel](https://antigravity.google/docs/side-panel/)：对话侧栏中的实时协作与任务观察。
- [Artifacts](https://antigravity.google/docs/artifacts/)：agent 结果作为结构化产物交付的产品模型。
- [Screenshots](https://antigravity.google/docs/screenshots/)：Browser subagent 可以截取可视页面或单个元素，作为检查渲染结果的方式。

### Pilot 源码与测试

- `pilot-core/src/main/java/com/pilot/core/browser/BrowserTool.java`：动作集、读写分类、审批规则、用户接管拦截和截图附件。
- `pilot-core/src/main/resources/browser/browser-sidecar.mjs`：DOM 快照敏感字段遮挡、iframe 快照省略、页面动作状态、console/network/log 脱敏、登录/支付页模型内容隐藏、下载文件名与危险扩展名审查、标签所有权检查。
- `pilot-server/src/main/java/com/pilot/server/browser/BrowserControlService.java` 与 `BrowserControlController.java`：镜像、动作 SSE、pause/resume 和用户操作入口。
- `pilot-web/frontend/src/store/streams.ts`：浏览器状态流只接收 nav/state/status/closed；当前未消费 action 事件。
- `pilot-core/src/test/java/com/pilot/core/browser/BrowserToolTest.java`、`BrowserIntegrationTest.java`、`pilot-server/src/test/java/com/pilot/server/browser/BrowserControlServiceTest.java`：权限、上传、隔离、镜像动作事件及接管测试。
- `pilot-web/src/test/js/browser-mirror.test.js`：当前仅验证浏览器状态事件归约。

此盘点是源码/测试核验，不代表已在 Windows、macOS、Linux 各完成实机验收；本次没有修改产品实现，也没有执行跨平台实机验证。
