# 桌面 IPC 与宿主运行验收（2026-11-05）

本轮在 macOS ARM64（Darwin 25.4.0）完成代码修复与实际进程验证。用户明确表示 Windows／Linux 两类实机由其自行验证，因此本轮交付统一入口和报告规则，不代替用户填写这两类结果。未操作真实手机、其他项目模拟器、个人后台或运行数据；业务夹具模型调用为 0。

## 修复内容

- 新增 `workbench-security.mjs`。16 个 invoke 通道和 3 个单向消息统一校验目标窗口、主框架身份、框架未销毁、Chromium 实际 origin 与工作台 HTTP(S) 来源；错误固定返回 `UNTRUSTED_WORKBENCH`，单向消息无副作用。
- 异步结果和异常返回前重新校验；原生菜单的延后动作也重新校验，避免页面离开后收到旧结果或执行菜单动作。
- 浏览器状态、控制台／网络日志、下载及引用等主动通知绑定已校验的主框架投递，页面切换时不改发给新文档。
- 生产 preload 仅在配置来源的主页面暴露 `pilotDesktop`；离线页、外部文档和子框架不暴露桥接能力。
- 工作台检查 `will-frame-navigate`、`will-navigate`、`will-redirect` 并拒绝创建任意原生弹窗。跨源 HTTP(S) 链接和 HTTP(S) 弹窗转到内置浏览器并展示提示；同源弹窗保留工作台与草稿。同源正常导航、原生 F5、离线重连与手机连接工作流保留。
- 导航事件明确处理旧版附加位置参数，避免把 frameProcessId 等数字误判为重定向策略。取消导航后的通知在下一事件轮次重新检查当前文档。

实现依据为 [Electron IPC 来源校验](https://www.electronjs.org/docs/latest/tutorial/security#17-validate-the-sender-of-all-ipc-messages)、[导航事件](https://www.electronjs.org/docs/latest/api/web-contents#navigation-events)及项目固定 Electron 45.2.1 的实际 API。`senderFrame.origin` 用于实际来源判定，不单独依赖候选导航 URL。报告中不声称已解决所有同源 XSS 或所有 Electron 安全问题。

## 测试结果

| 检查 | 结果与范围 |
| --- | --- |
| 桌面单元测试 | 41 项通过；包括窗口／框架／来源、无效 URL、单向消息、异步响应及导航策略边界 |
| Web 单元测试 | 267 项通过 |
| 前端生产构建检查 | `check.mjs` 通过，后台 Maven package 通过 |
| 实际源码启动器 | JavaScript 源码入口离线复用 Electron 缓存、启动真实 GUI、控制口鉴权、正常退出及删除端点通过 |
| 真实 Electron 安全测试 | 14 组通过，生产窗口保留 Chromium 沙箱；包括原跨源日志泄露复现被阻断、16 个调用通道对各不可信来源拒绝、真实同源与不透明子框架 IPC、主进程意外加载不可信文档后的独立防线、日志推送限制、导航／重定向／弹窗和退出清理 |
| 完整宿主流水线 | macOS ARM64 8 个阶段全部通过：缓存、单元测试、源码启动、安全、离线重连、原生连接、组件修复和重启恢复 |
| 真实工作台刷新 | Chrome 与 Electron 均通过 HTTP／SSE 同步、草稿／附件／滚动保留、刷新／失败重试／重载恢复与原生菜单；新增真实 App 外部链接转入浏览器面板并展示提示的验证 |
| 手机连接工作台 | Chrome／Electron 配对管理、设备、宽度／主题、复制兜底、原生剪贴板与非可信来源无桌面桥通过 |
| JVM 连接组件 | `test-bridge` 通过；新入口不配置 Android SDK，保留原有自动模式回归，并新增可独立执行的纯直连宿主恢复测试 |
| CI 配置 | 三平台作业、Linux GUI／沙箱准备、始终上传报告的 YAML 检查通过；未取得远程实际运行结果 |

安全脚本只在隔离的测试窗口中启用子框架 preload 并注入探针，以便主动发送真实子框架 IPC；使用与主程序相同的策略代码验证主框架允许、子框架拒绝。生产窗口保持原有沙箱、contextIsolation 与 nodeIntegration=false，未为测试放宽生产权限。主动推送检查会实际触发浏览器 console-message，再确认不可信文档没有收到消息。

真实工作台刷新夹具先等待字体并模拟用户向上滚动，避免初始贴底过程干扰“阅读中途刷新”的位置断言；原位置保留断言继续执行。轮询验证先确认页面可见，遵守生产中的后台暂停策略；GUI 回归按串行方式运行，避免窗口焦点相互干扰。取消导航后的 UI 断言通过页面状态与 DOM 检查，避免 Playwright 等待已被 Electron 阻止的导航。

## 宿主回归范围

统一命令为 `node scripts/verify-desktop-host.mjs`，前置依赖、Java 21／25 构建步骤和 Windows／Linux 操作见 [桌面说明](../pilot-desktop/README.md)。

源码入口和原生连接测试均真实启动进程，不以“文件存在”替代启动成功。回执恢复使用独立后台、同一测试手机密钥、同一请求标识，检查只有一个任务。监督恢复先通过真实进程确认新连接桥存在，再读取纯直连当前路由，不用管理接口代替监督恢复。

纯直连的 UDP 地址可能随进程重启变化，测试刷新当前本地路由但保留原授权、任务和请求回执。默认自动模式继续沿用原票据；本轮宿主流水线不依赖公共中继，不声称它重新验收了所有公网组合。

跨平台实现使用 Node 参数数组、标准 URL／路径／进程接口、Java ProcessHandle 和独立临时目录，Windows 使用 `java.exe` 分支，文件清理带重试。Linux CI 使用 Xvfb 和安装包内的沙箱 helper，不禁用沙箱或修改宿主网络、代理和防火墙。CI 为 Ubuntu 24.04、Windows Server 2022、macOS 15；runner 结果也不能代替原故障 Windows 机器的结果。

## 本机证据与未执行范围

- `pilot-desktop/out/host-report/darwin-arm64/host.json`：最终 8 阶段成功报告及平台／架构／Electron 版本。
- 同目录 `security.json`、各阶段 `.log` 和截图：实际 IPC、GUI、原生库和恢复证据。
- `/tmp/pilot-desktop-host-final-verified.log`
- `/tmp/pilot-desktop-security-broadcast-verified.log`
- `/tmp/pilot-desktop-refresh-verified.log`
- `/tmp/pilot-desktop-mobile-security.log`
- `/tmp/pilot-desktop-web-unit.log`
- `/tmp/pilot-desktop-host-package-current.log`
- `/tmp/pilot-desktop-current-environment.log`
- `/tmp/pilot-desktop-current-recovery.log`

最终构建包含当前工作区的前端外链处理；使用当前后台产物再次验证组件修复与重启恢复通过。上述日志和运行产物不入 Git。失败路径已实际产生非零退出与 `success:false` 报告，最终成功报告不把部分通过算作整体通过。

Windows／Linux 实机由用户执行，目前没有本轮结果；GitHub Actions 只有配置与静态检查，没有远程运行证据。原故障 Windows 的最终原因仍需以该机器的实际报告为准，不能根据 macOS 成功推断 DLL 加载或界面正常。用户之前的 Android 真机跨网成功作为已有证据保留，不代替桌面宿主验证。
