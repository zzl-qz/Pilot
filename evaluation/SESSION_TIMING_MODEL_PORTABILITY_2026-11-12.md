# 会话计时恢复与模型配置通用性验证

日期：2026-11-12。基于 `ca26511`，思考计时修复提交为 `567cc4b`。机器可读结果见 [validation.json](results/session-timing-model-portability-2026-11-12/validation.json)。

## 已修复

### 重新进入会话后思考计时归零

原来临时快照不包含思考起止时间，前端恢复文本时使用 `Date.now()` 创建起点；正文后的下一段思考也未恢复活动状态。

- 服务端快照包含思考起止时间，增量合并采用首段到达时间，不采用刷新页面触发的发送时间。
- 同一运行的重试和正文分段入库保留原起点；首段正文固定终点，后续思考恢复活动计时；新运行、其他会话和删除不会继承该时钟。
- 前端从快照恢复活动/结束状态，支持 ISO 和数字事件时间戳。旧服务端缺少计时信息时只显示状态，不伪造从进入页面开始的时长。
- 此处计时表示本轮思考块从首次开始到当前/结束的经过时间，不代表模型纯计算时间。临时快照仍只在进程存活期间有效，未增加完整历史推理持久化。

### 模型等待策略按端点与模型隔离

审查近期修改：终态确认、响应大小限制、取消、测试证据和只读任务保护是通用逻辑，未发现以 Qwen 或量化模型名称为条件的执行分支。之前根据本地实测提高的默认时限则确实影响所有模型。

- 新增端点与模型专属的总时限和无数据等待时限，沿用原有配置隔离键，不按品牌或模型能力猜测时限。
- 保留服务端默认 900/600 秒及环境变量兼容。留空继承默认；无数据等待 0 表示跟随总时限，实际值不超过总时限。长上限不会让已完成的模型响应额外等待。
- 保存后下次请求使用新配置，已取得配置快照的请求保持原时限。切换模型或端点不串用专属设置，切回恢复原值。
- 界面显示已保存的有效时限，超时反馈引导调整当前模型设置。前后端校验整数与范围；接口测试发现 JSON 小数会被默认解析器截断，现已对这两个字段严格拒绝。
- Chat Completions、Responses、Anthropic Messages 均走相同配置策略，保留协议各自的终态与工具参数完整性校验。

OpenAI 的复杂推理也可能运行数分钟，所以不能按“云端/强模型”自动推断较短时限。参考 [官方后台任务说明](https://developers.openai.com/api/docs/guides/background)。Chat 流式终态及末尾独立用量块按 [官方协议](https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events) 保留兼容。

## 验证结果

| 环境 | 范围 | 结果 |
| --- | --- | --- |
| macOS ARM64 / Java 25 | core 全量单元测试 | 731 项：722 通过、9 条件跳过，0 失败 |
| macOS ARM64 / Java 25 | 模型设置、客户端热更新、能力发现、设置 HTTP 接口 | 40 项全部通过 |
| macOS / Node 22 | 前端全量单元测试、类型检查、生产构建 | 426 项通过；类型和构建通过 |
| macOS / Chrome、Electron | 真实 SSE、刷新计时、再次思考、后台兜底、断线续传 | 两端通过 |
| macOS / Chrome、Electron | 配置保存、模型和端点切换、清空恢复、失败、整数校验、后台刷新保留草稿 | 两端通过；1440/768/390 宽度无横向溢出并截图检查 |
| Linux ARM64 容器 / Java 25 | 模型、会话、完成门回归 | 156 项：155 通过、1 条件跳过，0 失败 |
| Linux ARM64 容器 / Java 25 | 同一组服务端配置与接口测试 | 40 项全部通过 |
| Linux ARM64 容器 / Node 22 | 前端单元测试、类型检查、生产构建 | 426 项通过；类型和构建通过 |
| Windows | 无实机环境 | 未实机验证 |

macOS 的 9 条跳过为 7 条需显式启用的容器隔离集成测试、1 条性能基准和 1 条包装脚本前置条件测试；Linux 选定范围跳过的是性能基准。改动使用 Java Instant/Duration、浏览器标准时间戳与 HTML 输入，不引入平台命令或路径假设。

主要复跑命令（仓库根目录，Java 25）：

```sh
mvn -pl pilot-core test
mvn -pl pilot-server -DskipFrontend=true -Dtest=SettingsApiTest,ModelRequestTimeoutTest,ModelRuntimeSettingsTest,ModelCapabilityDiscoveryTest,ModelClientProviderTest test
mvn -pl pilot-core,pilot-server spotless:check checkstyle:check
```

在 `pilot-web/frontend` 中运行：

```sh
npm run test:unit
npm run check
node scripts/event-stream-smoke.mjs
node scripts/request-usage-smoke.mjs
```

Linux 使用完整源代码快照，排除了用户原有的未提交 Controller 修改。初次容器执行误用了 macOS 原生依赖，并使用了无法覆盖项目配置的前端跳过参数；改用容器内 `npm ci` 和项目支持的 `-DskipFrontend=true` 后验证通过。这些初始失败不计为产品测试通过。

本轮未调用真实 OpenAI/Anthropic 服务，也未重跑上一轮真实本地模型任务矩阵；协议夹具和配置回归不能证明任意模型的任务成功率。仍运行的旧后端进程未被中断，使用修复需要重新启动后端并刷新客户端。
