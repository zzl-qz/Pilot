import React, { useEffect, useState } from 'react';
import { useStore } from '../store.js';
import { appendQuote, sideChatFrameSrc } from '../lib/side-chat.js';

/**
 * 侧边对话面板（旧版右侧工作区的「Side chat」）。
 *
 * 每个标签页是一个 iframe：同一份前端带 ?sideChat=1&session=<id> 打开，
 * 内嵌页自己收起侧栏与审阅面板，只显示那一个会话——和旧版完全一致。
 * 关掉的标签页连帧一起销毁，没关的就留着，切回来不用重新加载。
 */
export function SideChatPanel() {
  const sideChats = useStore((state) => state.sideChats);
  const [creating, setCreating] = useState(false);
  const items = sideChats?.items || [];
  const activeId = sideChats?.activeId || null;

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
            >{item.title || 'Side chat'}</button>
            <button
              type="button"
              className="side-chat-close"
              aria-label={`关闭 ${item.title}`}
              title="关闭此 Side chat 标签"
              onClick={() => useStore.getState().closeSideChat(item.id)}
            >×</button>
          </div>
        ))}
        <button
          type="button"
          className="side-chat-add"
          title="新建 Side chat (Ctrl Alt S)"
          aria-label="新建 Side chat"
          disabled={creating}
          onClick={create}
        >＋</button>
      </div>
      <div className="side-chat-content">
        {items.map((item) => (
          <iframe
            key={item.id}
            className={`side-chat-frame${item.id === activeId ? '' : ' is-hidden'}`}
            data-side-chat-frame={item.id}
            title={item.title || 'Side chat'}
            src={sideChatFrameSrc(item.id)}
          />
        ))}
        {!items.length && (
          <p className="panel-empty">侧边对话用来问和主线无关的问题，不打断正在跑的任务。点上面的「＋」新建一个。</p>
        )}
      </div>
    </div>
  );
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
      <button type="button" onClick={askInSideChat}>问 Side chat</button>
    </div>
  );
}
