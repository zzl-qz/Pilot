# 完成凭证、验证策略与任务交接

> 最后更新：2026-11-24

这三个能力把「Agent 说完成了」变成可复核、可交接的交付物。它们不替代模型，也不替代测试；它们负责在模型之外留下可检查的记录。

## 1. 完成凭证（Completion Bundle）

任务完成后可以导出两份东西：

- `GET /api/sessions/{id}/completion`：机器可读 JSON。
- `GET /api/sessions/{id}/completion/markdown`：可粘贴进报告/工单的 Markdown。
- `POST /api/sessions/{id}/completion/verify`：只核对现有证据是否仍覆盖当前文件，不会偷偷重跑命令。

凭证包含：

- 原始目标、约束、验收项、纠正记录
- 验证结论（已验证 / 过期 / 未验证）与原因代号
- 校验证据：产物指纹、文件、命令、退出码、环境指纹、所属运行
- 浏览器宿主机证据（`browserEvidence`）：真实访问来源、截图/过程录像路径、下载、门控拦截与异常统计（Markdown 同步输出摘要表）
- 改动文件清单与逐文件 diff（有快照时）
- 验证策略的必跑命令与缺失项
- 下一步建议

关键语义：**文件再被改动，旧凭证会变成 `stale`**，而不是继续证明新产物。要重新验证，请让 Agent 重新执行策略里的命令，再生成一份新凭证。

示例：

```bash
curl -s http://127.0.0.1:9999/api/sessions/ses_xxx/completion | jq
curl -s http://127.0.0.1:9999/api/sessions/ses_xxx/completion/markdown
curl -s -X POST http://127.0.0.1:9999/api/sessions/ses_xxx/completion/verify | jq
```

## 2. 验证策略即代码

在工作区根目录放 `pilot.policy.yaml`（也支持 `.pilot/policy.yaml`）：

```yaml
version: 1
checks:
  "src/**":
    - "mvn -q test"
  "docs/**":
    - "node scripts/check-doc-links.mjs"
  "*.sql":
    - "sqlfluff lint"
completion:
  require: all      # all（默认）或 any
  allowOverride: false
```

语义：

- 改动了匹配某个模式的路径，就必须在本次运行里留下对应命令的**退出码 0** 记录。
- `require: all` 要求所有命中的命令都跑过；`any` 只要求至少一条。
- `allowOverride: true` 时缺检查只记录、不阻断；默认 `false`。
- 策略文件存在但无法解析时 **fail-closed**：任务标记为验证失败，避免静默放行。
- 策略检查发生在运行结束、证据落账之后；不通过时任务状态落为 `VERIFICATION_FAILED`，不会显示为已完成。

注意：策略只能检查「被记录到的命令」。如果命令不是通过 Pilot 的 `run_command` 跑的，或者输出被截断，策略无法证明它跑过。

## 3. 任务胶囊（Task Capsule）

把一个任务移交给另一个会话、模型或人：

```bash
# 导出
curl -s http://127.0.0.1:9999/api/sessions/ses_xxx/capsule > capsule.json

# 导入到目标项目（可选 --workspace 指定目录）
curl -s -X POST "http://127.0.0.1:9999/api/projects/prj_xxx/capsules?workspace=E:/work/demo" \
  -H 'content-type: application/json' --data-binary @capsule.json
```

胶囊包含：

- 目标、约束、验收、纠正记录
- 来源状态、执行阶段、阻塞原因
- 已有证据与验证结论
- 改动文件清单与 diff
- 下一步建议

导入行为：

- 在目标项目下创建一个新会话；
- 恢复任务契约，状态先设为 `WAITING`；
- 写入一条「任务交接胶囊」的 user 消息，让下一个模型能读到上下文；
- **不会自动应用 diff**。工作区差异必须先由人或新 Agent 审阅，避免交接变成绕过审批的自动写入。

胶囊里可能含源码差异和个人信息，按源代码同等敏感度处理，不要随意分享。

## 当前边界

- 工作台「成果」面板顶部已经提供「完成凭证」和「导出胶囊」入口；**导入胶囊**仍是 API，需要目标项目 ID 和可选的 workspace。
- 完成凭证不重新执行命令；「重新验证」仍由 Agent 执行。
- 策略是工作区级，不支持按项目/分支继承。
- 胶囊导入不合并 Git 历史，也不替用户解决冲突。
