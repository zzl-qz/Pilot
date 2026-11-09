# Astryx 界面迁移与首批验收

更新日期 2026-11-09。本文保留首批 React、公共主题和收件箱的验收基线，并记录全量迁移的分工与交付门槛。

## 全量迁移

工作台以任务为中心。项目和会话负责定位任务，中间保留阅读和输入空间，右侧集中审阅与证据。常用操作留在触手可及的位置，较少使用的操作按当前对象分组。组件沿用收件箱的中性主题、中文文案和间距。

| 工作流 | 覆盖范围 | 首要验收路径 |
| --- | --- | --- |
| 设置与模型 | 设置分类、模型表单和选择器、通用弹层 | 发现模型、保存失败重试、权限和诊断、恢复确认 |
| 定时、投递与手机连接 | 一次和周期计划、通知设置与收据、配对和设备管理 | 计划时区、暂停和取消、失败投递、过期配对、复制兜底 |
| 项目导航与搜索 | 功能栏、项目和会话列表、菜单、消息书签搜索 | 窄屏抽屉、会话生命周期、旧书签迁移、搜索跳转 |
| 工作区与审阅 | 文件树、变更、预览、成果、研究、日志、内置浏览器和侧聊 | 冲突确认、预览和下载失败、Web 能力降级、窄屏和键盘 |
| 会话与输入 | 消息、工具执行状态、对话导航、输入、附件、语音和用量 | 流式滚动、输入法和草稿、发送/停止/排队、附件与权限边界 |
| 统一集成 | 工作台外壳、公共主题、资源加载、功能边界和验收 | 真实应用入口、交叉流程、异常与恢复、生产打包 |

各工作流在独立分支实现和验收，负责人逐项审查接口、全局样式及重叠修改后合入。使用 Astryx 的公开组件，并为文件、Markdown、流式输入等专项交互保留已验证的语义与行为。

合入门槛包括严格类型检查、对应的成功及失败流程、深浅主题与 1440/768/390px 浏览器截图，以及键盘操作。合并后运行完整 Web/Electron 回归、检查实际生产资源体积并验证 Jar 会话与流式接口。平台记录区分实机、模拟和未覆盖场景。

### 全量验收

五个并行工作流已合入 `codex/astryx-inbox`。工作台、导航、输入、审阅、设置、自动化、搜索与收件箱采用同一主题和组件规范。功能按对象分组，例如会话 ID 复制保留在会话菜单，浏览器拒绝剪贴板写入时可手动复制。窄屏保留工作区选择和导航返回入口。远程登录与桌面断连页共用视觉规范，并保留自包含的连接和重试路径。

| 检查 | 结果 |
| --- | --- |
| 严格 TypeScript 检查、Vite 和 Maven | 生产 Jar 构建通过 |
| 单元测试 | 387 项前端、41 项桌面通过 |
| 19 套界面回归 | 基于同一合入版本按三组运行。菜单缩窄、工作区输入法和稀疏历史测试在集成修正后复验通过，手机连接和工作台样式也已复验 |
| 打包链路 | 实际 Jar 会话与 SSE、远程登录与退出、本地安全及前端 API 契约通过 |
| 视觉与交互 | 检查 1440、768、390px 的深浅色、键盘焦点、空状态和失败反馈。窄栏和 150% 缩放覆盖输入工具栏 |

本机汇总证据位于 `.tmp-check/astryx-final/logs/` 与 `.tmp-check/astryx-final/screens/`。界面回归包含计划到收件箱与工作区交回、投递到原结果、搜索到书签消息、审阅到对话引用、设置到模型选择等跨模块路径。

生产入口 gzip 约为 JS 406.21 KB、CSS 108.37 KB。收件箱、搜索、计划与审阅按需加载，后台未读计数继续工作。Vite 仍提示大分块，主入口与 PDF 预览需要继续跟踪。

实机为 macOS arm64 Chrome 和 Electron，覆盖原生文件剪贴板、菜单、桌面断连恢复及实际 Jar。Windows/Linux 路径和快捷键、能力缺失与权限失败使用模拟。真实手机扫码、系统通知实际投递、Windows/Linux、Safari 和 Firefox 的实机验证仍待补充。后端单测未重跑。

## 依赖基线

| 依赖 | 锁定版本 | 说明 |
| --- | --- | --- |
| React / React DOM | 19.3.1 | 两者保持一致 |
| React / React DOM 类型 | 19.3.0 | 修正计时器引用的初始值 |
| Astryx core / theme-neutral | 0.7.11 | 使用已发布组件及预编译 CSS |
| StyleX | 0.19.1 | 满足 Astryx 运行时依赖 |
| TypeScript / Vite | 6.0.3 / 6.4.5 | 沿用现有锁文件版本 |

采用包的公开入口，不复制 Astryx 内部实现，不增加 StyleX 编译器。`npm ci --offline --strict-peer-deps --no-audit --no-fund` 从隔离缓存安装成功；`npm ls` 确认只有一份 React / React DOM，未使用 `--force` 或 `--legacy-peer-deps`。

上游：[Astryx v0.7.11](https://github.com/meta/astryx/releases/tag/v0.7.11)。新增依赖许可证位于 `pilot-web/src/main/resources/static/third-party/astryx/`，已核对进入实际 Jar。

## 已完成的行为

- `UiProvider` 统一 Astryx 中文文案、neutral 主题与 Pilot 原有主题状态；支持浅色、深色和系统变化，继续使用随应用分发的本地字体。
- 任务收件箱使用 Astryx Dialog、Button、SegmentedControl、DropdownMenu、Banner、EmptyState、Badge。宽屏列表与详情并排；窄屏进入详情后可返回列表。
- 首次读取失败、刷新失败和真正的空列表分别呈现。刷新失败保留上次结果，迟到响应不覆盖较新的读取，超过轮询周期的请求仍可完成。
- 保留已读/未读、处理/重开、执行对话、验证依据、文件差异、工作区交回与冲突、开源参考和通知入口。
- 弹窗正文独立滚动；关闭入口固定；键盘菜单、方向键筛选、Escape 和关闭后的焦点恢复经过验证。窄屏返回时，原结果若已移出筛选列表，焦点回到当前筛选项。
- 缺少 CSS Anchor Positioning 或 Popover API 时，显示具有相同功能的直接操作按钮。判断浏览器能力，不按操作系统名称硬编码。

## 验收证据

以下证据位于本机忽略提交的 `pilot-web/frontend/.tmp-check/`；其他机器可用仓库中的脚本重跑。

| 检查 | 结果与证据 |
| --- | --- |
| 清洁安装、依赖树 | `astryx-clean-install.log`、`astryx-dependencies.log`，通过 |
| 严格类型检查、生产构建 | `npm run check`，`astryx-final-check.log`，通过 |
| 前端单元 | 387 项通过，`migration/2026-11-09T02-43-21-141Z/` |
| 桌面单元 | 41 项通过，`migration/2026-11-09T02-29-38-289Z/desktop-units.log` |
| 19 套界面回归 | Chromium / Electron 分批执行通过，覆盖下列脚本 |
| 收件箱增量验证 | `astryx-results-final.log`：首读失败、重试、空状态、迟到响应、超过 15 秒的慢请求、主题、键盘、焦点、弹层能力降级与业务链路 |
| 生产 Jar | Java 25 离线 Maven 打包成功；`session-package-smoke.mjs` 验证真实入口、会话与 SSE、历史恢复、上游 401、失败后再次发送、长流取消；`astryx-package-session.log` |
| 视觉验收 | 已查看 1440 / 768 / 390px 列表与详情、浅深主题截图；`astryx-inspect/report.json`、`astryx-source-lint.log` |

19 套界面脚本：agent-usage、composer、inspector、interaction、markdown、mobile-connection、model-discovery、navigation、notification、p1、request-usage、results、sandbox-recovery、schedule、session-management、thread-scroll、work-products、workbench-refresh、workbench-style。日志分布在 `migration/2026-11-09T02-29-38-289Z/`、`astryx-remaining/` 以及 `astryx-mobile.log`、`astryx-p1.log`、`astryx-refresh.log`、`astryx-style.log`、`astryx-results-final.log`。旧导航选择器已按真实“更多功能”路径更新，测试中使用的原生剪贴板内容会恢复。

视觉检查按应用界面规则执行。允许 `covered-text` 的原因是原生模态框有意遮住背景工作台；其余告警分别来自背景工作台与弹窗各自的标题、次要元数据字号、背景输入框边距，以及字体映射中的代码等宽字体。收件箱未发现横向溢出、关闭入口遮挡或稳定状态下的主题颜色错误。截图等待筛选器选中动画完成，避免把切换过程当成最终主题。

## 边界与后续批次

- 实机范围为 macOS arm64 上的 Chrome 与 Electron，以及本机 Java 25 打包运行。Windows / Linux 路径、快捷键、运行时准备等由既有单元测试模拟；尚未运行 Windows / Linux 实机、安装包或远端 CI，也没有完成 Safari / Firefox 实机验收。
- 本次 Maven 使用 `-DskipTests`；前端与桌面测试独立运行，后端单元测试未重跑。真实 Jar 链路通过本地隔离模型验收，不调用外部模型或个人会话。
- 接入后的主入口 JS gzip 约 407.23 KB、CSS 约 102.25 KB；React 升级后、Astryx 接入前分别约 222.64 KB 和 55.10 KB，合计增加约 232 KB。构建仍提示大 chunk。下一批扩大组件覆盖时应同时评估按入口拆分，不能忽略这个成本。
- React 升级为独立提交 `f73ab0a`；主题和收件箱为后续独立提交，便于单独回退。
- 上述首批报告作为并行迁移的对照基线。
