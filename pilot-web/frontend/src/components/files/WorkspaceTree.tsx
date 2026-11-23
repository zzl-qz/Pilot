import type { SessionFilesControllerEntry } from '../../types/server.js';
import type { ChangeFile } from '../../types/core.js';
import { errorInfo } from '../../lib/errors.js';
import { writeClipboard } from '../../lib/clipboard.js';
import { splitPath, statusKind } from '../../lib/changes.js';
import { statusLetter } from '../inspector/diff-format.js';
import React, { useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { api } from '../../api.js';
import { useStore } from '../../store.js';
import { FileIcon, Icon } from '.././Icon.jsx';
import './workspace-tree.css';

type TreeEntry = SessionFilesControllerEntry & { type?: string };
interface DirectoryData { entries: TreeEntry[]; status: string; error?: string; truncated?: boolean }
interface ContextMenuState { x: number; y: number; path: string; directory: boolean }
interface TreeContext {
  sessionId: string | null;
  workspace: string;
  revision: number;
  focused: React.MutableRefObject<string | null>;
  previewPath: string;
  gitStatuses: Map<string, string>;
  refresh: () => void;
  ensureFocus: () => void;
  openMenu: (menu: ContextMenuState) => void;
}

// 每个目录独立加载，折叠目录不请求。工作区切换/刷新时丢弃旧响应。
function useDirectory(sessionId: string | null, workspace: string, path: string, enabled: boolean, revision: number) {
  const [data, setData] = useState<DirectoryData>({ entries: [], status: 'loading' });
  useEffect(() => {
    if (!enabled) return;
    let current = true;
    setData(previous => ({ ...previous, status: 'loading', error: '' }));
    const request = sessionId ? api.files(sessionId, path) : api.workspaceTree(workspace, path);
    request.then(result => {
      if (!result) throw new Error('服务端未返回目录内容');
      if (current) setData({ entries: result.entries || [], truncated: result.truncated, status: 'ready' });
    }).catch(error => {
      if (current) setData(previous => ({ ...previous, status: 'error', error: errorInfo(error).message }));
    });
    return () => { current = false; };
  }, [sessionId, workspace, path, enabled, revision]);
  return data;
}

export function WorkspaceTree() {
  const sessionId = useStore(state => state.sessionId);
  const workspace = useStore(state => state.workspace);
  return <WorkspaceFiles key={`${sessionId || ''}:${workspace}`} sessionId={sessionId} workspace={workspace} />;
}

function WorkspaceFiles({ sessionId, workspace }: { sessionId: string | null; workspace: string }) {
  const [open, setOpen] = useState(false);
  const [localRevision, setRevision] = useState(0);
  const [query, setQuery] = useState('');
  const [flatFiles, setFlatFiles] = useState<string[] | null>(null);
  const [flatLoading, setFlatLoading] = useState(false);
  const [menu, setMenu] = useState<ContextMenuState | null>(null);
  const syncRevision = useStore(state => state.ui.filesRevision || 0);
  const changeReport = useStore(state => state.changeReport);
  const revision = localRevision + syncRevision;
  const focused = useRef<string | null>(null);
  const previewPath = useStore(state => state.ui.previewPath);
  const root = useRef<HTMLDivElement>(null);
  const typeahead = useRef({ text: '', time: 0 });
  const data = useDirectory(sessionId, workspace, '', open && !!workspace, revision);
  const refresh = () => { setFlatFiles(null); setRevision(value => value + 1); };

  const gitStatuses = useMemo(() => {
    const map = new Map<string, string>();
    const files: ChangeFile[] = Array.isArray(changeReport?.files) ? changeReport.files : [];
    for (const file of files) {
      if (file?.path) map.set(file.path.replace(/\\/g, '/'), file.status || '');
    }
    return map;
  }, [changeReport]);

  useEffect(() => {
    if (!open || !workspace || !query.trim() || flatFiles !== null) return;
    let active = true;
    setFlatLoading(true);
    const req = sessionId ? api.filesFlat(sessionId) : api.workspaceFiles(workspace);
    req.then(result => {
      if (active) setFlatFiles(Array.isArray(result?.files) ? result.files : []);
    }).catch(() => {
      if (active) setFlatFiles([]);
    }).finally(() => {
      if (active) setFlatLoading(false);
    });
    return () => { active = false; };
  }, [open, workspace, sessionId, query, flatFiles]);

  useEffect(() => {
    if (!menu) return;
    const close = () => setMenu(null);
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setMenu(null); };
    window.addEventListener('click', close);
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('click', close);
      window.removeEventListener('keydown', onKey);
    };
  }, [menu]);

  const filteredFiles = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle || !flatFiles) return [];
    const tokens = needle.split(/\s+/).filter(Boolean);
    return flatFiles.filter(item => {
      const lower = item.toLowerCase();
      return tokens.every(tok => lower.includes(tok));
    }).slice(0, 80);
  }, [query, flatFiles]);

  const ensureFocus = useCallback(() => {
    const rows = [...(root.current?.querySelectorAll<HTMLElement>('[role="treeitem"]') || [])];
    const active = rows.find(row => row.dataset.path === focused.current) || rows[0];
    focused.current = active?.dataset.path || null;
    for (const row of rows) row.tabIndex = row === active ? 0 : -1;
  }, []);

  const runMenuAction = async (action: 'copy' | 'open' | 'reveal') => {
    if (!menu) return;
    const targetPath = menu.path;
    setMenu(null);
    if (action === 'copy') {
      const ok = await writeClipboard(targetPath).catch(() => false);
      useStore.getState().showNotice(ok ? `已复制路径：${targetPath}` : '复制失败，请手动复制');
      return;
    }
    try {
      const res = await api.openFileNative(sessionId, workspace, targetPath, action);
      if (!res) useStore.getState().showNotice('服务端未返回结果');
      else useStore.getState().showNotice(res.ok ? res.message : [res.message, res.alternative].filter(Boolean).join(' '));
    } catch (failure) {
      useStore.getState().showNotice(errorInfo(failure).message || '调用系统应用失败');
    }
  };

  function onKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (!(event.target instanceof Element) || !root.current) return;
    const row = event.target.closest<HTMLElement>('[role="treeitem"]');
    if (!row) return;
    const rows = [...root.current.querySelectorAll<HTMLElement>('[role="treeitem"]')].filter(item => item.getClientRects().length);
    const index = rows.indexOf(row);
    let next;
    if (event.key === 'ArrowDown') next = rows[Math.min(index + 1, rows.length - 1)];
    else if (event.key === 'ArrowUp') next = rows[Math.max(index - 1, 0)];
    else if (event.key === 'Home') next = rows[0];
    else if (event.key === 'End') next = rows.at(-1);
    else if (event.key === 'ArrowRight') {
      if (row.getAttribute('aria-expanded') === 'false') row.click();
      else if (row.getAttribute('aria-expanded') === 'true' && rows[index + 1]?.dataset.parent === row.dataset.path) next = rows[index + 1];
    } else if (event.key === 'ArrowLeft') {
      if (row.getAttribute('aria-expanded') === 'true') row.click();
      else next = rows.find(item => item.dataset.path === row.dataset.parent);
    } else if (event.key.length === 1 && event.key !== ' ' && !event.metaKey && !event.ctrlKey && !event.altKey) {
      const now = Date.now();
      typeahead.current = { text: (now - typeahead.current.time < 650 ? typeahead.current.text : '') + event.key.toLowerCase(), time: now };
      next = [...rows.slice(index + 1), ...rows.slice(0, index + 1)].find(item => (item.dataset.name || '').toLowerCase().startsWith(typeahead.current.text));
    } else return;
    event.preventDefault();
    next?.focus();
  }

  const context: TreeContext = { sessionId, workspace, revision, focused, previewPath, gitStatuses, refresh, ensureFocus, openMenu: setMenu };
  const searching = query.trim().length > 0;
  return <section className={`workspace-files ${open ? 'is-open' : ''}`} aria-label="工作区文件">
    <header className="workspace-files-head">
      <button type="button" className="workspace-files-toggle" aria-expanded={open} aria-controls="file-tree" onClick={() => setOpen(!open)}><Icon name="chevron" /><span>工作区文件</span></button>
      {open && workspace && <button id="tree-refresh" type="button" className="icon-btn" title="刷新文件树" aria-label="刷新文件树" disabled={data.status === 'loading'} onClick={refresh}><Icon name="refresh" /></button>}
    </header>
    {open && <div className="workspace-files-body">
      {workspace && <div className="workspace-root" title={workspace}><FileIcon directory /><span>{workspace.split(/[\\/]/).filter(Boolean).pop()}</span></div>}
      {workspace && (
        <div className="tree-filter">
          <input
            type="search"
            className="tree-filter-input"
            value={query}
            placeholder="快速筛选文件名…"
            aria-label="筛选工作区文件"
            onChange={event => setQuery(event.target.value)}
          />
          {searching && <button type="button" className="tree-filter-clear" onClick={() => setQuery('')}>清空</button>}
        </div>
      )}
      {!workspace ? <p className="tree-message">选择工作区后浏览文件。</p> : searching ? (
        <div id="file-tree" className="file-tree" role="tree" aria-label="工作区文件搜索结果">
          {flatLoading && <p className="tree-message" role="status">正在索引工作区文件…</p>}
          {!flatLoading && !filteredFiles.length && <p className="tree-message">没有匹配的文件。</p>}
          {!!filteredFiles.length && (
            <ul className="file-tree-list" role="none">
              {filteredFiles.map(filePath => {
                const { name, dir } = splitPath(filePath);
                const selected = previewPath === filePath;
                const gitStatus = gitStatuses.get(filePath);
                return (
                  <li key={filePath} role="none">
                    <button
                      type="button"
                      role="treeitem"
                      className={`tree-row${selected ? ' is-active' : ''}`}
                      style={{ paddingInlineStart: 10 }}
                      title={filePath}
                      onClick={() => useStore.getState().openPreview(filePath)}
                      onContextMenu={event => { event.preventDefault(); setMenu({ x: event.clientX, y: event.clientY, path: filePath, directory: false }); }}
                    >
                      <FileIcon name={name} directory={false} />
                      <span className="tree-label">{name}</span>
                      {dir && <span className="tree-search-dir">{dir}</span>}
                      {gitStatus && <span className={`tree-git-badge is-${statusKind(gitStatus)}`}>{statusLetter(gitStatus)}</span>}
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      ) : (
        <div id="file-tree" className="file-tree" role="tree" aria-label="工作区文件" ref={root} onKeyDown={onKeyDown}>
          <TreeEntries data={data} context={context} parent="" depth={1} />
        </div>
      )}
    </div>}
    {menu && (
      <div className="tree-ctx-menu" role="menu" style={{ left: Math.min(menu.x, window.innerWidth - 180), top: Math.min(menu.y, window.innerHeight - 130) }}>
        {!menu.directory && <button type="button" role="menuitem" onClick={() => void runMenuAction('open')}>用系统默认应用打开</button>}
        <button type="button" role="menuitem" onClick={() => void runMenuAction('reveal')}>在文件夹中显示</button>
        <button type="button" role="menuitem" onClick={() => void runMenuAction('copy')}>复制相对路径</button>
      </div>
    )}
  </section>;
}

function TreeEntries({ data, context, parent, depth, groupId }: { data: DirectoryData; context: TreeContext; parent: string; depth: number; groupId?: string }) {
  useLayoutEffect(() => { context.ensureFocus(); }, [data, context.ensureFocus]);
  return <>
    {!!data.entries.length && <ul id={groupId} className="file-tree-list" role={depth === 1 ? 'none' : 'group'}>
      {data.entries.map((entry, index) => <TreeNode key={entry.path} entry={entry} context={context} parent={parent} depth={depth} first={depth === 1 && index === 0} />)}
    </ul>}
    {data.status === 'loading' && <p className="tree-message" role="status">正在读取…</p>}
    {data.status === 'error' && <div className="tree-message is-error" role="alert"><span>无法读取目录：{data.error}</span><button type="button" className="text-action" onClick={context.refresh}>重试</button></div>}
    {data.status === 'ready' && !data.entries.length && <p className="tree-message">空目录</p>}
    {data.truncated && <p className="tree-message">此目录文件较多，仅显示部分条目。</p>}
  </>;
}

function TreeNode({ entry, context, parent, depth, first }: { entry: TreeEntry; context: TreeContext; parent: string; depth: number; first: boolean }) {
  const [open, setOpen] = useState(false);
  const groupId = useId();
  const directory = entry.directory || entry.type === 'dir';
  const data = useDirectory(context.sessionId, context.workspace, entry.path, directory && open, context.revision);
  const selected = !directory && context.previewPath === entry.path;
  const gitStatus = !directory ? context.gitStatuses.get(entry.path.replace(/\\/g, '/')) : undefined;
  return <li role="none">
    <button type="button" role="treeitem" className={`tree-row${selected ? ' is-active' : ''}`} style={{ paddingInlineStart: 8 + (depth - 1) * 16 }}
      data-path={entry.path} data-parent={parent} data-name={entry.name} aria-level={depth} aria-expanded={directory ? open : undefined} aria-selected={selected} aria-owns={directory && open && data.entries.length ? groupId : undefined}
      tabIndex={first ? 0 : -1} title={entry.path}
      onFocus={() => { context.focused.current = entry.path; context.ensureFocus(); }}
      onContextMenu={event => { event.preventDefault(); context.openMenu({ x: event.clientX, y: event.clientY, path: entry.path, directory }); }}
      onClick={() => directory ? setOpen(!open) : useStore.getState().openPreview(entry.path)}>
      <span className={`tree-chevron${open ? ' is-open' : ''}`}>{directory && <Icon name="chevron" />}</span>
      <FileIcon name={entry.name} directory={directory} open={open} /><span className="tree-label">{entry.name}</span>
      {gitStatus && <span className={`tree-git-badge is-${statusKind(gitStatus)}`}>{statusLetter(gitStatus)}</span>}
    </button>
    {directory && open && <TreeEntries groupId={groupId} data={data} context={context} parent={entry.path} depth={depth + 1} />}
  </li>;
}
