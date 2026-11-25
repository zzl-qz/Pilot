import { useEffect, useRef, useState } from "react";
import type { PendingApproval, PendingQuestion, ThreadRow } from "../../types/thread.js";
import { Button } from "@astryxdesign/core/Button";
import { StatusDot } from "@astryxdesign/core/StatusDot";
import { useStore } from "../../store.js";
import { resumeBar } from "../../lib/queue.js";
import { latestRunResult } from "../../lib/events.js";
import { classifyApprovalRisk, formatCountdown, suggestAllowRule } from "../../lib/attention.js";
import { questionChoice, toolArgsText, toolLabel } from "../../lib/tool-meta.js";
import { agentStatusLabel, agentStatusDotVariant, summarizeAgents } from "../../lib/agents.js";
import { Icon } from "../Icon.jsx";

const EMPTY_ROWS: ThreadRow[] = [];

export function ResumeBar() {
  const running = useStore((state) => state.running);
  const lastOutcome = useStore((state) => state.lastOutcome);
  const rows = useStore((state) => state.thread?.rows ?? EMPTY_ROWS);
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

function blockingModalOpen() {
  try {
    return Boolean(document.querySelector('dialog[open]:modal'));
  } catch {
    const open = document.querySelector('dialog[open]') as HTMLElement | null;
    return Boolean(open && open.getClientRects().length);
  }
}

function isTypingElement(target: EventTarget | null): boolean {
  const el = target instanceof HTMLElement ? target : null;
  if (!el) return false;
  if (el.isContentEditable || el.closest('[contenteditable="true"]')) return true;
  return Boolean(el.closest('input, textarea, select'));
}

export function ApprovalDock() {
  const pending = useStore((state) => state.pendingApprovals);
  const workspace = useStore((state) => state.workspace);
  const resolve = useStore((state) => state.resolveApproval);
  const resolveWithTrust = useStore((state) => state.resolveApprovalWithTrust);
  const resolveWithRule = useStore((state) => state.resolveApprovalWithRule);
  const entries = Object.values(pending || {});
  const hasExpiry = entries.some((item) => item.expiresAt != null);
  const [, setTick] = useState(0);

  useEffect(() => {
    if (!hasExpiry) return;
    const timer = setInterval(() => setTick((value) => value + 1), 1000);
    return () => clearInterval(timer);
  }, [hasExpiry]);

  useEffect(() => {
    const first = entries.find((item) => !item.busy);
    if (!first) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.isComposing || event.keyCode === 229 || event.getModifierState?.('AltGraph')) return;
      if (!event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
      if (blockingModalOpen() || isTypingElement(event.target)) return;
      const key = event.key.toLowerCase();
      if (key === 'y' || event.code === 'KeyY') {
        event.preventDefault();
        void resolve(first.callId, true);
      } else if (key === 'n' || event.code === 'KeyN') {
        event.preventDefault();
        void resolve(first.callId, false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [entries, resolve]);

  if (!entries.length) {
    return null;
  }
  const now = Date.now();
  const safeBatch = entries.filter((item) => {
    if (item.busy) return false;
    const toolName = item.name || (item as { tool?: string }).tool || '';
    return !classifyApprovalRisk(toolName, item.args, item.structuredArgs).highRisk;
  });

  return (
    <section id="approval-dock" className="approval-dock" aria-label="待审批操作">
      {entries.length > 1 && safeBatch.length >= 2 && (
        <div className="approval-batch-bar">
          <span>共 {entries.length} 项待审批（{safeBatch.length} 项非高风险）</span>
          <Button
            size="sm"
            variant="ghost"
            label={`批量批准 ${safeBatch.length} 项低风险`}
            onClick={() => {
              for (const entry of safeBatch) {
                void resolve(entry.callId, true);
              }
            }}
          />
        </div>
      )}
      {entries.map((item, index) => (
        <ApprovalCard
          key={item.callId || item.name || index}
          item={item}
          index={index}
          total={entries.length}
          now={now}
          workspace={workspace}
          resolve={resolve}
          resolveWithTrust={resolveWithTrust}
          resolveWithRule={resolveWithRule}
        />
      ))}
    </section>
  );
}

function ApprovalCard({
  item,
  index,
  total,
  now,
  workspace,
  resolve,
  resolveWithTrust,
  resolveWithRule
}: {
  item: PendingApproval;
  index: number;
  total: number;
  now: number;
  workspace: string;
  resolve: (id: string, approved: boolean, sessionId?: string, reason?: string) => Promise<void>;
  resolveWithTrust: (id: string, approved: boolean, trust: { action: string; server: string; tool?: string; configHash?: string; toolHash?: string }) => Promise<void>;
  resolveWithRule: (callId: string, rule: string) => Promise<void>;
}) {
  const [rejectOpen, setRejectOpen] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const composingRef = useRef(false);

  // 绑定信息来自审批事件：配置/工具哈希确保用户信任的是卡片上展示的那一版
  const server = typeof item.structuredArgs?.server === 'string' ? item.structuredArgs.server as string : '';
  const tool = typeof item.structuredArgs?.tool === 'string' ? item.structuredArgs.tool as string : '';
  const configHash = typeof item.structuredArgs?.configHash === 'string' ? item.structuredArgs.configHash : '';
  const toolHash = typeof item.structuredArgs?.toolHash === 'string' ? item.structuredArgs.toolHash : '';
  const previewDiff = typeof item.structuredArgs?.previewDiff === 'string' ? item.structuredArgs.previewDiff : '';
  const previewPath = typeof item.structuredArgs?.path === 'string' ? item.structuredArgs.path : '';
  const toolName = item.name || (item as { tool?: string }).tool || '';
  const formattedArgs = formatApprovalArgs(toolName, item.args);
  const isMcp = toolName === 'mcp_tools' || toolName === 'mcp_call';
  const risk = classifyApprovalRisk(toolName, item.args, item.structuredArgs);
  const allowSuggestion = !isMcp && workspace ? suggestAllowRule(toolName, item.args, item.structuredArgs) : null;
  const countdown = formatCountdown(item.expiresAt, now);

  const submitRejectWithReason = () => {
    if (composingRef.current || item.busy) return;
    const trimmed = rejectReason.trim();
    void resolve(item.callId, false, undefined, trimmed || undefined);
  };

  return (
    <article className={`approval-card${risk.highRisk ? ' is-high-risk' : ''}`} data-tool-name={toolName} aria-busy={item.busy}>
      <header className="approval-head">
        <h3 className="approval-title"><StatusDot variant="warning" label="待审批" aria-hidden="true" />需要你批准：{toolLabel(toolName)}</h3>
        <div className="approval-badges">
          <span className="approval-category">{risk.category}</span>
          {risk.highRisk && <span className="approval-risk-badge">高风险操作</span>}
          {total > 1 && <span className="approval-count">{index + 1} / {total}</span>}
          {countdown && (
            <span className={`approval-countdown${countdown.remainingSeconds <= 60 ? ' is-urgent' : ''}`}>
              {countdown.label}
            </span>
          )}
        </div>
      </header>
      {formattedArgs && <pre className="approval-args" tabIndex={0} aria-label="操作参数">{formattedArgs}</pre>}
      {previewDiff && (
        <details className="approval-diff-preview">
          <summary>预览改动差异{previewPath ? `（${previewPath}）` : ''}</summary>
          <pre className="approval-diff-lines">
            {previewDiff.split('\n').map((line, i) => {
              const cls = line.startsWith('+') && !line.startsWith('+++')
                ? 'is-add'
                : line.startsWith('-') && !line.startsWith('---')
                  ? 'is-del'
                  : line.startsWith('@@') || line.startsWith('---') || line.startsWith('+++')
                    ? 'is-hunk'
                    : '';
              return <span key={i} className={`approval-diff-line ${cls}`}>{line}{'\n'}</span>;
            })}
          </pre>
        </details>
      )}
      {rejectOpen && (
        <form className="approval-reason-form" onSubmit={(event) => { event.preventDefault(); submitRejectWithReason(); }}>
          <input
            className="question-custom"
            value={rejectReason}
            placeholder="说明拒绝原因或期望做法（如：改用 pnpm，别改此文件）…"
            aria-label="拒绝原因或改法建议"
            disabled={item.busy}
            onChange={(event) => setRejectReason(event.target.value)}
            onCompositionStart={() => { composingRef.current = true; }}
            onCompositionEnd={() => { composingRef.current = false; }}
            onKeyDown={(event) => {
              if ((event.nativeEvent.isComposing || event.keyCode === 229 || composingRef.current) && event.key === 'Enter') {
                event.preventDefault();
              }
            }}
          />
          <Button type="submit" size="sm" variant="primary" label="提交拒绝" isDisabled={item.busy} />
        </form>
      )}
      {item.error && <p className="approval-error" role="alert">{item.error}</p>}
      <div className="approval-actions">
        {item.busy && <span role="status">正在确认审批…</span>}
        <Button
          label={rejectOpen ? '收起说明' : '拒绝并说明'}
          size="sm"
          variant="ghost"
          isDisabled={item.busy}
          onClick={() => setRejectOpen((open) => !open)}
        />
        <Button
          label="拒绝"
          size="sm"
          variant="ghost"
          isDisabled={item.busy}
          tooltip={index === 0 ? '拒绝 (Alt+N)' : undefined}
          aria-keyshortcuts={index === 0 ? 'Alt+N' : undefined}
          onClick={() => resolve(item.callId, false)}
        />
        <Button
          label="批准"
          size="sm"
          variant="primary"
          isDisabled={item.busy}
          tooltip={index === 0 ? '批准 (Alt+Y)' : undefined}
          aria-keyshortcuts={index === 0 ? 'Alt+Y' : undefined}
          onClick={() => resolve(item.callId, true)}
        />
        {allowSuggestion && (
          <Button
            label={allowSuggestion.label}
            size="sm"
            variant="ghost"
            isDisabled={item.busy}
            tooltip={`批准本次并将规则写入工作区权限白名单：${allowSuggestion.rule}`}
            onClick={() => resolveWithRule(item.callId, allowSuggestion.rule)}
          />
        )}
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
}

export function QuestionDock() {
  const pending = useStore((state) => state.pendingQuestions);
  const answerQuestion = useStore((state) => state.answerQuestion);
  const entries = Object.values(pending || {});
  const hasExpiry = entries.some((item) => item.expiresAt != null);
  const [, setTick] = useState(0);

  useEffect(() => {
    if (!hasExpiry) return;
    const timer = setInterval(() => setTick((value) => value + 1), 1000);
    return () => clearInterval(timer);
  }, [hasExpiry]);

  if (!entries.length) {
    return null;
  }
  const now = Date.now();
  return (
    <section id="question-dock" className="question-dock" aria-label="等待你的选择">
      {entries.map((item, index) => (
        <QuestionCard
          key={item.callId}
          item={item}
          index={index}
          total={entries.length}
          now={now}
          onSubmit={(choice) => answerQuestion(item.callId, choice)}
        />
      ))}
    </section>
  );
}

function QuestionCard({
  item,
  index,
  total,
  now,
  onSubmit
}: {
  item: PendingQuestion;
  index: number;
  total: number;
  now: number;
  onSubmit: (choice: string) => Promise<boolean>;
}) {
  const [custom, setCustom] = useState('');
  const composingRef = useRef(false);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const options = item.options || [];
  const countdown = formatCountdown(item.expiresAt, now);

  useEffect(() => {
    if (index !== 0 || item.busy) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.isComposing || event.keyCode === 229 || event.getModifierState?.('AltGraph')) return;
      if (!event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
      if (blockingModalOpen() || isTypingElement(event.target)) return;
      const digit = /^[1-9]$/.test(event.key)
        ? Number(event.key)
        : /^Digit([1-9])$/.test(event.code)
          ? Number(event.code.slice(5))
          : 0;
      if (digit > 0) {
        const optionIndex = digit - 1;
        if (optionIndex < options.length) {
          event.preventDefault();
          void onSubmit(options[optionIndex]);
        }
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [index, item.busy, onSubmit, options]);

  const onListKeyDown = (event: React.KeyboardEvent<HTMLUListElement>) => {
    if (!options.length) return;
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    const currentIndex = optionRefs.current.findIndex((btn) => btn === document.activeElement);
    if (currentIndex < 0) return;
    event.preventDefault();
    const step = event.key === 'ArrowDown' ? 1 : -1;
    const nextIndex = (currentIndex + step + options.length) % options.length;
    optionRefs.current[nextIndex]?.focus();
  };

  return (
    <article className="question-card" data-call-id={item.callId} aria-busy={Boolean(item.busy)}>
      <header className="question-head">
        <span className="question-badge"><Icon name="info" /> 需要你的选择</span>
        <div className="question-meta">
          {total > 1 && <span className="approval-count">{index + 1} / {total}</span>}
          {countdown && (
            <span className={`approval-countdown${countdown.remainingSeconds <= 60 ? ' is-urgent' : ''}`}>
              {countdown.label}
            </span>
          )}
        </div>
      </header>
      <p className="question-title">{item.question}</p>
      {options.length > 0 && (
        <ul className="question-options" onKeyDown={onListKeyDown}>
          {options.map((option, optIndex) => (
            <li key={`${optIndex}:${option}`}>
              <button
                ref={(el) => { optionRefs.current[optIndex] = el; }}
                type="button"
                className="question-option"
                disabled={Boolean(item.busy)}
                title={index === 0 && optIndex < 9 ? `快捷选择 (Alt+${optIndex + 1})` : undefined}
                aria-keyshortcuts={index === 0 && optIndex < 9 ? `Alt+${optIndex + 1}` : undefined}
                onClick={() => void onSubmit(option)}
              >
                <span className="ask-option-index" aria-hidden="true">{optIndex + 1}</span>
                <span className="ask-option-text">{option}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <form
        className="question-form"
        onSubmit={(event) => {
          event.preventDefault();
          if (composingRef.current || item.busy) return;
          const choice = questionChoice('', custom);
          if (!choice) return;
          void onSubmit(choice);
        }}
      >
        <input
          className="question-custom"
          value={custom}
          placeholder="都不是，自己填写…"
          aria-label="自己填写答案"
          disabled={Boolean(item.busy)}
          onChange={(event) => setCustom(event.target.value)}
          onCompositionStart={() => { composingRef.current = true; }}
          onCompositionEnd={() => { composingRef.current = false; }}
          onKeyDown={(event) => {
            if ((event.nativeEvent.isComposing || event.keyCode === 229 || composingRef.current) && event.key === 'Enter') {
              event.preventDefault();
            }
          }}
        />
        <Button
          type="submit"
          size="sm"
          variant="primary"
          label={item.busy ? '提交中…' : '提交'}
          isDisabled={!custom.trim() || Boolean(item.busy)}
        />
      </form>
      {item.busy && <p className="question-status" role="status">正在提交选择…</p>}
      {item.error && <p className="question-error" role="alert">{item.error}</p>}
    </article>
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

const HANDOFF_KIND_LABELS: Record<string, string> = {
  auth: '需要登录 / MFA',
  captcha: '需要人机验证',
  payment: '需要支付确认',
  manual: '需要人工协助'
};

export function BrowserControlDock() {
  const mirror = useStore((state) => state.ui.mirror);
  const stopBrowserControl = useStore((state) => state.stopBrowserControl);
  const setUi = useStore((state) => state.setUi);
  const [stopping, setStopping] = useState(false);

  if (!mirror.agentControlled || (mirror.handoffs && mirror.handoffs.length > 0)) {
    return null;
  }

  return (
    <section id="browser-control-dock" className="browser-control-dock" role="status" aria-label="浏览器正在被智能体控制">
      <div className="browser-control-dock-info">
        <span className="browser-control-dot" aria-hidden="true" />
        <strong>Pilot 正在控制浏览器</strong>
        {mirror.controlStatus && <span className="browser-control-dock-step">{mirror.controlStatus}</span>}
      </div>
      <div className="browser-control-dock-actions">
        <Button
          size="sm"
          variant="ghost"
          label="查看浏览器"
          onClick={() => setUi({ inspectorOpen: true, inspectorTab: 'browser' })}
        />
        <button
          type="button"
          className="browser-stop-btn"
          disabled={stopping}
          onClick={async () => {
            setStopping(true);
            try {
              await stopBrowserControl(mirror.activeLane || undefined);
            } finally {
              setStopping(false);
            }
          }}
        >
          {stopping ? '正在停止…' : '停止控制 (Stop)'}
        </button>
      </div>
    </section>
  );
}

export function BrowserHandoffDock() {
  const handoffs = useStore((state) => state.ui.mirror.handoffs);
  const resumeHandoff = useStore((state) => state.resumeBrowserHandoff);
  const cancelHandoff = useStore((state) => state.cancelBrowserHandoff);
  const setUi = useStore((state) => state.setUi);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState('');

  if (!handoffs || !handoffs.length) {
    return null;
  }

  return (
    <section id="browser-handoff-dock" className="browser-handoff-dock" role="region" aria-label="浏览器接管请求">
      {handoffs.map((item) => {
        const busy = busyId === item.handoffId;
        const label = HANDOFF_KIND_LABELS[item.kind] || '请求接管浏览器';
        return (
          <article key={item.handoffId} className="browser-handoff-card">
            <div className="browser-handoff-head">
              <span className="browser-handoff-kind">{label}</span>
              {item.url && <span className="browser-handoff-url mono" title={item.url}>{item.url}</span>}
            </div>
            <p className="browser-handoff-reason">{item.reason}</p>
            <div className="browser-handoff-actions">
              <input
                type="text"
                className="browser-handoff-note"
                placeholder="可选备注（例如：已登录完成，请继续验证首页）"
                value={notes[item.handoffId] || ''}
                disabled={busy}
                onChange={(event) => {
                  const val = event.target.value;
                  setNotes((prev) => ({ ...prev, [item.handoffId]: val }));
                }}
              />
              <Button
                size="sm"
                variant="ghost"
                label="打开浏览器"
                onClick={() => setUi({ inspectorOpen: true, inspectorTab: 'browser' })}
              />
              <Button
                size="sm"
                variant="ghost"
                label="取消"
                isDisabled={busy}
                onClick={async () => {
                  setBusyId(item.handoffId);
                  await cancelHandoff(item.browserSessionId, '用户在输入框快捷取消接管');
                  setBusyId('');
                }}
              />
              <Button
                size="sm"
                variant="primary"
                label={busy ? '交回中…' : '我已完成，交回智能体'}
                isDisabled={busy}
                onClick={async () => {
                  setBusyId(item.handoffId);
                  await resumeHandoff(item.browserSessionId, notes[item.handoffId] || '');
                  setBusyId('');
                }}
              />
            </div>
          </article>
        );
      })}
    </section>
  );
}

export function ComposerTopDocks() {
  return (
    <>
      <ApprovalDock />
      <QuestionDock />
      <BrowserControlDock />
      <BrowserHandoffDock />
      <ResumeBar />
    </>
  );
}

