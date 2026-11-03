# Pilot

Pilot 是一个以 Java 构建的本地 Coding Agent 原型。用户通过 Web 界面提交开发任务，服务端调用兼容 OpenAI Chat Completions 的模型，并在配置的工作区内读取代码、搜索文本、执行受控工具操作和运行验证。会话、工具调用、审批与运行状态通过事件流呈现。

当前项目适合本地开发和评估，不是可无监督修改生产代码的成熟产品。模型质量取决于所配置的服务；权限策略也不是操作系统级沙箱。使用前请阅读下方的安全说明，并优先在可丢弃的工作区中试用。

## 功能概览

- 项目制导航：先建项目，再在项目下新建对话；一个项目可以绑定多个文件夹，对话可在其中切换。
- 空白对话在项目内唯一：同一项目最多一条「还没发过消息」的对话，再点新建会提示归档旧的或回到旧的；有历史的对话不限数量，可归档/恢复。
- 项目记录建项目时的 git 基线（`baseCommit`），改文件夹只影响之后新建的对话，老对话保留自己的文件视图。
- 多轮会话、运行历史、任务继续/重新生成与排队消息；运行中可分别选择排队下一件事、纠正当前任务或暂停后续跑。
- 运行中发送的消息等待当前任务完成后依次独立执行；失败、取消、暂停、步数触顶、计划未完成或验收未通过时保留队列，等待期间可编辑、删除。
- 持久保存原始目标、用户约束、验收条件与证据；续跑重新核对任务要求，普通任务的明确验收条件需要人工勾选并提供证据，定时任务由 Goal 独立核验后自动验收。
- 对话导航：左侧刻度标记用户消息，悬停预览、点击定位、键盘上下切换；支持消息书签与窄屏导航列表。书签按对话保存在当前浏览器或桌面客户端，可定位并重新加载旧消息。
- 定时任务：侧栏管理计划，输入框时钟按钮带入文字草稿；支持一次性到期执行、改期、暂停/恢复、取消、模型预约唤醒及执行记录。
- 通过 SSE 展示运行进度、工具调用和审批事件。
- 本地工作区文件浏览、读取、搜索、修改及变更 diff；同一文件的多处独立精确替换可在一次 `edit_file` 调用中原子提交。
- 同一轮多个独立的本地读取可并行执行，工具结果仍按模型调用顺序回填；写入、命令与需审批操作保持顺序执行。
- 后台命令任务：代理可以启动持续运行的构建或服务，在右侧「命令」面板查看状态、增量日志和退出码，并独立停止命令进程树。
- 可配置的权限模式、命令审批、运行取消和变更回滚。
- 消息检查点：消息旁「回退 / 分支」可选择只撤代码、只撤对话或一起撤，回到该消息发送前；支持另开对话分支并保留原聊天。
- 回退前展示文件清单并校验后续修改；发现手工修改、缺失快照、链接文件或跨工作区时阻止代码回退。Git 分支可在变更面板单独创建，未提交改动继续保留。
- 收尾门：默认关闭（与 Codex / OpenCode 一致，模型说完成就完成）；需要时用 `pilot.agent.completion-checks=true` 打开。
- 项目约定：先加载用户级 `~/.pilot/PILOT.md`、`AGENTS.md`、`CLAUDE.md`（优先级最低），再沿工作区祖先目录逐级加载同名文件，每层按此顺序取首份，标明来源并限制长度后注入系统提示词。
- 结构化记忆（克制版）：`MEMORY.md` 与约定是两条独立通道——沿工作区祖先目录逐级 + 用户级 `~/.pilot/MEMORY.md` 注入"项目记忆"段（独立 4k 字符预算，不挤占约定）；代理用 `remember` 工具沉淀候选条目到 `.pilot/memory-draft.md`（带日期一行一条），**候选区刻意不注入**，由用户审阅后整理进 `MEMORY.md` 才生效——模型不能把任意内容直接写进自己的记忆。
- 确定性钩子：`.pilot/settings.json` 的 `hooks` 支持 `PreToolUse` / `PostToolUse`，格式兼容 Claude Code；配置后编辑完自动跑格式化或 lint，结果直接贴在工具结果后面，`blockOnFailure` 可让前置钩子阻止执行。
- 权限规则：同一份 `.pilot/settings.json` 的 `permissions.allow` / `deny` / `ask` 支持 `Tool` 与 `Tool(specifier)` 两种写法（命令用 `mvn test:*` 前缀，文件用 `src/**` glob），判定顺序 deny → ask → allow，未命中才回落权限档位。
- 仓库快照：会话首次运行时注入顶层结构、git 分支与未提交改动、识别到的构建方式，模型不用从零摸索。
- 独立审查模式：输入以 `/review` 开头（可带范围说明）进入只读审查 run——工具表只剩读/列/搜/git 查看命令，固定审查协议（阻断/建议/风格分级、行内定位、拿不准必须标注「不确定」、末尾如实交代盲区），不提供任何写工具与子代理派发；权限语义与普通 run 一致。
- OpenAI Chat Completions 兼容模型端点；无模型配置时可使用 Mock 模式体验界面流程。
- 用量详情显示缓存命中率：累计命中缓存的输入 token 与总输入同口径，区分 OpenAI 风格（缓存已计入 `prompt_tokens`）与 Anthropic 风格（缓存单独列出，需加回总输入）两种字段口径；端点不返回缓存用量或走本地估算时显示「—」，不把「没观测」当成「没命中」。
- 本地 SQLite 会话数据、快照及 trace 存储。

## 架构

```text
pilot-web (静态 Web UI)
        │ HTTP / SSE
pilot-server (Spring Boot API、会话/事件存储、审批、运行装配)
        │
pilot-core (AgentLoop、模型客户端、上下文压缩、工具注册/执行、权限门、trace)
        │
本地工作区工具 (读取/搜索/写入/受限命令)
```

## 快速启动

要求：JDK 25。仓库提供 Maven Wrapper，无需单独安装 Maven。启动脚本会先构建并安装 `pilot-server` 及其依赖模块、运行测试，成功后再启动服务；构建或测试失败会停止，不会启动旧包。SQLite JDBC 所需的本机访问参数已包含在脚本中。

macOS/Linux：

```sh
bash start.sh
```

Windows PowerShell / 命令提示符：

```powershell
.\start.cmd
```

脚本会切换到自身所在的仓库根目录，也可以从其他目录通过完整路径运行，例如 `bash /path/to/pilot/start.sh`。传入参数会交给应用，例如 `bash start.sh --server.port=10001`（Windows 使用 `.\start.cmd --server.port=10001`）。构建前先检查 Java 版本：优先使用 `JAVA_HOME`，未设置时使用 PATH 中的 Java；版本为 25 及以上则保留。macOS 检测到版本过低或不可用时，会尝试已安装的 JDK 25，打印本次切换说明，不修改全局 Java 设置。Windows/Linux 遇到不合适的版本会在 Maven 启动前报错，请将 `JAVA_HOME` 或 PATH 指向 JDK 25 及以上版本。

默认 profile 为 `local`，权限默认为 `AUTO`（完全放行；可在界面切到 `AUTO_EDIT` / `ASK` / `READ_ONLY`）。默认 Web/API 地址为 `http://localhost:9999`。服务启动后可在浏览器打开该地址。无模型密钥时可使用 Mock 模式体验界面；真实模型运行可用环境变量配置：

```sh
PILOT_MODEL_API_KEY=你的密钥 \
PILOT_MODEL_ENDPOINT=https://api.openai.com/v1/chat/completions \
PILOT_MODEL_MODEL=gpt-4o-mini \
PILOT_WORKSPACE=/绝对/工作区路径 \
bash start.sh
```

Windows PowerShell 中请用 `$env:PILOT_MODEL_API_KEY='…'` 等方式设置环境变量。不要把密钥提交到仓库；也可在被忽略的 `pilot-server/src/main/resources/application-local.yaml` 配置。请先确认工作区路径正确；默认档位是 `AUTO`，若在不可丢弃的工作区里试用，建议先切到 `ASK` 或 `READ_ONLY`。

### 开发时构建与运行

只运行测试时，在仓库根目录执行 `bash mvnw test`；只测服务端也要带上依赖模块：`bash mvnw -pl pilot-server -am test`（Windows 使用 `.\mvnw.cmd`）。若需要 Spring Boot 开发运行方式，先在根目录安装整个服务端依赖链，再单独运行服务端：

```sh
bash mvnw -pl pilot-server -am install
bash mvnw -pl pilot-server spring-boot:run
```

只构建 `pilot-server` 时，同一次编译会带上 `pilot-core` 的源码，不依赖本机仓库里可能过期的 JAR，也不另起一次 Maven。IDEA 里运行 `PilotServerApplication` 即可。项目不使用 Spring Boot DevTools，改了 Java 后由你再点一次运行。若 9999 仍被旧进程占用，先停掉那个进程。

## 权限说明

- `AUTO`：注册工具直接执行（默认，与 Codex / OpenCode 一致）。
- `AUTO_EDIT`：写文件直接执行，命令仍需审批。
- `ASK`：只读工具放行；每次写入或命令请求用户审批，拒绝/超时不得执行。
- `READ_ONLY`：只读工具放行，写文件和命令拒绝（相当于 plan 模式）。

### `.pilot/settings.json`

工作区（或用户级 `~/.pilot/`）的这份配置可以进一步收窄权限、挂上确定性钩子，格式与 Claude Code 一致：

```json
{
  "permissions": {
    "deny":  ["run_command(rm -rf:*)", "read_file(.env)"],
    "ask":   ["edit_file(docs/*.md)"],
    "allow": ["run_command(mvn test:*)", "edit_file(src/**)"]
  },
  "hooks": {
    "PostToolUse": [
      { "matcher": "edit_file|create_file|apply_patch",
        "hooks": [ { "type": "command", "command": "mvn -q spotless:apply" } ] }
    ],
    "PreToolUse": [
      { "matcher": "run_command", "command": "git status --porcelain", "blockOnFailure": false }
    ]
  }
}
```

- 权限判定顺序为 deny → ask → allow，规则不命中时才回落到档位；命令规则 `mvn test:*` 表示前缀匹配，文件规则支持 `*` 与 `**`。
- `PostToolUse` 的输出会贴在工具结果后面交给模型（例如格式化后剩下多少问题）；`PreToolUse` 配了 `blockOnFailure` 时，命令退出码非 0 会阻止工具执行。
- 钩子命令来自配置文件而不是模型，超时默认 60 秒；配置解析失败等同于没有钩子，不会阻断运行。

权限不是操作系统沙箱。工具路径限制和命令白名单不能替代容器/账户隔离；使用真实模型时，审查模型提出的命令、diff 和审批请求。不要把敏感文件放入可访问工作区。

## 模块说明

| 模块 | 职责 |
| --- | --- |
| `pilot-core` | Agent 循环、模型协议、工具注册与执行、权限判断、上下文管理和 trace 契约。 |
| `pilot-server` | Spring Boot 服务端，提供会话、事件、审批、工作区及设置 API，并装配运行时。 |
| `pilot-web` | 静态 Web 客户端资源；由服务端一并提供，不需要单独启动前端开发服务器。 |

## 数据与配置

默认配置位于 `pilot-server/src/main/resources/application.yaml`，本地 profile 覆盖配置可放在 Git 忽略的 `pilot-server/src/main/resources/application-local.yaml`。也可通过环境变量配置模型和运行参数，例如 `PILOT_MODEL_API_KEY`、`PILOT_MODEL_ENDPOINT`、`PILOT_MODEL_MODEL`、`PILOT_WORKSPACE`、`PILOT_PERMISSION_MODE`。

上下文预算默认 120000，按「字符」和「token」两个口径取更满的一个：英文约 96k 字符、中文约 96k 字符（≈96k token）时触发摘要，以预算约 80% 为目标；摘要仍压不下去且超过 97% 时再硬裁剪旧组。模型窗口够大时可以调高，例如 `PILOT_AGENT_CONTEXT_MAX_CHARS=300000` 或 `-Dpilot.context.max-chars=300000`。

提示词不写在 Java 代码里，正文位于 `pilot-core/src/main/resources/prompts/`：`system/` 下是系统提示词的五段（身份与边界、工作流、工具策略、多智能体调度、结论格式），另有计划注入、压缩摘要、自动继续、子代理任务模板，以及子 Agent 的三段式身份/任务模板（`subagent/base.md`、`subagent/job.md`）。模板变量写作 `{{name}}`，缺失变量会直接报错。调提示词时可用 `PILOT_PROMPTS_DIR`（或系统属性 `pilot.prompts.dir`）指向一个外部目录，同名文件优先于打包资源，只覆盖其中一部分也可以，无需重新打包。

运行数据默认写入仓库根目录下的 `.pilot/`，可通过 `PILOT_DATA_DIR` 修改位置。该目录可能包含会话数据库、工作区快照和 trace；请按本机敏感数据管理，不要将密钥或私人会话数据提交到版本库。

项目制改造之前创建的会话没有 `project_id`，会显示在侧栏的「未分组」下，数据不会被删除；老库启动时会自动补上 `project_id` 列。

事件、快照和 trace 默认永久保留。设置 `PILOT_RETENTION_DAYS`（或 `pilot.retention.days`）为正数后，服务每天清理一次早于该天数的数据并收缩 WAL；会话本身不会被删除。写文件前的快照对单个文件有 8MB 上限，超过的文件不备份，回滚结果里会把它列为失败而不是静默删除。备份目录与校验证据可以单独设置更短的期限：`PILOT_RETENTION_BACKUPS_DAYS`、`PILOT_RETENTION_VALIDATION_DAYS`。

执行隔离与远程访问是两组独立开关：

| 配置 | 默认 | 作用 |
| --- | --- | --- |
| `PILOT_EXECUTION_ISOLATION` | `local` | `container` 时校验命令在容器内执行（`--network=none`、只读根、cap-drop、内存/CPU/进程上限）；容器不可用会显式降级并在 `/api/maintenance/status` 报告原因 |
| `PILOT_EXECUTION_IMAGE` / `PILOT_EXECUTION_RUNTIME` | temurin 25 / 自动探测 | 容器镜像与运行时（docker/podman） |
| `PILOT_SECURITY_MODE` | `local` | `token` 允许远程访问，但必须带令牌 |
| `PILOT_SECURITY_TOKEN` | 空 | 访问令牌；`token` 模式为空会拒绝远程请求 |
| `PILOT_SECURITY_ALLOWED_ORIGINS` | 空 | 允许的浏览器来源，逗号分隔；默认只允许同源 |
| `PILOT_SECURITY_RATE_LIMIT` | 600 | 单 IP 每分钟请求上限，`0` 表示关闭 |

令牌可通过 `Authorization: Bearer`、`X-Pilot-Token` 或 SSE 的 `?token=` 传入。浏览器首次用 `http://<host>:8787/?token=<令牌>` 打开后，服务会写入 HttpOnly、SameSite=Strict 的 Cookie，页面内的 `fetch` 与 `EventSource` 随后自动携带，不必改前端代码。被拒绝的请求写入 `.pilot/audit/access.jsonl`，不记录令牌本身。

这只是单令牌边界：没有用户、角色与会话级隔离，适合可信局域网的单人使用。要开放到公网，应在其前面加一层真正的认证代理，并自行处理 TLS。

数据备份用 `POST /api/maintenance/backup`：数据库走 SQLite `VACUUM INTO` 得到事务一致快照，清单、校验证据、命令日志一并复制并写 `manifest.json`（含每个文件的 sha256）。恢复只能停服务后整体覆盖数据目录；不要在服务运行时恢复。

## API 概览

主要接口以 `/api` 为前缀：

- `GET/POST /api/projects`：列出或创建项目；`PATCH /api/projects/{id}` 改名，`PUT /api/projects/{id}/folders` 替换绑定文件夹，`DELETE /api/projects/{id}` 删除（默认连同对话）。
- `GET/POST /api/sessions`：列出或创建会话（`?projectId=` 过滤，`projectId=none` 看未分组）。项目下已有空白对话时返回 409；带 `replaceBlank=true` 表示已确认归档旧空白对话。
- `PATCH /api/sessions/{id}/workspace`：在项目绑定的文件夹之间切换该对话的工作区。
- `PATCH /api/sessions/{id}/archive`：归档或恢复对话；归档只影响列表，历史与事件都保留。
- `POST /api/sessions/{id}/messages`：向会话发送任务；`GET /api/sessions/{id}/events`：订阅 SSE 事件。
- `POST /api/sessions/{id}/steer`：纠正当前运行；`POST /api/runs/{runId}/pause`：暂停当前任务；`POST /api/sessions/{id}/continue`：沿用任务目标与要求继续。
- `GET/PUT /api/sessions/{id}/task`：查看当前任务，或在空闲时保存约束、验收条件与证据；原始目标不可通过此接口修改。
- `GET /api/sessions/{id}/commands`：列出命令任务；`GET /api/sessions/{id}/commands/{taskId}`：查看状态；`GET .../{taskId}/logs?offset=0&limit=16000`：增量读取日志；`POST .../{taskId}/stop`：停止命令及其子进程。
- `GET /api/sessions/{id}/history`：读取历史（`limit` + `before` 游标分页，长会话只取尾部一页）；`GET /api/sessions/{id}/diff`：查看会话变更。
- `GET /api/sessions/{id}/context`：查看持久模型记忆的估算占用；`POST /api/sessions/{id}/context/compact`：空闲时主动压缩早期模型记忆，聊天与审计事件保留不动。
- `GET /api/metrics`：进程内运行指标（运行数、状态分布、失败码、事件类型计数、平均耗时）。
- `GET /api/usage?days=N`：用量聚合（默认 30 天，夹在 1-90）：按日 token/请求数、会话 Top 10 与合计；`run.usage` 是 run 内累计快照，聚合时每个 run 只取末值，主 Agent 与已归集的子 Agent（spawn / dispatch）都计入。
- `/api/approvals`：查看和处理待审批工具调用。
- `/api/settings`：查看或更新模型与权限设置。
- `/api/health`：服务健康状态。
- `GET /api/sessions/{id}/multi`：多智能体任务看板、沙箱改动、待仲裁事项与产物清单摘要。
- `POST /api/sessions/{id}/multi/sandboxes/{sandboxId}/accept|reject`：接受或拒绝某个子 Agent 的改动。
- `POST /api/sessions/{id}/multi/arbitrations/{arbitrationId}/resolve`：记录人工仲裁结果。
- `GET /api/sessions/{id}/multi/sandboxes/{sandboxId}/manifest`：当前产物清单、与上一版的差异、校验有效性原因；`.../manifests` 返回版本历史。
- `GET /api/sessions/{id}/diagnostics`：任务诊断（停在哪一步、上次成功步骤、下一动作、重试状态、耗时与用量、命令、校验覆盖、存储健康、启动恢复）。文本经过脱敏。
- `GET /api/sessions/{id}/validation`：完成声明对应的校验证据是否仍覆盖当前产物；`GET /api/recovery`：最近一次启动接管的运行清单。
- `GET /api/maintenance/status`、`POST /api/maintenance/backup`、`POST /api/maintenance/purge`：存储、备份与保留策略。

具体请求结构以 `pilot-server` 控制器实现为准；该列表是入口索引，不替代接口契约文档。

## 定时任务

侧栏「定时任务」可填写目标、验收条件、约束、技能及执行时间，使用当前工作区，在独立对话中执行。支持一次性计划；模型可通过 `wait_until` 保存状态并预约下一次检查，无需占住线程等待。完成和需要处理时显示站内提示。

计划复用 SQLite、RunStarter、AgentRunService、任务检查点和 Goal 核验。设计参考 [OpenClaw cron](https://docs.openclaw.ai/automation/cron-jobs/how-it-works) 的持久计划与独立上下文、[管理界面](https://docs.openclaw.ai/automation/cron-jobs/managing-jobs)的状态和执行历史，以及 [LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence) 的恢复边界；未另建模型执行循环。

定时任务默认授予本任务全部已提供工具的执行权限，不改全局设置；子任务继承授权但保留 scope、工作区和沙箱限制。创建时可取消自主执行，沿用原有审批规则。所有定时任务都必须先读取开源仓库，通过 `record_research` 记录可追溯的来源、复用决定和许可证检查说明，之后才能写入、运行命令或派发子代理。仅有搜索结果不能解除门禁。目标、约束与验收条件通过独立核验后才能完成。

后端需要保持运行；休眠、关机或后端关闭期间不执行，恢复后补投到期任务。中断自动恢复最多 3 次（30/60/120 秒），首次启动后的总时限 24 小时，最多 100 次唤醒，并保留原有模型预算。人工暂停不自动恢复，未知投递和预算耗尽进入「需要处理」。目前只支持单后端实例与文字目标/工作区文件，不支持周期 cron、云端代跑或附件投递，也不承诺外部副作用全局 exactly-once。

接口为 `GET/POST /api/schedules` 与 `POST /api/schedules/{id}/control`；控制动作包括 `pause`、`resume`、`cancel`、`reschedule`。时间使用 UTC Instant 存储，界面按 IANA 时区显示。

验证：相关后端回归 165 项通过；`npm run test:schedules -- --production` 在 macOS 的 Chromium/Electron 验证创建、改期、暂停、恢复、取消、结果入口、草稿保留、错误反馈和三种屏幕宽度。时钟、模型与 UI API 使用测试替身；Windows/Linux 尚未实机验证，未做真实模型长时间无人值守验证。

## 任务控制

任务控制参考 [dsh 的 followup / steer](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/agent/README.md) 对下一轮输入与当前步骤纠正的区分，以及 [Codex 的 Plan / Goal](https://developers.openai.com/blog/mastering-codex-remote-for-engineering) 对执行路径与持久目标的区分。Pilot 的计划记录执行步骤，任务卡保存原始目标、约束和验收条件，两者在续跑时一并提供给模型。

运行中，输入框的 Enter 和发送按钮都表示「排队下一件事」。例如当前在修复登录问题，发送「接下来补充注册页测试」会进入队列，等当前任务完成后再独立启动。需要调整正在做的事情时，在同一输入框写下纠正内容，再点「立即纠正当前」；它会追加到当前任务的纠正记录，不消费或替换排队消息。纠正绑定当前运行 ID，如果发送时任务已经交接到下一项，服务端会拒绝该请求，输入内容仍保留。

纠正和暂停都在安全步骤边界生效。已经开始的工具操作会先结束，随后才应用纠正或停止继续执行；点击「暂停当前」不会回滚已完成的文件修改。暂停保留任务要求、模型记忆、计划与排队消息，点击「继续当前任务」接着原任务执行。关闭页面或服务重启后，持久记录仍保留；未正常结束的运行显示为已中断，可以续跑。

开始新任务前可展开「新任务要求」，按行填写用户约束和验收条件。执行后「当前任务」卡会显示不可覆盖的原始目标、约束、验收条件与证据、纠正记录；运行时只读，暂停或结束后可编辑并保存。任务记录独立于压缩后的对话记忆保存，避免续跑仅依赖聊天摘要。

模型结束运行不代表已通过人工验收。设有明确验收条件时，尚未勾选的条件会让任务停在「待验收」，后续队列继续保留。核对每条条件并填写证据（例如测试命令与结果、复现步骤）后勾选，保存全部验收结果才会标记「已验收」，并自动启动下一条排队任务。纠正方向会取消已有验收勾选，保留旧证据供重新核对；未设置验收条件的任务仍使用现有计划与收尾检查判断完成。

主要请求示例：

```jsonc
// POST /api/sessions/{id}/messages：空闲时开始带要求的新任务
{"content":"修复登录失败提示","task":{"constraints":["保持登录 API 兼容"],"acceptance":[{"text":"错误密码显示明确提示","checked":false,"evidence":""}]}}

// POST /api/sessions/{id}/steer：运行中纠正当前任务
{"content":"只修改登录页，不调整注册流程","runId":"run_当前运行ID"}

// PUT /api/sessions/{id}/task：空闲时保存要求与人工验收结果
{"constraints":["保持登录 API 兼容"],"acceptance":[{"text":"错误密码显示明确提示","checked":true,"evidence":"手工验证：错误密码显示明确提示，刷新后可重新登录"}]}
```

运行中的排队消息只接受普通任务文本；结构化要求通过空闲时的新任务请求提交，也可以暂停当前任务后在任务卡中编辑。`GET /task` 在尚未创建任务记录时返回 `null`。

## 独立审查模式（/review）

消息以 `/review` 开头即进入审查 run（命令面板的「独立审查」就是填入这个前缀，直接手输也生效）。与普通对话的区别：

- **工具表被裁剪为只读**：读文件/列目录/搜索/git 查看命令保留，写文件、子代理派发、多智能体调度在本 run 里「不存在」而不是「被拒绝」——模型不会反复尝试越权，也不会在审查中顺手改代码。权限语义不变：git 只读子命令按档位放行，写命令照旧走审批。
- **固定审查协议**（外置在 `prompts/review/protocol.md`，热覆盖可调）：先 git status/diff 实查改动，逐文件补上下文再下结论；按「阻断 / 建议 / 风格」三级输出，每条带文件:行定位、依据与建议；拿不准必须标注「（不确定）」，末尾如实交代审查盲区——不允许把臆测写成结论。
- **范围自定义**：`/review` 默认审未提交改动；`/review 对比 main 分支`、`/review 重点看登录模块` 会作为范围说明注入协议。
- 审查协议不改变收尾语义：结论文本照常进对话流与历史，可继续追问。

## 自适应执行与 Goal

新任务直接进入普通执行，不先消耗一次模型请求做模式分类。普通执行与 Codex / OpenCode 一致：**模型说完成就完成**，不做二票否决——没有收尾自审、没有“必须跑通验证才能交付”、也不会因为“没有新证据”判成无进展而停下。只有主 Agent 发现任务需要跨轮追踪动态结果、形成调查→修复→复验闭环，或用户明确要求持续推进到结果时，才调用 `promote_goal` 保存完成条件并启用独立核验；Goal 的继续必须同时给出具体缺口、唯一下一动作和预期观察，并由新文件状态、有效验证或后台终态驱动；等待、需要输入、未验证、无进展、核验失败和预算耗尽分别保留状态。

计划只是给模型和用户的进度提示：未完成项会如实报进 `run.completed`，但不拦住收尾，也不再自动催模型继续。是否运行构建、测试以及是否复跑，都由模型自己决定。

执行预算只作高位事故熔断，不在半额度强制改变策略，也没有“读取/搜索 32 次后禁止调查”的业务门。自上次修改或成功验证后累计 16 次读取/搜索时，运行时会提醒模型转入具体实现或定向验证，不禁止必要调查。默认 DIRECT 每段最多 1000 次主执行请求、5000 万输入 token、200 万输出 token 和 8 小时模型活跃时间；Goal 的请求保险丝跟随 `pilot.agent.max-steps`（默认 1000），token 上限相同，活跃时间为 8 小时。触顶后保存进度与证据，用户可继续开启新预算段，历史用量与证据不会清零。核验器另预留最多 2 次请求；任何非取消停止另有一次禁用工具的阶段收尾。

重复调用只注入提醒，不再中断运行；是否换理由模型自己判断。仍在运行的后台状态/日志查询使用更宽的有界观察窗口，长期完全不变才暂停轮询。上下文接近预算时会先占位符化与截断老工具输出，再折叠成摘要，压不动就本地裁剪，不会因为压缩让任务失败。同步 `spawn_agent` 使用冻结且去递归/Goal 控制的工具快照；单次派生最多 8 个工作步和 1 个收尾请求（合计最多 9 次模型请求），同一 run 共享最多 4 次派生、24 次子模型请求的资源池，子请求及 token 用量同时计入父任务的事故熔断。子运行会把改动路径和检查结果结构化交回父任务。完整协议见 [Goal 执行说明](evaluation/GOALS.md)。

## 浏览器验证与 MCP

`browser` 工具在隔离的 Chromium 里打开本机页面（只允许 localhost / 127.0.0.1 / [::1]），支持 DOM 快照、点击、输入、选择、文件上传、截图、控制台与网络记录，用于让代理验证自己改的前端。运行时是固定版本 `playwright-core@1.64.1` 的 Node sidecar，首次启动时自动安装到 `.pilot/browser`；浏览器**只用**收进 `.pilot/browser/browsers` 的内置 Chromium（版本配套、行为跨机器一致，缺失时自动下载约 400MB，`--no-shell` 跳过用不上的 headless-shell），删 `.pilot` 即全量清理。不会去用用户本机安装的 Chrome / Edge：确实需要救急时才显式设 `PILOT_BROWSER_PREFER_BUNDLED=false`，或用 `PILOT_BROWSER_PATH` 指到一个可执行文件。默认**无头运行**：不弹独立窗口，页面只镜像到右侧面板里（`PILOT_BROWSER_HEADLESS=false` 恢复有头窗口，用于调试或手动接管）；无桌面环境自动降级。空闲回收：无头 10 分钟、有头默认不回收（`pilot.browser.idle-shutdown-seconds` 可改），面板正在显示某一页时不回收。可用 `pilot.browser.enabled=false` 关闭。握手没通过时模型看不到这个工具，原因保留在工具不可用信息里；本机装好浏览器后约一分钟内自动重测。截图保存在数据目录的 `artifacts/` 下。

右侧审阅面板的「浏览器」tab 就是一个**内置浏览器**，而且默认不弹独立窗口：浏览器在无头模式跑，页面只镜像到面板里（`PILOT_BROWSER_HEADLESS=false` 才回到有头窗口，用于调试）。顶部有地址栏与后退 / 前进 / 刷新（加载中变成停止），输入 `localhost:5173` 这类本机地址即可打开页面（简写自动补 `http://`，仍只允许回环地址）；画面是 CDP screencast 的实时帧（页面变化才推帧、静态页面零流量），面板里的点击、滚轮与键盘按页面视口换算后转发过去，和代理操作的是同一个页面。另外三个面板内能力：**选择元素**（点画面里的元素，把它描述成引用直接进输入框）、**页面控制台**（读 console 记录）、**暂停智能体操作**（代理的交互与导航被温和拦截，snapshot 等观察动作仍可用）。帧走独立的瞬态 SSE（不落库、断线重连补发缓存帧），切走 tab 或折叠面板即停止推帧；面板正在显示某一页时浏览器不会被空闲回收。

MCP 服务器在工作区的 `.mcp.json`（格式同 Claude Code：`{"mcpServers": {"名字": {"command": "npx", "args": [...], "env": {...}}}}`）或数据目录的 `mcp.json` 里配置，目前支持 stdio 传输。模型每次请求只携带 `mcp_tools` 与 `mcp_call` 两个定义，具体工具按需查询；启动服务器等于执行配置里的命令，所以需要审批，声明了 `readOnlyHint` 的工具调用免审批。

## 后台命令

代理调用 `run_command` 时可指定 `background: true`，命令启动后返回任务 ID，代理可继续其他工作。后台命令仍遵守现有权限与审批流程；界面不提供绕过审批的任意命令启动表单。代理可以用 `command_status` 查询状态、`command_logs` 增量读取输出、`stop_command` 停止任务。

右侧「命令」面板列出当前会话的命令、任务 ID、前台或后台模式、状态、耗时与退出码。点击任务查看工作目录、进程 ID、开始与结束时间及纯文本日志；面板可见时约每秒更新，切换会话、收起面板或隐藏页面后停止轮询。查看旧日志时不会被新输出强制拉回底部，可点「跳到最新」回到末尾。界面只保留最近约 20 万字符并提示省略量，服务端日志另行保存。

「停止命令」会终止命令及已跟踪的子进程。请直接运行 `npm run dev` 这类前台命令，并由 Pilot 托管到后台，不要再使用自我守护化或自行脱离的启动方式；在被跟踪之前就脱离父进程的程序无法可靠清理。暂停对话不会停止后台命令；对话结束后仍可打开命令面板查看或停止它们。后台命令默认不设超时，显式指定的 `timeoutSeconds` 最长为 600 秒；前台默认 120 秒、最长 600 秒。单任务输出达到 10 MiB 上限时会停止并标记 `OUTPUT_LIMIT`，避免持续输出耗尽存储。

服务正常关闭或删除会话时会停止相关命令进程树。异常退出后的重启会把未结束任务标为 `INTERRUPTED`，保留日志，不会根据旧 PID 重新接管进程。服务端保留最近 128 个命令任务；已被清理的任务返回 404。增量日志的 `offset` / `nextOffset` 是 Unicode 码点游标，客户端应原样使用返回的 `nextOffset`，不能用 JavaScript 字符串长度计算下次位置。

## 消息检查点

参考 [Claude Code Checkpoints](https://code.claude.com/docs/en/checkpointing) 的三种恢复范围，每轮开始前保存模型对话记忆和计划，文件写工具第一次修改某个路径前保存原文件。消息旁点击「回退 / 分支」查看所选消息及后续轮次的文件清单：

- **只撤代码**：恢复这些轮次首次修改前的文件，删除它们新建的文件，保留对话。
- **只撤对话**：移除选中消息及后续事件，恢复当时的模型记忆和计划，文件保持当前状态，原消息回填输入框。
- **一起撤**：先完整校验代码恢复条件，再恢复代码和对话；冲突或缺失快照时不裁剪对话。
- **另开对话分支**：复制选中消息之前的历史、模型记忆和计划到同项目的新聊天，保留原聊天；分支共用当前工作区文件。若需要 Git 分支，在变更面板点「Git 分支」。

代码回退会检查最新文件指纹，以及同一文件在相邻修改轮次之间的指纹衔接。发现后续手工修改时列出冲突并阻止恢复，不提供强制覆盖。实际执行前会再次校验；符号链接、硬链接、目录占位和超过 8MB 的文件不能恢复。正在运行的会话不能回退，回退对话前需要先处理排队消息。历史检查点缺失或被保留策略清理后，相应选项不可用。

检查点覆盖当前会话写文件工具记录的路径；仅由命令、其他会话或独立子 Agent 修改且没有经过快照工具的文件不在恢复范围内。检查点用于本地会话恢复，长期版本历史继续使用 Git。

- `GET /api/sessions/{id}/checkpoints?sequence=消息序号`：预览范围、冲突和快照可用性。
- `POST /api/sessions/{id}/checkpoints/restore`：`{sequence, mode: "code" | "conversation" | "both", revision}`，返回 `applied` 与代码恢复报告。
- `POST /api/sessions/{id}/checkpoints/fork`：`{sequence, revision}`，返回新会话及待回填原消息。
- `POST /api/sessions/{id}/branch`：`{name}`，在当前工作区创建并切换 Git 分支。

## 多智能体调度

父 Agent 通过 `dispatch_agents` 把任务 DAG 交给调度器，自己只负责拆解、归集与核对证据：

- 星型拓扑：子 Agent 之间不通信，结论统一交回父 Agent；需要别的产出时由父 Agent 摘要转发。
- 权限硬隔离：每个子任务带文件白名单（`AgentScope`），路径校验在 `PathValidator` 里拦截，白名单外的文件在子 Agent 视图里不存在，列举/搜索也会被过滤。
- 细粒度授权：工具白名单支持 `read_*` 这类通配符；`commands` / `deny_commands` 按参数前缀限制 `run_command`（如 `mvn test`、`git status`），`*` 匹配单个参数、`**` 匹配剩余参数；只读任务即使拿到 `run_command` 也不能执行 `git add` / `git commit`。
- 变更沙箱：子 Agent 的 `edit_file` / `create_file` 只产出 unified diff 与目标内容，写入 `.pilot/multi/<session>/` 审计副本，真实工作区在用户点「接受变更」之前保持只读；接受时用内容指纹校验文件没有被别人改过。
- 合并预检：同一文件的重叠改动、两个 Agent 新建同一文件都会标记为冲突，冲突不自动合并。
- 硬校验：在 `git worktree` 出来的隔离副本上套用「工作区未提交改动 + 沙箱改动」，执行 `mvnw compile` / `mvnw test`（可用 `pilot.multi.verify-commands` 覆盖）。编译器与测试结果优先于任何 Agent 的判断。
- 人工仲裁：结论不一致、变更冲突、硬校验失败都会发出 `arbitration.required`，父 Agent 停止推进，由用户选择方案或放弃。

配置项在 `pilot.multi.*`：`enabled`、`max-parallel`、`max-attempts`（默认 3，即最多 2 次重试）、`max-steps`、`verify-timeout-seconds`、`verify-commands`。

## 自定义子代理

`.pilot/agents/<name>.md` 平铺定义角色卡（项目级随仓库走；用户级在数据目录 `agents/` 下，环境变量 `PILOT_AGENTS_DIR` 可改；同名项目级遮蔽用户级；每次 run 重扫描，改完即生效）：

```markdown
---
description: 数据库脚本审查员（必填，模型靠它判断何时引用）
tools: [read_*, search_*, run_command]   # 可省：省略时沿用子代理默认
files: [src/main/resources/db/**]       # 文件白名单
deny-commands: [git push]               # 命令黑名单
writable: true                          # 声明可提交沙箱改动（见下方语义）
---
（正文 = 角色提示词，注入子代理的系统提示词）
```

两种引用方式：

- `dispatch_agents` 任务加 `agent: <名字>`：工具/文件/命令白名单按定义装配，任务里显式声明的字段**优先**、缺省用定义补，不取并集；`writable` **完全由任务决定**（JSON 参数里 false 即显式只读，定义里的可写声明不能放大任务——预设不是越权通道）；角色提示词拼在任务目标前。
- `spawn_agent` 加 `agent` 参数：在只读探索语义上按定义裁剪工具名单并注入角色提示词（spawn 保持只读，可写角色请走 dispatch）。

引用不存在的名字会明确报错并列出可用代理，不静默退回普通任务；坏定义文件记入扫描问题列表，不拖垮整个目录。

## 测试与评估

```sh
./mvnw test
```

前端纯逻辑（diff 解析、模型设置请求体/错误归类）拆在 `pilot-web/src/main/resources/static/lib/`，用 Node 内置测试器覆盖，无需安装依赖：

```sh
cd pilot-web
npm test
```

Sprint 6 可复现任务规格与任务矩阵、运行命令见 `evaluation/TASKS.md`，记录模板/当前结果见 `evaluation/REPORT.md`。任务包括代码理解、修复、测试、权限拒绝、循环检测和压缩等。脚本化单测验证的是组件行为，不等同于真实模型成功率。

真实模型评估已有固定任务集与执行器：`evaluation/harness/tasks.json` 定义六个可复制工作区与外部验收命令，`evaluation/harness/runner.mjs` 驱动真实运行并写出 JSONL，`metrics.mjs` 聚合成功率、回归率、成本、耗时、返工与人工接管率，并支持与上次基线比较：

```sh
node evaluation/harness/runner.mjs --base http://127.0.0.1:8787 \
  --model <模型> --prompt-version <提示词版本> --tool-version <工具版本> \
  --fixtures evaluation/harness/fixtures --out evaluation/harness/runs/run1.jsonl
node --test evaluation/harness/metrics.test.mjs   # 指标聚合的单元测试
node evaluation/harness/runner.mjs --dry-run --out /tmp/dry.jsonl  # 只验证流程
```

只有验收命令真的执行为 0 才算成功；`--dry-run` 的数字只用于验证流程，不能当作能力结论。

窄屏回归用 `cd pilot-web && npm run check:viewports -- --base http://127.0.0.1:8787`：在 390/768/1440 三个视口检查横向溢出、输入框与发送按钮可见性、设置面板是否超出屏幕，并输出截图与 `report.json`。需要先 `npm install` 安装 playwright。

### 浏览器调试产物

用 `playwright-cli` 做界面冒烟测试会在项目根写出 `.playwright-cli/`（页面快照 `page-*.yml`、控制台日志 `console-*.log`、trace 与录屏）。这些是每次运行重新生成的中间文件，用完即弃：

- 已加入 `.gitignore`，不会被 `git add` 波及；
- 会话结束用包装命令，`close` 时自动清理：`powershell -NoProfile -File scripts/pw.ps1 close`；
- 单独清理（幂等）：`powershell -NoProfile -File scripts/clean-browser-artifacts.ps1`；
- 仓库的 `.git/hooks/pre-commit`（本地钩子，不进版本库）会在提交前清理，并把误加进索引的产物撤下、中止这次提交。

## 已知限制

- 模型的质量、成功率和响应受模型、网络和提示词影响；单元测试不能证明真实端到端质量。
- 模型端点返回 `usage` 时，任务输入/输出 Token 使用模型精确值；不返回时按字符保守估算（ASCII 4 字符 1 token，中文 1 字符 1 token），界面用 `≈` 明示。任务统计覆盖主 Agent 与子 Agent（spawn / dispatch 已归集），设置页「用量」面板按日与会话展示历史成本；上下文占用始终是本地估算。
- 主动压缩会额外调用一次模型生成交接摘要；压缩只替换模型记忆中的早期内容，不删除聊天和审计记录。摘要可能丢失细节，关键事实仍需复读原文件核实。
- ASK 依赖运行中的审批服务；等待超时按拒绝处理。不要把审批 UI 当作安全沙箱。
- 运行时存储、文件写入、命令执行均有本地副作用；先备份并使用可丢弃工作区。
- `run_command` 仍以宿主进程执行：容器隔离目前只覆盖多智能体的校验命令，因为后台命令状态、日志与停止都依赖进程归属。`ExecutionBackend` 已抽出，但把它接到完整命令生命周期还要处理路径映射、工具链镜像与停止语义。
- 检查点、租约与幂等账本解决的是「同一实例重启后安全接管」，不是多实例集群调度：租约存在 SQLite 里，多实例共享同一数据目录才有意义。
- 远程访问只有单一共享令牌，没有用户、角色与会话级隔离；开放到公网前应在前面加一层真正的认证代理。
- 评估集当前是任务规格和自动化测试映射，不包含十份独立仓库快照；评估前应在干净 checkout 执行，并记录提交号。`evaluation/harness/fixtures/` 需要按任务补充初始仓库后才能真正跑出成功率。

> AI生成
