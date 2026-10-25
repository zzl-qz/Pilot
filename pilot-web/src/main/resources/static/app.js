const EVENT_TYPES = [
  'run.started',
  'assistant.delta',
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
  stop: document.querySelector('#stop'),
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
  changedFiles: [],
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
  document.addEventListener('click', (event) => {
    if (!el.permMenu.hidden && !event.target.closest('.perm-picker')) {
      togglePermissionMenu(false);
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
  el.stop.addEventListener('click', cancelRun);
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
  await calibrateRun(sessionId);
  if (state.sessionId !== sessionId) {
    return;
  }
  connect(0);
  setComposerEnabled(true);
  state.previewPath = null;
  el.previewPath.textContent = '未选择文件';
  el.previewBody.textContent = '在左侧文件树里点一个文件，或从「变更」里点开 diff 文件。';
  loadTree('');
}

/** 断线/刷新后校准运行状态：服务端还有活跃 run 就恢复计时与停止按钮。 */
async function calibrateRun(sessionId) {
  state.running = false;
  state.runId = null;
  try {
    const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/runs/active`);
    if (!response.ok) {
      return;
    }
    const data = await response.json();
    if (!data.runId || state.sessionId !== sessionId) {
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

  const stick = isNearBottom(el.messages);
  const stickTimeline = isNearBottom(el.timeline);

  switch (event.type) {
    case 'run.started': {
      state.running = true;
      state.runId = event.runId || state.runId;
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
      setRunState('done', '已完成');
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
      record(event, {
        kind: 'run',
        tone: 'ok',
        status: '完成',
        title: '运行完成',
        detail: event.payload.summary,
        foot: extras.join(' · ')
      });
      refreshChanges();
      break;
    }

    case 'run.failed': {
      const code = String(event.payload.code || '');
      const cancelled = code === 'CANCELLED';
      // 步数触顶不是失败：历史对话已保留，继续跑即可
      const paused = code === 'MAX_STEPS';
      state.running = false;
      stopRunClock();
      markSessionStatus(cancelled ? 'CANCELLED' : paused ? 'PAUSED' : 'FAILED');
      state.lastRunId = event.runId || state.runId || state.lastRunId;
      state.runId = null;
      setRunState(paused ? 'warn' : cancelled ? 'idle' : 'error',
        paused ? '已暂停' : cancelled ? '已取消' : '失败');
      setComposerEnabled(true);
      clearThinking();
      finishReply(!paused);
      record(event, {
        kind: 'run',
        tone: paused || cancelled ? 'warn' : 'error',
        status: paused ? '可继续' : cancelled ? '已取消' : (event.payload.retryable ? '可重试' : '失败'),
        title: paused ? `本轮已暂停 · ${code}` : `运行${cancelled ? '取消' : '失败'} · ${code}`,
        detail: event.payload.message,
        resumable: paused
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
  scheduleReplyRender();
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

  const nameEl = document.createElement('span');
  nameEl.className = 'tool-name';
  nameEl.textContent = name || 'tool';

  const argsEl = document.createElement('span');
  argsEl.className = 'tool-args';
  argsEl.textContent = args || '';

  const metaEl = document.createElement('span');
  metaEl.className = 'tool-meta';
  metaEl.textContent = '';

  button.append(status, nameEl, argsEl, metaEl);

  const body = document.createElement('div');
  body.className = 'tool-body';
  const pre = document.createElement('pre');
  body.append(pre);

  button.addEventListener('click', () => row.classList.toggle('is-open'));

  row.append(button, body);
  el.messages.append(row);
  scrollThread();

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

/** 把 unified diff 拆成「文件 → 行」两级结构，供折叠面板渲染。 */
function parseDiff(text) {
  const files = [];
  let current = null;
  let oldLine = 0;
  let newLine = 0;
  for (const line of String(text).split('\n')) {
    if (line.startsWith('diff --git ')) {
      const match = /^diff --git a\/(.+?) b\/(.+)$/.exec(line);
      current = { path: match ? match[2] : line, lines: [], added: 0, removed: 0 };
      files.push(current);
      continue;
    }
    if (!current) {
      continue;
    }
    if (line.startsWith('@@')) {
      const hunk = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
      if (hunk) {
        oldLine = Number(hunk[1]);
        newLine = Number(hunk[2]);
      }
      current.lines.push({ kind: 'hunk', text: line, old: '', next: '' });
      continue;
    }
    if (line.startsWith('+++') || line.startsWith('---') || line.startsWith('index ')
        || line.startsWith('new file') || line.startsWith('deleted file') || line.startsWith('similarity')) {
      current.lines.push({ kind: 'meta', text: line, old: '', next: '' });
      continue;
    }
    if (line.startsWith('+')) {
      current.added++;
      current.lines.push({ kind: 'add', text: line.slice(1), old: '', next: newLine++ });
    } else if (line.startsWith('-')) {
      current.removed++;
      current.lines.push({ kind: 'del', text: line.slice(1), old: oldLine++, next: '' });
    } else {
      current.lines.push({ kind: 'ctx', text: line.slice(1), old: oldLine++, next: newLine++ });
    }
  }
  return files;
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
      const row = document.createElement('div');
      row.className = 'diff-line is-' + line.kind;
      const no = document.createElement('span');
      no.className = 'n';
      no.textContent = line.old !== '' ? String(line.old) : (line.next !== '' ? String(line.next) : '');
      const code = document.createElement('code');
      code.textContent = line.text === '' ? ' ' : line.text;
      row.append(no, code);
      body.append(row);
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

function normalizePath(path) {
  return String(path || '').replace(/\\/g, '/').replace(/^\.\//, '');
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

async function describeError(response) {
  try {
    const body = await response.json();
    if (body && body.message) {
      return body.message;
    }
  } catch (ignored) {
    // 忽略非 JSON 错误体
  }
  if (response.status === 404) {
    return '没有找到对应的运行或快照';
  }
  return response.status === 400 ? '目录不可访问' : 'HTTP ' + response.status;
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
  if (!content) {
    return;
  }
  el.input.value = '';
  autoGrow();
  await submitMessage(content);
}

/** 提交一条用户消息并启动运行；输入框与「继续」按钮共用。 */
async function submitMessage(content) {
  if (!content || !state.sessionId || state.running) {
    return;
  }

  appendUserMessage(content);
  state.pendingInputs.push(content);
  renameSessionIfDefault(content);
  state.running = true;
  state.runId = null;
  state.lastRunId = null;
  state.changedFiles = [];
  syncComposer();
  setRunState('running', '发送中');
  scrollThread();

  try {
    const response = await fetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content })
    });
    if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    }
    const started = await response.json();
    state.runId = started.runId || null;
    syncComposer();
  } catch (error) {
    state.running = false;
    state.pendingInputs.pop();
    syncComposer();
    setRunState('error', '发送失败');
    showNotice('发送失败：' + error.message);
  }
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
  el.stop.disabled = true;
  try {
    const response = await fetch(`/api/runs/${encodeURIComponent(state.runId)}/cancel`, { method: 'POST' });
    if (!response.ok) {
      throw new Error(await describeError(response));
    }
    showNotice('已请求取消，当前步骤结束后停止。');
  } catch (error) {
    showNotice('取消失败：' + error.message);
  } finally {
    el.stop.disabled = false;
  }
}

/** 步数触顶后继续：对话历史在服务端，发一条「继续」即可接着做。 */
async function continueRun(box) {
  if (state.running) {
    return;
  }
  const buttons = [...box.querySelectorAll('button')];
  buttons.forEach((item) => { item.disabled = true; });
  await submitMessage('继续');
  if (!state.running) {
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

/** 输入区：空闲显示发送，运行中只显示停止，结束后按需显示回滚。 */
function syncComposer() {
  const running = state.running;
  el.stop.hidden = !running;
  el.send.hidden = running;
  el.send.disabled = !state.composerEnabled;
  if (!running) {
    el.stop.disabled = false;
  }
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
