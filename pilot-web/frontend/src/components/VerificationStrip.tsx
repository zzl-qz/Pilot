import { useEffect, useState } from 'react';
import { useStore } from '../store.js';
import { REVERIFY_TEXT, stripView } from '../lib/validation-strip.js';
import './verification-strip.css';

/**
 * 输入框上方的验证结论条。
 *
 * <p>产品的核心主张是「AI 说完成不算数，要有证据」。这句话如果只写在任务契约的折叠区里，
 * 用户永远看不到；放在这里，每轮结束都能一眼看到当前结论、依据和下一步动作。
 *
 * <p>没有会话、或还没跑过任何一轮时不显示——空会话上摆一条「未验证」只会变成噪音。
 * 这一轮还在跑时整条都不显示：顶栏的运行状态已经报了当前动作和耗时，这里再摆一条只重复一遍。
 * 结论要等这一轮停下来才有。
 */
export function VerificationStrip() {
  const sessionId = useStore(state => state.sessionId);
  const validation = useStore(state => state.validation);
  const hasRun = useStore(state => state.hasRun);
  const running = useStore(state => state.running);
  const resumable = useStore(state => (state.thread?.rows || []).some(row => row.type === 'result' && row.resumable && !row.continued));
  const [open, setOpen] = useState(false);

  // 换会话时收起：展开状态跟内容走，不跟着组件实例走。
  useEffect(() => { setOpen(false); }, [sessionId]);

  if (!sessionId) return null;
  // 跑的过程中不给验证结论，也不另起一条运行提示条：顶栏已经在报状态。
  if (running) return null;
  // 任务中断待续跑时，输入框上方已有「继续执行」主动作条，不再叠放一条「未验证」干扰主操作。
  if (resumable) return null;

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