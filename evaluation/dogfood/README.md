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

- **当前阶段**：Stage 3.1（基础期：L1/L2 夯实 + S2/S4 + M01/M02 金丝雀回归）推进中（已完成 `T001`/`T006`/`T002`/`T003`/`T004` 共 **5 项**，到达首次里程碑 **`M01`**，正执行金丝雀回归 + 24 题冒烟）
- **当前 runner 分支与提交**：`df/runner`（已同步 `master @ 2ca6f137`、合入 `T004` `d89454f6`、`D-024` `2a936ff8`、Spotless 对齐 `074d9cd6`、`AgentLoopTest` 对齐 `b76de1f0`、`SourceLayout` 包依赖修复 `0ebb1575`，全量 V1 2,130 用例全绿；端口 `9999`，Electron 桌面端在线）
- **主工作区基线**：`/Users/smt/Desktop/pilot` (`master @ 2ca6f137`，干净冻结)
- **已完成任务数**：5（`T001` L1 10/10 需纠偏 `2a020f1d`；`T006` L3 10/10 一次通过 `e342b1a4`；`T002` L1 10/10 含 S2 #1 `f1646c33`；`T003` L1 无路径 10/10 一次通过 `d111480f`；`T004` L1 S4 #1 9/10 一次通过 `d89454f6`）+ X1 3v3 完整 6 样本对照
- **进行中任务**：执行第 5 任务里程碑 **`M01` 首次金丝雀回归**（3 历史任务原基线重跑 + 24 题冒烟 ≥23/24）并输出 `milestones/M01.md` → 续推 `T005`/`T007`/`T008`/`T009`/`T010`
- **未关闭 P0/P1 缺陷**：0 个待修 P0/P1（**`D-012`/`D-014`/`D-015`/`D-019`/`R-01` 已达 V4 3/3 正式关闭！** `D-020`/`D-021` 2/3，`D-016`/`D-022`/`D-023` 1/3，`D-024` 0/3）
- **最近金丝雀回归**：即将启动首次金丝雀 `M01`
