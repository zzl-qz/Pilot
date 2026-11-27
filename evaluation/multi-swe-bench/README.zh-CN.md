# Pilot × Multi-SWE-bench 离线评测套件

[English](README.md) | [简体中文](README.zh-CN.md)

本目录存放 **Pilot** 在官方 [ByteDance-Seed/Multi-SWE-bench-flash](https://huggingface.co/datasets/ByteDance-Seed/Multi-SWE-bench-flash)（Java / TypeScript）上的可复现离线评测框架、防泄题容器入口、锁定题集元数据及脱敏归档报告。

## 核心规范

1. **零泄题与反作弊**：每题在独立销毁型 `mswebench/*` 容器内运行，启动 Pilot 前删除 `/home/*.patch` 与 `/home/*run.sh`，并彻底销毁未来 git 历史、重建单提交 `baseline` 仓库；每轮自动审计（`commitCount == 1`、`leakedArtifactsCount == 0`）。
2. **官方隐藏单测验收**：补丁仅交由上游 `multi_swe_bench` Docker 验收器评分（`mode=instance_only`，默认 `--workers 1` 消除并发时序抖动）。
3. **严格交错 A/B 对照（`--interleave`）**：双组锁死同一模型与端点，按题交替运行（`A(t1), B(t1), A(t2), B(t2)...`），消除 API 时段漂移。
4. **全量成本与重复稳定性**：未命中输入 Token 按失败分摊到成功任务统计；通过 12 题独立 `k=3` 重复运行报告 `avg@3`、`pass^3`（3 次全过一致性）与 `pass@3`。
5. **零密钥落盘**：API Key 经 `0600` 临时 env 文件注入容器并在 `docker run` 后立即删除，严禁写入清单、日志或报告。

## 最新归档结果

详见 [2026-11-27 纵向版本 A/B 与稳定性报告](results/2026-11-27-0caa2257-vs-9b862d35/REPORT.zh-CN.md)（`v0-0caa2257` vs `v1-9b862d35`，固定模型 `gpt-6.1-sol`，40 题 Java/TS + 12 题 `k=3` 稳定性 + 8/8 崩溃恢复确定性冒烟）。

## 快速复现步骤

请参考英文版 [README.md](README.md#how-to-reproduce) 中的完整 6 步复现命令（需 Docker、Node 22+、Python 3.10+、JDK 25 及 Linux x64 JRE 25 挂载目录）。大体积运行期产物（`runs/`、`jars/`、`datasets/*.jsonl`、`.venv/` 等）均已配置 `.gitignore`，不会污染代码库。
