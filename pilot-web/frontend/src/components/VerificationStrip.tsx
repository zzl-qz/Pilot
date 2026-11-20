import { useEffect, useState } from 'react';
import { useStore } from '../store.js';
import { runPhase } from '../lib/interaction.js';
import { formatClock } from '../thread-utils.js';
import { REVERIFY_TEXT, stripView } from '../lib/validation-strip.js';
import { toolArgsText, toolLabel } from '../lib/tool-meta.js';
import './verification-strip.css';

/**
 * 输入框上方的验证结论条。
 *
 * <p>产品的核心主张是「AI 说完成不算数，要有证据」。这句话如果只写在任务契约的折叠区里，
 * 用户永远看不到；放在这里，每轮结束都能一眼看到当前结论、依据和下一步动作。
 *
 * <p>没有会话、或还没跑过任何一轮时不显示——空会话上摆一条「未验证」只会变成噪音。
 * 这一轮还在跑时也不显示结论，只说当前在做什么。结论要等这一轮停下来才有。
 */
export function VerificationStrip() {
  const sessionId = useStore(state => state.sessionId);
  const validation = useStore(state => state.validation);
  const hasRun = useStore(state => state.hasRun);
  const running = useStore(state => state.running);
  const [open, setOpen] = useState(false);

  // 换会话时收起：展开状态跟内容走，不跟着组件实例走。
  useEffect(() => { setOpen(false); }, [sessionId]);

  if (!sessionId) return null;
  if (running) return <RunningStatus />;

  const view = stripView(validation, hasRun, running);
  if (!view.visible) return null;

  return (
    <section id="verification-strip" className="verification-strip" data-tone={view.tone} aria-label="验证结论">
      <div className="verification-strip-row">
        <span className="verification-strip-badge">{view.label}</span>
        <span className="verification-strip-detail" title={view.detail}>{view.detail}</span>
        <div className="verification-strip-actions">
          {view.facts.length > 0 && (
            <button type="button" className="verification-strip-link" aria-expanded={open}
              onClick={() => setOpen(value => !value)}>{open ? '收起依据' : '看依据'}</button>
          )}
          {view.needsRerun && (
            <button type="button" className="verification-strip-button"
              onClick={() => { void useStore.getState().submitInput(REVERIFY_TEXT); }}>重新验证</button>
          )}
        </div>
      </div>
      {open && view.facts.length > 0 && (
        <ul className="verification-strip-facts">
          {view.facts.map(fact => <li key={fact}>{fact}</li>)}
        </ul>
      )}
    </section>
  );
}

/** 任务还在跑：只报当前动作，不给验证结论，也不提供「重新验证」。 */
function RunningStatus() {
  const hasApprovals = useStore(state => Object.keys(state.pendingApprovals || {}).length > 0);
  const phase = useStore(runPhase);
  const startedAt = useStore(state => state.runStartedAt);
  const detail = useStore(state => currentAction(state.thread));
  const [, setTick] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => setTick(value => value + 1), 1000);
    return () => clearInterval(timer);
  }, []);
  if (hasApprovals) return null;
  const clock = startedAt ? formatClock(Math.round((Date.now() - startedAt) / 1000)) : '';
  // 顶栏已展示「正在思考」，没有正在执行的工具详情与计时时不再重复画一条占位横条。
  if (!detail && !clock) return null;
  const text = detail ? `${phase} · ${detail}` : phase;
  return (
    <section id="verification-strip" className="verification-strip" data-tone="running" aria-label="任务状态">
      <div className="verification-strip-row">
        {clock && <span className="verification-strip-badge">{clock}</span>}
        <span className="verification-strip-detail" title={text}>{text}</span>
      </div>
    </section>
  );
}

function currentAction(thread: { rows?: { type?: string; tools?: { status?: string; name?: string; args?: unknown }[] }[] } | null | undefined) {
  const turn = [...(thread?.rows || [])].reverse().find(row => row.type === 'turn');
  const tool = [...(turn?.tools || [])].reverse().find(item => item.status === 'run' || item.status === 'running');
  if (!tool) return '';
  const args = toolArgsText(tool.name || '', tool.args).replace(/\s+/g, ' ').trim();
  if (!args) return toolLabel(tool.name || '');
  return args.length > 72 ? `${args.slice(0, 72)}…` : args;
}