# 与验证类工具的差异

> 最后更新：2026-11-15

「让 AI 的完成声明可被核对」这件事已经有人做了，而且做得不少。
回避这一点只会让比较更难进行，所以这里先把已有的做法列清楚，再说明 Pilot 选择的是哪一层。

## 已经存在的做法

以下都是外部项目，仅作对比参照，不表示它们与 Pilot 有代码或授权关系：

| 项目 | 形态 | 做法 |
| --- | --- | --- |
| donecheck | 零依赖脚本 | 扫描改动的文件，运行用户指定的验证命令，写一份 `DONECHECK.md` 回执 |
| agent-done-or-not | 编辑器/CLI 钩子 | 把「完成」事件接到一个校验脚本上，没有证据就不允许结束 |
| Crucible | 代理插件 | 要求每条成功标准都有可检查的产物，并需要多路独立评审通过 |
| verification-before-completion 一类的技能 | 提示词片段 | 要求在声称完成前先运行命令并读输出 |

它们的共同点很明确：**都在代理外部，都需要用户自己接上，都绑定某一个宿主，都主要覆盖一次代码改动。**

## Pilot 选择的位置

Pilot 不在代理外面加一层，而是把验证做进运行时本身：

| 维度 | 外部验证工具 | Pilot |
| --- | --- | --- |
| 证据来源 | 外部脚本另外跑一遍，用户自己对接 | 运行时内生：与工具的检查摘要、运行记录同源 |
| 覆盖范围 | 一次代码改动 | 代码改动、交付文件、命令任务共用一套判断 |
| 证据与产物的关系 | 通常与一次提交或一次运行关联 | 绑定产物指纹与文件集合；文件一变，旧证据自动作废（`ValidationReason.STALE_CANDIDATE`） |
| 装不装得上 | 用户自己判断要不要挂 | 不装也在；成果登记与任务卡直接读这本账 |
| 跨端 | 换一个终端就断了 | Web、桌面、Android 读同一份结论与同一个指纹前 8 位 |
| 证据过期 | 多数没有这个概念 | 是一等状态，与「从未验证」「校验失败」分开呈现 |

## 一句话区别

**外部工具回答「有没有证据」，Pilot 还要回答「这份证据现在还算不算数」。**

这不是否定上面的项目：它们解决的是「能不能验」，Pilot 解决的是「验完之后，这份结论还能不能拿来支撑完成」。
前者是挂件能做的事，后者需要证据和产物在同一个运行时里被一起管理。

## 对应的实现位置

| 能力 | 位置 |
| --- | --- |
| 证据账本（按会话、按候选指纹） | `pilot-server/src/main/java/com/pilot/server/validation/ValidationLedger.java` |
| 结论计算（当前文件 vs 最近证据） | `pilot-server/src/main/java/com/pilot/server/validation/ValidationStatusService.java` |
| 三态与原因代号 | `ValidationSnapshot.java`、`ValidationReason.java` |
| 产物指纹 | `WorkspaceFingerprint.java` |
| 成果登记时带上结论 | `pilot-server/src/main/java/com/pilot/server/work/WorkProductService.java` |
| 证据失效时撤销人工验收 | `pilot-server/src/main/java/com/pilot/server/run/TaskControlService.java` |

## 边界

- 这些对比是**架构层面**的，不是功能数量上的；外部工具在各自场景下可能更轻、更直接。
- Pilot 的结论只覆盖它实际记录过的文件与命令，见[范围与边界](scope.md)。
- 不声称自己是第一个或唯一做验证的系统。这个方向已有多个独立实现，Pilot 的差异在于把它放进运行时，而不是挂在外面。