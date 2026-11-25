import { useEffect, useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { Dialog } from '@astryxdesign/core/Dialog';
import { TextInput } from '@astryxdesign/core/TextInput';
import type { ApiResults } from '../types/api.js';
import { api } from '../api.js';
import { errorInfo } from '../lib/errors.js';
import { resolveFolders, samePath } from '../lib/project/sessions.js';
import { useStore } from '../store.js';
import { Icon } from './Icon.jsx';

export default function WorkspaceDialog({ onClose }: { onClose: () => void }) {
  const workspace = useStore((state) => state.workspace);
  const projects = useStore((state) => state.projects);
  const sessions = useStore((state) => state.sessions);
  const sessionId = useStore((state) => state.sessionId);
  const currentProjectId = useStore((state) => state.currentProjectId);
  const projectId = currentProjectId || sessions.find((item) => item.id === sessionId)?.projectId || null;
  const project = projects.find((item) => item.id === projectId) || null;
  const folders = resolveFolders(project, workspace);
  const [worktree, setWorktree] = useState<ApiResults['worktreeInfo'] | null>(null);
  const [worktreeBranch, setWorktreeBranch] = useState('');
  const [inspectionError, setInspectionError] = useState('');

  // 当前目录是不是某个 worktree：是的话给出分支与清理入口，否则遗留目录只能手工 rm
  useEffect(() => {
    let alive = true;
    setWorktree(null);
    setInspectionError('');
    if (!workspace) return undefined;
    api.worktreeInfo(workspace)
      .then((info) => { if (alive) setWorktree(info?.worktree ? info : null); })
      .catch(error => {
        if (!alive) return;
        setWorktree(null);
        setInspectionError(errorInfo(error).message || '无法读取工作区状态');
      });
    return () => { alive = false; };
  }, [workspace]);

  return (
    <Dialog isOpen onOpenChange={open => { if (!open) onClose(); }} purpose="form" width={560}
      maxHeight="calc(100dvh - 32px)" className="workbench-workspace-dialog"
      aria-labelledby="workbench-workspace-title" onKeyDown={event => event.stopPropagation()}>
      <header className="workbench-workspace-heading">
        <div>
          <h2 id="workbench-workspace-title">工作区</h2>
          <p>为当前对话选择文件夹，或创建独立检出。</p>
        </div>
        <Button label="关闭工作区" icon={<Icon name="close" />} isIconOnly variant="ghost" onClick={onClose} />
      </header>
      {folders.length > 0 && <div className="workbench-workspace-folders">
        {folders.map(folder => {
          const current = samePath(folder, workspace);
          return <Button key={folder} label={folder} tooltip={folder}
            variant={current ? 'secondary' : 'ghost'} className="workbench-workspace-folder"
            aria-current={current ? 'true' : undefined}
            onClick={() => {
              onClose();
              if (!current) void useStore.getState().switchWorkspace(folder);
            }} />;
        })}
      </div>}
      <Button label={folders.length ? '选择其他文件夹' : '选择文件夹'} icon={<Icon name="folder" />}
        onClick={() => { onClose(); useStore.getState().setUi({ picker: { title: '切换工作区' } }); }} />
      <section className="workbench-workspace-section" aria-labelledby="workbench-worktree-title">
        <h3 id="workbench-worktree-title">独立检出</h3>
        {inspectionError && <p role="alert">读取检出状态失败：{inspectionError}</p>}
        <form className="workbench-worktree-form" onSubmit={event => {
          event.preventDefault();
          if (!workspace) return;
          onClose();
          void useStore.getState().openWorktree(worktreeBranch.trim());
        }}>
          <TextInput label="分支" placeholder="留空使用当前分支" value={worktreeBranch}
            isDisabled={!workspace} onChange={setWorktreeBranch} onKeyDown={event => {
              if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) {
                event.preventDefault();
              }
            }} />
          <Button type="submit" label="创建 worktree" variant="primary" isDisabled={!workspace} />
        </form>
        {worktree && (
          <>
            <p title={worktree.path}>当前检出：{worktree.branch} · {(worktree.commit || '').slice(0, 7)}</p>
            <div className="workbench-worktree-actions">
              <Button label="把改动交回本地" isDisabled={!sessionId}
                onClick={() => { onClose(); void useStore.getState().handoffWorktree(); }} />
              <Button label="删除这个 worktree" variant="destructive"
                onClick={() => {
                  onClose();
                  useStore.getState().askConfirm({
                    title: '删除 worktree',
                    message: `会删除 ${worktree.path} 这个检出目录（分支 ${worktree.branch} 保留）。未交回的改动会丢失，确定吗？`,
                    confirmText: '删除',
                    danger: true,
                    onConfirm: async () => {
                      try {
                        await api.worktreeRemove(worktree.path);
                        useStore.getState().showNotice('已删除 worktree');
                        if (worktree.base) await useStore.getState().switchWorkspace(worktree.base);
                      } catch (error) {
                        useStore.getState().showNotice('删除 worktree 失败：' + errorInfo(error).message);
                      }
                    }
                  });
                }}
              />
            </div>
          </>
        )}
      </section>
    </Dialog>
  );
}
