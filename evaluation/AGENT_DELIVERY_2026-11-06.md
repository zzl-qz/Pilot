# 三个会话的补齐与 Agent 调整记录

日期：2026-11-06。范围是当前工作区的实现、回归与本机验证；未重启用户正在使用的服务，也未对真实招聘账户发送申请。保留此前会话已有的修改，在其上完成补齐。

## 需求与交付对应

| 会话 | 原有缺口 | 本轮实现 | 验收入口 |
| --- | --- | --- | --- |
| `ses_c882b347-afd5-45a7-8b0c-5fb10274dbc7` | 产品方向文档和 PATH 探测未形成可执行的非编码任务链路 | `local_resources`、内置 `office`、`prepare_runtime`；公开 HTTPS 浏览器流程与逐次动作确认；复用已有 `publish_artifact` 保存成果 | OfficeToolTest、PrepareRuntimeToolTest、ExplicitApprovalTest、LocalCapabilityServiceTest、BrowserIntegrationTest、agent-delivery.test.mjs |
| `ses_6ebbaad9-2d16-4d1c-9780-f286e6eb2e61` | 原始目标可能被摘要遗漏、整批新观察保护不完整、工具存档没有读取入口、字符预算不等于端点请求预算 | 原文与证据引用元数据、来源指纹检查、全部未观察结果保护、只读分页恢复、三种模型协议的输入检查和输出预留 | ContextIntegrityTest、ContextBudgetTest、AgentLoopTest、ModelCapacityTest、JdbcToolCallJournalTest、ReadTaskHistoryToolTest |
| `ses_aedcbc31-ae41-4dda-be5d-20a76cd7ecec` | 空行把同一个列表拆成多个列表，编号重新从 1 开始 | 连续列表跨空行保持同一个 DOM 列表，支持非 1 起始编号；段落及列表类型切换仍分开 | agent-delivery.test.mjs、Web 全量测试和 Vite 构建 |

## 非编码任务如何实际执行

### 表格、演示稿与报告

1. Agent 根据用户材料整理 JSON 规格，使用 `create_file` 写入工作区。
2. `office create_xlsx` 生成真实 OOXML 工作簿，计算显式公式、检查错误单元格，重新打开文件；普通 `=...` 字符串保持文本。禁止外部工作簿引用与网络公式，不覆盖已有文件。
3. `office create_pptx` 生成可编辑文本幻灯片；重新解析内容，再用 `render_pptx` 输出所有页面图片。首四页自动作为模型图片返回，其余逐页读取。
4. 检查用户输入、公式汇总、页数与图片后，调用已有 `publish_artifact` 保存快照、来源和最终文件。报告可沿用 Markdown/TXT/PDF 等现有文件流程。

这是基础生成与检查路径，不是任意 Excel/PPT 文件编辑器，也不等于高级演示设计。当前内置 PPT 支持标题与要点，表格不支持宏；产物解析和渲染采用 POI，不能当作已经在 Microsoft Office 实机验收。

本机样例：`/tmp/pilot-delivery-20261106-headless/`。工作簿两项金额分别为 360、160，公式合计为 520；两页中文 PPT 已生成、读回并查看 PNG，无缺字、重叠或裁切。直接运行 Java 样例时需要 `-Djava.awt.headless=true`；首次未加该选项的受限环境运行触发了本机 AWT 进程中止，随后 headless 路径完成。Spring Boot 服务本身使用 headless 运行环境。

### 本机软件与依赖准备

- 发现过程不启动软件。除 PATH 外，显式按平台检查 Windows 的 ProgramFiles / LOCALAPPDATA 和 macOS 的系统、用户 Applications 目录。Linux 沿用 PATH。未发现不等于未安装，发现路径也不等于能力验证通过。
- `prepare_runtime action=plan` 展示来源、固定直接依赖版本、工作区目录、联网需求、清理方案，不启动进程。
- `prepare` 必须经过动作级审批；创建 `.pilot-task-envs/env_<UUID>/venv`，不改系统 Python 或全局 PATH。固定配方为 openpyxl 3.1.5、python-pptx 1.0.2、python-docx 1.2.0；只允许来自 PyPI 的 wheel，保留 pip 安装报告及导入检查日志。
- 失败/中断清理 venv 并保留状态、日志；`cleanup` 只处理已记录的环境 ID，仍需审批。
- 传递依赖的实际版本和哈希由安装报告记录，尚未做全平台完整依赖锁定。venv 是依赖隔离，不是 OS 安全沙箱。

### 外网浏览器

- 默认本机行为保持可用；`external=true` 与明确 `purpose` 开启一次公开 HTTPS 导航审批。使用独立浏览器上下文，不导入桌面登录 Cookie。
- 页面交互需本次审批，`expected_url` 必须与执行时完整 URL 相同；审批展示完整参数而非截断摘要。通用 allow 规则和无人值守模式不能跳过声明必须确认的动作。
- 私网/回环/凭据 URL、非 HTTPS、非 443 端口以及未经确认的跨站导航拒绝。后台非读取请求仅在已批准的同源动作执行期间允许；外网 WebSocket 关闭，service worker 禁用。
- 用户在面板中手动接管、点击、输入属于用户操作，保留可用；模型无法通过 BrowserTool 参数伪造这一内部入口。
- 登录、验证码、跨站 SSO 可能需要人工接管或再次导航确认；当前没有为具体招聘网站编写稳定的业务适配器。请求过滤不是浏览器进程的 OS 网络隔离，也不是 DNS 重绑定防护的完整证明。

## Agent 可靠性调整

### 目标、观察与摘要

- 摘要由模型生成，但用户原文和证据引用由程序另行保存；反复摘要不会再次把它们交给模型改写。压缩记录有源内容 SHA-256，应用前确认源历史未变化。
- 新工具结果以整批调用与结果为单位保留，直到模型真正收到成功响应；预算不足不能假定模型已经看过它们。工具图片同样受观察边界保护。
- `read_task_history` 从当前会话原始事件恢复用户目标与纠正；`read_tool_output` 从当前会话存档分页恢复结果，不重新执行原操作。
- 存档是不可覆盖记录；其他会话不能读取，重复调用 ID 不能覆盖原证据。归档失败或超过单条 200,000 字符时明确标记未归档，保护唯一原文，可能因此拒绝继续发送超大请求。
- 超预算请求会明确发送失败事件并保存进度，不把界面留在运行状态，也不再为收尾重复发送同一个超大请求。

### 请求容量

所有 Chat Completions、Responses、Anthropic 请求，包括摘要、审查和收尾，都在适配器发送前检查完整消息、工具参数/定义、工具协议配对和容量。输出上限分别写入对应协议字段。

容量配置是显式运行策略，不根据模型别名猜窗口。环境变量及默认值：

| 环境变量 | 默认值 | 用途 |
| --- | ---: | --- |
| `PILOT_MODEL_CONTEXT_TOKENS` | 128000 | 总上下文窗口策略 |
| `PILOT_MODEL_INPUT_TOKENS` | 128000 | 输入单独上限 |
| `PILOT_MODEL_OUTPUT_TOKENS` | 16000 | 输出预留及请求输出上限 |
| `PILOT_MODEL_MARGIN_TOKENS` | 8000 | 安全余量 |
| `PILOT_MODEL_IMAGE_TOKENS` | 8192 | 单附件保守估值 |

输入预算取 `min(input, context - output - margin)`，并同时遵守本地上下文水位。也可使用同名含连字符的 JVM 属性，例如 `-Dpilot.model.context-tokens=128000`。兼容网关的 Chat 输出字段可由 `-Dpilot.model.chat-output-parameter=max_tokens` 或 `max_completion_tokens` 显式设置。

这仍是估算，不是厂商精确 tokenizer 或计费保证。多模型运行目前共享这套部署容量策略，需按最小适用模型配置；后续应按实际模型配置档保存容量并用真实 usage 校准，不能把默认值当成模型规格。

### 完成状态

- `pilot.agent.completion-checks` 默认开启。显式计划未完成时最多追加两次继续提示；仍未完成则 `TASK_INCOMPLETE`，保留部分交付、计划和队列，不发送成功完成事件。
- 显式关闭循环检查的兼容模式仍保留服务器端未完成计划，任务进入待复核。
- 这一检查能挡住“计划显示未完成却宣布成功”，不能证明模型制定的计划没有漏项，或模型自行标记完成就是真的完成。验收条件与独立证据仍然必要。

## 验证记录

- Java：`JAVA_HOME=<JDK 25> bash mvnw -o -pl pilot-server -am spotless:apply verify`，最终结果见本机 `/tmp/pilot-final-verify.log`。包含核心、服务器全量测试、打包、Spotless 和 Checkstyle，最终 BUILD SUCCESS。核心 597 项（589 通过、8 跳过），服务器 653 项（648 通过、5 跳过），合计 1,237 项通过、13 项跳过，失败和错误均为 0。已检查打包文件包含新增工具、浏览器策略、提示词及 POI 依赖。
- Web：`npm --prefix pilot-web test`，269 项通过。
- 新增前端/浏览器策略回归：`node --test pilot-web/frontend/scripts/agent-delivery.test.mjs pilot-web/frontend/scripts/local-capabilities.test.mjs`，5 项通过。
- JSX/CSS：`npm --prefix pilot-web/frontend run check` 通过；存在原有大于 500 kB bundle 提示。
- 实机范围：macOS + JDK 25；真实 Chromium 本机浏览器集成、真实 OOXML 生成/读回/PNG 渲染。Windows 路径、安装命令和应用目录分支为模拟测试；Linux/Windows 桌面、Microsoft Office、真实账户投递、真实 PyPI 安装尚未在本轮实机验证。
- CI 已将新增 Node 回归纳入原有 Windows/macOS/Linux 矩阵；本轮没有触发远程 CI，不能宣称三平台实机全通过。
- 13 项跳过包括 7 项容器隔离、1 项受平台条件限制的命令、3 项项目构建集成、2 项真实模型评估；没有用离线测试代替真实模型成功率。

## 仍影响“完美完成”的因素与调整优先级

1. **验收范围仍可能由模型缩小。** 本轮修复原文丢失和显式未完误判。下一步应从用户原文建立逐项需求与外部验收映射，逐项链接代码/文件/观察，特别防止模型把实施任务改成写文档。
2. **真实网站与桌面软件缺少语义适配。** 通用点击不能稳定识别“保存草稿”和“提交申请”；目前用逐动作确认控制边界，交互成本较高。后续优先做少量高频网站/应用适配器，用明确对象、字段、提交回执和恢复点验收，不承诺任意 GUI。
3. **上下文与成本还需要实测校准。** 当前保护和预算可避免已知破坏，但精确 token、图片成本、缓存读写价格依赖服务端。下一步固定任务、模型、工具版本做配对评估，再决定异步压缩或缓存策略；不要先凭估算宣称节费。
4. **上游服务故障与长任务恢复。** 原会话出现 503/504 与重试；这无法靠加提示词消除。保留已有有界重试和检查点，在真实评估中单独统计服务故障、工具失败、任务遗漏，避免把它们混成“模型不够聪明”。
5. **并行开发会污染验证证据。** 多会话共用同一工作树时，构建可能看到另一会话的半成品。后续有并行实施需求应分 worktree，汇总后只在最终合并树验证；某个历史提交或另一会话的通过记录不能替代当前结果。

建议下一阶段先做三条真实任务的固定验收：带来源的报告、含公式表格与演示稿、指定招聘站点的筛选和草稿到确认前流程；逐次记录需求覆盖率、真实产物通过率、人工介入次数、失败恢复率和实际费用。通过这些结果扩展能力，比继续单纯增加工具数更能改善交付质量。


## 14:01 单模块启动失败的补充修复

用户反馈 server 测试 85 项错误。报告的第一层底层异常为 `NoClassDefFoundError: org/apache/poi/ss/usermodel/FormulaEvaluator`，发生在 `toolRegistry` 创建时。单模块构建直接编译了最新 core 源码，但使用本地仓库旧 core POM 解析传递依赖，运行 classpath 没有 POI。此前联合构建通过没有覆盖这个路径，因此此前的验证范围不足。

修复：父 POM 统一管理 POI 5.5.1，core 和 server 各自声明依赖；不通过更新本机缓存掩盖问题，不引入嵌套 Maven。

复验：保留缺少 POI 的旧 core 缓存，执行 `bash mvnw -o -f pilot-server/pom.xml clean verify`，653 项测试中 648 项通过、5 项跳过，失败/错误均为 0；Spotless、Checkstyle 和打包通过。测试 classpath 同时包含旧 core JAR 和新声明的 POI，`BackgroundCommandRunTest` 通过。日志：`/tmp/pilot-standalone-startup-fix.log`。

额外在隔离临时目录、随机本机端口启动新 Jar，Spring 启动成功；创建测试会话后，capabilities API 返回 HTTP 200，确认 office / prepare_runtime / local_resources 注册。测试进程随后关闭，无真实模型调用，不影响用户数据或现有服务。此处实机验证为 macOS；修复仅涉及标准 Maven 依赖管理，没有平台特有脚本。


## 提交前审查与复验

按用户要求审查全部 diff，补充修复工具空结果的状态分类：归档成功或失败的恢复提示都属于元数据，不能把空输出变成可用成功证据；新增回归覆盖两条路径。提交范围包括三个会话已有改动、本轮补齐，以及单模块 POI 依赖修复。

提交前再次执行 `bash mvnw -o -pl pilot-server -am spotless:apply verify`，BUILD SUCCESS。核心 598 项（590 通过、8 跳过），服务器 653 项（648 通过、5 跳过），合计 1,238 项通过、13 项跳过，失败和错误均为 0；日志为 `/tmp/pilot-precommit-verify.log`。此前单模块的干净构建和隔离 Jar 实际启动验证同样通过。
