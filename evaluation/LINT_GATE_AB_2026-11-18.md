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
| `quality.enabled=false` | 开（被工作区关掉） | 状态是 DISABLED，不发现后面的 `package.json` |
| 显式 `quality.command` | 开 | 覆盖自动发现。样例里超时被读成 15 秒 |
| `ruff.toml`、`go.mod` | 开 | 只读根上的清单。命令分别是 `ruff check {files}`、`go vet ./...`。没有为了发现去遍历子目录 |
| 脚本命令不存在 | 开 | 判失败并打回，不当成通过 |
| 配置缺省 | 默认 | `AgentProperties` 未传该字段时 `lintGateEnabledValue()` 为 true；显式 false 为 false |

`LintGateTest` 5 个、`CompletionGateTest` 28 个、`AgentPropertiesTest` 4 个、`RefactorEvidenceGateTest` 4 个，加上同一次里的 `IndependentCodeReviewerTest`、`PromptLibraryTest`、`ToolNamesTest`、`GoalExecutionPolicyTest`，合计 pilot-core 55 个、pilot-server 54 个，全部通过。规则目录测试 7 个通过。

## 结论

开关打开时，规范以当前仓库的 lint 为准，而且只拿本次改动文件的输出拦收尾。同一条错误在开关关闭时不会被这道门看见，任务会直接去要测试。所以这个开关可以用来对照「有没有按仓库规范打回」，不能用来对照旧的自写扫描器：那套已经删掉，没有第二套结果可对。

没有 lint 配置的仓库，打开开关也不会编造问题。存量文件里的问题，只要这次没改到，不会被拿来拦本次收尾。
