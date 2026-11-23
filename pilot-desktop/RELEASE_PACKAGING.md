# Pilot 三平台自包含发行包构建指南（`jlink` 最小 JRE）

本文档说明如何使用 `scripts/package-desktop-release.mjs` 通过 JDK 25 `jlink` 裁剪最小运行时，产出不依赖最终用户本机安装 Java 的自包含桌面发行包。

## 一键构建命令

先构建最新服务端 JAR（跳过测试仅打包时）：

```sh
# macOS / Linux
JAVA_HOME=/path/to/jdk-25 sh ./mvnw -B -ntp -pl pilot-server -am -DskipTests package

# Windows PowerShell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
.\mvnw.cmd -B -ntp -pl pilot-server -am -DskipTests package
```

再生成当前平台的自包含发行包并执行本机启动自检：

```sh
# macOS (产出 Pilot.app + Pilot-macOS-<arch>.dmg)
JAVA_HOME=$(/usr/libexec/java_home -v 25) node scripts/package-desktop-release.mjs --verify

# Windows PowerShell (产出 bundle/Pilot.cmd + pilot-installer.nsi；若已安装 makensis 自动产出 Pilot-Windows-x64-Setup.exe)
node scripts/package-desktop-release.mjs --verify

# Linux (产出 Pilot.AppDir；若已安装 appimagetool 自动产出 Pilot-Linux-<arch>.AppImage)
node scripts/package-desktop-release.mjs --verify
```

也可通过 `npm --prefix pilot-desktop run package:release` 触发。

## 三平台产物矩阵与验证范围

| 平台 | 目标产物 | 原生封装依赖 | 当前验证状态 |
|---|---|---|---|
| **macOS ARM64** | `Pilot.app` + `Pilot-macOS-arm64.dmg` | 系统自带 `hdiutil` | **已在 macOS ARM64 实机跑通**：`jlink` 裁剪最小 JRE、生成 `.app` 与 `.dmg`、使用裁剪后 JRE 启动 `pilot-server.jar` 并通过 `/api/health` 与 `/api/diagnostics/export` 实测 |
| **Windows x64** | `bundle/Pilot.cmd` + `pilot-installer.nsi` / `Pilot-Windows-x64-Setup.exe` | 可选 `makensis`（NSIS） | **仅完成脚本跨平台兼容处理与单元测试**（CRLF、`.exe` 路径、NSIS 脚本生成）；**尚未在 Windows 实机验证安装与启动** |
| **Linux x64 / ARM64** | `Pilot.AppDir` / `Pilot-Linux-<arch>.AppImage` | 可选 `appimagetool` | **仅完成脚本跨平台兼容处理与单元测试**（`AppRun`、`pilot.desktop`、POSIX 权限）；**尚未在 Linux 实机验证安装与启动** |

## 裁剪策略说明

- 使用 `PILOT_JLINK_MODULES`（21 个必需模块，含 `jdk.charsets` 以支持中文 GBK 命令输出解码，排除 `jdk.jlink`、`jdk.jpackage`、`jdk.javadoc` 等开发工具模块），并启用 `--strip-debug --no-header-files --no-man-pages --compress=zip-6`。
- 每次打包均在产物目录写入 `release-manifest.json`，记录裁剪后的 JRE 体积、JAR 体积、模块清单与本机自检结果。
- 最终产物默认仅监听 `127.0.0.1`，数据目录指向用户目录 `~/.pilot`（可通过 `PILOT_DATA_DIR` 覆盖）。
