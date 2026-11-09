import type { SessionSummary } from '../types/core.js';
import { errorInfo } from '../lib/errors.js';
export interface SessionMenuAnchor { x: number; y: number; trigger: HTMLElement | null }
import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useStore } from '../store.js';
import { Icon } from './Icon.jsx';
import { Modal } from './Modals.jsx';
import { writeClipboard } from '../lib/clipboard.js';
import { restoreFocus } from '../lib/interaction.js';

/** 所有操作绑定打开菜单时的会话，不随当前对话切换而误改其它会话。 */
export function SessionMenu({ session, anchor, onClose }: { session: SessionSummary; anchor: SessionMenuAnchor; onClose: () => void }) {
  const menu = useRef<HTMLDivElement>(null);
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(session.title || '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [copyFallback, setCopyFallback] = useState('');
  const copyField = useRef<HTMLTextAreaElement>(null);
  const mounted = useRef(true);
  const [position, setPosition] = useState<{ left: number; top: number } | null>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  const previous = useRef(anchor.trigger);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; restoreFocus(previous.current); }; }, []);
  useLayoutEffect(() => { if (copyFallback) copyField.current?.focus(); }, [copyFallback]);

  useLayoutEffect(() => {
    if (editing) return;
    const place = () => {
      const box = menu.current?.getBoundingClientRect();
      if (!box) return;
      setPosition({ left: Math.max(8, Math.min(anchor.x, innerWidth - box.width - 8)),
        top: Math.max(8, Math.min(anchor.y, innerHeight - box.height - 8)) });
    };
    place();
    window.addEventListener('resize', place);
    return () => window.removeEventListener('resize', place);
  }, [anchor, editing, error]);

  useEffect(() => {
    if (editing || !position) return;
    menu.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
  }, [editing, Boolean(position)]);

  useEffect(() => {
    if (editing) return;
    const key = (event: KeyboardEvent) => {
      if (event.isComposing) return;
      if (event.key === 'Escape' || event.key === 'Tab') {
        event.preventDefault(); event.stopImmediatePropagation(); closeRef.current(); return;
      }
      if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault(); event.stopImmediatePropagation();
      const items = [...(menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]:not(:disabled)') || [])];
      const index = items.findIndex(item => item === document.activeElement);
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
        : (index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
      items[next]?.focus();
    };
    const outside = (event: PointerEvent) => {
      if (!(event.target instanceof Element)) return;
      if (!menu.current?.contains(event.target) && !event.target.closest('.session-more')) closeRef.current();
    };
    document.addEventListener('keydown', key, true);
    document.addEventListener('pointerdown', outside, true);
    return () => {
      document.removeEventListener('keydown', key, true);
      document.removeEventListener('pointerdown', outside, true);
    };
  }, [editing]);

  async function perform(action: () => unknown | Promise<unknown>) {
    if (busy) return;
    setBusy(true); setError('');
    try {
      const result = await action();
      if (!mounted.current) return;
      if (result === false) { setError('操作未成功，请重试。'); return; }
      onClose();
    } catch (failure) { if (mounted.current) setError(errorInfo(failure).message || '操作失败，请重试。'); }
    finally { if (mounted.current) setBusy(false); }
  }
  async function copy(value: string | undefined) {
    if (!await writeClipboard(value)) {
      if (mounted.current) setCopyFallback(value || '');
      throw new Error('剪贴板不可用，请选中下方内容手动复制。');
    }
    useStore.getState().showNotice('已复制');
    return true;
  }

  if (editing) return createPortal(<Modal id="session-rename" title="重命名对话" onClose={onClose}>
    <form className="session-rename-form" onSubmit={event => {
      event.preventDefault();
      if (title.trim() && [...title.trim()].length <= 120) perform(() => useStore.getState().renameSession(title, session.id));
    }} onKeyDown={event => {
      if (event.key === 'Enter' && event.nativeEvent.isComposing) event.preventDefault();
    }}>
      <label htmlFor="session-rename-input">对话名称</label>
      <input id="session-rename-input" value={title} disabled={busy} autoComplete="off"
        onChange={event => { setTitle(event.target.value); setError(''); }} aria-describedby="session-title-hint" />
      <p id="session-title-hint">最多 120 个字符。手动命名后会保留这个名称。</p>
      {error && <p className="session-menu-error" role="alert">{error}</p>}
      <footer className="modal-foot">
        <button type="button" className="btn" onClick={onClose}>取消</button>
        <button type="submit" className="btn btn-primary" disabled={busy || !title.trim() || [...title.trim()].length > 120}>{busy ? '保存中…' : '保存'}</button>
      </footer>
    </form>
  </Modal>, document.body);

  const item = (name: string, icon: string, action: () => void, danger = false) => <button type="button" role="menuitem" disabled={busy}
    className={danger ? 'is-danger' : ''} onClick={action}><Icon name={icon} /><span>{name}</span></button>;
  return createPortal(<div ref={menu} className="session-menu" role="menu" aria-label="对话操作"
    style={position || { visibility: 'hidden' }}>
    <p className="session-menu-title" title={session.title}>{session.title}</p>
    {item('重命名', 'edit', () => { previous.current?.focus(); setEditing(true); })}
    {item(session.pinned ? '取消置顶' : '置顶', 'pin', () => perform(() => useStore.getState().updateSessionPreferences(session.id, {pinned:!session.pinned})))}
    {item(session.unread ? '标记为已读' : '标记为未读', 'unread', () => perform(() => useStore.getState().updateSessionPreferences(session.id, {unread:!session.unread})))}
    <div role="separator" />
    {item('复制标题', 'copy', () => perform(() => copy(session.title)))}
    {item('复制会话 ID', 'copy', () => perform(() => copy(session.id)))}
    <div role="separator" />
    {item(session.archived ? '恢复到列表' : '归档', 'inbox', () => perform(() => useStore.getState().archiveSession(session.id, !session.archived)))}
    {item('永久删除', 'trash', () => {
      onClose();
      useStore.getState().askConfirm({title:'删除对话', message:`「${session.title}」及其历史、检查点会被永久删除，无法恢复。确定删除吗？`,
        confirmText:'删除', danger:true, onConfirm:() => useStore.getState().deleteSession(session.id)});
    }, true)}
    {error && <p className="session-menu-error" role="alert">{error}</p>}
    {copyFallback && <textarea ref={copyField} readOnly aria-label="可手动复制的内容" value={copyFallback} onFocus={event => event.target.select()} />}
  </div>, document.body);
}
