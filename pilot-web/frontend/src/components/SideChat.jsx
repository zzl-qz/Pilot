import React, { useEffect, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { quoteMarkdown } from '../lib/side-chat.js';
import {
  lastEventSequence, reduceSideChat, showSidePieceNav, simplifyTranscript
} from '../lib/side-chat.js';
import { MarkdownBody } from './MarkdownBody.jsx';

/**
 * 侧边对话：一张简易对话框，只有消息和输入。
 * 侧边栏开着时，顶上的标签导航不出现——对话列表已经在侧边栏里。
 */
export function SideChatPanel() {
  const sideChats = useStore((state) => state.sideChats);
  const sidebarOpen = useStore((state) => state.ui.sidebarOpen);
  const [creating, setCreating] = useState(false);
  const items = sideChats?.items || [];
  const activeId = sideChats?.activeId || null;
  const showNav = showSidePieceNav(sidebarOpen);

  async function create() {
    setCreating(true);
    try {
      await useStore.getState().createSideChat();
    } finally {
      setCreating(false);
    }
  }

  return (
    <div id="panel-side-chat" className="panel side-chat-panel">
      {showNav && (
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
                title="关闭"
                onClick={() => useStore.getState().closeSideChat(item.id)}
              >×</button>
            </div>
          ))}
          <button
            type="button"
            className="side-chat-add"
            title="新建侧边对话"
            aria-label="新建侧边对话"
            disabled={creating}
            onClick={create}
          >＋</button>
        </div>
      )}
      <div className="side-chat-content">
        {activeId
          ? <SimpleSideChat sessionId={activeId} />
          : (
            <div className="side-chat-empty">
              <p>问和主线无关的问题。这边不会打断正在跑的任务。</p>
              <button type="button" className="btn" disabled={creating} onClick={create}>新建对话</button>
            </div>
          )}
      </div>
    </div>
  );
}

function SimpleSideChat({ sessionId }) {
  const quote = useStore((state) => state.sideQuote);
  const [thread, setThread] = useState({ messages: [], live: '' });
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [busy, setBusy] = useState(false);
  const runRef = useRef('');
  const [error, setError] = useState('');
  const listRef = useRef(null);
  const stickRef = useRef(true);

  useEffect(() => {
    let cancelled = false;
    let source = null;
    setThread({ messages: [], live: '' });
    setError('');
    stickRef.current = true;
    api.history(sessionId, { limit: 80 }).then((data) => {
      if (cancelled) return;
      const events = data?.events || [];
      setThread({ messages: simplifyTranscript(events), live: '' });
      source = new EventSource(`/api/sessions/${encodeURIComponent(sessionId)}/events?after=${lastEventSequence(events)}`);
      const onEvent = (message) => {
        let event;
        try {
          event = JSON.parse(message.data);
        } catch {
          return;
        }
        setThread((current) => reduceSideChat(current, event));
        if (event.type === 'run.started') {
          setBusy(true);
          runRef.current = event.runId || '';
        }
        if (event.type === 'run.completed' || event.type === 'run.failed' || event.type === 'run.cancelled') {
          setBusy(false);
        }
      };
      for (const type of ['user.message', 'run.started', 'assistant.delta', 'assistant.preview', 'run.completed', 'run.failed', 'run.cancelled']) {
        source.addEventListener(type, onEvent);
      }
    }).catch((failure) => {
      if (!cancelled) setError(failure.message || '读不到这条侧边对话');
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

  async function send(event) {
    event.preventDefault();
    const text = draft.trim();
    if (!text || sending || busy) return;
    setSending(true);
    setError('');
    setDraft('');
    setBusy(true);
    setThread((current) => reduceSideChat(current, { type: 'user.message', payload: { input: text } }));
    try {
      await api.sendMessage(sessionId, { content: text, attachments: [], imagePositions: [] });
    } catch (failure) {
      setError(failure.message || '发送失败');
      setDraft(text);
      setBusy(false);
    } finally {
      setSending(false);
    }
  }

  function stop() {
    if (runRef.current) api.cancelRun(runRef.current);
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
        {!rows.length && <p className="side-chat-hint">直接问。这里不带主线的工具记录。</p>}
      </div>
      {error && <p className="side-chat-error">{error}</p>}
      <form className="side-chat-compose" onSubmit={send}>
        <textarea
          rows={2}
          placeholder="问一句，Enter 发送"
          value={draft}
          aria-label="侧边对话输入"
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
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
            <button type="submit" className="side-chat-send" aria-label="发送" title="发送" disabled={sending || !draft.trim()}>
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 12.5V3.5M4.8 6.4 8 3.2l3.2 3.2" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" /></svg>
            </button>
          )}
      </form>
    </div>
  );
}

/** 流式预览接在最后一条回复后面，避免和已落库的正文分成两段。 */
function visibleSideMessages(thread) {
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
  const [menu, setMenu] = useState(null);

  useEffect(() => {
    if (embedded) {
      return undefined;
    }
    const update = () => {
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
        left: Math.max(8, Math.min(window.innerWidth - 200, rect.left + rect.width / 2 - 92)),
        top: Math.max(8, rect.top >= 56 ? rect.top - 44 : rect.bottom + 8)
      });
    };
    document.addEventListener('mouseup', update);
    document.addEventListener('keyup', update);
    return () => {
      document.removeEventListener('mouseup', update);
      document.removeEventListener('keyup', update);
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
      className="selection-actions"
      role="toolbar"
      aria-label="选中文本操作"
      style={{ left: `${menu.left}px`, top: `${menu.top}px` }}
      // 按下时别让浏览器把选区清掉：否则点击动作执行前文本已经没了
      onMouseDown={(event) => event.preventDefault()}
    >
      <button type="button" onClick={addToComposer}>加到输入框</button>
      <button type="button" onClick={askInSideChat}>在侧边问</button>
    </div>
  );
}
