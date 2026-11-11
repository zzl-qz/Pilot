import { useEffect, useRef, useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { IconButton } from '@astryxdesign/core/IconButton';
import { TextArea } from '@astryxdesign/core/TextArea';
import { TextInput } from '@astryxdesign/core/TextInput';
import { Modal } from './Modal.jsx';
import { Icon, FileIcon } from './Icon.jsx';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { errorInfo } from '../lib/errors.js';
import { appendProjectFolders, folderPaths, MAX_PROJECT_FOLDERS, validateProjectFolders } from '../lib/project-folders.js';
import type { ApiResults } from '../types/api.js';
import type * as S from '../types/server.js';
import './project-editor.css';

/* ---------- 项目编辑 ---------- */

export function ProjectEditor() {
  const editor = useStore(state => state.ui.projectEditor);
  const project = useStore(state => state.projects.find(item => item.id === state.ui.projectEditor?.id));
  const [name, setName] = useState(editor?.name ?? project?.name ?? '');
  const [folders, setFolders] = useState((editor?.folders ?? project?.folders ?? []).join('\n'));
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [browserOpen, setBrowserOpen] = useState(false);
  const [notice, setNotice] = useState('');
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const paths = folderPaths(folders);
  const close = () => useStore.getState().setUi({ projectEditor: null });

  function addFolders(selected: string[]) {
    try {
      setFolders(appendProjectFolders(folders, selected));
      setError('');
    } catch (failure) {
      setError(errorInfo(failure).message || '无法添加所选目录。');
    }
  }

  async function chooseFolders() {
    if (busy) return;
    setError('');
    const desktop = window.pilotDesktop;
    if (!desktop?.selectDirectories) {
      setBrowserOpen(true);
      return;
    }
    setBusy('pick');
    try {
      const result = await desktop.selectDirectories();
      if (!mounted.current) return;
      if (!result.ok) {
        setNotice(result.message || '系统目录选择暂不可用，请浏览服务端文件夹或手动填写路径。');
        setBrowserOpen(true);
      } else if (!result.canceled) {
        if (!Array.isArray(result.paths)) throw new Error('目录选择器返回了无效结果，请重试。');
        if (result.paths.length) addFolders(result.paths);
      }
    } catch (failure) {
      if (!mounted.current) return;
      setNotice(`${errorInfo(failure).message || '无法打开系统目录选择器。'} 可改用服务端目录浏览或手动填写路径。`);
      setBrowserOpen(true);
    } finally {
      if (mounted.current) setBusy('');
    }
  }

  async function update(kind: 'save' | 'delete') {
    if (busy) return;
    setBusy(kind);
    setError('');
    try {
      const list = kind === 'delete' ? [] : validateProjectFolders(folders);
      if (kind === 'delete' && editor?.id) {
        await api.deleteProject(editor.id);
      } else if (editor?.id) {
        await api.renameProject(editor.id, { name });
        await api.saveProjectFolders(editor.id, list);
      } else {
        await api.createProject({ name, folders: list });
      }
      await useStore.getState().loadProjects();
      if (useStore.getState().ui.projectEditor === editor) close();
    } catch (failure) {
      setError(errorInfo(failure).message || '无法更新项目，请重试。');
    } finally {
      setBusy('');
    }
  }

  const footer = <>
        {editor?.id && <Button id="project-delete" variant="ghost" className="pilot-danger" label={busy === 'delete' ? '删除中…' : '删除项目'}
          isDisabled={Boolean(busy)} onClick={() => useStore.getState().askConfirm({
            title: '删除项目',
            message: `确定删除项目「${project?.name || name}」吗？`,
            confirmText: '删除',
            danger: true,
            onConfirm: () => update('delete')
          })} />}
        <Button id="project-save" onClick={() => update('save')} variant="primary" label={busy === 'save' ? '保存中…' : '保存'} isDisabled={Boolean(busy) || !name.trim() || !paths.length} />
  </>;

  return <Modal id="project-modal" title={editor?.id ? '编辑项目' : '新建项目'} onClose={close} dismissible={!busy} footer={footer}>
    <form className="pilot-form" onSubmit={event => {
      event.preventDefault();
      if (name.trim()) void update('save');
    }} onKeyDown={event => {
      if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault();
    }}>
      <TextInput id="project-name" label="项目名称" width="100%" size="lg" value={name} isDisabled={Boolean(busy)} onChange={setName} />
      <section className="project-folders-section" aria-labelledby="project-folders-label">
        <div className="project-folders-heading">
          <span id="project-folders-label">绑定文件夹 <span className="project-folder-count">{paths.length} / {MAX_PROJECT_FOLDERS}</span></span>
          <Button id="project-choose-folders" label={busy === 'pick' ? '正在选择…' : '选择文件夹'}
            isDisabled={Boolean(busy)} onClick={chooseFolders} />
        </div>
        <p className="project-folder-help">可绑定多个目录，对话按工作区路径归组到项目。</p>
        {paths.length ? <ul className="project-folder-list" aria-label="已选文件夹">
          {paths.map(path => <li key={path}>
            <FileIcon directory />
            <span className="project-selected-path" title={path}>{path}</span>
            <IconButton label={`移除 ${path}`} icon={<Icon name="close" />} variant="ghost" isDisabled={Boolean(busy)}
              onClick={() => setFolders(paths.filter(item => item !== path).join('\n'))} />
          </li>)}
        </ul> : <p className="project-no-folders">尚未选择文件夹。点击「选择文件夹」开始添加。</p>}
        {notice && <p className="pilot-inline-status" role="status">{notice}</p>}
        {browserOpen && <ProjectDirectoryBrowser disabled={Boolean(busy)} selected={paths} onAdd={addFolders}
          onClose={() => setBrowserOpen(false)} />}
        <details className="project-folder-manual">
          <summary>手动填写路径</summary>
          <TextArea id="project-folders" label="文件夹绝对路径" description="每行一个路径，必须存在于 Pilot 服务所在的电脑。"
            value={folders} onChange={setFolders} rows={3} width="100%" isDisabled={Boolean(busy)} hasSpellCheck={false} />
        </details>
      </section>
      {error && <p className="pilot-inline-status" role="alert">{error}</p>}

    </form>
  </Modal>;
}

/** 浏览的是 Pilot 服务的目录。浏览器不伪造本地绝对路径，也不上传整个目录。 */
function ProjectDirectoryBrowser({ disabled, selected, onAdd, onClose }: {
  disabled: boolean; selected: string[]; onAdd: (paths: string[]) => void; onClose: () => void;
}) {
  const workspace = useStore(state => state.workspace);
  const [roots, setRoots] = useState<S.WorkspaceServiceRootEntry[]>([]);
  const [directory, setDirectory] = useState<ApiResults['workspaceBrowse'] | null>(null);
  const [path, setPath] = useState(workspace || '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const version = useRef(0);

  async function browse(target: string) {
    const request = ++version.current;
    setBusy(true);
    setError('');
    setDirectory(null);
    setPath(target);
    try {
      const result = await api.workspaceBrowse(target);
      if (request !== version.current) return;
      if (!result || typeof result.path !== 'string') throw new Error('服务端返回空目录信息，请重试。');
      setDirectory(result);
      setPath(result.path);
    } catch (failure) {
      if (request === version.current) setError(errorInfo(failure).message || '无法读取该目录。');
    } finally {
      if (request === version.current) setBusy(false);
    }
  }

  useEffect(() => {
    const request = ++version.current;
    setBusy(true);
    api.workspaceRoots().then(result => {
      if (request !== version.current) return;
      const legacy = result as S.WorkspaceServiceRootEntry[] | { roots?: S.WorkspaceServiceRootEntry[] };
      const entries = Array.isArray(legacy) ? legacy : legacy?.roots || [];
      setRoots(entries);
      return browse(workspace || entries[0]?.path || '');
    }).catch(failure => {
      if (request !== version.current) return;
      setError(errorInfo(failure).message || '无法读取目录入口，可填写路径后重试。');
      setBusy(false);
    });
    return () => { version.current++; };
  }, []);

  const unavailable = disabled || busy;
  function added(target: string) { return folderPaths([...selected, target].join('\n')).length === selected.length; }
  return <section className="project-folder-browser" aria-label="浏览服务端文件夹">
    <div className="project-folders-heading">
      <strong>浏览服务端文件夹</strong><Button label="收起目录浏览" variant="ghost" onClick={onClose} />
    </div>
    <p className="project-folder-help">这里显示 Pilot 服务所在电脑的目录，可逐个添加多个文件夹。</p>
    {!!roots.length && <div className="picker-roots" role="group" aria-label="目录入口">
      {roots.map(root => <Button key={root.path} label={root.label || root.path} isDisabled={unavailable} onClick={() => browse(root.path)} />)}
    </div>}
    <div className="project-folder-location">
      <TextInput id="project-browse-path" label="服务端目录路径" width="100%" value={path} hasSpellCheck={false}
        isDisabled={unavailable} onChange={setPath} onKeyDown={event => {
          if (event.key === 'Enter') {
            event.preventDefault(); event.stopPropagation();
            if (!event.nativeEvent.isComposing && event.keyCode !== 229 && !unavailable) void browse(path);
          }
        }} />
      <div className="project-folder-actions">
        <Button label="上一级" isDisabled={unavailable || !directory?.parent} onClick={() => browse(directory?.parent || '')} />
        <Button label="前往" isDisabled={unavailable} onClick={() => browse(path)} />
      </div>
    </div>
    {busy && <p role="status">正在读取文件夹…</p>}
    {error && <p className="pilot-inline-status" role="alert">{error} <Button label="重试读取目录" onClick={() => browse(path)} /></p>}
    {directory && <>
      <Button id="project-add-current" label={added(directory.path) ? '当前文件夹已添加' : '添加当前文件夹'}
        isDisabled={unavailable || path !== directory.path || added(directory.path)} onClick={() => onAdd([directory.path])} />
      <ul className="project-browser-list" aria-label="服务端子目录" aria-busy={busy}>
        {(directory.directories || directory.entries || []).map(entry => <li key={entry.path}>
          <button type="button" className="picker-item" disabled={unavailable} onClick={() => browse(entry.path)}>
            <FileIcon directory /><span>{entry.name || entry.path}</span>
          </button>
          <IconButton label={added(entry.path) ? `已添加 ${entry.name || entry.path}` : `添加 ${entry.name || entry.path}`}
            icon={<Icon name={added(entry.path) ? 'check' : 'plus'} />} variant="ghost" isDisabled={unavailable || added(entry.path)}
            onClick={() => onAdd([entry.path])} />
        </li>)}
        {!(directory.directories || directory.entries || []).length && <li className="project-folder-help">没有子目录，可添加当前文件夹。</li>}
      </ul>
    </>}
  </section>;
}

