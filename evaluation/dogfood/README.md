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

- **当前阶段**：Stage 2.1（X1 3 对 3 A/B 复核）已完成并合入最新 `master` UI（`7921812e`）及 `T006` 产出（`e342b1a4`）；即将执行 **Stage 2.2（R-01 / R-04 / D-012 / D-013 与核心 P1 D-014 / D-015 修复及四级验证）**
- **当前 runner 分支与提交**：`df/runner`（已同步 `master` @ `7921812e` 最新前端 UI 并合入 `T001`/`T006`；服务端口 `9999`，模型 `deepseek-v4.1-flash-1017`，`PILOT_DEBUG_DUMP_MODEL_REQUESTS=true`，Electron 桌面窗口在线）
- **主工作区基线**：`/Users/smt/Desktop/pilot` (`master` @ `7921812e`，干净冻结)
- **已完成任务数**：2（`T001` L1 10/10 需纠偏合入 `2a020f1d`；`T006` L3 10/10 一次自主通过合入 `e342b1a4`）+ X1 3v3 完整 6 样本对照（实验组 3/3 自主通过、97.9% 稳态缓存命中 vs 基线组 0/3 超时、70.4% 缓存命中）
- **进行中任务**：Stage 2.2 疑点核查与 P1 缺陷修复（`D-014` 调研瘫痪 V4 落地、`D-015` `CommandChain` 转义解析、`D-012` 未清理临时产物门禁、`R-01`/`R-04` 核查）
- **未关闭 P0/P1 缺陷**：`D-014`（P1，V2 5x 重放已证明 5/5→0/5，待编码+V1+V3）、`D-015`（P1，待修+单测）；`D-013` 已固化 `runner-ctl.mjs v1-verify`；`D-001`～`D-011` 经 X1 验证转入 V4 观察期；`R-02` 已由 X1 实测确认保留
- **最近金丝雀回归**：尚未执行（计划于第 5 任务 `M01` 执行首次金丝雀）
