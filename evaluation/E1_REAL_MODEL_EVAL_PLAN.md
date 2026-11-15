# 真实模型任务评测：执行计划与记录位

> 状态：**尚未运行**（本机配置的端点为付费第三方代理，按项目既有规范「不自动消耗充值余额」，未擅自产生模型调用费用）
> 建立日期：2026-11-16
> 对应 issue：#IKVGSB

## 为什么需要这一轮

仓库里现有的结论只到「验收器有分辨力」这一步：

- `evaluation/GENERAL_AGENT_2026-11-04.md` 记的是「原始夹具 4/4 失败、参考结果通过」，说明验收能分辨真假；
- 同一份报告也写明「本轮真实 Jar 测试只输入任务草稿，**没有发送模型请求**」。

也就是说，到目前为止**没有任何数字能说明真实模型能不能完成任务**。这份计划就是为了补上这个数字。

## 已完成的离线验证（无模型调用）

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 24 个任务的夹具可解性 | `node evaluation/harness/reference-verifier.mjs --tasks evaluation/harness/tasks.json` | 24/24 同时满足「初始失败」与「参考解通过」 |
| 执行器流水线可用 | `node evaluation/harness/runner.mjs --dry-run --repeat 1 --task fix-failing-unit-test --out ...` | 正常产出记录，并明确标注不产生能力与成本结论 |

这两步证明：任务集不是自欺欺人的（初始真的失败），执行器能跑，且它不会把 dry-run 混进能力结论。

## 待执行的批次

分两段，第一段先拿到「能不能用」的粗结论，第二段才有资格谈能力。

### 第一批：有界试跑（最小可信样本）

```bash
# 1) 用独立数据目录启动接入真实模型的服务（不要用日常那份，避免混入历史会话）
PILOT_DATA_DIR=<独立目录> PILOT_MODEL_API_KEY=<密钥> \
PILOT_MODEL_ENDPOINT=<端点> PILOT_MODEL_MODEL=<模型> \
./mvnw.cmd -pl pilot-server -am quarkus:dev   # 或 java -jar pilot-server/target/*.jar

# 2) 8 个任务 × 3 次，共 24 次运行
node evaluation/harness/runner.mjs --base http://127.0.0.1:<port> \
  --model <同一模型> --repeat 3 \
  --task fix-failing-unit-test,async-race-condition,long-file-targeted-edit,frontend-form-a11y,tool-failure-recovery,steer-mid-run,steer-constraint-hold,<再一个> \
  --prompt-version P0 --tool-version T0 --environment "<OS/Java/预算标签>" \
  --out evaluation/harness/runs/e1-screening.jsonl
```

**为什么是 8×3**：24 次运行是「每个任务至少 3 次」的最小操作门槛，也是 `--strict` 门禁认可的下限；低于这个数量只能得到「证据不足」。

### 第二批：完整基线（可选，取决于第一批结论）

24 个任务 × 3 次 = 72 次运行。第一批若成功率明显偏低或成本失控，第二批不应开始。

## 必须记录的内容

| 维度 | 字段 | 说明 |
| --- | --- | --- |
| 识别 | `model`、`promptVersion`、`toolVersion`、`environment` | 缺任一项则不可与其它批次比较 |
| 结果 | `success`、`acceptance`（外部验收）、`status` | 外部验收未执行 → 不算成功 |
| 成本 | `inputTokens`、`outputTokens`、`cachedInputTokens`、`costUsd` | 缺失时为 `null`，**不当 0** |
| 耗时 | 执行耗时、验收耗时 | 报告 P50 / P95 |
| 失败 | `failureCode`、失败归因分类 | 保留全部失败样本，不剔除 |

指标口径沿用 `evaluation/harness/README.md` 的「指标口径」一节，不另立一套。

## 报告要写成的样子

新建 `evaluation/REAL_MODEL_EVAL_<日期>.md`，必须包含：

1. **样本量与版本**：跑了几个任务、几次、什么模型、什么提示词、什么环境；
2. **按任务族拆分的成功率**：编码类、文件交付类分开报，**不用总体平均掩盖弱项**；
3. **信任度**：完成声明准确率、外部验收覆盖率、未知项表达准确率；
4. **成本**：每个「验收通过」任务的成本，失败样本的成本也计入；
5. **失败样本清单**：可复现（附任务 ID 与运行 ID），不做美化；
6. **明确写出未覆盖的部分**：未测的平台、未测的模型、未测的任务族。

## 预算控制

- 先跑 `node evaluation/harness/budget-plan.mjs <plan-input.json>` 做离线规划，用观测用量的 P95 加余量估算下一批规模；
- 单次运行有步数上限与超时，异常时执行器会取消它自己创建的运行；
- 中途发现成本异常就停，用 `--resume` 补齐而不是重跑已完成的样本；
- 任何情况下都不重置累计额度基线，也不把未知费用当 0。

## 这份文档的边界

- 它只是**计划与记录位**，不是结果。在 `REAL_MODEL_EVAL_<日期>.md` 出现之前，项目里依然没有真实模型能力数字。
- 离线验证通过不等于模型能完成任务；夹具通过不等于验收器覆盖了真实场景。
- 不把这一步的产出（或者它的缺席）说成「已验证」。