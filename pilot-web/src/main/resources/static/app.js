import { normalizePath, parseDiff } from './lib/diff.js';
import {
  buildModelUpdate,
  classifyModelError,
  describeModelSaved,
  errorText
} from './lib/model-settings.js';

const EVENT_TYPES = [
  'run.started',
  'assistant.delta',
  'context.compacted',
  'plan.updated',
  'run.steered',
  'run.continued',
  'subagent.started',
  'subagent.finished',
  'tool.started',
  'approval.required',
  'approval.resolved',
  'tool.completed',
  'run.failed',
  'run.completed'
];

const el = {
  app: document.querySelector('#app'),
  sidebarToggle: document.querySelector('#sidebar-toggle'),
  scrim: document.querySelector('#scrim'),
  modeBadge: document.querySelector('#mode-badge'),
  permChip: document.querySelector('#perm-chip'),
  permMenu: document.querySelector('#perm-menu'),
  modelChip: document.querySelector('#model-chip'),
  modelMenu: document.querySelector('#model-menu'),
  modelOptions: document.querySelector('#model-options'),
  modelName: document.querySelector('#model-name'),
  modelEndpoint: document.querySelector('#model-endpoint'),
  modelKey: document.querySelector('#model-key'),
 modelClearKey: document.querySelector('#model-clear-key'),
  modelHint: document.querySelector('#model-hint'),
  modelSave: document.querySelector('#model-save'),
  attachments: document.querySelector('#attachments'),
  workspaceChip: document.querySelector('#workspace-chip'),
  workspaceName: document.querySelector('#workspace-name'),
  sessionChip: document.querySelector('#session-chip'),
  connection: document.querySelector('#connection'),
  connectionText: document.querySelector('#connection-text'),
  newSession: document.querySelector('#new-session'),
  sessionList: document.querySelector('#session-list'),
  sessionEmpty: document.querySelector('#session-empty'),
  crumbTitle: document.querySelector('#crumb-title'),
  notice: document.querySelector('#notice'),
  noticeText: document.querySelector('#notice-text'),
  noticeClose: document.querySelector('#notice-close'),
  runState: document.querySelector('#run-state'),
  messages: document.querySelector('#messages'),
  emptyState: document.querySelector('#empty-state'),
  prompts: document.querySelector('#prompts'),
  approvalDock: document.querySelector('#approval-dock'),
  toggleActivity: document.querySelector('#toggle-activity'),
  activity: document.querySelector('#activity'),
  railToggle: document.querySelector('#rail-toggle'),
  sessionCount: document.querySelector('#session-count'),
  tree: document.querySelector('#file-tree'),
  treeEmpty: document.querySelector('#tree-empty'),
  treeRefresh: document.querySelector('#tree-refresh'),
  panelPreview: document.querySelector('#panel-preview'),
  previewPath: document.querySelector('#preview-path'),
  previewBody: document.querySelector('#preview-body'),
  previewRefresh: document.querySelector('#preview-refresh'),
  refMenu: document.querySelector('#ref-menu'),
  gutterLeft: document.querySelector('#gutter-left'),
  gutterRight: document.querySelector('#gutter-right'),
  filters: document.querySelector('#filters'),
  panelTabs: document.querySelector('#panel-tabs'),
  panelActivity: document.querySelector('#panel-activity'),
  panelChanges: document.querySelector('#panel-changes'),
  changeCount: document.querySelector('#change-count'),
  changesState: document.querySelector('#changes-state'),
  changesRefresh: document.querySelector('#changes-refresh'),
  changesRollback: document.querySelector('#changes-rollback'),
  runChanges: document.querySelector('#run-changes'),
  changeFiles: document.querySelector('#change-files'),
  changeDiff: document.querySelector('#change-diff'),
  timeline: document.querySelector('#timeline'),
  timelineEmpty: document.querySelector('#timeline-empty'),
  composer: document.querySelector('#composer'),
  input: document.querySelector('#input'),
  send: document.querySelector('#send'),
  palette: document.querySelector('#palette'),
  paletteInput: document.querySelector('#palette-input'),
  paletteList: document.querySelector('#palette-list'),
  picker: document.querySelector('#picker'),
  pickerClose: document.querySelector('#picker-close'),
  pickerRoots: document.querySelector('#picker-roots'),
  pickerUp: document.querySelector('#picker-up'),
  pickerPath: document.querySelector('#picker-path'),
  pickerGo: document.querySelector('#picker-go'),
  pickerList: document.querySelector('#picker-list'),
  pickerError: document.querySelector('#picker-error'),
  pickerSelected: document.querySelector('#picker-selected'),
  pickerConfirm: document.querySelector('#picker-confirm')
};

const LAST_WORKSPACE_KEY = 'pilot.workspace';
const LAST_SESSION_KEY = 'pilot.session';
/** 超过这个秒数没有新事件，就提示「可能卡住」，让用户知道还能停止。 */
const STALL_SECONDS = 75;

const state = {
  sessionId: null,
  sessionTitle: '',
  workspace: null,
  runId: null,
  lastRunId: null,
  runStartedAt: null,
  lastEventAt: null,
  clock: null,
  cursor: 0,
  agentMode: null,
  permissionMode: null,
  historyLimit: 60,
  historyHasMore: false,
  replaying: false,
  changedFiles: [],
  /** 当前这一轮里出现过的 runId：自动继续会换 run，改动要合并展示 */
  turnRuns: [],
  composerEnabled: false,
  streamCard: null,
  streamText: '',
  renderQueued: false,
  sessions: [],
  source: null,
  running: false,
  thinking: null,
  reply: null,
  replyText: '',
  toolRows: new Map(),
  pendingInputs: [],
  attachments: [],
  modelOptions: [],
  modelApiKeySet: false,
  modelApiKeyMasked: '',
  previewPath: null,
  refKind: null,
  refItems: [],
  refIndex: 0,
  pendingApprovals: new Map(),
  activityCount: 0,
  filter: 'all',
  tab: 'activity',
  pickerPath: '',
  pickerParent: null,
  pickerSelection: '',
  paletteIndex: 0,
  paletteItems: []
};

const COMMANDS = [
  { label: '新任务（选择工作区）', hint: 'Ctrl N', run: () => openPicker() },
  { label: '切换活动/变更面板', hint: 'Ctrl B', run: () => toggleActivity() },
  { label: '刷新变更', hint: '', run: () => selectTab('changes') },
  { label: '复制会话 ID', hint: '', run: copySessionId },
  { label: '清空当前视图', hint: '', run: () => resetView() },
  { label: '重新连接事件流', hint: '', run: () => reconnect() }
];

init();

function init() {
  el.newSession.addEventListener('click', () => openPicker());
  el.permChip.addEventListener('click', () => togglePermissionMenu());
  el.modelChip.addEventListener('click', () => toggleModelMenu());
  el.modelSave.addEventListener('click', saveModel);
  el.modelClearKey.addEventListener('change', onModelClearKeyToggle);
  el.input.addEventListener('paste', onPasteAttachments);
  document.addEventListener('click', (event) => {
    if (!el.permMenu.hidden && !event.target.closest('.perm-picker')) {
      togglePermissionMenu(false);
    }
    if (!el.modelMenu.hidden && !event.target.closest('.perm-picker')) {
      toggleModelMenu(false);
    }
  });
  el.workspaceChip.addEventListener('click', () => openPicker());
  el.sessionChip.addEventListener('click', copySessionId);
  el.sidebarToggle.addEventListener('click', toggleSidebar);
  el.railToggle.addEventListener('click', toggleSidebar);
  el.treeRefresh.addEventListener('click', () => loadTree(''));
  el.previewRefresh.addEventListener('click', () => {
    if (state.previewPath) {
      openPreview(state.previewPath);
    }
  });
  el.scrim.addEventListener('click', closeOverlays);
  el.toggleActivity.addEventListener('click', toggleActivity);
  el.filters.addEventListener('click', onFilterClick);
  el.prompts.addEventListener('click', onPromptClick);
  el.panelTabs.addEventListener('click', onTabClick);
  el.changesRefresh.addEventListener('click', () => loadChanges());
  el.changesRollback.addEventListener('click', rollbackRun);
  el.send.addEventListener('click', onSendButtonClick);
  el.noticeClose.addEventListener('click', hideNotice);
  el.pickerClose.addEventListener('click', closePicker);
  el.pickerUp.addEventListener('click', pickerUp);
  el.pickerGo.addEventListener('click', () => browseTo(el.pickerPath.value.trim()));
  el.pickerPath.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      browseTo(el.pickerPath.value.trim());
    }
  });
  el.pickerConfirm.addEventListener('click', confirmPicker);
  el.picker.addEventListener('click', (event) => {
    if (event.target === el.picker) closePicker();
  });
  el.paletteInput.addEventListener('input', () => renderPalette(el.paletteInput.value));
  el.paletteInput.addEventListener('keydown', onPaletteKeydown);
  el.palette.addEventListener('click', (event) => {
    if (event.target === el.palette) closePalette();
  });
  document.addEventListener('keydown', onGlobalKeydown);
  window.addEventListener('resize', syncScrim);
  bindComposer();
  initWorkbench();
  loadHealth();
  restoreLastSession();
}

/**
 * 刷新后优先恢复上次打开的会话。
 *
 * <p>会话和事件都在服务端，界面只需要重新拉一次列表并回放事件，不再每次刷新都新建会话。
 * 只有一条历史都没有时，才退回「按上次工作区新建」或首次选择目录。
 */
async function restoreLastSession() {
  setComposerEnabled(false);
  setRunState('idle', '加载历史');
  const sessions = await loadSessions();
  const lastId = localStorage.getItem(LAST_SESSION_KEY);
  const target = sessions.find((item) => item.id === lastId) || sessions[0];
  if (target) {
    activate(target.id, target.title, target.workspace);
    return;
  }
  const lastWorkspace = localStorage.getItem(LAST_WORKSPACE_KEY);
  if (lastWorkspace) {
    startSession(lastWorkspace);
  } else {
    openPicker({ firstRun: true });
  }
}

async function loadSessions() {
  try {
    const response = await fetch('/api/sessions');
    if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    }
    const list = await response.json();
    state.sessions = list.map((item) => ({
      id: item.sessionId,
      title: item.title || '未命名会话',
      workspace: item.workspace,
      createdAt: item.createdAt,
      status: item.status
    }));
    renderSessions();
    return state.sessions;
  } catch (error) {
    console.warn('[sessions]', error);
    showNotice('读取会话列表失败：' + error.message);
    return [];
  }
}

/* ---------- 健康检查 ---------- */

async function loadHealth() {
  try {
    const response = await fetch('/api/health');
    if (!response.ok) {
      return;
    }
    const health = await response.json();
    const real = health.agentMode === 'real';
    state.agentMode = health.agentMode;
    syncComposer();
    el.modeBadge.textContent = real ? `真实模型 · ${health.model}` : 'Mock 剧本模式';
    el.modeBadge.className = 'mode-chip ' + (real ? 'is-real' : 'is-mock');
    el.modeBadge.title = real
      ? `可用工具：${(health.tools || []).join('、') || '无'}`
      : '未调用大模型，事件由内置剧本产生';

    if (health.permissionMode) {
      applyPermissionMode(health.permissionMode);
    }

    if (real) {
      hideNotice();
    } else {
      showNotice('当前为 Mock 模式：事件由内置剧本产生，没有调用大模型。设置 PILOT_AGENT_MODE=real 和 PILOT_MODEL_API_KEY 后重启即可接入真实模型。');
    }
    await loadSettings();
  } catch (error) {
    console.warn('[health]', error);
  }
}

/* ---------- 权限模式 ---------- */

const PERMISSION_MODES = [
  { mode: 'READ_ONLY', label: 'READ_ONLY', hint: '只读：写入与命令一律拒绝' },
  { mode: 'ASK', label: 'ASK', hint: '逐次批准：每次写入和命令都要确认' },
  { mode: 'AUTO_EDIT', label: '自动改', hint: '改文件直接生效，命令仍需批准；改完看 diff，可回滚' },
  { mode: 'AUTO', label: 'AUTO', hint: '全部放行：写入与命令都不再打断' }
];

function applyPermissionMode(mode) {
  const value = String(mode || '').toUpperCase();
  const found = PERMISSION_MODES.find((item) => item.mode === value);
  state.permissionMode = value;
  el.permChip.textContent = found ? found.label : value;
  el.permChip.className = 'perm-chip is-' + value.toLowerCase().replace(/_/g, '');
  el.permChip.title = (found ? found.hint : value) + ' · 点击切换';
  el.permChip.hidden = false;
  renderPermissionMenu();
}

function togglePermissionMenu(force) {
  const open = force === undefined ? el.permMenu.hidden : force;
  el.permMenu.hidden = !open;
  el.permChip.setAttribute('aria-expanded', String(open));
  if (open) {
    renderPermissionMenu();
  }
}

function renderPermissionMenu() {
  el.permMenu.replaceChildren();
  for (const item of PERMISSION_MODES) {
    const option = document.createElement('button');
    option.type = 'button';
    option.setAttribute('role', 'menuitem');
    option.className = 'perm-option' + (item.mode === state.permissionMode ? ' is-active' : '');
    const label = document.createElement('strong');
    label.textContent = item.label;
    const hint = document.createElement('span');
    hint.textContent = item.hint;
    option.append(label, hint);
    option.addEventListener('click', () => choosePermission(item.mode));
    el.permMenu.append(option);
  }
}

/** 运行时切换：服务端立即生效，重启后回到配置文件里的初始值。 */
async function choosePermission(mode) {
  togglePermissionMenu(false);
  if (mode === state.permissionMode) {
    return;
  }
  try {
    const response = await fetch('/api/settings/permission-mode', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mode })
    });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const data = await response.json();
    applyPermissionMode(data.permissionMode);
    showNotice('权限已切换为「' + el.permChip.textContent + '」，对后续工具调用立即生效。');
  } catch (error) {
    showNotice('切换权限失败：' + error.message);
  }
}

/* ---------- 模型设置 ---------- */

async function loadSettings() {
  try {
    const response = await fetch('/api/settings');
    if (!response.ok) {
      return;
    }
    applyModelSettings(await response.json());
  } catch (error) {
    console.warn('[settings]', error);
  }
}

function applyModelSettings(data) {
  if (!data) {
    return;
  }
  if (data.model) {
    el.modelChip.textContent = data.model;
    el.modelChip.hidden = false;
  }
  el.modelName.value = data.model || '';
  el.modelEndpoint.value = data.modelEndpoint || '';
  el.modelKey.value = '';
  el.modelKey.disabled = false;
  el.modelClearKey.checked = false;
  el.modelClearKey.disabled = !data.modelApiKeySet;
  state.modelApiKeySet = data.modelApiKeySet === true;
  state.modelApiKeyMasked = data.modelApiKeyMasked || '';
  setModelHint(idleModelHint(), 'idle');
  el.modelChip.title = `模型：${data.model || '未设置'} · 点击切换`;
  state.modelOptions = Array.isArray(data.modelOptions) ? data.modelOptions.slice() : [];
  renderModelOptions(data.model);
}

/** 模型面板里的状态行：保存成功/失败直接写在这里，不只闪一下顶部提示。 */
function setModelHint(text, tone) {
  el.modelHint.textContent = text || '';
  el.modelHint.className = 'model-hint' + (tone && tone !== 'idle' ? ' is-' + tone : '');
}

function idleModelHint() {
  return state.modelApiKeySet
    ? `API Key 已配置（${state.modelApiKeyMasked || '••••'}）：留空保存会保持原密钥，勾选「清除」可移除。`
    : 'API Key 未配置：填入后即可调用真实模型。';
}

/** 勾选「清除」就禁用输入框并说明后果，避免「填了 Key 又勾清除」的歧义。 */
function onModelClearKeyToggle() {
  const clear = el.modelClearKey.checked;
  el.modelKey.disabled = clear;
  if (clear) {
    el.modelKey.value = '';
    setModelHint('保存后会删除已保存的 API Key，直到重新填写前模型调用会失败。', 'warn');
  } else {
    setModelHint(idleModelHint(), 'idle');
  }
}

/** 下拉里给出候选模型，点击即切换；当前模型排在最前面。 */
function renderModelOptions(current) {
  el.modelOptions.replaceChildren();
  const names = [];
  if (current) {
    names.push(current);
  }
  for (const name of state.modelOptions) {
    if (name && !names.includes(name)) {
      names.push(name);
    }
  }
  for (const name of names) {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'model-option' + (name === current ? ' is-active' : '');
    item.setAttribute('role', 'option');
    item.setAttribute('aria-selected', String(name === current));
    item.textContent = name;
    item.addEventListener('click', () => saveModel(name));
    el.modelOptions.append(item);
  }
}

function toggleModelMenu(force) {
  const open = force === undefined ? el.modelMenu.hidden : force;
  el.modelMenu.hidden = !open;
  el.modelChip.setAttribute('aria-expanded', String(open));
  if (open) {
    el.modelKey.value = '';
    el.modelKey.disabled = false;
    el.modelClearKey.checked = false;
    setModelHint(idleModelHint(), 'idle');
    el.modelName.focus();
  }
}

async function saveModel(override) {
  // 点下拉项时直接切换；点「保存」按钮时用输入框里的值
  const model = (typeof override === 'string' ? override : el.modelName.value).trim();
  if (!model) {
    setModelHint('请选择或填写模型名。', 'error');
    showNotice('请选择或填写模型名。');
    return;
  }
  const payload = buildModelUpdate({
    model,
    endpoint: el.modelEndpoint.value,
    apiKey: el.modelKey.value,
    clearKey: el.modelClearKey.checked
  });
  const keyChanged = !payload.clearApiKey && Boolean(payload.apiKey);
  el.modelSave.disabled = true;
  setModelHint('保存中…', 'idle');
  try {
    const response = await fetch('/api/settings/model', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const data = await response.json();
    applyModelSettings(data);
    // 保存成功后面板保持打开：既能看到服务端回写的状态，也能继续改
    const message = describeModelSaved(data, { clearKey: payload.clearApiKey, keyChanged });
    setModelHint(message, 'ok');
    showNotice(message);
  } catch (error) {
    // 失败时面板不关：错误留在原来的位置，用户可以直接改端点/Key 再试
    setModelHint('保存失败：' + error.message, 'error');
    showNotice('保存模型配置失败：' + error.message);
  } finally {
    el.modelSave.disabled = false;
  }
}

/* ---------- 附件 ---------- */

const MAX_ATTACHMENTS = 5;
const MAX_ATTACHMENT_BYTES = 2 * 1024 * 1024;

/** 粘贴时优先取剪贴板里的文件（截图、复制的文件），纯文本粘贴保持原样。 */
function onPasteAttachments(event) {
  const data = event.clipboardData;
  if (!data) {
    return;
  }
  const files = [...(data.files || [])];
  if (!files.length) {
    for (const item of data.items || []) {
      if (item.kind === 'file') {
        const file = item.getAsFile();
        if (file) {
          files.push(file);
        }
      }
    }
  }
  if (!files.length) {
    return;
  }
  event.preventDefault();
  addAttachments(files);
}

async function addAttachments(files) {
  for (const file of files) {
    if (state.attachments.length >= MAX_ATTACHMENTS) {
      showNotice(`一次最多附加 ${MAX_ATTACHMENTS} 个文件。`);
      break;
    }
    const name = file.name || `paste-${Date.now()}.png`;
    if (file.size > MAX_ATTACHMENT_BYTES) {
      showNotice(`「${name}」超过 2 MB，已跳过。`);
      continue;
    }
    const mimeType = file.type || guessMime(name);
    try {
      state.attachments.push({ name, mimeType, dataBase64: await readAsDataUrl(file) });
    } catch (error) {
      showNotice('读取附件失败：' + error.message);
    }
  }
  renderAttachments();
  syncComposer();
}

function setAttachments(list) {
  state.attachments = (list || []).slice();
  renderAttachments();
  syncComposer();
}

function renderAttachments() {
  el.attachments.replaceChildren();
  el.attachments.hidden = state.attachments.length === 0;
  state.attachments.forEach((item, index) => {
    const chip = document.createElement('span');
    chip.className = 'attachment-chip';
    const name = document.createElement('span');
    name.className = 'attachment-name';
    name.textContent = item.name;
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'attachment-remove';
    remove.title = '移除附件';
    remove.textContent = '×';
    remove.addEventListener('click', () => {
      state.attachments.splice(index, 1);
      renderAttachments();
      syncComposer();
    });
    chip.append(name, remove);
    el.attachments.append(chip);
  });
}

function attachmentSummary(list) {
  const names = (list || []).map((item) => item.name).join('、');
  return names ? `（附带文件：${names}）` : '（附件）';
}

function readAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(new Error('读取文件失败'));
    reader.readAsDataURL(file);
  });
}

function guessMime(name) {
  const lower = String(name || '').toLowerCase();
  if (lower.endsWith('.png')) return 'image/png';
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
  if (lower.endsWith('.webp')) return 'image/webp';
  if (lower.endsWith('.gif')) return 'image/gif';
  if (lower.endsWith('.json')) return 'application/json';
  // 类型未知时交给服务端嗅探：文本内联，二进制落盘后只给路径
  return 'application/octet-stream';
}

/* ---------- 会话 ---------- */

async function startSession(workspace) {
  const target = workspace || state.workspace || localStorage.getItem(LAST_WORKSPACE_KEY);
  if (!target) {
    openPicker({ firstRun: true });
    return;
  }

  closeStream();
  resetView();
  setComposerEnabled(false);
  setConnection('pending', '创建会话');
  setRunState('idle', '空闲');

  try {
    const response = await fetch('/api/sessions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: '新任务', workspace: target })
    });
    if (!response.ok) {
      throw new Error(response.status === 400 ? '工作区不可用，请重新选择目录' : 'HTTP ' + response.status);
    }
    const session = await response.json();
    const title = session.title || '新任务';
    const resolvedWorkspace = session.workspace || target;
    state.sessions.unshift({
      id: session.sessionId,
      title,
      workspace: resolvedWorkspace,
      createdAt: session.createdAt,
      status: session.status
    });
    localStorage.setItem(LAST_WORKSPACE_KEY, resolvedWorkspace);
    activate(session.sessionId, title, resolvedWorkspace);
    renderSessions();
  } catch (error) {
    setConnection('error', '创建失败');
    showNotice('创建会话失败：' + error.message);
    openPicker();
  }
}

async function activate(sessionId, title, workspace) {
  closeStream();
  stopRunClock();
  state.sessionId = sessionId;
  state.sessionTitle = title || '新任务';
  state.workspace = workspace || state.workspace;
  state.pendingInputs = [];
  localStorage.setItem(LAST_SESSION_KEY, sessionId);
  el.crumbTitle.textContent = state.sessionTitle;
  el.sessionChip.textContent = sessionId.slice(0, 8);
  el.sessionChip.title = sessionId + '\n点击复制会话 ID';
  el.sessionChip.hidden = false;
  renderWorkspace();
  renderSessions();
  setConnection('pending', '连接中');
  // 先问服务端这个会话是否还在跑，再回放事件：回放不会覆盖掉「运行中」，
  // 而已经结束的运行会在回放里收到终态事件，把状态纠正回来。
  // 先按页拉取尾部历史（长会话不再一次性回放上千条事件），再从游标接上实时流
  state.historyLimit = 60;
  await loadHistory();
  if (state.sessionId !== sessionId) {
    return;
  }
  await calibrateRun(sessionId);
  if (state.sessionId !== sessionId) {
    return;
  }
  connect(state.cursor);
  setComposerEnabled(true);
  state.previewPath = null;
  el.previewPath.textContent = '未选择文件';
  el.previewBody.textContent = '在左侧文件树里点一个文件，或从「变更」里点开 diff 文件。';
  loadTree('');
}

/** 断线/刷新后校准运行状态：服务端还有活跃 run 就恢复计时与停止按钮。 */
async function calibrateRun(sessionId) {
  const wasRunning = state.running;
  state.running = false;
  state.runId = null;
  try {
    const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/runs/active`);
    if (!response.ok) {
      return;
    }
    const data = await response.json();
    if (!data.runId || state.sessionId !== sessionId) {
      // 服务端已经没有活跃 run：清掉回放残留的「正在处理」气泡，否则界面会一直显示在跑
      clearThinking();
      if (wasRunning) {
        stopRunClock();
        finishReply(false);
        setRunState('idle', '空闲');
      }
      syncComposer();
      return;
    }
    state.running = true;
    state.runId = data.runId;
    state.lastRunId = data.runId;
    startRunClock(Date.now());
    showThinking();
    setComposerEnabled(false);
    markSessionStatus('RUNNING');
  } catch (error) {
    console.warn('[active]', error);
  }
}

function renderWorkspace() {
  if (!state.workspace) {
    el.workspaceChip.hidden = true;
    return;
  }
  el.workspaceName.textContent = folderName(state.workspace);
  el.workspaceChip.title = state.workspace + '\n点击选择其他目录（会新建会话）';
  el.workspaceChip.hidden = false;
}

function folderName(path) {
  if (!path) {
    return '';
  }
  const parts = String(path).split(/[\\/]+/).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : path;
}

function renameSessionIfDefault(content) {
  if (state.sessionTitle !== '新任务') {
    return;
  }
  const title = preview(content, 22);
  state.sessionTitle = title;
  const found = state.sessions.find((item) => item.id === state.sessionId);
  if (found) {
    found.title = title;
  }
  el.crumbTitle.textContent = title;
  renderSessions();
}

function activateSession(sessionId) {
  const found = state.sessions.find((item) => item.id === sessionId);
  if (!found || sessionId === state.sessionId) {
    closeSidebar();
    return;
  }
  resetView();
  setRunState('idle', '空闲');
  activate(sessionId, found.title, found.workspace);
  closeSidebar();
}

function renderSessions() {
  el.sessionList.replaceChildren();
  for (const session of state.sessions) {
    const item = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'session-item' + (session.id === state.sessionId ? ' is-active' : '');
    button.title = session.workspace ? `${session.title}\n${session.workspace}` : session.title;
    button.addEventListener('click', () => activateSession(session.id));

    const dot = document.createElement('span');
    dot.className = 'dot' + (session.status ? ' is-' + String(session.status).toLowerCase() : '');
    const text = document.createElement('span');
    text.className = 'session-title';
    text.textContent = session.title;

    button.append(dot, text);
    if (session.workspace) {
      const folder = document.createElement('span');
      folder.className = 'session-folder';
      folder.textContent = folderName(session.workspace);
      button.append(folder);
    }
    item.append(button);
    el.sessionList.append(item);
  }
  el.sessionEmpty.hidden = state.sessions.length > 0;
  el.sessionCount.textContent = String(state.sessions.length);
}

async function copySessionId() {  if (!state.sessionId) {
    return;
  }
  try {
    await navigator.clipboard.writeText(state.sessionId);
    showNotice('已复制会话 ID：' + state.sessionId);
    setTimeout(hideNotice, 2200);
  } catch (error) {
    console.warn('[clipboard]', error);
  }
}

/* ---------- 历史分页 ---------- */

/**
 * 只渲染尾部一页历史，再挂上实时流。
 * 长会话（几百上千条事件）不再在首屏逐条建 DOM，这是刷新慢的主要原因。
 */
async function loadHistory({ keepViewport = false } = {}) {
  if (!state.sessionId) {
    return;
  }
  const sessionId = state.sessionId;
  let data;
  try {
    const response = await fetch(
      `/api/sessions/${encodeURIComponent(sessionId)}/history?limit=${state.historyLimit}`);
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    data = await response.json();
  } catch (error) {
    showNotice('读取历史失败：' + error.message);
    return;
  }
  if (state.sessionId !== sessionId) {
    return;
  }

  // 加载更早的消息时，锚点 = 视口底端距离内容末尾的距离，重排后据此还原滚动位置
  const anchor = keepViewport ? el.messages.scrollHeight - el.messages.scrollTop : 0;

  resetView();
  state.pendingInputs = [];
  state.historyHasMore = data.hasMore === true;
  state.replaying = true;
  for (const event of data.events || []) {
    handleEvent(event);
  }
  state.replaying = false;
  if (state.reply && !state.running) {
    finishReply(false);
  }
  await loadPlan();
  renderHistoryMore();
  if (keepViewport) {
    el.messages.scrollTop = Math.max(0, el.messages.scrollHeight - anchor);
  } else {
    scrollThread();
  }
}

function renderHistoryMore() {
  const existing = el.messages.querySelector('.history-more');
  if (existing) {
    existing.remove();
  }
  if (!state.historyHasMore) {
    return;
  }
  const more = document.createElement('button');
  more.type = 'button';
  more.className = 'history-more';
  more.textContent = '加载更早的消息';
  more.addEventListener('click', async () => {
    more.disabled = true;
    more.textContent = '加载中…';
    state.historyLimit += 80;
    await loadHistory({ keepViewport: true });
  });
  el.messages.prepend(more);
}

/* ---------- 事件流 ---------- */

function connect(cursor) {
  const from = Number.isFinite(cursor) ? cursor : 0;
  const source = new EventSource(`/api/sessions/${encodeURIComponent(state.sessionId)}/events?after=${from}`);
  state.source = source;
  for (const type of EVENT_TYPES) {
    source.addEventListener(type, (message) => {
      try {
        handleEvent(JSON.parse(message.data));
      } catch (error) {
        console.warn('[sse] 事件解析失败', error);
      }
    });
  }
  const session = state.sessionId;
  source.onopen = () => setConnection('ok', '已连接');
  source.onerror = () => {
    // 接管自动重连：带着已渲染的游标续订，避免从 0 重新回放
    setConnection('error', '重连中');
    closeStream();
    setTimeout(() => {
      if (state.sessionId === session) {
        connect(state.cursor);
      }
    }, 1500);
  };
}

function reconnect() {
  if (!state.sessionId) {
    return;
  }
  closeStream();
  setConnection('pending', '重连中');
  connect(state.cursor);
}

function closeStream() {
  if (state.source) {
    state.source.close();
    state.source = null;
  }
}

function handleEvent(event) {
  // 同一事件可能被旧连接重复推送，按 sequence 去重，避免历史消息叠加渲染
  if (typeof event.sequence === 'number') {
    if (event.sequence <= state.cursor) {
      return;
    }
    state.cursor = event.sequence;
  }
  state.lastEventAt = Date.now();
  hideEmptyState();

  const stick = state.replaying ? false : isNearBottom(el.messages);
  const stickTimeline = state.replaying ? false : isNearBottom(el.timeline);

  switch (event.type) {
    case 'run.started': {
      state.running = true;
      state.runId = event.runId || state.runId;
      state.turnRuns = event.runId ? [event.runId] : [];
      startRunClock(Date.now());
      markSessionStatus('RUNNING');
      syncComposer();
      clearThinking();
      const input = event.payload.input || '';
      if (state.pendingInputs[0] === input) {
        state.pendingInputs.shift();
      } else if (input) {
        appendUserMessage(input);
      }
      showThinking();
      record(event, { kind: 'run', tone: 'run', status: '开始', title: '运行开始', detail: input });
      break;
    }

    case 'assistant.delta':
      appendDelta(event.payload.text);
      recordDelta(event, event.payload.text);
      break;

    case 'tool.started': {
      clearThinking();
      const row = addToolRow(event.payload.callId, event.payload.name, event.payload.summary);
      row.setStatus('run', '…');
      state.toolRows.set(event.payload.callId, row);
      showThinking();
      record(event, {
        kind: 'tool',
        tone: 'tool',
        status: '运行中',
        title: `${event.payload.name} · ${event.payload.summary}`,
        detail: null
      });
      break;
    }

    case 'tool.completed': {
      const failed = String(event.payload.resultSummary || '').startsWith('ERROR');
      const row = state.toolRows.get(event.payload.callId);
      if (row) {
        state.toolRows.delete(event.payload.callId);
        row.setStatus(failed ? 'error' : 'ok', failed ? '✕' : '✓');
        row.setMeta(`${event.payload.latencyMs} ms`);
        row.setBody(event.payload.resultSummary);
      }
      record(event, {
        kind: 'tool',
        tone: failed ? 'error' : 'ok',
        status: failed ? '失败' : '完成',
        title: event.payload.callId,
        detail: event.payload.resultSummary,
        foot: `${event.payload.latencyMs} ms`
      });
      break;
    }

    case 'approval.required': {
      clearThinking();
      const row = addToolRow(event.payload.callId, event.payload.name, event.payload.argsSummary);
      row.setStatus('wait', '!');
      state.toolRows.set(event.payload.callId, row);
      state.pendingApprovals.set(event.payload.callId, {
        name: event.payload.name,
        args: event.payload.argsSummary,
        row
      });
      renderApprovalDock();
      record(event, {
        kind: 'approval',
        tone: 'warn',
        status: '待审批',
        title: `${event.payload.name} · ${event.payload.argsSummary}`,
        detail: '需要你批准后才能执行',
        actions: true,
        callId: event.payload.callId
      });
      break;
    }

    case 'approval.resolved': {
      const approved = event.payload.decision === 'APPROVED';
      const pending = state.pendingApprovals.get(event.payload.callId);
      if (pending) {
        state.pendingApprovals.delete(event.payload.callId);
        pending.row.setStatus(approved ? 'run' : 'error', approved ? '…' : '✕');
        pending.row.setMeta(approved ? '已批准' : '已拒绝');
        if (!approved) {
          state.toolRows.delete(event.payload.callId);
        }
      }
      renderApprovalDock();
      record(event, {
        kind: 'approval',
        tone: approved ? 'ok' : 'error',
        status: approved ? '已批准' : '已拒绝',
        title: event.payload.callId,
        detail: null
      });
      break;
    }

    case 'run.completed': {
      state.running = false;
      stopRunClock();
      markSessionStatus('COMPLETED');
      state.lastRunId = event.runId || state.runId || state.lastRunId;
      state.runId = null;
      state.changedFiles = event.payload.changedFiles || [];
      // 「运行结束」≠「任务做完」：计划还有 pending 项时如实标成未完成
      const openSteps = Array.isArray(event.payload.openSteps) ? event.payload.openSteps : [];
      const planTotal = Number(event.payload.planTotal || 0);
      const planDone = Number(event.payload.planDone || 0);
      const planOpen = openSteps.length > 0;
      const progress = planTotal ? ` · 计划 ${planDone}/${planTotal}` : '';
      setRunState(planOpen ? 'warn' : 'done', (planOpen ? '本轮结束' : '已完成') + progress);
      setComposerEnabled(true);
      clearThinking();
      finishReply(false);
      const extras = [];
      if (event.payload.changedFiles && event.payload.changedFiles.length) {
        extras.push('变更：' + event.payload.changedFiles.join('、'));
      }
      if (event.payload.checks && event.payload.checks.length) {
        extras.push('检查：' + event.payload.checks.join('、'));
      }
      if (planOpen) {
        extras.push('剩余：' + openSteps.join('、'));
      }
      record(event, {
        kind: 'run',
        tone: planOpen ? 'warn' : 'ok',
        status: planOpen ? '未完成' : '完成',
        title: (planOpen ? '本轮结束' : '运行完成') + progress,
        detail: event.payload.summary,
        foot: extras.join(' · '),
        resumable: planOpen
      });
      appendTurnResult({
        tone: planOpen ? 'warn' : 'ok',
        title: (planOpen ? `本轮结束 · 还有 ${openSteps.length} 项没做` : '本轮已完成') + progress,
        openSteps,
        changedFiles: event.payload.changedFiles || [],
        // 自动继续会换 runId，同一轮里写过的文件分散在多个快照中，合并后再取
        runIds: state.turnRuns.length ? state.turnRuns.slice()
          : (event.runId ? [event.runId] : [state.lastRunId]),
        resumable: planOpen
      });
      refreshChanges();
      break;
    }

    case 'context.compacted': {
      appendCompaction(event.payload);
      break;
    }
    case 'plan.updated': {
      renderPlan(event.payload);
      break;
    }
    case 'run.steered': {
      showNotice('已追加指令：' + (event.payload.input || ''));
      setRunState('running', '已收到补充指令');
      break;
    }
    case 'run.continued': {
      if (event.runId && !state.turnRuns.includes(event.runId)) {
        state.turnRuns.push(event.runId);
      }
      const attempt = Number(event.payload.attempt || 1);
      const max = Number(event.payload.maxAttempts || 0);
      showNotice(`自动继续 ${attempt}/${max}：${event.payload.reason || ''}`);
      setRunState('running', '自动继续中');
      record(event, {
        kind: 'run',
        tone: 'ok',
        status: max ? `${attempt}/${max}` : '继续',
        title: '计划未完成，自动继续',
        detail: event.payload.reason
      });
      break;
    }
    case 'subagent.started': {
      appendSubagent(event.payload);
      break;
    }
    case 'subagent.finished': {
      finishSubagent(event.payload);
      break;
    }
    case 'run.failed': {
      const code = String(event.payload.code || '');
      const cancelled = code === 'CANCELLED';
      // 步数触顶不是失败：历史对话已保留，继续跑即可
      const paused = code === 'MAX_STEPS';
      // 服务重启导致的中断：不是任务本身失败，同样可以继续
      const interrupted = code === 'INTERRUPTED';
      const resumable = paused || interrupted;
      state.running = false;
      stopRunClock();
      markSessionStatus(cancelled ? 'CANCELLED' : paused ? 'PAUSED' : interrupted ? 'INTERRUPTED' : 'FAILED');
      state.lastRunId = event.runId || state.runId || state.lastRunId;
      state.runId = null;
      setRunState(resumable ? 'warn' : cancelled ? 'idle' : 'error',
        paused ? '已暂停' : interrupted ? '已中断' : cancelled ? '已取消' : '失败');
      setComposerEnabled(true);
      clearThinking();
      finishReply(!resumable);
      record(event, {
        kind: 'run',
        tone: resumable || cancelled ? 'warn' : 'error',
        status: paused ? '可继续' : interrupted ? '已中断' : cancelled ? '已取消' : (event.payload.retryable ? '可重试' : '失败'),
        title: paused ? `本轮已暂停 · ${code}` : interrupted ? `运行已中断 · ${code}` : `运行${cancelled ? '取消' : '失败'} · ${code}`,
        detail: event.payload.message,
        resumable
      });
      // 模型错误：把原始报错翻译成可行动的提示（401 去填 Key、404 查端点…）
      const modelError = code === 'MODEL_ERROR' ? classifyModelError(event.payload.message) : null;
      if (modelError && modelError.hint) {
        showNotice(modelError.hint);
      }
      appendTurnResult({
        tone: cancelled || resumable ? 'warn' : 'error',
        title: paused ? '本轮已暂停' : interrupted ? '运行已中断' : cancelled ? '本轮已取消' : '运行失败',
        detail: event.payload.message,
        hint: modelError ? modelError.hint : '',
        actionLabel: modelError && modelError.action === 'model-settings' ? '打开模型设置' : '',
        onAction: (clickEvent) => {
          // 阻止冒泡：document 上的「点外面就收起」会立刻把刚打开的面板关掉
          clickEvent?.stopPropagation();
          toggleModelMenu(true);
          el.modelKey.focus();
        },
        resumable
      });
      break;
    }
  }

  if (stick) {
    scrollThread();
  }
  if (stickTimeline) {
    el.timeline.scrollTop = el.timeline.scrollHeight;
  }
  if (state.filter !== 'all') {
    applyFilter();
  }
}

/* ---------- 对话渲染 ---------- */

/** 上下文压缩：在对话流里留一条可展开的交接摘要，让「记忆断层」有迹可查。 */
function appendCompaction(payload) {
  const card = document.createElement('details');
  card.className = 'ctx-card';

  const head = document.createElement('summary');
  head.className = 'ctx-head';
  head.textContent = `上下文已压缩 · 折叠 ${payload.coveredMessages || 0} 条较早消息 · `
    + `${fmtChars(payload.charactersBefore)} → ${fmtChars(payload.charactersAfter)}`;

  const body = document.createElement('pre');
  body.className = 'ctx-body';
  body.textContent = payload.summary || '（摘要为空）';

  card.append(head, body);
  el.messages.append(card);
  hideEmptyState();
}

/**
 * 每轮结束在对话流里留一条结论：干完了没有、还剩什么、改了哪些文件。
 * 没有这一条时，用户只能凭最后一条工具卡片猜本轮是结束了还是卡住了。
 */
function appendTurnResult({ tone, title, detail, openSteps, changedFiles, runIds, hint, actionLabel, onAction, resumable }) {
  const card = document.createElement('article');
  card.className = 'turn-card is-' + (tone || 'ok');

  const head = document.createElement('div');
  head.className = 'turn-head';
  const mark = document.createElement('span');
  mark.className = 'turn-mark';
  mark.textContent = tone === 'ok' ? '✓' : tone === 'warn' ? '!' : '×';
  const label = document.createElement('strong');
  label.textContent = title || '本轮结束';
  head.append(mark, label);
  card.append(head);

  if (detail) {
    const note = document.createElement('p');
    note.className = 'turn-note';
    note.textContent = detail;
    card.append(note);
  }

  if (openSteps && openSteps.length) {
    const list = document.createElement('ul');
    list.className = 'turn-open';
    for (const step of openSteps) {
      const item = document.createElement('li');
      item.textContent = step;
      list.append(item);
    }
    card.append(list);
  }

  if (hint) {
    const tip = document.createElement('p');
    tip.className = 'turn-hint';
    tip.textContent = hint;
    card.append(tip);
  }

  if (changedFiles && changedFiles.length) {
    card.append(runChangeDetails(runIds, changedFiles));
  }

  const actions = [];
  if (resumable) {
    actions.push(button('继续执行', 'btn btn-primary', () => continueRun(card)));
  }
  if (actionLabel && typeof onAction === 'function') {
    actions.push(button(actionLabel, 'btn btn-sm', onAction));
  }
  if (actions.length) {
    const row = document.createElement('div');
    row.className = 'turn-actions';
    row.append(...actions);
    card.append(row);
  }

  el.messages.append(card);
  hideEmptyState();
  scrollThread();
}

/**
 * Codex 风格改动卡：默认收起，摘要只报「改了 N 个文件 +x −y」，
 * 展开后按文件列状态与增删行数，再点单个文件才拉取并渲染 diff。
 */
function runChangeDetails(runIds, changedFiles) {
  const paths = changedFiles.map(normalizePath);
  const details = document.createElement('details');
  details.className = 'turn-changes';

  const head = document.createElement('summary');
  head.className = 'turn-changes-head';
  const label = document.createElement('span');
  label.textContent = `改动 ${paths.length} 个文件`;
  const stat = document.createElement('span');
  stat.className = 'turn-changes-stat';
  head.append(label, stat);

  const body = document.createElement('div');
  body.className = 'turn-changes-body';
  details.append(head, body);

  let started = false;
  const start = () => {
    if (started) {
      return;
    }
    started = true;
    loadRunChangeDetail(runIds, paths, stat, body);
  };
  details.addEventListener('toggle', () => {
    if (details.open) {
      start();
    }
  });
  // 刚跑完的这一轮立刻取一次统计，摘要行才有 +x −y；回放历史时按需加载，避免一次打几十个请求
  if (!state.replaying) {
    start();
  }
  return details;
}

async function loadRunChangeDetail(runIds, paths, stat, body) {
  const ids = (runIds || []).filter(Boolean);
  body.replaceChildren(changeHint('读取改动中…'));
  if (!ids.length) {
    stat.textContent = '';
    body.replaceChildren(runChangeFallback(paths));
    return;
  }
  try {
    const reports = await Promise.all(ids.map(async (runId) => {
      const response = await fetch(`/api/runs/${encodeURIComponent(runId)}/changes`);
      if (!response.ok) {
        throw new Error('HTTP ' + response.status);
      }
      return response.json();
    }));
    renderRunChangeDetail(mergeRunReports(reports), paths, stat, body);
  } catch (error) {
    stat.textContent = '';
    body.replaceChildren(
      changeHint('读取改动失败：' + error.message),
      runChangeFallback(paths)
    );
  }
}

/**
 * 合并一轮里多个 run 的改动：同一文件以最后一次运行的统计为准
 * （最后一次的 diff 本身就是相对 HEAD 的完整改动，直接相加会重复计数）。
 */
function mergeRunReports(reports) {
  const files = new Map();
  let gitRepository = false;
  let truncated = false;
  for (const report of reports || []) {
    gitRepository = gitRepository || report.gitRepository === true;
    truncated = truncated || report.truncated === true;
    for (const file of report.files || []) {
      files.set(normalizePath(file.path), file);
    }
  }
  const list = [...files.values()];
  return {
    gitRepository,
    truncated,
    files: list,
    added: list.reduce((sum, file) => sum + Number(file.added || 0), 0),
    removed: list.reduce((sum, file) => sum + Number(file.removed || 0), 0)
  };
}

function renderRunChangeDetail(report, paths, stat, body) {
  const files = Array.isArray(report.files) ? report.files : [];
  const added = Number(report.added || 0);
  const removed = Number(report.removed || 0);

  stat.replaceChildren();
  if (added || removed) {
    const add = document.createElement('span');
    add.className = 'add';
    add.textContent = '+' + added;
    const del = document.createElement('span');
    del.className = 'del';
    del.textContent = '−' + removed;
    stat.append(add, document.createTextNode(' '), del);
  }

  body.replaceChildren();
  if (!files.length) {
    body.append(runChangeFallback(paths));
    return;
  }
  if (!report.gitRepository) {
    body.append(changeHint('工作区不是 git 仓库：只能列出本轮写过的文件，没有 diff。'));
  }
  for (const file of files) {
    body.append(runChangeRow(file, report.gitRepository));
  }
  if (report.truncated) {
    body.append(changeHint('改动过多，diff 已截断。'));
  }
  // 本轮清单只覆盖 Agent 的写文件工具；命令行改出来的文件要到「改动」面板看
  const actions = document.createElement('div');
  actions.className = 'turn-changes-actions';
  actions.append(button('查看全部改动', 'btn btn-sm', () => selectTab('changes')));
  body.append(actions);
}

/** 后端没有快照（老运行或快照已清理）时的兜底：只列文件名。 */
function runChangeFallback(paths) {
  const node = document.createElement('div');
  node.className = 'run-change-flat';
  node.textContent = paths.length ? paths.join('、') : '本次运行没有改动任何文件。';
  return node;
}

function runChangeRow(file, gitRepository) {
  const row = document.createElement('details');
  row.className = 'diff-file run-change-row';

  const summary = document.createElement('summary');
  const status = document.createElement('span');
  status.className = 'change-status is-' + statusKind(file.status || 'M');
  status.textContent = file.status || 'M';
  const path = document.createElement('span');
  path.className = 'diff-path';
  path.textContent = file.path;
  path.title = file.path;
  summary.append(status, path);

  if (file.added || file.removed) {
    const stat = document.createElement('span');
    stat.className = 'diff-stat';
    const add = document.createElement('span');
    add.className = 'add';
    add.textContent = '+' + Number(file.added || 0);
    const del = document.createElement('span');
    del.className = 'del';
    del.textContent = ' −' + Number(file.removed || 0);
    stat.append(add, del);
    summary.append(stat);
  }
  row.append(summary);

  if (!file.diff) {
    path.title = file.path + (gitRepository ? '（无 diff）' : '（非 git 仓库）');
    return row;
  }

  row.addEventListener('toggle', () => {
    if (!row.open || row.dataset.rendered) {
      return;
    }
    row.dataset.rendered = '1';
    const body = document.createElement('div');
    body.className = 'diff-lines';
    const parsed = parseDiff(file.diff);
    if (!parsed.length) {
      body.append(changeHint('diff 为空。'));
    } else {
      for (const line of parsed[0].lines) {
        body.append(diffLineRow(line));
      }
    }
    row.append(body);
  });
  return row;
}

function fmtChars(value) {
  const n = Number(value || 0);
  return n >= 1000 ? `${(n / 1000).toFixed(1)}k 字符` : `${n} 字符`;
}

/** 子代理：一条折叠卡片，展开可看它回传的结论。 */
function appendSubagent(payload) {
  const card = document.createElement('details');
  card.className = 'subagent-card';
  card.dataset.agentId = payload.agentId || '';

  const head = document.createElement('summary');
  head.className = 'subagent-head';
  head.textContent = `子任务：${payload.task || ''}`;

  const body = document.createElement('pre');
  body.className = 'subagent-body';
  body.textContent = '执行中…';

  card.append(head, body);
  el.messages.append(card);
  hideEmptyState();
}

function finishSubagent(payload) {
  const id = payload.agentId || '';
  const card = el.messages.querySelector(`.subagent-card[data-agent-id="${CSS.escape(id)}"]`);
  if (!card) {
    return;
  }
  card.classList.toggle('is-failed', payload.ok === false);
  const body = card.querySelector('.subagent-body');
  if (body) {
    body.textContent = payload.summary || (payload.ok === false ? '子代理失败' : '完成');
  }
}

/** 任务计划：整表重绘，只保留一个卡片并跟在最新消息后面。 */
function renderPlan(payload) {
  const items = Array.isArray(payload && payload.plan) ? payload.plan : [];
  let card = el.messages.querySelector('.plan-card');
  if (!card) {
    card = document.createElement('aside');
    card.className = 'plan-card';
    const title = document.createElement('div');
    title.className = 'plan-title';
    title.textContent = '任务计划';
    const list = document.createElement('ol');
    list.className = 'plan-list';
    card.append(title, list);
  }
  if (!items.length) {
    card.remove();
    return;
  }

  const list = card.querySelector('.plan-list');
  list.replaceChildren();
  for (const item of items) {
    const li = document.createElement('li');
    li.className = `plan-item is-${item.status || 'pending'}`;
    li.textContent = item.step || '';
    list.append(li);
  }
  // 移到末尾，让计划跟着最新进度走
  el.messages.append(card);
  hideEmptyState();
}

/** 刷新后从服务端恢复计划清单（事件已重放的那份会覆盖这一份）。 */
async function loadPlan() {
  if (!state.sessionId) {
    return;
  }
  try {
    const response = await fetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/plan`);
    if (!response.ok) {
      return;
    }
    const data = await response.json();
    if (Array.isArray(data.plan) && data.plan.length) {
      renderPlan(data);
    }
  } catch (error) {
    // 计划恢复失败不影响对话
  }
}

function showThinking() {
  clearThinking();
  const wrapper = document.createElement('article');
  wrapper.className = 'msg msg-assistant';

  const who = document.createElement('div');
  who.className = 'who';
  const mark = document.createElement('span');
  mark.className = 'who-mark';
  mark.textContent = 'P';
  who.append(mark, document.createTextNode('Pilot · 正在处理'));

  const bubble = document.createElement('div');
  bubble.className = 'bubble typing';
  for (let i = 0; i < 3; i++) {
    bubble.append(document.createElement('span'));
  }

  wrapper.append(who, bubble);
  el.messages.append(wrapper);
  state.thinking = wrapper;
  scrollThread();
}

function clearThinking() {
  if (state.thinking) {
    state.thinking.remove();
    state.thinking = null;
  }
}

function startReply() {
  const wrapper = document.createElement('article');
  wrapper.className = 'msg msg-assistant';

  const who = document.createElement('div');
  who.className = 'who';
  const mark = document.createElement('span');
  mark.className = 'who-mark';
  mark.textContent = 'P';
  who.append(mark, document.createTextNode('Pilot'));

  const bubble = document.createElement('div');
  bubble.className = 'bubble';

  wrapper.append(who, bubble);
  el.messages.append(wrapper);
  state.reply = bubble;
  state.replyText = '';
}

function appendDelta(text) {
  if (!text) {
    return;
  }
  clearThinking();
  if (!state.reply) {
    startReply();
  }
  state.replyText += text;
  // 文本同时挂在气泡自身上：run.completed 可能在同一帧到达并把 state.reply 置空，
  // 若只依赖 state.replyText，最后一帧的 rAF 会因为 state.reply === null 直接放弃渲染，
  // 于是界面上只剩一个空的「Pilot」气泡。
  state.reply.dataset.text = state.replyText;
  if (!state.replaying) {
    scheduleReplyRender();
  }
}

/** 流式时每帧最多重绘一次，避免长回答的 O(n²) 重排。 */
function scheduleReplyRender() {
  if (state.renderQueued) {
    return;
  }
  state.renderQueued = true;
  const bubble = state.reply;
  requestAnimationFrame(() => {
    state.renderQueued = false;
    if (!bubble) {
      return;
    }
    const text = bubble.dataset.text || '';
    if (!text) {
      return;
    }
    const stick = isNearBottom(el.messages);
    bubble.replaceChildren(renderMarkdown(text));
    if (stick) {
      scrollThread();
    }
  });
}

function finishReply(failed) {
  clearThinking();
  const bubble = state.reply;
  if (!bubble) {
    return;
  }
  // 收尾时同步渲染一次：即使最后一帧的 requestAnimationFrame 还没执行也不会丢内容
  const text = state.replyText || bubble.dataset.text || '';
  if (text) {
    bubble.replaceChildren(renderMarkdown(text));
  } else {
    bubble.classList.add('is-muted');
    bubble.textContent = failed ? '（本轮没有返回文本）' : '（本轮只调用了工具，没有文本输出）';
  }
  state.reply = null;
  state.replyText = '';
}

function appendUserMessage(text) {
  const wrapper = document.createElement('article');
  wrapper.className = 'msg msg-user';

  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.textContent = text;

  wrapper.append(bubble);
  el.messages.append(wrapper);
  hideEmptyState();
}

/** 工具类型 → 单字符图标，让一屏内能快速扫出「读 / 写 / 命令」。 */
const TOOL_ICONS = {
  read_file: 'R',
  list_dir: 'D',
  search_text: 'S',
  edit_file: 'E',
  create_file: '+',
  run_command: '$'
};

function addToolRow(callId, name, args) {
  const existing = state.toolRows.get(callId);
  if (existing) {
    return existing;
  }

  const row = document.createElement('div');
  row.className = 'tool-row';

  const button = document.createElement('button');
  button.type = 'button';

  const status = document.createElement('span');
  status.className = 'tool-status';
  status.textContent = '…';

  const icon = document.createElement('span');
  icon.className = 'tool-icon';
  icon.textContent = TOOL_ICONS[name] || '·';

  const nameEl = document.createElement('span');
  nameEl.className = 'tool-name';
  nameEl.textContent = name || 'tool';

  const argsEl = document.createElement('span');
  argsEl.className = 'tool-args';
  argsEl.textContent = args || '';

  const metaEl = document.createElement('span');
  metaEl.className = 'tool-meta';
  metaEl.textContent = '';

  button.append(status, icon, nameEl, argsEl, metaEl);

  const body = document.createElement('div');
  body.className = 'tool-body';
  const pre = document.createElement('pre');
  body.append(pre);

  button.addEventListener('click', () => row.classList.toggle('is-open'));

  row.append(button, body);
  el.messages.append(row);
  if (!state.replaying) {
    scrollThread();
  }

  const handle = {
    element: row,
    setStatus(kind, glyph) {
      status.className = 'tool-status is-' + kind;
      status.textContent = glyph;
    },
    setMeta(value) {
      metaEl.textContent = value || '';
    },
    setBody(value) {
      pre.textContent = value || '';
      if (value) {
        metaEl.textContent = metaEl.textContent || '详情';
      }
    }
  };
  state.toolRows.set(callId, handle);
  return handle;
}

/* ---------- 审批 ---------- */

function renderApprovalDock() {
  const entries = [...state.pendingApprovals.entries()];
  if (!entries.length) {
    el.approvalDock.hidden = true;
    el.approvalDock.replaceChildren();
    return;
  }

  const [callId, pending] = entries[0];
  el.approvalDock.hidden = false;
  el.approvalDock.replaceChildren();

  const head = document.createElement('p');
  head.className = 'approval-head';
  head.append(document.createTextNode('需要批准 · '));
  const toolName = document.createElement('span');
  toolName.className = 'tool-name';
  toolName.textContent = pending.name;
  head.append(toolName);
  if (entries.length > 1) {
    head.append(document.createTextNode(` 等 ${entries.length} 项`));
  }

  const args = document.createElement('p');
  args.className = 'approval-args';
  args.textContent = pending.args || '';

  const actions = document.createElement('div');
  actions.className = 'approval-actions';
  const allow = button('批准并执行', 'btn btn-primary', () => resolveApproval(callId, true));
  const deny = button('拒绝', 'btn btn-danger', () => resolveApproval(callId, false));
  actions.append(allow, deny);

  el.approvalDock.append(head, args, actions);
}

async function resolveApproval(callId, approved, scope) {
  const buttons = scope
    ? [...scope.querySelectorAll('button')]
    : [...el.approvalDock.querySelectorAll('button')];
  buttons.forEach((item) => { item.disabled = true; });

  try {
    const response = await fetch(`/api/approvals/${encodeURIComponent(callId)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ approved })
    });
    if (!response.ok) {
      throw new Error(response.status === 404 ? '审批已超时或已处理' : 'HTTP ' + response.status);
    }
  } catch (error) {
    buttons.forEach((item) => { item.disabled = false; });
    showNotice('提交审批失败：' + error.message);
  }
}

/* ---------- 活动面板 ---------- */

const MAX_TIMELINE_EVENTS = 300;

/** 同一次运行里连续的 assistant.delta 合并到一张卡片，避免长任务把面板刷爆。 */
function recordDelta(event, text) {
  if (state.streamCard && state.streamCard.isConnected) {
    state.streamText += text;
    const detail = state.streamCard.querySelector('.event-detail');
    if (detail) {
      detail.textContent = preview(state.streamText, 160);
    }
    return;
  }
  state.streamText = text;
  state.streamCard = record(event, {
    kind: 'run',
    tone: 'text',
    status: '增量',
    title: null,
    detail: preview(text, 160)
  });
}

function record(event, { kind, tone, status, title, detail, foot, actions, callId, resumable }) {
  if (event.type !== 'assistant.delta') {
    state.streamCard = null;
    state.streamText = '';
  }
  state.activityCount += 1;

  const item = document.createElement('li');
  item.className = 'event tone-' + tone;
  item.dataset.kind = kind;

  const head = document.createElement('div');
  head.className = 'event-head';

  const seq = document.createElement('span');
  seq.className = 'event-seq';
  seq.textContent = '#' + event.sequence;

  const type = document.createElement('span');
  type.className = 'event-type';
  type.textContent = event.type;

  const statusEl = document.createElement('span');
  statusEl.className = 'event-status';
  statusEl.textContent = status;

  const time = document.createElement('span');
  time.className = 'event-time';
  time.textContent = formatTime(event.timestamp);

  head.append(seq, type, statusEl, time);
  item.append(head);

  if (title) {
    const titleEl = document.createElement('p');
    titleEl.className = 'event-title';
    titleEl.textContent = title;
    item.append(titleEl);
  }
  if (detail) {
    const detailEl = document.createElement('p');
    detailEl.className = 'event-detail';
    detailEl.textContent = preview(detail, 240);
    item.append(detailEl);
  }
  if (foot) {
    const footEl = document.createElement('p');
    footEl.className = 'event-foot';
    footEl.textContent = foot;
    item.append(footEl);
  }
  if (actions && callId) {
    const box = document.createElement('div');
    box.className = 'approval-actions';
    box.append(
      button('批准', 'btn btn-primary', () => resolveApproval(callId, true, box)),
      button('拒绝', 'btn btn-danger', () => resolveApproval(callId, false, box))
    );
    item.append(box);
  }
  if (resumable) {
    const box = document.createElement('div');
    box.className = 'approval-actions';
    box.append(button('继续执行', 'btn btn-primary', () => continueRun(box)));
    item.append(box);
  }

  el.timeline.append(item);
  while (el.timeline.children.length > MAX_TIMELINE_EVENTS) {
    el.timeline.firstElementChild.remove();
  }
  el.timelineEmpty.hidden = true;
  el.toggleActivity.title = `活动面板 · ${state.activityCount} 条事件 (Ctrl B)`;

  if (state.filter === 'all') {
    item.hidden = false;
  } else {
    item.hidden = item.dataset.kind !== state.filter;
  }
  return item;
}

function onFilterClick(event) {
  const target = event.target.closest('.filter');
  if (!target) {
    return;
  }
  state.filter = target.dataset.filter;
  for (const item of el.filters.querySelectorAll('.filter')) {
    const on = item === target;
    item.classList.toggle('is-on', on);
    item.setAttribute('aria-selected', String(on));
  }
  applyFilter();
}

function applyFilter() {
  for (const item of el.timeline.children) {
    item.hidden = state.filter !== 'all' && item.dataset.kind !== state.filter;
  }
}

function toggleActivity() {
  let visible;
  if (isNarrow()) {
    visible = el.activity.classList.toggle('is-open');
  } else {
    visible = !el.activity.classList.toggle('is-hidden');
  }
  el.toggleActivity.setAttribute('aria-pressed', String(visible));
  el.app.classList.toggle('inspector-collapsed', !visible);
  syncScrim();
  if (visible) {
  el.timeline.scrollTop = el.timeline.scrollHeight;
  }
  }

function isNarrow() {
  return window.matchMedia('(max-width: 960px)').matches;
}

/* ---------- 活动 / 变更 面板 ---------- */

function onTabClick(event) {
  const tab = event.target.closest('.tab');
  if (!tab) {
    return;
  }
  selectTab(tab.dataset.tab);
}

function selectTab(tab) {
  state.tab = tab;
  for (const item of el.panelTabs.querySelectorAll('.tab')) {
    const on = item.dataset.tab === tab;
    item.classList.toggle('is-on', on);
    item.setAttribute('aria-selected', String(on));
  }
  el.panelActivity.hidden = tab !== 'activity';
  el.panelChanges.hidden = tab !== 'changes';
  el.panelPreview.hidden = tab !== 'preview';
  el.filters.hidden = tab !== 'activity';
  if (tab === 'changes') {
    loadChanges();
  }
}

function resetChanges() {
  el.changeCount.hidden = true;
  el.changeCount.textContent = '0';
  el.changesState.textContent = '尚未加载';
  el.changesState.className = 'changes-state';
  el.changeFiles.replaceChildren();
  el.changeDiff.replaceChildren();
  el.runChanges.replaceChildren();
  el.runChanges.hidden = true;
}

function refreshChanges() {
  if (!state.sessionId) {
    return;
  }
  loadChanges({ silent: state.tab !== 'changes' });
}

async function loadChanges(options = {}) {
  if (!state.sessionId) {
    return;
  }
  const silent = options.silent === true;
  if (!silent) {
    el.changesState.textContent = '读取中…';
    el.changesState.className = 'changes-state';
  }
  try {
    const response = await fetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/diff`);
    if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    }
    renderChanges(await response.json(), silent);
  } catch (error) {
    if (!silent) {
      el.changesState.textContent = '读取失败：' + error.message;
      el.changesState.className = 'changes-state is-error';
    }
  }
}

function renderChanges(report, silent) {
  const files = report.files || [];
  el.changeCount.textContent = String(files.length);
  el.changeCount.hidden = files.length === 0;
  if (silent) {
    return;
  }
  renderRunChanges();

  el.changeFiles.replaceChildren();
  el.changeDiff.replaceChildren();
  refreshChangeMarkers(files);

  if (!report.gitRepository) {
    el.changesState.textContent = '当前工作区不是 git 仓库';
    el.changesState.className = 'changes-state';
    el.changeDiff.append(changeHint('没有 git 仓库，无法生成 diff。在该目录执行 git init 后即可审阅改动。'));
    return;
  }

  el.changesState.textContent = files.length ? `${files.length} 个文件有改动` : '工作区干净';
  el.changesState.className = 'changes-state';

  for (const file of files) {
    const li = document.createElement('li');
    li.className = 'change-file';
    const status = document.createElement('span');
    status.className = 'change-status is-' + statusKind(file.status);
    status.textContent = file.status;
    const path = document.createElement('span');
    path.className = 'change-path';
    path.textContent = file.path;
    path.title = file.path + '（点击预览）';
    li.append(status, path);
    li.addEventListener('click', () => openPreview(file.path));
    el.changeFiles.append(li);
  }

  if (!report.diff) {
    el.changeDiff.append(changeHint(files.length
      ? '这些改动没有可显示的 diff（新增文件或仅暂存）。'
      : '没有未暂存的改动。'));
    return;
  }
  renderDiff(report.diff);
  if (report.truncated) {
    el.changeDiff.append(changeHint('diff 过大，已截断。'));
  }
}

/** 只展示本次运行写过的文件，避免和仓库里的历史改动混在一起。 */
function renderRunChanges() {
  const files = state.changedFiles || [];
  el.runChanges.replaceChildren();
  if (!files.length) {
    el.runChanges.hidden = true;
    return;
  }
  el.runChanges.hidden = false;

  const label = document.createElement('span');
  label.className = 'run-changes-label';
  label.textContent = `本次运行改动 ${files.length} 个`;
  el.runChanges.append(label);

  files.slice(0, 6).forEach((path) => {
    const chip = document.createElement('span');
    chip.className = 'run-chip';
    chip.textContent = folderName(path);
    chip.title = path;
    el.runChanges.append(chip);
  });
  if (files.length > 6) {
    const more = document.createElement('span');
    more.className = 'run-changes-label';
    more.textContent = `+${files.length - 6}`;
    el.runChanges.append(more);
  }
}

function statusKind(status) {
  if (status === '??' || status === 'A') {
    return 'added';
  }
  if (status === 'D') {
    return 'deleted';
  }
  return 'modified';
}

function renderDiff(text) {
  const files = parseDiff(text);
  if (!files.length) {
    el.changeDiff.append(changeHint('diff 为空。'));
    return;
  }
  const changed = new Set((state.changedFiles || []).map(normalizePath));
  for (const file of files) {
    const details = document.createElement('details');
    details.className = 'diff-file';
    if (changed.has(normalizePath(file.path)) || files.length <= 3) {
      details.open = true;
    }
    const summary = document.createElement('summary');
    const path = document.createElement('span');
    path.className = 'diff-path';
    path.textContent = file.path;
    path.title = file.path;
    const stat = document.createElement('span');
    stat.className = 'diff-stat';
    const add = document.createElement('span');
    add.className = 'add';
    add.textContent = '+' + file.added;
    const del = document.createElement('span');
    del.className = 'del';
    del.textContent = ' −' + file.removed;
    stat.append(add, document.createTextNode(' '), del);
    summary.append(path, stat);
    details.append(summary);

    const body = document.createElement('div');
    body.className = 'diff-lines';
    for (const line of file.lines) {
      body.append(diffLineRow(line));
    }
    details.append(body);
    details.addEventListener('toggle', () => {
      if (details.open) {
        state.previewPath = file.path;
      }
    });
    el.changeDiff.append(details);
  }
}

/** 单行 diff：左侧行号，右侧代码；加/删/上下文由 kind 决定配色。 */
function diffLineRow(line) {
  const row = document.createElement('div');
  row.className = 'diff-line is-' + line.kind;
  const no = document.createElement('span');
  no.className = 'n';
  no.textContent = line.old !== '' ? String(line.old) : (line.next !== '' ? String(line.next) : '');
  const code = document.createElement('code');
  code.textContent = line.text === '' ? ' ' : line.text;
  row.append(no, code);
  return row;
}

function changeHint(text) {
  const node = document.createElement('div');
  node.className = 'change-empty';
  node.textContent = text;
  return node;
}

/* ---------- 工作区选择 ---------- */

async function openPicker(options = {}) {
  el.picker.hidden = false;
  el.pickerError.hidden = true;
  el.pickerConfirm.disabled = true;
  await loadRoots();
  const start = state.workspace || localStorage.getItem(LAST_WORKSPACE_KEY) || '';
  await browseTo(start);
  if (options.firstRun && !state.pickerSelection) {
    el.pickerSelected.textContent = '尚未选择目录';
  }
}

function closePicker() {
  el.picker.hidden = true;
}

async function loadRoots() {
  try {
    const response = await fetch('/api/workspaces/roots');
    if (!response.ok) {
      return;
    }
    const roots = await response.json();
    el.pickerRoots.replaceChildren();
    for (const root of roots) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'root-item';
      button.textContent = root.label;
      button.title = root.path;
      button.addEventListener('click', () => browseTo(root.path));
      el.pickerRoots.append(button);
    }
  } catch (error) {
    console.warn('[roots]', error);
  }
}

async function browseTo(path) {
  el.pickerError.hidden = true;
  try {
    const query = path ? '?path=' + encodeURIComponent(path) : '';
    const response = await fetch('/api/workspaces/browse' + query);
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const data = await response.json();
    state.pickerPath = data.path;
    state.pickerParent = data.parent;
    state.pickerSelection = data.path;
    el.pickerPath.value = data.path;
    renderPickerList(data);
    updatePickerSelection();
  } catch (error) {
    el.pickerError.textContent = error.message;
    el.pickerError.hidden = false;
  }
}

/** 把错误响应翻译成一句人话：优先服务端给的 message/detail，其次 404 与状态码兜底。 */
async function describeError(response) {
  let body = null;
  try {
    body = await response.json();
  } catch (ignored) {
    // 非 JSON 错误体（例如网关返回的 HTML）
  }
  return errorText(response, body);
}

function renderPickerList(data) {
  el.pickerList.replaceChildren();
  const directories = data.directories || [];
  if (!directories.length) {
    const empty = document.createElement('li');
    empty.className = 'dir-item';
    empty.textContent = '（没有子目录，可以直接选择当前目录）';
    empty.style.cursor = 'default';
    empty.style.color = 'var(--dim)';
    el.pickerList.append(empty);
    return;
  }
  for (const dir of directories) {
    const item = document.createElement('li');
    item.className = 'dir-item';
    item.dataset.path = dir.path;

    const name = document.createElement('span');
    name.className = 'dir-name';
    name.textContent = dir.name;

    const enter = document.createElement('button');
    enter.type = 'button';
    enter.className = 'dir-enter';
    enter.textContent = '进入';
    enter.addEventListener('click', (event) => {
      event.stopPropagation();
      browseTo(dir.path);
    });

    item.append(name, enter);
    item.addEventListener('click', () => {
      state.pickerSelection = dir.path;
      updatePickerSelection();
    });
    item.addEventListener('dblclick', () => browseTo(dir.path));
    el.pickerList.append(item);
  }
}

function updatePickerSelection() {
  el.pickerSelected.textContent = state.pickerSelection || '尚未选择目录';
  el.pickerConfirm.disabled = !state.pickerSelection;
  for (const item of el.pickerList.querySelectorAll('.dir-item')) {
    item.classList.toggle('is-on', item.dataset.path === state.pickerSelection);
  }
  const roots = [...el.pickerRoots.querySelectorAll('.root-item')];
  let best = null;
  for (const root of roots) {
    if (isUnder(state.pickerPath, root.title) && (!best || root.title.length > best.title.length)) {
      best = root;
    }
  }
  roots.forEach((root) => root.classList.toggle('is-on', root === best));
}

function isUnder(path, root) {
  if (!path || !root) {
    return false;
  }
  const normalize = (value) => value.toLowerCase().replace(/\//g, '\\');
  const target = normalize(path);
  const base = normalize(root);
  return target === base || target.startsWith(base.endsWith('\\') ? base : base + '\\');
}

function pickerUp() {
  if (state.pickerParent) {
    browseTo(state.pickerParent);
  }
}

function confirmPicker() {
  const target = state.pickerSelection;
  if (!target) {
    return;
  }
  closePicker();
  startSession(target);
}

/* ---------- 输入 ---------- */

function bindComposer() {
  el.input.addEventListener('keydown', (event) => {
    if (onComposerKeydown(event)) {
      return;
    }
    const submit = (event.ctrlKey || event.metaKey) && event.key === 'Enter';
    if ((event.key === 'Enter' && !event.shiftKey && !event.isComposing) || submit) {
      event.preventDefault();
      el.composer.requestSubmit();
    }
  });
  el.composer.addEventListener('submit', sendMessage);
  // 模型设置里的输入框在表单内：回车不能触发发消息，改为保存
  el.modelMenu.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter') {
      return;
    }
    event.preventDefault();
    saveModel();
  });
}

function onPromptClick(event) {
  const target = event.target.closest('button[data-prompt]');
  if (!target) {
    return;
  }
  el.input.value = target.dataset.prompt;
  autoGrow();
  el.input.focus();
}

async function sendMessage(event) {
  event.preventDefault();
  const content = el.input.value.trim();
  const attachments = state.attachments.slice();
  if (!content && !attachments.length) {
    return;
  }
  el.input.value = '';
  autoGrow();
  setAttachments([]);
  // 只有附件没有文字时也要有正文，服务端和模型都需要一句话说明
  await submitMessage(content || attachmentSummary(attachments), attachments);
}

/** 提交一条用户消息并启动运行；输入框与「继续」按钮共用。 */
async function submitMessage(content, attachments = []) {
  if ((!content && !attachments.length) || !state.sessionId) {
    return;
  }

  // 运行中发送不再拦截：服务端会把它排进当前 run 的下一轮
  const wasRunning = state.running;
  appendUserMessage(content || attachmentSummary(attachments));
  state.pendingInputs.push(content);
  if (!wasRunning) {
    renameSessionIfDefault(content);
    state.running = true;
    state.runId = null;
    state.lastRunId = null;
    state.changedFiles = [];
    setRunState('running', '发送中');
  } else {
    setRunState('running', '已排队');
  }
  syncComposer();
  scrollThread();

  try {
    const response = await fetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        content,
        attachments: attachments.map((item) => ({
          name: item.name,
          mimeType: item.mimeType,
          dataBase64: item.dataBase64
        }))
      })
    });
    if (response.status === 202) {
      // 已入队，等 run.steered 事件确认被消费
      return;
    }
    if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    }
    const started = await response.json();
    state.runId = started.runId || null;
    syncComposer();
  } catch (error) {
    if (!wasRunning) {
      state.running = false;
      state.pendingInputs.pop();
      syncComposer();
      setRunState('error', '发送失败');
    } else if (attachments.length) {
      // 运行中追加图片会被服务端拒绝，把附件放回输入区，避免白丢
      setAttachments(attachments);
    }
    showNotice('发送失败：' + error.message);
  }
}

/** 单按钮：运行中点击等于停止，空闲时等于发送（Enter 仍走表单提交，保留运行中追加指令）。 */
function onSendButtonClick() {
  if (state.running) {
    cancelRun();
    return;
  }
  el.composer.requestSubmit();
}

async function cancelRun() {
  if (!state.runId) {
    // 刷新后本地可能还没拿到 runId，向服务端确认一次再决定能不能停
    await refreshActiveRun();
  }
  if (!state.runId) {
    showNotice('当前没有正在运行的任务。');
    return;
  }
  el.send.disabled = true;
  try {
    const response = await fetch(`/api/runs/${encodeURIComponent(state.runId)}/cancel`, { method: 'POST' });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    showNotice('已请求取消，当前步骤结束后停止。');
  } catch (error) {
    showNotice('取消失败：' + error.message);
  } finally {
    el.send.disabled = false;
  }
}

/**
 * 继续上一次未完成的任务：走 /continue，不伪造「继续」这条用户消息，
 * 服务端用已经落盘的对话记忆接着跑，进程重启过也能接上。
 */
async function continueRun(box) {
  if (state.running || !state.sessionId) {
    return;
  }
  const buttons = [...box.querySelectorAll('button')];
  buttons.forEach((item) => { item.disabled = true; });
  try {
    const response = await fetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/continue`, {
      method: 'POST'
    });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const data = await response.json();
    state.runId = data.runId || state.runId;
    state.lastRunId = data.runId || state.lastRunId;
    state.running = true;
    startRunClock(Date.now());
    setRunState('run', '运行中');
    syncComposer();
  } catch (error) {
    showNotice('继续失败：' + error.message);
    buttons.forEach((item) => { item.disabled = false; });
  }
}

async function refreshActiveRun() {
  if (!state.sessionId) {
    return null;
  }
  try {
    const response = await fetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/runs/active`);
    if (!response.ok) {
      return null;
    }
    const data = await response.json();
    if (data.runId) {
      state.runId = data.runId;
      state.running = true;
      startRunClock(Date.now());
      syncComposer();
    }
    return data.runId || null;
  } catch (error) {
    console.warn('[active]', error);
    return null;
  }
}

async function rollbackRun() {
  if (!state.lastRunId) {
    return;
  }
  const confirmed = window.confirm('把本次运行改过的文件还原到运行前？新建的文件会被删除。');
  if (!confirmed) {
    return;
  }
  el.changesRollback.disabled = true;
  try {
    const response = await fetch(`/api/runs/${encodeURIComponent(state.lastRunId)}/rollback`, { method: 'POST' });
    if (response.status === 404) {
      showNotice('这次运行没有产生文件改动，无需回滚。');
      return;
    }
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const report = await response.json();
    const failed = (report.failed || []).length;
    showNotice(`已回滚：还原 ${(report.restored || []).length} 个、删除 ${(report.deleted || []).length} 个`
      + (failed ? `、失败 ${failed} 个` : ''));
    loadChanges();
  } catch (error) {
    showNotice('回滚失败：' + error.message);
  } finally {
    el.changesRollback.disabled = false;
    syncComposer();
  }
}

function autoGrow() {
  el.input.style.height = 'auto';
  el.input.style.height = Math.min(el.input.scrollHeight, 180) + 'px';
}

/* ---------- 命令面板 ---------- */

function openPalette() {
  el.palette.hidden = false;
  el.paletteInput.value = '';
  state.paletteIndex = 0;
  renderPalette('');
  el.paletteInput.focus();
}

function closePalette() {
  el.palette.hidden = true;
  el.paletteItems = [];
}

function renderPalette(query) {
  const needle = query.trim().toLowerCase();
  const items = COMMANDS.filter((item) => !needle || item.label.toLowerCase().includes(needle));
  state.paletteItems = items;
  state.paletteIndex = Math.min(state.paletteIndex, Math.max(items.length - 1, 0));

  el.paletteList.replaceChildren();
  if (!items.length) {
    const empty = document.createElement('li');
    empty.className = 'palette-item is-empty';
    empty.textContent = '没有匹配的命令';
    el.paletteList.append(empty);
    return;
  }

  items.forEach((item, index) => {
    const li = document.createElement('li');
    li.className = 'palette-item' + (index === state.paletteIndex ? ' is-on' : '');
    li.setAttribute('role', 'option');
    const label = document.createElement('span');
    label.textContent = item.label;
    const hint = document.createElement('span');
    hint.className = 'palette-hint';
    hint.textContent = item.hint || '';
    li.append(label, hint);
    li.addEventListener('click', () => runPaletteItem(item));
    li.addEventListener('mouseenter', () => {
      state.paletteIndex = index;
      renderPalette(query);
    });
    el.paletteList.append(li);
  });
}

function runPaletteItem(item) {
  closePalette();
  item.run();
}

function onPaletteKeydown(event) {
  if (event.key === 'Escape') {
    event.preventDefault();
    closePalette();
    return;
  }
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    const step = event.key === 'ArrowDown' ? 1 : -1;
    const total = state.paletteItems.length;
    if (!total) {
      return;
    }
    state.paletteIndex = (state.paletteIndex + step + total) % total;
    renderPalette(el.paletteInput.value);
    return;
  }
  if (event.key === 'Enter') {
    event.preventDefault();
    const item = state.paletteItems[state.paletteIndex];
    if (item) {
      runPaletteItem(item);
    }
  }
}

function onGlobalKeydown(event) {
  const mod = event.ctrlKey || event.metaKey;
  if (mod && event.key.toLowerCase() === 'k') {
    event.preventDefault();
    el.palette.hidden ? openPalette() : closePalette();
    return;
  }
  if (mod && event.key.toLowerCase() === 'n') {
    event.preventDefault();
    openPicker();
    return;
  }
  if (mod && (event.key.toLowerCase() === 'b' || event.key.toLowerCase() === 'j')) {
    event.preventDefault();
    toggleActivity();
    return;
  }
  if (event.key === 'Escape') {
    if (!el.permMenu.hidden) {
      togglePermissionMenu(false);
      return;
    }
    if (!el.modelMenu.hidden) {
      toggleModelMenu(false);
      return;
    }
    if (!el.picker.hidden) {
      closePicker();
    } else if (!el.palette.hidden) {
      closePalette();
    } else {
      closeOverlays();
    }
  }
}

/* ---------- 布局 ---------- */

function toggleSidebar() {
  if (isNarrow()) {
    el.app.classList.toggle('sidebar-open');
    syncScrim();
    return;
  }
  el.app.classList.toggle('sidebar-collapsed');
}

function closeSidebar() {
  if (isNarrow()) {
    el.app.classList.remove('sidebar-open');
    syncScrim();
  }
}

function syncScrim() {
  const drawerOpen = el.app.classList.contains('sidebar-open')
    || el.activity.classList.contains('is-open');
  el.scrim.hidden = !(isNarrow() && drawerOpen);
}

function closeOverlays() {
  closeSidebar();
  el.activity.classList.remove('is-open');
  el.toggleActivity.setAttribute('aria-pressed', String(!el.activity.classList.contains('is-hidden')));
  syncScrim();
  if (!el.palette.hidden) {
    closePalette();
  }
}

/* ---------- 视图状态 ---------- */

function setComposerEnabled(enabled) {
  state.composerEnabled = enabled;
  syncComposer();
}

/** 输入区：单按钮，空闲是发送、运行中变停止；结束后按需显示回滚。 */
function syncComposer() {
  const running = state.running;
  el.send.classList.toggle('is-running', running);
  el.send.setAttribute('aria-label', running ? '停止' : '发送');
  el.send.title = running ? '停止本次运行' : '发送 (Ctrl Enter)';
  // 运行中按钮必须可点：此时它是唯一的停止入口
  el.send.disabled = running ? false : !state.composerEnabled;
  // mock 模式没有真实写入，也就没有快照可回滚
  el.changesRollback.hidden = !(state.lastRunId && !running && state.agentMode === 'real');
}

function setConnection(kind, label) {
  el.connection.className = 'conn is-' + kind;
  el.connectionText.textContent = label;
}

function setRunState(kind, label) {
  el.runState.className = 'run-state state-' + kind;
  el.runState.textContent = label;
}

function markSessionStatus(status) {
  const found = state.sessions.find((item) => item.id === state.sessionId);
  if (found && found.status !== status) {
    found.status = status;
    renderSessions();
  }
}

/** 运行计时：让「卡住」和「还在跑」在界面上可区分。 */
function startRunClock(startedAt) {
  state.runStartedAt = startedAt || Date.now();
  state.lastEventAt = Date.now();
  stopRunClock();
  state.clock = setInterval(tickRunClock, 1000);
  tickRunClock();
}

function stopRunClock() {
  if (state.clock) {
    clearInterval(state.clock);
    state.clock = null;
  }
  state.runStartedAt = null;
}

function tickRunClock() {
  if (!state.running) {
    stopRunClock();
    return;
  }
  const elapsed = elapsedText(Date.now() - (state.runStartedAt || Date.now()));
  const idle = Math.max(0, Math.round((Date.now() - (state.lastEventAt || Date.now())) / 1000));
  if (idle >= STALL_SECONDS) {
    setRunState('warn', `运行中 · ${elapsed} · ${idle}s 无新事件`);
  } else {
    setRunState('running', `运行中 · ${elapsed}`);
  }
}

function elapsedText(ms) {
  const total = Math.max(0, Math.round(ms / 1000));
  if (total < 60) {
    return total + 's';
  }
  const minutes = Math.floor(total / 60);
  if (minutes < 60) {
    return `${minutes}m${String(total % 60).padStart(2, '0')}s`;
  }
  return `${Math.floor(minutes / 60)}h${String(minutes % 60).padStart(2, '0')}m`;
}

function showNotice(message) {
  el.noticeText.textContent = message;
  el.notice.hidden = false;
}

function hideNotice() {
  el.notice.hidden = true;
}

function resetView() {
  clearThinking();
  stopRunClock();
  state.cursor = 0;
  state.toolRows.clear();
  state.pendingApprovals.clear();
  state.reply = null;
  state.replyText = '';
  state.running = false;
  state.runId = null;
  state.lastRunId = null;
  state.changedFiles = [];
  state.turnRuns = [];
  state.streamCard = null;
  state.streamText = '';
  syncComposer();
  el.messages.replaceChildren(el.emptyState);
  el.emptyState.hidden = false;
  el.messages.classList.add('is-empty');
  el.timeline.replaceChildren();
  el.timelineEmpty.hidden = false;
  state.activityCount = 0;
  el.toggleActivity.title = '活动面板 (Ctrl B)';
  renderApprovalDock();
  resetChanges();
}

function hideEmptyState() {
  if (!el.messages.classList.contains('is-empty')) {
    return;
  }
  el.messages.classList.remove('is-empty');
  el.emptyState.hidden = true;
}

function scrollThread() {
  el.messages.scrollTop = el.messages.scrollHeight;
}

function isNearBottom(node, slack = 140) {
  return node.scrollHeight - node.scrollTop - node.clientHeight < slack;
}

/* ---------- 工作台：文件树 / 预览 / 分栏 / @ 引用 ---------- */

const QUICK_COMMANDS = [
  { name: '/test', hint: '跑全量测试', text: '运行 mvnw -o test，汇总失败用例与原因，不要改动代码。' },
  { name: '/fix', hint: '修最近一次失败', text: '读取最近一次失败的测试或编译错误，定位根因并直接修改代码，改完重新运行验证。' },
  { name: '/run', hint: '跑指定模块', text: '运行 mvnw -o -pl pilot-core test，并把失败点整理出来。' },
  { name: '/review', hint: '审阅未提交改动', text: '审阅当前工作区未提交的改动，按风险从高到低列出问题与建议。' },
  { name: '/explain', hint: '解释当前文件', text: '解释当前打开文件的核心逻辑、调用链和潜在风险。' }
];

function initWorkbench() {
  setupGutter(el.gutterLeft, '--sidebar-w', 210, 460, false);
  setupGutter(el.gutterRight, '--activity-w', 300, 720, true);
  el.input.addEventListener('input', onComposerInput);
  document.addEventListener('click', (event) => {
    if (!event.target.closest('.composer-wrap')) {
      hideRefMenu();
    }
  });
}

/** 拖动分栏：左栏按鼠标 X 定位，右栏按到右边缘的距离定位。 */
function setupGutter(node, variable, min, max, fromRight) {
  if (!node) {
    return;
  }
  node.addEventListener('pointerdown', (event) => {
    event.preventDefault();
    node.setPointerCapture(event.pointerId);
    node.classList.add('is-dragging');
    document.body.style.cursor = 'col-resize';
    const move = (moveEvent) => {
      const raw = fromRight ? window.innerWidth - moveEvent.clientX : moveEvent.clientX;
      const value = Math.max(min, Math.min(max, raw));
      document.documentElement.style.setProperty(variable, value + 'px');
    };
    const stop = () => {
      node.classList.remove('is-dragging');
      document.body.style.cursor = '';
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', stop);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', stop);
  });
}

async function loadTree(path = '', container = el.tree) {
  if (!state.sessionId) {
    return;
  }
  if (container === el.tree) {
    el.treeEmpty.hidden = true;
    container.replaceChildren(treeHint('读取中…'));
  }
  try {
    const data = await fetchTree(path);
    container.replaceChildren();
    renderTreeEntries(data.entries || [], container);
    if (container === el.tree) {
      refreshChangeMarkers(state.changedFiles || []);
      if (!(data.entries || []).length) {
        container.replaceChildren(treeHint('工作区是空的。'));
      }
    }
  } catch (error) {
    container.replaceChildren(treeHint('读取失败：' + error.message));
  }
}

async function fetchTree(path) {
  const response = await fetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/files?path=${encodeURIComponent(path)}`);
  if (!response.ok) {
    throw new Error(await describeError(response));
  }
  return response.json();
}

function treeHint(text) {
  const node = document.createElement('p');
  node.className = 'tree-hint';
  node.textContent = text;
  return node;
}

function renderTreeEntries(entries, container) {
  for (const entry of entries) {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'tree-row';
    row.dataset.path = normalizePath(entry.path);
    row.dataset.dir = String(entry.directory);
    row.title = entry.path;

    const caret = document.createElement('span');
    caret.className = 'tree-caret';
    caret.textContent = entry.directory ? '▸' : '';
    const name = document.createElement('span');
    name.className = 'tree-name';
    name.textContent = entry.name;

    row.append(caret, name);

    if (entry.directory) {
      const children = document.createElement('div');
      children.className = 'tree-children';
      children.hidden = true;
      row.addEventListener('click', () => {
        children.hidden = !children.hidden;
        caret.textContent = children.hidden ? '▸' : '▾';
        if (!children.hidden && !children.dataset.loaded) {
          children.dataset.loaded = '1';
          loadTree(entry.path, children);
        }
      });
      container.append(row, children);
    } else {
      caret.textContent = '·';
      caret.style.visibility = 'hidden';
      row.addEventListener('click', () => openPreview(entry.path, row));
      container.append(row);
    }
  }
}

async function openPreview(path, row) {
  if (!state.sessionId) {
    return;
  }
  state.previewPath = path;
  selectTab('preview');
  el.previewPath.textContent = path;
  el.previewPath.title = path;
  el.previewBody.textContent = '读取中…';
  highlightTree(path, row);
  try {
    const response = await fetch(
      `/api/sessions/${encodeURIComponent(state.sessionId)}/file?path=${encodeURIComponent(path)}`);
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    const data = await response.json();
    if (data.binary) {
      el.previewBody.textContent = '（二进制文件，无法预览）';
    } else if (data.truncated) {
      el.previewBody.textContent = '（文件超过 512 KB，未加载预览）';
    } else {
      el.previewBody.textContent = data.content && data.content.length ? data.content : '（空文件）';
    }
  } catch (error) {
    el.previewBody.textContent = '读取失败：' + error.message;
  }
}

function highlightTree(path, row) {
  const target = normalizePath(path);
  for (const item of el.tree.querySelectorAll('.tree-row.is-active')) {
    item.classList.remove('is-active');
  }
  const found = row || [...el.tree.querySelectorAll('.tree-row')]
    .find((item) => item.dataset.path === target && item.dataset.dir !== 'true');
  if (found) {
    found.classList.add('is-active');
  }
}

/** 变更面板加载后，在文件树里给改动过的文件打点。 */
function refreshChangeMarkers(files) {
  const changed = new Set((files || []).map((file) => normalizePath(typeof file === 'string' ? file : file.path)));
  for (const row of el.tree.querySelectorAll('.tree-row')) {
    row.classList.toggle('is-changed', changed.has(row.dataset.path));
  }
}

/* ---------- 输入框：@ 引用与 / 快捷指令 ---------- */

function onComposerInput() {
  autoGrow();
  const caret = el.input.selectionStart;
  const before = el.input.value.slice(0, caret);
  const fileMatch = /(^|\s)@([\w./-]*)$/.exec(before);
  const commandMatch = /^\/([\w-]*)$/.exec(before);
  if (fileMatch) {
    showRefMenu('file', fileMatch[2]);
  } else if (commandMatch) {
    showRefMenu('command', commandMatch[1]);
  } else {
    hideRefMenu();
  }
}

function showRefMenu(kind, query) {
  const items = kind === 'command'
    ? QUICK_COMMANDS.filter((item) => item.name.includes(query))
    : treeFiles().filter((path) => path.toLowerCase().includes(String(query).toLowerCase())).slice(0, 40);

  el.refMenu.replaceChildren();
  state.refItems = items;
  state.refKind = kind;
  state.refIndex = 0;
  if (!items.length) {
    hideRefMenu();
    return;
  }
  const title = document.createElement('p');
  title.className = 'ref-title';
  title.textContent = kind === 'command' ? '快捷指令' : '引用文件';
  el.refMenu.append(title);

  items.forEach((item, index) => {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'ref-item' + (index === 0 ? ' is-on' : '');
    const label = document.createElement('span');
    const desc = document.createElement('span');
    desc.className = 'ref-desc';
    if (kind === 'command') {
      label.textContent = item.name;
      desc.textContent = item.hint;
    } else {
      label.className = 'mono';
      label.textContent = item;
      desc.textContent = '文件';
    }
    row.append(label, desc);
    row.addEventListener('mousedown', (event) => {
      event.preventDefault();
      applyRef(item);
    });
    el.refMenu.append(row);
  });
  el.refMenu.hidden = false;
}

function hideRefMenu() {
  el.refMenu.hidden = true;
  state.refItems = [];
}

function treeFiles() {
  return [...el.tree.querySelectorAll('.tree-row')]
    .filter((row) => row.dataset.dir === 'false')
    .map((row) => row.dataset.path);
}

function applyRef(item) {
  const value = el.input.value;
  const caret = el.input.selectionStart;
  const before = value.slice(0, caret);
  const after = value.slice(caret);
  const kind = state.refKind;
  if (kind === 'command') {
    el.input.value = item.text + after.replace(/^\s*/, ' ');
  } else {
    const replaced = before.replace(/@([\w./-]*)$/, '@' + item + ' ');
    el.input.value = replaced + after;
  }
  hideRefMenu();
  autoGrow();
  el.input.focus();
  el.input.setSelectionRange(el.input.value.length, el.input.value.length);
}

function onComposerKeydown(event) {
  if (!el.refMenu.hidden && state.refItems && state.refItems.length) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const delta = event.key === 'ArrowDown' ? 1 : -1;
      state.refIndex = (state.refIndex + delta + state.refItems.length) % state.refItems.length;
      [...el.refMenu.querySelectorAll('.ref-item')].forEach((row, index) => {
        row.classList.toggle('is-on', index === state.refIndex);
      });
      return true;
    }
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      applyRef(state.refItems[state.refIndex]);
      return true;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      hideRefMenu();
      return true;
    }
  }
  return false;
}

/* ---------- 工具函数 ---------- */

function button(label, className, onClick) {
  const node = document.createElement('button');
  node.type = 'button';
  node.className = className;
  node.textContent = label;
  node.addEventListener('click', onClick);
  return node;
}

const FENCE_PATTERN = /^\s{0,3}(`{3,}|~{3,})\s*([\w+#.-]*)\s*$/;
const HEADING_PATTERN = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;
const RULE_PATTERN = /^\s{0,3}([-*_])(\s*\1){2,}\s*$/;
const QUOTE_PATTERN = /^\s{0,3}>\s?/;
const LIST_PATTERN = /^\s{0,3}([-*+]|\d+[.)])\s+(.*)$/;
const TABLE_DIVIDER = /^:?-{2,}:?$/;

/**
 * 把模型返回的 Markdown 渲染成 DOM。只构造节点、不拼 innerHTML，天然免疫 HTML 注入。
 * 支持：围栏代码块、标题、表格、引用、有序/无序列表、分隔线、粗体/斜体/删除线/行内代码/链接。
 * 流式期间未闭合的代码围栏会一直按代码渲染，避免逐字闪烁。
 */
function renderMarkdown(text) {
  const fragment = document.createDocumentFragment();
  for (const node of markdownBlocks(String(text).replace(/\r\n/g, '\n').split('\n'))) {
    fragment.append(node);
  }
  return fragment;
}

function markdownBlocks(lines) {
  const nodes = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    if (!line.trim()) {
      i++;
      continue;
    }

    const fence = line.match(FENCE_PATTERN);
    if (fence) {
      const marker = fence[1][0];
      const length = fence[1].length;
      const language = fence[2] || '';
      i++;
      const code = [];
      while (i < lines.length) {
        const closing = lines[i].match(/^\s{0,3}(`{3,}|~{3,})\s*$/);
        if (closing && closing[1][0] === marker && closing[1].length >= length) {
          i++;
          break;
        }
        code.push(lines[i]);
        i++;
      }
      nodes.push(codeBlock(code.join('\n'), language));
      continue;
    }

    if (RULE_PATTERN.test(line)) {
      nodes.push(document.createElement('hr'));
      i++;
      continue;
    }

    const heading = line.match(HEADING_PATTERN);
    if (heading) {
      const node = document.createElement('h' + heading[1].length);
      node.append(inlineFragment(heading[2]));
      nodes.push(node);
      i++;
      continue;
    }

    if (isTableStart(lines, i)) {
      const table = buildTable(lines, i);
      nodes.push(table.node);
      i = table.next;
      continue;
    }

    if (QUOTE_PATTERN.test(line)) {
      const quoted = [];
      while (i < lines.length && QUOTE_PATTERN.test(lines[i])) {
        quoted.push(lines[i].replace(QUOTE_PATTERN, ''));
        i++;
      }
      const blockquote = document.createElement('blockquote');
      for (const node of markdownBlocks(quoted)) {
        blockquote.append(node);
      }
      nodes.push(blockquote);
      continue;
    }

    if (LIST_PATTERN.test(line)) {
      const list = buildList(lines, i);
      nodes.push(list.node);
      i = list.next;
      continue;
    }

    const paragraph = [];
    while (i < lines.length) {
      const current = lines[i];
      if (!current.trim()) {
        break;
      }
      if (FENCE_PATTERN.test(current) || RULE_PATTERN.test(current)
          || HEADING_PATTERN.test(current) || QUOTE_PATTERN.test(current)
          || LIST_PATTERN.test(current) || isTableStart(lines, i)) {
        break;
      }
      paragraph.push(current.trim());
      i++;
    }
    if (!paragraph.length) {
      i++;
      continue;
    }
    const node = document.createElement('p');
    paragraph.forEach((part, index) => {
      if (index) {
        node.append(document.createElement('br'));
      }
      node.append(inlineFragment(part));
    });
    nodes.push(node);
  }

  return nodes;
}

function codeBlock(code, language) {
  const pre = document.createElement('pre');
  const el = document.createElement('code');
  if (language) {
    el.dataset.lang = language;
  }
  el.textContent = code.replace(/\n+$/, '');
  pre.append(el);
  return pre;
}

function splitRow(line) {
  let value = line.trim();
  if (value.startsWith('|')) {
    value = value.slice(1);
  }
  if (value.endsWith('|')) {
    value = value.slice(0, -1);
  }
  return value.split('|').map((cell) => cell.trim());
}

function isTableStart(lines, index) {
  const header = lines[index];
  if (!header || !header.includes('|')) {
    return false;
  }
  const divider = lines[index + 1];
  if (!divider || !divider.includes('-')) {
    return false;
  }
  const cells = splitRow(divider);
  return cells.length > 0 && cells.every((cell) => TABLE_DIVIDER.test(cell.replace(/\s/g, '')));
}

function buildTable(lines, start) {
  const header = splitRow(lines[start]);
  const aligns = splitRow(lines[start + 1]).map((cell) => {
    const value = cell.replace(/\s/g, '');
    if (value.startsWith(':') && value.endsWith(':')) {
      return 'center';
    }
    if (value.endsWith(':')) {
      return 'right';
    }
    return '';
  });

  const table = document.createElement('table');
  const thead = document.createElement('thead');
  const headRow = document.createElement('tr');
  header.forEach((cell, index) => {
    const th = document.createElement('th');
    if (aligns[index]) {
      th.style.textAlign = aligns[index];
    }
    th.append(inlineFragment(cell));
    headRow.append(th);
  });
  thead.append(headRow);
  table.append(thead);

  const tbody = document.createElement('tbody');
  let i = start + 2;
  while (i < lines.length && lines[i].trim() && lines[i].includes('|')) {
    const cells = splitRow(lines[i]);
    const row = document.createElement('tr');
    header.forEach((ignored, index) => {
      const td = document.createElement('td');
      if (aligns[index]) {
        td.style.textAlign = aligns[index];
      }
      td.append(inlineFragment(cells[index] || ''));
      row.append(td);
    });
    tbody.append(row);
    i++;
  }
  table.append(tbody);
  return { node: table, next: i };
}

function buildList(lines, start) {
  const ordered = /^\s{0,3}\d/.test(lines[start]);
  const list = document.createElement(ordered ? 'ol' : 'ul');
  let i = start;
  while (i < lines.length) {
    const item = lines[i].match(LIST_PATTERN);
    if (!item) {
      break;
    }
    if (/^\s{0,3}\d/.test(lines[i]) !== ordered) {
      break;
    }
    const li = document.createElement('li');
    li.append(inlineFragment(item[2]));
    list.append(li);
    i++;
  }
  return { node: list, next: i };
}

function inlineFragment(text) {
  const fragment = document.createDocumentFragment();
  const pattern = /(`[^`\n]+`|\*\*[^*\n]+\*\*|~~[^~\n]+~~|\*[^*\n]+\*|\[[^\]\n]+\]\([^)\s]+\))/g;
  let last = 0;
  let match;
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > last) {
      fragment.append(document.createTextNode(text.slice(last, match.index)));
    }
    fragment.append(inlineToken(match[0]));
    last = match.index + match[0].length;
  }
  if (last < text.length) {
    fragment.append(document.createTextNode(text.slice(last)));
  }
  return fragment;
}

function inlineToken(token) {
  if (token.startsWith('`')) {
    const code = document.createElement('code');
    code.textContent = token.slice(1, -1);
    return code;
  }
  if (token.startsWith('**')) {
    const strong = document.createElement('strong');
    strong.append(inlineFragment(token.slice(2, -2)));
    return strong;
  }
  if (token.startsWith('~~')) {
    const del = document.createElement('del');
    del.textContent = token.slice(2, -2);
    return del;
  }
  if (token.startsWith('*')) {
    const em = document.createElement('em');
    em.textContent = token.slice(1, -1);
    return em;
  }
  const link = token.match(/^\[([^\]]+)\]\(([^)\s]+)\)$/);
  if (link && /^https?:\/\//i.test(link[2])) {
    const anchor = document.createElement('a');
    anchor.href = link[2];
    anchor.textContent = link[1];
    anchor.target = '_blank';
    anchor.rel = 'noreferrer noopener';
    return anchor;
  }
  return document.createTextNode(token);
}

function formatTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return date.toLocaleTimeString('zh-CN', { hour12: false });
}

function preview(value, maxLength) {
  const single = String(value || '').replace(/\s+/g, ' ').trim();
  return single.length <= maxLength ? single : single.slice(0, maxLength) + '…';
}
