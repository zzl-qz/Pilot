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
