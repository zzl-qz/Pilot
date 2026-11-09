import type { SideTranscript } from '../types/content.js';
import type { EventEnvelope } from '../types/events.js';
import { errorInfo } from '../lib/errors.js';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { api } from '../api.js';
import {
  appendQuote, lastEventSequence, quoteMarkdown, reduceSideChat, sideChatsFor, simplifyTranscript
} from '../lib/side-chat.js';
import { Button } from '@astryxdesign/core/Button';
import './side-chat.css';
import { MarkdownBody } from './MarkdownBody.jsx';

/**
 * 侧边对话：从当前任务分叉出来的临时对话（对齐 Codex 的 /side）。
 *
 * <p>打开这一页就等于开一条侧边对话，不用先点「新建」；
 * 切到别的任务时这里只显示属于那条任务的侧边对话，关掉即丢弃。
 */
export function SideChatPanel() {
  const sideChats = useStore((state) => state.sideChats);
  const sessionId = useStore((state) => state.sessionId);
  const workspace = useStore((state) => state.workspace);
  const parentTitle = useStore((state) => state.sessionTitle);
  const embedded = useStore((state) => state.embeddedSessionId);
  const [creating, setCreating] = useState(false);
  const openedFor = useRef('');

  const { items, activeId } = sideChatsFor(sideChats, { sessionId, workspace });

  const create = useCallback(async ({ force = false } = {}) => {
    setCreating(true);
    try {
      return await useStore.getState().createSideChat({ force });
    } finally {
      setCreating(false);
    }
  }, []);

  /*
   * 进这一页就现开一条：Codex 的 /side 是「问一句」，不该先让用户点新建。
   * 只在这一页刚打开时尝试一次——用户手动关掉后留在空态，反复重建会很吵。
   */
  useEffect(() => {
    const owner = JSON.stringify([sessionId, workspace]);
    if (openedFor.current === owner) {
      return;
    }
    if (!sessionId || embedded) {
      return;
    }
    openedFor.current = owner;
    if (!items.length) {
      create();
    }
  }, [sessionId, workspace, embedded, items.length, create]);

  return (
    <div id="panel-side-chat" className="panel side-chat-panel">
      <div className="side-chat-scope">
        <span className="side-chat-scope-text" title={workspace || ''}>
          {sessionId ? `从「${parentTitle || '当前任务'}」分叉` : '还没有可以分叉的任务'}
        </span>
        <button
          type="button"
          className="side-chat-new"
          title="在当前任务旁再开一条侧边对话"
          disabled={creating || !sessionId}
          onClick={() => create({ force: true })}
        >＋ 新开</button>
        <button
          type="button"
          className="side-chat-discard"
          title="关闭并丢弃这条侧边对话（主任务不受影响）"
          aria-label="关闭并丢弃这条侧边对话"
          disabled={!activeId}
          onClick={() => useStore.getState().closeSideChat(activeId!)}
        >×</button>
      </div>
      {items.length > 1 && (
        <div className="side-chat-tabs" role="tablist" aria-label="侧边对话">
          {items.map((item) => (
            <div key={item.id} className={`side-chat-tab${item.id === activeId ? ' is-on' : ''}`} role="presentation">
              <button
                type="button"
                role="tab"
                aria-selected={item.id === activeId}
                className="side-chat-select"
                title={item.workspace ? `${item.title}\n${item.workspace}` : item.title}
                onClick={() => useStore.getState().selectSideChat(item.id)}
              >{item.title || '侧边对话'}</button>
              <button
                type="button"
                className="side-chat-close"
                aria-label={`关闭 ${item.title}`}
                title="关闭并丢弃这条侧边对话"
                onClick={() => useStore.getState().closeSideChat(item.id)}
              >×</button>
            </div>
          ))}
        </div>
      )}
      <div className="side-chat-content">
        {activeId
          ? <SimpleSideChat key={activeId} sessionId={activeId} />
          : (
            <div className="side-chat-empty">
              <p>
                {sessionId
                  ? '这条任务还没有侧边对话。在旁边问一句，主线照常跑。'
                  : '先发一条消息开始任务，再开侧边对话。'}
              </p>
              {sessionId && (
                <button type="button" className="btn" disabled={creating} onClick={() => create()}>
                  {creating ? '正在打开…' : '开一条侧边对话'}
                </button>
              )}
            </div>
          )}
      </div>
    </div>
  );
}

function SimpleSideChat({ sessionId }: { sessionId: string }) {
  const quote = useStore((state) => state.sideQuote);
  const [thread, setThread] = useState<SideTranscript>({ messages: [], live: '' });
  const draft = useStore(state => state.sideDrafts?.[sessionId] || '');
  const setDraft = useCallback((value: string | ((current: string) => string)) => useStore.getState().setSideDraft(sessionId, value), [sessionId]);
  const alive = useRef(true);
  const pending = useRef(false);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const [sending, setSending] = useState(false);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const runRef = useRef('');
  const stopRequested = useRef(false);
  const stopping = useRef(false);
  const [error, setError] = useState('');
  const listRef = useRef<HTMLDivElement>(null);
  const stickRef = useRef(true);

  useEffect(() => {
    let cancelled = false;
    let source: EventSource | null = null;
    setThread({ messages: [], live: '' });
    setError('');
    stickRef.current = true;
    Promise.all([api.history(sessionId, { limit: 80 }), api.activeRun(sessionId)]).then(([data, active]) => {
      if (cancelled) return;
      const events = data?.events || [];
      let sequence = lastEventSequence(events);
      runRef.current = active?.runId || '';
      setBusy(!!runRef.current);
      setLoading(false);
      setThread({ messages: simplifyTranscript(events), live: '' });
      source = new EventSource(`/api/sessions/${encodeURIComponent(sessionId)}/events?after=${lastEventSequence(events)}`);
      const onEvent = (message: MessageEvent<string>) => {
        let event: EventEnvelope;
        try {
          event = JSON.parse(message.data) as EventEnvelope;
        } catch {
          return;
        }
        if (cancelled || event.sessionId !== sessionId) return;
        if (event.sequence && event.sequence <= sequence) return;
        sequence = Math.max(sequence, Number(event.sequence) || 0);
        setThread((current) => reduceSideChat(current, event));
        if (event.type === 'run.started') {
          setBusy(true);
          runRef.current = event.runId || '';
          if (stopRequested.current) stop();
        }
        if (event.type === 'run.completed' || event.type === 'run.failed' || event.type === 'run.cancelled') {
          setBusy(false); runRef.current = '';
          if (event.type === 'run.failed') setError(event.payload?.message || event.payload?.reason || '侧边任务失败，请重试。');
        }
      };
      for (const type of ['user.message', 'run.started', 'assistant.delta', 'assistant.preview', 'run.completed', 'run.failed', 'run.cancelled']) {
        source.addEventListener(type, onEvent);
      }
      source.onerror = () => { if (!cancelled) setError('侧边对话连接中断，正在重连…'); };
      source.onopen = () => { if (!cancelled) setError(current => current === '侧边对话连接中断，正在重连…' ? '' : current); };
    }).catch((failure) => {
      if (!cancelled) { setLoading(false); setError(errorInfo(failure).message || '读不到这条侧边对话'); }
    });
    return () => {
      cancelled = true;
      source?.close();
    };
  }, [sessionId]);

  useEffect(() => {
    if (!quote || quote.id !== sessionId) return;
    const text = useStore.getState().takeSideQuote(sessionId);
    if (!text) return;
    const block = quoteMarkdown(text);
    setDraft((current) => (current ? `${current}\n\n${block}\n\n` : `${block}\n\n`));
  }, [quote, sessionId]);

  useEffect(() => {
    const node = listRef.current;
    if (node && stickRef.current) node.scrollTop = node.scrollHeight;
  }, [thread]);

  async function send(event: React.FormEvent) {
    event.preventDefault();
    const text = draft.trim();
    if (!text || pending.current || busy || loading) return;
    pending.current = true;
    runRef.current = '';
    stopRequested.current = false;
    setSending(true);
    setError('');
    setDraft('');
    setBusy(true);
    try {
      const result = await api.sendMessage(sessionId, { content: text, attachments: [], imagePositions: [] });
      if (alive.current) {
        runRef.current = result?.runId || runRef.current;
        if (stopRequested.current) await stop();
      }
    } catch (failure) {
      setDraft(current => current ? `${text}\n\n${current}` : text);
      if (alive.current) { setError(errorInfo(failure).message || '发送失败'); setBusy(false); }
    } finally {
      pending.current = false;
      if (alive.current) setSending(false);
    }
  }

  async function stop() {
    stopRequested.current = true;
    if (!runRef.current) return;
    if (stopping.current) return;
    stopping.current = true;
    stopRequested.current = false;
    try { await api.cancelRun(runRef.current); }
    catch (failure) { if (alive.current) setError(errorInfo(failure).message || '停止失败，请重试。'); }
    finally { stopping.current = false; }
  }

  const rows = visibleSideMessages(thread);

  return (
    <div className="side-chat-simple">
      <div
        className="side-chat-log"
        ref={listRef}
        onScroll={(event) => {
          const node = event.currentTarget;
          stickRef.current = node.scrollHeight - node.scrollTop - node.clientHeight < 48;
        }}
      >
        {rows.map((item, index) => (
          item.role === 'user'
            ? <p key={`${item.role}-${index}`} className="side-chat-line is-user">{item.text}</p>
            : (
              <div key={`${item.role}-${index}`} className="side-chat-line is-assistant">
                <div className="bubble"><MarkdownBody text={item.text} /></div>
              </div>
            )
        ))}
        {!rows.length && (
          <p className="side-chat-hint">
            直接问。它继承了当前任务的上下文，这里不显示主线的工具记录，也不会打断主任务。
          </p>
        )}
      </div>
      {error && <p className="side-chat-error" role="alert">{error}</p>}
      <form className="side-chat-compose" onSubmit={send}>
        <textarea
          rows={2}
          placeholder="问一句，Enter 发送"
          value={draft}
          aria-label="侧边对话输入"
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing && event.keyCode !== 229) {
              event.preventDefault();
              if (!busy) event.currentTarget.form?.requestSubmit();
            }
          }}
        />
        {busy
          ? (
            <button type="button" className="side-chat-stop" aria-label="停止" title="停止" onClick={stop}>
              <span />
            </button>
          )
          : (
            <button type="submit" className="side-chat-send" aria-label="发送" title="发送" disabled={loading || sending || !draft.trim()}>
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 12.5V3.5M4.8 6.4 8 3.2l3.2 3.2" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" /></svg>
            </button>
          )}
      </form>
    </div>
  );
}

/** 流式预览接在最后一条回复后面，避免和已落库的正文分成两段。 */
function visibleSideMessages(thread: SideTranscript) {
  const messages = (thread.messages || []).slice();
  const live = thread.live || '';
  if (!live) return messages;
  const last = messages[messages.length - 1];
  if (last?.role === 'assistant') {
    messages[messages.length - 1] = { role: 'assistant', text: `${last.text}${live}` };
    return messages;
  }
  messages.push({ role: 'assistant', text: live });
  return messages;
}

/**
 * 选中正文后的浮动操作：加到输入框 / 问 Side chat（旧版 selectionActions）。
 *
 * 只在消息列表里触发；输入框里选字不弹，避免和编辑自己的文本打架。
 */
export function SelectionQuote() {
  const embedded = useStore((state) => state.embeddedSessionId);
  const [menu, setMenu] = useState<{ text: string; left: number; top: number } | null>(null);
  const toolbar = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (embedded) {
      return undefined;
    }
    const update = () => {
      if (toolbar.current?.contains(document.activeElement)) return;
      const selection = window.getSelection();
      const text = selection?.toString().trim();
      const thread = document.querySelector('.thread');
      if (!selection || !selection.rangeCount || !text || !thread) {
        setMenu(null);
        return;
      }
      const range = selection.getRangeAt(0);
      if (!thread.contains(range.commonAncestorContainer)) {
        setMenu(null);
        return;
      }
      const rect = range.getBoundingClientRect();
      if (!rect.width && !rect.height) {
        setMenu(null);
        return;
      }
      setMenu({
        text: text.slice(0, 8000),
        left: Math.max(8, Math.min(window.innerWidth - 232, rect.left + rect.width / 2 - 112)),
        top: Math.max(8, Math.min(window.innerHeight - 56, rect.top >= 64 ? rect.top - 52 : rect.bottom + 8))
      });
    };
    let frame = 0;
    const schedule = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(update); };
    document.addEventListener('mouseup', update);
    document.addEventListener('keyup', update);
    document.addEventListener('selectionchange', schedule);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener('mouseup', update);
      document.removeEventListener('keyup', update);
      document.removeEventListener('selectionchange', schedule);
    };
  }, [embedded]);

  if (!menu) {
    return null;
  }

  const addToComposer = () => {
    const api = useStore.getState().composerApi;
    if (api) {
      appendQuote(api.getText, api.appendText, menu.text);
      api.focus();
    }
    setMenu(null);
  };
  const askInSideChat = () => {
    useStore.getState().askSideChat(menu.text);
    setMenu(null);
  };

  return (
    <div
      className="selection-actions side-quote-actions"
      ref={toolbar}
      role="group"
      aria-label="选中文本操作"
      style={{ left: `${menu.left}px`, top: `${menu.top}px` }}
      onKeyDown={event => {
        if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setMenu(null); }
      }}
      // 按下时别让浏览器把选区清掉：否则点击动作执行前文本已经没了
      onMouseDown={(event) => event.preventDefault()}
    >
      <Button label="加到输入框" variant="ghost" onClick={addToComposer} />
      <Button label="在侧边问" variant="ghost" onClick={askInSideChat} />
    </div>
  );
}
