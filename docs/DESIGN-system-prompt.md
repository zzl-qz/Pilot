# Pilot 系统提示词优化设计

> 版本：0.1（设计稿）
> 日期：2026-10-28
> 状态：待评审
> 参照系：deepseek-harness（dsh）的提示词工程，重点是 `dsh-system-prompt` 包与 `sdk-minimal` 极简 profile

## 1. 背景与目标

Pilot 的提示词体系（`PromptLibrary` + `prompts/` 目录）解决了「提示词与代码分离」和「变量缺失快速失败」，
但系统提示词的组装方式存在四类问题：

1. **提示词与实际注册的工具可能不一致**——`dispatch_agents` 仅在多智能体开启时注册，
   但 `system/tools.md` 与 `system/orchestration.md` 无条件组装，关闭时模型会看到一个不存在工具的用法教学。
2. **动态内容混进 system**——仓库快照（git status 每轮变化）与计划（每步变化）拼在 system 尾部，
   跨轮请求的前缀缓存从第一个变化 token 起全部失效。
3. **正文与工具描述重复**——同一细节（如 `timeoutSeconds` 建议值）在 system 段落与工具描述里各出现一次，
   每步请求都重复付费。
4. **行为约束停留在提示词层**——「先读后改」等可由机制强制的行为，目前仅靠 system 文本劝导。

dsh 对这四类问题都给出了成熟答案。本文档提炼其模式，映射为 Pilot 的最小改动方案。

### 目标

- system 提示词**字节级可预期**：除计划与日期外，跨轮不变化，为模型端前缀缓存保住稳定前缀。
- system 段落**与工具生命周期一致**：工具未注册，对应指导不存在。
- **单一事实来源**：工具使用细节只出现在 `prompts/tools/<name>.md`；system 只保留跨工具策略。
- 可由机制强制的行为**下沉为机制**，不再占用提示词。

### 非目标

- 不引入提示词的运行时可编辑 API（终端用户不直接改提示词，仍走 `PILOT_PROMPTS_DIR` 覆盖）。
- 不实现 dsh 的 waterfall 组装流水线与作用域遮蔽；Pilot 是单用户本地产品，注册表 + 过滤即可。
- 不改变 `AgentLoop` 与 `ToolExecutor` 的核心循环结构。

## 2. 参照系：dsh 提示词工程的四个核心模式

### 2.1 工具指导跟工具走，且按作用域裁剪

dsh 的「主约定」（`packages/workflow/tool-workflow/src/index.ts:324`）：

> tool guidance lives in tool plugins as prompt sections, not in the deployment persona

每个工具插件注册自己的 system 段落，段落文本是作用域相关的——
`packages/fs/tool-fs/src/edit.ts:77-83`：

```ts
ctx.systemPrompt.section({
  name: 'tool:edit',
  order: ctx.systemPrompt.getSectionOrder('TOOL_EDIT'),
  text: ({ scope }) => ctx.tools.get('edit', scope) === undefined
    ? ''
    : 'Read a file before editing it (the default fs-observation-policy requires it), ...',
})
```

工具不在该 agent 的注册表里，对应段落渲染为空。

### 2.2 稳定 system 与动态上下文严格分离

dsh 把模型可见内容分两层：

- **system 段落**：追求字节级稳定。`packages/core/system-prompt/src/index.ts:176` 注释明确
  「Process-specific paths and endpoints stay out of the cached system prompt」，
  README 甚至禁止 persona 模板引用 `{{cwd}}`。
- **runtime context**：变化的事实（工作目录、沙箱策略）作为带来源的 user 角色快照注入历史，
  每步刷新，可被压缩机制折叠，不影响 system 前缀。

### 2.3 极简模式证明 system 可以压到一句话

`packages/bundle/sdk-minimal/cordis.patch.yml:113-118`：

```yaml
- id: system-prompt
  name: '@deepseek-ai/dsh-system-prompt'
  config:
    includeHarnessIdentity: false
    includeRuntimeContext: false
    personaPrefix: !!js process.env.DSH_SYSTEM_PROMPT ?? 'You are a helpful software engineer assistant.'
```

一句话 persona + 工具 description / 参数 description 承载全部行为细节。
推论：**system 里的每个 token 都应该是跨工具的行为约束**，不是某个工具的使用说明。

### 2.4 集中分配的段落顺序表

`packages/core/system-prompt/src/index.ts:131-165` 用稀疏有序号管理第一方段落：
身份（−1000）→ persona（0）→ 策略段（500–900）→ 工具段（1000–3000）→ 工具 SDK（5000）
→ 环境信息（10000+）。越动态的段越靠后——利用前缀缓存按 token 匹配的特性，
尾部变化只损失尾部缓存，前面的稳定前缀仍然可复用。

### 与 Pilot 现状一致、无需改动的部分

- 变量缺失即抛异常（`PromptLibrary.render`）——与 dsh「格式错误的提示词比明确失败更糟」一致。
- 收尾门 verify / review 作为按需 user 消息注入（`AgentLoop.completionNudge`）——即 dsh 的 reminder 模式。
- 工具描述独立维护于 `prompts/tools/`、随 definitions 每次请求发送——方向正确。

## 3. Pilot 现状盘点

组装链路：`AgentRunService.systemPrompt()`（`pilot-server/.../session/AgentRunService.java:387-399`）
每轮重建 system，顺序为：

```
base → workflow → tools → orchestration → output → repository → rules → plan
```

| 段落 | 内容 | 稳定性 | 问题 |
| --- | --- | --- | --- |
| `system/base` | 身份、边界、意图判定，含 `{{today}}` | 每天 1 次 | `{{today}}` 位于 system 头部，跨天首条消息断整个前缀 |
| `system/workflow` | 定位、计划、编辑、验证流程 | 稳定 | 「先读后改」等约束可机制化 |
| `system/tools` | 工具选择表 + 跨工具约束 | 稳定 | 与 `prompts/tools/*.md` 内容重复；无条件提及条件注册的工具 |
| `system/orchestration` | 多智能体调度规则（16 行） | 稳定 | 依赖 `dispatch_agents`，但该工具条件注册 |
| `system/output` | 结论格式（14 行） | 稳定 | 可精简 |
| `system/repository` | 顶层树 / git status / 构建方式 | **每轮变化** | 动态内容进 system；末尾自己也写着「要确认时重新用工具查」 |
| `system/project-rules` | PILOT.md 约定 | 低频变化 | 无 |
| `system/plan-context` | 未完成计划 | **每步可能变** | 见 §4.2 的取舍 |

工具注册事实（与本设计相关）：

- `dispatch_agents` 仅在 `multiProperties.enabled()` 时注册（`AgentRunService.java:613`）。
- 子代理工具集不含 `spawn_agent`（防递归，`AgentRunService.java:609`）。
- `EditFileTool` / `CreateFileTool` 写盘前仅回调 `SnapshotSink.beforeWrite`（备份用），
  **没有**「本会话已读过该文件」的校验；「先读后改」只存在于 `workflow.md:21` 的文本。
- `AgentLoop` 对连续相同工具调用：达到 `repeatedToolCallLimit`（默认 3）直接
  `RunFailed("REPEATED_TOOL_CALL")`（`AgentLoop.java:373-384`），无中间提醒。

## 4. 设计方案

按优先级分六个子项，前两项解决正确性与缓存，其余是内容与结构收敛。

### 4.1 工具感知的段落过滤（P0，正确性）

**问题**：多智能体关闭时，system 仍在教模型调用不存在的 `dispatch_agents`。

**方案**：段落声明依赖工具，组装时按实际注册表过滤。

1. 提示词 md 支持极简 frontmatter，仅一个键：

   ```markdown
   ---
   requires: [dispatch_agents]
   ---
   ## 多智能体调度
   ...
   ```

   解析规则：`requires` 值为逗号分隔或 `[...]` 包裹的工具名列表；缺 frontmatter 视为无依赖；
   frontmatter 存在但格式非法时抛 `IllegalArgumentException`（失败关闭，不静默降级——
   与「变量缺失即抛异常」同一哲学；classpath 段与外部目录段同样对待）。
   解析器手写（约 20 行），不引入 YAML 依赖，保持 `pilot-core` 零第三方依赖。

2. `PromptLibrary` 增加重载：

   ```java
   public String system(Map<String, String> variables, Set<String> availableTools)
   ```

   声明了 `requires` 的段落，若其工具不在 `availableTools` 中则整段跳过；
   原单参重载保留（等价于不过滤），供测试与无会话场景。

3. `ToolRegistry` 补一个 `Set<String> names()` 查询（现只有 `definitions()`）；
   `AgentRunService.systemPrompt()` 改为在 `registryFor(...)` 之后组装，传入实际工具名。

4. 段落拆分：

   - `system/tools.md` 的选择表中 `dispatch_agents`、`spawn_agent` 两行拆到带 `requires` 的
     `system/tools-agents.md`（两个工具都注册才注入；子代理工具集不含这两行，天然正确）。
   - `system/orchestration.md` 拆两段：通用约束（证据、不隐瞒风险、分歧上交）留常驻
     `system/orchestration.md`；调度专属规则（星型拓扑、派发粒度、重试上限）移入
     `system/dispatch.md`，`requires: [dispatch_agents]`。

**验收**：`multi.enabled=false` 时渲染出的 system 不含 `dispatch_agents` 字样；
开启时与现状语义等价；`PromptLibrary` 现有测试全部保持通过。

### 4.2 稳定 system 与动态上下文分离（P1，缓存）

**问题**：`repositoryBrief` 的 git status 每轮重新执行，任何一次文件改动都使下一轮 system 变化；
跨轮前缀缓存（DeepSeek context caching / vLLM prefix cache 等按前缀命中）从 repository 段起全部失效。

**方案**：

1. **仓库快照移出 system**：`system/repository.md` 模板改名为 `context/repository.md`
   （不再属于 system 目录），改为**会话首条 run 时**在用户消息之前注入一条独立 user 消息：

   ```
   ## 仓库现状（会话开始时的快照，此后以工具实查为准）
   顶层内容：...
   Git：...
   构建：...
   ```

   - 注入条件：`conversations.load(sessionId).isEmpty()`（会话尚无历史），每会话仅一次；
     续跑与压缩不重新注入。
   - 压缩兼容：该消息随历史正常折叠，无需特殊处理——模板本身声明了「以工具实查为准」。
   - `AgentRunService.repositoryBrief()` 的每轮 `list_dir + git status` IO 随之消失，只剩会话首次一次。
   - SSE 事件：注入的 user 消息对界面可见（与用户输入区分，可在模板抬头注明），不新增事件类型。

2. **计划保留在 system 尾部**（保持现状）：`AgentRunService.java:383-385` 的注释是对的——
   计划放 system 可在压缩折叠后不丢。代价是计划更新时 system 变化，但前缀缓存按 token 匹配，
   尾部变化只损失 plan 段之后的缓存，前面的 base→workflow→tools→output 仍然复用。
   这正是 dsh「越动态越靠后」的排序收益，Pilot 的现有段落顺序已经把 plan 放在最后，无需调整。

3. **`{{today}}` 下移**：从 `base.md` 的「环境」段挪到 system 末尾的独立小段
   （顺序：… → output → rules → today → plan）。`platform` / `workspace` 稳定，留在 base 的定锚段；
   `today` 是 system 中唯一的日级变化项，下移后跨天只损失尾部缓存。

**变更后 system 段落顺序**（§4.4 的 order 值即按此固化）：

```
base → workflow → tools → tools-agents* → orchestration → dispatch* → output → rules → today → plan
                    (* 按 availableTools 过滤)
```

**验收**：同一会话中除 plan 段与 today 段外，两次 run 的 system 逐字节相同；
新会话首条消息前可见仓库现状；长会话第二轮起不再执行 git status。

### 4.3 正文去重与收缩（P2，token 经济）

**原则**（写入 `prompts/README` 或本文档附录）：单一工具的使用细节只出现在
`prompts/tools/<name>.md`；system 段落只保留两类内容——跨工具的策略约束、工具间的关系（选择表）。

具体改动：

1. `system/tools.md`：
   - 删「构建和测试可能跑很久，run_command 记得把 timeoutSeconds 设到 300」——
     已在 `prompts/tools/run_command.md:5`。
   - 子代理适用范围的细节收敛到 `prompts/tools/spawn_agent.md` 与 `dispatch_agents.md` 工具描述；
     system 只留一行「信息量大但结论短的只读调研派 spawn_agent；跨模块并行改派 dispatch_agents」。
   - 保留：白名单与「不支持管道、重定向、`&&` 串联」（系统性限制，模型最易犯错处）、
     「独立调用可并行」、精简后的选择表（≤8 行）。
2. `system/output.md` 从 14 行收缩到 ≤8 行：保留「结论先行、`path:line`、验证结果、未验证要标明」
   四条硬约束与「纯问答不套格式」；删排比式展开。
3. `system/orchestration.md` 通用部分（§4.1 拆分后）同步精简，只留对单 agent 也成立的条款。

**验收**：全文检索确认每条工具细节只有一个出处；system 正文（不含 rules/plan）行数下降 ≥25%。

### 4.4 段落注册表化（P3，结构收敛 + 扩展铺路）

**问题**：`SYSTEM_SECTIONS` 是 `PromptLibrary.java:56` 的硬编码 List，
`AgentRunService` 又在库外追加 repository / rules / plan——两处拼接，顺序知识分散。
扩展体系（skill 能力目录、MCP 工具风险提示）落地时都要注入 system，正是 dsh 用注册表解决的问题。

**方案**（取其形不取其重，不做 waterfall、不做作用域）：

```java
public record SystemSection(String name, int order, String template, Set<String> requiresTools) { }

// PromptLibrary 内部
private static final List<SystemSection> SECTIONS = List.of(
        new SystemSection("system/base",         0,     null, null),
        new SystemSection("system/workflow",     100,   null, null),
        new SystemSection("system/tools",       200,   null, null),
        new SystemSection("system/tools-agents", 300,   null, Set.of("spawn_agent", "dispatch_agents")),
        new SystemSection("system/orchestration",400,   null, null),
        new SystemSection("system/dispatch",    500,   null, Set.of("dispatch_agents")),
        new SystemSection("system/output",      9000,   null, null),
        new SystemSection("system/project-rules", 10100, null, null),
        new SystemSection("system/today",       10200, null, null),
        new SystemSection("system/plan-context", 10300, null, null),
        new SystemSection("system/plan-done",   10300, null, null));
```

- order 数值稀疏分配，语义对齐 dsh：身份 0 → 流程/策略 100–500 → 输出 9000 →
  环境/约定 10000+ → 计划 10300（最动态，压轴）。
- frontmatter 的 `requires` 与记录的 `requiresTools` 二选一即可（记录声明优先级更高，
  md frontmatter 作为轻量标记保留给拆分中的过渡段）；
  v1 以代码内声明为准，避免解析歧义。
- `AgentRunService` 的 repository / rules / plan 注入逻辑不变（plan 依赖会话态，
  留在 service 层；注册表只管模板段落），但通过注册表拿段落顺序，消除双处排序知识。
- **为扩展预留**：`ToolSource`（扩展体系的 builtin→plugin→mcp 装配线）将来增加
  `default Optional<SystemSection> systemSection()`；本文档只保证
  `PromptLibrary.system(variables, availableTools)` 这一个入口存在且行为明确，
  扩展侧注册表落地时（`docs/DESIGN-extension.md`，另行成文）不再改动此签名。

**验收**：现有渲染结果与 §4.1–4.2 完成后的输出逐字节一致（纯结构重构）；
`AgentLoop.DEFAULT_SYSTEM_PROMPT` 不变。

### 4.5 「先读后改」机制化（P3，提示词让位于机制）

dsh 的 `fs-observation-policy` 在未读文件上直接拒绝编辑并返回补救指引；
Pilot 同款约束现在只是 `workflow.md:21` 的一句话，模型可绕过。

**方案**：

1. `pilot-core` 新增会话级 `ReadGuard`（接口 + 内存实现）：

   ```java
   public interface ReadGuard {
       void observed(Path file);                 // read_file 成功后记录
       boolean isObserved(Path file);            // edit 前校验
       static ReadGuard allowAll() { ... }        // 测试与降级用
   }
   ```

2. 记录时机：`read_file` 成功返回正文后记 `observed(path)`；
   **`create_file` / `edit_file` 成功后也记**——dsh 明文豁免「本会话刚创建或编辑过的文件」
   （`tool-fs/src/edit.ts:82`），否则连续编辑会误伤。
3. `EditFileTool` / `CreateFileTool` / `SandboxEditFileTool` / `SandboxCreateFileTool`
   构造参数追加 `ReadGuard`（默认 `allowAll()`，现有调用点与测试零改动）；
   `WorkspaceToolFactory` 按会话装配实例。
4. 未读即改时返回结构化错误（对齐 `EditFileTool` 现有错误风格）：

   ```
   ERROR: 本会话尚未读取过 path。先 read_file 查看当前内容与行号，再给出 old_string。
   ```

5. 提示词侧：`workflow.md` 的「修改前必须先用 read_file 看过该文件」改为一句机制说明
   （「edit_file 会拒绝本会话未读过的文件」），不再承担劝导职责。

**风险与对策**：会话续跑（进程重启恢复历史）后 ReadGuard 归零，模型会先被拒绝一次再重读——
可接受（一次额外 read_file 换取确定性）；后续如需优化，可在会话恢复时从历史
`tool` 消息中重建已读集合，列为远期项。

**验收**：未读先改被拒且错误信息可指导下一步；刚创建后立即编辑不误伤；
`allowAll()` 路径行为与现状完全一致。

### 4.6 重复调用渐进提醒（P3，柔化硬中断）

**问题**：`repeatedToolCallLimit=3` 直接 `RunFailed`，模型没有纠错机会；
dsh 的 `repeat-tool-reminder` 用 `[3, 5, 8]` 阈值渐进：先注入 user 提醒，升级到 8 次才中断。

**方案**：

1. `AgentLoop.Config` 增加两个字段（保持现有构造器链兼容，重载默认值）：
   `repeatRemindAt`（默认 3，与现阈值同档）、`repeatAbortAt`（默认 5）。
2. 达到 `repeatRemindAt` 且尚未到 `repeatAbortAt` 时，注入 user 提醒（新模板
   `prompts/loop/repeat-reminder.md`，变量：`{{tool}}`、`{{count}}`），继续循环；
   提醒只注入一次，第二次触顶（`repeatAbortAt`）才 `RunFailed`。

   模板要点：指出连续次数与调用签名、说明「相同调用大概率返回相同结果」、
   要求换方法（扩大上下文 / 整文件重写 / 说明卡点）。
3. 事件：复用 `RunContinued`（reason 注明「重复调用提醒」），不新增事件类型。
4. 兼容：`repeatedToolCallLimit` 字段保留为 `repeatAbortAt` 的别名语义
   （旧配置读入时映射），对外行为从「3 次中断」变为「3 次提醒、5 次中断」，
   属行为改进，在 CHANGELOG / README 权限说明中注明。

**验收**：现有 AgentLoop 重复调用测试改为断言「3 次收到提醒、5 次失败」；
提醒消息进对话历史并可被压缩折叠。

## 5. 涉及文件与接口变更总表

| 文件 / 类 | 变更 | 子项 |
| --- | --- | --- |
| `prompts/system/tools.md` | 删重复细节；agents 两行拆出；`requires` 标记 | 4.1 4.3 |
| `prompts/system/tools-agents.md`（新） | 子代理 / 调度选择行，`requires: [spawn_agent, dispatch_agents]` | 4.1 |
| `prompts/system/orchestration.md` | 拆分，通用条款精简 | 4.1 4.3 |
| `prompts/system/dispatch.md`（新） | 调度专属规则，`requires: [dispatch_agents]` | 4.1 |
| `prompts/system/output.md` | 收缩至 ≤8 行 | 4.3 |
| `prompts/system/base.md` | `{{today}}` 移出 | 4.2 |
| `prompts/system/today.md`（新） | 日级事实段，置于 rules 之后 | 4.2 |
| `prompts/context/repository.md`（改名自 system/） | 模板头注明「会话起始快照」 | 4.2 |
| `prompts/loop/repeat-reminder.md`（新） | 重复调用提醒 | 4.6 |
| `PromptLibrary` | frontmatter 解析；`system(variables, availableTools)` 重载；`SystemSection` 注册表替换 `SYSTEM_SECTIONS` | 4.1 4.4 |
| `ToolRegistry` | 增加 `names()` | 4.1 |
| `AgentRunService` | `systemPrompt()` 接收工具集；repository 注入移到会话首条 user 消息 | 4.1 4.2 |
| `ReadGuard` / `ReadTracker`（新） | core 新增；四个文件工具构造参数追加（默认 `allowAll()`） | 4.5 |
| `WorkspaceToolFactory` | 按会话装配 `ReadTracker` | 4.5 |
| `AgentLoop` / `AgentLoop.Config` | `repeatRemindAt` / `repeatAbortAt`；提醒注入 | 4.6 |
| `MultiAgentService.systemPrompt` | 同步应用过滤与段落顺序（子代理工具集） | 4.1 4.2 |

## 6. 实施顺序与验收

三批，每批独立可发布、可回滚：

**批次 1（约半天）——正确性与去重**：4.1 + 4.3。
验收：`multi.enabled=false` 的 system 快照不含 `dispatch_agents`；工具细节单一出处；
全部现有测试通过。

**批次 2（约一天）——分离与缓存**：4.2。
验收：同会话跨轮 system 除 plan/today 外逐字节一致；新会话首条消息含仓库快照；
第二轮起无 git status IO。

**批次 3（1–2 天）——结构与机制**：4.4 + 4.5 + 4.6。
验收：4.4 为纯重构（输出逐字节不变）；4.5 未读拒改、刚建不误伤；
4.6 提醒-中断两段式行为符合新测试断言。

## 7. 风险与兼容性

| 风险 | 评估与对策 |
| --- | --- |
| frontmatter 手写解析的边界 | 只支持一个键、两种列表写法；解析失败抛异常不降级。classpath 与外部目录同样对待，与「变量缺失即抛异常」一致 |
| 仓库快照移位后模型「不知道 git 状态」 | 模板本就要求实查；会话内改动模型自己产生，无信息损失 |
| 前缀缓存收益依赖模型端实现 | DeepSeek / vLLM / sglang 均按前缀命中；本文档不承诺具体倍率，只承诺「稳定段不再逐轮变化」 |
| ReadGuard 对续跑会话的冷启动拒绝 | 一次额外 read_file，可接受；历史重建列为远期项 |
| 重复调用从「3 次中断」变「3 提醒 5 中断」 | 有意的对外行为变化，README 权限说明与 CHANGELOG 注明 |
| 计划仍在 system 内 | 维持现状的有意取舍（防压缩丢失）；扩展体系若引入 dsh 式 runtime context，再评估迁移 |

## 8. 测试要点

- `PromptLibraryTest`：frontmatter 合法 / 非法 / 缺失三分支；`requires` 过滤；
  外部目录覆盖带 `requires` 段落；变量缺失仍抛异常（回归）。
- `AgentLoopTest`：重复调用「3 提醒、5 中断」时序；提醒消息入历史且可压缩；
  `repeatAbortAt` 显式配置的兜底。
- `EditFileTool` / `CreateFileTool` 测试：未读拒改、已读放行、刚建放行、`allowAll()` 全放行；
  沙箱版工具同套断言。
- `AgentRunService` 集成：`multi.enabled` 开 / 关两种 system 渲染快照；
  首条消息注入仓库快照、第二轮不注入、system 逐字节稳定。
- 评估基线（`evaluation/`）跑一遍既有用例，观察行为回退（若有）记录并归因。

## 附录 A：dsh 参照文件索引

| dsh 文件 | 对应本设计的子项 |
| --- | --- |
| `packages/core/system-prompt/src/index.ts:131-179`（段落与上下文顺序表） | 4.4 |
| `packages/core/system-prompt/README.zh.md`（system/context 分离、{{cwd}} 禁令、KV cache 说明） | 4.2 |
| `packages/bundle/sdk-minimal/cordis.patch.yml:113-118`（极简 persona） | 4.3 |
| `packages/fs/tool-fs/src/edit.ts:77-83`（scope-aware 工具段、先读后改豁免） | 4.1 4.5 |
| `packages/workflow/tool-workflow/src/index.ts:324-330`（工具指导归属主约定） | 4.1 4.3 |
| `packages/bundle/base/cordis.patch.yml:448-452`（repeat-tool-reminder `[3,5,8]`） | 4.6 |
| `packages/bundle/base/cordis.patch.yml:332-346`（plan mode 段落的行为边界写法） | 4.3 的行文参照 |

> AI生成
