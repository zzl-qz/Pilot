/**
 * 侧边对话（Side chat）的纯逻辑：标签页持久化、内嵌页判定、引用投递。
 *
 * <p>旧版每个 Side chat 都是「同一份前端 + ?sideChat=1&session=<id>」的 iframe：
 * 内嵌页收起侧栏与审阅面板，只显示那一个会话；父窗口用 postMessage 把选中的文本递过去。
 * 这里把同一套做法搬到 React 上，只把 DOM 操作换成查询 iframe + postMessage。
 */

const STORAGE_KEY = 'pilot.side-chats';
const QUERY_KEY = 'sideChat';

/** 内存里的待投递引用与已就绪帧：iframe 还没加载完时先排队。 */
const pendingQuotes = new Map();
const readyFrames = new Set();

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
        title: item.title || 'Side chat',
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

/** iframe 地址：同一份前端，带上侧边对话参数（与旧版一致）。 */
export function sideChatFrameSrc(sessionId) {
  return `/?${QUERY_KEY}=1&session=${encodeURIComponent(sessionId)}`;
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

function frameSelector(sessionId) {
  return `iframe[data-side-chat-frame="${String(sessionId).replace(/["\\]/g, '\\$&')}"]`;
}

function postQuote(frame, sessionId, text) {
  frame?.contentWindow?.postMessage({ type: 'pilot:side-chat-quote', sessionId, text }, window.location.origin);
}

export function markSideChatReady(sessionId) {
  readyFrames.add(sessionId);
}

export function forgetSideChat(sessionId) {
  readyFrames.delete(sessionId);
  pendingQuotes.delete(sessionId);
}

export function queueSideChatQuote(sessionId, text) {
  const list = pendingQuotes.get(sessionId) || [];
  list.push(text);
  pendingQuotes.set(sessionId, list);
}

/**
 * 尝试投递该会话排队的引用。
 *
 * @returns {boolean} 帧还没就绪时返回 false，引用继续留在队列里，等 ready 消息再补投
 */
export function deliverSideChatQuotes(sessionId) {
  if (!readyFrames.has(sessionId)) {
    return false;
  }
  const frame = document.querySelector(frameSelector(sessionId));
  if (!frame) {
    return false;
  }
  const quotes = pendingQuotes.get(sessionId) || [];
  pendingQuotes.delete(sessionId);
  quotes.forEach((text) => postQuote(frame, sessionId, text));
  return true;
}
