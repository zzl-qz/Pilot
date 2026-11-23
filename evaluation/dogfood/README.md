# Pilot 自举监督训练台账（Dogfood Production Loop）

本目录持久化记录 Pilot 自举训练的全部任务卡、缺陷台账、逐轮复盘、结构化指标、里程碑报告及自动化工具，确保任何新会话或上下文压缩后均可直接恢复推进，不依赖聊天历史。

## 目录结构

- `README.md`：本说明与实时「当前状态」恢复锚点
- `defects.md`：缺陷台账（D-001～D-xxx）、待复核疑点（R-01～R-04）与生命周期状态
- `metrics.jsonl`：每完成一个任务/对照运行追加一行 JSON 指标
- `tasks/Txxx.md`：冻结的真实用户口吻任务卡、范围约束与验收标准
- `cycles/Cxxx.md`：逐轮独立验收打分、轨迹审计结论、根因与修复记录
- `milestones/M0x.md`：每 5 个任务一次的里程碑汇总与金丝雀回归报告
- `tools/`：`runner-ctl.mjs`、`supervise_pilot.py`、`trace-audit.mjs`、`replay.mjs`

## 隔离拓扑与运行纪律

- 用户主工作区：`/Users/smt/Desktop/pilot`（冻结，Pilot 与运行器均不直接写入）
- 运行器 worktree：`/Users/smt/pilot-dogfood/runner`（本地分支 `df/runner`，仅从已提交且通过 V1 的代码打包重启）
- 任务 worktree：`/Users/smt/pilot-dogfood/ws-Txxx`（本地分支 `df/Txxx`，Pilot 唯一可写工作区）
- 数据目录复用：显式 `PILOT_DATA_DIR=/Users/smt/Desktop/pilot/.pilot` 与 `PILOT_BROWSER_HOME=/Users/smt/Desktop/pilot/.pilot/browser`（严禁复制或打印密钥）
- 桌面窗口：每次重启默认保持 `PILOT_DESKTOP_LAUNCH=true`，确保用户可在 Electron 桌面端实时看到所有 `dogfood:` 任务运行状态
- Git 纪律：仅用用户本人身份 `zzl <2080879934@qq.com>`，中文标题与正文，零 Agent 署名，**一律不执行 `git push`**

## 当前状态（跨会话恢复锚点）

- **当前阶段**：**第一档 `#1`–`#3` + 第二档 `#4`–`#7` 全部收口完成**（已按用户 token 预算止损指令暂停全部付费模型自举与冒烟调用，零活跃 LLM 任务）
- **当前 runner 分支与提交**：`df/runner`（已同步 `master @ 69768003` 含 `#1`–`#6`、多会话看门狗、前后端拆分 + `T005`/`T007`/`T009` + `X2-preflight.md`；端口 `9999` `PID 38584` + Electron 桌面端 `PID 38594` 在线空闲）
- **主工作区基线**：`/Users/smt/Desktop/pilot` (`master @ 69768003`，干净冻结)
- **已完成任务数**：8（`T001` L1 10/10、`T006` L3 10/10、`T002` L1 10/10、`T003` L1 10/10、`T004` L1 9/10、`T005` L1 10/10、`T007` L2 10/10、`T009` L2 9/10，均分 **9.75/10**，接管率 0%）+ X1 3v3（6 样本）+ M01 金丝雀（24/24 冒烟 + 3/3 历史重跑）+ `R-03` X2 离线 10 任务审计与预算止损实机归档（`experiments/X2-preflight.md`）
- **进行中任务**：无（已严格停损，零活跃外部模型调用）
- **未关闭 P0/P1 缺陷**：**0**（`D-012`/`D-014`/`D-015`/`D-016`/`D-017`/`D-019`/`D-020`/`D-021`/`D-022`/`D-023`/`D-024`/`D-026`/`R-01` 共 **13 项全部 V4 关闭**！）
- **最近金丝雀回归**：**`M01`（2026-11-21，`runner @ 855b0047`）**——24 题冒烟 **24/24（100%，p50 27.3s，缓存 85.2%）** + 3 历史任务 **3/3 零回退**（报告见 `milestones/M01.md`）
