import { useEffect } from "react";
import { Button } from "@astryxdesign/core/Button";
import { StatusDot } from "@astryxdesign/core/StatusDot";
import { useStore } from "../../store.js";
import { resumeBar } from "../../lib/queue.js";
import { latestRunResult } from "../../lib/events.js";
import { toolArgsText, toolLabel } from "../../lib/tool-meta.js";
import { agentStatusLabel, agentStatusDotVariant, summarizeAgents } from "../../lib/agents.js";

export function ResumeBar() {
  const running = useStore((state) => state.running);
  const lastOutcome = useStore((state) => state.lastOutcome);
  const rows = useStore((state) => state.thread?.rows || []);
  const latestResult = latestRunResult(rows);
  const resumable = Boolean(latestResult?.resumable && !latestResult.continued);
  const bar = resumeBar({ running, needInput: lastOutcome === 'NEED_INPUT', resumable, outcome: latestResult?.outcome || latestResult?.errorCode || '' });
  const barKey = bar ? bar.label : '';
  useEffect(() => {
    document.body.classList.toggle('has-resume-bar', Boolean(barKey));
    return () => document.body.classList.remove('has-resume-bar');
  }, [barKey]);
  if (!bar) {
    return null;
  }
  return (
    <div id="task-resume-controls" className="task-live-controls" role="status">
      <span>{bar.text}</span>
      <Button id="task-resume" size="sm" label={bar.label} onClick={() => useStore.getState().continueRun()} />
    </div>
  );
}

/** 底部审批 dock：需要人工批准的工具调用。 */
function formatApprovalArgs(name: string, raw: unknown): string {
  const fromMeta = toolArgsText(name, raw);
  if (fromMeta) return fromMeta;
  if (typeof raw === 'string') return raw;
  if (raw && typeof raw === 'object') {
    try { return JSON.stringify(raw, null, 2); } catch { return ''; }
  }
  return '';
}

export function ApprovalDock() {
  const pending = useStore((state) => state.pendingApprovals);
  const resolve = useStore((state) => state.resolveApproval);
  const resolveWithTrust = useStore((state) => state.resolveApprovalWithTrust);
  const entries = Object.values(pending || {});
  if (!entries.length) {
    return null;
  }
  return (
    <section id="approval-dock" className="approval-dock" aria-label="待审批操作">
      {entries.map(item => {
        // 绑定信息来自审批事件：配置/工具哈希确保用户信任的是卡片上展示的那一版
        const server = typeof item.structuredArgs?.server === 'string' ? item.structuredArgs.server as string : '';
        const tool = typeof item.structuredArgs?.tool === 'string' ? item.structuredArgs.tool as string : '';
        const configHash = typeof item.structuredArgs?.configHash === 'string' ? item.structuredArgs.configHash : '';
        const toolHash = typeof item.structuredArgs?.toolHash === 'string' ? item.structuredArgs.toolHash : '';
        const toolName = item.name || (item as { tool?: string }).tool || '';
        const formattedArgs = formatApprovalArgs(toolName, item.args);
        const isMcp = toolName === 'mcp_tools' || toolName === 'mcp_call';
        return (
          <article key={item.callId || toolName} className="approval-card" data-tool-name={toolName} aria-busy={item.busy}>
            <h3 className="approval-title"><StatusDot variant="warning" label="待审批" aria-hidden="true" />需要你批准：{toolLabel(toolName)}</h3>
            {formattedArgs && <pre className="approval-args" tabIndex={0} aria-label="操作参数">{formattedArgs}</pre>}
            <div className="approval-actions">
              {item.busy && <span role="status">正在确认审批…</span>}
              <Button label="拒绝" size="sm" variant="ghost" isDisabled={item.busy} onClick={() => resolve(item.callId, false)} />
              <Button label="批准" size="sm" variant="primary" isDisabled={item.busy} onClick={() => resolve(item.callId, true)} />
              {isMcp && server && configHash && (
                <Button label="批准并信任服务器" size="sm" variant="ghost" isDisabled={item.busy}
                  aria-label={`记住 ${server} 当前这条启动命令，之后再启动不再询问`}
                  onClick={() => resolveWithTrust(item.callId, true, { action: 'trust', server, configHash })} />
              )}
              {isMcp && server && tool && toolHash && (
                <Button label="始终允许此工具" size="sm" variant="ghost" isDisabled={item.busy}
                  aria-label={`记住 ${server}.${tool}（以当前定义为准），之后自动放行`}
                  onClick={() => resolveWithTrust(item.callId, true, { action: 'allow-tool', server, tool, configHash, toolHash })} />
              )}
            </div>
          </article>
        );
      })}
    </section>
  );
}

/** 子智能体 dock：运行中的子智能体一览。 */
export function AgentDock() {
  const agents = useStore((state) => state.agents);
  const open = useStore((state) => state.ui.agentsDockOpen);
  const list = Object.values(agents || {});
  if (!list.length) {
    return null;
  }
  const summary = summarizeAgents(list);
  // 已结束的子任务留在线程中；只有用户点顶部入口时才重开底部列表。
  if (!open && !summary.running) return null;
  const toggle = () => {
    useStore.getState().setUi({ agentsDockOpen: !open });
    if (open && !summary.running) document.getElementById('agents-chip')?.focus();
  };
  return (
    <div id="agents-dock" className="agents-dock">
      <button type="button" className="agents-dock-head" aria-expanded={open} aria-controls="agents-dock-list" onClick={toggle}>
        <span>{summary.text}</span>
        <span className="queue-toggle">{open ? '收起' : '展开'}</span>
      </button>
      {open && (
        <ul id="agents-dock-list" className="agents-dock-list">
          {list.map((agent) => (
            <li key={agent.id}>
              <StatusDot variant={agentStatusDotVariant(agent.status || '')} label={agentStatusLabel(agent.status || '')} aria-hidden="true" />
              <span className="agent-name" title={agent.task}>{agent.name || agent.task || agent.id}</span>
              <span className="agent-state">{agentStatusLabel(agent.status)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** 改动文件汇总条：审阅面板收起且工作区有变更时显示，点击文件名或操作按钮直达 diff。 */
export function ComposerChangesStrip() {
  const files = useStore((state) => state.changeReport?.files);
  const inspectorOpen = useStore((state) => state.ui.inspectorOpen);
  const embedded = useStore((state) => state.embeddedSessionId);
  if (embedded || inspectorOpen || !files || !files.length) return null;
  // 这条汇总读的是工作区 git diff，行数都由补丁算出来，不存在「未知」的情况
  let added = 0;
  let removed = 0;
  for (const file of files) {
    added += Number(file.added) || 0;
    removed += Number(file.removed) || 0;
  }
  const openReview = (filePath?: string) => {
    const target = filePath || files[0]?.path || '';
    useStore.getState().setUi({ inspectorOpen: true, inspectorTab: 'changes', changeView: target });
  };
  const visibleFiles = files.slice(0, 3);
  const extraCount = files.length - visibleFiles.length;
  return (
    <div id="composer-changes-strip" className="composer-changes-strip" role="region" aria-label="本次工作区改动汇总">
      <div className="composer-changes-summary">
        <span className="composer-changes-dot" aria-hidden="true" />
        <span className="composer-changes-count">{files.length} 个文件改动</span>
        {(added > 0 || removed > 0) && (
          <span className="composer-changes-diff" aria-label={`新增 ${added} 行，删除 ${removed} 行`}>
            {added > 0 && <span className="diff-add">+{added}</span>}
            {removed > 0 && <span className="diff-del">-{removed}</span>}
          </span>
        )}
      </div>
      <div className="composer-changes-files">
        {visibleFiles.map((file) => {
          const base = String(file.path || '').split(/[\\/]/).filter(Boolean).pop() || file.path;
          return (
            <button
              key={file.path}
              type="button"
              className="composer-change-chip"
              title={file.path}
              onClick={() => openReview(file.path)}
            >
              <span className="composer-change-chip-name">{base}</span>
            </button>
          );
        })}
        {extraCount > 0 && <span className="composer-changes-more">+{extraCount}</span>}
      </div>
      <button type="button" className="composer-changes-action" onClick={() => openReview()}>
        审阅改动
        <span aria-hidden="true">→</span>
      </button>
    </div>
  );
}

