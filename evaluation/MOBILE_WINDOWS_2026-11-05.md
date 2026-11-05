# Windows x64 源码启动手机连接的排查与修正

用户报告部分 Windows x64 电脑源码启动后无法生成二维码，界面只显示 Service Error；macOS 正常。本轮无法访问故障 Windows 的日志或真机，因此不将任何单一候选原因判定为该机器的最终根因。

## 已确认的代码与制品事实

- Windows x64 在服务端平台检查和上游依赖制品中均受支持。iroh-1.2.0.jar 包含 `win32-x86-64/iroh_ffi.dll`，本机解析 PE 头确认 Machine=0x8664，并导入 `VCRUNTIME140.dll`。这证明存在 Windows 原生制品及运行库依赖，不等于故障电脑一定缺少运行库。
- 旧版曾调用 `Path.resolve("*")`，Windows 路径不允许该字符。仓库已有 `c2ee9a6` 将 classpath 改为字符串拼接；仅拉代码但没有重新编译 / 重启后台，仍可能执行旧版本。本轮保留该修复及 Windows 路径测试。
- Maven 不生成独立的连接桥。缺少构建产物、显式组件目录配置错误、Java 子进程启动失败或 DLL 加载失败都可能阻止生成二维码。
- 连接服务原来丢弃子进程 stderr；控制器又依赖 Spring 默认异常响应。默认响应可能省略 message，前端便只显示 HTTP 通用状态，不能区分实际故障。

## 本轮修正

1. 连接管理接口显式返回 503 JSON，包括受控的中文 message、error 与 code，设置 no-store。首次失败时二维码占位显示“连接暂不可用”，不再一直显示“正在准备连接”。
2. 子进程输出只转成已知故障类别：依赖缺失、Java 版本、原生库、权限。原始输出、路径、管理地址和凭证不存入诊断状态，也不显示到页面。启动失败时最多等待一秒收集分类结果；Java 子进程无法启动和显式目录不存在也有独立提示。
3. Windows 原生加载失败提示检查 VC++ x64 运行库、临时目录权限和安全软件；macOS / Linux 使用对应提示。中继就绪超时与子进程启动失败分开报告，不建议关闭所有防护。
4. 新增 `node scripts/mobile.mjs build-bridge`，通过 Gradle 参数仅配置 / 构建 transport 与 bridge，不配置 app，不需要 Android SDK。构建使用 Java 21，后台仍使用 Java 25；原有 APK / QA / 测试入口不变。CI 三系统移动任务增加此入口，但本轮没有取得远程 CI 或 Windows 实机结果。
5. 源码说明补充构建 / 重启步骤和 [Microsoft 官方运行库文档](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist)。运行库只在原生加载失败的诊断范围内检查，不因 Service Error 就断言必须安装；不自动下载 DLL 或修改安全策略。

## 验证

- 完整 Maven verify：core 552、server 592，共 1144 项，13 项跳过，失败 / 错误均为 0；格式和 Checkstyle 通过。包括 Windows / macOS / Linux 分类、凭证不回显、真实 Java 子进程缺 classpath、显式错误目录与受控 HTTP 响应。随后针对最终分类优先级再验证相关测试。
- 移动 JVM 27 项与 Android lint 通过。既有二维码生成后用 ZXing 解码核对、权限 / 来源检查仍通过；新增中继超时返回明确原因，状态接口继续正常工作。
- `build-bridge` 成功，仅出现 JVM 模块任务，无安卓任务。源码入口与参数数组兼容 Windows / macOS / Linux；本机验证平台为 macOS ARM64。
- Web / 真实 Electron 配对窗口验收通过：首次服务失败显示具体中文原因及失败占位、重试恢复二维码、优先显示 message 而非 Service Unavailable；并回归多设备、三种宽度、明暗主题、剪贴板和来源限制。
- 隔离真实 Pilot Jar 验证：空的带中文 / 空格组件目录导致真实子进程退出，HTTP 503 仍包含修复命令，不回显临时目录和令牌；重启同一隔离后台后，正常配对、鉴权、项目 / 任务、幂等提交、快照确认和 SSE 契约通过。临时数据，模型调用为 0。

日志：`/tmp/pilot-windows-mobile-maven.log`、`/tmp/pilot-windows-mobile-target.log`、`/tmp/pilot-windows-mobile-unit.log`、`/tmp/pilot-windows-mobile-bridge-build.log`、`/tmp/pilot-windows-mobile-ui.log`、`/tmp/pilot-windows-mobile-backend.log`。

Windows 真机尚未验收；本轮测试包含平台参数模拟及 Windows DLL 制品检查，不能当成 Windows 上实际加载 DLL 成功。故障电脑需更新源码、用 Java 21 执行 `node scripts/mobile.mjs build-bridge`，再用 Java 25 重新编译并启动后台；若仍失败，依据新的中文提示和该电脑的启动日志继续定位。
