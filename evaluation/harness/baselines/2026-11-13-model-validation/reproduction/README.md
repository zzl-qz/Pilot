# 本轮实验的复现入口

这些文件保存本轮追加实验的任务派发、外部确认和验收逻辑。它们是实验记录，不会自动启动服务或读取用户密钥。模型配置、预算和通知设置由操作者在独立评测服务中准备；不要指向日常使用的 Pilot 服务。

原始 24 × 3 矩阵使用 `8496d32` 的生产代码；追加实验使用 `2145dbc` 的独立构建。前两组保持相同提示词；后续 `8dc383b` 增加通用预算提醒和续跑状态修复，提示词指纹变化，单独保存为恢复修复组。具体构建 SHA-256、运行时代码 hash 和环境见上一级 `validation.json`。汇总使用 `7b552ac`，修正失败样本缺失用量被算作零的问题，不改写原始样本。

## 状态文件

把对应版本的源码和构建放在独立目录，使用新的数据目录、端口及空结果目录启动服务。状态文件至少包含：

```json
{
  "root": "<冻结源码目录的绝对路径>",
  "sourceCommit": "<完整提交号>",
  "jarSha256": "<实际构建的 SHA-256>",
  "main": { "base": "http://127.0.0.1:<独立端口>", "model": "<模型名称>" },
  "secondary": { "base": "http://127.0.0.1:<另一个独立端口>", "model": "<模型名称>" },
  "budget-1": { "base": "http://127.0.0.1:<预算服务端口>", "model": "<模型名称>" }
}
```

常规服务配置：Java 25、模型总时限 900 秒、空闲时限 600 秒、直接请求预算 1000、运行线程 4，启用完成检查。浏览器、技能和 `dispatch_agents` 关闭；普通 `spawn_agent` 仍按产品默认可用。`--pilot.multi.enabled=false` 不代表关闭所有子代理。原始预算服务另设每段请求与步骤上限均为 8；该组会触发自动续跑，手动段数不是实际 run 数。恢复修复组使用每段请求 8、步骤 64，避免混合上限。

```bash
node run-postfix.mjs /absolute/path/state.json main
node run-scenarios.mjs /absolute/path/state.json main
node run-postfix.mjs /absolute/path/state.json secondary
node run-scenarios.mjs /absolute/path/state.json secondary
node run-budget.mjs /absolute/path/state.json budget-1
node run-scheduled-wake.mjs /absolute/path/state.json main
```

预算实验使用三个独立服务，分别以 `budget-1`、`budget-2`、`budget-3` 执行。定向复测与定时唤醒并发为 3，场景组为 4；各组依次执行，不与其他实验共享模型请求计数。脚本通过状态文件传入路径，目录拼接、子进程和 HTTP 请求使用标准 Node API。本轮实机为 macOS；环境标签记录本次实验条件，换平台运行时应同步修改标签并新建批次。

结果写入冻结目录的 `runs/`，工作区写入 `.pilot-eval/`。文件以独占创建方式保存，重复执行不会覆盖已有试验。发生中断时先确认所有已派发会话的终态，再用原任务与配置仅补缺少的 attempt；不能删掉失败样本后重跑原命令。原始预算组每会话最多四个手动段；恢复修复组最多八个手动段。超过仍未完成必须保留失败，最终成功不能覆盖初次边界台账失败。

恢复修复组用 `run-budget-followup.mjs`，传参方式与原预算脚本相同；它额外保存每个边界的台账内容与 hash。定时唤醒仍用原脚本。第一轮状态文件指向 `8dc383b` 的独立 `wake` 服务，三次失败完整保留；修复默认调研策略后，再用 `43dd5be` 的独立服务按相同提示词、fixture 和验收追加三次：

```bash
node run-budget-followup.mjs /absolute/path/followup-state.json budget-1
node run-scheduled-wake.mjs /absolute/path/followup-state.json wake
```

定时唤醒由脚本在独立数据目录创建三个计划，不启用桌面或 Webhook 通知。只有观察到真实 `WAITING` 后才写入外部随机确认；同时检查原始脚本、输入与测试未改、checkpoint 不变、不同 runId 按预约时间启动、计划完成及独立测试通过。它与等待后台命令的 `wait-condition` 是不同证据。

## 网络故障组

使用仓库 `evaluation/harness/support/fault-proxy.mjs`，每种故障各一个独立代理和 Pilot 服务。代理上游密钥与本地控制令牌只经环境变量传入，不写入报告。HTTP 私有 IPv4 模型需要显式 `PILOT_FAULT_ALLOW_PRIVATE_HTTP=true`。

依次对 `mid-eof`、`stall`、`http-503`、`reset` 执行以下原生 runner 命令，每种重复三次；四个独立故障服务可以并发。Pilot 空闲时限为 120 秒、总时限 900 秒，代理每生命周期上限 200 次请求。先把本目录的 `fault-tasks.json` 复制到冻结源码的 `evaluation/harness/fault-tasks.json`，保持 fixture 相对路径可解析。

故障组在派发前使用独立的 30 分钟任务合同：runner 取 CLI 与任务配置中的较小期限，只传 CLI 的 30 分钟仍会被普通任务的 8 分钟限制。多次 120 秒停顿需要单独的恢复观测窗口；任务正文和 fixture 没有变化，原始矩阵的 8 分钟期限不改。

```bash
node evaluation/harness/runner.mjs \
  --base http://127.0.0.1:<独立服务端口> --model <模型名称> \
  --tasks evaluation/harness/fault-tasks.json \
  --repeat 3 --task fix-failing-unit-test --timeout-minutes 30 \
  --prompt-version current-2145dbc --tool-version current-2145dbc \
  --environment macos-arm64-java25-node22-independent-proxy-concurrency4-total900-idle120-direct1000 \
  --fault-proxy http://127.0.0.1:<对应代理端口> \
  --fault-profile <故障模式> --fault-every 3 --fault-stall-ms 180000 \
  --out runs/<独立故障组>/trials.jsonl
```

中途断流和停顿以协议事件为边界，先发送不含终止标记的完整 SSE 事件，再断开或停顿；不能按第一个 HTTP 数据块截断，否则整个回复被合并到一个块时会制造假通过。结果应同时复核代理故障计数、实际失败/重试事件、工具轨迹和外部验收。

新调研策略组的三个唤醒会话与三个独立预算恢复会话共用主模型，各组并发 3，总主会话并发上限 6；时延受共享端点负载影响。`43dd5be` 的 `requireResearch` 缺省为 false，不需要给 fixture 加绕过门禁的提示词；明确要求调研的任务仍须通过原有门禁。

随后 `bd6a69b` 修复核验计数回退和历史等待证据被判为过期，修改了核验系统提示词。第三个唤醒组仍运行同一份 `run-scheduled-wake.mjs`，任务正文、脚本、测试、二十分钟期限和三次并发不变；该组开始前其他模型评测组均已结束。三个构建的唤醒结果分开保存，不能把后组成功回填到前组。

唤醒脚本的期限报告先记录当时状态，再取消超时计划。归档时另行等待活动 run 为空，保存取消完成后的历史及计划状态；原期限快照保留。前两组已停服务的补充证据来自无 WAL 的隔离数据库只读副本，文件标为 `settled-db-events.jsonl`，不冒充原 API 序列化结果。
