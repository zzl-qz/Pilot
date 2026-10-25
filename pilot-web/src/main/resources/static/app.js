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

const state = {
  sessionId: null,
  sessionTitle: '',
  workspace: null,
  runId: null,
  lastRunId: null,
  agentMode: null,
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
  el.workspaceChip.addEventListener('click', () => openPicker());
  el.sessionChip.addEventListener('click', copySessionId);
  el.sidebarToggle.addEventListener('click', toggleSidebar);
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
  loadHealth();
  restoreLastSession();
}

/** 优先用上次的工作区直接开会话；没有记录时先让用户选目录。 */
function restoreLastSession() {
  const last = localStorage.getItem(LAST_WORKSPACE_KEY);
  setComposerEnabled(false);
  setRunState('idle', '待选择工作区');
  if (last) {
    startSession(last);
  } else {
    openPicker({ firstRun: true });
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
      const mode = String(health.permissionMode).toUpperCase();
      el.permChip.textContent = mode;
      el.permChip.className = 'perm-chip is-' + mode.toLowerCase().replace(/_/g, '');
      el.permChip.title = {
        READ_ONLY: '只读模式：写入与命令一律拒绝',
        ASK: '询问模式：写入与命令需你批准',
        AUTO: '自动模式：已注册工具直接放行'
      }[mode] || mode;
      el.permChip.hidden = false;
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
    state.sessions.unshift({ id: session.sessionId, title, workspace: resolvedWorkspace, createdAt: session.createdAt });
    localStorage.setItem(LAST_WORKSPACE_KEY, resolvedWorkspace);
    activate(session.sessionId, title, resolvedWorkspace);
    renderSessions();
  } catch (error) {
    setConnection('error', '创建失败');
    showNotice('创建会话失败：' + error.message);
    openPicker();
  }
}

function activate(sessionId, title, workspace) {
  closeStream();
  state.sessionId = sessionId;
  state.sessionTitle = title || '新任务';
  state.workspace = workspace || state.workspace;
  state.pendingInputs = [];
  el.crumbTitle.textContent = state.sessionTitle;
  el.sessionChip.textContent = sessionId.slice(0, 8);
  el.sessionChip.title = sessionId + '\n点击复制会话 ID';
  el.sessionChip.hidden = false;
  renderWorkspace();
  renderSessions();
  setConnection('pending', '连接中');
  connect(0);
  setComposerEnabled(true);
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
    dot.className = 'dot';
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
  const suffix = cursor ? `?after=${cursor}` : '';
  const source = new EventSource(`/api/sessions/${encodeURIComponent(state.sessionId)}/events${suffix}`);
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
  source.onopen = () => setConnection('ok', '已连接');
  source.onerror = () => setConnection('error', '重连中');
}

function reconnect() {
  if (!state.sessionId) {
    return;
  }
  closeStream();
  setConnection('pending', '重连中');
  connect(0);
}

function closeStream() {
  if (state.source) {
    state.source.close();
    state.source = null;
  }
}

function handleEvent(event) {
  console.log('[SSE]', event.type, event);
  hideEmptyState();

  const stick = isNearBottom(el.messages);
  const stickTimeline = isNearBottom(el.timeline);

  switch (event.type) {
    case 'run.started': {
      state.running = true;
      setRunState('running', '运行中');
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
      state.lastRunId = state.runId || state.lastRunId;
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
      const cancelled = event.payload.code === 'CANCELLED';
      state.running = false;
      state.lastRunId = state.runId || state.lastRunId;
      state.runId = null;
      setRunState(cancelled ? 'idle' : 'error', cancelled ? '已取消' : '失败');
      setComposerEnabled(true);
      clearThinking();
      finishReply(true);
      record(event, {
        kind: 'run',
        tone: cancelled ? 'warn' : 'error',
        status: cancelled ? '已取消' : (event.payload.retryable ? '可重试' : '失败'),
        title: `运行${cancelled ? '取消' : '失败'} · ${event.payload.code}`,
        detail: event.payload.message
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
  scheduleReplyRender();
}

/** 流式时每帧最多重绘一次，避免长回答的 O(n²) 重排。 */
function scheduleReplyRender() {
  if (state.renderQueued) {
    return;
  }
  state.renderQueued = true;
  requestAnimationFrame(() => {
    state.renderQueued = false;
    if (!state.reply) {
      return;
    }
    const stick = isNearBottom(el.messages);
    state.reply.replaceChildren(renderMarkdown(state.replyText));
    if (stick) {
      scrollThread();
    }
  });
}

function finishReply(failed) {
  clearThinking();
  if (!state.reply) {
    return;
  }
  if (!state.replyText) {
    state.reply.classList.add('is-muted');
    state.reply.textContent = failed ? '（本轮没有返回文本）' : '（本轮只调用了工具，没有文本输出）';
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

function record(event, { kind, tone, status, title, detail, foot, actions, callId }) {
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
    path.title = file.path;
    li.append(status, path);
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
  const lines = String(text).split('\n');
  const limit = 4000;
  const fragment = document.createDocumentFragment();
  lines.slice(0, limit).forEach((line) => {
    const span = document.createElement('span');
    span.className = 'line ' + diffClass(line);
    span.textContent = line.length ? line : ' ';
    fragment.append(span);
  });
  if (lines.length > limit) {
    const more = document.createElement('span');
    more.className = 'line meta';
    more.textContent = `… 还有 ${lines.length - limit} 行未显示`;
    fragment.append(more);
  }
  el.changeDiff.append(fragment);
}

function diffClass(line) {
  if (line.startsWith('@@')) {
    return 'hunk';
  }
  if (line.startsWith('+++') || line.startsWith('---') || line.startsWith('diff --git') || line.startsWith('index ')) {
    return 'meta';
  }
  if (line.startsWith('+')) {
    return 'add';
  }
  if (line.startsWith('-')) {
    return 'del';
  }
  return 'ctx';
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
  el.input.addEventListener('input', autoGrow);
  el.input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
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
  if (!content || !state.sessionId || state.running) {
    return;
  }

  appendUserMessage(content);
  state.pendingInputs.push(content);
  renameSessionIfDefault(content);
  el.input.value = '';
  autoGrow();
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
    showNotice('运行刚开始，请稍后再试。');
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
  if (mod && event.key.toLowerCase() === 'b') {
    event.preventDefault();
    toggleActivity();
    return;
  }
  if (event.key === 'Escape') {
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

function showNotice(message) {
  el.noticeText.textContent = message;
  el.notice.hidden = false;
}

function hideNotice() {
  el.notice.hidden = true;
}

function resetView() {
  clearThinking();
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
