import type { ChangeFile } from '../types/core.js';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Banner } from '@astryxdesign/core/Banner';
import { Button } from '@astryxdesign/core/Button';
import { Dialog } from '@astryxdesign/core/Dialog';
import { IconButton } from '@astryxdesign/core/IconButton';
import { TextInput } from '@astryxdesign/core/TextInput';
import { errorInfo } from '../lib/errors.js';
import { useStore } from '../store.js';
import { Icon } from './Icon.jsx';
import { WorkspaceMenu } from './WorkspaceMenu.jsx';
import './review-actions.css';

type ActionDialog = { type: 'branch' | 'undo'; files: ChangeFile[]; partial?: boolean };

export function ReviewActions({ files, isGit, canUndo, loading, restoring, onRefresh }: { files: ChangeFile[]; isGit: boolean; canUndo: boolean; loading: boolean; restoring: boolean; onRefresh: () => void }) {
  const running = useStore(state => state.running);
  const [dialog, setDialog] = useState<ActionDialog | null>(null);
  const [branch, setBranch] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const pending = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  function show(type: ActionDialog['type']) {
    if (!isGit || running || restoring || pending.current || (type === 'undo' && (!canUndo || loading))) return;
    setError('');
    // 固定确认时可见的文件，避免撤销刷新后新出现、尚未审阅的文件。
    setDialog({ type, files: type === 'undo' ? files.map(file => ({ ...file })) : [] });
  }

  function close() {
    if (pending.current) return;
    setDialog(null);
    requestAnimationFrame(() => (trigger.current?.disabled ? document.getElementById('changes-refresh') : trigger.current)?.focus());
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!dialog || !isGit || pending.current || restoring || useStore.getState().running) return;
    if (dialog.type === 'undo' && (!canUndo || loading || dialog.partial)) return;
    const name = branch.trim();
    if (dialog.type === 'branch' && !name) return;
    pending.current = true; setBusy(true); setError('');
    let succeeded = false;
    try {
      if (dialog.type === 'branch') {
        const result = await useStore.getState().createBranch(name);
        if (!mounted.current) return;
        if (!result.ok) { setError(result.error || '创建分支失败'); return; }
        setBranch('');
      } else {
        const result = await useStore.getState().restoreChanges(dialog.files.map(file => file.path), false);
        if (!mounted.current) return;
        const failed = result?.failed || [];
        if (!result || result.error || failed.length) {
          setError(result?.error || (failed.length
            ? `${failed.length} 个文件未能撤销：${failed[0].path}（${failed[0].reason}）。请关闭确认，检查剩余改动后重试。`
            : '撤销失败，请检查连接后重试。'));
          // 部分成功后重新打开确认，不能重复删除已撤销后又被修改的文件。
          if (failed.length) setDialog(current => current && ({ ...current, partial: true }));
          return;
        }
      }
      succeeded = true;
    } catch (failure) {
      if (mounted.current) setError(errorInfo(failure).message || '操作失败，请重试');
    } finally {
      pending.current = false;
      if (mounted.current) {
        setBusy(false);
        if (succeeded) close();
      }
    }
  }

  const undo = dialog?.type === 'undo';
  const untracked = dialog?.files.filter(file => file.status === '??').length || 0;
  return <div className="changes-actions">
    <IconButton id="changes-refresh" label="刷新变更" icon={<Icon name="refresh" />} variant="ghost"
      isDisabled={restoring || loading || busy} onClick={onRefresh} />
    <WorkspaceMenu
      button={{ ref: trigger, id: 'changes-more', label: '更多变更操作', icon: <Icon name="more" />, isIconOnly: true, variant: 'ghost', isDisabled: !isGit || restoring || busy }}
      menuWidth={224}
      items={[
        { label: '创建并切换分支…', description: running ? '任务结束后可创建分支' : undefined, icon: <Icon name="branch" />, isDisabled: running, onClick: () => show('branch') },
        ...(canUndo ? [{ label: '撤销全部改动…', icon: <Icon name="trash" />, isDisabled: running || loading, onClick: () => show('undo') }] : [])
      ]}
    />
    {dialog && <Dialog id="review-action-dialog" className="review-action-dialog" isOpen purpose="form" width={440}
      maxHeight="calc(100dvh - 32px)" padding={0} aria-labelledby="review-action-title"
      onOpenChange={open => { if (!open) close(); }} onKeyDown={event => event.stopPropagation()}>
      <form onSubmit={submit} aria-busy={busy}>
        <header><h2 id="review-action-title">{undo ? '撤销全部改动？' : '创建并切换分支'}</h2></header>
        <div className="review-dialog-body">
          {undo ? <p>将撤销确认时列出的 {dialog.files.length} 个文件的改动。{dialog.files.length > untracked && ` ${dialog.files.length - untracked} 个已跟踪文件将恢复到 HEAD。`}{untracked > 0 && ` ${untracked} 个未跟踪文件会被删除。`}其中可能有你或其他任务的修改。此操作无法撤回。</p>
            : <>
              <p>从当前分支创建新分支并切换，未提交的改动会保留。</p>
              <TextInput label="新 Git 分支名" placeholder="例如 feature/review" autoComplete="off" hasSpellCheck={false}
                size="lg" width="100%" hasAutoFocus value={branch} isDisabled={busy}
                onChange={value => { setBranch(value.slice(0, 200)); setError(''); }}
                onKeyDown={event => { if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault(); }} />
            </>}
          {error && <Banner status="error" role="alert" title={error} />}
          {running && <p role="status">当前任务正在运行，请等待结束后再操作。</p>}
          {undo && !canUndo && !busy && !error && <p role="status">变更列表已更新，请关闭后重新核对。</p>}
        </div>
        <footer>
          <Button label={dialog.partial ? '关闭' : '取消'} isDisabled={busy} onClick={close} />
          <Button type="submit" id={undo ? 'review-undo-confirm' : 'branch-create'}
            label={undo ? `撤销 ${dialog.files.length} 个文件的改动` : '创建并切换'} variant={undo ? 'destructive' : 'primary'}
            isLoading={busy} isDisabled={busy || restoring || running || !isGit || (undo ? !canUndo || loading || !!dialog.partial : !branch.trim())} />
        </footer>
      </form>
    </Dialog>}
  </div>;
}
