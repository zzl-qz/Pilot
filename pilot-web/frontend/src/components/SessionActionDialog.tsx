import { useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { AlertDialog } from '@astryxdesign/core/AlertDialog';
import { Button } from '@astryxdesign/core/Button';
import { TextInput } from '@astryxdesign/core/TextInput';
import type { SessionSummary } from '../types/core.js';
import { errorInfo } from '../lib/errors.js';
import { restoreFocus } from '../lib/interaction.js';
import { useStore } from '../store.js';
import { Modal } from './Modal.jsx';

type SessionActionOrigin = { trigger: HTMLElement | null; title: string };
export type SessionAction = SessionActionOrigin & (
  | { kind: 'rename' | 'delete'; session: SessionSummary }
  | { kind: 'copy'; value: string }
  | { kind: 'retry'; retry: () => Promise<boolean> }
);

const FAILED = '操作未成功，请重试。';

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
        // 关闭和下一次操作可能落在同一帧，已打开的菜单、对话或新焦点优先保留，避免回抢焦点。
        const active = document.activeElement;
        const focusLost = !active || active === document.body || !active.isConnected;
        if (focusLost && !document.querySelector('dialog[open], [aria-modal="true"], [data-workbench-menu-open]')) {
          restoreFocus(action.trigger);
        }
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
    return createPortal(<Modal id="session-rename" title="重命名对话" onClose={close} dismissible={!busy} purpose="form">
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
