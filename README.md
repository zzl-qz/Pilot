# Pilot

Pilot 是一个以 Java 构建的本地 Coding Agent 原型。用户通过 Web 界面提交开发任务，服务端调用兼容 OpenAI Chat Completions 的模型，并在配置的工作区内读取代码、搜索文本、执行受控工具操作和运行验证。会话、工具调用、审批与运行状态通过事件流呈现。

当前项目适合本地开发和评估，不是可无监督修改生产代码的成熟产品。模型质量取决于所配置的服务；权限策略也不是操作系统级沙箱。使用前请阅读下方的安全说明，并优先在可丢弃的工作区中试用。

## 功能概览

- 多轮会话、运行历史、任务继续/重新生成与排队消息。
- 通过 SSE 展示运行进度、工具调用和审批事件。
- 本地工作区文件浏览、读取、搜索、修改及变更 diff。
- 可配置的权限模式、命令审批、运行取消和变更回滚。
- 收尾门：改了代码却没跑验证、有改动但没核对目标、只读调研调用太少就下结论时，自动推回一轮（`-Dpilot.completion.checks=false` 可关闭）。
- 项目约定：工作区根目录的 `PILOT.md` / `AGENTS.md` / `CLAUDE.md` 会自动注入系统提示词（取第一个存在的）。
- 仓库快照：每轮注入顶层结构、git 分支与未提交改动、识别到的构建方式，模型不用从零摸索。
- OpenAI Chat Completions 兼容模型端点；无模型配置时可使用 Mock 模式体验界面流程。
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

要求：JDK 25。仓库提供 Maven Wrapper，无需单独安装 Maven；Windows 使用 `mvnw.cmd`，macOS/Linux 使用 `./mvnw`。SQLite JDBC 通过本机库工作，运行环境需允许其加载本地库。

在仓库根目录构建并运行测试：

```sh
./mvnw test
```

启动服务（Windows PowerShell 使用 `./mvnw.cmd` 替换命令开头）：

```sh
./mvnw -pl pilot-server -am spring-boot:run
```

默认 profile 为 `local`，权限默认为 `READ_ONLY`。默认 Web/API 地址为 `http://localhost:9999`。服务启动后可在浏览器打开该地址。无模型密钥时可使用 Mock 模式体验界面；真实模型运行可用环境变量配置：

```sh
PILOT_MODEL_API_KEY=你的密钥 \
PILOT_MODEL_ENDPOINT=https://api.openai.com/v1/chat/completions \
PILOT_MODEL_MODEL=gpt-4o-mini \
PILOT_WORKSPACE=/绝对/工作区路径 \
./mvnw -pl pilot-server -am spring-boot:run
```

Windows PowerShell 中请用 `$env:PILOT_MODEL_API_KEY='…'` 等方式设置环境变量。不要把密钥提交到仓库；也可在被忽略的 `pilot-server/src/main/resources/application-local.yaml` 配置。请先确认工作区路径正确，并从 READ_ONLY 开始。

## 权限说明

- `READ_ONLY`：只读工具放行，写文件和命令拒绝（默认）。
- `ASK`：只读工具放行；每次写入或命令请求用户审批，拒绝/超时不得执行。
- `AUTO_EDIT`：写文件直接执行，命令仍需审批。
- `AUTO`：注册工具直接执行。仅在可丢弃的隔离工作区使用。

权限不是操作系统沙箱。工具路径限制和命令白名单不能替代容器/账户隔离；使用真实模型时，审查模型提出的命令、diff 和审批请求。不要把敏感文件放入可访问工作区。

## 模块说明

| 模块 | 职责 |
| --- | --- |
| `pilot-core` | Agent 循环、模型协议、工具注册与执行、权限判断、上下文管理和 trace 契约。 |
| `pilot-server` | Spring Boot 服务端，提供会话、事件、审批、工作区及设置 API，并装配运行时。 |
| `pilot-web` | 静态 Web 客户端资源；由服务端一并提供，不需要单独启动前端开发服务器。 |

## 数据与配置

默认配置位于 `pilot-server/src/main/resources/application.yaml`，本地 profile 覆盖配置可放在 Git 忽略的 `pilot-server/src/main/resources/application-local.yaml`。也可通过环境变量配置模型和运行参数，例如 `PILOT_MODEL_API_KEY`、`PILOT_MODEL_ENDPOINT`、`PILOT_MODEL_MODEL`、`PILOT_WORKSPACE`、`PILOT_PERMISSION_MODE`。

上下文预算默认 120000，按「字符」和「token」两个口径取更满的一个：英文约 96k 字符、中文约 96k 字符（≈96k token）时触发压缩，压缩后回到预算的一半左右再继续。模型窗口够大时可以调高，例如 `PILOT_AGENT_CONTEXT_MAX_CHARS=300000` 或 `-Dpilot.context.max-chars=300000`。

提示词不写在 Java 代码里，正文位于 `pilot-core/src/main/resources/prompts/`：`system/` 下是系统提示词的四段（身份与边界、工作流、工具策略、结论格式），另有计划注入、压缩摘要、自动继续与子代理任务模板。模板变量写作 `{{name}}`，缺失变量会直接报错。调提示词时可用 `PILOT_PROMPTS_DIR`（或系统属性 `pilot.prompts.dir`）指向一个外部目录，同名文件优先于打包资源，只覆盖其中一部分也可以，无需重新打包。

运行数据默认写入仓库根目录下的 `.pilot/`，可通过 `PILOT_DATA_DIR` 修改位置。该目录可能包含会话数据库、工作区快照和 trace；请按本机敏感数据管理，不要将密钥或私人会话数据提交到版本库。

## API 概览

主要接口以 `/api` 为前缀：

- `GET/POST /api/sessions`：列出或创建会话。
- `POST /api/sessions/{id}/messages`：向会话发送任务；`GET /api/sessions/{id}/events`：订阅 SSE 事件。
- `GET /api/sessions/{id}/history`：读取历史；`GET /api/sessions/{id}/diff`：查看会话变更。
- `/api/approvals`：查看和处理待审批工具调用。
- `/api/settings`：查看或更新模型与权限设置。
- `/api/health`：服务健康状态。

具体请求结构以 `pilot-server` 控制器实现为准；该列表是入口索引，不替代接口契约文档。

## 测试与评估

```sh
./mvnw test
```

前端纯逻辑（diff 解析、模型设置请求体/错误归类）拆在 `pilot-web/src/main/resources/static/lib/`，用 Node 内置测试器覆盖，无需安装依赖：

```sh
cd pilot-web
node --test src/test/js
```

Sprint 6 可复现任务规格与任务矩阵、运行命令见 `evaluation/TASKS.md`，记录模板/当前结果见 `evaluation/REPORT.md`。任务包括代码理解、修复、测试、权限拒绝、循环检测和压缩等。脚本化单测验证的是组件行为，不等同于真实模型成功率；真实模型评估还需配置模型并人工记录结果。

### 浏览器调试产物

用 `playwright-cli` 做界面冒烟测试会在项目根写出 `.playwright-cli/`（页面快照 `page-*.yml`、控制台日志 `console-*.log`、trace 与录屏）。这些是每次运行重新生成的中间文件，用完即弃：

- 已加入 `.gitignore`，不会被 `git add` 波及；
- 会话结束用包装命令，`close` 时自动清理：`powershell -NoProfile -File scripts/pw.ps1 close`；
- 单独清理（幂等）：`powershell -NoProfile -File scripts/clean-browser-artifacts.ps1`；
- 仓库的 `.git/hooks/pre-commit`（本地钩子，不进版本库）会在提交前清理，并把误加进索引的产物撤下、中止这次提交。

## 已知限制

- 模型的质量、成功率和响应受模型、网络和提示词影响；单元测试不能证明真实端到端质量。
- token 数是按字符估算的（ASCII 4 字符 1 token，中文 1 字符 1 token），不是模型 tokenizer 的精确计数；压缩摘要可能丢失细节，需复读原文件核实。
- ASK 依赖运行中的审批服务；等待超时按拒绝处理。不要把审批 UI 当作安全沙箱。
- 运行时存储、文件写入、命令执行均有本地副作用；先备份并使用可丢弃工作区。
- 评估集当前是任务规格和自动化测试映射，不包含十份独立仓库快照；评估前应在干净 checkout 执行，并记录提交号。
