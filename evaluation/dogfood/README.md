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

- **当前阶段**：Stage 3.1（基础期：L1/L2 夯实 + S2/S4 + M01/M02 金丝雀回归）+ `#7`（`D-020`–`D-026` V4 收口 + `R-03` X2 预定位对照）推进中
- **当前 runner 分支与提交**：`df/runner`（已同步 `master @ 9ab73386` 含 `#1`–`#6` + `D-026` + `T005` `a10421f6` + `T007` `d13147a7`；端口 `9999`，Electron 桌面端在线）
- **主工作区基线**：`/Users/smt/Desktop/pilot` (`master @ 9ab73386`，干净冻结)
- **已完成任务数**：7（`T001` L1 10/10、`T006` L3 10/10、`T002` L1 10/10、`T003` L1 10/10、`T004` L1 9/10、`T005` L1 10/10、`T007` L2 10/10，均分 **9.86/10**，接管率 0%）+ X1 3v3（6 样本）+ M01 金丝雀（24/24 冒烟 + 3/3 历史重跑）
- **进行中任务**：派发 **`T009`**（L2 S4 #2 `ProjectLint` 圈复杂度治理，完成后 **`D-026` 达 3/3 V4 关闭**）→ **`X2`（`R-03` Pre-flight 3v3 对照）**
- **未关闭 P0/P1 缺陷**：0 个待修 P0/P1（已 V4 关闭 `D-012`/`D-014`/`D-015`/`D-016`/`D-017`/`D-019`/`D-020`/`D-021`/`D-022`/`D-023`/`D-024`/`R-01` 共 12 项；仅剩 `D-026` 2/3 待本轮收口）
- **最近金丝雀回归**：**`M01`（2026-11-21，`runner @ 855b0047`）**——24 题冒烟 **24/24（100%，p50 27.3s，缓存 85.2%）** + 3 历史任务 **3/3 零回退**（报告见 `milestones/M01.md`）
