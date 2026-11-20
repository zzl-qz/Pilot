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

- **当前阶段**：Stage 1 已全部完成（1.1–1.4 全部验收通过）；立即进入 **Stage 2.1（X1 3 对 3 A/B 复核）** 与 **Stage 2.2（R-01～R-04、D-012、D-013，及优先修复高影响新缺陷 D-014/D-015）**
- **当前 runner 分支与提交**：`df/runner` 已合入 `T001` 产品提交 `2a020f1d`（服务运行中：端口 `9999`，模型 `deepseek-v4.1-flash-1017`，`PILOT_DEBUG_DUMP_MODEL_REQUESTS=true`，Electron 桌面窗口在线）
- **主工作区基线**：`/Users/smt/Desktop/pilot` (`master` @ `cdbe4aa9`，干净冻结)
- **已完成任务数**：1（`T001` L1，产出得分 10/10，合入 `2a020f1d`，过程需 2 次 `/steer`）
- **进行中任务**：无（下一阶段：Stage 2.1 X1 3v3 A/B 对照）
- **未关闭 P0/P1 缺陷**：`D-013`（固化 V1 全量门禁）、`D-014`（调研瘫痪 5/5 重放复现）、`D-015`（`CommandChain` 双引号 `\"` 解析 Bug）；`D-001`～`D-011` 待 X1 3v3 复核；待复核疑点 `R-01`～`R-04`
- **最近金丝雀回归**：尚未执行（计划于第 5 任务 `M01` 执行首次金丝雀）
