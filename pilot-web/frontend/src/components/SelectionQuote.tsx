import { useEffect, useRef, useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { appendQuote } from '../lib/side-chat.js';
import { useStore } from '../store.js';

/**
 * 选中正文后的浮动操作：加到输入框 / 问 Side chat（旧版 selectionActions）。
 *
 * 只在消息列表里触发；输入框里选字不弹，避免和编辑自己的文本打架。
 */
export function SelectionQuote() {
  const embedded = useStore((state) => state.embeddedSessionId);
  const [menu, setMenu] = useState<{ text: string; left: number; top: number; sequence: number | null; role: string } | null>(null);
  const toolbar = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (embedded) return undefined;
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
      const start = range.startContainer.nodeType === Node.ELEMENT_NODE
        ? range.startContainer as Element
        : range.startContainer.parentElement;
      const row = start?.closest('[data-sequence]');
      const sequence = Number(row?.getAttribute('data-sequence'));
      setMenu({
        text: text.slice(0, 8000),
        sequence: row && Number.isFinite(sequence) ? sequence : null,
        role: row?.classList.contains('msg-user') ? 'user' : 'assistant',
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

  if (!menu) return null;

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
  const saveAsNote = () => {
    useStore.getState().setUi({ ideasOpen: true, ideaSeed: { text: menu.text } });
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
      <Button label="保存为想法" variant="ghost" onClick={saveAsNote} />
    </div>
  );
}
