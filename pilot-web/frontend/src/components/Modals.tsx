import type * as S from '../types/server.js';
import type { ApiResults } from '../types/api.js';
import type { SessionSummary } from '../types/core.js';
type PaletteItem = { label: string; hint: string; run: () => unknown };
import { errorInfo } from '../lib/errors.js';
import { useEffect, useId, useRef, useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { TextArea } from '@astryxdesign/core/TextArea';
import { TextInput } from '@astryxdesign/core/TextInput';
import { Modal } from './Modal.jsx';
import { SettingsModal } from './Settings.jsx';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { Icon, FileIcon } from './Icon.jsx';
import { writeClipboard } from '../lib/clipboard.js';
import { filterPaletteActions } from '../lib/commands.js';
import { MobileConnectionContent } from './MobileConnection.jsx';
import { verificationLabel, verificationHint } from '../lib/verification.js';
import { splitAttachmentMarkers } from '../lib/attachments.js';

export { Modal };

/** 任务契约状态 → 中文（与旧版 renderTaskContract 的映射一致）。 */
const TASK_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '执行中', PAUSED: '已暂停', INTERRUPTED: '已中断',
  AWAITING_REVIEW: '待验收', COMPLETED: '已验收', FAILED: '待处理',
  BLOCKED: '遇到阻塞', BUDGET_LIMITED: '已达预算',
  WAITING: '等待外部事件', NEED_INPUT: '需要输入',
  UNVERIFIED: '结果未验证', NO_PROGRESS: '暂无有效进展', REVIEW_UNAVAILABLE: '核验暂不可用'
};
const EXECUTION_MODES: Record<string, string> = { DIRECT: '普通执行', GOAL: '持续推进目标', PROBE: '旧版探查任务', UNASSESSED: '旧版待迁移任务' };

/** 任务契约：目标 / 约束 / 验收记录 / 执行评估，随事件更新，可手动补充约束。 */
export function TaskContract() {
  const task = useStore((state) => state.task);
  const dirty = useStore(state => state.taskDirty);
  const busy = useStore(state => state.taskBusy);
  const [constraints, setConstraints] = useState('');
  const [criteria, setCriteria] = useState('');
  const [open, setOpen] = useState(false);
  const [newCriterion, setNewCriterion] = useState('');

  useEffect(() => {
    setConstraints((task?.constraints || []).join('\n'));
    setCriteria((task?.acceptance || [])
      .map((item) => `${item.checked ? '[x]' : '[ ]'} ${item.text || ''}${item.evidence ? ` —— ${item.evidence}` : ''}`)
      .join('\n'));
  }, [task]);

  if (!task) {
    return null;
  }
  const goal = task.originalGoal || task.goal || '';
  const goalParts = splitAttachmentMarkers(goal);
  const displayGoal = goalParts.body || (goalParts.files.length
    ? `查看附件：${goalParts.files.map(file => file.name).join('、')}`
    : goal);
  const items = Array.isArray(task.acceptance) ? task.acceptance : [];
  const checked = items.filter((item) => item.checked).length;
  const status = String(task.status || '').toUpperCase();
  // 没有验收条件时 COMPLETED 只代表正常收尾，不能显示成「已验收」
  const lifecycle = status === 'COMPLETED' && !items.length
    ? '执行结束'
    : (TASK_STATUS_LABELS[status] || status || '已保存');
  const execution = task.execution || null;
  return (
    <details id="task-contract" className="task-contract" open={open} onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary id="task-contract-summary">
        <span className="task-contract-label">任务契约</span>
        <span className="task-contract-goal" title={displayGoal}>{displayGoal || '未设定目标'}</span>
        <span className="task-contract-status">{lifecycle}{items.length ? ` · 验收 ${checked}/${items.length}` : ''}</span>
      </summary>
      <div className="task-contract-body">
        <p id="task-original-goal"><b>目标：</b>{displayGoal || '（未设定）'}</p>
        {execution && (
          <div id="task-execution" className="task-execution">
            <p className="task-help">
              {EXECUTION_MODES[execution.mode] || execution.mode}
              {' · '}
              {verificationLabel(execution.phase)}
              {execution.requests != null ? ` · 已调用模型 ${execution.requests} 次` : ''}
            </p>
            {execution.reason && <p className="task-help">{execution.reason}</p>}
            {verificationHint(execution.phase) && <p className="task-help">{verificationHint(execution.phase)}</p>}
            {(execution.criteria || []).length > 0 && (
              <ul className="task-criteria">
                {execution.criteria.map((criterion, index) => (
                  <li key={index}>
                    {`${criterion.required === false ? '[可选]' : '[必需]'} ${criterion.outcome}；验证：${criterion.verification}`}
                  </li>
                ))}
              </ul>
            )}
            {execution.gap && <p className="task-help">当前缺口：{execution.gap}</p>}
          </div>
        )}
        {(task.corrections || []).length > 0 && (
          <div id="task-corrections" className="task-corrections">
            <p className="task-help">已记录的引导</p>
            <ol>{(task.corrections || []).map((text, index) => <li key={index}>{text}</li>)}</ol>
          </div>
        )}
        <div className="task-contract-fields">
          <TextArea id="task-constraints" label="约束（每行一条）" width="100%" value={constraints}
            onChange={value => { setConstraints(value); useStore.setState(draft => { draft.taskDirty = true; draft.taskDraftRevision++; }); }} rows={3} />
          <TextArea id="task-acceptance" label="验收记录" description="每行一条，用 [x] 标记完成，可接「—— 证据」。"
            width="100%" value={criteria} onChange={value => { setCriteria(value); useStore.setState(draft => { draft.taskDirty = true; draft.taskDraftRevision++; }); }} rows={4} />
        </div>
        <div className="task-contract-actions">
          <TextInput id="task-add-criterion" label="追加验收标准" isLabelHidden placeholder="追加验收标准…"
            value={newCriterion} onChange={setNewCriterion} onEnter={() => {
              if (newCriterion.trim()) {
                setCriteria(current => `${current}\n[ ] ${newCriterion.trim()}`.trim());
                setNewCriterion('');
                useStore.setState(draft => { draft.taskDirty = true; draft.taskDraftRevision++; });
              }
            }} />
          <Button id="task-contract-save" label={busy ? '保存中…' : '保存要求与验收记录'} variant="primary" isDisabled={!dirty || busy}
            onClick={() => useStore.getState().saveTask(
              constraints.split('\n').map((line) => line.trim()).filter(Boolean),
              parseAcceptanceLines(criteria, items)
            )} />
        </div>
      </div>
    </details>
  );
}

/**
 * 文本框 → 接口的 acceptance。
 * 后端字段是 {checked,text,evidence}，之前发的是 {done,text}：Jackson 不认 done，
 * 勾选的验收项在保存后会全部变回未勾选，证据也丢了。
 */
function parseAcceptanceLines(text: string, previous: S.TaskControlServiceAcceptance[] = []) {
  const evidence = new Map((previous || []).map((item) => [item.text, item.evidence]));
  return String(text || '')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const itemChecked = /^\[[xX]\]/.test(line);
      const body = line.replace(/^\[[ xX]\]\s*/, '');
      const [main, ...rest] = body.split('——');
      const name = main.trim();
      const evidenceText = rest.join('——').trim() || evidence.get(name) || '';
      return { checked: itemChecked, text: name, evidence: evidenceText };
    });
}

/** 全部弹层：工作区选择 / 项目编辑 / 设置 / 命令面板 / 检查点。 */
export function Modals() {
  const picker = useStore((state) => state.ui.picker);
  const projectEditor = useStore((state) => state.ui.projectEditor);
  const settingsOpen = useStore((state) => state.ui.settingsOpen);
  const paletteOpen = useStore((state) => state.ui.paletteOpen);
  const checkpoint = useStore((state) => state.ui.checkpointDialog);
  const confirm = useStore((state) => state.ui.confirm);
  const mobileConnectionOpen = useStore((state) => state.ui.mobileConnectionOpen);
  return (
    <>
      {picker && <WorkspacePicker />}
      {projectEditor && <ProjectEditor />}
      {settingsOpen && <SettingsModal />}
      {paletteOpen && <Palette />}
      {checkpoint && <CheckpointDialog key={checkpoint.sequence} />}
      {confirm && <ConfirmDialog />}
      {mobileConnectionOpen && <Modal id="mobile-connection" title="连接手机" width={760} onClose={() => useStore.getState().setUi({ mobileConnectionOpen: false })}>
        <MobileConnectionContent onDone={() => useStore.getState().setUi({ mobileConnectionOpen: false })} />
      </Modal>}
    </>
  );
}

/** 通用二次确认：删除会话、清 Key、删 worktree 这类不可逆操作统一走这里。 */
function ConfirmDialog() {
  const dialog = useStore((state) => state.ui.confirm);
  const close = () => useStore.getState().setUi({ confirm: null });
  if (!dialog) return null;
  return (
    <Modal id="confirm-dialog" title={dialog.title || '确认操作'} onClose={close} initialFocus="#confirm-cancel" footer={<>
      <Button id="confirm-cancel" label="取消" onClick={close} />
      <Button id="confirm-ok" variant={dialog.danger ? 'destructive' : 'primary'} label={dialog.confirmText || '确定'}
        onClick={async () => {
          close();
          await dialog.onConfirm?.();
        }} />
    </>}>
      <p className="confirm-message">{dialog.message}</p>
    </Modal>
  );
}

/* ---------- 工作区选择 ---------- */

function WorkspacePicker() {
  const picker = useStore(state => state.ui.picker);
  const workspace = useStore(state => state.workspace);
  const [roots, setRoots] = useState<(S.WorkspaceServiceRootEntry & { name?: string })[]>([]);
  const [path, setPath] = useState(workspace || '');
  const [directory, setDirectory] = useState<ApiResults['workspaceBrowse'] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(true);
  const [selecting, setSelecting] = useState(false);
  const [retry, setRetry] = useState<(() => void) | null>(null);
  const version = useRef(0);
  const errorRef = useRef<HTMLParagraphElement>(null);
  useEffect(() => { errorRef.current?.scrollIntoView({ block: 'nearest' }); }, [error]);

  async function browse(target: string) {
    const request = ++version.current;
    setBusy(true);
    setError('');
    setRetry(null);
    try {
      const data = await api.workspaceBrowse(target);
      if (!data || typeof data.path !== 'string') throw new Error('服务端返回空目录信息，请重试。');
      if (request !== version.current) return;
      setPath(data.path);
      setDirectory(data);
    } catch (failure) {
      if (request !== version.current) return;
      setError(errorInfo(failure).message || '无法读取该文件夹。');
      setRetry(() => () => { void browse(target); });
    } finally {
      if (request === version.current) setBusy(false);
    }
  }

  async function loadRoots() {
    const request = ++version.current;
    setBusy(true);
    setError('');
    setRetry(null);
    try {
      const data = await api.workspaceRoots();
      if (request !== version.current) return;
      const legacy = data as S.WorkspaceServiceRootEntry[] | { roots?: S.WorkspaceServiceRootEntry[] } | null;
      const next = Array.isArray(legacy) ? legacy : legacy?.roots || [];
      setRoots(next);
      await browse(workspace || next[0]?.path || '');
    } catch (failure) {
      if (request !== version.current) return;
      setError(errorInfo(failure).message || '无法读取文件夹入口。');
      setRetry(() => () => { void loadRoots(); });
      setBusy(false);
    }
  }

  useEffect(() => {
    void loadRoots();
    return () => { version.current++; };
  }, []);

  const close = () => useStore.getState().setUi({ picker: null });
  const canPick = Boolean(directory?.path && path.trim() === directory.path && !busy && !selecting && !retry);
  async function selectDirectory() {
    if (!directory || !canPick) return;
    setSelecting(true);
    setError('');
    try {
      if (picker?.onPick) await picker.onPick(directory.path);
      else await useStore.getState().switchWorkspace(directory.path, { throwOnError: true });
      if (useStore.getState().ui.picker === picker) close();
    } catch (failure) {
      setError(errorInfo(failure).message || '无法选择该文件夹，请重试。');
    } finally {
      setSelecting(false);
    }
  }

  const entries = directory?.directories || directory?.entries || [];
  return <Modal id="picker" title={picker?.title || '选择工作区'} onClose={close} dismissible={!selecting} footer={<>
    <span id="picker-selected">{directory?.path || '选择一个文件夹'}</span>
    <Button id="picker-confirm" label={selecting ? '正在选择…' : '选择此文件夹'} variant="primary" isDisabled={!canPick} onClick={selectDirectory} />
  </>}>
    {!!roots.length && <div className="picker-roots" role="group" aria-label="常用文件夹">
      {roots.map(root => <Button key={root.path} label={root.label || root.name || root.path} isDisabled={busy || selecting}
        onClick={() => browse(root.path)} />)}
    </div>}
    <form className="picker-toolbar" onSubmit={event => {
      event.preventDefault();
      if (!busy && !selecting) void browse(path);
    }} onKeyDown={event => {
      if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault();
    }}>
      <TextInput id="picker-path" label="工作区路径" size="lg" width="100%" value={path} hasSpellCheck={false} autoComplete="off"
        isDisabled={busy || selecting} onChange={setPath} />
      <Button id="picker-up" label="上一级" isDisabled={busy || selecting || !directory?.parent} onClick={() => browse(directory?.parent || '')} />
      <Button id="picker-go" label="前往" type="submit" isDisabled={busy || selecting} />
    </form>
    {busy && <p className="picker-status" role="status">正在读取文件夹…</p>}
    {error && <div className="picker-status">
      <p ref={errorRef} id="picker-error" className="pilot-inline-status" role="alert">{error}</p>
      {retry && <Button label="重试读取文件夹" onClick={retry} />}
    </div>}
    <ul id="picker-list" className="picker-list" aria-label="文件夹" aria-busy={busy}>
      {entries.map(entry => <li key={entry.path}>
        <button type="button" className="picker-item" disabled={busy || selecting} onClick={() => browse(entry.path)}>
          <FileIcon directory /><span>{entry.name || entry.path}</span><Icon name="chevron" />
        </button>
      </li>)}
      {!busy && !error && !entries.length && directory && <li className="panel-empty">这个文件夹没有子目录，可以直接选择。</li>}
    </ul>
  </Modal>;
}

/* ---------- 项目编辑 ---------- */

function ProjectEditor() {
  const editor = useStore(state => state.ui.projectEditor);
  const project = useStore(state => state.projects.find(item => item.id === state.ui.projectEditor?.id));
  const [name, setName] = useState(editor?.name ?? project?.name ?? '');
  const [folders, setFolders] = useState((editor?.folders ?? project?.folders ?? []).join('\n'));
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const close = () => useStore.getState().setUi({ projectEditor: null });

  async function update(kind: 'save' | 'delete') {
    if (busy) return;
    setBusy(kind);
    setError('');
    const list = folders.split('\n').map(line => line.trim()).filter(Boolean);
    try {
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

  return <Modal id="project-modal" title={editor?.id ? '编辑项目' : '新建项目'} onClose={close} dismissible={!busy}>
    <form className="pilot-form" onSubmit={event => {
      event.preventDefault();
      if (name.trim()) void update('save');
    }} onKeyDown={event => {
      if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault();
    }}>
      <TextInput id="project-name" label="项目名称" width="100%" size="lg" value={name} isDisabled={Boolean(busy)} onChange={setName} />
      <TextArea id="project-folders" label="绑定文件夹" description="每行一个绝对路径。对话按工作区路径归组到项目。"
        value={folders} onChange={setFolders} rows={3} width="100%" isDisabled={Boolean(busy)} hasSpellCheck={false} />
      {error && <p className="pilot-inline-status" role="alert">{error}</p>}
      <footer className="modal-foot">
        {editor?.id && <Button id="project-delete" variant="ghost" className="pilot-danger" label={busy === 'delete' ? '删除中…' : '删除项目'}
          isDisabled={Boolean(busy)} onClick={() => useStore.getState().askConfirm({
            title: '删除项目',
            message: `确定删除项目「${project?.name || name}」吗？`,
            confirmText: '删除',
            danger: true,
            onConfirm: () => update('delete')
          })} />}
        <Button id="project-save" type="submit" variant="primary" label={busy === 'save' ? '保存中…' : '保存'} isDisabled={Boolean(busy) || !name.trim()} />
      </footer>
    </form>
  </Modal>;
}

/* ---------- 命令面板 ---------- */

function Palette() {
  const [query, setQuery] = useState('');
  const [index, setIndex] = useState(0);
  const sessions = useStore(state => state.sessions);
  const items = paletteItems(query, sessions);
  const activeIndex = Math.min(index, items.length - 1);
  const listId = useId();
  const close = () => useStore.getState().setUi({ paletteOpen: false });

  useEffect(() => {
    if (activeIndex >= 0) document.getElementById(`${listId}-${activeIndex}`)?.scrollIntoView({ block: 'nearest' });
  }, [activeIndex, listId]);

  function pick(item: PaletteItem | undefined) {
    if (!item) return;
    item.run();
    close();
  }

  return <Modal id="palette" title="命令面板" onClose={close}>
    <div className="palette-search">
      <Icon name="search" />
      <input id="palette-input" role="combobox" aria-label="搜索命令或对话" aria-autocomplete="list" aria-expanded="true"
        aria-controls="palette-list" aria-activedescendant={activeIndex >= 0 ? `${listId}-${activeIndex}` : undefined}
        autoComplete="off" spellCheck={false} value={query} placeholder="搜索会话或输入命令…"
        onChange={event => { setQuery(event.target.value); setIndex(0); }}
        onKeyDown={event => {
          if (event.nativeEvent.isComposing || event.keyCode === 229) return;
          if (event.key === 'Enter') {
            event.preventDefault();
            pick(items[activeIndex]);
          } else if (items.length && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
            event.preventDefault();
            const delta = event.key === 'ArrowDown' ? 1 : -1;
            setIndex((activeIndex + delta + items.length) % items.length);
          }
        }} />
    </div>
    <ul id="palette-list" className="picker-list" role="listbox" aria-label="匹配的命令和对话">
      {items.map((item, position) => <li key={`${item.label}-${position}`} role="presentation">
        <button id={`${listId}-${position}`} type="button" role="option" tabIndex={-1} aria-selected={position === activeIndex}
          className={`picker-item${position === activeIndex ? ' is-on' : ''}`} title={item.label}
          onPointerMove={event => { if (event.pointerType === 'mouse') setIndex(position); }}
          onMouseDown={event => event.preventDefault()} onClick={() => pick(item)}>
          <span>{item.label}</span>
          {item.hint && <span className="picker-hint">{item.hint}</span>}
        </button>
      </li>)}
    </ul>
    {!items.length && <p className="pilot-inline-status" role="status">没有匹配的命令或会话。</p>}
  </Modal>;
}

/** 面板条目 = 会话 + 旧版动作表（lib/commands.js 里那份，之前没人用）。 */
function paletteItems(query: string, sessions: SessionSummary[]) {
  const needle = String(query || '').trim().toLowerCase();
  const items: PaletteItem[] = [];
  for (const session of sessions || []) {
    if (!needle || (session.title || '').toLowerCase().includes(needle)) {
      items.push({
        label: `会话 · ${session.title || '新对话'}`,
        hint: '',
        run: () => useStore.getState().activate(session.id)
      });
    }
  }
  for (const action of filterPaletteActions(query)) {
    items.push({ label: action.label, hint: action.hint || '', run: () => runPaletteAction(action.action) });
  }
  return items.slice(0, 16);
}

function runPaletteAction(action: string) {
  const state = useStore.getState();
  switch (action) {
    case 'refresh-workbench':
      state.refreshWorkbench();
      break;
    case 'reload-workbench':
      state.setUi({ paletteOpen: false });
      state.reloadWorkbench();
      break;
    case 'new-session':
      state.startNewSession();
      break;
    case 'open-context':
      state.setUi({ inspectorOpen: true, inspectorTab: 'context' });
      break;
    case 'toggle-activity':
      state.setUi((ui) => ({ inspectorOpen: !ui.inspectorOpen }));
      break;
    case 'open-settings':
      state.setUi({ settingsOpen: true, settingsSection: 'appearance' });
      break;
    case 'refresh-changes':
      state.loadChanges();
      break;
    case 'copy-session-id':
      if (state.sessionId) {
        writeClipboard(state.sessionId).then(ok => state.showNotice(ok ? '会话 ID 已复制' : '自动复制不可用。请在对话操作中选择「复制会话 ID」，然后手动复制。'));
      }
      break;
    case 'reset-view':
      state.setUi({ filters: 'all', inspectorTab: 'activity', changeView: null });
      break;
    case 'export-markdown':
      exportTranscript();
      break;
    case 'toggle-notify':
      state.toggleNotify();
      break;
    case 'reconnect':
      if (state.sessionId) {
        state.connectSse(state.sessionId, state.thread.cursor);
        state.showNotice('已重新连接事件流');
      }
      break;
    default:
      break;
  }
}

function exportTranscript() {
  import('../lib/transcript.js').then(({ buildTranscript, transcriptFileName }) => {
    const state = useStore.getState();
    const rows = state.thread.rows.filter((row) => row.type === 'user' || row.type === 'turn');
    const messages: { role: string; text?: string; summary?: string }[] = [];
    for (const row of rows) {
      if (row.type === 'user') {
        messages.push({ role: 'user', text: row.text });
      } else {
        for (const tool of row.tools || []) {
          messages.push({ role: 'tools', summary: `${tool.name} ${tool.args || ''}`.trim() });
        }
        if (row.reply?.text) {
          messages.push({ role: 'assistant', text: row.reply.text });
        }
      }
    }
    // buildTranscript 收的是对象参数：位置参数会让导出文件只剩「未命名对话」一行标题
    const blob = new Blob([buildTranscript({
      title: state.sessionTitle,
      workspace: state.workspace,
      exportedAt: new Date().toLocaleString(),
      messages
    })], { type: 'text/markdown' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = transcriptFileName(state.sessionTitle);
    link.click();
    URL.revokeObjectURL(link.href);
  });
}

/* ---------- 检查点 ---------- */

/**
 * 回到某条消息发送前。
 *
 * <p>后端要求明确选择 code / conversation / both：一起撤、只撤代码、只撤对话三者差别很大
 * （对话回退会丢掉之后累积的模型记忆），不能让界面替用户猜。预览里的 revision 同时用于乐观锁。
 */
function CheckpointDialog() {
  const dialog = useStore((state) => state.ui.checkpointDialog);
  const info = dialog?.info;
  const code: Partial<S.RunSnapshotServiceRestorePreview> & { ready?: boolean } = info?.code || {};
  const canCode = Boolean(info) && code.ready !== false && Boolean(code.files?.length)
    && !code.conflicts?.length && !code.unavailable?.length;
  const canConversation = Boolean(info?.conversationAvailable);
  const [mode, setMode] = useState('');
  const errorRef = useRef<HTMLParagraphElement>(null);
  useEffect(() => { errorRef.current?.scrollIntoView({ block: 'nearest' }); }, [dialog?.error]);

  // 预览到达后选一个可用范围：两者都能撤就默认「一起撤」
  useEffect(() => {
    if (!info) {
      return;
    }
    if (canCode && canConversation) {
      setMode('both');
    } else if (canCode) {
      setMode('code');
    } else if (canConversation) {
      setMode('conversation');
    } else {
      setMode('');
    }
  }, [info]);

  const options = [
    ['both', '一起撤', '还原文件，移除这条消息及之后的对话，并把原消息回填到输入框', canCode && canConversation],
    ['code', '只撤代码', '只还原后续任务改过的文件，保留当前对话', canCode],
    ['conversation', '只撤对话', '移除这条消息及之后的对话并回填输入框，文件保持现状', canConversation]
  ] as const;
  const busy = Boolean(dialog?.applying);
  const close = () => useStore.getState().setUi({ checkpointDialog: null });
  const canRestore = options.some(([value, , , enabled]) => mode === value && enabled);

  return (
    <Modal id="checkpoint-dialog" title="回到这条消息发送前" onClose={close} dismissible={!busy} footer={<>
      <Button label="取消" isDisabled={busy} onClick={close} />
      <Button id="checkpoint-fork" label="另开对话" isDisabled={!info || !canConversation || busy}
        onClick={() => { if (dialog?.sequence != null && info) void useStore.getState().forkCheckpoint(dialog.sequence, info.revision); }} />
      <Button id="checkpoint-restore" variant="destructive" label={busy ? '正在处理…' : '确认回退'} isDisabled={!info || !canRestore || busy}
        onClick={() => { if (dialog?.sequence != null && info && canRestore) void useStore.getState().restoreCheckpoint(dialog.sequence, mode, info.revision); }} />
    </>}>
      {dialog?.loading && <p className="pilot-inline-status" role="status">正在检查后续改动…</p>}
      {dialog?.error && <p ref={errorRef} className="pilot-inline-status" role="alert">{dialog.error}</p>}
      {info && (
        <>
          <blockquote className="checkpoint-excerpt">{info.input || '（这条消息没有可回填的文本）'}</blockquote>
          <p className="checkpoint-hint">
            将回退这条消息及之后的 {info.runs ?? 0} 轮任务，涉及 {(code.files || []).length} 个文件。{(code.files || []).length > 0 ? '选择还原文件时，这些改动中新建的文件会被删除。' : '没有文件需要还原。'}
          </p>
          {!!(code.conflicts || []).length && (
            <div className="checkpoint-block">
              <p>检测到后续修改，代码回退已被阻止：</p>
              <ul>{(code.conflicts || []).map((path) => <li key={path}>{path}</li>)}</ul>
            </div>
          )}
          {!!(code.unavailable || []).length && (
            <div className="checkpoint-block">
              <p>快照不可用，无法回退代码：</p>
              <ul>{(code.unavailable || []).map((text) => <li key={text}>{text}</li>)}</ul>
            </div>
          )}
          {!!(code.files || []).length && (
            <details className="checkpoint-block">
              <summary>涉及 {(code.files || []).length} 个文件</summary>
              <ul>{(code.files || []).map((path) => <li key={path}>{path}</li>)}</ul>
            </details>
          )}
          {!canConversation && (
            <p className="checkpoint-hint">这条历史消息没有可用的对话检查点，无法回退对话或另开分支。</p>
          )}
          <fieldset className="checkpoint-choices" id="checkpoint-choices">
            <legend>选择回退范围</legend>
            {options.map(([value, label, hint, enabled]) => (
              <label key={value} className={enabled ? '' : 'is-disabled'}>
                <input
                  type="radio"
                  name="checkpoint-mode"
                  value={value}
                  checked={mode === value}
                  disabled={!enabled || busy}
                  onChange={() => setMode(value)}
                />
                <span>
                  <strong>{label}</strong>
                  <small>{hint}</small>
                </span>
              </label>
            ))}
          </fieldset>
          <p className="checkpoint-hint">另开对话：保留原聊天，从此处历史开始新对话，共用现有文件。此操作不创建 Git 分支。</p>
        </>
      )}
    </Modal>
  );
}
