# Pilot 文档

按问题查阅即可，不需要一次读完整个仓库。

## 使用 Pilot

| 想了解 | 文档 |
| --- | --- |
| 首次启动、选择工作区、配置模型 | [首次启动指南](getting-started.md) |
| 工作台流程、任务控制、成果与常用配置 | [功能与配置参考](user-guide.md) |
| 权限、模型数据流、本机存储与远程访问 | [安全与隐私](security.md) |
| 桌面端与 Electron 验收 | [桌面端指南](../pilot-desktop/README.md) |
| Android 客户端与移动连接 | [Android 指南](../pilot-mobile/README.md) |

## 系统与开发

| 文档 | 内容 |
| --- | --- |
| [项目技术导览](../PROJECT_TECHNICAL_GUIDE.md) | 模块边界、任务数据流与源码阅读路线 |
| [产品方向与发展路线](../PRODUCT_DIRECTION.md) | 产品目标、阶段安排和当前门槛 |
| [通用任务工作流](../GENERAL_AGENT_WORKFLOW.md) | 通用任务、远程工作台和结果交付 |
| [源码环境自动准备](../SOURCE_ENVIRONMENT.md) | Java、Node 与运行时准备方式 |
| [贡献指南](../CONTRIBUTING.md) | 缺陷报告、开发验证和跨平台要求 |
| [安全报告政策](../SECURITY.md) | 私下报告安全问题的方式 |

## 评估记录

仓库的 `evaluation/` 保存自动化评测规格和阶段性验证结果。它们对应特定提交与测试环境，不应外推为所有平台或真实模型的能力承诺。查看时请核对报告日期、提交号和明确列出的覆盖范围。

## 文档约定

运行和测试命令以仓库脚本、[CI 工作流](../.github/workflows/ci.yml)和对应操作系统的验证结果为准。界面截图使用 Playwright 测试场景中的模拟数据；不代表真实模型会话。
