# Pilot 自举缺陷台账（Defects & Review Ledger）

> 状态流转：`新建` → `定位中` → `已修复待复验`（已过 V1）→ `观察中`（已过 V2/V3，等待 V4 连续 3 个触发条件相同的任务零复现）→ `已关闭`（V4 达标）。
> 根因分层优先级：1. 工具设计 → 2. 循环策略 → 3. 上下文与缓存 → 4. 运行时/基础设施 → 5. 提示词（最后手段）→ 6. 任务描述歧义 → 7. 模型能力上限。

## 一、历史种子缺陷（D-001～D-013）

| 编号 | 严重度 | 类别 | 根因层 | 简要描述 | 修复提交 | 证据与验证状态 | 当前状态 |
|---|---|---|---|---|---|---|---|
| D-001 | P1 | A 定位低效 | 工具设计 | 读取/搜索/符号大纲信息密度低导致重复翻页与低效定位 | `35ce5d84` | V1+X1 3v3 已证实（shell 绕路中位 159→5，-97%） | 观察中（待 V4） |
| D-002 | P1 | H 缓存抖动 | 上下文/缓存 | 上下文预算按字符粗算且逐条裁剪破坏前缀缓存 | `1179e5c7` | V1+X1 3v3 已证实（稳态缓存中位 70.4%→97.9%，压缩 17→1，未命中 token -77.2%） | 观察中（待 V4） |
| D-003 | P1 | I 压缩爆炸 | 上下文/缓存 | `ContextPins` 递归嵌入运行时提醒与旧元数据导致膨胀 | `2f68dc32` | V1+X1 3v3 已证实（基线压缩 85k→75k 失效，实验组仅压缩 1 次即稳态） | 观察中（待 V4） |
| D-004 | P1 | F 翻页套娃 | 工具设计 | 长命令输出首屏缺报错与首尾摘要，且归档引用二次套娃 | `d424f954` | V1+X1 3v3 已证实（未再出现翻页套娃） | 观察中（待 V4） |
| D-005 | P2 | G 审查重复 | 循环策略 | 独立代码审查已放行后仍追加重复自审，且检查失败未列失败命令 | `ad328a91` | V1+X1 3v3 已证实（待 X5 专项复核） | 观察中（待 X5） |
| D-006 | P1 | H 工具前缀漂移 | 上下文/缓存 | 启用延迟工具打乱核心工具排序导致前缀失效，续跑丢失已启用工具 | `82d379b1` | V1+X1 3v3 已证实 | 观察中（待 V4） |
| D-007 | P1 | A/B 调研低效 | 循环策略/上下文 | 缺少新消息前预定位候选文件与按信息增益的调研提醒 | `79499a7d` | V1+X1 3v3 显示仍存在首次编辑前 16–36 轮空转，已拆出 D-014 根治；待 X2/X3 专项对照 | 观察中（见 D-014） |
| D-008 | P1 | H 前缀摘要误判 | 上下文/缓存 | `ContextCompactor.summarize()` 将历史总字符数直接与 token 预算比较，误判放不下前缀摘要 | `385f4032` | V1+X1 3v3 已证实 | 观察中（待 V4） |
| D-009 | P2 | D 编辑噪音 | 工具设计 | `ReuseScan` 将流式 setter/方法体误报为重复工具函数，干扰编辑收尾 | `385f4032` | V1 通过；待 V4 观察 | 观察中 |
| D-010 | P2 | F 日志翻页 | 工具设计 | `ToolExecutor` 误截断用户显式请求的 `command_logs` 小切片（<= 8192 字符） | `385f4032` | V1 通过；待 V4 观察 | 观察中 |
| D-011 | P2 | E 核验误读 | 工具设计 | `CommandToolSupport`/`RunCommandTool` 命令摘要仅截前 120 字符，丢弃末尾目标测试类名 | `385f4032` | V1 通过；待 V4 观察 | 观察中 |
| D-012 | P2 | C 临时文件遗留 | 工具设计/运行时 | Pilot 运行过程可能在工作区遗留 `.tmp-verify/*` 等未清理临时文件（已在历史 `ses_c502a2ef` 轨迹确认：Pilot 用 `mkdir -p .tmp-verify` 暂存回退文件） | `8d587064` | `CompletionGate` 新增本轮残留 `.tmp/.tmp-*/.tmp_*` 及 `.tmp/.bak/.orig/.rej` 清理门禁且不标脏已通过测试；V1 全绿（`CompletionGateTest` 37/37 + 全量 1091） | 观察中（待 V4） |
| D-013 | P1 | E 验证缺失 | 流程/循环策略 | 改动工具或规则后未跑全量 `mvn test`，漏改历史断言（如 `8025dfad`/`10a7b2f3` 遗留 4 处单测失配） | `cdbe4aa9` / `43b541ac` | 已固化 `runner-ctl.mjs v1-verify`（全量 `pilot-core,pilot-server` `test` + `spotless:check` + `checkstyle:check` + `quality-gate.mjs`），并在 D-015/R-01/D-012/D-014/D-019 连续 5 次落地强制执行 | 观察中（待 V4） |
| D-014 | P1 | B 调研瘫痪 | 循环策略 | `T001`（36 行 L1 文件）在 req 1–4 已读完核心文件后仍持续只读 17 轮 / 34 次工具调用不编辑；`req-11.json` 带 `[调查收敛提醒]` 重放 5/5（100%）仍不编辑 | `c405e8e8` | 跨任务 V2 5x 重放通过（`T001 req-11` `5/5 → 0/5`，`X1-T3 req-16` `5/5 → 1/5`）；全量 V1 全绿（`CacheWireTest`/`CacheContextTest`/`InvestigationTrackerTest`/`GoalExecutionPolicyTest` + 1091）；待 V3 重启与 V4 观察 | 观察中（待 V3/V4） |
| D-015 | P1 | F 命令解析 Bug | 工具设计 | `CommandChain.parse()` 在双引号内未处理 `\"` 转义，导致 `grep -l "failures=\"[1-9]\|errors=\"[1-9]"` 提前闭合引号并误报 `不允许通过命令前缀设置 errors` | `d196d69d` | 同步修复 `CommandChain.parse()`（含重定向目标）与 `RunCommandTool.tokenize()`，跨平台兼容 Windows 路径与正则 `\|`；V1 全绿（`CommandChainTest` 7/7 + 全量 1090） | 观察中（待 V4） |
| D-016 | P1 | E/B 沙箱嵌套排错空转 | 工具设计/运行时 | macOS Seatbelt / Linux bwrap 沙箱内跑全量测试触发嵌套沙箱 `Operation not permitted`（退出码 71 等）时无环境提示，导致 T001 与 T002 连续各白耗 22–25 轮追查无关沙箱测试 | `409a291d` | `CommandToolSupport.nestedSandboxHint()` 确定性识别 macOS Seatbelt 与 Linux bwrap 嵌套沙箱失败并首屏输出 `[环境提示]`（覆盖 `RunCommandTool`/`CommandStatusTool`/`CommandLogsTool`），不削弱沙箱隔离；V1 全绿（1092 用例） | 观察中（待 V3/V4） |
| D-017 | P2 | C 评测泄露风险 | 自举工具 | 任务 worktree 中 `search_text` 命中 `evaluation/dogfood/tasks/`，可能暴露 S4 剧本答案 | `eb5e0a85` | S4 答案已迁至仓库外 `~/pilot-dogfood/supervisor-s4-scripts.md` | 已关闭 |
| D-018 | P2 | G 计划门禁多余续跑 | 循环策略 | 验证已全部通过且模型给出完整答复时，仅因未把 plan 末项标 `completed` 被 `PlanGate` 触发 `RUN_CONTINUED` 多跑 3 轮与重复测试 | 待评估 | `T001` seq 707–716 复现，观察后续任务复现频率 | 新建 |
| D-019 | P2 | A 预定位超时丢分 | 运行时/工具设计 | `PreFlightLocator.scanGitFileNames()` 对 `git ls-files` 全仓文件先做约 4,500 次磁盘校验再匹配文件名，高负载下 120ms 超时丢掉 `+90` 定义文件加分 | `e203e19a` | 改为先纯内存 `matchesAnyFileOrStem()` 初筛再校验命中候选并将路径子预算放宽至 200ms；实测 `PreFlightLocatorTest` 300ms→127ms、`InvestigationDisciplineVerificationTest` 2.10s→0.92s；V1 全绿（1092 用例） | 观察中（待 V4） |
| D-020 | P1 | L 非法工具参数 JSON 误杀运行 | 运行时/模型适配 | `T002`（`ses_acfeb9f3` req 70）实测暴露：模型偶发返回非合法 JSON 工具调用参数时，适配器将其抛出为 `PERMANENT` 直接终结 70 步任务 | `a91bb586` | `ModelFailureKind` 新增 `MALFORMED_TOOL_CALL(retryable=true)`，`AgentLoop` 最多 3 次带 JSON 修正提示的有界重试；V1 全绿（108 定向 + 全量 1092） | 观察中（待 V3/V4） |

## 二、待复核疑点（R-01～R-04）

| 编号 | 关注点 | 涉及位置 | 复核计划 | 当前状态 |
|---|---|---|---|---|
| R-01 | `GoalExecutionPolicy.researchOnlyGoal()` 使用关键字表匹配用户原话，存在过拟合与误判风险 | `pilot-core/.../GoalIntent.java`、`pilot-server/.../GoalExecutionPolicy.java` | 已在 `X1-T3` 轨迹实测证实误杀（提示词含“避免只读 `configured()`”被误判为只读分析任务）；已于 `c411bfe3` 改为结构性信号优先（`readOnlySession` + `availableTools` 写能力）+ 尊重 steering + 与 `CompletionGate` 共享 `GoalIntent`，V1 全绿 | 已修复观察中（待 V4） |
| R-02 | `ContextBudget` 清理水位、滞回区间与冷却参数是否最优 | `pilot-core/.../ContextBudget.java` | X1 3v3 实测验证：80% prune / 90% pre-compact / 16% cooldown 在 106–120 轮 L3 任务全程仅压缩 1 次、稳态缓存命中 97.7%–98.1%、未命中 token 降 77.2%，按数据决定维持当前水位不调参 | 已核查保留 |
| R-03 | Pre-flight 预定位默认注入范围、命中质量与噪声比 | `pilot-server/.../AgentRunService.java` / `PreFlightLocator` | 已顺带修复 `D-019` 扫描热点；Stage 3.2 继续通过 X2 3v3 开关对照验证注入净收益 | 待 X2 复核 |
| R-04 | `workflow.md` 新增指导引入的 system prompt token 增量及是否存在冗余/矛盾表述 | `pilot-core/src/main/resources/prompts/.../workflow.md` | 量化核查（`0caa2257..HEAD`）：`workflow.md` 从 `2,350` 字符 / `6,012` B / `~2,327` estTokens 增至 `2,914` 字符 / `7,400` B / `~2,860` estTokens，净增 `+564` 字符 / `+533` estTokens（占全部 `system/*.md` `7,676→8,208` 约 `+6.9%`，占 55k+ 稳态缓存前缀 `<1%` 且首轮后 ~98% 命中缓存）；且新增「首轮最小证据集」删掉了旧版制造调研瘫痪的“缺一项就先搜索或读取确认”矛盾句。按“无因果重放证据不盲动提示词”原则保留现状 | 已核查保留 |

## 三、自举过程中新发现的产品缺陷（转化为真实任务卡）

| 编号 | 发现来源 | 简要描述 | 对应任务卡 | 状态 |
|---|---|---|---|---|
| P-001 | Stage 1.1 实机切换 `runner/` 启动日志 | 显式设置 `PILOT_DATA_DIR` 后，`BrowserHome` 仍强制使用仓库 `.pilot/browser`，触发 400MB Chromium 重复下载且桌面端点路径分离 | `T001` | 已由 Pilot 修复并合入 `df/runner`（`2a020f1d`） |
| P-002 | Stage 1.1 实机切换 `runner/` 启动日志 | 显式设置 `PILOT_DATA_DIR` 后，`BundledRuntime` 未接入 `PilotDataDir`，仍向当前 worktree `.pilot/runtime` 重复下载 Python 3.12 | `T002` | 待派发 |
| P-003 | Stage 1.1 实机运行 `java -jar pilot-server` 启动日志 | 打包后的 Spring Boot 4 嵌套 jar 启动时仍触发 `FrontendDepsPrompt` 20 秒「是否更新前端依赖」询问，违背“仅开发态提示”契约 | `T003` | 待派发 |
