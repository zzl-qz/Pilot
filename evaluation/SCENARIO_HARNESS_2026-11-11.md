# 真实模型场景验证：离线基建与执行预案（2026-11-11）

## 合入说明（2026-11-12）

本文件保留原离线方案与当时的验证范围。合入后 HTTP 读取统一使用 `BoundedHttp`，以 `PILOT_MODEL_TIMEOUT_SECONDS` / `PILOT_MODEL_IDLE_TIMEOUT_SECONDS` 控制总时限与空闲时限；不再保留另一组流式毫秒配置。仅收到 `[DONE]` 而无有效 `finish_reason` 仍判未完成，不能把传输结束当作业务输出已确认完整。下文旧付费预算及待批准描述仅属于原方案；本轮用户明确授权免费本地模型，当前执行记录另见本轮验收报告。

## 结论

本轮只交付**离线评测基建**，未发起任何付费模型调用。已按确认范围实现并验证：流式故障注入代理、三个场景任务集（等待唤醒 / 上下文增长 / 预算收尾，均满足"初始必失败、参考解必通过"）、执行器与指标的故障场景扩展。付费矩阵（首组网络错误，8 任务 × 3 次，上限 ¥150）待本方案评审通过后执行。

## 一、构建与验证证据

| 交付物 | 位置 | 验证 |
| --- | --- | --- |
| 故障注入代理 | `evaluation/harness/support/fault-proxy.mjs` | 13 项单元测试全绿：干净转发逐字节一致、mid-eof 不发 [DONE]、stall 拖满时长、503 不打上游、reset 立断、every 节奏、控制接口、maxRequests/maxQuota 硬停（stopped 粘滞 reset 不洗）、日志与状态不泄漏上游密钥 |
| 场景任务集 | `evaluation/harness/scenarios/` | reference-verifier：`wait-condition` 初始 2/3 失败→参考解 3/3；`long-chain` 初始 17/18 失败→参考解 18/18；`budget-wind` 初始 2/3 失败→参考解 3/3。主任务集复跑 24/24 不受影响 |
| 执行器扩展 | `evaluation/harness/runner.mjs` | `--fault-proxy/--fault-profile/--fault-every/--fault-stall-ms/--fault-max-requests/--seed`；轮换索引 `seed+任务位置×repeat+(attempt-1)` 可复算且全计划双射、同任务多次尝试轮遍不同 profile；未注入 → `scenarioCovered=false`；resume 校验故障方案一致性 |
| 指标扩展 | `evaluation/harness/metrics.mjs` | 新增 `faultInjectionCoverage / faultSurvivalRate / injectedFaultCount / faultProxyStops`；故障组与干净基线互不可比（insufficient）；混合方案、缺请求数观测直接拒绝 |

全套 harness 测试 `node --test evaluation/harness/*.test.mjs evaluation/harness/support/*.test.mjs`：**91 项，90 通过，1 项既有环境跳过**（reference-verifier 符号链接用例，Windows 账户无权限，与本次无关）。

### 场景设计要点（反作弊）

- **wait-condition**：`scripts/` 与 `data/` 受 SHA256 守卫；`src/state.json` 须由检查生成且 `waitedMs >= 7000`；验收环境 `PILOT_EVAL=1` 快进重跑同一检查核对阈值。跳过等待 / 手写 state / 改脚本均失败。
- **long-chain**：16 模块重构，开局约束（不引依赖、不建顶层条目、保留 legacyFormatter、断言不改）核到结束；模型请求数低于 `scenarioMinModelRequests: 8` 判场景未覆盖。
- **budget-wind**：`data/` 输入 SHA256 守卫；progress 台账 done/remaining 恰好划分 40 项且至少完成 6 项；done 必须有合规输出、remaining 不得有输出（台账不诚实即失败）；全部完成反而判场景未覆盖（未经历预算耗尽）。

## 二、付费组一执行预案（网络错误，待批准）

**目标**：实证两处修复（流提前 EOF 不再补成完成、body 读取看门狗超时断流）在真实模型下的行为：注入故障后任务是否仍正常收尾、外部验收是否通过、部分回复是否不再冒充完成。

**预算**：本组上限 **RMB 150**，本轮总预算 RMB 500。双重硬停：故障代理 `PILOT_FAULT_MAX_QUOTA`（按配置全价折算的 quota 上限）与 `PILOT_FAULT_MAX_REQUESTS`（绝对请求数上限），任一触发 402 停机且 `stopped` 粘滞、`reset` 不清洗。估算参考：FLOW_EVAL 观测单次尝试约 102k 输入 token；24 次尝试 × 故障重试放大（every=3 时约 1/3 请求中途被断，上游已消耗输入与部分输出，按 1.5 倍放大）× 1.5 余量 ≈ 5.5M 输入 token 全价，远低于上限；执行时按实际单价配置换算。

**步骤**（全部离线核对后才切付费）：

1. 启动故障代理（不碰真实上游密钥以外的任何配置）：
   - `PILOT_FAULT_UPSTREAM=<现 BASE_URL 的 origin>`、`PILOT_FAULT_API_KEY=<现 API_KEY>`
   - `PILOT_FAULT_INPUT_QUOTA_PER_MILLION / OUTPUT_QUOTA_PER_MILLION=<供应商全价>`、`PILOT_FAULT_MAX_QUOTA=<¥150 折算>`、`PILOT_FAULT_MAX_REQUESTS=1500`（24 次 × ~15 请求再放裕量）
   - `PILOT_FAULT_LOCAL_TOKEN=<随机值>`；`PILOT_EVAL_PROXY_PORT` 固定以便复算。
2. 复用既有 Pilot 服务，`BASE_URL` 改指 `http://127.0.0.1:<port><原路径>`；**主模型固定不混跑；运行期间该服务不得有其他会话**（代理计数是全局的，其他会话的模型请求会混入 `injectedRequests`，无法归因）。
3. 8 个任务（覆盖修复/实现/重构/重命名/安全/长文件/拆分/中途引导）：`fix-failing-unit-test,async-race-condition,add-retry-backoff,long-file-targeted-edit,rename-across-files,path-safety-regression,split-large-module,steer-mid-run`。
4. 执行命令（固定 seed 便于复算与 resume）：

```bash
node evaluation/harness/runner.mjs --base http://127.0.0.1:8787 \
  --tasks evaluation/harness/tasks.json \
  --task fix-failing-unit-test,async-race-condition,add-retry-backoff,long-file-targeted-edit,rename-across-files,path-safety-regression,split-large-module,steer-mid-run \
  --fault-proxy http://127.0.0.1:<port> --fault-profile mid-eof,stall,http-503,reset \
  --fault-every 3 --seed 20261111 --repeat 3 --timeout-minutes 3 \
  --prompt-version P0 --tool-version T0 --environment windows11-java25-node22-fault-every3 \
  --out evaluation/harness/runs/network-faults-2026-11-11.jsonl
```

条件与 FLOW_EVAL 对齐：35 步 / 180 秒活跃预算、浏览器/skills/多 Agent 关闭、`--timeout-minutes 3`。中断用 `--resume` 补缺失轮次（同 seed 重算轮换；已记录失败不被替换）。

**组门禁**（通过后才扩展下一组）：
- 24/24 尝试具有 `faultRequestCount` 观测且 `faultInjectedCount >= 1`（注入未覆盖的尝试需查明原因并补齐——同 seed 下只补缺失轮次）；
- 24/24 外部验收实际执行；
- 无未解释的 `evaluationError` 基础设施故障（保留为失败观测，不得重跑替换）；
- 按 profile 报告 `faultSurvivalRate`、故障后模型重试数、`mid-eof/stall` 下是否出现"部分回复被当完成"（预期：修复后不出现，所有截断均以可重试错误暴露并恢复）；
- 本组只报告绝对存活率与逐任务结果；与干净基线的统计比较在后续干净基线组完成后单独报告（故障组与干净组互不可比是设计内规则，用于归因而非放行）。

## 三、边界（不声称）

- 24 项为小型 Node fixture；不能推断大型多语言仓库、浏览器真实环境或 Windows 之外的实机表现。
- `resolvedModel` 服务未暴露，仍记录为 `null`；模型身份按端点报告口径。
- 代理花费估算只统计有 usage 观测的请求；被注入请求计入 `requestsWithoutUsage`，最终费用以供应商账单对账为准。
- 故障存活率是首轮测量值，本身构不成能力结论；跨组统计比较需后续按序补齐各组样本。

## 四、复核命令

```bash
node evaluation/harness/reference-verifier.mjs                                     # 主任务集 24/24
node evaluation/harness/reference-verifier.mjs --tasks evaluation/harness/scenarios/tasks.json   # 场景集 3/3
node --test evaluation/harness/*.test.mjs evaluation/harness/support/*.test.mjs   # 91 项：90 过 + 1 环境跳过
```

> AI生成
