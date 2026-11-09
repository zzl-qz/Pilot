import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { AlertDialog } from '@astryxdesign/core/AlertDialog';
import { Button } from '@astryxdesign/core/Button';
import { ContextMenu } from '@astryxdesign/core/ContextMenu';
import { DropdownMenu, type DropdownMenuItemData, type DropdownMenuOption } from '@astryxdesign/core/DropdownMenu';
import { TextInput } from '@astryxdesign/core/TextInput';
import type { SessionSummary } from '../types/core.js';
import { writeClipboard } from '../lib/clipboard.js';
import { errorInfo } from '../lib/errors.js';
import { restoreFocus } from '../lib/interaction.js';
import { supportsAnchoredLayers } from '../lib/theme.js';
import { useStore } from '../store.js';
import { Icon } from './Icon.jsx';
import { Modal } from './Modals.jsx';

type SessionActionOrigin = { trigger: HTMLElement | null; title: string };
export type SessionAction = SessionActionOrigin & (
  | { kind: 'rename' | 'delete'; session: SessionSummary }
  | { kind: 'copy'; value: string }
  | { kind: 'retry'; retry: () => Promise<boolean> }
);

const FAILED = '操作未成功，请重试。';

/** 右键、长按和行尾按钮共用同一组操作，所有回调都绑定当时选择的会话。 */
export function SessionMenu({ session, children, onAction }: {
  session: SessionSummary; children: ReactNode; onAction: (action: SessionAction) => void;
}) {
  const row = useRef<HTMLElement>(null);
  const more = useRef<HTMLElement | null>(null);
  const pending = useRef(false);
  const touch = useRef({ holding: false, openedMenu: false });
  const [busy, setBusy] = useState(false);
  const [contextOpen, setContextOpen] = useState(false);
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const anchored = supportsAnchoredLayers();
  const presentation = anchored ? 'popover' : 'bottom-sheet';
  const title = session.title || '未命名对话';
  const label = `对话操作：${title}`;

  function items(trigger: () => HTMLElement | null): DropdownMenuOption[] {
    const origin = () => ({ trigger: trigger(), title });
    const item = (label: string, icon: string, onClick: () => void, variant?: DropdownMenuItemData['variant']): DropdownMenuItemData =>
      ({ label, icon: <Icon name={icon} />, onClick, variant, isDisabled: busy });
    const run = async (retry: () => Promise<boolean>) => {
      if (pending.current) return;
      const source = origin();
      pending.current = true;
      setBusy(true);
      try {
        if (!await retry()) onAction({ ...source, kind: 'retry', retry });
      } catch {
        onAction({ ...source, kind: 'retry', retry });
      } finally {
        pending.current = false;
        setBusy(false);
      }
    };
    const copy = async (value: string) => {
      const source = origin();
      if (await writeClipboard(value)) useStore.getState().showNotice('已复制');
      else onAction({ ...source, kind: 'copy', value });
    };
    const preference = (values: { pinned?: boolean; unread?: boolean }) =>
      run(() => useStore.getState().updateSessionPreferences(session.id, values));
    return [
      item('重命名', 'edit', () => onAction({ ...origin(), kind: 'rename', session })),
      item(session.pinned ? '取消置顶' : '置顶', 'pin', () => void preference({ pinned: !session.pinned })),
      item(session.unread ? '标记为已读' : '标记为未读', 'unread', () => void preference({ unread: !session.unread })),
      { type: 'divider' },
      item('复制标题', 'copy', () => void copy(title)),
      item('复制会话 ID', 'copy', () => void copy(session.id)),
      { type: 'divider' },
      item(session.archived ? '恢复到列表' : '归档', 'inbox',
        () => void run(() => useStore.getState().archiveSession(session.id, !session.archived))),
      item('永久删除', 'trash', () => onAction({ ...origin(), kind: 'delete', session }), 'destructive')
    ];
  }

  return <div className="session-menu-trigger" data-workbench-menu-open={contextOpen || dropdownOpen || undefined}>
    <ContextMenu ref={row} label={label} menuWidth={244} presentation={presentation}
      onOpenChange={open => {
        if (open && touch.current.holding) touch.current.openedMenu = true;
        setContextOpen(open);
      }} items={items(() => row.current?.querySelector('.session-item') ?? null)}>
      <span className="session-context-target"
        onTouchStartCapture={() => { touch.current = { holding: true, openedMenu: false }; }}
        onTouchEndCapture={event => {
          touch.current.holding = false;
          // 底部弹层的遮罩也不能收到本次抬手合成的点击。
          if (touch.current.openedMenu && event.cancelable) event.preventDefault();
        }}
        onTouchCancelCapture={() => { touch.current.holding = false; }}
        onPointerDownCapture={event => {
          if (event.pointerType !== 'touch') touch.current.openedMenu = false;
        }}
        onClickCapture={event => {
          // Chromium 可能在长按菜单显示后仍为抬手生成一次会话按钮 click。
          if (!touch.current.openedMenu || event.detail === 0) return;
          touch.current.openedMenu = false;
          event.preventDefault();
          event.stopPropagation();
        }}
        onKeyDown={event => {
          if (event.nativeEvent.isComposing || event.keyCode === 229 || event.repeat) return;
          if (event.key !== 'ContextMenu' && !(event.shiftKey && event.key === 'F10')) return;
          event.preventDefault();
          // 某些浏览器不会从按键派发 contextmenu。使用同一入口并让 Astryx 恢复原焦点。
          event.target.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true }));
        }}>{children}</span>
    </ContextMenu>
    <DropdownMenu aria-label={label} menuWidth={244} placement="end" presentation={presentation}
      isMenuOpen={dropdownOpen} onOpenChange={setDropdownOpen} items={items(() => more.current)}
      renderTrigger={props => <button {...props} type="button" className="session-more icon-btn"
        ref={element => { more.current = element; props.ref(element); }}
        title="更多操作" aria-label={label}
        onKeyDown={event => {
          props.onKeyDown(event);
          if (!anchored && !dropdownOpen && event.key === 'ArrowDown' && !event.nativeEvent.isComposing && event.keyCode !== 229) {
            event.preventDefault();
            setDropdownOpen(true);
          }
        }}><Icon name="more" /></button>} />
  </div>;
}

export function SessionActionDialog({ action, onClose }: { action: SessionAction; onClose: () => void }) {
  const [title, setTitle] = useState(action.kind === 'rename' ? action.session.title || '' : '');
  const [error, setError] = useState(action.kind === 'retry' ? FAILED : '');
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const validTitle = title.trim().length > 0 && [...title.trim()].length <= 120;
  const close = () => { if (!pending.current) onClose(); };

  useLayoutEffect(() => {
    return () => {
      requestAnimationFrame(() => {
        // 关闭和下一次操作可能落在同一帧，已打开的菜单或对话优先持有焦点。
        if (!document.querySelector('dialog[open], [aria-modal="true"], [data-workbench-menu-open]')) restoreFocus(action.trigger);
      });
    };
  }, [action]);

  async function submit(operation: () => Promise<boolean>) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setError('');
    try {
      if (await operation()) onClose();
      else setError(FAILED);
    } catch (failure) {
      setError(errorInfo(failure).message || FAILED);
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }

  if (action.kind === 'copy') {
    return createPortal(<Modal id="session-copy" title="手动复制" onClose={close} initialFocus="textarea">
      <div className="session-rename-form">
        <p>剪贴板不可用，请复制下方已选中的内容。</p>
        <textarea data-autofocus readOnly aria-label="可手动复制的内容" value={action.value} onFocus={event => event.target.select()} />
        <footer className="modal-foot"><Button label="完成" onClick={close} /></footer>
      </div>
    </Modal>, document.body);
  }
  if (action.kind === 'rename') {
    return createPortal(<Modal id="session-rename" title="重命名对话" onClose={close} dismissible={!busy}>
      <form className="session-rename-form" onSubmit={event => {
        event.preventDefault();
        if (validTitle) void submit(() => useStore.getState().renameSession(title, action.session.id));
      }}>
        <TextInput id="session-rename-input" label="对话名称" value={title} isDisabled={busy}
          description="最多 120 个字符。手动命名后会保留这个名称。" width="100%" autoComplete="off"
          onChange={value => { setTitle(value); setError(''); }}
          onKeyDown={event => {
            if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault();
          }} />
        {error && <p className="session-action-error" role="alert">{error}</p>}
        <footer className="modal-foot">
          <Button label="取消" isDisabled={busy} onClick={close} />
          <Button type="submit" label="保存" variant="primary" isLoading={busy} isDisabled={!validTitle} />
        </footer>
      </form>
    </Modal>, document.body);
  }
  if (action.kind === 'retry') {
    return createPortal(<Modal id="session-retry" title="会话操作失败" onClose={close} dismissible={!busy}>
      <div className="session-rename-form">
        <p title={action.title}>{action.title}</p>
        {error && <p className="session-action-error" role="alert">{error}</p>}
        <footer className="modal-foot">
          <Button label="取消" isDisabled={busy} onClick={close} />
          <Button label="重试" variant="primary" isLoading={busy} onClick={() => void submit(action.retry)} />
        </footer>
      </div>
    </Modal>, document.body);
  }

  const description = `「${action.title}」及其历史、检查点会被永久删除，无法恢复。`;
  const remove = () => void submit(() => useStore.getState().deleteSession(action.session.id));
  const nativeDialog = typeof HTMLDialogElement !== 'undefined' && typeof HTMLDialogElement.prototype.showModal === 'function';
  return createPortal(nativeDialog
    ? <AlertDialog id="session-delete" isOpen aria-busy={busy} title="删除对话" description={error ? `${description} ${error}` : description}
        cancelLabel="取消" actionLabel={error ? '重试删除' : '删除'} isActionLoading={busy}
        onOpenChange={open => { if (!open) close(); }} onAction={remove} />
    : <Modal id="session-delete" title="删除对话" onClose={close} dismissible={!busy} initialFocus="#session-delete-cancel">
        <div className="session-rename-form">
          <p>{description}</p>
          {error && <p className="session-action-error" role="alert">{error}</p>}
          <footer className="modal-foot">
            <Button id="session-delete-cancel" label="取消" isDisabled={busy} onClick={close} />
            <Button label={error ? '重试删除' : '删除'} variant="destructive" isLoading={busy} onClick={remove} />
          </footer>
        </div>
      </Modal>, document.body);
}
