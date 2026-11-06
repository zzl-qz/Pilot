# 钩子权限与本机请求边界修复

日期：2026-11-06。根据用户提供的两项报告检查并修复；此文只描述本次验证范围。

## 结论与行为变化

| 问题 | 确认结果 | 修复 |
| --- | --- | --- |
| 只读工具触发 shell 钩子写文件 | 成立。原 Hooks 直接 ProcessBuilder 启动 shell，不经过工具权限；PostToolUse 对只读工具同样运行 | 钩子统一走命令权限和审批，READ_ONLY 拒绝所有 shell 钩子；工作区配置每条命令需具体确认；用户级配置仍受命令权限约束 |
| 本机回环请求携带陌生 Origin/Host | 成立，且已用真实 Chromium 在隔离旧包上复现浏览器路径 | 回环连接同时校验 Host、Origin、Sec-Fetch-Site 和 Referer；拒绝陌生、畸形或重复的来源/Host。公开连接页与健康检查同样受本机边界约束 |

## 钩子执行

- `Hooks` 只解析带配置来源的命令，不再直接启动进程。缺少执行器上下文的调用返回拒绝，不隐式执行。
- 内部钩子工具使用 `run_command` 的权限规则，始终按可写命令处理；只读档位不执行，即使用户级配置看上去只是 `echo`。
- 工作区配置需要逐条动作确认，显示实际来源文件、工作目录、事件、触发工具和完整命令。普通 allow 或自动任务全工具授权不能代替这个信任确认。
- PreToolUse 在原工具已获准后执行，解决原先 ASK 获批路径跳过前置钩子的问题。钩子被拒、暂停或所需隔离不可用时阻止原工具；已执行的检查失败按 blockOnFailure 处理。
- PostToolUse 只跟随真实工具执行，未知工具、权限拒绝、跳过及幂等重放不会启动后置命令。读取本身成功仍是读取证据，钩子恢复提示或拒绝提示不改变原工具的状态分类。
- 审批完成及前置钩子完成后会重新检查拒绝策略，防止等待期间切到 READ_ONLY 后仍开始副作用。
- 容器选择、网络开关、资源限制与环境过滤由当前执行后端传入；请求容器但发生降级时拒绝，不回落宿主。本地命令通过既有 OsSandbox，使用当前平台的 Seatbelt/bubblewrap 能力及 command-network 配置。没有落实系统沙箱时明确返回现有诊断，不能把 LOCAL 当成容器隔离。

## 本机请求与代理配置

- 默认 Host 允许 localhost、合法 127/8 和 ::1；不做 DNS 查询来信任主机名，`127.attacker.example` 等相似名称不算回环。
- Origin 必须精确同源或位于 `pilot.security.allowed-origins`；`null`、错误协议/端口、带路径或凭据的值不被接受。没有 Origin 时，跨站/同站跨源的浏览器标记及陌生 Referer 也会被拒绝。
- curl 等不带 Origin 的命令行客户端可连接回环地址，仍须使用受支持 Host；HTTP/1.0 无 Host 时使用 Servlet 的服务器名称兼容回退。此模式不提供对本机其他原生进程的身份认证。
- 新配置 `PILOT_SECURITY_ALLOWED_HOSTS` / `pilot.security.allowed-hosts` 允许显式代理 Host；按后端实际收到的 Host 写入，例如 `pilot.example` 或 `pilot.example:9999`。TLS 终止或地址不同还需设置 `PILOT_SECURITY_ALLOWED_ORIGINS=https://pilot.example`。均为精确名单，无通配符。
- 不依据客户端 Forwarded / X-Forwarded-* 扩大信任。非回环来源仍需 token 模式；反向代理对外提供服务时继续使用 token 认证和原有 TLS/代理边界。

来源策略参考 [W3C Fetch Metadata](https://www.w3.org/TR/fetch-metadata/) 和 [OWASP CSRF Prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)。这些信号用于浏览器请求边界，不是对宿主恶意进程的隔离。

## 验证

- 新增 HookPermissionsTest 7 项：真实 marker 文件写入、只读阻断、工作区拒绝/批准、用户级命令权限、前置拒绝、审批时切换权限、隔离降级与后端传参、ASK 获批后的前置检查及命令 deny。复用并更新 HooksTest / ToolExecutorHooksTest。
- AccessControlTest / RemoteAccessFilterTest 覆盖正常与陌生 Origin/Host、相似 IP 名称、IPv6、端口差异、不透明来源、重复头、无 Origin 命令行与浏览器标记、公共路径、代理精确名单、token 模式回归。
- `JAVA_HOME=<JDK25> bash mvnw -o -pl pilot-server -am spotless:apply verify`：BUILD SUCCESS，核心 605 项（597 通过、8 跳过），服务器 658 项（653 通过、5 跳过）；合计 1,250 项通过、13 项按条件跳过，失败和错误为 0。包含打包、Spotless、Checkstyle。日志：`/tmp/pilot-security-verify.log`。
- 新增 `pilot-web/frontend/scripts/local-security-package-smoke.mjs`：独立数据目录和随机端口启动 Jar，使用真实 Chromium，不调用模型、不访问真实会话。旧包跨源简单 POST 返回 204，攻击者域名模拟解析到回环后读取会话接口返回 200；修复包分别返回 403、403。两版正常同源请求均为 204，无 Origin 的命令行读接口均为 200。
- 旧包对比使用 `PILOT_SECURITY_TEST_JAR=/tmp/pilot-before-security.jar PILOT_SECURITY_EXPECT_VULNERABLE=true`。结果记录于 `/tmp/pilot-local-security-before.log`；最终修复包记录于 `/tmp/pilot-local-security-after.log`。该测试模拟域名解析到回环，未声称复现公网 DNS 的 TTL 切换全过程。
- CI 将真实浏览器脚本加入已有 Windows/macOS/Linux desktop-host 矩阵；本轮没有执行远程 CI。Shell 选择使用平台接口并修正 Darwin 判断；路径与容器后端有模拟测试。实机范围为 macOS、JDK25、Chrome/Chromium 和本地 Seatbelt；Windows、Linux 桌面及容器守护进程没有在本轮实机验证。

运行真实浏览器回归（先完成 Maven package）：

```sh
node pilot-web/frontend/scripts/local-security-package-smoke.mjs
```
