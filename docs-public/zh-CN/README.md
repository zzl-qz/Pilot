# Pilot 文档索引

[English](../README.md) | [简体中文](README.md)

按问题查阅即可，不需要一次读完整个仓库。

## 使用与架构

| 想了解 | 文档 |
| --- | --- |
| 首次启动、选择工作区、配置模型 | [首次启动指南](getting-started.md) |
| 系统架构、Agent 循环、SSE 游标恢复与安全边界 | [系统架构概览](architecture.md) |
| Multi-SWE-bench 交错 A/B 实测、稳定性与崩溃恢复验收 | [基准评测与工程验证](benchmarks.md) |
| 工作台流程、浏览器自动化、任务控制、成果与常用配置 | [功能与配置参考](user-guide.md) |
| 权限、高危命令红线、浏览器隐私、模型数据流与远程访问 | [安全与隐私](security.md) |
| MCP 启动命令信任与工具定义钉住 | [MCP 信任模型](design-mcp-trust.md) |
| 我们不做什么：用户范围、不承诺项与不覆盖项 | [范围与边界](scope.md) |
| 插件如何声明依赖、为什么升级不会打掉整个生态 | [插件契约](plugin-contracts.md) |
| 与验证类工具的差异，以及证据在运行时里的位置 | [与验证类工具的差异](verification-approach.md) |
| 完成凭证、验证策略即代码与任务交接 | [完成凭证与交接](completion-capsule.md) |
| 桌面端与 `jlink` 三平台自包含发行包 | [桌面端指南](../../pilot-desktop/README.md) · [自包含发行包构建指南](../../pilot-desktop/RELEASE_PACKAGING.md) |
| Android 客户端、手机审批演示与 P2P/中继延迟测量 | [Android 指南](../../pilot-mobile/README.md) · [手机审批演示](mobile-approval-demo.md) · [手机延迟测量](mobile-latency-measurement.md) |

## 系统与开发

| 文档 | 内容 |
| --- | --- |
| [项目技术导览](../../PROJECT_TECHNICAL_GUIDE.md) | 模块边界、任务数据流与源码阅读路线 |
| [产品方向与发展路线](../../PRODUCT_DIRECTION.md) | 产品目标、阶段安排和当前门槛 |
| [通用任务工作流](../../GENERAL_AGENT_WORKFLOW.md) | 通用任务、远程工作台和结果交付 |
| [源码环境自动准备](../../SOURCE_ENVIRONMENT.md) | Java、Node 与运行时准备方式 |
| [贡献指南](../../CONTRIBUTING.md) | 缺陷报告、开发验证和跨平台要求 |
| [安全报告政策](../../SECURITY.md) | 私下报告安全问题的方式 |

## 评估记录

仓库的 `evaluation/` 保存自动化评测规格和阶段性验证结果（含可复现的 [Multi-SWE-bench 离线评测套件](../../evaluation/multi-swe-bench/README.zh-CN.md)）。每份报告仅对应特定提交、模型与环境，查阅时请核对日期、提交号与边界声明。

## 文档约定

运行和测试命令以仓库脚本、[CI 工作流](../../.github/workflows/ci.yml)和对应操作系统的验证结果为准。界面截图使用 Playwright 测试场景中的模拟数据，不代表真实模型会话。
