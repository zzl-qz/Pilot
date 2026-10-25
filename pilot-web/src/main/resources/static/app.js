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
  modeBadge: document.querySelector('#mode-badge'),
  sessionChip: document.querySelector('#session-chip'),
  connection: document.querySelector('#connection'),
  connectionText: document.querySelector('#connection-text'),
  newSession: document.querySelector('#new-session'),
  notice: document.querySelector('#notice'),
  runState: document.querySelector('#run-state'),
  messages: document.querySelector('#messages'),
  emptyState: document.querySelector('#empty-state'),
  timeline: document.querySelector('#timeline'),
  timelineEmpty: document.querySelector('#timeline-empty'),
  composer: document.querySelector('#composer'),
  input: document.querySelector('#input'),
  send: document.querySelector('#send')
};

const state = {
  sessionId: null,
  source: null,
  running: false,
  reply: null,
  replyText: '',
  toolCards: new Map()
};

el.newSession.addEventListener('click', () => startSession());
bindComposer();
loadHealth();
startSession();

async function loadHealth() {
  try {
    const response = await fetch('/api/health');
    if (!response.ok) {
      return;
    }
    const health = await response.json();
    const real = health.agentMode === 'real';
    el.modeBadge.textContent = real ? `真实模型 · ${health.model}` : 'Mock 模式';
    el.modeBadge.className = 'badge ' + (real ? 'badge-ok' : 'badge-warn');
    el.modeBadge.title = real
      ? `可用工具：${(health.tools || []).join('、') || '无'}`
      : '未调用大模型，事件由内置剧本产生';
    if (real) {
      hideNotice();
    } else {
      showNotice('当前为 Mock 模式：事件由内置剧本产生，没有调用大模型。设置 PILOT_AGENT_MODE=real 和 PILOT_MODEL_API_KEY 后重启即可接入真实模型。');
    }
  } catch (error) {
    console.warn('[health]', error);
  }
}

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
      body: JSON.stringify({ title: '浏览器会话' })
    });
    if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    }
    const session = await response.json();
    state.sessionId = session.sessionId;
    el.sessionChip.textContent = session.sessionId;
    el.sessionChip.title = session.sessionId;
    el.sessionChip.hidden = false;

    setConnection('pending', '连接中');
    connect();
    setComposerEnabled(true);
  } catch (error) {
    setConnection('error', '创建失败');
    showNotice('创建会话失败：' + error.message);
    console.error(error);
  }
}

function connect() {
  const source = new EventSource(`/api/sessions/${encodeURIComponent(state.sessionId)}/events`);
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

function closeStream() {
  if (state.source) {
    state.source.close();
    state.source = null;
  }
}

function handleEvent(event) {
  console.log('[SSE]', event.type, event);
  hideEmptyState();

  switch (event.type) {
    case 'run.started':
      state.running = true;
      setRunState('running', '运行中');
      setComposerEnabled(false);
      startReply();
      addCard(event, {
        tone: 'run',
        status: '开始',
        title: '运行开始',
        detail: event.payload.input
      });
      break;

    case 'assistant.delta':
      appendDelta(event.payload.text);
      addCard(event, {
        tone: 'text',
        status: '增量',
        title: null,
        detail: preview(event.payload.text, 90)
      });
      break;

    case 'tool.started': {
      const card = addCard(event, {
        tone: 'tool',
        status: '运行中',
        title: `${event.payload.name} · ${event.payload.summary}`,
        detail: null
      });
      state.toolCards.set(event.payload.callId, card);
      break;
    }

    case 'tool.completed': {
      const card = state.toolCards.get(event.payload.callId);
      const failed = String(event.payload.resultSummary || '').startsWith('ERROR');
      if (card) {
        state.toolCards.delete(event.payload.callId);
        updateCardStatus(card, failed ? '失败' : '完成', failed ? 'error' : 'ok');
        appendCardFoot(card, `#${event.sequence} · ${event.payload.latencyMs} ms · ${event.payload.resultSummary}`);
      } else {
        addCard(event, {
          tone: failed ? 'error' : 'ok',
          status: failed ? '失败' : '完成',
          title: event.payload.callId,
          detail: event.payload.resultSummary
        });
      }
      break;
    }

    case 'approval.required':
      addCard(event, {
        tone: 'warn',
        status: '待审批',
        title: `${event.payload.name} · ${event.payload.argsSummary}`,
        detail: `callId ${event.payload.callId}`
      });
      break;

    case 'approval.resolved':
      addCard(event, {
        tone: event.payload.decision === 'APPROVED' ? 'ok' : 'error',
        status: event.payload.decision === 'APPROVED' ? '已批准' : '已拒绝',
        title: event.payload.callId,
        detail: null
      });
      break;

    case 'run.completed': {
      state.running = false;
      setRunState('done', '已完成');
      setComposerEnabled(true);
      finishReply(false);
      const card = addCard(event, {
        tone: 'ok',
        status: '完成',
        title: '运行完成',
        detail: event.payload.summary
      });
      const extras = [];
      if (event.payload.changedFiles && event.payload.changedFiles.length) {
        extras.push('变更：' + event.payload.changedFiles.join('、'));
      }
      if (event.payload.checks && event.payload.checks.length) {
        extras.push('检查：' + event.payload.checks.join('、'));
      }
      if (extras.length) {
        appendCardFoot(card, extras.join(' · '));
      }
      break;
    }

    case 'run.failed':
      state.running = false;
      setRunState('error', '失败');
      setComposerEnabled(true);
      finishReply(true);
      addCard(event, {
        tone: 'error',
        status: event.payload.retryable ? '可重试' : '失败',
        title: `运行失败 · ${event.payload.code}`,
        detail: event.payload.message
      });
      break;
  }

  scrollToLatest();
}

function startReply() {
  const wrapper = document.createElement('article');
  wrapper.className = 'msg msg-assistant';

  const meta = document.createElement('div');
  meta.className = 'msg-meta';
  meta.textContent = 'Pilot';

  const bubble = document.createElement('div');
  bubble.className = 'bubble typing';
  for (let i = 0; i < 3; i++) {
    bubble.append(document.createElement('span'));
  }

  wrapper.append(meta, bubble);
  el.messages.append(wrapper);
  state.reply = bubble;
  state.replyText = '';
}

function appendDelta(text) {
  if (!state.reply) {
    startReply();
  }
  if (!state.replyText) {
    state.reply.classList.remove('typing');
    state.reply.textContent = '';
  }
  state.replyText += text;
  state.reply.textContent = state.replyText;
}

function finishReply(failed) {
  if (!state.reply) {
    return;
  }
  if (!state.replyText) {
    state.reply.classList.remove('typing');
    state.reply.classList.add('is-muted');
    state.reply.textContent = failed ? '（本轮没有返回文本）' : '（本轮只调用了工具，没有文本输出）';
  }
  state.reply = null;
  state.replyText = '';
}

function appendUserMessage(text) {
  const wrapper = document.createElement('article');
  wrapper.className = 'msg msg-user';

  const meta = document.createElement('div');
  meta.className = 'msg-meta';
  meta.textContent = '你';

  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.textContent = text;

  wrapper.append(meta, bubble);
  el.messages.append(wrapper);
  hideEmptyState();
}

function addCard(event, { tone, status, title, detail }) {
  const item = document.createElement('li');
  item.className = 'event tone-' + tone;

  const head = document.createElement('div');
  head.className = 'event-head';
  const statusEl = text('span', 'event-status', status);
  head.append(
    text('span', 'event-seq', '#' + event.sequence),
    text('span', 'event-type', event.type),
    statusEl,
    text('span', 'event-time', formatTime(event.timestamp))
  );
  item.append(head);

  if (title) {
    item.append(text('p', 'event-title', title));
  }
  if (detail) {
    item.append(text('p', 'event-detail', detail));
  }

  el.timeline.append(item);
  el.timelineEmpty.hidden = true;
  return { item, statusEl };
}

function updateCardStatus(card, label, kind) {
  card.statusEl.textContent = label;
  card.statusEl.className = 'event-status';
  card.item.classList.remove('tone-tool');
  card.item.classList.add(kind === 'error' ? 'tone-error' : 'tone-ok');
}

function appendCardFoot(card, content) {
  let foot = card.item.querySelector('.event-foot');
  if (!foot) {
    foot = text('p', 'event-foot', '');
    card.item.append(foot);
  }
  foot.textContent = content;
}

function setComposerEnabled(enabled) {
  el.send.disabled = !enabled;
}

function setConnection(kind, label) {
  const style = kind === 'ok' ? 'badge-ok' : kind === 'error' ? 'badge-error' : 'badge-muted';
  el.connection.className = 'badge ' + style;
  el.connectionText.textContent = label;
}

function setRunState(kind, label) {
  el.runState.className = 'state state-' + kind;
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
  state.toolCards.clear();
  state.reply = null;
  state.replyText = '';
  state.running = false;
  el.messages.replaceChildren(el.emptyState);
  el.emptyState.hidden = false;
  el.messages.classList.add('is-empty');
  el.timeline.replaceChildren();
  el.timelineEmpty.hidden = false;
}

function hideEmptyState() {
  if (!el.messages.classList.contains('is-empty')) {
    return;
  }
  el.messages.classList.remove('is-empty');
  el.emptyState.hidden = true;
}

function scrollToLatest() {
  el.messages.scrollTop = el.messages.scrollHeight;
  el.timeline.scrollTop = el.timeline.scrollHeight;
}

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

async function sendMessage(event) {
  event.preventDefault();
  const content = el.input.value.trim();
  if (!content || !state.sessionId || state.running) {
    return;
  }

  appendUserMessage(content);
  el.input.value = '';
  autoGrow();
  state.running = true;
  setComposerEnabled(false);
  setRunState('running', '发送中');
  scrollToLatest();

  try {
    const response = await fetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content })
    });
    if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    }
    console.log('[run]', await response.json());
  } catch (error) {
    state.running = false;
    setComposerEnabled(true);
    setRunState('error', '发送失败');
    showNotice('发送失败：' + error.message);
  }
}

function autoGrow() {
  el.input.style.height = 'auto';
  el.input.style.height = Math.min(el.input.scrollHeight, 148) + 'px';
}

function text(tag, className, content) {
  const node = document.createElement(tag);
  node.className = className;
  node.textContent = content == null ? '' : content;
  return node;
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
