# Astryx 界面迁移与首批验收

更新日期：2026-11-09。首批范围为 React 升级、公共主题和任务收件箱。其余页面继续分批迁移，不能将本次验收视为整个界面重构完成。

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
- 后续顺序：设置与表单 → 其余弹窗 → 导航与工作区 → 聊天和输入。每批核对完整业务行为，补充失败与边界验证，通过 Web / Electron 回归后再扩大范围。
