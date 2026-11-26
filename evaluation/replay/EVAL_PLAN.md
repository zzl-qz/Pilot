# 回放评测执行方案

这份文档写给**执行者**（另一个 AI 或人）。目标是：在现有仓库上跑出一批新的、可复核的编码 Agent 评测数据，并把数据提交到 gitee。

不需要重新设计评测，工具和任务集都已经在 `evaluation/replay/` 里。照着做即可。

---

## 0. 一分钟版

```powershell
# 1. 环境自检（见第 2 节）
# 2. 复验任务集（不花 token，约 20 分钟）
node evaluation/replay/replay.mjs verify --all
# 3. 正式跑批（18 题，约 60 分钟，约 2200 万输入 token）
node evaluation/replay/run-eval.mjs --batch r4 --parallel 2 --timeout 40
# 4. 对账（修正断连造成的误判）
node evaluation/replay/reconcile.mjs --batch r4
# 5. 出报告
node evaluation/replay/report.mjs --batch r4
# 6. 作弊审计（第 6 节），然后提交推送
```

---

## 1. 目标与产出

| 产出 | 位置 |
| --- | --- |
| 原始结果（每任务一行 JSONL） | `.pilot-eval/replay-runs/<batch>/results.jsonl` |
| 汇总 | `.pilot-eval/replay-runs/<batch>/summary.json` |
| 报告（要提交） | `evaluation/replay/results-<batch>.md` |
| 提交记录 | `git log` + 推送到 gitee `origin/master` |

产出的核心数字只有四个：

1. **pass@1**：运行终态 `COMPLETED` **且** 注入隐藏用例后测试通过的比例
2. **误报完成率**：正常收尾但验收失败 / 正常收尾数
3. **每成功任务的未命中输入 token**
4. **耗时 p50 / p95**

---

## 2. 前置条件

在一台 Windows 机器上，仓库根目录执行。

| 要求 | 检查方式 | 期望 |
| --- | --- | --- |
| JDK 25 | `$env:JAVA_HOME` | 指向 `D:\DevTools\jdks\corretto-25.0.5`（或任意 25.x） |
| Node 22+ | `node --version` | `v22.x` |
| Maven 依赖已就绪 | 跑一次 `.\mvnw.cmd -pl pilot-core -q -DskipTests compile` | 成功 |
| Pilot 服务在跑 | `Invoke-RestMethod http://127.0.0.1:9999/api/health` | `status=ok` |
| 模型已配置 | 上面返回的 `model` | 记录实际值，**写进报告** |
| 工具齐全 | 返回的 `tools` 里含 `git_status`、`delete_file` | 缺了说明 jar 是旧构建，见 7.1 |

环境变量（每个新终端都要设）：

```powershell
$env:JAVA_HOME='D:\DevTools\jdks\corretto-25.0.5'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
```

服务没在跑就启动：

```powershell
cd E:\Pilot
java -jar pilot-server\target\pilot-server-0.0.1-SNAPSHOT.jar --server.port=9999
```

---

## 3. 已经有什么（不要重建）

- `evaluation/replay/tasks.json`：18 个任务，S/M/L 各 6 个。每个任务记录了 commit、父提交、验收用例、A/B 校验结果、任务正文。
- `replay.mjs`：`list` / `verify` / `setup` / `accept`
- `run-eval.mjs`：批量跑
- `reconcile.mjs`：对账
- `merge.mjs`：补跑批次并回主批次
- `report.mjs`：出报告
- `results-r2.md`：上一批的真实结果（pass@1 55.6%，误报完成率 44.4%）

**已有批次不要覆盖**：`r1`（作废，题面只有标题，仅作反面样本）、`r2`（正式数据）、`r3`（补跑合并进 r2）。新批次用新名字，例如 `r4`。

---

## 4. 执行步骤

### Phase 1：复验任务集（不花模型 token，约 20 分钟）

```powershell
node evaluation/replay/replay.mjs list
node evaluation/replay/replay.mjs verify --all
```

每个任务会做两件事：

- **A**：父提交 + 只放隐藏用例 → 必须**失败**
- **B**：父提交 + 参考解 → 必须**通过**

判定：输出的最后一行应为 `汇总：18/18 合格`。

任何一项不合格**不要继续跑批**，先报告是哪几个任务、A/B 各自的结果。

### Phase 2：正式跑批（约 60 分钟）

```powershell
node evaluation/replay/run-eval.mjs --batch r4 --parallel 2 --timeout 40
```

说明：

- `--parallel 2` 是安全值。机器空闲且内存充足可以调到 3，**不要超过 3**。
- 每个任务会：导出父提交的独立仓库 → 建会话 → 发任务 → 轮询终态 → 还原隐藏用例跑测试。
- 输出逐行 `[start]` / `[done]`。`status=COMPLETED` 且 `passed=true` 才算通过。
- 中途断连会记成 `status=ERROR`、`fetch failed`，这**不是**模型失败，交给 Phase 3。

### Phase 3：对账

```powershell
node evaluation/replay/reconcile.mjs --batch r4
```

它会：按服务端真实终态修正 `ERROR` 记录、重新验收每个任务、重算汇总。

注意两种情况：

- `无会话，跳过（需要补跑）`：这次根本没启动模型。把这些 id 收集起来，用 Phase 2 的命令补跑一个新批次，再用 `merge.mjs` 并回。
- 仍有记录是 `ERROR`：再跑一次 `reconcile`；连续两次失败就单独补跑该任务。

补跑与合并：

```powershell
node evaluation/replay/run-eval.mjs --batch r4b --tasks M-03,M-04 --parallel 2 --timeout 40
node evaluation/replay/merge.mjs --into r4 --from r4b
```

### Phase 4：出报告

```powershell
node evaluation/replay/report.mjs --batch r4
```

生成 `evaluation/replay/results-r4.md`。检查报告里这几项是否合理：

- 任务数 = 18，正常收尾数应接近 18（正常收尾少说明服务不稳）
- 分档通过应该有梯度（S ≥ M ≥ L）。如果 L 反而高于 S，先怀疑验收环境，而不是模型

### Phase 5：提交推送

```powershell
cd E:\Pilot
git add evaluation/replay
git -c user.name="zzl" -c user.email="2080879934@qq.com" commit -m "运行 r4 回放评测并记录结果"
$env:GIT_TERMINAL_PROMPT='0'
git push origin master
```

推送前确认 `git rev-parse HEAD` 与 `git rev-parse origin/master` 一致。

---

## 5. 指标口径（不要自创口径）

| 指标 | 定义 | 注意 |
| --- | --- | --- |
| pass@1 | `status == COMPLETED && acceptExit == 0` | 模型说完成不算 |
| 误报完成率 | `status == COMPLETED && acceptExit != 0` / 正常收尾数 | 这是产品问题指标，别省略 |
| 每成功任务 token | 全批未命中输入 / 成功数 | 区分总输入与未命中输入，缓存命中部分另计 |
| 耗时 | 从发任务到终态 | 不含验收时间 |

禁止事项：

- 不许删掉失败样本、不许只报成功的那些
- 不许把 `ERROR`（基础设施问题）算成模型的失败
- 不许用 `r1` 的数据和 `r2/r4` 混合比较（题面不同）
- 成本未知就写未知，不要填 0

---

## 6. 反作弊硬约束

被测对象是另一个 Agent，**必须让它干净地做题**。执行者要保证：

1. **工作区在仓库外**：`E:\Pilot-eval\<batch>\<taskId>`。不要放在 `E:\Pilot` 里面，否则它能翻到主仓库的提交历史，直接找到答案。
2. **工作区已剥掉答案材料**：`run-eval.mjs` 会删除 `evaluation/` 与 `docs/` 目录，并新建一个只有 1 个 baseline 提交的 git 仓库。不要绕过这一步手工准备。
3. **隐藏用例只注入不预置**：验收时 `accept` 会先删掉模型改过的测试，再写回提交里的原文。不要手动把新测试放进工作区。
4. **任务说明只给标题+正文**：不要透露 commit hash、diff、文件清单、测试方法名。
5. **跑完做审计**（必须执行）：

```powershell
Get-ChildItem E:\Pilot-eval\r4 -Directory | ForEach-Object {
  $st = git -C $_.FullName status --porcelain
  $bad = $st | Where-Object { $_ -match 'src/test' -or $_ -notmatch 'pilot-(core|server)/' }
  if ($bad) { "$($_.Name): $($bad -join '; ')" }
  $n = (git -C $_.FullName log --oneline | Measure-Object -Line).Lines
  if ($n -gt 1) { "$($_.Name): 出现了额外提交 $n" }
}
```

期望输出为空。有输出就说明该任务作弊或有越界改动，必须单独标注，不能计入统计。

6. **不要替被测 Agent 修代码**。任何手工改动都算作弊。

---

## 7. 常见故障

### 7.1 服务工具不全（缺 `git_status` / `delete_file`）

说明 jar 是旧构建。重新打包并重启：

```powershell
cd E:\Pilot
# 先停掉占用 jar 的进程，否则 repackage 会因文件锁失败
.\mvnw.cmd -B -ntp -pl pilot-server -am package -DskipTests -DskipFrontend=true
java -jar pilot-server\target\pilot-server-0.0.1-SNAPSHOT.jar --server.port=9999
```

### 7.2 `Could not find the selected project in the reactor`

Windows 上临时目录可能返回 8.3 短路径（`ZHUANG~1`），Maven 在该路径下解析 `-pl` 会失败。`replay.mjs` 已经用 `realpathSync` 处理；如果你自己写脚本，也要先转长路径。

### 7.3 `No tests matching pattern ... were executed`

`-pl pilot-server -am` 时 `-Dtest` 会连带作用到 `pilot-core`。命令行必须带 `-Dsurefire.failIfNoSpecifiedTests=false`（`replay.mjs` 已带）。

### 7.4 大量 `fetch failed`

本机连接抖动或服务过载。处理顺序：

1. `reconcile.mjs` 再跑一次（它会按会话真实状态修正结果）
2. 仍失败则降低 `--parallel` 到 1 重跑该批次
3. 检查服务是否还活着：`Invoke-RestMethod http://127.0.0.1:9999/api/health`

### 7.5 内存吃紧

3 个并发 Agent 加 Maven 构建会占满 15GB 机器。看到可用内存低于 1GB 就降到 `--parallel 1`。

---

## 8. 可选增强（按价值排序）

### 8.1 稳定性：失败题各再跑 2 次（约 30 分钟）

目的：区分「每次都改错」和「偶尔改对」。

```powershell
node evaluation/replay/run-eval.mjs --batch r4-stab --tasks S-01,M-04,M-05,M-06,L-01,L-02,L-03,L-05 --parallel 2 --timeout 40
```

统计时报告每个任务的 `通过次数 / 3`，以及 `pass@3`（三次全过）。

### 8.2 oracle 对照组（最有信息量，约 60 分钟）

目的：回答「55% 是模型能力不足，还是题面信息不足」。

做法：给同样的任务，但在任务说明里附上**失败测试的输出**（现实中 bug 报告本来就有复现）。

实现方式（需要写 `replay.mjs` 的一个子命令，约 40 行）：

1. 在临时目录导出父提交，注入隐藏用例；
2. 跑该测试，捕获失败输出（保留第一个失败的方法名与断言信息，截断到 2000 字符）；
3. 把这个输出作为 `失败信息` 段拼进任务说明；
4. 用同一批任务再跑一次，批次名 `r4-oracle`；
5. 对比两组 pass@1。

判读：

- 若 `r4-oracle` 显著高于 `r4`（例如 55% → 80%），说明主要瓶颈是信息缺失，不是编码能力；
- 若两组接近，说明是能力问题，应转向改进自检与收尾门。

### 8.3 多模型对照

同一批任务换一个模型再跑一次（`--model` 需在服务端切换），比较 pass@1 与每成功任务 token。成本翻倍，按需做。

---

## 9. 完成标准（自查清单）

- [ ] `replay.mjs verify --all` 输出 18/18 合格
- [ ] `results.jsonl` 行数 = 18
- [ ] `summary.json` 的 `tasks` = 18，`passed` + 失败数 = 18
- [ ] 正常收尾数 ≥ 17（低于说明服务不稳，数据不可信）
- [ ] 分档通过有梯度
- [ ] 作弊审计输出为空
- [ ] `results-<batch>.md` 已生成并包含逐任务表
- [ ] 已 commit 并 push 到 `origin/master`，本地与远端 SHA 一致
- [ ] 报告里写清了：模型名、运行日期、批次名、任务数、以及「只跑一次，不能外推」的边界

---

## 10. 成本参考（上次实测）

| 项 | 数值 |
| --- | --- |
| 18 个任务、每任务 1 次 | 输入 22,462,727 token（缓存命中 71.1%），未命中 6,488,482，输出 100,887 |
| 模型请求 | 356 次（平均 20 次/任务） |
| 耗时 | p50 321s，p95 808s；并发 2 时整批约 60 分钟 |
| A/B 复验 | 0 token，18 题约 20 分钟 |

---

## 11. 报告写作要求

`report.mjs` 生成的是数据，提交前在报告末尾补一段结论，必须包含：

1. 模型、日期、批次、任务数
2. 与上一批（r2）的对比：pass@1 与误报完成率变化
3. 失败的形态分类（举例说明是「方向错」还是「细节错」）
4. 边界：只跑一次、单一模型、单仓库任务，不能外推

不要写「效果显著提升」这类没有对照的话。没有对照就没有结论。
