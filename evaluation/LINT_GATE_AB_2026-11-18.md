# 收尾 lint 开关对照

日期：2026-11-18

对照对象是同一套样例，不是两个模型。样例是一个只有两个文件的小仓库：`src/app.js` 和 `src/legacy.js` 都写了裸数字 `86400`。检查器是仓库自己的 `node lint.js`，由 `package.json` 的 `lint` 脚本或 `.pilot/settings.json` 的 `quality.command` 指向它。开关是 `pilot.agent.lint-gate-enabled`，默认 `true`。

## 怎么跑

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-25.jdk/Contents/Home
sh mvnw -pl pilot-core,pilot-server \
  -Dtest=LintGateTest,CompletionGateTest,AgentPropertiesTest,RefactorEvidenceGateTest test
node --test scripts/quality-rules.test.mjs
```

JDK 25。样例不依赖本机安装 eslint、ruff 或 Checkstyle。

## 结果

| 场景 | 开关 | 结果 |
| --- | --- | --- |
| `package.json` 有 `lint`，只改了 `src/app.js` | 开 | 跑了 1 次。打回 `src/app.js:1 error 魔法值 86400`。没把没改的 `src/legacy.js` 算进这次 |
| 命令扫了两个文件，改动只有 `src/app.js` | 开 | 输出里两行都有。打回只留 `src/app.js:1`，丢掉 `src/legacy.js:1` |
| 把 `src/app.js` 改成没有 `86400` | 开 | 判定通过。`legacy.js` 里的旧问题不拦收尾 |
| 同一个仓库、同一条会报错的输出 | 关 | 检查进程没有启动。收尾直接进入「还没跑测试」 |
| 根目录没有 lint 清单 | 开 | 不执行命令，也不假装通过 |
| `quality.enabled=false` | 开 | 不能关掉检查。仍按仓库自己的 lint 走 |
| 显式 `quality.command` | 开 | 覆盖自动发现。样例里超时被读成 15 秒 |
| `ruff.toml`、`go.mod` | 开 | 只读根上的清单。命令分别是 `ruff check {files}`、`go vet ./...`。没有为了发现去遍历子目录 |
| 脚本命令不存在 | 开 | 判失败并打回，不当成通过 |
| 配置缺省 | 默认 | `AgentProperties` 未传该字段时 `lintGateEnabledValue()` 为 true；显式 false 为 false |

`LintGateTest` 5 个、`CompletionGateTest` 28 个、`AgentPropertiesTest` 4 个、`RefactorEvidenceGateTest` 4 个，加上同一次里的 `IndependentCodeReviewerTest`、`PromptLibraryTest`、`ToolNamesTest`、`GoalExecutionPolicyTest`，合计 pilot-core 55 个、pilot-server 54 个，全部通过。规则目录测试 7 个通过。

## 结论

开关打开时，规范以当前仓库的 lint 为准，而且只拿本次改动文件的输出拦收尾。同一条错误在开关关闭时不会被这道门看见，任务会直接去要测试。所以这个开关可以用来对照「有没有按仓库规范打回」，不能用来对照旧的自写扫描器：那套已经删掉，没有第二套结果可对。

没有 lint 配置的仓库，只对本次新行做浅检查：裸数字和重复字符串。存量文件里的问题，只要这次没改到，不会被拿来拦本次收尾。仓库已经有自己的 lint 时，不用这套浅检查覆盖它。

进程开关 `pilot.agent.lint-gate-enabled` 默认开启，这是唯一的关闭方式。工作区里的 `quality.enabled=false` 不能关掉它。运行开始时锁定命令和规则文件；模型再改 `package.json` 的 lint 脚本、`quality.command` 或 `lint.js`，这次收尾不会把改过的命令当成通过。

## 写入后立刻反馈

2026-11-18 补上的增量：命令里带 `{files}` 时，文件写入成功后立刻再跑一次，把结果写成「修正指令」贴在这次工具输出后面。`go vet ./...` 这种整仓命令不在每次写入时跑，只在收尾跑一次。

默认策略是 `retry`：同一条诊断重复 3 次，或累计未通过 5 次，并且最新代码的测试或构建已经通过，才记录并放行。测试没过不能放行。`stop` 会直接停在未完成，`pass` 会第一次就记录并放行。不使用 `git reset --hard`。成段复制只看本次改动和同目录，不做跨目录扫描。搜索和按文件名查找都有目录上限，符号链接目录不进入。
