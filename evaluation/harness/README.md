# 评估执行器

固定任务集 + 外部验收。执行器在发任务前保存 `test/`，结束后把候选代码复制到独立验收目录并还原这份测试，再运行验收命令；删改工作区断言不能制造通过。模型自述「测试通过」不构成成功证据。

## 命令

```bash
# 跑一遍（需要本机已启动 Pilot，并接到真实模型端点）
node evaluation/harness/runner.mjs --base http://127.0.0.1:8787 \
  --model <模型> --prompt-version P1 --tool-version T1 \
  --fixtures evaluation/harness --out evaluation/harness/runs/20261103.jsonl

# 与上次结果比较，并给出优化门禁结论
node evaluation/harness/runner.mjs ... --baseline evaluation/harness/runs/上次.jsonl --strict

# 只检查 fixture（不需要启动服务）
node evaluation/harness/runner.mjs --verify-fixtures --fixtures evaluation/harness

# 不启动服务，只验证流程与产物格式（不能当作能力结论）
node evaluation/harness/runner.mjs --dry-run --fixtures evaluation/harness
```

`--strict` 下门禁不是 `pass` 就以非零退出码结束，用于 CI 拦住会造成能力退化的「优化」。
`--strict` 必须同时指定可读的 `--baseline`，不能与 `--dry-run` 一起使用。dry-run 记录明确标为未验收，门禁会拒绝它们。
未提供 `--fixtures` 时相对于任务集文件定位 fixture；缺少 fixture 会在请求服务前失败，绝不回退到默认工作区。

## 任务集规范

`tasks.json` 每个任务给：

- `fixture`：可复制的初始工作区
- `prompt`：发给模型的任务
- `acceptance`：验收命令数组，全部退出码为 0 才算通过
- `steer`（可选）：`{ afterSeconds, content }`，跑到指定秒数时通过 `/steer` 引导当前 run

fixture 统一是**零依赖的 Node 工程**：跨平台、不联网、验收秒级完成（不用 `mvn -q -o test` 那种几十秒还要碰离线仓库的方式）。
目录约定是 `src/` 放待修代码、`test/` 放验收测试。
Node 验收使用 `node --test` 自动发现测试，不使用在 Node 22 下会被解析成模块路径的 `node --test test/`。
只有 `COMPLETED` 且外部验收通过才记为成功；取消、暂停、失败不会被误记成功。超时不执行验收，并尝试取消执行器创建的运行；取消失败写明会话 ID。

两条硬要求，缺一个任务就白给：

1. **未修复时验收必须失败。** 初始就通过的任务没有分辨力——模型什么都不做也能拿到 PASS。
2. **必须可解。** 断言如果根本无法满足，任务就是恒定失败。新增任务时先写一份参考解确认能跑绿。

这两条都会让门禁空转：基线上全是失败的任务，永远报不出退化（因为判定的是「从成功变成失败」）。

用 `--verify-fixtures` 检查第 1 条；第 2 条目前靠人工写参考解验证。

## 门禁：`metrics.mjs` 的 `gate()`

判定顺序是**能力优先、成本其次**：

| 检查 | 为什么单独列 |
|---|---|
| 任一任务从「基线成功过」变失败 → 拒 | 逐任务 Pareto 判定。只看平均通过率会漏掉「放弃难题换整体好看」 |
| 通过率下降 → 拒 | — |
| 验收执行率下降 → 拒 | 少跑验收既省钱又不会拉低通过率，是最隐蔽的退化 |
| 人工接管率上升 → 拒 | — |
| 成本上升 → 拒 | 没有价格表时退化为用输入 token 当代理，涨幅超 5% 视为变贵 |
| 可比任务 < 20 → insufficient | 「看不出退化」不等于「没有退化」，样本不足不算通过 |

样本数量按不同的 `taskId + model` 计算；同一个任务重跑 20 次仍只有一个可比任务。
缺少基线中的任务会拒绝。成本使用同任务每次运行的均值，只有全部可比记录都有价格才按金额比较，否则使用输入 token；减少重跑次数和只记录便宜样本不能制造降本。

基线的能力取「曾经成功过」而不是「最后一次」：同一任务跑多次时，只要做到过一次，本次做不到就算退化。

结果写进 `*.summary.json` 的 `gate` 字段，也打印在标准输出。
