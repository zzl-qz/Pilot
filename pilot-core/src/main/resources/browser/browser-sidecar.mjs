// Pilot 浏览器 sidecar：由 Java 通过 stdin/stdout 的 JSON Lines 驱动一个 Playwright Chromium。
//
// 协议（一行一个 JSON）：
//   请求  {"id": 1, "session": "s1", "op": "navigate", ...参数}
//   响应  {"id": 1, "ok": true, "text": "...", "screenshot": "/abs/a.png"} 或 {"id": 1, "ok": false, "error": "..."}
//   事件  {"event": "...", "session": "s1", ...}（无 id；Java 端按 event 字段路由，见事件通道）
// stdout 只放协议行；任何日志一律走 stderr。
//
// 隔离：整个进程共享一个 Chromium，每个 Pilot 会话一个独立 BrowserContext（cookie / storage 互不可见）。
// 导航只允许回环地址（localhost / 127.0.0.1 / [::1]）：本工具用来验证本机 Web 应用，不是通用上网。
//
// 环境变量（由 Java 端注入，未设时保持保守默认）：
//   PILOT_BROWSER_HEADLESS        'false' 才有头（有窗口）；默认 true（无头）。
//   PILOT_BROWSER_PREFER_BUNDLED  'false' 时系统 Chrome/Edge 优先于内置缓存；默认 true。
//   PLAYWRIGHT_BROWSERS_PATH      内置缓存目录（chromium.executablePath() 由此解析）。

import { createRequire } from 'node:module';
import { existsSync, statSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { createInterface } from 'node:readline';

// 把 console.* 全部改道到 stderr，避免污染协议通道。
for (const method of ['log', 'info', 'warn', 'debug']) {
  console[method] = (...args) => process.stderr.write(args.map(String).join(' ') + '\n');
}

const require = createRequire(import.meta.url);
const MAX_LOG_ENTRIES = 300;
const MAX_SNAPSHOT_CHARS = 24000;
const MAX_SNAPSHOT_NODES = 600;
const ACTION_TIMEOUT_MS = 8000;
const NAVIGATION_TIMEOUT_MS = 30000;
const MAX_SCREENSHOT_BYTES = 4 * 1024 * 1024;

let playwright;
let browserPromise = null;
const sessions = new Map();
const queues = new Map();
// 前端订阅过镜像的会话：浏览器尚未启动时先登记，会话建立时自动开始推帧。
const mirrorRequested = new Set();
// 镜像帧限流：前端只需看清页面，12fps 足够，视频页也不至于冲爆 SSE。
const MIRROR_MIN_FRAME_MS = 80;

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function loopbackUrl(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    return false;
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return false;
  const host = url.hostname.toLowerCase();
  return host === 'localhost' || host.endsWith('.localhost') || host === '127.0.0.1'
    || host === '[::1]' || host === '::1' || /^127\.\d+\.\d+\.\d+$/.test(host);
}

function loadPlaywright() {
  if (!playwright) {
    playwright = require('playwright-core');
  }
  return playwright;
}

function candidateExecutables() {
  const list = [];
  if (process.env.PILOT_BROWSER_PATH) list.push(process.env.PILOT_BROWSER_PATH);
  const bundled = [];
  try {
    bundled.push(loadPlaywright().chromium.executablePath());
  } catch {
    // 没装 Playwright 自带 Chromium 就继续找系统浏览器
  }
  const system = [];
  if (process.platform === 'darwin') {
    system.push('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
      '/Applications/Chromium.app/Contents/MacOS/Chromium',
      '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser');
  } else if (process.platform === 'win32') {
    const roots = [process.env['PROGRAMFILES'], process.env['PROGRAMFILES(X86)'], process.env['LOCALAPPDATA']]
      .filter(Boolean);
    for (const root of roots) {
      system.push(`${root}\\Google\\Chrome\\Application\\chrome.exe`,
        `${root}\\Microsoft\\Edge\\Application\\msedge.exe`,
        `${root}\\BraveSoftware\\Brave-Browser\\Application\\brave.exe`);
    }
  } else {
    system.push('/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium',
      '/usr/bin/chromium-browser', '/snap/bin/chromium', '/usr/bin/microsoft-edge');
  }
  // 只认内置缓存（版本配套、行为跨机器一致）：优先内置时不再回退系统 Chrome / Edge，
  // 内置缺失就报「没有找到可用的…」，由 Java 端触发下载。用户要救急可以设
  // PILOT_BROWSER_PREFER_BUNDLED=false 显式放行系统浏览器，或 PILOT_BROWSER_PATH 直接指路径。
  const preferBundled = (process.env.PILOT_BROWSER_PREFER_BUNDLED ?? 'true') !== 'false';
  list.push(...(preferBundled ? bundled : [...system, ...bundled]));
  return list.filter((path) => {
    try {
      return existsSync(path) && statSync(path).isFile();
    } catch {
      return false;
    }
  });
}

function launchArgs() {
  const extra = (process.env.PILOT_BROWSER_ARGS || '').split(/\s+/).filter(Boolean);
  return ['--disable-dev-shm-usage', '--no-first-run', '--no-default-browser-check', ...extra];
}

function headlessMode() {
  // 只有显式收到 'false' 才有头：环境变量缺失时保持无头（老行为），避免误开窗口。
  return (process.env.PILOT_BROWSER_HEADLESS ?? 'true') !== 'false';
}

async function launchBrowser() {
  const { chromium } = loadPlaywright();
  const executables = candidateExecutables();
  if (executables.length === 0) {
    let expected = '';
    try {
      expected = loadPlaywright().chromium.executablePath();
    } catch {
      // playwright-core 不可用时不强求给出期望路径
    }
    throw new Error(`没有找到可用的内置 Chromium${expected ? `（期望位置：${expected}）` : ''}。首次启动会自动下载完整版 Chromium（约 400MB）；也可以用 PILOT_BROWSER_PATH 指向已有的浏览器可执行文件。`);
  }
  const headless = headlessMode();
  const failures = [];
  for (const executablePath of executables) {
    try {
      const browser = await chromium.launch({ executablePath, headless, args: launchArgs(), timeout: 20000 });
      browser.__executable = executablePath;
      browser.__headless = headless;
      browser.on('disconnected', () => {
        browserPromise = null;
        for (const session of sessions.values()) session.dead = true;
        sessions.clear();
        send({ event: 'browser-closed', reason: 'disconnected' });
      });
      return browser;
    } catch (error) {
      failures.push(`${executablePath}: ${String(error.message || error).split('\n')[0]}`);
    }
  }
  throw new Error('浏览器启动失败：' + failures.join('；'));
}

function getBrowser() {
  if (!browserPromise) {
    browserPromise = launchBrowser().catch((error) => {
      browserPromise = null;
      throw error;
    });
  }
  return browserPromise;
}

function pushLimited(list, entry) {
  list.push(entry);
  if (list.length > MAX_LOG_ENTRIES) list.shift();
}

function attachPage(session, page) {
  session.page = page;
  page.on('framenavigated', (frame) => {
    if (frame !== page.mainFrame()) return;
    reportNavigation(session, frame.url()).catch(() => {});
  });
  page.on('console', (message) => {
    const location = message.location();
    pushLimited(session.console, {
      type: message.type(),
      text: message.text(),
      at: location && location.url ? `${location.url}:${location.lineNumber}` : ''
    });
  });
  page.on('pageerror', (error) => {
    pushLimited(session.console, { type: 'pageerror', text: String(error.message || error), at: '' });
  });
  page.on('dialog', async (dialog) => {
    pushLimited(session.console, { type: 'dialog', text: `${dialog.type()}: ${dialog.message()}`, at: '' });
    try {
      if (dialog.type() === 'alert' || dialog.type() === 'beforeunload') await dialog.accept();
      else await dialog.dismiss();
    } catch {
      // 对话框已被页面自己处理
    }
  });
  page.on('request', (request) => {
    const entry = {
      method: request.method(),
      url: request.url(),
      type: request.resourceType(),
      status: null,
      failure: null
    };
    request.__entry = entry;
    pushLimited(session.network, entry);
  });
  page.on('response', (response) => {
    const entry = response.request().__entry;
    if (entry) entry.status = response.status();
  });
  page.on('requestfailed', (request) => {
    const entry = request.__entry;
    if (entry) entry.failure = request.failure() ? request.failure().errorText : 'failed';
  });
  // 镜像开着：page 切换（popup/新标签）时把 screencast 跟到新页面上。
  if (session.mirror) ensureScreencast(session).catch(() => {});
}

/*
 * 页面跳转时把 URL 和「能不能前进/后退」一起推给前端——地址栏与按钮状态都靠它，
 * 前端不需要自己猜历史。CDP 会话按页面缓存，创建失败就只报 URL（按钮保持上次状态）。
 */
async function navigationState(session) {
  try {
    const page = session.page;
    if (!page || page.isClosed()) return {};
    if (!session.historyCdp || session.historyCdpPage !== page) {
      session.historyCdp = await session.context.newCDPSession(page);
      session.historyCdpPage = page;
    }
    const history = await session.historyCdp.send('Page.getNavigationHistory');
    return {
      canGoBack: Number(history.currentIndex) > 0,
      canGoForward: Number(history.currentIndex) < history.entries.length - 1
    };
  } catch {
    return {};
  }
}

async function reportNavigation(session, url) {
  const state = await navigationState(session);
  send({ event: 'nav', session: session.id, url, ...state, ts: Date.now() });
}

async function createSession(id) {
  const browser = await getBrowser();
  const context = await browser.newContext({
    viewport: { width: 1280, height: 800 },
    acceptDownloads: false,
    serviceWorkers: 'block'
  });
  context.setDefaultTimeout(ACTION_TIMEOUT_MS);
  context.setDefaultNavigationTimeout(NAVIGATION_TIMEOUT_MS);
  const session = { id, context, page: null, console: [], network: [], dead: false,
    mirror: mirrorRequested.has(id), mirrorWidth: 960, mirrorQuality: 55,
    cdp: null, cdpPage: null, lastFrameAt: 0, historyCdp: null, historyCdpPage: null };
  // 主框架导航只允许回环地址，页面里的链接也不能把它带去外网。
  await context.route('**/*', (route) => {
    const request = route.request();
    if (request.isNavigationRequest() && request.frame() === session.page?.mainFrame()
      && !loopbackUrl(request.url()) && request.url() !== 'about:blank') {
      pushLimited(session.console, { type: 'blocked', text: `已拦截对非本机地址的导航：${request.url()}`, at: '' });
      return route.abort('blockedbyclient');
    }
    return route.continue();
  });
  context.on('page', (page) => attachPage(session, page));
  // attachPage 会在 mirror 开启时自动挂 screencast（含首个页面）。
  attachPage(session, await context.newPage());
  sessions.set(id, session);
  return session;
}

// ---- 镜像（CDP screencast 事件通道）----------------------------------------------------
// 页面变化才产帧，静态页面零流量；ack 背压限速，帧到达后立刻 ack 以免 Chromium 停发。
async function ensureScreencast(session) {
  const page = session.page;
  if (!page || page.isClosed() || session.cdpPage === page) return;
  await stopScreencast(session);
  try {
    const cdp = await session.context.newCDPSession(page);
    session.cdp = cdp;
    session.cdpPage = page;
    cdp.on('Page.screencastFrame', async (frame) => {
      try {
        await cdp.send('Page.screencastFrameAck', { sessionId: frame.sessionId });
      } catch {
        // 会话已关
      }
      if (!session.mirror || session.dead || session.page !== page) return;
      const now = Date.now();
      if (now - session.lastFrameAt < MIRROR_MIN_FRAME_MS) return; // 限流：新帧稍后自然到来
      session.lastFrameAt = now;
      send({
        event: 'mirror-frame',
        session: session.id,
        frame: frame.data,
        w: (frame.metadata && frame.metadata.deviceWidth) || null,
        h: (frame.metadata && frame.metadata.deviceHeight) || null,
        url: page.url(),
        ts: now
      });
    });
    await cdp.send('Page.startScreencast', {
      format: 'jpeg',
      quality: session.mirrorQuality,
      maxWidth: session.mirrorWidth,
      maxHeight: Math.round(session.mirrorWidth * 0.75),
      everyNthFrame: 1
    });
  } catch (error) {
    session.cdp = null;
    session.cdpPage = null;
    process.stderr.write('screencast 启动失败: ' + String(error && error.message || error) + '\n');
  }
}

/**
 * 主动推一帧当前画面。
 *
 * <p>screencast 只在页面有变化时才推帧；改完视口如果页面是静态的，前端就会一直停在
 * 旧尺寸的帧上（看着像「画面没变」）。这里补一帧，让尺寸立刻跟上。
 */
async function publishSnapshot(session) {
  if (!session.mirror || session.dead) return;
  const page = session.page;
  if (!page || page.isClosed()) return;
  try {
    const viewport = page.viewportSize() || { width: 1280, height: 800 };
    const shot = await page.screenshot({ type: 'jpeg', quality: session.mirrorQuality });
    if (!session.mirror || session.dead || session.page !== page) return;
    session.lastFrameAt = Date.now();
    send({
      event: 'mirror-frame',
      session: session.id,
      frame: shot.toString('base64'),
      w: viewport.width,
      h: viewport.height,
      url: page.url(),
      ts: Date.now()
    });
  } catch {
    // 下一帧 screencast 会补上
  }
}

async function stopScreencast(session) {
  const cdp = session.cdp;
  session.cdp = null;
  session.cdpPage = null;
  if (!cdp) return;
  try {
    await cdp.send('Page.stopScreencast');
    await cdp.detach();
  } catch {
    // 已断开
  }
}

// 智能体动作的坐标上报：给前端涟漪高亮用，拿不到坐标就跳过，绝不影响主流程。
async function reportAction(session, kind, args) {
  if (!session.mirror) return;
  try {
    const box = await locatorFor(session, args).boundingBox();
    if (box) {
      send({
        event: 'agent-action',
        session: session.id,
        kind,
        x: Math.round(box.x + box.width / 2),
        y: Math.round(box.y + box.height / 2),
        ref: String(args.ref || args.selector || ''),
        ts: Date.now()
      });
    }
  } catch {
    // 页面在动，坐标拿不到就算了
  }
}

async function session(id) {
  const existing = sessions.get(id);
  if (existing && !existing.dead) return existing;
  return createSession(id);
}

function pageOf(current) {
  if (!current.page || current.page.isClosed()) {
    throw new Error('当前没有打开的页面，请先 navigate。');
  }
  return current.page;
}

// ---- DOM 快照 ----------------------------------------------------------------------------
// 在页面里执行：给可交互元素打上 data-pilot-ref，并输出缩进的可读大纲。
const SNAPSHOT_SCRIPT = `(limits) => {
  const INTERACTIVE_TAGS = new Set(['A', 'BUTTON', 'INPUT', 'SELECT', 'TEXTAREA', 'SUMMARY', 'DETAILS', 'OPTION']);
  const INTERACTIVE_ROLES = new Set(['button', 'link', 'checkbox', 'radio', 'tab', 'menuitem', 'switch',
    'textbox', 'combobox', 'option', 'slider', 'searchbox', 'menuitemcheckbox', 'menuitemradio', 'treeitem']);
  const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'META', 'LINK', 'HEAD', 'SVG', 'PATH']);
  document.querySelectorAll('[data-pilot-ref]').forEach((node) => node.removeAttribute('data-pilot-ref'));
  let counter = 0;
  let nodes = 0;
  let chars = 0;
  let truncated = false;
  const lines = [];
  const visible = (el) => {
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
    const rect = el.getBoundingClientRect();
    return rect.width > 0 || rect.height > 0 || el.tagName === 'INPUT';
  };
  const clean = (value, max = 120) => {
    const text = String(value || '').replace(/\\s+/g, ' ').trim();
    return text.length > max ? text.slice(0, max) + '…' : text;
  };
  const labelOf = (el) => {
    const aria = el.getAttribute('aria-label');
    if (aria) return clean(aria);
    const labelled = el.getAttribute('aria-labelledby');
    if (labelled) {
      const target = document.getElementById(labelled.split(' ')[0]);
      if (target) return clean(target.textContent);
    }
    if (el.labels && el.labels.length) return clean(el.labels[0].textContent);
    if (el.tagName === 'INPUT' && ['button', 'submit', 'reset'].includes(el.type)) return clean(el.value);
    if (el.tagName === 'IMG') return clean(el.alt);
    return clean(el.textContent);
  };
  const interactive = (el) => INTERACTIVE_TAGS.has(el.tagName)
    || INTERACTIVE_ROLES.has((el.getAttribute('role') || '').toLowerCase())
    || el.hasAttribute('onclick') || el.isContentEditable && el.getAttribute('contenteditable') !== 'inherit'
    || (el.tabIndex >= 0 && el.tagName !== 'BODY' && el.tagName !== 'HTML');
  const attrs = (el) => {
    const out = [];
    const tag = el.tagName.toLowerCase();
    if (tag === 'a' && el.getAttribute('href')) out.push('href=' + clean(el.getAttribute('href'), 80));
    if (tag === 'input') {
      out.push('type=' + (el.type || 'text'));
      if (el.type === 'checkbox' || el.type === 'radio') out.push(el.checked ? 'checked' : 'unchecked');
      else if (el.type === 'file') out.push('files=' + (el.files ? el.files.length : 0));
      else if (el.type !== 'password' && el.value) out.push('value=' + JSON.stringify(clean(el.value, 60)));
      if (el.placeholder) out.push('placeholder=' + JSON.stringify(clean(el.placeholder, 60)));
      if (el.accept) out.push('accept=' + clean(el.accept, 40));
      if (el.multiple) out.push('multiple');
    } else if (tag === 'textarea') {
      if (el.value) out.push('value=' + JSON.stringify(clean(el.value, 60)));
      if (el.placeholder) out.push('placeholder=' + JSON.stringify(clean(el.placeholder, 60)));
    } else if (tag === 'select') {
      const chosen = el.selectedOptions && el.selectedOptions[0];
      if (chosen) out.push('selected=' + JSON.stringify(clean(chosen.textContent, 40)));
      out.push('options=' + JSON.stringify(Array.from(el.options).slice(0, 12).map((o) => clean(o.textContent, 30))));
    }
    if (el.disabled) out.push('disabled');
    if (el.getAttribute('aria-expanded')) out.push('expanded=' + el.getAttribute('aria-expanded'));
    if (el.getAttribute('aria-pressed')) out.push('pressed=' + el.getAttribute('aria-pressed'));
    if (el.getAttribute('aria-selected')) out.push('selected=' + el.getAttribute('aria-selected'));
    if (el.id) out.push('id=' + clean(el.id, 40));
    return out;
  };
  const push = (depth, text) => {
    if (truncated) return;
    if (nodes >= limits.nodes || chars + text.length > limits.chars) {
      truncated = true;
      lines.push('… 快照已截断，可用 selector 精确查询或先滚动 / 导航到目标区域');
      return;
    }
    nodes += 1;
    chars += text.length + 1;
    lines.push('  '.repeat(Math.min(depth, 12)) + text);
  };
  const walk = (el, depth) => {
    if (truncated || SKIP.has(el.tagName.toUpperCase()) || !visible(el)) return;
    const tag = el.tagName.toLowerCase();
    const role = (el.getAttribute('role') || '').toLowerCase();
    if (interactive(el)) {
      counter += 1;
      const ref = 'e' + counter;
      el.setAttribute('data-pilot-ref', ref);
      const name = labelOf(el);
      const kind = role || tag;
      push(depth, '- ' + kind + (name ? ' ' + JSON.stringify(name) : '') + ' [ref=' + ref + ']'
        + (attrs(el).length ? ' ' + attrs(el).join(' ') : ''));
      if (tag === 'select' || tag === 'option' || tag === 'textarea' || tag === 'input') return;
      for (const child of el.children) walk(child, depth + 1);
      return;
    }
    if (tag === 'img') {
      push(depth, '- img ' + JSON.stringify(clean(el.alt || el.getAttribute('src'), 80)));
      return;
    }
    if (tag === 'canvas' || tag === 'video' || tag === 'audio') {
      push(depth, '- ' + tag);
      return;
    }
    if (tag === 'iframe') {
      push(depth, '- iframe（内容不在快照内）' + (el.title ? ' ' + JSON.stringify(clean(el.title)) : ''));
      return;
    }
    const heading = /^h[1-6]$/.test(tag);
    let own = '';
    for (const node of el.childNodes) {
      if (node.nodeType === Node.TEXT_NODE) own += ' ' + node.textContent;
    }
    own = clean(own, 200);
    if (heading) {
      push(depth, '- ' + tag + ' ' + JSON.stringify(clean(el.textContent, 160)));
    } else if (own) {
      push(depth, '- text ' + JSON.stringify(own));
    }
    const nestedDepth = (own || heading || ['UL', 'OL', 'TABLE', 'FORM', 'NAV', 'MAIN', 'SECTION', 'ASIDE', 'DIALOG'].includes(el.tagName)) ? depth + 1 : depth;
    for (const child of el.children) walk(child, nestedDepth);
  };
  walk(document.body, 0);
  return { title: document.title, url: location.href, lines, interactive: counter, truncated };
}`;

// ---- 元素拾取 ------------------------------------------------------------------------------
// 面板里点「选择元素」后，下一次点击不再转发给页面，而是把点到的东西描述成可粘贴的引用。
const PICK_SCRIPT = `(x, y) => {
  const INTERACTIVE = new Set(['A', 'BUTTON', 'INPUT', 'SELECT', 'TEXTAREA', 'SUMMARY', 'LABEL', 'OPTION']);
  const ROLES = new Set(['button', 'link', 'checkbox', 'radio', 'tab', 'menuitem', 'switch',
    'textbox', 'combobox', 'option', 'slider', 'searchbox']);
  const clean = (value, max = 160) => String(value || '').replace(/\\s+/g, ' ').trim().slice(0, max);
  const interactive = (el) => INTERACTIVE.has(el.tagName)
    || ROLES.has((el.getAttribute('role') || '').toLowerCase())
    || el.isContentEditable;
  const label = (el) => clean(el.getAttribute('aria-label')
    || (el.labels && el.labels[0] ? el.labels[0].textContent : '')
    || el.innerText || el.value || el.getAttribute('placeholder') || '', 80);
  const part = (el) => {
    const tag = el.tagName.toLowerCase();
    const id = el.id ? '#' + el.id : '';
    const cls = Array.from(el.classList || []).slice(0, 2).map((name) => '.' + name).join('');
    return tag + id + cls;
  };
  const path = (el) => {
    const parts = [];
    let node = el;
    for (let i = 0; i < 4 && node && node.nodeType === 1; i++) {
      parts.unshift(part(node));
      node = node.parentElement;
      if (node === document.body) {
        parts.unshift('body');
        break;
      }
    }
    return parts.join(' > ');
  };
  const hit = document.elementFromPoint(x, y);
  if (!hit) return null;
  let target = hit;
  // 点到的是按钮里的文字/图标：往上找一个真正可交互、或者有意义的容器
  for (let i = 0; i < 4 && target.parentElement && !interactive(target); i++) {
    target = target.parentElement;
  }
  // 点在页面空白处只会命中 body：这种引用没有意义，交给前端提示换个位置
  if (target === document.body || target === document.documentElement) return null;
  const rect = target.getBoundingClientRect();
  const attrs = {};
  for (const name of ['type', 'name', 'href', 'placeholder', 'aria-label', 'role', 'value', 'data-testid']) {
    const value = target.getAttribute(name);
    if (value) attrs[name] = clean(value, 80);
  }
  return {
    tag: target.tagName.toLowerCase(),
    role: (target.getAttribute('role') || '').toLowerCase() || null,
    text: label(target),
    selector: path(target),
    rect: { x: Math.round(rect.x), y: Math.round(rect.y), w: Math.round(rect.width), h: Math.round(rect.height) },
    attrs
  };
}`;

async function snapshot(current) {
  const page = pageOf(current);
  const result = await page.evaluate(`(${SNAPSHOT_SCRIPT})`.concat(`(${JSON.stringify({ nodes: MAX_SNAPSHOT_NODES, chars: MAX_SNAPSHOT_CHARS })})`));
  const header = [`页面: ${result.title || '(无标题)'}`, `地址: ${result.url}`,
    `可交互元素: ${result.interactive}（用 [ref=eN] 作为 click/fill 的 ref）`];
  return header.concat('', result.lines).join('\n');
}

function locatorFor(current, args) {
  const page = pageOf(current);
  if (args.ref) {
    if (!/^e\d+$/.test(String(args.ref))) throw new Error(`ref 格式不对：${args.ref}（应形如 e3，来自最近一次 snapshot）`);
    return page.locator(`[data-pilot-ref="${args.ref}"]`).first();
  }
  if (args.selector) return page.locator(String(args.selector)).first();
  throw new Error('需要 ref（来自 snapshot）或 selector。');
}

async function settle(page) {
  try {
    await page.waitForLoadState('domcontentloaded', { timeout: 3000 });
  } catch {
    // 页面没有发生导航
  }
  await page.waitForTimeout(150);
}

async function describePage(current) {
  const page = pageOf(current);
  let title = '';
  try {
    title = await page.title();
  } catch {
    // 页面正在导航
  }
  return `当前页面: ${title || '(无标题)'} <${page.url()}>`;
}

async function elementMissing(error, args) {
  const text = String(error.message || error).split('\n')[0];
  if (/waiting for locator|Timeout/i.test(text)) {
    return new Error(`找不到可操作的元素（${args.ref || args.selector}）：${text}。页面可能已变化，请重新 snapshot 后再操作。`);
  }
  return error;
}

// ---- 操作 -------------------------------------------------------------------------------
const handlers = {
  async ping() {
    let version = 'unknown';
    try {
      version = require('playwright-core/package.json').version;
    } catch {
      loadPlaywright();
    }
    // capabilities：让 Java 端协商特性（镜像通道），旧 sidecar 不带此字段即不支持。
    return { text: 'pong', version, capabilities: ['mirror'] };
  },

  async probe() {
    loadPlaywright();
    const browser = await getBrowser();
    const version = browser.version();
    const executable = browser.__executable;
    const headless = browser.__headless;
    // 探测只验证能启动，随后关掉，不占用常驻内存。（有头模式下这会闪一下窗口，属可接受代价。）
    await browser.close();
    browserPromise = null;
    return { text: `浏览器可用：${executable}（Chromium ${version}${headless ? '' : '，有头'}）`,
             executable, version, headless, capabilities: ['mirror'] };
  },

  async navigate(args, current) {
    if (!args.url || !loopbackUrl(args.url)) {
      throw new Error(`只允许打开本机地址（http(s)://localhost、127.0.0.1、[::1]）：${args.url}`);
    }
    const page = pageOf(current);
    let response;
    try {
      response = await page.goto(String(args.url), { waitUntil: 'load', timeout: NAVIGATION_TIMEOUT_MS });
    } catch (error) {
      const text = String(error.message || error).split('\n')[0];
      if (/ERR_CONNECTION_REFUSED/.test(text)) {
        throw new Error(`无法连接 ${args.url}：连接被拒绝。服务可能没有启动或端口不对；先用 run_command 后台启动并确认就绪。`);
      }
      throw new Error(`打开 ${args.url} 失败：${text}`);
    }
    const status = response ? response.status() : 0;
    return { text: `已打开，HTTP ${status || '未知'}\n${await describePage(current)}`, url: page.url() };
  },

  async reload(args, current) {
    const page = pageOf(current);
    await page.reload({ waitUntil: 'load', timeout: NAVIGATION_TIMEOUT_MS });
    return { text: `已刷新\n${await describePage(current)}`, url: page.url() };
  },

  async back(args, current) {
    const page = pageOf(current);
    const response = await page.goBack({ waitUntil: 'load', timeout: NAVIGATION_TIMEOUT_MS });
    return { text: (response ? '已后退' : '没有可后退的历史') + `\n${await describePage(current)}`, url: page.url() };
  },

  async forward(args, current) {
    const page = pageOf(current);
    const response = await page.goForward({ waitUntil: 'load', timeout: NAVIGATION_TIMEOUT_MS });
    return { text: (response ? '已前进' : '没有可前进的历史') + `\n${await describePage(current)}`, url: page.url() };
  },

  /*
   * 用户在面板里直接操作页面（内置浏览器）：坐标是页面坐标系（与镜像帧同尺度）。
   * 与智能体的 click 分开：用户动作不打 reportAction 涟漪，也不该被「接管」开关拦截。
   */
  async mouse(args, current) {
    const page = pageOf(current);
    const x = Math.round(Number(args.x) || 0);
    const y = Math.round(Number(args.y) || 0);
    const action = String(args.action || 'click');
    if (action === 'move') {
      await page.mouse.move(x, y);
      return { text: '已移动', url: page.url() };
    }
    if (action === 'wheel') {
      await page.mouse.move(x, y);
      await page.mouse.wheel(Number(args.deltaX) || 0, Number(args.deltaY) || 0);
      await settle(page);
      return { text: '已滚动', url: page.url() };
    }
    await page.mouse.click(x, y, { button: args.button === 'right' ? 'right' : 'left' });
    await settle(page);
    return { text: '已点击', url: page.url() };
  },

  /** 面板里的「选择元素」：返回点击位置元素的描述，用户可以把这段当引用发给模型。 */
  async pick(args, current) {
    const page = pageOf(current);
    const info = await page.evaluate(`(${PICK_SCRIPT})(${Math.round(Number(args.x) || 0)}, ${Math.round(Number(args.y) || 0)})`);
    if (!info) throw new Error('这个位置没有取到元素');
    const attrs = Object.entries(info.attrs || {}).map(([name, value]) => `${name}=${JSON.stringify(value)}`).join(' ');
    const lines = [
      `页面元素：${info.tag}${info.role ? `（role=${info.role}）` : ''}${info.text ? ` 文本 ${JSON.stringify(info.text)}` : ''}`,
      `选择器：${info.selector}`
    ];
    if (info.rect) lines.push(`位置与尺寸：(${info.rect.x}, ${info.rect.y}) ${info.rect.w}×${info.rect.h}`);
    if (attrs) lines.push(`属性：${attrs}`);
    return { text: lines.join('\n') };
  },

  /**
   * 面板尺寸变化：把页面视口设成面板大小。
   *
   * <p>内嵌浏览器的关键一步——视口跟着面板走，页面按真实宽度做响应式排版，
   * 镜像帧的比例也就等于面板比例，画面铺满而不留大片空白，点击坐标还能 1:1 对上。
   */
  async resize(args, current) {
    const page = pageOf(current);
    const width = Math.min(1920, Math.max(280, Math.round(Number(args.width) || 0)));
    const height = Math.min(1600, Math.max(220, Math.round(Number(args.height) || 0)));
    const existing = page.viewportSize();
    if (existing && existing.width === width && existing.height === height) {
      return { text: '尺寸未变', url: page.url() };
    }
    await page.setViewportSize({ width, height });
    // 视口变化会让原来的 screencast 停在旧画面上：重开一次，并立刻补一帧
    if (current.mirror) {
      await stopScreencast(current);
      await ensureScreencast(current);
      await publishSnapshot(current);
    }
    await settle(page);
    return { text: `视口已调整为 ${width}×${height}`, url: page.url() };
  },

  /** 面板里的「停止加载」：SPA 里 window.stop() 也能把挂起的请求停掉。 */
  async stop(args, current) {
    const page = pageOf(current);
    try {
      await page.evaluate('window.stop()');
    } catch {
      // 页面正在销毁或还没就绪：当作已经停了
    }
    return { text: '已停止加载', url: page.url() };
  },

  /** 面板里的键盘输入：按键（Enter/Backspace/方向键…）与整段文本分别走 press / insertText。 */
  async keys(args, current) {
    const page = pageOf(current);
    const presses = Array.isArray(args.keys) ? args.keys : [];
    for (const key of presses) {
      await page.keyboard.press(String(key));
    }
    if (typeof args.text === 'string' && args.text) {
      await page.keyboard.insertText(args.text);
    }
    if (args.key) {
      await page.keyboard.press(String(args.key));
    }
    await settle(page);
    return { text: '已输入', url: page.url() };
  },

  async snapshot(args, current) {
    return { text: await snapshot(current) };
  },

  async click(args, current) {
    const locator = locatorFor(current, args);
    await reportAction(current, 'click', args);
    try {
      await locator.click({ timeout: ACTION_TIMEOUT_MS });
    } catch (error) {
      throw await elementMissing(error, args);
    }
    await settle(pageOf(current));
    return { text: `已点击 ${args.ref || args.selector}\n${await describePage(current)}` };
  },

  async fill(args, current) {
    if (typeof args.text !== 'string') throw new Error('fill 需要 text。');
    const locator = locatorFor(current, args);
    await reportAction(current, 'fill', args);
    try {
      await locator.fill(args.text, { timeout: ACTION_TIMEOUT_MS });
      if (args.submit) await locator.press('Enter');
    } catch (error) {
      throw await elementMissing(error, args);
    }
    await settle(pageOf(current));
    return { text: `已填入 ${args.ref || args.selector}${args.submit ? '并提交' : ''}\n${await describePage(current)}` };
  },

  async type(args, current) {
    if (typeof args.text !== 'string') throw new Error('type 需要 text。');
    const locator = locatorFor(current, args);
    await reportAction(current, 'type', args);
    try {
      await locator.click({ timeout: ACTION_TIMEOUT_MS });
      await pageOf(current).keyboard.type(args.text, { delay: 15 });
      if (args.submit) await pageOf(current).keyboard.press('Enter');
    } catch (error) {
      throw await elementMissing(error, args);
    }
    await settle(pageOf(current));
    return { text: `已逐字输入到 ${args.ref || args.selector}${args.submit ? '并提交' : ''}\n${await describePage(current)}` };
  },

  async press(args, current) {
    if (!args.key) throw new Error('press 需要 key，例如 Enter、Escape、Control+A。');
    await pageOf(current).keyboard.press(String(args.key));
    await settle(pageOf(current));
    return { text: `已按下 ${args.key}\n${await describePage(current)}` };
  },

  async select(args, current) {
    const values = Array.isArray(args.values) ? args.values.map(String) : [];
    if (values.length === 0) throw new Error('select 需要 values。');
    const locator = locatorFor(current, args);
    await reportAction(current, 'select', args);
    try {
      // 字符串同时匹配 option 的 value 与可见文本。
      const chosen = await locator.selectOption(values, { timeout: ACTION_TIMEOUT_MS });
      await settle(pageOf(current));
      return { text: `已选择 ${JSON.stringify(chosen)}\n${await describePage(current)}` };
    } catch (error) {
      throw await elementMissing(error, args);
    }
  },

  async upload(args, current) {
    const files = Array.isArray(args.files) ? args.files.map(String) : [];
    if (files.length === 0) throw new Error('upload 需要 files（绝对路径）。');
    for (const file of files) {
      if (!existsSync(file)) throw new Error(`文件不存在：${file}`);
    }
    const locator = locatorFor(current, args);
    await reportAction(current, 'upload', args);
    try {
      await locator.setInputFiles(files, { timeout: ACTION_TIMEOUT_MS });
    } catch (error) {
      throw await elementMissing(error, args);
    }
    await settle(pageOf(current));
    return { text: `已向 ${args.ref || args.selector} 上传 ${files.length} 个文件\n${await describePage(current)}` };
  },

  async screenshot(args, current) {
    if (!args.path) throw new Error('screenshot 需要 path。');
    const page = pageOf(current);
    mkdirSync(dirname(args.path), { recursive: true });
    let path = String(args.path);
    const options = { path, type: 'png', fullPage: Boolean(args.fullPage) };
    if (args.ref || args.selector) {
      await locatorFor(current, args).screenshot({ path, type: 'png' });
    } else {
      await page.screenshot(options);
    }
    if (statSync(path).size > MAX_SCREENSHOT_BYTES) {
      path = path.replace(/\.png$/i, '') + '.jpg';
      if (args.ref || args.selector) {
        await locatorFor(current, args).screenshot({ path, type: 'jpeg', quality: 70 });
      } else {
        await page.screenshot({ path, type: 'jpeg', quality: 70, fullPage: Boolean(args.fullPage) });
      }
    }
    return { text: `已截图\n${await describePage(current)}`, screenshot: path };
  },

  async console(args, current) {
    const entries = current.console.slice();
    if (args.clear) current.console.length = 0;
    const filtered = args.level ? entries.filter((entry) => entry.type === args.level) : entries;
    if (filtered.length === 0) return { text: '控制台没有记录。' };
    const shown = filtered.slice(-100);
    return { text: `控制台记录 ${filtered.length} 条（显示最近 ${shown.length} 条）：\n`
      + shown.map((entry) => `[${entry.type}] ${entry.text}${entry.at ? ' @ ' + entry.at : ''}`).join('\n') };
  },

  async network(args, current) {
    let entries = current.network.slice();
    if (args.failedOnly) entries = entries.filter((entry) => entry.failure || (entry.status && entry.status >= 400));
    if (args.filter) {
      const needle = String(args.filter).toLowerCase();
      entries = entries.filter((entry) => entry.url.toLowerCase().includes(needle));
    }
    if (args.clear) current.network.length = 0;
    if (entries.length === 0) return { text: '没有匹配的网络请求。' };
    const shown = entries.slice(-100);
    return { text: `网络请求 ${entries.length} 条（显示最近 ${shown.length} 条）：\n`
      + shown.map((entry) => `${entry.method} ${entry.url} → ${entry.failure ? 'FAILED ' + entry.failure
        : entry.status === null ? '(等待响应)' : entry.status} [${entry.type}]`).join('\n') };
  },

  async wait(args, current) {
    const page = pageOf(current);
    const timeout = Math.min(Math.max(Number(args.ms) || 0, 0), 15000);
    if (args.text) {
      try {
        await page.getByText(String(args.text)).first().waitFor({ state: 'visible', timeout: timeout || 8000 });
      } catch {
        throw new Error(`等待文本 ${JSON.stringify(args.text)} 超时，页面上没有出现。`);
      }
      return { text: `文本已出现：${args.text}` };
    }
    if (args.selector) {
      try {
        await page.locator(String(args.selector)).first().waitFor({ state: 'visible', timeout: timeout || 8000 });
      } catch {
        throw new Error(`等待元素 ${args.selector} 超时。`);
      }
      return { text: `元素已出现：${args.selector}` };
    }
    await page.waitForTimeout(timeout || 1000);
    return { text: `已等待 ${timeout || 1000}ms` };
  },

  async evaluate(args, current) {
    if (!args.expression) throw new Error('evaluate 需要 expression。');
    const page = pageOf(current);
    const value = await page.evaluate(String(args.expression));
    let text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
    if (text === undefined) text = 'undefined';
    if (text.length > 8000) text = text.slice(0, 8000) + '\n…（已截断）';
    return { text };
  },

  async close(args) {
    return { text: '已关闭' };
  },

  // ---- 镜像与窗口控制 -------------------------------------------------------------------
  async 'mirror-start'(args, current) {
    current.mirrorWidth = Math.min(1920, Math.max(320, Math.round(Number(args.maxWidth) || 960)));
    current.mirrorQuality = Math.min(90, Math.max(20, Math.round(Number(args.quality) || 55)));
    current.mirror = true;
    await ensureScreencast(current);
    return { text: '镜像已开启' };
  },

  async 'mirror-stop'(args, current) {
    current.mirror = false;
    await stopScreencast(current);
    return { text: '镜像已停止' };
  },

  async focus(args, current) {
    await pageOf(current).bringToFront();
    return { text: '已置前' };
  }
};

async function dispatch(request) {
  const op = request.op;
  if (op === 'ping' || op === 'probe') return handlers[op]();
  if (op === 'close') {
    const existing = sessions.get(request.session);
    if (existing) {
      sessions.delete(request.session);
      try {
        await existing.context.close();
      } catch {
        // 浏览器已退出
      }
    }
    return handlers.close();
  }
  if (op === 'shutdown') {
    setTimeout(() => process.exit(0), 50);
    return { text: 'bye' };
  }
  // 镜像控制与置前只作用于已存在的会话：浏览器没起来时不强制拉起（免得开面板即开窗）。
  if (op === 'mirror-start' || op === 'mirror-stop' || op === 'focus') {
    if (op === 'mirror-start') mirrorRequested.add(request.session);
    if (op === 'mirror-stop') mirrorRequested.delete(request.session);
    const existing = sessions.get(request.session);
    if (!existing || existing.dead) {
      return { text: op === 'mirror-start' ? '镜像已登记，浏览器启动后自动开始' : '没有活动页面' };
    }
    return handlers[op](request, existing);
  }
  const handler = handlers[op];
  if (!handler) throw new Error(`未知操作：${op}`);
  const current = await session(request.session);
  return handler(request, current);
}

async function handle(request) {
  try {
    const result = await dispatch(request);
    send({ id: request.id, ok: true, ...result });
  } catch (error) {
    send({ id: request.id, ok: false, error: String(error && error.message ? error.message : error).split('\n')[0] });
  }
}

// 同一会话的操作串行，不同会话并行。
function enqueue(request) {
  const key = request.session || '_';
  const previous = queues.get(key) || Promise.resolve();
  const next = previous.then(() => handle(request));
  queues.set(key, next.catch(() => {}));
}

const lines = createInterface({ input: process.stdin });
lines.on('line', (line) => {
  if (!line.trim()) return;
  let request;
  try {
    request = JSON.parse(line);
  } catch {
    send({ id: null, ok: false, error: '无法解析请求' });
    return;
  }
  enqueue(request);
});
lines.on('close', async () => {
  try {
    const browser = browserPromise ? await browserPromise : null;
    if (browser) await browser.close();
  } catch {
    // 退出时忽略
  }
  process.exit(0);
});
process.on('unhandledRejection', (error) => {
  process.stderr.write('unhandledRejection: ' + String(error && error.stack ? error.stack : error) + '\n');
});
send({ id: 0, ok: true, text: 'ready' });
