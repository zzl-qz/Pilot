# 真实模型任务评测 · 2026-11-16

> 对应 issue：#IKVGSB
> 执行计划：[E1_REAL_MODEL_EVAL_PLAN.md](E1_REAL_MODEL_EVAL_PLAN.md)
> 原始记录：`harness/runs/e1-baseline.jsonl`、`harness/runs/e1-retry.jsonl`（已被 `.gitignore` 忽略，只在本机）

## 一句话结论

**8 个任务 × 3 次重复共 24 次运行，24 次全部通过外部验收。** 其中 4 次是补跑——首轮的 4 次尝试因模型端点上游故障中断（不是能力失败），端点在 19 分钟窗口外恢复后原样重跑，全部通过。

## 跑的是什么

| 项 | 值 |
| --- | --- |
| 模型 | `gpt-6-sol`（经第三方代理 `tokenmarket.cheap`） |
| 提示词版本 | `P0` |
| 工具版本 | `T0` |
| 权限档位 | `AUTO`（不产生审批阻塞） |
| 能力探测 | 关闭（`capability-discovery.enabled=false`，避免后台探测混入请求计数） |
| 主机 | Windows 11 / JDK 25 |
| 任务集 | `evaluation/harness/tasks.json` 的 24 个中取 8 个，覆盖 6 个任务族 |

选中的 8 个：

| 任务 | 族 | 结果 |
| --- | --- | --- |
| `fix-failing-unit-test` | 缺陷修复 | 3/3 |
| `async-race-condition` | 并发正确性 | 3/3 |
| `add-validation` | 加功能 | 3/3 |
| `add-idempotency` | 加功能（领域约束） | 3/3 |
| `refactor-without-behavior-change` | 重构 | 3/3 |
| `rename-across-files` | 跨文件改动 | 3/3 |
| `path-safety-regression` | 路径安全 | 3/3 |
| `frontend-form-a11y` | 前端与可访问性 | 3/3 |

## 验收是外部验收，不是模型自述

每次尝试结束后，执行器把候选代码复制到独立目录、**还原原始测试**，再跑 `node --test`。删改断言不能制造成功。`success` 同时要求：运行终态 `COMPLETED` **且** 外部验收通过。

## 首批 24 次：20 通过，4 次被上游打断

| 尝试 | 终态 | 外部验收 | 模型请求 | 重试次数 | 耗时 |
| --- | --- | --- | --- | --- | --- |
| `refactor-without-behavior-change` #3 | FAILED | **通过** | 31 | 19 | 620s |
| `rename-across-files` #3 | FAILED | 失败 | 20 | 19 | 402s |
| `path-safety-regression` #3 | FAILED | 失败 | 20 | 19 | 369s |
| `frontend-form-a11y` #3 | FAILED | 失败 | 20 | 19 | 402s |

四次都是 `failureCode=MODEL_ERROR`，且**重试次数一律打满 19 次**：这是模型端点在那段时间不可用，不是模型做错了。服务端日志对应时刻：

```
08:58:10  Agent run ... completed: failureCode=MODEL_ERROR
09:04:54  Agent run ... completed: failureCode=MODEL_ERROR
09:11:03  Agent run ... completed: failureCode=MODEL_ERROR
09:17:45  Agent run ... completed: failureCode=MODEL_ERROR
```

值得单独指出的是第一行：`refactor-without-behavior-change` #3 **外部验收其实是通过的**——活干完了，只是运行没能正常收尾。把它算成「能力失败」会是错的。

### 补跑

端点恢复后用同一个模型、同一批任务原样重跑这 4 次：

```
▶ refactor-without-behavior-change ... PASS
▶ rename-across-files              ... PASS
▶ path-safety-regression           ... PASS
▶ frontend-form-a11y               ... PASS
成功率 100%（4/4）
```

补跑批次的环境标签多了 `retry-batch` 后缀，**不能和首批混在一起做跨模型对照**；这里只把它当作「同一任务合同、同一版本、被上游打断后重跑」的证据。

## 成本

| 项 | 值 |
| --- | --- |
| 输入 token（28 次尝试合计） | 6,386,703 |
| 输出 token | 37,780 |
| 其中缓存命中输入 | 2,241,280 |
| 金额 | **未知** |

服务端没有返回价格字段，`costUsd` 全部为 `null`、`costKnown=false`。按项目口径，未知就不填 0——**这里不给「每次成功任务成本」**，只给 token 作为可比代理。

一个观察：单次请求的 `prompt_tokens` 底噪约 4.4k（同一句「回复 ok」也要 4388 输入），说明该代理在请求前追加了自己的内容。这会让 token 成本显著高于直连官方端点，比较成本时必须带着这个前提。

## 耗时

| 指标 | 值 |
| --- | --- |
| 单次执行 p50 | 80.6s |
| 单任务 p50（3 次中位） | 40s（`path-safety-regression`）～ 127s（`add-idempotency`） |
| 最长 | 620s（即那次重试打满的上游故障） |

## 这些数字不能说明什么

1. **不能外推到全部 24 个任务**：只跑了 8 个，另外 16 个（含 `steer-*` 两个中途改要求的场景）未测。
2. **不能外推到模型本身**：端点是一个会改写请求的第三方代理，结果描述的是「这个代理 + 这个模型」这一组合。
3. **不能说明泛化**：8 个任务都来自同一个固定任务集，且此前用参考解验证过可解（24/24），这只能排除「任务本身无解」。
4. **不能说明长期稳定性**：24 次运行集中在约一小时窗口内，且中途出现过一次上游不可用。
5. **不能当作安全结论**：权限档位用的 `AUTO`，没有触发审批路径。

## 与产品主线的关系

这一轮补上了此前最大的空洞：以前只有「验收器有分辨力」（夹具初始失败、参考解通过），**没有任何数字说明真实模型能不能干完活**。现在有了：在固定任务集、固定版本、外部验收下，24/24 通过。

它同时验证了另一件事——**「完成」不等于「验收通过」**：那次 `refactor` 尝试外部验收通过但运行终态是 FAILED，如果只看模型自述或只看测试结果，都会得出相反结论。这恰好是产品要做「运行终态、计划完成、人工验收」三态分离的理由。

## 下一步

- 补齐剩余 16 个任务的同条件运行（预计 token 约 1,300 万）；
- 端点在跑批期间不稳定，正式对照需要更稳的通道，或在批次层面记录中断并单独补跑；
- 需要价格数据才能给出「每个成功任务成本」，否则只能报告 token。