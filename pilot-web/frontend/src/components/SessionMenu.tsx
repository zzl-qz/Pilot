import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Banner } from '@astryxdesign/core/Banner';
import { Button } from '@astryxdesign/core/Button';
import { Dialog } from '@astryxdesign/core/Dialog';
import { TextInput } from '@astryxdesign/core/TextInput';
import type { SessionSummary } from '../types/core.js';
import { errorInfo } from '../lib/errors.js';
import { useStore } from '../store.js';
import { writeClipboard } from '../lib/clipboard.js';
import { restoreFocus } from '../lib/interaction.js';
import { Icon } from './Icon.jsx';
import { moveMenuFocus, navigationDialogKeys } from './NavigationMenu.jsx';
import './navigation.css';

export interface SessionMenuAnchor { x: number; y: number; trigger: HTMLElement | null }

/** 菜单可从行尾、右键坐标或键盘打开。操作始终绑定打开时的会话。 */
export function SessionMenu({ session, anchor, onClose }: {
  session: SessionSummary; anchor: SessionMenuAnchor; onClose: () => void;
}) {
  const menu = useRef<HTMLDivElement>(null);
  const opener = useRef(anchor.trigger);
  const project = useRef(anchor.trigger?.closest('.project-group')?.querySelector('.project-disclosure'));
  const returnFocus = useRef(true);
  const mounted = useRef(false);
  const [dialog, setDialog] = useState<'rename' | 'archive' | 'copy' | null>(null);
  const [title, setTitle] = useState(session.title || '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [copyFallback, setCopyFallback] = useState('');
  const heading = useId();
  const sessionTitle = session.title || '未命名对话';
  const validTitle = Boolean(title.trim()) && [...title.trim()].length <= 120;

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      if (returnFocus.current) restoreFocus(opener.current?.isConnected ? opener.current : project.current);
    };
  }, []);

  // 定位用视口坐标，右键点在边缘或菜单因错误增高时也能完整操作。
  useLayoutEffect(() => {
    const node = menu.current;
    if (dialog || !node) return;
    const place = () => {
      const box = node.getBoundingClientRect();
      node.style.left = `${Math.max(8, Math.min(anchor.x, window.innerWidth - box.width - 8))}px`;
      node.style.top = `${Math.max(8, Math.min(anchor.y, window.innerHeight - box.height - 8))}px`;
    };
    place();
    window.addEventListener('resize', place);
    return () => window.removeEventListener('resize', place);
  }, [anchor, dialog, error, busy]);

  useLayoutEffect(() => {
    if (!dialog) menu.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus({ preventScroll: true });
  }, [anchor, dialog]);

  useEffect(() => {
    if (dialog) return;
    const outside = (event: PointerEvent) => {
      if (!(event.target instanceof Element) || menu.current?.contains(event.target) || event.target.closest('.session-more')) return;
      returnFocus.current = false;
      onClose();
    };
    document.addEventListener('pointerdown', outside, true);
    return () => document.removeEventListener('pointerdown', outside, true);
  }, [dialog, onClose]);

  function showDialog(next: 'rename' | 'archive' | 'copy') {
    restoreFocus(opener.current);
    setError('');
    setDialog(next);
  }

  async function perform(action: () => Promise<boolean>) {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const result = await action();
      if (!mounted.current) return;
      if (result) onClose();
      else setError('操作未成功，请重试。');
    } catch (failure) {
      if (mounted.current) setError(errorInfo(failure).message || '操作失败，请重试。');
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function copy(value: string) {
    if (busy) return;
    if (!value) {
      setError('没有可复制的内容。');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const copied = await writeClipboard(value).catch(() => false);
      if (!mounted.current) return;
      if (copied) {
        useStore.getState().showNotice('已复制');
        onClose();
      } else {
        setCopyFallback(value);
        showDialog('copy');
      }
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  function confirmDelete() {
    if (busy) return;
    // 确认框关闭后先回到触发位置。只有删除了这个位置才回到项目标题。
    restoreFocus(opener.current);
    returnFocus.current = false;
    onClose();
    useStore.getState().askConfirm({
      title: '删除对话',
      message: `「${sessionTitle}」及其历史、检查点会被永久删除，无法恢复。确定删除吗？`,
      confirmText: '删除',
      danger: true,
      onConfirm: async () => {
        await useStore.getState().deleteSession(session.id);
        if (!opener.current?.isConnected && document.activeElement === document.body) restoreFocus(project.current);
      },
    });
  }

  if (dialog) {
    const label = dialog === 'rename' ? '重命名对话' : dialog === 'archive' ? '归档对话' : '手动复制';
    return createPortal(
      <Dialog isOpen onOpenChange={open => { if (!open) onClose(); }} width={440} padding={0} purpose="form"
        id={dialog === 'rename' ? 'session-rename' : undefined} aria-labelledby={heading}
        className="pilot-navigation-dialog" onKeyDown={event => navigationDialogKeys(event, onClose)}>
        <header className="pilot-navigation-dialog-head">
          <h2 id={heading}>{label}</h2>
          <Button label="关闭弹窗" icon={<Icon name="close" />} isIconOnly variant="ghost" onClick={onClose} />
        </header>
        <form className="navigation-dialog-body" onSubmit={event => {
          event.preventDefault();
          if (dialog === 'rename' && validTitle) void perform(() => useStore.getState().renameSession(title, session.id));
          if (dialog === 'archive') void perform(() => useStore.getState().archiveSession(session.id));
        }} onKeyDown={event => {
          if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229)) event.preventDefault();
        }}>
          {dialog === 'rename' && <TextInput id="session-rename-input" label="对话名称" value={title} size="lg"
            width="100%" hasAutoFocus isReadOnly={busy} autoComplete="off" controlClassName="navigation-form-field"
            description="最多 120 个字符。手动命名后会保留这个名称。" onChange={value => { setTitle(value); setError(''); }} />}
          {dialog === 'archive' && <p>将「{sessionTitle}」移到已归档列表。你可以随时恢复对话。</p>}
          {dialog === 'copy' && <>
            <p>剪贴板不可用。请选中下方内容手动复制。</p>
            <textarea readOnly aria-label="可手动复制的内容" value={copyFallback} rows={3}
              data-autofocus="true" onFocus={event => event.currentTarget.select()} />
          </>}
          {error && <Banner status="error" role="alert" title={error} />}
          <footer className="navigation-dialog-actions">
            <Button label={dialog === 'copy' ? '完成' : '取消'} variant="ghost" onClick={onClose} />
            {dialog === 'rename' && <Button label={busy ? '保存中…' : '保存'} type="submit" variant="primary" isDisabled={busy || !validTitle} />}
            {dialog === 'archive' && <Button label={busy ? '归档中…' : '归档'} type="submit" variant="primary" isDisabled={busy} />}
          </footer>
        </form>
      </Dialog>, document.body);
  }

  const item = (label: string, icon: string, onClick: () => void, danger = false) => (
    <Button role="menuitem" tabIndex={-1} label={label} icon={<Icon name={icon} />}
      variant="ghost" aria-disabled={busy} className={danger ? 'is-danger' : undefined}
      onClick={() => { if (!busy) onClick(); }} />
  );
  return createPortal(
    <div ref={menu} className="session-menu pilot-session-menu" role="menu" aria-label="对话操作"
      aria-busy={busy} onKeyDown={event => {
        event.stopPropagation();
        if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return;
        if (event.key === 'Escape' || event.key === 'Tab') {
          event.preventDefault();
          onClose();
        } else {
          moveMenuFocus(event);
        }
      }}>
      <p className="session-menu-title" title={sessionTitle}>{sessionTitle}</p>
      {item('重命名', 'edit', () => { setTitle(session.title || ''); showDialog('rename'); })}
      {item(session.pinned ? '取消置顶' : '置顶', 'pin', () => {
        void perform(() => useStore.getState().updateSessionPreferences(session.id, { pinned: !session.pinned }));
      })}
      {item(session.unread ? '标记为已读' : '标记为未读', 'unread', () => {
        void perform(() => useStore.getState().updateSessionPreferences(session.id, { unread: !session.unread }));
      })}
      <div role="separator" />
      {item('复制标题', 'copy', () => { void copy(session.title || ''); })}
      {item('复制会话 ID', 'copy', () => { void copy(session.id); })}
      <div role="separator" />
      {item(session.archived ? '恢复到列表' : '归档', 'inbox', () => {
        if (session.archived) void perform(() => useStore.getState().archiveSession(session.id, false));
        else showDialog('archive');
      })}
      {item('永久删除', 'trash', confirmDelete, true)}
      {error && <p className="session-menu-error" role="alert">{error}</p>}
    </div>, document.body);
}
