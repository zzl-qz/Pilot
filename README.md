# Pilot

Pilot 是一个本地工作区内运行的编码代理原型：通过兼容 OpenAI Chat Completions 的模型接口调用工具，提供只读/审批权限、事件时间线与会话式运行能力。项目目前适合开发和评估，不应视为可无监督修改生产代码的成熟产品。

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

要求：JDK 24、Maven 3.9+（或仓库自带 `mvnw`），SQLite JDBC 所需本机库环境。Windows 使用 `mvnw.cmd`，macOS/Linux 使用 `./mvnw`。

```sh
./mvnw test
./mvnw -pl pilot-server -am spring-boot:run
```

默认 profile 为 `local`，代理模式默认为 `mock`，权限默认为 `READ_ONLY`。默认 Web/API 地址为 `http://localhost:8080`。可用环境变量设置真实模型：

```sh
PILOT_AGENT_MODE=real \
PILOT_MODEL_API_KEY=你的密钥 \
PILOT_MODEL_ENDPOINT=https://api.openai.com/v1/chat/completions \
PILOT_MODEL_MODEL=gpt-4o-mini \
PILOT_WORKSPACE=/绝对/工作区路径 \
./mvnw -pl pilot-server -am spring-boot:run
```

Windows PowerShell 中请用 `$env:PILOT_MODEL_API_KEY='…'` 等方式设置环境变量。不要把密钥提交到仓库；也可在被忽略的 `pilot-server/src/main/resources/application-local.properties` 配置。请先确认工作区路径正确，并从 READ_ONLY 开始。

## 权限说明

- `READ_ONLY`：只读工具放行，写文件和命令拒绝（默认）。
- `ASK`：只读工具放行；每次写入或命令请求用户审批，拒绝/超时不得执行。
- `AUTO_EDIT`：写文件直接执行，命令仍需审批。
- `AUTO`：注册工具直接执行。仅在可丢弃的隔离工作区使用。

权限不是操作系统沙箱。工具路径限制和命令白名单不能替代容器/账户隔离；使用真实模型时，审查模型提出的命令、diff 和审批请求。不要把敏感文件放入可访问工作区。

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

## 已知限制

- 真实模型的质量、成功率和响应受模型/网络/提示词影响；mock 和单元测试不能证明真实端到端质量。
- token 预算采用字符估算，不是模型 tokenizer 的精确计数；压缩摘要可能丢失细节，需复读原文件核实。
- ASK 依赖运行中的审批服务；等待超时按拒绝处理。不要把审批 UI 当作安全沙箱。
- 运行时存储、文件写入、命令执行均有本地副作用；先备份并使用可丢弃工作区。
- 评估集当前是任务规格和自动化测试映射，不包含十份独立仓库快照；评估前应在干净 checkout 执行，并记录提交号。
