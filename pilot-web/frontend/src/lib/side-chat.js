/**
 * 侧边对话的纯逻辑：标签页持久化、旧链接的内嵌页判定、选中文本的引用格式。
 * 引用直接交给当前页的侧边对话，不再经过 iframe。
 */

const STORAGE_KEY = 'pilot.side-chats';
const QUERY_KEY = 'sideChat';

/**
 * 默认存储：拿不到 localStorage（隐私模式、Node 测试）时降级成 null，
 * 让上层安静地按「没有侧边对话」处理，而不是在模块加载阶段就炸掉。
 */
function defaultStorage() {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null;
  } catch {
    return null;
  }
}

/** 读持久化的标签页；坏数据直接当没有，不能让侧栏被一段脏 JSON 拖死。 */
export function readSideChats(storage = defaultStorage()) {
  if (!storage || typeof storage.getItem !== 'function') {
    return { items: [], activeId: null };
  }
  try {
    const saved = JSON.parse(storage.getItem(STORAGE_KEY) || '{}');
    const items = (Array.isArray(saved.items) ? saved.items : [])
      .filter((item) => item && item.id && item.workspace)
      .map((item) => ({
        id: item.id,
        title: item.title || '侧边对话',
        workspace: item.workspace,
        projectId: item.projectId || null
      }));
    const activeId = items.some((item) => item.id === saved.activeId) ? saved.activeId : (items[0]?.id || null);
    return { items, activeId };
  } catch {
    return { items: [], activeId: null };
  }
}

export function writeSideChats(state, storage = defaultStorage()) {
  if (!storage || typeof storage.setItem !== 'function') {
    return;
  }
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify({
      activeId: state.activeId,
      items: (state.items || []).map(({ id, title, workspace, projectId }) => ({ id, title, workspace, projectId }))
    }));
  } catch {
    // 存不下只影响下次启动
  }
}

/**
 * 内嵌页判定：带 sideChat=1 且**确实在 iframe 里**时返回要展示的会话 ID。
 *
 * <p>必须带上 iframe 判断：这个链接会出现在历史记录、复制粘贴里，
 * 用户在顶层窗口直接打开它时应当还是完整界面，而不是一个没有侧栏和审阅面板的残缺页。
 */
export function readEmbeddedSession(search) {
  // 测试环境没有 window.self/window.top，两边都是 undefined 时判定为「不在 iframe 里」
  if (typeof window === 'undefined' || window.self === window.top) {
    return null;
  }
  const raw = search ?? (window.location?.search || '');
  const params = new URLSearchParams(raw || '');
  if (params.get(QUERY_KEY) !== '1') {
    return null;
  }
  return params.get('session') || null;
}

/**
 * 侧边栏已经列出对话时，侧片自己的顶栏导航就不再显示。
 * 侧边栏收起后，顶栏才出现，否则没法切换。
 */
export function showSidePieceNav(sidebarOpen) {
  return !sidebarOpen;
}

/** 长消息收成可展开的侧片。短消息保持原样。 */
export function messagePeek(text, maxLines = 4) {
  const raw = String(text || '').replace(/\s+$/, '');
  const lines = raw.split(/\r?\n/);
  const clipped = lines.length > maxLines || raw.length > 280;
  const preview = (lines.length > maxLines ? lines.slice(0, maxLines) : lines).join('\n');
  return {
    preview: clipped && lines.length <= maxLines && raw.length > 280 ? `${raw.slice(0, 280)}…` : preview,
    collapsed: clipped
  };
}

function sameText(left, right) {
  return String(left || '').replace(/\s+/g, ' ').trim() === String(right || '').replace(/\s+/g, ' ').trim();
}

/**
 * 侧边对话只留人话和回复。工具、计划和用量都不进这张简易对话框。
 * user.message 与随后的 run.started 会带同一句输入，只留一次。
 */
export function reduceSideChat(state, event) {
  const current = {
    messages: Array.isArray(state?.messages) ? state.messages.slice() : [],
    live: state?.live || ''
  };
  const type = event?.type;
  const payload = event?.payload || {};
  if (type === 'user.message' || type === 'run.started') {
    const text = String(payload.input || payload.text || '');
    if (!text.trim()) {
      return current;
    }
    const last = current.messages[current.messages.length - 1];
    if (last?.role === 'user' && sameText(last.text, text)) {
      return { ...current, live: '' };
    }
    current.messages.push({ role: 'user', text });
    return { messages: current.messages, live: '' };
  }
  if (type === 'assistant.delta') {
    const text = payload.text || '';
    const last = current.messages[current.messages.length - 1];
    if (last?.role === 'assistant') {
      current.messages[current.messages.length - 1] = { role: 'assistant', text: last.text + text };
    } else if (text) {
      current.messages.push({ role: 'assistant', text });
    }
    return { messages: current.messages, live: '' };
  }
  if (type === 'assistant.preview') {
    return { messages: current.messages, live: current.live + (payload.text || '') };
  }
  if (type === 'run.completed' || type === 'run.failed' || type === 'run.cancelled') {
    return { messages: current.messages, live: '' };
  }
  return current;
}

export function simplifyTranscript(events) {
  return (events || []).reduce((state, event) => reduceSideChat(state, event), { messages: [], live: '' }).messages;
}

export function lastEventSequence(events) {
  return (events || []).reduce((max, event) => Math.max(max, Number(event?.sequence) || 0), 0);
}

/** 被选中的文本 → markdown 引用块（多行逐行加 >）。空白内容返回空串。 */
export function quoteMarkdown(text) {
  const raw = String(text || '').replace(/\s+$/, '');
  if (!raw.trim()) {
    return '';
  }
  return raw
    .split(/\r?\n/)
    .map((line) => `> ${line}`)
    .join('\n');
}

/** 追加引用到输入框：与已有内容之间留一个空行，结尾换行方便继续写。 */
export function appendQuote(getText, appendText, text) {
  const quote = quoteMarkdown(text);
  if (!quote) {
    return false;
  }
  const current = getText() || '';
  const separator = current ? (current.endsWith('\n') ? '\n' : '\n\n') : '';
  appendText(`${separator}${quote}\n\n`);
  return true;
}

