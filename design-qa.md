# Pilot UI design QA

## Scope

- Reference: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/01-pilot-empty-preview-before.png`
- Implementation: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/02-pilot-empty-preview-optimized.png`
- Side-by-side comparison: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/03-pilot-empty-preview-comparison.png`
- Loaded file preview: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/05-pilot-file-preview.png`
- Narrow viewport: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/04-pilot-mobile-preview.png`

## Comparison conditions

- Reference and implementation were captured at 1332 x 773 CSS pixels and 1332 x 773 image pixels (1x density).
- Both use the dark theme, session `ses_256f`, a completed 3/3 long-running task, the review panel open, and the Preview tab selected without a file.
- The full-view comparison is sufficient for the primary issue because the target is the relationship between the three major regions. No focused crop was required; the original-size captures retain readable header, body, and empty-state details.

## Findings and fixes

### Pass 1: reference audit

- P1: The empty inspector used about 647 px (49% of the viewport) while the conversation was constrained to about 481 px. The inactive panel dominated the working surface.
- P2: The inspector tab/header stack consumed too much vertical space for a passive state.
- P2: The single-line empty message did not explain the next action.
- P2: Breadcrumb, session id, status, theme, settings, and review controls competed for the same horizontal priority.
- P2: Long-form assistant content wrapped too aggressively, increasing scroll depth and making comparison/review tiring.

Applied corrections:

- Empty Preview now uses `clamp(340px, 28vw, 390px)` at desktop widths, restoring the conversation to about 707 px in the same viewport.
- Preview expands only when actual file content is loaded; Changes keeps a wider review-oriented width.
- Inspector navigation was compressed and the empty state now has a clear title plus a direct instruction.
- The top bar gives the task title flexible space while preserving controls without overlap.
- Conversation/composer reading width and spacing were tuned for long technical answers.
- The narrow layout uses a modal-like inspector with an explicit close control.

### Pass 2: implementation audit

- P0: none.
- P1: none.
- P2: none.
- P3: the reference sidebar was manually narrower than the new default. This is acceptable because the splitter remains user-adjustable and the new default improves project/file legibility.

## Interaction and regression evidence

- Opened and closed the review panel with the header control.
- Switched Activity -> Preview and verified the disabled refresh control plus empty-state guidance.
- Expanded Workspace Files and opened `pom.xml`; the inspector widened and rendered readable source without degrading the main layout.
- Tested the same Preview state at 390 x 844; the inspector became an overlay, the close action worked, and reopening preserved the selected tab.
- Browser console errors/warnings: none.
- `npm run check`: passed.
- `npm test`: 54/54 passed.
- Maven package for the current web implementation: passed.
- `git diff --check`: passed.

## Content presentation QA (2026-10-29)

### Scope and comparison conditions

- Reference: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/06-content-format-before.png`
- Implementation: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/07-content-format-after.png`
- Side-by-side comparison: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/08-content-format-comparison.png`
- Both captures use a 910 x 664 CSS-pixel viewport at 1x density, the dark theme, session `ses_256f`, and the completed 3/3 task near the final-answer boundary.
- The reference contains a transient retry notice and the two captures differ slightly in vertical scroll position. The comparison is therefore used to judge content hierarchy, reference treatment, and answer actions rather than exact pixel alignment.
- The original-size full view preserves the relevant body text, status strip, file references, and composer. A focused crop was unnecessary.

### Pass 1: reference audit

- P1: automatic continuation joined self-review, tool/research progress, and the final conclusion into one long assistant body, so users could not distinguish process from deliverable.
- P1: local file references appeared as Markdown-like path text instead of clear, actionable evidence links.
- P2: code fences, callouts, tables, task lists, sources, and status text had insufficient type-specific treatment.
- P2: completed answers lacked a direct copy action.
- P2: the model output contract did not consistently request outcome-first writing, meaningful source labels, line-addressable file links, or restrained alert use.

Applied corrections:

- Tool-bound and continuation-bound intermediate text is reconstructed as collapsed `Pilot · 过程更新` disclosures; the final conclusion remains expanded as the primary deliverable.
- Workspace Markdown links are rendered as compact file chips with basename and target line. Activating a chip opens Preview and scrolls near the requested line.
- Added semantic presentation for code blocks (language label and copy), GitHub-style alerts, scrollable/sticky-header tables, task lists, external references, and answer copy actions.
- Added a presentation grammar to the system output prompt without changing the message, SSE, tool, or history protocol.
- Added isolated parsing and path-safety tests for callouts, file references, and language labels.

### Pass 2: implementation audit

- P0: none.
- P1: none.
- P2: none.
- P3: historical messages only demonstrate the progress disclosures and file-reference treatment. Code-block and callout visuals are covered by renderer logic and tests, but should also be exercised by a future live-model fixture when an automated browser fixture suite is introduced.

### Interaction and regression evidence

- History reconstruction produced five collapsed progress disclosures followed by one independently expanded final answer.
- A progress disclosure opened and closed without changing the final-answer state.
- Activating `README.md · L140` opened Preview, displayed `README.md · L140`, and positioned the document around the task-control section containing that line.
- At 390 x 844 the content column had a 390 px document width and 390 px scroll width: no horizontal overflow.
- Browser console errors/warnings: none.
- `npm run check`: passed.
- `npm test`: 54/54 passed.
- `bash mvnw test`: reactor build passed; `pilot-core` 243 tests (1 skipped), `pilot-server` 193 tests (2 skipped), no failures or errors.
- `git diff --check`: passed.

## Runtime telemetry and context lifecycle QA (2026-10-29)

### Scope and comparison conditions

- Source visual truth: `/var/folders/v3/fbb0zyg91pd0qyt0gp6fnqcc0000gn/T/codex-clipboard-6d1afc7d-5f62-40da-bd80-6f956a5fcf99.png`
- Matched implementation: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/design-qa-capabilities-1332x773.jpg`
- Side-by-side comparison evidence: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/design-qa-source-vs-implementation.jpg`
- Focused context state: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/design-qa-context-open.jpg`
- Narrow context state: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/design-qa-mobile-context.jpg`
- Source and matched implementation are both 1332 x 773 image/CSS pixels at 1x density. Both use the dark theme, session `ses_256f`, a completed 3/3 long-running task, and the review panel open. The source has Preview selected; the implementation intentionally has the new Capability Map selected.
- The focused desktop context capture is 1280 x 720 at 1x. The narrow capture is 390 x 844 at 1x; its document and scroll widths are both 390 px.

### Full-view comparison evidence

- The implementation preserves the established Pilot shell, restrained dark tokens, orange active indicator, green success states, compact top bar, and long-form reading hierarchy.
- The source's oversized empty Preview region is replaced by the previously corrected narrow inspector and a dense capability map. The conversation retains materially more width without hiding the review controls.
- The new capability content is explicitly separated into `可用`, `待确认`, and `候选`; unimplemented directions are not presented as working controls.

### Focused region evidence

- The context popover was inspected at desktop and 390 px. Percentage, meter, estimated/exact disclosure, elapsed time, model requests, input/output tokens, and compaction action remain readable.
- At 390 px the popover measures 366 x 307 px at x=12 and stays inside the 390 px viewport. Composer controls and the send action remain reachable; there is no horizontal overflow.
- A separate crop was not needed because the original desktop and narrow screenshots preserve readable labels and control states.

### Comparison history

#### Pass 1 findings

- P2, stale runtime notices: replaying a completed task surfaced historical retry/auto-continue notices as if they were current. Fix: transient notices now render only for live events, and resetting the view clears old notices.
- P2, misleading legacy token values: sessions created before `run.usage` could look like zero-token runs. Fix: legacy tasks show `—` plus `旧任务未记录 Token`; estimates carry `≈`, model-reported values are labeled, and the UI says current totals cover the main task.
- P2, context action discoverability: compaction existed only as an internal lifecycle. Fix: added a composer context chip, usage popover, explicit `压缩上下文` action, and command-palette entry. The copy explains that compaction calls the model, preserves chat/audit history, and is disabled during a run.

#### Pass 2 post-fix evidence

- P0: none.
- P1: none.
- P2: none.
- P3: historical sessions cannot retroactively show token totals. This is deliberately represented as unavailable data instead of an estimate and does not affect new runs.

### Required fidelity surfaces

- Fonts and typography: existing sans/monospace hierarchy, weights, line height, truncation, and small status labels remain consistent; desktop and narrow labels wrap without clipping.
- Spacing and layout rhythm: the popover uses the existing 12 px composer gutter, the capability cards follow the inspector rhythm, and neither desktop nor 390 px states overlap persistent controls.
- Colors and tokens: orange remains the active/context emphasis, green indicates live capability, and muted/outlined states distinguish future items without implying availability. Contrast remains legible in dark mode.
- Image quality and assets: the reference and new surfaces contain no required product imagery, logos, illustrations, or raster assets to reproduce. Existing text/icon treatment is retained; no placeholder imagery or CSS illustration was introduced.
- Copy and content: exact, estimated, unavailable, implemented, pending, and candidate states are named distinctly. Compaction consequences and current aggregation scope are disclosed in the UI.

### Interaction and regression evidence

- Opened the context popover, loaded live context status, and verified the enabled compaction action without invoking a paid model compaction during visual QA.
- Opened the review panel and switched to Capability Map; all capability sections and status badges rendered.
- Tested the main task and context popover at 390 x 844 with no horizontal overflow.
- Browser console errors/warnings: none.
- `npm run check`: passed.
- `npm test`: 55/55 passed.
- Full `bash mvnw test` reactor: passed with no failures or errors.
- `git diff --check`: passed.

## Warm graphite dark-theme QA (2026-10-29)

### Scope and comparison conditions

- Source visual truth: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/design-qa-capabilities-1332x773.jpg`
- Implementation: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/design-qa-graphite-after-1332x773.jpg`
- Side-by-side comparison: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/design-qa-graphite-comparison.jpg`
- Focused context state: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/design-qa-graphite-context.jpg`
- Settings state: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/design-qa-graphite-settings.jpg`
- Narrow state: `/Users/smt/.codex/visualizations/2026/10/29/01a1857e-150b-7761-a71e-c76557d9b390/design-qa-graphite-mobile.jpg`
- Source and implementation are both 1332 x 773 CSS/image pixels at 1x density, dark theme, session `ses_256f`, completed 3/3 task, review panel open, Capability Map selected.
- The narrow state is 390 x 844 at 1x density with the context popover open; document and scroll widths are both 390 px.

### Full-view and focused comparison evidence

- The original near-black canvas flattened the main thread, sidebar, inspector, cards, and composer into one dark field. The implementation uses warm graphite surfaces (`#121419` through `#292e38`) so the three work regions remain distinct without becoming a stack of high-contrast cards.
- The context popover, settings modal, composer focus state, code/file chips, capability cards, selection state, scrollbars, and top bar were inspected as focused regions. They use the same tokens and retain readable boundaries.
- The implementation intentionally preserves layout, typography, content, icon family, interaction structure, and amber identity; only palette, elevation, focus treatment, and active-row detail changed.

### Comparison history

#### Pass 1 findings

- P1, eye strain and weak depth: `#0a0a0a` and adjacent neutral blacks made long reading harsh while failing to distinguish large work regions. Fix: moved the base to warm graphite and introduced four controlled surface levels.
- P2, weak active-session affordance: the selected session depended on a slightly lighter rectangle. Fix: added a 2 px amber inset locator plus a restrained inner highlight.
- P2, inconsistent interaction polish: selection, scrollbar, popover elevation, composer focus, and cards still carried older cool-blue or pure-black treatments. Fix: mapped them to the current accent and graphite tokens, with restrained shadows only on floating/interactive surfaces.
- P2, small muted text contrast: the first graphite pass used `#7d818a`, which reached only 3.98:1 on the second-level panel. Fix: raised `--dim` to `#91959e`; it reaches at least 4.54:1 on the darkest selected surface while keeping a visible hierarchy below `--muted`.

#### Pass 2 post-fix evidence

- P0: none.
- P1: none.
- P2: none.
- P3: the amber focus ring is intentionally more visible than before. This is retained for keyboard and open-popover clarity.

### Required fidelity surfaces

- Fonts and typography: unchanged system sans/monospace stack, weights, sizes, line heights, wrapping, and truncation; the warmer foreground tokens reduce glare without softening hierarchy.
- Spacing and layout rhythm: unchanged region widths, gutters, card spacing, radii, and responsive rules. Shadows were added only where they clarify interactive elevation.
- Colors and tokens: warm graphite replaces pure black; warm gray text replaces stark white; amber, green, warning, and error semantics remain distinct. Measured primary, secondary, muted, dim, and amber contrast ratios satisfy the intended text roles.
- Image quality and assets: no source imagery or product assets are present in these states, and no placeholder imagery, CSS illustration, or replacement asset was introduced.
- Copy and content: unchanged; the palette refinement does not alter task or capability meaning.

### Interaction and regression evidence

- Opened Capability Map, context popover, and Settings; verified selected, open, focused, modal, and status states.
- Tested the context popover at 390 x 844; body background is `rgb(18, 20, 25)`, popover background is `rgb(26, 29, 36)`, and there is no horizontal overflow.
- Browser console errors/warnings: none.
- `npm run check`: passed.
- `npm test`: 55/55 passed.
- `git diff --check`: passed.

## Running composer primary-action QA (2026-10-29)

### Scope and comparison conditions

- Source reference: `/var/folders/v3/fbb0zyg91pd0qyt0gp6fnqcc0000gn/T/codex-clipboard-8eed8867-7ce1-4759-8844-3d1b8acbc1f7.png` (1341 x 854, browser chrome included).
- Implementation evidence: local Pilot in the in-app browser, tab 9, captured inline at 910 x 664 and 1x density. The browser returned the captures as inline image bytes rather than filesystem paths.
- Real state used for verification: active session `ses_af0f`, with a task genuinely running. The same composer was inspected in three consecutive states: empty draft, typed draft, and cleared draft.
- Because the reference and implementation viewports differ, comparison was limited to the focused composer action: fixed 32 px control size, placement, icon state, label, and transition behavior. No full-layout pixel-fidelity claim is made.

### Comparison history

#### Pass 1 finding

- P2, ambiguous running action: while a task was running, the primary composer action remained a send arrow even with no draft, so the most immediate action did not match Codex's stop affordance. Fix: introduced an explicit composer action resolver with `stop`, `queue`, `send`, and `save` modes and drove both visuals and behavior from that single state.

#### Pass 2 post-fix evidence

- Empty composer during an active run: label changed to `停止当前`, accessible name to `停止当前任务`, tooltip to `停止当前任务 (Esc)`, and the arrow changed to a neutral square stop glyph without changing button dimensions.
- Typing a draft during the same run: label changed immediately to `排队下一件事`, accessible name and tooltip changed to `排队下一件事 (Enter)`, and the send arrow returned.
- Clearing that draft: the button returned immediately to the stop state. Adding an attachment follows the same send/queue path as text.
- Queue editing remains a save action and never becomes stop. Cancellation and pause-in-flight states prevent duplicate control requests.
- No real message was sent and the live task was not cancelled during QA.
- P0: none.
- P1: none.
- P2: none.

### Required fidelity surfaces

- Fonts and typography: existing compact labels, tooltip wording, and icon scale are retained; no layout shift occurs between stop and send.
- Spacing and layout rhythm: the existing composer dimensions and right-edge alignment are unchanged.
- Colors and tokens: stop uses the neutral foreground/panel tokens; send/queue continues to use the selected accent token, so it works across themes and accent choices.
- Image quality and assets: both icons are inline SVGs at the existing size; no raster placeholder, emoji, or CSS-drawn replacement was introduced.
- Copy and content: `停止当前` and `排队下一件事` distinguish the two operations, with matching ARIA labels and keyboard hints.

### Interaction and regression evidence

- Verified all three real-time state transitions in the in-app browser against an actual running session.
- Verified `Escape` requests cancellation only after open menus and overlays have had priority.
- Browser console errors/warnings: none.
- `npm run check`: passed.
- `npm test`: 62/62 passed, including seven focused composer-state tests.
- `git diff --check`: passed.

final result: passed

## Web 与桌面工作台统一优化（2026-11-02）

### 范围与设计依据

本轮按用户扩大的范围检查整个共享工作台：项目与会话导航、工作区选择和文件树、任务正文与输入区、对话/变更/预览/命令/能力/浏览器/活动七个面板，以及外观/模型/权限/技能/用量/诊断/关于七个设置页。桌面壳另测启动失败、重新连接、原生菜单与剪贴板。

保留原有三栏结构、Pilot 标识和中性深灰主题，统一文字层级、控件尺寸、焦点、边框与选中态。使用现有 design-taste-frontend 与 product-design:audit 技能，无需安装额外技能。

参考与取舍：

- [VS Code Views UX](https://code.visualstudio.com/api/ux-guidelines/views)：工作区树采用文件夹/文件图标、层级缩进、行选中与紧凑工具栏。
- [Cursor Agents Window](https://cursor.com/docs/agent/agents-window)：借鉴任务、导航和审阅的分工，保留 Pilot 现有功能结构。
- [Codex Code Review](https://learn.chatgpt.com/docs/code-review)：将检查改动与正文阅读区分，代码预览有内容时恢复合理宽度。
- [Electron 原生菜单](https://www.electronjs.org/docs/latest/tutorial/menus)：缩放与编辑采用标准 role，让快捷键跟随平台。

### 主要问题与实现

1. 文件树原先是接近纯文本的清单，没有连接到已有图标样式。现在独立成 WorkspaceTree，加入目录展开图标、文件类型图标、28px 行高、选中态、长名称省略和完整路径提示；支持方向键、Home/End、字符定位、空目录、读取失败重试与刷新后焦点恢复。
2. 会话列表与文件树分开滚动，标题保留在可见位置；空列表不再占据固定的大块高度。悬停操作不再一直占用会话标题宽度。
3. 文件打开后自动显示预览，修复遗漏的 has-content 状态，已加载代码获得更宽的面板。预览响应按请求/会话/工作区隔离，切换或新建任务时清理旧文件内容与加载状态。
4. 工作区选择器原来把后端根数组当成 roots 对象，并读取错误的 entries 字段。现在匹配真实 roots / directories 返回格式，支持空文件夹、无效路径、异步浏览防串页、切换失败留在弹窗。补充无会话的目录树与文件预览接口，复用已有路径和大小限制。
5. 七个面板采用持续可见的名称、说明和关闭动作，图标配短标签；支持键盘切换。取消已显示标签旁重复出现的自定义气泡。
6. 设置统一为导航与独立内容滚动区，标题和导航不再随长诊断内容消失；表单占满合理宽度，长路径和命令换行。主题预览展示简化工作台，窄屏改为横向分类；Tab/Shift-Tab 留在弹窗，Escape 关闭并返回触发控件。
7. 简短且重复的任务完成信息收敛为状态行，保留有独立内容、文件改动或续跑动作的结果卡。正文与输入区域统一字号和间距。
8. 桌面新增标准界面缩放菜单；服务不可用时展示实际服务地址和“重新连接”，失败后仍可重试，无需关闭重开应用。
9. 新增 workbench.css 作为本轮组件样式归属，清理旧样式中对应的文件树、设置、弹窗、主题预览等重复规则；保留已有未提交功能改动。

### 验证结果

| 验证 | 结果与覆盖 |
| --- | --- |
| `npm --prefix pilot-web test` | 227/227 通过，包含预览乱序响应、工作区切换、失败保留状态与新任务清理 |
| `npm --prefix pilot-web/frontend run test:composer` | Chrome / Electron 通过：粘贴、拖入、文件选择、大小/数量限制、去重、草稿、失败保留、纯附件、缩略图、IME、换行；macOS 原生文件剪贴板 + Cmd+V 两端通过 |
| `npm --prefix pilot-web/frontend run test:interaction` | Chrome / Electron 通过：引用补全、菜单、排队、发送竞态、差异引用；文件树键盘/空目录/删除焦点条目/失败重试；真实接口格式的选择器、失败不关闭；七个设置页、弹窗焦点、主题、面板键盘；1440/768/390 宽度与 900×420 短窗口 |
| `SessionFilesApiTest` | Java 25 下 10/10 通过，覆盖草稿/会话文件接口、中文与空格路径、排序、二进制、数量边界、路径穿越和符号链接越界 |
| 独立真实服务 HTTP 与 UI | 临时数据目录、19099 端口：roots/browse/files/file 正常，越界 400；浏览器实测首条消息发送前选择工作区并预览文件，未创建空会话 |
| `npm --prefix pilot-desktop test` | 11/11 通过 |
| `npm --prefix pilot-desktop run smoke` | Pilot 品牌宿主、浏览器控制与本地页面加载通过 |
| `npm --prefix pilot-desktop run test:ui` | 实际品牌 Electron 主进程：离线页、连续重试失败、服务恢复后重连成功、原生菜单项通过 |
| `build` / `check` / `git diff --check` | 通过；check 使用跨平台临时目录且不覆盖运行产物 |
| 新增样式/文件树/图标的 app 模式设计 lint | 0 FAIL；9 WARN 为静态规则对 none、真实弹窗阴影/遮罩以及主题缩略图线条绘制的提示，已人工检查 |

浏览器手工复核七个工作区面板均正常渲染；控制台无错误。390px 模型设置页文档宽度与视口同为 390px，内容区域无横向溢出。自动化功能测试使用固定 API 响应以免修改个人会话；真实接口另用独立服务验证。

### 视觉证据与边界

证据目录：`/Users/smt/.codex/visualizations/2026/11/02/01a19b15-eceb-7c02-9620-9e6f0e2253b6/pilot-polish/`。

- 修改前：before-appearance.png、before-settings-诊断.png、before-panel-*.png 等。
- 修改后：after-start.png、after-appearance.png、after-diagnostics.png、after-draft-preview.png、after-mobile-settings.png、after-panel-*.png、after-desktop-offline.png。
- 同目录保存本轮测试日志。桌面截图为 Retina 分辨率，Web 常规视口为 1280×720；窄屏为 390×844。起始页与历史任务页用于验证各自状态，不作像素级同内容对比。

本轮没有调用付费模型来评测任务生成质量，没有实机验证 Windows/Linux 原生剪贴板，也没有把安装打包、自动更新或所有历史样式文件重写算作已完成。当前 IDE 启动的 9999 服务及既有桌面进程仍是旧后端/旧主进程；前端产物已构建，新草稿文件 API 和桌面菜单需重启服务及桌面窗口后使用。新后端已通过独立服务验证。

## 三平台兼容补充（2026-11-02）

本轮“三个平台”按 Windows、macOS、Linux 处理，范围包含它们运行的 Web 和 Electron 桌面工作台；平台兼容要求同时记录在 AGENTS.md。

| 项目 | Windows | macOS | Linux |
| --- | --- | --- | --- |
| 快捷键与输入法 | Ctrl，识别 AltGr/IME，避免误触动作 | Cmd，识别 IME | Ctrl，识别 AltGr/IME |
| 文件与图片输入 | 标准 File/DataTransfer 的 files/items 路径 | 同一标准路径 | 同一标准路径；仅 URI/文件标记时提示使用文件选择或拖入 |
| 复制代码、会话 ID、文件与日志 | 统一 Clipboard API + execCommand 回退 | 同左 | 同左 |
| 目录比较 | 盘符与 UNC 路径忽略大小写 | POSIX 路径保留大小写，使用服务端规范化结果 | POSIX 大小写不同的目录保持独立 |
| 桌面启动 | electron.exe | Pilot.app 品牌宿主 | electron 可执行文件 |
| 窗口尺寸动作 | 最大化/还原 | 原生 Zoom | 最大化/还原 |
| 构建与测试入口 | 直接调用 Node/Vite 和真实 Electron 可执行文件 | 同左 | 同左 |

具体修补：

- 所有文字复制入口使用同一兼容实现；在弹窗内部执行复制回退，成功或失败都清理临时控件，并恢复原焦点与选区。
- 原始剪贴板只含文件 URI 或不可读取文件标记时，显示替代操作，不把本地路径文字当成已添加的附件；单个 getAsFile 失败不会中断其他条目。
- 附件本地编号支持缺少 randomUUID、受限 crypto 的环境，不会因普通 HTTP / WebView 能力缺失而卡在读取中。
- 前端目录比较按路径格式处理 Windows 盘符/UNC 与 POSIX，保留根目录；后端目录快捷入口按本机 Path 语义去重，避免 Linux 大小写不同的目录被合并。
- Vite 构建改为 process.execPath 启动 CLI，去除 .cmd/shell 及带空格路径的解析差异；构建失败不清理上一版可用产物。
- GUI 回归脚本采用真实的平台 Electron 二进制入口；原生文件剪贴板测试使用 text/uri-list，由 Electron 映射到各平台格式，再分别按 Meta+V / Control+V。
- 原有三平台 CI 矩阵新增桌面依赖安装和单测，安装排在 Maven 之前，供桌面启动相关 Java 测试使用。

依据：[MDN Clipboard API](https://developer.mozilla.org/en-US/docs/Web/API/Clipboard_API)、[Electron 文件剪贴板格式](https://www.electronjs.org/docs/latest/api/clipboard-item#files-the-texturi-list-mime-type)、[Node 子进程与 Windows 脚本差异](https://nodejs.org/api/child_process.html)、[Electron 窗口 API](https://www.electronjs.org/docs/latest/api/base-window)。

验证：前端 234/234，桌面 14/14，后端 WorkspaceServiceTest / SessionFilesApiTest / DesktopWindowLauncherTest 共 17/17；构建、check 与 diff 检查通过。Chrome 和 Electron 的输入及交互回归通过，包括 URI-only 粘贴不污染草稿、缺少 randomUUID 时文件读取，以及 macOS 真实文件剪贴板快捷键。新增平台分支使用模拟输入验证；Windows/Linux 没有实机验收，远程 CI 矩阵也尚未执行。GUI 脚本需要已安装 Chrome 和可用图形会话，不能把普通无显示环境当成剪贴板实测环境。浏览器可能保留部分全局快捷键，对应界面按钮和命令入口仍可使用。


## 2026-11-02 输入区、队列引导与语音输入

按用户给的 Codex 图 3 收敛操作，保留当前工作台布局和黑灰配色。主要交互如下：

- 运行中有文字或附件时，Enter/发送按钮默认加入队列；空草稿时同一个主按钮显示停止。Esc 保留停止当前任务的能力。去掉“排队/立即纠正”下拉框、常驻说明文字、独立停止及暂停按钮。
- 队列每一行常显“引导”、编辑、删除；多条消息的更多菜单提供上移/下移，同时保留桌面拖动排序。标题可通过键盘折叠。
- “引导”直接进入当前运行的安全边界，不取消或重建任务。后端核对 runId 和队列版本，在同一次存储事务中移出普通队列、移入引导队列；文字、图片、纯图片及图片位置都保留。修改/排序/删除也校验队列版本，客户端防止重复提交和迟到读取覆盖新队列。
- 引导事件会在消息流中显示正文和图片；后续回复属于同一运行。引导消息不展示仅适用于原任务起点的重新发送/检查点按钮。修正原图片消息组件未使用附件及字符位置的问题。
- 排队消息编辑只改正文，内联文件块保留；Esc 只退出编辑，不误停任务。
- 麦克风按钮：申请权限 → 录音（最多 2 分钟/10 MB）→ 完成后转写 → 加入原草稿，不自动发消息。已有文字、附件保留。取消、会话/工作区变化、权限超时后的迟到结果均不会回填；失败时可重试转写。
- 语音通过服务端 OpenAI 兼容音频转写接口上传。默认沿用聊天端点与密钥，默认模型为 gpt-transcribe；在“设置 → 模型 → 语音输入”可配置独立端点、模型和 Key。独立端点不会自动继承聊天服务密钥。音频不持久保存，服务错误不透传原始敏感响应；不自动跟随带认证请求的重定向。

### 三平台处理

Windows/macOS/Linux 的 Web 与 Electron 共享 getUserMedia + MediaRecorder，实现按能力选择 WebM/MP4/Ogg；不依赖仅部分浏览器提供的 SpeechRecognition。非安全页面/无编码支持、权限拒绝、无设备、设备被占用均有明确提示，键盘输入仍可使用。macOS 品牌宿主补充 NSMicrophoneUsageDescription 和系统麦克风授权；Windows/Linux 使用标准媒体权限。桌面仅允许 Pilot 主页面申请音频，不放行摄像头或外部页面；授权过程中页面离开 Pilot 时拒绝迟到授权。

### 已完成验证

- 前端单元测试 **247/247**，包括录音格式、权限拒绝/超时、取消后迟到 stream、转写中取消、空结果、重试、2 分钟及体积上限、音轨释放、外部 AbortSignal，以及引导回放/图片顺序。
- Java **133/133**：AgentLoopTest 49、AgentLoopControlTest 10；SessionApiTest 25、VoiceServiceTest 5、EventCodecNewEventsTest 12、QueuedMessageRepositoryTest 3、AgentRunTaskControlTest 11、AgentRunServiceTest 18。覆盖旧 run/旧队列、重复引导、文字与纯图片引导、同一任务标识、存储事务回滚、HTTP multipart 转写、空/超大/未知格式、错误响应脱敏、独立服务凭据隔离。
- 桌面单元测试 **19/19**，含 Windows/macOS/Linux 权限分支、macOS 拒绝、授权期间导航和麦克风用途声明。
- Chromium 与 Electron 交互回归均通过：实际 MediaRecorder + 测试音源产生音频，FormData 上传到拦截的本地测试响应；回填保留草稿/附件，不自动发送；拒绝、失败重试、切换会话、Esc 边界；队列并发、乱序读取、文件保留、图片显示，1440/768/390 三种宽度及更多菜单边界。
- 两端文件粘贴/拖放/选择回归通过；macOS 原生文件剪贴板 Cmd+V 通过。品牌桌面离线重试与原生菜单 UI 回归通过。
- 生产构建及 git diff --check 通过。设计静态检查 0 FAIL；13 个提示为计时器补零、浮层阴影及已有清除阴影/模糊的声明。人工截图检查确认队列操作常显、窄屏无横向溢出。

实机环境为 **macOS**。Windows/Linux 做了兼容实现和分支模拟，未做两平台实机验证；未调用真实付费语音供应商、未采集人的麦克风。转写接口用本地 HTTP 服务验证；真实语音识别质量和用户当前供应商的转写可用性尚未验证。后端接口及桌面麦克风权限声明需重启后端/桌面壳后生效，未中断用户当前运行中的应用。

证据目录：`/Users/smt/.codex/visualizations/2026/11/02/01a19b15-eceb-7c02-9620-9e6f0e2253b6/pilot-queue-voice/`。其中队列截图为隔离测试场景，避免影响真实任务；录音截图使用测试音源。

参考：[Codex Queue/Steer 语义](https://developers.openai.com/blog/mastering-codex-remote-for-engineering)、[OpenAI 文件转写](https://developers.openai.com/api/docs/guides/speech-to-text)、[MediaRecorder 格式探测](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder/isTypeSupported_static)、[Electron 媒体权限](https://github.com/electron/electron/blob/main/docs/api/session.md)、[Electron macOS 麦克风授权](https://github.com/electron/electron/blob/main/docs/api/system-preferences.md)。


## 2026-11-02 审阅工具栏收敛

- 文件统计、增删行和未跟踪数量保留在工具栏，常驻操作仅刷新与更多；文件总数包含未跟踪文件，增删行仍只统计已跟踪文件。
- 创建并切换分支移入按需弹窗，说明未提交改动会保留；空白名称不可提交，中文输入法确认不触发提交，请求中防重复，失败保留名称并显示原因。
- 撤销全部移入更多菜单，弹窗列明已跟踪文件恢复到 HEAD、未跟踪文件删除以及不可撤回的影响。只提交确认时的文件路径，避免把随后新增文件纳入撤销；部分失败保留提示，要求重新检查后确认。
- 菜单支持方向键、Home/End、Esc、点击外部关闭；弹窗焦点圈定并恢复。任务运行中不允许在此入口创建分支或撤销全部。刷新失败说明当前展示旧数据；刷新与撤销响应按会话、工作区、请求顺序隔离。
- 新增 `scripts/review-cases.mjs`，集成进 Web / Electron 交互回归：正常分支创建、空值、中文输入法、失败保留、请求中重复操作、取消撤销、网络失败重试、部分失败、确认后新增文件、非 Git、无改动、仅未跟踪、任务文件范围、运行中菜单、刷新乱序、切换会话及 1440 / 768 / 390 px 布局。
- 验证通过：247 项 Web 单元测试；Chrome 与 Electron 整套交互回归（含队列、语音、文件树、设置与本次审阅场景）；生产构建；`git diff --check`。设计 lint 为 0 FAIL、13 WARN，警告来自既有样式及菜单/弹窗等实际浮层阴影。
- 兼容处理：Web 与 Electron 共用 React、标准 DOM 事件和响应式 CSS，覆盖 Windows/macOS/Linux；本次实机范围为 macOS 上的 Chrome、Electron 与本地服务页面。Git 写操作使用隔离 API 模拟，没有创建真实分支或撤销实际工作区文件；未宣称 Windows/Linux 实机验证。
- 截图与测试日志：`/Users/smt/.codex/visualizations/2026/11/02/01a19b15-eceb-7c02-9620-9e6f0e2253b6/pilot-review/`。
