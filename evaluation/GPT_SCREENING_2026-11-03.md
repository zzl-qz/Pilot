# GPT 提示词小规模筛选（2026-11-03）

## 结论

**原计划 36 次未完成，最终收敛为 6 项任务 × 3 个版本 × 1 次，共 18 次，保留全部失败。** P0 通过 5 / 6，P1 通过 6 / 6，P2 通过 5 / 6，共 16 次通过；未执行第二轮重复。P0→P1 门禁为 `insufficient`；P0→P2 因验收覆盖下降被 `reject`，同时样本与重复次数不足。这不构成统计证明 P2 能力退化，也不能据此选择 P1 为最佳版或声称全部功能通过。

过程中发生上游错误及额度查询接口限流；后续在保留未确认预留的前提下继续固定计划，并按[范围调整记录](harness/baselines/2026-11-03-gpt-screening/scope-update.json)完成第一轮。最终本地保守可用预留仍有 667,050 quota，**不是资金用光**。最终核对消耗为 130,806 quota，按用户确认的后台人民币口径折合 **¥0.261612**，包含正式评测、校准和预检。P0 默认提示词保持不变，候选未推广，本轮未提交代码。

## 条件与样本

采用 macOS / arm64、Java 25、Node 22，接口配置及单次预检返回模型名均为 `gpt-6.1-sol`；这只是端点报告的身份，未独立验证其上游模型，逐次记录的 `resolvedModel` 为 `null`。三版使用同一新运行时与同一 JAR，只替换 P0/P1/P2 提示词；这里的 P0 是“新运行时 + 原默认提示词”，不能直接等同于[此前 DeepSeek 对照](FLOW_EVAL_2026-11-03.md)中的旧 P0 服务。

| 控制条件 | 设置 |
| --- | --- |
| 调度 | 单任务串行，固定种子 `20261103` 打乱任务顺序，同任务三版轮换出场顺序 |
| 任务与验收 | 新会话、新 fixture 副本；外部验收恢复原始测试，参考解不提供给模型 |
| 执行限制 | 35 步、Direct 活跃时间 180 秒；Harness 墙钟截止 180 秒；单次输出上限 4,096 token |
| 工具 | 浏览器、skills、多 Agent 关闭 |
| 传输 | 预算代理缓冲响应后转交，账单查询和代理等待计入观测耗时 |
| 运行时指纹 | `b15a1c2b24565c294d65da28220bac6618a726fd01ed242ade463bed213b48f6` |
| JAR SHA256 | `5dfd4348f2bd2e77a5c3b0ec675ce03bf0e1cfa3adabcfb69068e2776c49d0c4` |

完整任务列表、调度和构建标识见[原计划](harness/baselines/2026-11-03-gpt-screening/plan.json)及[运行清单](harness/baselines/2026-11-03-gpt-screening/manifest.json)。三版未改变模型、工具或输出限制；评测代理在阶段间有调整，不能忽略这一条件变化。只有 macOS 实机执行，不能推断 Windows/Linux 实机表现。

| 实际进入的任务 | P0 | P1 | P2 |
| --- | --- | --- | --- |
| `async-race-condition`，第 1 次 | 通过 | 通过 | 通过 |
| `rename-across-files`，第 1 次 | 通过 | 通过 | 通过 |
| `case-insensitive-path`，第 1 次 | 通过 | 通过 | 执行器超时错误，未验收 |
| `steer-constraint-hold`，第 1 次 | 通过 | 通过 | 通过 |
| `long-file-targeted-edit`，第 1 次 | FAILED，外部验收失败 | 通过 | 通过 |
| `fix-failing-unit-test`，第 1 次 | 通过 | 通过 | 通过 |

“通过”要求正常收尾、原始外部验收通过，以及必需的引导场景已触发。两次失败没有重跑替换，分别保留在 P0/P2 原始记录中；它们都不是正常完成并宣称验证成功的案例。

## 原始指标与覆盖范围

下表描述全部正式记录，**用量完整性并不相同**：P0 的失败样本含估算，P2 的超时样本缺失最终用量；缺失不能当零。来源：[P0](harness/baselines/2026-11-03-gpt-screening/p0.jsonl)、[P1](harness/baselines/2026-11-03-gpt-screening/p1.jsonl)、[P2](harness/baselines/2026-11-03-gpt-screening/p2.jsonl)。

| 指标 | P0 | P1 | P2 |
| --- | ---: | ---: | ---: |
| 尝试 / 通过 | 6 / 5 | 6 / 6 | 6 / 5 |
| 外部验收执行覆盖率 | 6 / 6 | 6 / 6 | 5 / 6 |
| 用量完整且标为精确的样本 | 5 / 6 | 6 / 6 | 5 / 6 |
| 记录输入 token 合计 | 515,076（含估算） | 483,797 | 465,967（仅 5 次） |
| 记录输出 token 合计 | 4,960（含非精确样本） | 5,052 | 5,209（仅 5 次） |
| 平均总耗时，含失败等待 | 54.18 秒 | 45.05 秒 | 87.47 秒 |
| 有缓存观测 / 带 usage 记录的请求 | 17 / 41（41.46%） | 33 / 41（80.49%） | 21 / 37（56.76%，仅 5 次任务） |
| 有缓存观测的输入 token | 215,648 | 390,070 | 265,605 |
| 已观测缓存读取 token | 154,624 | 237,952 | 185,472 |
| **有缓存观测子集的命中率** | **71.70%** | **61.00%** | **69.83%** |

缓存字段缺失不等于零命中。最后一行是“已观测缓存读取 token / 有缓存观测的输入 token”，**不是全部请求的命中率**；各版覆盖范围不同，不能据此评出谁缓存更好。表中的请求计数来自 Pilot usage 记录，可能包含本地拒绝，不能当成收费请求数。P2 超时现场显示的 138,356 输入由 45,036 成功请求输入加 10 × 9,332 失败估算组成，`exact=false`；不能将它补成精确收费输入或完整缓存统计。

耗时包含代理缓冲、额度查询、故障等待及外部验收；样本很少且中途调整了代理机制，不能给速度排名，更不能评价首字延迟或原生流式体验。用量含估算或缺失，输入合计也不能据此用于最终效率排名或账单节省结论。

[正式汇总](harness/baselines/2026-11-03-gpt-screening/summary.json)中，P0→P1 为 `insufficient`；P0→P2 为 `reject`，主要触发项是外部验收覆盖从 6 / 6 降为 5 / 6。两组都只有 6 项、每项 1 次，低于至少 20 项、3 次独立重复的要求。拒绝放行是覆盖不足的保守门禁，不是统计证明提示词能力退化；即使原定 36 次全部完成，仍只是筛选规模。

## 费用、阶段变化与停止原因

用户预算为人民币 ¥10，计划控制在 ¥8，即按后台口径采用 4,000,000 quota 上限。API 只提供通用货币符号 `¤`；人民币含义来自用户确认，不能声称接口独立证明了币种。费用统一以首次基准 `45,209,239` 对账，重启代理不重置预算。

| 最终对账 | quota | 按确认口径折算人民币 |
| --- | ---: | ---: |
| 预检与校准 | 8,444 | ¥0.016888 |
| 正式评测，含失败尝试 | 122,362 | ¥0.244724 |
| 合计：`45,340,045 − 45,209,239` | **130,806** | **¥0.261612** |

依据[最终费用核对](harness/baselines/2026-11-03-gpt-screening/reconciled-cost.json)，计算为 `130,806 / 500,000 = 0.261612` 个后台显示单位。逐任务账单无法可靠拆分，不给出版本金额排名或每成功任务成本。[代理停止快照](harness/baselines/2026-11-03-gpt-screening/final-budget.json)中的 `spent: 119,927` 是滞后观测，不能替代最终核对值；`pendingReserved` 是保守占用，不是已经发生的消费。

正式评测向上游发起 **122 次请求尝试**，加上校准 8 次、预检工具 `ping` 1 次，合计 **131 次上游请求尝试**。计数不证明每次均已收费，消费只能按最终 quota 整体核对；代理锁定后的本地拒绝也不算新增上游请求。[校准失败](harness/baselines/2026-11-03-gpt-screening/calibration/p0.jsonl)与[工具预检](harness/baselines/2026-11-03-gpt-screening/preflight.json)不混入正式成功率，但消费全部计入总预算。

三个阶段的中间记录均保留，未用重跑成功替换失败：

1. [第一阶段](harness/baselines/2026-11-03-gpt-screening/stage1/summary.json)完成首项任务三版各一次后，因保守预留暂止。第二阶段采用完整供应商 usage 按配置全价上界占用，携带此前 379,944 quota 未确认预留；缺失完整 usage 时保留原预留，不回收未知差额。
2. [第二阶段](harness/baselines/2026-11-03-gpt-screening/stage2/summary.json)中，P2 的 `case-insensitive-path` 遇到上游 SSE 错误，旧代理永久锁定后返回 503，引发正常暂态退避。Harness 在约 180 秒墙钟截止时取消任务，记录 `evaluationError`、未验收。现场 14 次模型调用记录为 4 次成功、1 次上游错误、9 次本地拒绝；不能都算上游消费。[失败事件](harness/baselines/2026-11-03-gpt-screening/failure-events.json)保留了证据。Direct 的 180 秒计算活跃时间，不含全部重试等待，不能据此写成产品预算失效。
3. 后续在保留失败与未确认预留的前提下继续原定下一条任务，不重跑失败样本；[第三阶段](harness/baselines/2026-11-03-gpt-screening/stage3/summary.json)将代理永久拒绝改为不可重试的 402。P0 的 `long-file-targeted-edit` 遇到 HTTP 504 后锁定，由 402 终止为 `FAILED`，部分修复未完成，外部原始测试 3 失败、2 通过，见[失败轨迹](harness/baselines/2026-11-03-gpt-screening/long-file-failure-events.json)。最终按保守预留余量收敛为第一轮 18 次，未执行第二轮。

预算协议见[配置](harness/baselines/2026-11-03-gpt-screening/budget-config.json)。预留上界依赖 usage 完整性及配置费率成立，不能宣称代理提供无条件的绝对金额保证。

## 验证与下一步

最终预算代理 **40 项 mock 测试通过**，执行文件与被测试实现的 SHA256 一致；此前三版代理合计 **92 项测试运行通过**作为历史记录保留，覆盖有重复，不能称为 132 项独立产品测试。Harness 本轮复跑 **51 项通过**。它们验证代理与执行器逻辑，不是模型能力证据。各阶段实际执行版本保存在 `*.executed.mjs`，与后续 [support](harness/baselines/2026-11-03-gpt-screening/support) 修订版的历史差异和哈希均在[运行清单](harness/baselines/2026-11-03-gpt-screening/manifest.json)注明。

可复核材料包括 [18 份候选产物清单](harness/baselines/2026-11-03-gpt-screening/candidate-manifest.json)及 [diff](harness/baselines/2026-11-03-gpt-screening/candidate-diffs)，合计 25 个文件变更记录，关联原始 JSONL 行、fixture 指纹与 diff SHA256；归档不重跑验收或修改测试。[提示词来源](harness/baselines/2026-11-03-gpt-screening/prompt-provenance.json)记录 P0 的仓库目录、P2 实验目录，以及从 P2 重建 P1 所需的完整 `system/workflow.md` 覆盖文本和期望哈希，不依赖本机临时目录存活。

下一轮先稳定上游和额度查询渠道，保留永久拒绝不可重试的处理，再用一致协议补充独立重复与未参与调优的保留任务。在这些证据齐备前，继续保留 P0 默认，不推广“最佳提示词”结论。

从仓库根目录复算现有记录无需调用模型：

```bash
node --input-type=module -e "import fs from 'node:fs'; import {summarize,gate} from './evaluation/harness/metrics.mjs'; const root='evaluation/harness/baselines/2026-11-03-gpt-screening/'; const read=p=>fs.readFileSync(root+p+'.jsonl','utf8').trim().split('\n').map(JSON.parse); const a=read('p0'),b=read('p1'),c=read('p2'); console.log(JSON.stringify({p0:summarize(a),p1:summarize(b),p2:summarize(c),p0p1:gate(a,b),p0p2:gate(a,c)},null,2));"
```

命令保留每版全部 6 条记录，包括两次失败；汇总中缺失用量不代表零消耗，本文已注明用量与缓存统计的覆盖范围。
