# 源码环境自动准备

普通启动入口是 Windows 的 `start.cmd` 或 macOS / Linux 的 `bash start.sh`。参数仍传给后台，工作目录自动切到源码根目录，支持目录中的空格和中文。第一次联网启动会准备缺失依赖，下载进度和当前步骤会输出到启动控制台；电脑端缺少的依赖会按需下载到 Pilot 私有目录。

## 自动处理范围

| 检查项 | 处理方式 |
| --- | --- |
| 启动 Java | 检查版本、JDK 编译能力及架构；优先复用本机 Java 25+，否则下载私有 Temurin JDK 25 |
| Node 与 npm | 检查 Node 22.13+ 和 npm 是否齐全，缺失或残缺时下载完整私有 Node 22；不修改系统安装 |
| Maven | 使用固定版本的私有 Maven，通过 Java 参数数组启动，避免 Windows 批处理路径转义问题 |
| 前端依赖 | 缺失或版本不符合锁文件时自动执行 npm ci；依赖齐全时保留终端中的更新询问 |
| 可选手机连接桥 | 仅在打开“连接手机”时检查已打包组件；未打包时提示，不启动 Gradle，不构建 APK / 连接桥 |
| 原生与二维码 | 手机连接窗口按需实际创建无中继的临时端点并生成 PNG，确认原生库能加载、二维码编码可运行；不创建配对身份 |
| 桌面 Electron | npm 安装后显式运行官方二进制安装器；验证锁定版本、平台、架构、路径及执行权限后再准备桌面壳；失效副本保留后修复 |
| Windows VC++ | 原生加载失败时先尝试私有 Temurin，再按需使用微软安装器；下载哈希与 Authenticode 签名均验证后才运行 |

Python、Chromium 仍沿用既有组件准备机制。源码启动器和后台新增的 Node 安装共用制品清单和 SHA-256 缓存，已有 `.pilot/runtime/node` 继续可用。PC 启动不检查或下载 Gradle、Android SDK、移动构建用 Java 21，也不强装 Docker、Xcode 或修改系统证书、企业安全策略。这些项目工具和系统能力不能等同于可复制的私有运行时。

## 系统与架构

- Windows x64 使用对应的 JDK、Node 与 x64 原生库。32 位系统不能运行这些工具，会提前提示。
- Windows ARM64 会尝试独立 x64 Java 后端，由子进程实际验证系统的 x64 应用兼容能力；不能把选择了兼容后端当作已经支持所有 Windows ARM 系统。
- Apple Silicon 优先使用 ARM Java。IDE 处于 Intel/Rosetta 模式时，连接桥会单独尝试 ARM Java，不强迫更改 IDE 的 Java 设置。
- Intel Mac 可准备 Java / Node 并运行应用；当前上游连接库没有 Intel Mac 制品，手机连接明确降级，其余功能继续运行。
- Linux x64 / ARM64 使用对应的 glibc 制品。启动脚本不把 glibc 包当作 musl 包安装；musl / 过旧系统不在当前自动准备的验证范围。
- 下载后仍验证可执行能力。系统版本、动态库、执行权限或安全策略不满足时报告失败，不会只因文件存在就宣称可用。

## 缓存与失败处理

默认缓存位于仓库 `.pilot/bootstrap`。设置 `PILOT_DATA_DIR` 时，启动准备器使用该目录下的 bootstrap；也可直接设置 `PILOT_BOOTSTRAP_DIR` 到有写权限的目录。目录不进入 Git；不会修改用户全局 JAVA_HOME、PATH 或覆盖系统 Java。构建源码仍需要仓库自身可写。

电脑端 Maven、npm 与 Electron 下载缓存默认分别保存到 `pilot/.pilot/cache/maven`、`npm`、`electron`；可用 `PILOT_DEPENDENCY_CACHE` 整体换位置。显式设置的 `PILOT_MAVEN_REPOSITORY`、`npm_config_cache` 和 `electron_config_cache` 优先，避免覆盖已有配置。IDE 从 `pilot-server` 目录启动时，默认手机组件缓存也会定位到 Pilot 根目录。工具仍可以读取用户原有的仓库镜像、代理与认证配置。

后端自动安装浏览器与桌面依赖时，首次尝试沿用现有工具配置。失败后，两个安装器共用 `PILOT_RUNTIME_NPM_FALLBACK_REGISTRY` 指定的 npm 备用源，浏览器和桌面二进制分别读取 `PILOT_RUNTIME_CHROMIUM_FALLBACK_MIRROR` 与 `PILOT_RUNTIME_ELECTRON_FALLBACK_MIRROR`。未设置时沿用原有 npmmirror 地址。备用地址支持无凭据、查询参数和片段的 HTTP(S) URL。

新增运行时下载来自随源码固定的官方版本，SHA-256 保存在 `pilot-core/src/main/resources/bootstrap/runtime.properties`。下载使用临时文件，校验和解压完成后才发布；同一缓存有跨进程锁，同一项目构建也串行执行。重复启动不会同时安装，下载 / 进程有时间与大小上限，损坏的私有副本留存后再替换。Windows 安装器额外校验微软签名，不从随机网站获取单独 DLL。

Windows VC++ 安装采用微软支持的 `/install /passive /norestart`。系统可能显示 UAC；拒绝授权、签名不可信或管理策略阻止时停止该修复，不自动重启、不关闭防护、不反复弹安装。手机连接可以降级，后台其他功能继续提供服务。微软流程参考 [官方部署说明](https://learn.microsoft.com/en-us/cpp/windows/redistributing-visual-cpp-files)。

IDE 或已打包后台仅在打开手机连接窗口时异步检查已打包组件，HTTP 状态读取不会等待原生验证或电脑运行库补齐结束。窗口显示“正在准备环境”和当前步骤，完成后再生成二维码。失败后可用“检查并修复环境”显式重试；轮询不会反复运行安装器。配对失败的提示不会被成功的状态轮询清除。设备授权与任务数据不因环境修复而删除。

## 开关与离线使用

- `PILOT_START_CHECK_ONLY=true`：运行启动脚本只准备和检查环境，不编译后台或启动应用，适合首次预热。
- `PILOT_FORCE_MANAGED_JAVA=true`：启动脚本优先用私有 Java，便于复现统一工具链。
- `PILOT_OFFLINE=true`：准备器只使用本机工具或已校验缓存；缺缓存时明确失败，不继续执行不完整包。Maven / npm 使用离线选项，源码启动传递关闭自动下载的组件环境设置。这不是 Agent 命令的网络沙箱。
- `PILOT_MOBILE_PROVISION=false` 或 `pilot.mobile.provision=false`：关闭后台手机环境自动补全，适用于已有组件或受管理环境。
- 运行时下载识别 HTTPS_PROXY / HTTP_PROXY，JVM 下载支持主机及域后缀形式的 NO_PROXY，保持正常 TLS 验证。Electron 保留 npm 包内置的制品校验值；镜像不能替换校验值。Maven 的私有仓库及企业证书仍遵循本机配置，不会自动导入未知证书。

完全离线的新机器无法凭空获得未携带的依赖。可先联网预热，或携带同系统 / 架构的完整便携包与缓存，再由启动探针确认。IDE 自身的 Java 编译器在程序运行前执行，无法由尚未启动的后台修改；没有 Java 25 时应先运行启动脚本，再按 IDE 需要选择生成的私有 JDK。

直接从 IDE 运行 Maven 也会在编译前检查前端依赖，即使没有交互终端。若无法安装，启动会停在依赖检查，修好网络或缓存后可重试；只构建后端可指定 `-DskipFrontend=true`，同时跳过前端依赖安装与构建。

## 开发验证

```text
node --test scripts/bootstrap.test.mjs scripts/frontend-deps.test.mjs
npm --prefix pilot-desktop test
node scripts/mobile.mjs test  # 仅移动端开发验收，由开发者自行准备 Java 21 / Gradle / Android SDK
node pilot-mobile/scripts/environment-smoke.mjs
```

Windows 脚本可直接使用系统 PowerShell；其他系统可通过 `PILOT_TEST_POWERSHELL` 指定 PowerShell 引擎以运行模拟测试。模拟签名与 UAC 场景不会执行真实系统安装。最新范围与测试记录见 `evaluation/SCANNER_AND_DESKTOP_START_2026-11-05.md`；Windows / Rosetta / ARM 兼容层未经实机测试的部分不会计作实机通过。

普通 PC 启动不会尝试修复移动构建工具链。需要手机功能时由开发者单独构建 `node scripts/mobile.mjs build-bridge` / `build` 或提供包含连接桥的安装包；这些是明确执行的开发命令，与 PC 启动自检分开。
