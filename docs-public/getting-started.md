# 首次启动指南

> 最后更新：2026-11-24

Pilot 默认仅在本机提供工作台；模型服务、工作区目录和运行数据由用户配置。第一次启动前，建议准备一个可丢弃的测试项目。

## 运行条件

- 64 位 Windows、macOS 或 Linux。Linux 自动准备目前要求 glibc；musl 发行版不在自动准备范围内。
- Git，用于克隆仓库。
- 首次启动需要网络：启动器会在 Pilot 私有缓存内准备 Java 25、Node.js 22 及需要的桌面依赖，不要求另外安装 Maven。若需构建不依赖用户本机 Java 的自包含桌面包，见[三平台自包含发行包构建指南](../pilot-desktop/RELEASE_PACKAGING.md)。
- Windows 使用 PowerShell 或命令提示符；macOS/Linux 使用 Bash。

Android SDK、Gradle 和 Java 21 只在构建或测试 Android 客户端时需要，不是运行电脑端工作台的前置条件。移动端要求见 [Android 与移动连接](../pilot-mobile/README.md)。

## 启动

```sh
git clone https://github.com/zzl-qz/Pilot.git
cd Pilot
```

macOS/Linux：

```sh
bash start.sh
```

Windows PowerShell 或命令提示符：

```powershell
.\start.cmd
```

首次准备可能需要几分钟。启动过程会构建并验证服务端；失败时会停止启动，不会用旧构建结果冒充成功。完成后，在浏览器打开 <http://localhost:9999>。桌面依赖可用时也会启动 Electron 工作台。

如果本机 9999 端口已被占用，可以显式换端口：

```sh
bash start.sh --server.port=10001
```

```powershell
.\start.cmd --server.port=10001
```

## 选择工作区与模型

首次试用请在设置中选择一个可丢弃的仓库，并把默认权限档位 `AUTO` 改为 `ASK` 或 `READ_ONLY`（也可以直接在输入栏按 `Shift+Tab` 切到消息级只读分析模式 `plan`，或用 `/review` 做只读审查、`/browser` 发起内置浏览器任务）。权限档位与高危命令红线见[安全与隐私](security.md#工具权限和本机执行)。

没有模型密钥时，可以在工作台的模型设置中使用 Mock 模式体验界面流程。Mock 模式不代表模型能力测试，也不能验证真实模型执行结果。

使用真实模型时，在启动 Pilot 的同一终端设置模型服务参数，或在工作台「设置 → 模型」中配置多组端点。不要把密钥放进工作区文件或提交到 Git。

macOS/Linux：

```sh
PILOT_MODEL_API_KEY="替换为你的密钥" \
PILOT_MODEL_ENDPOINT="https://api.openai.com/v1/chat/completions" \
PILOT_MODEL_MODEL="gpt-4o-mini" \
PILOT_WORKSPACE="/绝对路径/到/测试项目" \
bash start.sh
```

Windows PowerShell：

```powershell
$env:PILOT_MODEL_API_KEY = "替换为你的密钥"
$env:PILOT_MODEL_ENDPOINT = "https://api.openai.com/v1/chat/completions"
$env:PILOT_MODEL_MODEL = "gpt-4o-mini"
$env:PILOT_WORKSPACE = "C:\work\sample-project"
.\start.cmd
```

模型客户端支持 OpenAI Chat Completions、OpenAI Responses 及 Anthropic Messages 协议（支持原生 SSE 流式、Prompt Caching 与用量统计），端点格式和能力以服务商文档为准；在模型设置中可按端点与模型单独配置请求时限和计价。

## 本机数据与网络

- Web/API 默认地址是 `http://localhost:9999`，远程访问默认关闭并限制在本机回环地址。
- 会话数据库、附件、快照、浏览器证据和 trace 默认写入仓库下的 `.pilot/`；可用 `PILOT_DATA_DIR` 指定另一个数据目录。在 POSIX 文件系统上，`.pilot/pilot.db` 与请求诊断落盘默认收紧为仅当前用户可读写（`0600`）。
- `.pilot/` 含本机工作内容和运行记录。备份、复制或上传前先检查其内容；需要提交排障信息时，请使用「设置 → 诊断」中的一键导出脱敏诊断包。
- 手机连接是可选功能。启动 Pilot 不会自动把服务开放到公网；配置远程访问前请阅读[安全与隐私](security.md#远程访问)。

首次准备行为和运行时细节见[源码环境自动准备](../SOURCE_ENVIRONMENT.md)。
