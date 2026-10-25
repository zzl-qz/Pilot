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

const statusEl = document.querySelector('#status');
const timeline = document.querySelector('#timeline');
const messages = document.querySelector('#messages');
const form = document.querySelector('#chat-form');
const input = document.querySelector('#message');
const sendButton = form.querySelector('button');

let sessionId = null;
let source = null;
let currentReply = null;

async function openSession() {
  const response = await fetch('/api/sessions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: '浏览器会话' })
  });
  if (!response.ok) {
    throw new Error('创建会话失败：HTTP ' + response.status);
  }
  const session = await response.json();
  sessionId = session.sessionId;
  console.log('[session]', session);
  sendButton.disabled = false;
  connect();
}

function connect() {
  source = new EventSource(`/api/sessions/${encodeURIComponent(sessionId)}/events`);
  for (const type of EVENT_TYPES) {
    source.addEventListener(type, (message) => handleEvent(JSON.parse(message.data)));
  }
  source.onopen = () => setStatus(`会话 ${sessionId} · SSE 已连接`);
  source.onerror = () => setStatus(`会话 ${sessionId} · 连接中断，浏览器将自动重连`);
}

function handleEvent(event) {
  console.log('[SSE]', event.type, event);
  appendTimeline(event);

  if (event.type === 'run.started') {
    currentReply = makeMessage('assistant', '');
    messages.append(currentReply);
    messages.scrollTop = messages.scrollHeight;
    return;
  }
  if (event.type === 'assistant.delta') {
    if (!currentReply) {
      currentReply = makeMessage('assistant', '');
      messages.append(currentReply);
    }
    currentReply.textContent += event.payload.text;
    messages.scrollTop = messages.scrollHeight;
    return;
  }
  if (event.type === 'run.completed' || event.type === 'run.failed') {
    currentReply = null;
    sendButton.disabled = false;
  }
}

function appendTimeline(event) {
  const item = document.createElement('li');
  item.className = 'event ' + event.type.replace('.', '-');
  item.append(
    span('seq', '#' + event.sequence),
    span('type', event.type),
    span('detail', describe(event.payload, event.type))
  );
  timeline.append(item);
  timeline.scrollTop = timeline.scrollHeight;
}

function describe(payload, type) {
  switch (type) {
    case 'run.started': return payload.input;
    case 'assistant.delta': return payload.text;
    case 'tool.started': return `${payload.name} · ${payload.summary}`;
    case 'tool.completed': return `${payload.callId} · ${payload.resultSummary} · ${payload.latencyMs}ms`;
    case 'approval.required': return `${payload.name} · ${payload.argsSummary}`;
    case 'approval.resolved': return `${payload.callId} · ${payload.decision}`;
    case 'run.failed': return `${payload.code} · ${payload.message}`;
    case 'run.completed': return `${payload.summary}（${payload.changedFiles.length} 个文件）`;
    default: return JSON.stringify(payload);
  }
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const content = input.value.trim();
  if (!content || !sessionId) {
    return;
  }
  messages.append(makeMessage('user', content));
  messages.scrollTop = messages.scrollHeight;
  input.value = '';
  sendButton.disabled = true;

  try {
    const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content })
    });
    if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    }
    console.log('[run]', await response.json());
  } catch (error) {
    sendButton.disabled = false;
    setStatus('发送失败：' + error.message);
  }
});

function makeMessage(role, text) {
  const item = document.createElement('p');
  item.className = 'message ' + role;
  item.textContent = text;
  return item;
}

function span(className, text) {
  const element = document.createElement('span');
  element.className = className;
  element.textContent = text;
  return element;
}

function setStatus(text) {
  statusEl.textContent = text;
}

openSession().catch((error) => {
  setStatus(error.message);
  console.error(error);
});
