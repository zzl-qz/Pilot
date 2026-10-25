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
  sessionChip: document.querySelector('#session-chip'),
  connection: document.querySelector('#connection'),
  connectionText: document.querySelector('#connection-text'),
  newSession: document.querySelector('#new-session'),
  sessionList: document.querySelector('#session-list'),
  sessionEmpty: document.querySelector('#session-empty'),
  crumbTitle: document.querySelector('#crumb-title'),
  notice: document.querySelector('#notice'),
  runState: document.querySelector('#run-state'),
  messages: document.querySelector('#messages'),
  emptyState: document.querySelector('#empty-state'),
  prompts: document.querySelector('#prompts'),
  approvalDock: document.querySelector('#approval-dock'),
  toggleActivity: document.querySelector('#toggle-activity'),
  activity: document.querySelector('#activity'),
  filters: document.querySelector('#filters'),
  timeline: document.querySelector('#timeline'),
  timelineEmpty: document.querySelector('#timeline-empty'),
  composer: document.querySelector('#composer'),
  input: document.querySelector('#input'),
  send: document.querySelector('#send'),
  palette: document.querySelector('#palette'),
  paletteInput: document.querySelector('#palette-input'),
  paletteList: document.querySelector('#palette-list')
};

const state = {
  sessionId: null,
  sessionTitle: '',
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
  paletteIndex: 0,
  paletteItems: []
};

const COMMANDS = [
  { label: '新任务', hint: 'Ctrl N', run: () => startSession() },
  { label: '切换活动面板', hint: 'Ctrl B', run: () => toggleActivity() },
  { label: '复制会话 ID', hint: '', run: copySessionId },
  { label: '清空当前视图', hint: '', run: () => resetView() },
  { label: '重新连接事件流', hint: '', run: () => reconnect() }
];

init();

function init() {
  el.newSession.addEventListener('click', () => startSession());
  el.sidebarToggle.addEventListener('click', toggleSidebar);
  el.scrim.addEventListener('click', closeOverlays);
  el.toggleActivity.addEventListener('click', toggleActivity);
  el.filters.addEventListener('click', onFilterClick);
  el.prompts.addEventListener('click', onPromptClick);
  el.paletteInput.addEventListener('input', () => renderPalette(el.paletteInput.value));
  el.paletteInput.addEventListener('keydown', onPaletteKeydown);
  el.palette.addEventListener('click', (event) => {
    if (event.target === el.palette) closePalette();
  });
  document.addEventListener('keydown', onGlobalKeydown);
  window.addEventListener('resize', syncScrim);
  bindComposer();
  loadHealth();
  startSession();
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
    el.modeBadge.textContent = real ? `真实模型 · ${health.model}` : 'Mock 剧本模式';
    el.modeBadge.className = 'mode-chip ' + (real ? 'is-real' : 'is-mock');
    el.modeBadge.title = real
      ? `可用工具：${(health.tools || []).join('、') || '无'}`
      : '未调用大模型，事件由内置剧本产生';

    if (health.workspace) {
      el.workspaceChip.textContent = health.workspace;
      el.workspaceChip.title = health.workspace;
      el.workspaceChip.hidden = false;
    }

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

async function startSession() {
  closeStream();
  resetView();
  setComposerEnabled(false);
  setConnection('pending', '创建会话');
  setRunState('idle', '空闲');

  try {
    const response = await fetch('/api/sessions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: '新任务' })
    });
    if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    }
    const session = await response.json();
    const title = session.title || '新任务';
    state.sessions.unshift({ id: session.sessionId, title, createdAt: session.createdAt });
    activate(session.sessionId, title);
    renderSessions();
  } catch (error) {
    setConnection('error', '创建失败');
    showNotice('创建会话失败：' + error.message);
    console.error(error);
  }
}

function activate(sessionId, title) {
  closeStream();
  state.sessionId = sessionId;
  state.sessionTitle = title || '新任务';
  state.pendingInputs = [];
  el.crumbTitle.textContent = state.sessionTitle;
  el.sessionChip.textContent = sessionId.slice(0, 8);
  el.sessionChip.title = sessionId;
  el.sessionChip.hidden = false;
  renderSessions();
  setConnection('pending', '连接中');
  connect(0);
  setComposerEnabled(true);
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
  activate(sessionId, found.title);
  closeSidebar();
}

function renderSessions() {
  el.sessionList.replaceChildren();
  for (const session of state.sessions) {
    const item = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'session-item' + (session.id === state.sessionId ? ' is-active' : '');
    button.title = session.title;
    button.addEventListener('click', () => activateSession(session.id));

    const dot = document.createElement('span');
    dot.className = 'dot';
    const text = document.createElement('span');
    text.className = 'session-title';
    text.textContent = session.title;

    button.append(dot, text);
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
      setComposerEnabled(false);
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
      record(event, {
        kind: 'run',
        tone: 'text',
        status: '增量',
        title: null,
        detail: preview(event.payload.text, 90)
      });
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
      break;
    }

    case 'run.failed': {
      state.running = false;
      setRunState('error', '失败');
      setComposerEnabled(true);
      clearThinking();
      finishReply(true);
      record(event, {
        kind: 'run',
        tone: 'error',
        status: event.payload.retryable ? '可重试' : '失败',
        title: `运行失败 · ${event.payload.code}`,
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
  state.reply.replaceChildren(renderRich(state.replyText));
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

function record(event, { kind, tone, status, title, detail, foot, actions, callId }) {
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
  setComposerEnabled(false);
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
  } catch (error) {
    state.running = false;
    state.pendingInputs.pop();
    setComposerEnabled(true);
    setRunState('error', '发送失败');
    showNotice('发送失败：' + error.message);
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
    startSession();
    return;
  }
  if (mod && event.key.toLowerCase() === 'b') {
    event.preventDefault();
    toggleActivity();
    return;
  }
  if (event.key === 'Escape') {
    if (!el.palette.hidden) {
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
  el.send.disabled = !enabled;
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
  el.notice.textContent = message;
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
  el.messages.replaceChildren(el.emptyState);
  el.emptyState.hidden = false;
  el.messages.classList.add('is-empty');
  el.timeline.replaceChildren();
  el.timelineEmpty.hidden = false;
  state.activityCount = 0;
  el.toggleActivity.title = '活动面板 (Ctrl B)';
  renderApprovalDock();
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

function renderRich(text) {
  const fragment = document.createDocumentFragment();
  const chunks = String(text).split('```');
  chunks.forEach((chunk, index) => {
    if (index % 2 === 1) {
      const lines = chunk.replace(/^\n/, '').split('\n');
      if (lines.length > 1 && /^[\w+#.-]{0,24}$/.test(lines[0].trim())) {
        lines.shift();
      }
      const pre = document.createElement('pre');
      const code = document.createElement('code');
      code.textContent = lines.join('\n').replace(/\n+$/, '');
      pre.append(code);
      fragment.append(pre);
    } else if (chunk.trim()) {
      fragment.append(inlineNodes(chunk));
    }
  });
  return fragment;
}

function inlineNodes(chunk) {
  const paragraph = document.createElement('p');
  const pattern = /(\*\*[^*]+\*\*|`[^`]+`)/g;
  let last = 0;
  let match;
  while ((match = pattern.exec(chunk)) !== null) {
    if (match.index > last) {
      paragraph.append(document.createTextNode(chunk.slice(last, match.index)));
    }
    const token = match[1];
    if (token.startsWith('**')) {
      const strong = document.createElement('strong');
      strong.textContent = token.slice(2, -2);
      paragraph.append(strong);
    } else {
      const code = document.createElement('code');
      code.textContent = token.slice(1, -1);
      paragraph.append(code);
    }
    last = match.index + token.length;
  }
  if (last < chunk.length) {
    paragraph.append(document.createTextNode(chunk.slice(last)));
  }
  return paragraph;
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
