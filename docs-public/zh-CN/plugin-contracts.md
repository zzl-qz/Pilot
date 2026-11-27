# 插件契约

[English](../plugin-contracts.md) | [简体中文](plugin-contracts.md)


> 最后更新：2026-11-16

## 为什么需要

插件失效的根因通常不是「核心升级了」，而是**插件绑在了实现上**：它记住了某个内部函数、某个字段位置、某次事件的载荷形状。核心一动，插件就断。

常见做法是让插件跟着核心版本号走。这会把两边绑在一起：核心每发一版，插件作者都要跟一版；跟不上就被淘汰。生态越大，这种绑定越贵。

Pilot 的做法是**让插件依赖契约，而不是依赖核心版本号**。

## 契约是什么

契约是一小组带版本的公开承诺：

| 契约 | 含义 |
| --- | --- |
| `tool.read_file@1` | 读取工作区文件，返回文本或图片 |
| `tool.edit_file@1` | 按唯一匹配替换文件片段 |
| `tool.create_file@1` | 创建文件 |
| `tool.run_command@1` | 执行命令并返回退出码与输出 |
| `hook.pre_tool_use@1` | 工具执行前的确定性钩子 |
| `hook.post_tool_use@1` | 工具执行后的确定性钩子 |
| `event.run_end@1` | 一次运行结束事件 |
| `event.tool_result@1` | 工具结果事件 |
| `skill.frontmatter@1` | `SKILL.md` 的 frontmatter 字段与语义 |

完整清单由 `GET /api/maintenance/contracts` 返回，代码在 `pilot-core/src/main/java/com/pilot/core/contract/Contracts.java`。

## 版本规则

只有两条，其余都从这两条推出来：

1. **新增字段、新增可选能力是兼容变更**，不升大版本。旧插件照常工作。
2. **删除字段、改变语义或默认行为要升大版本**。旧插件按不兼容处理。

所以插件写 `tool.read_file@1`，只要核心还提供 v1 语义就一直可用，不会因为核心从 1.x 走到 2.x 被打掉。

## 插件怎么声明

### 技能

`SKILL.md` 的 frontmatter 里写：

```yaml
---
description: 整理调研结论并交付
requires: tool.read_file@1, event.run_end@1
requires-optional: tool.edit_file@1
---
```

- `requires`：缺任何一条，技能**不加载**，并在能力页与升级前体检里说明缺哪条。
- `requires-optional`：缺了仍然加载，只是标注为降级。

没写 `requires` 的旧技能按宽松兼容处理：只根据 `allowed-tools` 做提示，不阻断。

### 钩子

`settings.json` 的钩子组可以声明它挂的是哪个事件：

```json
{
  "hooks": {
    "PostToolUse": [
      { "matcher": "edit_file", "requires": "hook.post_tool_use@1",
        "hooks": [ { "type": "command", "command": "mvn -q spotless:apply" } ] }
    ]
  }
}
```

缺必需契约的整组钩子**不加载**，并作为「未加载」条目出现在能力页与升级前体检里——钩子失效本来就不会报错，再静默跳过就根本查不出来。

## 怎么确认会不会坏

两个入口，读的是同一份判断：

- **升级前体检**：设置 → 诊断 → 升级前体检，或 `GET /api/maintenance/plugin-compat`。四档状态：正常 / 会重新确认 / 会失效 / 无法判断，并附可粘贴的 Markdown 报告。
- **能力页**：`GET /api/sessions/{id}/capabilities` 的 `plugins` 字段，与上面完全一致。

## 边界

- MCP 服务器由外部规范定义，Pilot 不扩展它的配置格式；对 MCP 的判断只能基于**已钉住的启动命令与工具定义哈希**，属于安全层，不是契约层。
- 契约表目前只覆盖插件真正依赖的那一小部分；不把内部实现细节都写进契约，否则契约会变成第二个版本号。
- 契约不授予权限：声明了 `tool.run_command@1` 不代表可以随便执行命令，权限仍由工具权限与审批决定。
