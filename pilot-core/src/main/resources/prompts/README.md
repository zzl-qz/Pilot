# prompts 目录规范

> 提示词正文全部维护在这里，Java 代码只引用名字（`PromptLibrary`）。
> 写法总原则：**宁可当场失败，也不要给模型一段破损的提示词**——变量缺失、frontmatter
> 非法都会抛异常，不会静默降级。

## 目录职责

| 目录 | 职责 | 渲染时机 |
| --- | --- | --- |
| `system/` | 系统提示词段落（身份、流程、工具策略、输出格式） | 每次 run 组装，进 system |
| `tools/` | 每个工具的描述，文件名 = `Tool.name()` | 随工具定义每次请求发送 |
| `completion/` | 收尾门自查（verify / review / review-readonly） | 收尾前按需注入 user 消息 |
| `compact/` | 上下文压缩摘要指令 | 压缩时注入 |
| `plan/` | 计划未完成的自动继续提醒 | 自动续跑时注入 user 消息 |
| `loop/` | 循环行为提醒（repeat-reminder） | 重复调用达到提醒档时注入 |
| `subagent/` | 子代理的任务与系统提示 | 子代理启动时渲染 |
| `context/` | 运行期上下文模板（仓库快照） | 会话首条 run 注入一次 |

## 单一出处原则

- 工具的使用细节（参数建议、超时值、错误恢复）**只写在 `tools/<name>.md` 与该工具的参数 description**；
  `system/` 段落只放跨工具的策略约束。
- 同一事实出现在第二处时，删掉一处——收尾门等按需注入的模板允许复述场景语义，
  但不复述具体参数值。

## frontmatter 与段落过滤

`system/` 段落可在文件头声明依赖工具，工具未注册时整段跳过：

```markdown
---
requires: [dispatch_agents]
---
```

- 只支持 `requires` 一个键；值支持 `a`、`a, b`、`[a, b]` 三种写法；其他键或格式非法抛异常。
- 独立工具的使用指导分别放在独立段落中，例如 `tools-spawn` 与 `tools-dispatch`，避免关闭一个工具时连带移除另一个工具的指导。
- 原 `system/tools-agents.md` 已拆为 `system/tools-spawn.md` 和 `system/tools-dispatch.md`；使用外部目录覆盖旧片段时，也需要按工具拆分到这两个文件。
- **注册表声明优先**：`PromptLibrary.SYSTEM_SECTIONS`（`SystemSection` 记录）里的
  `requiresTools` 是真源，frontmatter 只是阅读标记；两处写一个即可，新段落建议写注册表。
- 段落顺序由注册表 `order` 稀疏分配决定（身份 0 → 策略 100–500 → 输出 9000 →
  约定/日期 10000+ → 计划压轴），新增取区间空隙值。

## 改动检查清单

改 `system/` 段时：

1. 新段落：在 `SystemSection` 注册（order 空隙值），需要过滤的写 `requiresTools`。
2. `PromptLibraryTest` 补对应用例（过滤行为 / frontmatter 解析）。
3. 确认没有把工具细节写进 system 正文（单一出处）。

改 `tools/` 描述时：

1. 文件名必须与 `Tool.name()` 一致，缺失会运行时报「找不到提示词资源」。
2. 参数级建议写在 Java 的 `parameters()` 描述字段里——只写「模型填参时最容易做错的动作」。

## 外部覆盖

`PILOT_PROMPTS_DIR`（或系统属性 `pilot.prompts.dir`）指向的外部目录按同名覆盖
classpath 版本，缺失文件回落；外部文件每次读盘，改完无需重新打包。

> AI生成
