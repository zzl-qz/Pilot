import React, { useEffect, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { Icon, FileIcon } from './Icon.jsx';
import { shortcut } from '../lib/interaction.js';
import { writeClipboard } from '../lib/clipboard.js';
import { filterPaletteActions } from '../lib/commands.js';
import { PERMISSION_MODES } from '../lib/permission.js';
import { emptySkillsHint, parseInstallSource, summarizeInstallResults } from '../lib/skills.js';
import { buildModelProbe, buildModelUpdate, describeModelsFetched } from '../lib/model-settings.js';

/** 任务契约状态 → 中文（与旧版 renderTaskContract 的映射一致）。 */
const TASK_STATUS_LABELS = {
  ACTIVE: '执行中', PAUSED: '已暂停', INTERRUPTED: '已中断',
  AWAITING_REVIEW: '待验收', COMPLETED: '已验收', FAILED: '待处理',
  BLOCKED: '遇到阻塞', BUDGET_LIMITED: '已达预算',
  WAITING: '等待外部事件', NEED_INPUT: '需要输入',
  UNVERIFIED: '结果未验证', NO_PROGRESS: '暂无有效进展', REVIEW_UNAVAILABLE: '核验暂不可用'
};
const EXECUTION_MODES = { DIRECT: '普通执行', GOAL: '持续推进目标', PROBE: '旧版探查任务', UNASSESSED: '旧版待迁移任务' };
const EXECUTION_PHASES = {
  ASSESSING: '准备执行', EXPLORING: '探查中', EXECUTING: '执行中', VERIFYING: '核验中',
  UNVERIFIED: '尚未验证'
};

/** 任务契约：目标 / 约束 / 验收记录 / 执行评估，随事件更新，可手动补充约束。 */
export function TaskContract() {
  const task = useStore((state) => state.task);
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
  const items = Array.isArray(task.acceptance) ? task.acceptance : [];
  const checked = items.filter((item) => item.checked).length;
  const status = String(task.status || '').toUpperCase();
  // 没有验收条件时 COMPLETED 只代表正常收尾，不能显示成「已验收」
  const lifecycle = status === 'COMPLETED' && !items.length
    ? '已完成'
    : (TASK_STATUS_LABELS[status] || status || '已保存');
  const execution = task.execution || null;
  return (
    <details id="task-contract" className="task-contract" open={open} onToggle={(event) => setOpen(event.target.open)}>
      <summary id="task-contract-summary">
        任务契约 · {goal ? String(goal).slice(0, 40) : '未设定目标'} · {lifecycle}
        {items.length ? ` · 验收 ${checked}/${items.length}` : ''}
      </summary>
      <div className="task-contract-body">
        <p id="task-original-goal"><b>目标：</b>{goal || '（未设定）'}</p>
        {execution && (
          <div id="task-execution" className="task-execution">
            <p className="task-help">
              {EXECUTION_MODES[execution.mode] || execution.mode}
              {' · '}
              {EXECUTION_PHASES[execution.phase] || execution.phase}
              {execution.requests != null ? ` · 已调用模型 ${execution.requests} 次` : ''}
            </p>
            {execution.reason && <p className="task-help">{execution.reason}</p>}
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
            <ol>{task.corrections.map((text, index) => <li key={index}>{text}</li>)}</ol>
          </div>
        )}
        <label className="task-field">
          约束（每行一条）
          <textarea id="task-constraints" value={constraints} onChange={(event) => { setConstraints(event.target.value); useStore.setState((draft) => { draft.taskDirty = true; }); }} rows={3} />
        </label>
        <label className="task-field">
          验收记录（[x] 表示已完成，可接「—— 证据」）
          <textarea id="task-acceptance" value={criteria} onChange={(event) => { setCriteria(event.target.value); useStore.setState((draft) => { draft.taskDirty = true; }); }} rows={4} />
        </label>
        <div className="task-contract-actions">
          <input
            id="task-add-criterion"
            placeholder="追加验收标准…"
            value={newCriterion}
            onChange={(event) => setNewCriterion(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && newCriterion.trim()) {
                setCriteria((current) => `${current}\n[ ] ${newCriterion.trim()}`.trim());
                setNewCriterion('');
                useStore.setState((draft) => { draft.taskDirty = true; });
              }
            }}
          />
          <button
            type="button"
            className="btn btn-primary btn-sm"
            id="task-contract-save"
            disabled={!useStore.getState().taskDirty}
            onClick={() => useStore.getState().saveTask(
              constraints.split('\n').map((line) => line.trim()).filter(Boolean),
              parseAcceptanceLines(criteria, items)
            )}
          >保存要求与验收记录</button>
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
function parseAcceptanceLines(text, previous = []) {
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
  return (
    <>
      {picker && <WorkspacePicker />}
      {projectEditor && <ProjectEditor />}
      {settingsOpen && <SettingsModal />}
      {paletteOpen && <Palette />}
      {checkpoint && <CheckpointDialog />}
      {confirm && <ConfirmDialog />}
    </>
  );
}

/** 通用二次确认：删除会话、清 Key、删 worktree 这类不可逆操作统一走这里。 */
function ConfirmDialog() {
  const dialog = useStore((state) => state.ui.confirm);
  const close = () => useStore.getState().setUi({ confirm: null });
  return (
    <Modal id="confirm-dialog" title={dialog.title || '确认操作'} onClose={close}>
      <p className="confirm-message">{dialog.message}</p>
      <footer className="modal-foot">
        <button type="button" className="btn" onClick={close}>取消</button>
        <button
          type="button"
          className={dialog.danger ? 'btn btn-danger' : 'btn btn-primary'}
          id="confirm-ok"
          onClick={async () => {
            close();
            await dialog.onConfirm?.();
          }}
        >{dialog.confirmText || '确定'}</button>
      </footer>
    </Modal>
  );
}

export function Modal({ id, title, onClose, children, wide }) {
  const container = useRef(null);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const previous = document.activeElement;
    const node = container.current;
    const focusable = () => [...node.querySelectorAll('button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex="0"]')].filter(el => el.getClientRects().length);
    const topmost = () => [...document.querySelectorAll('.modal')].at(-1) === node;
    const frame = requestAnimationFrame(() => (node.querySelector('input:not([disabled]), textarea, [aria-current="page"]') || focusable()[0] || node).focus());
    const onKey = event => {
      if (!topmost()) return;
      if (event.key === 'Escape' && !event.isComposing) {
        event.preventDefault(); event.stopImmediatePropagation(); close.current();
      } else if (event.key === 'Tab') {
        const items = focusable();
        if (!items.length) { event.preventDefault(); node.focus(); return; }
        const first = items[0], last = items.at(-1);
        if (event.shiftKey && (document.activeElement === first || !node.contains(document.activeElement))) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && (document.activeElement === last || !node.contains(document.activeElement))) { event.preventDefault(); first.focus(); }
      }
    };
    const onFocus = event => { if (topmost() && !node.contains(event.target)) (focusable()[0] || node).focus(); };
    document.addEventListener('keydown', onKey, true);
    document.addEventListener('focusin', onFocus);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener('keydown', onKey, true);
      document.removeEventListener('focusin', onFocus);
      if (previous?.isConnected) previous.focus();
    };
  }, []);
  return (
    <div className="modal" id={id} ref={container} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby={`${id}-title`} onClick={event => { if (event.target === event.currentTarget) onClose(); }}>
      <div className={`modal-box${wide ? ' is-wide' : ''}`}>
        <header className="modal-head">
          <h2 id={`${id}-title`}>{title}</h2>
          <button type="button" className="icon-action" aria-label="关闭弹窗" onClick={onClose}><Icon name="close" /></button>
        </header>
        {children}
      </div>
    </div>
  );
}

/* ---------- 工作区选择 ---------- */

function WorkspacePicker() {
  const picker = useStore(state => state.ui.picker);
  const workspace = useStore(state => state.workspace);
  const [roots, setRoots] = useState([]);
  const [entries, setEntries] = useState([]);
  const [path, setPath] = useState(workspace || '');
  const [directory, setDirectory] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(true);
  const version = useRef(0);
  useEffect(() => {
    let active = true;
    api.workspaceRoots().then(data => {
      if (!active) return;
      const roots = Array.isArray(data) ? data : data?.roots || [];
      setRoots(roots);
      browse(workspace || roots[0]?.path || '');
    }).catch(err => { if (active) { setError(err.message); setBusy(false); } });
    return () => { active = false; version.current++; };
  }, []);
  async function browse(target) {
    const request = ++version.current;
    setBusy(true); setError('');
    try {
      const data = await api.workspaceBrowse(target);
      if (request !== version.current) return;
      setEntries(data?.directories || data?.entries || []);
      setPath(data.path); setDirectory(data);
    } catch (err) { if (request === version.current) setError(err.message); }
    finally { if (request === version.current) setBusy(false); }
  }
  return <Modal id="picker" title={picker?.title || '选择工作区'} onClose={() => useStore.getState().setUi({ picker: null })}>
    <div className="picker-roots">{roots.map(root => <button key={root.path} type="button" className="btn" onClick={() => browse(root.path)}>{root.label || root.name || root.path}</button>)}</div>
    <form className="picker-toolbar" onSubmit={event => { event.preventDefault(); browse(path); }}>
      <button type="button" className="btn" id="picker-up" disabled={busy || !directory?.parent} onClick={() => browse(directory.parent)}>上一级</button>
      <input id="picker-path" aria-label="工作区路径" value={path} onChange={event => setPath(event.target.value)} />
      <button type="submit" className="btn" id="picker-go" disabled={busy}>前往</button>
    </form>
    <ul id="picker-list" className="picker-list" aria-label="文件夹" aria-busy={busy}>
      {entries.map(entry => <li key={entry.path}><button type="button" className="picker-item" onClick={() => browse(entry.path)}><FileIcon directory /><span>{entry.name || entry.path}</span><Icon name="chevron" /></button></li>)}
      {!busy && !error && !entries.length && <li className="panel-empty">这个文件夹没有子目录，可以直接选择。</li>}
    </ul>
    {busy && <p className="chip-hint" role="status">正在读取文件夹…</p>}
    {error && <p id="picker-error" className="picker-error" role="alert">{error}</p>}
    <footer className="modal-foot">
      <span id="picker-selected" title={directory?.path}>{directory?.path || '选择一个文件夹'}</span>
      <button type="button" className="btn btn-primary" id="picker-confirm" disabled={busy || !!error || !directory?.path || path.trim() !== directory.path} onClick={async () => {
        setBusy(true); setError('');
        try {
          if (picker?.onPick) await picker.onPick(directory.path);
          else await useStore.getState().switchWorkspace(directory.path, { throwOnError: true });
          useStore.getState().setUi({ picker: null });
        } catch (err) { setError(err.message); setBusy(false); }
      }}>选择此文件夹</button>
    </footer>
  </Modal>;
}

/* ---------- 项目编辑 ---------- */

function ProjectEditor() {
  const editor = useStore((state) => state.ui.projectEditor);
  const [name, setName] = useState(editor?.name || '');
  const [folders, setFolders] = useState((editor?.folders || []).join('\n'));
  return (
    <Modal id="project-modal" title={editor?.id ? '编辑项目' : '新建项目'} onClose={() => useStore.getState().setUi({ projectEditor: null })}>
      <label className="task-field">项目名称<input id="project-name" value={name} onChange={(event) => setName(event.target.value)} /></label>
      <label className="task-field">绑定文件夹（每行一个绝对路径）<textarea id="project-folders" value={folders} onChange={(event) => setFolders(event.target.value)} rows={3} /></label>
      <p id="project-hint" className="chip-hint">会话按工作区路径归组到项目。</p>
      <footer className="modal-foot">
        {editor?.id && (
          <button type="button" className="btn btn-danger" id="project-delete" onClick={async () => {
            try {
              await api.deleteProject(editor.id);
              await useStore.getState().loadProjects();
              useStore.getState().setUi({ projectEditor: null });
            } catch (error) {
              useStore.getState().showNotice('删除失败：' + error.message);
            }
          }}>删除项目</button>
        )}
        <button type="button" className="btn btn-primary" id="project-save" onClick={async () => {
          const list = folders.split('\n').map((line) => line.trim()).filter(Boolean);
          try {
            if (editor?.id) {
              await api.renameProject(editor.id, { name });
              await api.saveProjectFolders(editor.id, list);
            } else {
              await api.createProject({ name, folders: list });
            }
            await useStore.getState().loadProjects();
            useStore.getState().setUi({ projectEditor: null });
          } catch (error) {
            useStore.getState().showNotice(error.message);
          }
        }}>保存</button>
      </footer>
    </Modal>
  );
}

/* ---------- 设置 ---------- */

const SETTINGS_SECTIONS = [
  ['appearance', '外观'], ['model', '模型'], ['permission', '权限'],
  ['skills', '技能'], ['usage', '用量'], ['diagnostics', '诊断'], ['about', '关于']
];

const SETTINGS_META = {
  appearance: ['palette', '选择适合你的工作环境的主题与强调色。更改即时生效。'],
  model: ['model', '配置模型与连接信息，保存后用于后续请求。'],
  permission: ['shield', '决定哪些操作需要你确认。'],
  skills: ['skill', '管理当前工作区与用户级技能。'],
  usage: ['chart', '查看模型请求与 Token 用量，了解任务开销。'],
  diagnostics: ['pulse', '检查连接、运行记录和本地数据状态。'],
  about: ['info', '当前工作环境与常用快捷键。'],
};

function SettingsModal() {
  const section = useStore((state) => state.ui.settingsSection);
  return (
    <Modal id="settings" title="设置" onClose={() => useStore.getState().setUi({ settingsOpen: false })} wide>
      <div className="settings-layout">
        <nav className="settings-nav" aria-label="设置分类">
          {SETTINGS_SECTIONS.map(([id, label]) => (
            <button
              key={id}
              type="button"
              className={`settings-nav-item${section === id ? ' is-active' : ''}`}
              aria-current={section === id ? 'page' : undefined}
              onClick={() => {
                useStore.getState().setUi({ settingsSection: id });
                if (id === 'usage') {
                  useStore.getState().loadUsage(useStore.getState().ui.usageDays);
                }
                if (id === 'skills') {
                  useStore.getState().loadSkills();
                }
              }}
            ><Icon name={SETTINGS_META[id][0]} /><span>{label}</span></button>
          ))}
        </nav>
        <div className="settings-body" key={section}>
          <header className="settings-section-head"><h3>{SETTINGS_SECTIONS.find(([id]) => id === section)?.[1]}</h3><p>{SETTINGS_META[section]?.[1]}</p></header>
          {section === 'appearance' && <AppearanceSettings />}
          {section === 'model' && <ModelSettings />}
          {section === 'permission' && <PermissionSettings />}
          {section === 'skills' && <SkillsSettings />}
          {section === 'usage' && <UsageSettings />}
          {section === 'diagnostics' && <DiagnosticsSettings />}
          {section === 'about' && <AboutSettings />}
        </div>
      </div>
    </Modal>
  );
}

/** 主题卡上的三个选项（旧版 THEME_MODES 的文案）。 */
const THEME_MODES = [
  { mode: 'light', label: '浅色', hint: '始终亮色' },
  { mode: 'dark', label: '深色', hint: '始终暗色' },
  { mode: 'system', label: '跟随系统', hint: '随系统切换' }
];

/** 强调色：每一项都对应 CSS 里的 html[data-theme][data-accent=…]。 */
const ACCENTS = [
  { name: 'sky', label: '天蓝' },
  { name: 'blue', label: '靛蓝' },
  { name: 'violet', label: '紫罗兰' },
  { name: 'green', label: '松绿' },
  { name: 'amber', label: '暖橙' },
  { name: 'rose', label: '玫红' }
];

/**
 * 外观：主题给一张能看出明暗的小预览，强调色是六个色点。
 *
 * <p>之前是两排纯文字按钮，既看不出选中的是哪个（默认档 sky 不在列表里，
 * 打开时一个都不高亮），也看不出「深色」长什么样。
 */
function AppearanceSettings() {
  const themeMode = useStore((state) => state.ui.themeMode);
  const accent = useStore((state) => state.ui.accent);
  return (
    <div className="settings-appearance">
      <h3>主题</h3>
      <div className="theme-options" id="theme-options" role="group" aria-label="主题">
        {THEME_MODES.map((item) => {
          const on = themeMode === item.mode;
          return (
            <button
              key={item.mode}
              type="button"
              aria-pressed={on}
              title={item.hint}
              className={`theme-card${on ? ' is-on' : ''}`}
              onClick={() => useStore.getState().setTheme(item.mode)}
            >
              <span className={`theme-preview is-${item.mode}`} aria-hidden="true"><span /><span /></span>
              <span className="theme-card-label">{item.label}</span>
              <span className="theme-card-hint">{item.hint}</span>
            </button>
          );
        })}
      </div>
      <h3>强调色</h3>
      <div className="accent-options" id="accent-options" role="group" aria-label="强调色">
        {ACCENTS.map((item) => (
          <button
            key={item.name}
            type="button"
            aria-pressed={accent === item.name}
            aria-label={item.label}
            title={item.label}
            className={`accent-dot is-${item.name}${accent === item.name ? ' is-on' : ''}`}
            onClick={() => useStore.getState().setAccent(item.name)}
          />
        ))}
        <span className="accent-label">{ACCENTS.find((item) => item.name === accent)?.label || '天蓝'}</span>
      </div>
    </div>
  );
}

function ModelSettings() {
  const settings = useStore((state) => state.settingsData);
  // 后端字段：model（字符串）/ modelEndpoint / modelApiKeySet；旧代码读的是 model.name，永远是空
  const [name, setName] = useState(settings?.model || '');
  const [endpoint, setEndpoint] = useState(settings?.modelEndpoint || '');
  const [key, setKey] = useState('');
  const [hint, setHint] = useState('');
  return (
    <div>

      <label className="task-field">模型名称<input id="settings-model-name" value={name} onChange={(event) => setName(event.target.value)} /></label>
      <label className="task-field">API 端点<input id="settings-model-endpoint" value={endpoint} onChange={(event) => setEndpoint(event.target.value)} /></label>
      <label className="task-field">
        API Key
        <input
          id="settings-model-key"
          type="password"
          value={key}
          onChange={(event) => setKey(event.target.value)}
          placeholder={settings?.modelApiKeySet ? `已保存 ${settings?.modelApiKeyMasked || ''}（不填沿用）` : '尚未保存密钥'}
        />
      </label>
      <div className="chip-menu-actions">
        <button type="button" className="btn" id="settings-model-fetch" onClick={async () => {
          try {
            const list = await useStore.getState().probeModels(buildModelProbe({ endpoint, apiKey: key }));
            setHint(describeModelsFetched(list, { endpoint }));
          } catch (error) {
            setHint('探测失败：' + error.message);
          }
        }}>探测可用模型</button>
        <button type="button" className="btn btn-primary" id="settings-model-save" onClick={() => useStore.getState().saveModel(buildModelUpdate({ model: name, endpoint, apiKey: key }))}>保存</button>
        <button
          type="button"
          className="btn btn-danger"
          id="settings-model-clear-key"
          title="删除已保存在本机的 API Key"
          onClick={() => useStore.getState().askConfirm({
            title: '清除 API Key',
            message: '清除后模型请求会失败，直到重新填写。确定清除吗？',
            confirmText: '清除',
            danger: true,
            onConfirm: () => useStore.getState().clearModelKey()
          })}
        >清除 Key</button>
      </div>
      {hint && <p id="settings-model-hint" className="chip-hint">{hint}</p>}
      <VoiceSettings />
    </div>
  );
}

function VoiceSettings() {
  const [value, setValue] = useState(null);
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [hint, setHint] = useState('');
  const load = () => api.voiceSettings().then(setValue).catch(error => setHint(error.message));
  useEffect(() => { load(); }, []);
  return <details className="voice-settings"><summary>语音输入</summary>
    <p className="task-help">录音完成后发送到语音服务，转写文字加入草稿。默认沿用聊天服务的端点和 Key；该服务需支持音频转写。若提示「服务不可用」，通常是聊天端点是中转站、没有转写能力：在下面填写独立端点（例如 https://api.openai.com/v1）与对应模型。</p>
    {value ? <>
      <label className="task-field">语音模型<input placeholder="gpt-4o-mini-transcribe" value={value.model || ''} onChange={e => setValue({ ...value, model: e.target.value })} /></label>
      <label className="task-field">独立语音端点（可选）<input placeholder="留空沿用聊天服务" value={value.endpoint || ''} onChange={e => setValue({ ...value, endpoint: e.target.value })} /></label>
      {value.effectiveEndpoint && <p className="task-help">当前实际请求：{value.effectiveEndpoint}</p>}
      <label className="task-field">语音 API Key（可选）<input type="password" value={key} placeholder={value.apiKeySet ? '已保存（不填沿用）' : '独立服务请填写对应 Key'} onChange={e => setKey(e.target.value)} /></label>
      <div className="chip-menu-actions">
        <button type="button" className="btn" disabled={busy || !value.model?.trim()} onClick={async () => {
          setBusy(true);
          try { setValue(await api.saveVoiceSettings({ endpoint: value.endpoint, model: value.model, apiKey: key })); setKey(''); setHint('语音设置已保存'); }
          catch (error) { setHint(error.message); } finally { setBusy(false); }
        }}>{busy ? '保存中…' : '保存语音设置'}</button>
        {value.apiKeySet && <button type="button" className="btn" disabled={busy} onClick={async () => {
          setBusy(true);
          try { setValue(await api.saveVoiceSettings({ clearApiKey: true })); setKey(''); setHint('已清除独立语音 Key'); }
          catch (error) { setHint(error.message); } finally { setBusy(false); }
        }}>清除语音 Key</button>}
      </div>
    </> : <button type="button" className="btn" onClick={load}>重新加载语音设置</button>}
    {hint && <p className="chip-hint" role="status">{hint}</p>}
  </details>;
}

function PermissionSettings() {
  const mode = useStore((state) => state.permissionMode);
  return (
    <div>
      <h3>权限模式</h3>
      <div className="perm-cards" id="permission-options" role="group" aria-label="权限模式">
        {PERMISSION_MODES.map((item) => (
          <button
            key={item.mode}
            type="button"
            aria-pressed={mode === item.mode}
            className={`perm-card${mode === item.mode ? ' is-on' : ''}`}
            onClick={() => useStore.getState().savePermissionMode(item.mode)}
          >
            <span className="perm-card-head">
              <strong>{item.label}</strong>
              <span className="perm-card-code">{item.code}</span>
            </span>
            <span className="perm-card-hint">{item.hint}</span>
          </button>
        ))}
      </div>
      <p id="settings-permission-hint" className="chip-hint">「完全放行」时所有工具调用不再请求批准，请仅在可信工作区使用；「只读」下写入与命令一律拒绝。</p>
    </div>
  );
}

function SkillsSettings() {
  const skills = useStore((state) => state.skills);
  const meta = useStore((state) => state.skillsMeta);
  const [installing, setInstalling] = useState(false);
  return (
    <div>
      <h3>已安装技能</h3>
      <ul className="skills-list" id="settings-skills-list">
        {(skills || []).map((skill) => (
          <li key={skill.name || skill.id}>
            <b>/{skill.command || skill.name}</b>
            <span className="skill-source">{skill.source === 'project' ? '项目级' : '用户级'}</span>
            <span className="skill-desc">{skill.description || ''}</span>
          </li>
        ))}
        {!skills?.length && (
          <li className="panel-empty">
            {emptySkillsHint(meta?.projectRoot)}也可以从 git 仓库或本机目录一键安装。
          </li>
        )}
      </ul>
      {!!meta?.invalid?.length && (
        <p className="chip-hint">
          有 {meta.invalid.length} 个目录没通过校验（缺 SKILL.md 或名称不合法），已跳过。
        </p>
      )}
      <div className="skills-actions">
        <button type="button" className="btn" id="skills-install-open" onClick={() => setInstalling((open) => !open)}>
          {installing ? '收起安装' : '安装技能…'}
        </button>
        {meta?.projectRoot && <span className="chip-hint">项目级目录：{meta.projectRoot}</span>}
      </div>
      {installing && <SkillsInstall onDone={() => useStore.getState().loadSkills()} />}
    </div>
  );
}

/**
 * 安装向导：来源 → 预览候选 → 选目标级别 → 安装；同名冲突时才出现「覆盖安装」。
 * 安装只复制文件，不带任何钩子（与后端协议一致）。
 */
function SkillsInstall({ onDone }) {
  const sessionId = useStore((state) => state.sessionId);
  const meta = useStore((state) => state.skillsMeta);
  const [source, setSource] = useState('');
  const [token, setToken] = useState('');
  const [candidates, setCandidates] = useState([]);
  const [picked, setPicked] = useState(() => new Set());
  const [target, setTarget] = useState('project');
  const [busy, setBusy] = useState('');
  const [hint, setHint] = useState('填 git 地址或本机目录，先「查看内容」再安装。');
  const [conflicts, setConflicts] = useState([]);

  const preview = async () => {
    // 与后端同一套口径：git 必须带协议，本机路径必须是绝对路径
    const parsed = parseInstallSource(source);
    if (!parsed) {
      setHint('来源要填 http(s):// 开头的 git 地址，或本机技能目录的绝对路径（如 D:\\skills\\review）。');
      return;
    }
    setBusy('preview');
    setHint(parsed.type === 'git' ? '正在拉取仓库（浅克隆）…' : '正在读取目录…');
    try {
      const data = await api.skillsPreview({ source: parsed.value, sessionId: sessionId || undefined });
      const list = Array.isArray(data?.candidates) ? data.candidates : [];
      setToken(data?.token || '');
      setCandidates(list);
      setPicked(new Set(list.filter((item) => item.valid).map((item) => item.name)));
      setConflicts([]);
      setHint(list.length ? `找到 ${list.length} 个技能，勾选后安装。` : (data?.message || '来源里没有找到可安装的技能。'));
    } catch (error) {
      setToken('');
      setCandidates([]);
      setHint('查看内容失败：' + error.message);
    } finally {
      setBusy('');
    }
  };

  const install = async (names, overwrite) => {
    if (!token || !names.length) {
      return;
    }
    setBusy(overwrite ? 'overwrite' : 'install');
    setHint(overwrite ? '正在覆盖安装…' : '正在安装…');
    try {
      const data = await api.skillsInstall({
        token,
        skills: names,
        target,
        sessionId: sessionId || undefined,
        ...(overwrite ? { overwrite: true } : {})
      });
      const results = Array.isArray(data?.results) ? data.results : [];
      const { installed, conflicts: conflicted, errors: failed } = summarizeInstallResults(results);
      setConflicts(conflicted);
      const parts = [];
      if (installed.length) {
        parts.push(`已安装：${installed.join('、')}`);
        setPicked((current) => {
          const next = new Set(current);
          for (const name of installed) {
            next.delete(name);
          }
          return next;
        });
      }
      if (conflicted.length) {
        parts.push(`同名冲突：${conflicted.join('、')}（点「覆盖安装」删旧装新）`);
      }
      if (failed.length) {
        parts.push(`失败：${failed.map((item) => `${item.name}（${item.reason || item.status}）`).join('；')}`);
      }
      setHint(parts.length ? `${parts.join('；')}。` : '没有执行任何安装。');
      if (installed.length) {
        onDone?.();
      }
    } catch (error) {
      setHint('安装失败：' + error.message);
    } finally {
      setBusy('');
    }
  };

  const selectedNames = [...picked];
  return (
    <div className="skills-install" id="skills-install">
      <label className="task-field">
        来源（git 地址或本机目录）
        <span className="skills-install-row">
          <input
            id="skills-install-source"
            value={source}
            placeholder="https://github.com/you/skills 或 D:\\skills\\review"
            onChange={(event) => setSource(event.target.value)}
            onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); preview(); } }}
          />
          <button
            type="button"
            className="btn"
            title="选择本机目录"
            onClick={() => useStore.getState().setUi({
              picker: { title: '选择技能目录', onPick: (path) => setSource(path) }
            })}
          >浏览…</button>
          <button type="button" className="btn" id="skills-install-preview" disabled={busy === 'preview'} onClick={preview}>查看内容</button>
        </span>
      </label>
      {!!candidates.length && (
        <div className="skills-install-candidates" id="skills-install-candidates">
          {candidates.map((item) => (
            <label key={item.name} className={`skills-install-item${item.valid ? '' : ' is-invalid'}`}>
              <input
                type="checkbox"
                disabled={!item.valid}
                checked={picked.has(item.name)}
                onChange={(event) => setPicked((current) => {
                  const next = new Set(current);
                  if (event.target.checked) {
                    next.add(item.name);
                  } else {
                    next.delete(item.name);
                  }
                  return next;
                })}
              />
              <span className="skills-install-name">
                {item.name}
                {item.command ? <code>/{item.command}</code> : null}
              </span>
              <span className="skills-install-desc">{item.valid ? (item.description || '') : `无效：${item.reason || '校验未通过'}`}</span>
            </label>
          ))}
        </div>
      )}
      <div className="skills-install-target" id="skills-install-target">
        {[['project', '装到项目', meta?.projectRoot], ['user', '装到用户目录', meta?.userRoot]].map(([value, label, root]) => (
          <label key={value} title={root || ''}>
            <input type="radio" name="skills-target" checked={target === value} onChange={() => setTarget(value)} />
            {label}
          </label>
        ))}
      </div>
      <div className="skills-install-actions">
        <button
          type="button"
          className="btn btn-primary"
          id="skills-install-confirm"
          disabled={!token || !selectedNames.length || Boolean(busy)}
          onClick={() => install(selectedNames, false)}
        >安装选中的 {selectedNames.length} 个</button>
        {!!conflicts.length && (
          <button
            type="button"
            className="btn btn-danger"
            id="skills-install-overwrite"
            disabled={Boolean(busy)}
            onClick={() => install(conflicts, true)}
          >覆盖安装冲突项</button>
        )}
      </div>
      <p id="skills-install-hint" className="chip-hint">{hint}</p>
    </div>
  );
}

/** token 数字口径：与旧版一致（万级以上转 k/M/B）。 */
function formatCount(value) {
  const number = Number(value) || 0;
  if (number >= 1_000_000_000) return `${(number / 1_000_000_000).toFixed(1)}B`;
  if (number >= 1_000_000) return `${(number / 1_000_000).toFixed(1)}M`;
  if (number >= 10_000) return `${(number / 1_000).toFixed(0)}k`;
  return String(number);
}

function UsageSettings() {
  const usage = useStore((state) => state.usageData);
  const days = useStore((state) => state.ui.usageDays);

  useEffect(() => {
    if (!usage) {
      useStore.getState().loadUsage(days);
    }
  }, []);

  if (!usage) {
    return <p className="panel-empty">选择范围后加载用量。</p>;
  }
  const cards = [
    ['模型请求', formatCount(usage.totalRequests)],
    ['输入 token', formatCount(usage.totalInputTokens)],
    ['输出 token', formatCount(usage.totalOutputTokens)]
  ];
  const daily = usage.daily || [];
  const maxTokens = Math.max(...daily.map((day) => (day.inputTokens || 0) + (day.outputTokens || 0)), 1);
  return (
    <div>

      <div className="option-row" id="usage-range">
        {[7, 14, 30].map((value) => (
          <button key={value} type="button" className={`option${days === value ? ' is-active' : ''}`} onClick={() => useStore.getState().loadUsage(value)}>{value} 天</button>
        ))}
      </div>
      <div id="usage-summary" className="usage-cards">
        {cards.map(([label, value]) => (
          <div key={label} className="usage-card">
            <strong>{value}</strong>
            <span>{label}</span>
          </div>
        ))}
      </div>
      <div id="usage-daily" className="usage-daily">
        {daily.length === 0 && <p className="chip-hint">这个窗口内还没有用量记录。</p>}
        {daily.map((day) => {
          const total = (day.inputTokens || 0) + (day.outputTokens || 0);
          return (
            <div
              key={day.date}
              className="usage-bar-day"
              title={`${day.date}：请求 ${day.requests}，输入 ${formatCount(day.inputTokens)}，输出 ${formatCount(day.outputTokens)}`}
            >
              <div
                className="usage-bar"
                style={{
                  height: `${Math.round((total / maxTokens) * 56)}px`,
                  '--bar-out': `${(day.outputTokens / Math.max(total, 1)) * 100}%`
                }}
              />
              <span>{(day.date || '').slice(5)}</span>
            </div>
          );
        })}
      </div>
      <h3>用量最多的对话</h3>
      <ul id="usage-sessions" className="usage-sessions">
        {(usage.sessions || []).map((session) => (
          <li key={session.sessionId}>
            <span className="usage-session-name" title={`${session.title}（${session.sessionId}）`}>{session.title}</span>
            <span className="usage-session-meta">
              {formatCount((session.inputTokens || 0) + (session.outputTokens || 0))} token · {session.requests} 请求
            </span>
          </li>
        ))}
        {!(usage.sessions || []).length && <li className="panel-empty">还没有可统计的对话。</li>}
      </ul>
    </div>
  );
}

/**
 * 诊断：把后端 Diagnostics / Maintenance / Metrics 三块运维能力摆到界面上。
 * 以前这些只有 curl 能看到——卡住时不知道卡在哪、能不能续跑、数据有没有备份。
 */
function DiagnosticsSettings() {
  const sessionId = useStore((state) => state.sessionId);
  const [data, setData] = useState(null);
  const [busy, setBusy] = useState('');
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const load = async () => {
    setError('');
    try {
      const [maintenance, metrics, recovery, diagnostics, validation] = await Promise.all([
        api.maintenanceStatus(),
        api.metrics(),
        api.recovery(),
        sessionId ? api.diagnostics(sessionId) : Promise.resolve(null),
        sessionId ? api.validation(sessionId) : Promise.resolve(null)
      ]);
      setData({ maintenance, metrics, recovery, diagnostics, validation });
    } catch (err) {
      setError(err.message);
    }
  };

  useEffect(() => { load(); }, [sessionId]);

  const run = async (kind) => {
    setBusy(kind);
    setMessage('');
    try {
      if (kind === 'backup') {
        const report = await api.backupNow();
        setMessage(report?.ok ? `已备份 ${report.files} 个文件到 ${report.directory}` : (report?.message || '备份未完成'));
      } else {
        const report = await api.purgeNow();
        setMessage(`已清理：${report?.removed ?? 0} 条过期数据`);
      }
      await load();
    } catch (err) {
      setMessage('操作失败：' + err.message);
    } finally {
      setBusy('');
    }
  };

  if (!data && !error) {
    return <p className="panel-empty">读取诊断信息…</p>;
  }
  const maintenance = data?.maintenance;
  const metrics = data?.metrics;
  const recovery = data?.recovery;
  const diagnostics = data?.diagnostics;
  const validation = data?.validation;
  const counters = Object.entries(metrics?.metrics?.counters || {}).sort((a, b) => b[1] - a[1]).slice(0, 8);

  return (
    <div id="settings-diagnostics" className="diagnostics">
      {error && <p className="chip-hint is-error">读取失败：{error}</p>}

      <h3>存储与备份</h3>
      {maintenance ? (
        <>
          <dl className="diag-grid">
            <div><dt>数据目录</dt><dd className="diag-path" title={maintenance.backup?.dataDir}>{maintenance.backup?.dataDir || '—'}</dd></div>
            <div><dt>数据库</dt><dd>{formatBytes(maintenance.backup?.databaseBytes)}</dd></div>
            <div><dt>备份</dt><dd>{maintenance.backup?.backups ?? 0} 份</dd></div>
            <div><dt>上次备份</dt><dd>{maintenance.backup?.lastBackupAt || '从未'}</dd></div>
            <div><dt>保留策略</dt><dd>{maintenance.retentionDays > 0 ? `${maintenance.retentionDays} 天` : '不自动清理'}</dd></div>
            <div><dt>执行隔离</dt><dd>{maintenance.isolated ? '已启用' : (maintenance.degraded ? '降级运行' : '未启用')}</dd></div>
          </dl>
          {!maintenance.storage?.healthy && (
            <p className="chip-hint is-error">
              存储告警：{(maintenance.storage?.issues || []).map((issue) => issue.message || issue.kind || String(issue)).join('；') || '未知问题'}
            </p>
          )}
          {maintenance.degraded && maintenance.degradedReason && (
            <p className="chip-hint">隔离降级原因：{maintenance.degradedReason}</p>
          )}
          <div className="diag-actions">
            <button type="button" className="btn" disabled={Boolean(busy)} onClick={() => run('backup')}>
              {busy === 'backup' ? '备份中…' : '立即备份'}
            </button>
            <button type="button" className="btn" disabled={Boolean(busy)} onClick={() => run('purge')} title="按保留策略删除过期事件与快照">
              {busy === 'purge' ? '清理中…' : '清理过期数据'}
            </button>
          </div>
          {message && <p className="chip-hint">{message}</p>}
        </>
      ) : <p className="chip-hint">没有维护信息。</p>}

      <h3>运行指标</h3>
      {metrics?.metrics ? (
        <>
          <dl className="diag-grid">
            <div><dt>运行轮数</dt><dd>{metrics.metrics.runs}</dd></div>
            <div><dt>平均时长</dt><dd>{formatDuration(metrics.metrics.averageRunMs)}</dd></div>
            <div><dt>统计窗口</dt><dd>{metrics.retentionDays > 0 ? `${metrics.retentionDays} 天` : '全部'}</dd></div>
          </dl>
          {counters.length > 0 && (
            <ul className="diag-counters">
              {counters.map(([name, value]) => (
                <li key={name}><span>{name}</span><b>{formatCount(value)}</b></li>
              ))}
            </ul>
          )}
        </>
      ) : <p className="chip-hint">还没有运行指标。</p>}

      <h3>恢复快照</h3>
      {recovery ? (
        <p className="chip-hint">
          {recovery.recoveredAt ? `上次启动：${recovery.recoveredAt}` : '本进程还没有执行过启动恢复'} ·
          {' '}接管 {recovery.entries?.length ?? 0} 条 · {recovery.instanceId || '—'}
          {recovery.note ? ` · ${recovery.note}` : ''}
        </p>
      ) : <p className="chip-hint">没有恢复记录。</p>}

      <h3>当前对话诊断</h3>
      {!sessionId && <p className="chip-hint">打开一个对话后可以看到它的卡点、重试与校验状态。</p>}
      {diagnostics && (
        <>
          <dl className="diag-grid">
            <div><dt>状态</dt><dd>{diagnostics.sessionStatus} · {diagnostics.phase || '—'} 第 {diagnostics.step} 步</dd></div>
            <div><dt>上次成功</dt><dd>{diagnostics.lastSuccessfulStep || '—'}</dd></div>
            <div><dt>下一步</dt><dd>{diagnostics.nextAction || '—'}</dd></div>
            <div><dt>模型请求</dt><dd>{diagnostics.modelRequests} 次</dd></div>
            <div><dt>Token</dt><dd>{formatCount(diagnostics.inputTokens)} 输入 / {formatCount(diagnostics.outputTokens)} 输出{diagnostics.usageExact ? '' : '（估算）'}</dd></div>
            <div><dt>最近工具</dt><dd>{diagnostics.lastToolSummary || '—'}</dd></div>
          </dl>
          {diagnostics.retryAttempt != null && (
            <p className="chip-hint">
              正在重试第 {diagnostics.retryAttempt} 次{diagnostics.retryDelayMs ? `，${Math.round(diagnostics.retryDelayMs / 1000)}s 后` : ''}
              {diagnostics.retryReason ? `：${diagnostics.retryReason}` : ''}
            </p>
          )}
          {!!(diagnostics.commands || []).length && (
            <ul className="diag-commands">
              {diagnostics.commands.map((task) => (
                <li key={task.taskId}>
                  <code>{task.command}</code>
                  <span>{task.status}{task.exitCode != null ? ` · exit ${task.exitCode}` : ''}</span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      {validation?.report && (
        <p className={`chip-hint${validation.report.covered ? '' : ' is-error'}`}>
          校验证据：{validation.report.covered ? '已覆盖当前产物' : '未覆盖当前产物'}
          {validation.report.reason ? `（${validation.report.reason}）` : ''}
        </p>
      )}
    </div>
  );
}

function formatBytes(value) {
  const size = Number(value) || 0;
  if (size <= 0) return '0 B';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function formatDuration(ms) {
  const value = Number(ms) || 0;
  if (value < 1000) return `${Math.round(value)} ms`;
  if (value < 60000) return `${(value / 1000).toFixed(1)} s`;
  return `${Math.floor(value / 60000)} 分 ${Math.round((value % 60000) / 1000)} 秒`;
}

function AboutSettings() {
  const workspace = useStore((state) => state.workspace);
  const connection = useStore((state) => state.ui.connection);
  const permissionMode = useStore((state) => state.permissionMode);
  return (
    <div id="settings-about" className="about">
      <div className="about-brand"><span className="logo" aria-hidden="true">P</span><div><strong>Pilot</strong><span>本地编码工作区</span></div></div>
      <p><b>工作区：</b><span id="settings-about-workspace">{workspace || '未选择'}</span></p>
      <p><b>连接：</b><span id="settings-about-connection">{connection.text}</span></p>
      <p><b>权限：</b><span id="settings-about-permission">{permissionMode}</span></p>
      <dl className="shortcut-list">{[['新任务','N'],['命令面板','K'],['展开侧栏','B'],['审阅面板','J'],['设置',',']].map(([label,key]) => <div key={key}><dt>{label}</dt><dd><kbd>{shortcut(key)}</kbd></dd></div>)}</dl>
    </div>
  );
}

/* ---------- 命令面板 ---------- */

function Palette() {
  const [query, setQuery] = useState('');
  const [index, setIndex] = useState(0);
  const sessions = useStore((state) => state.sessions);
  const items = paletteItems(query, sessions);
  const pick = (item) => {
    if (!item) {
      return;
    }
    item.run();
    useStore.getState().setUi({ paletteOpen: false });
  };
  return (
    <Modal id="palette" title="命令面板" onClose={() => useStore.getState().setUi({ paletteOpen: false })}>
      <input
        id="palette-input"
        autoFocus
        value={query}
        placeholder="搜索会话或输入命令…"
        onChange={(event) => { setQuery(event.target.value); setIndex(0); }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            if (!items.length) {
              return;
            }
            event.preventDefault();
            const delta = event.key === 'ArrowDown' ? 1 : -1;
            setIndex((current) => (current + delta + items.length) % items.length);
            return;
          }
          if (event.key === 'Enter') {
            event.preventDefault();
            // 取当前选中项，不是恒取第一条
            pick(items[Math.min(index, items.length - 1)]);
          }
        }}
      />
      <ul id="palette-list" className="picker-list">
        {items.map((item, position) => (
          <li key={`${item.label}-${position}`}>
            <button
              type="button"
              className={`picker-item${position === index ? ' is-on' : ''}`}
              onMouseEnter={() => setIndex(position)}
              onClick={() => pick(item)}
            >
              <span>{item.label}</span>
              {item.hint ? <span className="picker-hint">{item.hint}</span> : null}
            </button>
          </li>
        ))}
        {!items.length && <li className="panel-empty">没有匹配的命令或会话</li>}
      </ul>
    </Modal>
  );
}

/** 面板条目 = 会话 + 旧版动作表（lib/commands.js 里那份，之前没人用）。 */
function paletteItems(query, sessions) {
  const needle = String(query || '').trim().toLowerCase();
  const items = [];
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

function runPaletteAction(action) {
  const state = useStore.getState();
  switch (action) {
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
        writeClipboard(state.sessionId).then(ok => state.showNotice(ok ? '会话 ID 已复制' : '复制失败'));
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
    const messages = [];
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
  const code = info?.code || {};
  const canCode = Boolean(info) && code.ready !== false && (code.files || []).length > 0;
  const canConversation = Boolean(info?.conversationAvailable);
  const [mode, setMode] = useState('');

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
  ];
  const busy = Boolean(dialog?.applying);

  return (
    <Modal id="checkpoint-dialog" title="回到这条消息发送前" onClose={() => useStore.getState().setUi({ checkpointDialog: null })}>
      {dialog?.loading && <p className="panel-empty">正在检查后续改动…</p>}
      {dialog?.error && <p className="chip-hint is-error">{dialog.error}</p>}
      {info && (
        <>
          <blockquote className="checkpoint-excerpt">{info.input || '（这条消息没有可回填的文本）'}</blockquote>
          <p className="checkpoint-hint">
            将回退这条消息及之后的 {info.runs ?? 0} 轮任务，涉及 {(code.files || []).length} 个文件。{(code.files || []).length > 0 ? '选择还原文件时，这些改动中新建的文件会被删除。' : '没有文件需要还原。'}
          </p>
          {!!(code.conflicts || []).length && (
            <div className="checkpoint-block">
              <p>检测到后续修改，代码回退已被阻止：</p>
              <ul>{code.conflicts.map((path) => <li key={path}>{path}</li>)}</ul>
            </div>
          )}
          {!!(code.unavailable || []).length && (
            <div className="checkpoint-block">
              <p>快照不可用，无法回退代码：</p>
              <ul>{code.unavailable.map((text) => <li key={text}>{text}</li>)}</ul>
            </div>
          )}
          {!!(code.files || []).length && (
            <details className="checkpoint-block">
              <summary>涉及 {code.files.length} 个文件</summary>
              <ul>{code.files.map((path) => <li key={path}>{path}</li>)}</ul>
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
      <footer className="modal-foot">
        <button type="button" className="btn" onClick={() => useStore.getState().setUi({ checkpointDialog: null })}>取消</button>
        <button
          type="button"
          className="btn"
          id="checkpoint-fork"
          disabled={!info || !canConversation || busy}
          onClick={() => useStore.getState().forkCheckpoint(dialog.sequence, info?.revision)}
        >另开对话</button>
        <button
          type="button"
          className="btn btn-danger"
          id="checkpoint-restore"
          disabled={!info || !mode || busy}
          onClick={() => useStore.getState().restoreCheckpoint(dialog.sequence, mode, info?.revision)}
        >{busy ? '回退中…' : '确认回退'}</button>
      </footer>
    </Modal>
  );
}
