# Pilot 桌面宿主验证

桌面宿主使用 Electron，工作台和普通浏览器页分别管理。工作台 IPC 的发送窗口、主框架和 Chromium 实际 origin 必须同时可信；所有调用、单向消息和浏览器日志／状态推送都经过 `workbench-security.mjs` 的统一入口。

工作台仅允许可信 HTTP(S) 来源内的导航。跨来源链接和 HTTP(S) 弹窗转到内置浏览器，界面显示打开提示，保留工作台；同源弹窗也不替换工作台。跨来源重定向和不支持的协议会被拒绝并提示。子框架不能借用工作台 IPC，外部文档和离线页不暴露 `pilotDesktop`。离线页仍可通过原生菜单、F5 或页面重试链接恢复。

## 开发与单项回归

在仓库根目录执行：

```text
npm --prefix pilot-desktop test
node pilot-desktop/security-smoke.mjs
node pilot-desktop/ui-smoke.mjs
node pilot-desktop/launcher-smoke.mjs
```

真实 Electron 测试需要已安装桌面依赖及 `pilot-web/frontend` 的测试依赖，使用临时数据目录和独立 profile。安全测试为主动发送子框架 IPC，在独立测试窗口中额外开启子框架 preload 并注入探针；生产窗口维持原有沙箱与子框架隔离。测试使用实际主进程策略，探针不会加入生产 preload。

## Windows／Linux 宿主完整验收

用户负责在对应实机执行。本机 macOS 通过不能替代 Windows／Linux，也不能替代原故障 Windows 机器的原生库加载和进程验证。更新到本轮源码后，先准备 Node 22、Java 21（连接组件构建）与 Java 25（后台运行）。不需要 Android SDK、模拟器或手机。

只在依赖未安装或版本变化时执行：

```text
npm --prefix pilot-desktop ci
npm --prefix pilot-web/frontend ci
```

Windows PowerShell（把示例 Java 路径换成实际安装路径）：

```powershell
$env:JAVA_HOME = 'C:\实际路径\jdk-21'
node scripts/mobile.mjs test-bridge
$env:JAVA_HOME = 'C:\实际路径\jdk-25'
.\mvnw.cmd -B -ntp -pl pilot-server -am -DskipTests package
node scripts/verify-desktop-host.mjs
```

Linux（在有图形桌面的终端执行；把示例 Java 路径换成实际路径）：

```sh
export JAVA_HOME=/实际路径/jdk-21
node scripts/mobile.mjs test-bridge
export JAVA_HOME=/实际路径/jdk-25
sh mvnw -B -ntp -pl pilot-server -am -DskipTests package
node scripts/verify-desktop-host.mjs
```

已有最新后台 Jar、连接桥和测试类时，只需使用 Java 25 执行最后一条命令。`test-bridge` 只构建、测试 JVM 模块，不配置 Android 工程。

统一验收入口依次检查：

1. 实际 Electron 缓存离线复用和桌面单元测试。
2. `start.mjs` 源码入口、真实 GUI、控制口鉴权、正常关闭和端点清理。
3. 可信主页面、不可信页面、其他窗口、同源／不透明子框架、浏览器页的 IPC 边界，以及导航和弹窗。
4. 离线页、F5 重连、原生菜单和旧页面重载。
5. 实际 JVM／原生连接库加载、生成 PNG 并解码验证。
6. 缺失组件时后台继续运行、跨源修复拒绝、显式修复和连接桥启动。
7. 连接桥异常退出后的自动监督恢复、后台退出后的子进程清理、后台重启后的授权／任务／幂等回执保留。

宿主验收全程使用隔离后台和固定业务夹具，不调用模型，不使用真实手机或个人运行数据。纯直连测试不使用公共中继；新进程可能使用新 UDP 端口，因此在确认监督进程已经恢复后读取当前本地路由，并验证原手机身份无需重新配对。该结果不冒充“旧纯直连地址在重启后仍可访问”。公网旧票据恢复仍可另行使用既有 `node pilot-mobile/scripts/backend-smoke.mjs --recovery` 专项验证。

成功时退出码为 0，并且 `host.json` 的 `success` 为 `true`。失败时非零退出，报告标明失败阶段；不能因为部分阶段通过就判定整体通过。默认报告位置：

- Windows x64：`pilot-desktop/out/host-report/win32-x64/`
- Linux x64：`pilot-desktop/out/host-report/linux-x64/`
- Linux ARM64：`pilot-desktop/out/host-report/linux-arm64/`
- macOS ARM64：`pilot-desktop/out/host-report/darwin-arm64/`

保留 `host.json`、对应阶段 `.log`，以及安全测试的 `security.json`／失败截图即可定位问题。报告不复制控制端点凭证文件。可使用 `PILOT_DESKTOP_REPORT_DIR` 指定报告目录。

`.github/workflows/ci.yml` 增加 `desktop-host` 三系统作业，在实际 runner 中启动 GUI 和原生连接进程。Linux 使用 Xvfb，并准备 Chromium 沙箱 helper；测试不使用 `--no-sandbox`。无论成败上传报告，保留 14 天。工作流配置完成不代表远程执行通过；当前仓库远程为 Gitee，用户可在自己的 CI 中调用同一入口。

实际验证与用户负责的范围见 [本轮验收](../evaluation/DESKTOP_IPC_AND_HOST_2026-11-05.md)。
