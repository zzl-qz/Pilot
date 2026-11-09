# 贡献指南

感谢你关注 Pilot。仓库目前处于开发预览阶段；提交缺陷报告、文档改进或代码变更前，请先查看[首次启动指南](docs-public/getting-started.md)、[安全与隐私](docs-public/security.md)和[当前已知边界](docs-public/user-guide.md#当前边界)。

## 提交问题或建议

- 先搜索现有 Issues，避免重复报告。
- 缺陷报告请说明复现步骤、预期行为、实际结果、操作系统和相关运行时版本。
- 先检查日志与截图，再删除访问令牌、模型密钥、个人路径、会话正文及其他私人信息。
- 安全漏洞不要公开报告；按 [SECURITY.md](SECURITY.md) 联系维护者。

## 提交代码或文档

- 小步提交并聚焦一个问题；描述改动动机、行为变化和验证结果。
- 新提交的标题和正文使用中文；文件路径、代码标识符和专有名词可保留原文。
- 涉及 Web/桌面快捷键、剪贴板、路径、进程启动或构建脚本时，考虑 Windows、macOS 和 Linux 的差异；使用标准跨平台接口，必要时做能力检测。
- 浏览器不支持所需能力时，应提供明确反馈和可用替代操作，不要静默失败或把路径文本当作文件上传成功。
- 测试正常流程、失败和边界情况。注明本机验证、模拟测试、CI 覆盖和未做的实机验证；不要把单一系统的成功描述成三平台兼容。
- 文档中的启动、测试和配置说明应能在当前仓库复现。涉及界面的截图使用非敏感示例数据，并说明是否为模拟场景。

通过 GitHub Pull Request 提交改动时，请在说明中附上关联问题、变更摘要、实际运行的命令，以及尚未验证的平台或边界。PR 是否合并由维护者决定。

## 本地验证

### 服务端与完整 Maven 验证

根目录执行完整服务端构建与测试。Java 25 由项目源码运行时准备流程使用；Android 构建另需 Java 21。

macOS/Linux：

```sh
sh mvnw -B -ntp verify
```

Windows PowerShell：

```powershell
.\mvnw.cmd -B -ntp verify
```

### 桌面端与前端

安装桌面端依赖并运行其测试：

```sh
npm --prefix pilot-desktop ci
npm --prefix pilot-desktop test
```

前端单元与迁移检查在 `pilot-web/frontend/` 下执行：

```sh
node scripts/verify-migration.mjs --unit-only
```

前端构建与类型检查：

```sh
npm --prefix pilot-web/frontend run check
```

### Android

Android 修改需要安装 Android SDK，并使用 Java 21 与 Node.js 22。CI 使用以下项目入口运行移动端验证：

```sh
node scripts/mobile.mjs test
```

构建预览包：

```sh
node scripts/mobile.mjs build
```

完整平台矩阵和额外隔离测试见 [CI 工作流](.github/workflows/ci.yml)。本机没有 Android SDK 或对应操作系统时，请如实注明未覆盖项。

### README 截图

README 中的桌面截图由工作台成果与执行结果 UI 回归场景生成。要在本机重新生成，可运行：

```sh
npm --prefix pilot-web/frontend run test:results -- --production --chromium-only
npm --prefix pilot-web/frontend run test:products -- --web-only
```

这些场景使用模拟 API 和示例任务内容，截图路径会打印在命令输出中；发布前请人工检查截图，不要将测试结果描述成真实模型任务。

## 许可证

当前仓库没有 `LICENSE` 文件，也没有发布贡献者许可或 CLA 条款。公开仓库不等于代码已按开源许可证授权；在维护者确定许可证前，请先联系维护者确认复用与贡献条款。
