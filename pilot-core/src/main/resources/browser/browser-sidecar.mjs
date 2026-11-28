// Pilot 浏览器 sidecar：由 Java 通过 stdin/stdout 的 JSON Lines 驱动一个 Playwright Chromium。
//
// 协议（一行一个 JSON）：
//   请求  {"id": 1, "session": "s1", "op": "navigate", ...参数}
//   响应  {"id": 1, "ok": true, "text": "...", "screenshot": "/abs/a.png"} 或 {"id": 1, "ok": false, "error": "..."}
//   事件  {"event": "...", "session": "s1", ...}（无 id；Java 端按 event 字段路由，见事件通道）
// stdout 只放协议行；任何日志一律走 stderr。
//
// 独立浏览器按会话隔离 BrowserContext；桌面标签按会话归属，cookie / storage 沿用工作区分区。
// 导航只允许回环地址（localhost / 127.0.0.1 / [::1]）：本工具用来验证本机 Web 应用，不是通用上网。
//
// 环境变量（由 Java 端注入，未设时保持保守默认）：
//   PILOT_BROWSER_HEADLESS        'false' 才有头（有窗口）；默认 true（无头）。
//   PILOT_BROWSER_PREFER_BUNDLED  'false' 时系统 Chrome/Edge 优先于内置缓存；默认 true。
//   PLAYWRIGHT_BROWSERS_PATH      内置缓存目录（chromium.executablePath() 由此解析）。

import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { publicHttps, externalRequestAllowed, externalMutations, requireExternalApproval, hostDecision,
  hostRulesFromEnv, cdpEndpoint, externalAgentHostAllowed, sanitizeDiagnosticText, sanitizeDiagnosticUrl,
  sensitivePageUrl, classifySensitivePage, sensitiveRulesFromEnv, loopbackOrigin,
  inspectDownloadSafety, maxDownloadBytesFromEnv, sanitizeDownloadFilename } from './browser-policy.mjs';
import { writeFileSync, appendFileSync, closeSync, existsSync, mkdirSync, openSync, readFileSync, readSync, rmSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';

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
let downloadSequence = 0;
const SENSITIVE_MASK_COLOR = '#171717';
const SENSITIVE_FIELD_KEYWORDS = ['password', 'token', 'csrf', 'secret', 'otp', 'card', 'cvv', 'cvc', 'security', 'ssn'];
const SENSITIVE_FIELD_PATTERN_SOURCE = ['one-time-code', ...SENSITIVE_FIELD_KEYWORDS].join('|');
const SENSITIVE_FIELD_SELECTOR = [
  'input[type="password"]',
  'input[autocomplete*="password" i]',
  'input[autocomplete="one-time-code" i]',
  'input[autocomplete^="cc-" i]',
  ...SENSITIVE_FIELD_KEYWORDS.flatMap((kw) => [
    `input[name*="${kw}" i]`, `input[id*="${kw}" i]`,
    `textarea[name*="${kw}" i]`, `textarea[id*="${kw}" i]`
  ]),
  '[data-sensitive]'
].join(',');
const AGENT_OVERLAY_STYLE = '[data-pilot-agent-overlay] { visibility: hidden !important; }';
const DEFAULT_VIEWPORT = Object.freeze({ width: 1280, height: 800 });
const DEFAULT_MIRROR_WIDTH = 960;
const DEFAULT_MIRROR_QUALITY = 55;
// 外网主机清单由 Java 侧配置写入环境变量；进程启动时读一次，改配置后重启 sidecar 即生效。
const HOST_RULES = hostRulesFromEnv();
const SENSITIVE_RULES = sensitiveRulesFromEnv();
const MAX_DOWNLOAD_BYTES = maxDownloadBytesFromEnv();
// 外部 CDP 端点：用户自己开着的 Chrome。只接受回环地址，写错就当没配。
const EXTERNAL_CDP = cdpEndpoint(process.env.PILOT_BROWSER_CDP);

let playwright;
const managedBrowsers = { default: null, headed: null };
let desktopPromise = null;
let externalPromise = null;
const sessions = new Map();
const queues = new Map();
// 前端订阅过镜像的会话：浏览器尚未启动时先登记，会话建立时自动开始推帧。
const mirrorRequested = new Set();
// 订阅还在、但面板已经直接嵌页面：不要再推截图。缺省仍推帧。
const mirrorCapture = new Map();
// 截图退路的限流。直接嵌页面时这条链路是停着的。
const MIRROR_MIN_FRAME_MS = 50;

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function emitMirrorFrame(session, frame, w, h, url, ts = Date.now()) {
  send({
    event: 'mirror-frame',
    session: session.id,
    frame, w, h, width: w, height: h,
    url: sanitizeDiagnosticUrl(url),
    ts
  });
}

function emitControlState(session, event = 'control-state', extra = {}) {
  send({
    event,
    session: session.id,
    agentControlled: Boolean(session.agentControlled),
    controlStatus: String(session.controlStatus || ''),
    ...extra,
    ts: Date.now()
  });
}

function sensitiveMessage(page, detail, suffix = '请由用户完成验证或确认页面状态后继续。') {
  if (!sensitivePageUrl(page.url(), SENSITIVE_RULES)) return null;
  return `敏感登录或支付页面${detail}（${sanitizeDiagnosticUrl(page.url())}）。${suffix}`;
}

function loadPlaywright() {
  if (!playwright) {
    playwright = require('playwright-core');
  }
  return playwright;
}

/** 打开外网被拒时按主机清单区分原因，否则模型分不清是清单外还是网址本身不合格。 */
function externalBlockedReason(value) {
  let host = '';
  try {
    host = new URL(value).hostname.toLowerCase();
  } catch {
    return '目标不是可访问的公开 HTTPS 域名';
  }
  const decision = hostDecision(host, HOST_RULES);
  if (decision === 'deny') return `主机 ${host} 在拒绝清单（PILOT_BROWSER_DENY_HOSTS）里，不能打开`;
  if (decision === 'blocked') return `主机 ${host} 不在放行清单（PILOT_BROWSER_ALLOW_HOSTS）里，不能打开`;
  return '目标不是可访问的公开 HTTPS 域名';
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
  // 命令沙箱里再套 Chrome 自己的沙箱会直接起不来。验证用的浏览器关掉这层。
  return ['--disable-dev-shm-usage', '--no-first-run', '--no-default-browser-check', '--no-sandbox', ...extra];
}

let presentHeaded = false;

function headlessMode() {
  // 网页里点「打开内置浏览器」后，这一进程改为有头。否则只有显式 false 才开窗口。
  if (presentHeaded) return false;
  return (process.env.PILOT_BROWSER_HEADLESS ?? 'true') !== 'false';
}

async function launchManagedBrowser({ headless, onDisconnect }) {
  const { chromium } = loadPlaywright();
  const executables = candidateExecutables();
  if (executables.length === 0) {
    let expected = '';
    try { expected = chromium.executablePath(); } catch { /* playwright-core 不可用时不强求给出期望路径 */ }
    throw new Error(`没有找到可用的内置 Chromium${expected ? `（期望位置：${expected}）` : ''}。首次启动会自动下载完整版 Chromium（约 400MB）；也可以用 PILOT_BROWSER_PATH 指向已有的浏览器可执行文件。`);
  }
  const failures = [];
  for (const executablePath of executables) {
    try {
      const browser = await chromium.launch({ executablePath, headless, args: launchArgs(), timeout: 20000 });
      browser.__executable = executablePath;
      browser.__headless = headless;
      browser.on('disconnected', () => {
        onDisconnect(browser);
        for (const [id, current] of sessions) {
          if (!current.desktop && !current.external && current.browser === browser) {
            current.dead = true;
            sessions.delete(id);
          }
        }
        send({ event: 'browser-closed', reason: 'disconnected' });
      });
      return browser;
    } catch (error) {
      failures.push(`${executablePath}: ${String(error.message || error).split('\n')[0]}`);
    }
  }
  throw new Error('浏览器启动失败：' + failures.join('；'));
}

function cachedBrowser(slot, factory) {
  if (!managedBrowsers[slot]) {
    managedBrowsers[slot] = factory().catch((error) => {
      managedBrowsers[slot] = null;
      throw error;
    });
  }
  return managedBrowsers[slot];
}

const getBrowser = () => cachedBrowser('default', () => launchManagedBrowser({
  headless: headlessMode(),
  onDisconnect: () => { managedBrowsers.default = null; }
}));

const getHeadedBrowser = () => cachedBrowser('headed', () => launchManagedBrowser({
  headless: false,
  onDisconnect: () => { managedBrowsers.headed = null; presentHeaded = false; }
}));

function pushLimited(list, entry) {
  list.push(entry);
  if (list.length > MAX_LOG_ENTRIES) list.shift();
}

function registerLocalTab(session, page) {
  if (!session || session.desktop || !page) return null;
  session.tabs ||= new Map();
  session.pageToTabId ||= new WeakMap();
  const existingId = session.pageToTabId.get(page);
  if (existingId && session.tabs.has(existingId)) return existingId;
  session.tabSeq = (session.tabSeq || 0) + 1;
  const tabId = `tab-${session.tabSeq}`;
  session.tabs.set(tabId, page);
  session.pageToTabId.set(page, tabId);
  session.activeTabId = tabId;
  return tabId;
}

function recordBlockedOrigin(session, fromUrl, toUrl, reason) {
  if (!session) return;
  session.blockedOrigins ||= [];
  const entry = {
    from: sanitizeDiagnosticUrl(fromUrl || ''),
    to: sanitizeDiagnosticUrl(toUrl || ''),
    reason: String(reason || 'blocked')
  };
  session.blockedOrigins.push(entry);
  if (session.blockedOrigins.length > 50) session.blockedOrigins.shift();
}

function redirectChainOf(request) {
  const chain = [];
  let cursor = request;
  for (let i = 0; i < 10 && cursor; i++) {
    chain.unshift(sanitizeDiagnosticUrl(cursor.url()));
    cursor = typeof cursor.redirectedFrom === 'function' ? cursor.redirectedFrom() : null;
  }
  return chain;
}

function sessionDiagnostics(session) {
  if (!session) return { consoleErrors: 0, failedRequests: 0, blockedOrigins: [] };
  const consoleErrors = (session.console || []).filter((e) => e.type === 'error' || e.type === 'pageerror').length;
  const failedRequests = (session.network || []).filter((e) => e.failure || (e.status && e.status >= 400)).length;
  const blockedOrigins = (session.blockedOrigins || []).map((item) => `${item.to} (${item.reason})`);
  return { consoleErrors, failedRequests, blockedOrigins };
}

function recordActionMarker(session, entry) {
  if (!session || !session.recording) return;
  const rec = session.recording;
  const now = Date.now();
  rec.actions.push({
    ts: now,
    offsetMs: Math.max(0, now - rec.startedAt),
    kind: String(entry?.kind || ''),
    status: sanitizeDiagnosticText(String(entry?.status || '')),
    url: sanitizeDiagnosticUrl(String(entry?.url || '')),
    ref: String(entry?.ref || ''),
    x: typeof entry?.x === 'number' ? entry.x : null,
    y: typeof entry?.y === 'number' ? entry.y : null,
    box: entry?.box || null
  });
  if (rec.actions.length > 400) rec.actions.shift();
}

function appendRecordingFrameData(session, base64Jpeg, width, height, url, force = false) {
  const rec = session?.recording;
  if (!rec || !base64Jpeg) return;
  if (sensitivePageUrl(url, SENSITIVE_RULES)) return;
  const now = Date.now();
  if (!force && now - rec.lastFrameAt < rec.minIntervalMs) return;
  rec.lastFrameAt = now;
  const w = Number(width) || rec.width || DEFAULT_VIEWPORT.width;
  const h = Number(height) || rec.height || DEFAULT_VIEWPORT.height;
  rec.frames.push({
    ts: now,
    offsetMs: Math.max(0, now - rec.startedAt),
    url: sanitizeDiagnosticUrl(url),
    w,
    h,
    data: base64Jpeg
  });
  if (rec.frames.length > rec.maxFrames) {
    // 保留首帧，抽稀中间最密帧以控制内存与回放节奏
    rec.frames.splice(1, 1);
  }
}

async function captureRecordingFrame(session, reason = '', force = false) {
  const rec = session?.recording;
  if (!rec || rec.capturing || session.dead) return;
  const page = session.page;
  if (!page || page.isClosed()) return;
  const url = page.url();
  if (sensitivePageUrl(url, SENSITIVE_RULES)) {
    if (reason) recordActionMarker(session, { kind: reason, status: '敏感页面跳过画面录制', url });
    return;
  }
  const now = Date.now();
  if (!force && now - rec.lastFrameAt < rec.minIntervalMs) return;
  rec.capturing = true;
  try {
    const vp = page.viewportSize() || DEFAULT_VIEWPORT;
    const buf = await page.screenshot({ type: 'jpeg', quality: rec.quality || 55, style: AGENT_OVERLAY_STYLE });
    const currentUrl = page.url();
    if (!session.recording || session.dead) return;
    appendRecordingFrameData(session, buf.toString('base64'), vp.width, vp.height, currentUrl, force);
  } catch {
    // 画面正在切换时不阻断操作
  } finally {
    if (session.recording) session.recording.capturing = false;
  }
}

function attachPage(session, page) {
  session.page = page;
  if (!session.desktop) {
    const tabId = registerLocalTab(session, page);
    if (tabId) session.activeTabId = tabId;
  }
  session.attachedPages ||= new WeakSet();
  ensureStopBinding(session, page).catch(() => {});
  if (session.agentControlled) syncControlOverlay(session, page).catch(() => {});
  if (session.attachedPages.has(page)) {
    if (session.mirror && session.capture !== false) ensureScreencast(session).catch(() => {});
    return;
  }
  session.attachedPages.add(page);
  page.on('close', () => {
    if (session.tabs && session.pageToTabId) {
      const closedTabId = session.pageToTabId.get(page);
      if (closedTabId) session.tabs.delete(closedTabId);
      if (session.page === page) {
        const remaining = [...session.tabs.entries()];
        if (remaining.length > 0) {
          const [nextId, nextPage] = remaining[remaining.length - 1];
          session.activeTabId = nextId;
          session.page = nextPage;
        } else {
          session.activeTabId = null;
          session.page = null;
        }
      }
    }
  });
  page.on('framenavigated', (frame) => {
    if (frame !== page.mainFrame() || session.page !== page) return;
    session.refFrames = {};
    reportNavigation(session, frame.url()).catch(() => {});
    if (session.agentControlled) syncControlOverlay(session, page).catch(() => {});
    if (session.recording) captureRecordingFrame(session, 'nav', true).catch(() => {});
  });
  page.on('load', () => {
    if (session.page === page && session.agentControlled) {
      syncControlOverlay(session, page).catch(() => {});
    }
    if (session.page === page && session.recording) {
      captureRecordingFrame(session, 'load').catch(() => {});
    }
  });
  page.on('console', (message) => {
    const location = message.location();
    const entry = {
      type: message.type(),
      text: sanitizeDiagnosticText(message.text()),
      at: location && location.url ? `${sanitizeDiagnosticUrl(location.url)}:${location.lineNumber}` : ''
    };
    pushLimited(session.console, entry);
    appendLog(session, `[console] [${entry.type}] ${entry.text}${entry.at ? ' @ ' + entry.at : ''}`);
  });
  page.on('pageerror', (error) => {
    pushLimited(session.console, { type: 'pageerror', text: sanitizeDiagnosticText(error.message || error), at: '' });
  });
  page.on('dialog', async (dialog) => {
    const type = dialog.type();
    const msg = sanitizeDiagnosticText(dialog.message());
    const preset = session.nextDialog || null;
    session.nextDialog = null;
    const shouldAccept = preset ? Boolean(preset.accept) : (type === 'alert' || type === 'beforeunload');
    const promptText = preset ? String(preset.promptText ?? '') : '';
    pushLimited(session.console, { type: 'dialog', text: `${type}: ${msg} -> ${shouldAccept ? 'accept' : 'dismiss'}`, at: '' });
    session.dialogs ||= [];
    session.dialogs.push({ type, message: msg, handled: shouldAccept ? 'accepted' : 'dismissed', promptText, ts: Date.now() });
    if (session.dialogs.length > 50) session.dialogs.shift();
    recordActionMarker(session, { kind: 'dialog', status: `${type} (${shouldAccept ? '确认' : '取消'})：${msg.slice(0, 60)}` });
    try {
      if (shouldAccept) {
        if (type === 'prompt') await dialog.accept(promptText);
        else await dialog.accept();
      } else {
        await dialog.dismiss();
      }
    } catch {
      // 对话框已被页面自己处理
    }
  });
  page.on('request', (request) => {
    const entry = {
      method: request.method(),
      url: sanitizeDiagnosticUrl(request.url()),
      type: request.resourceType(),
      status: null,
      failure: null
    };
    request.__entry = entry;
    pushLimited(session.network, entry);
  });
  page.on('response', (response) => {
    const request = response.request();
    const entry = request.__entry;
    if (entry) {
      entry.status = response.status();
      appendLog(session, `[network] ${entry.method} ${entry.url} → ${response.status()}`);
    }
    session.downloadResponses ||= new Map();
    const headers = typeof response.headers === 'function' ? response.headers() : {};
    session.downloadResponses.set(response.url(), {
      contentType: String(headers['content-type'] || ''),
      status: response.status(),
      redirects: redirectChainOf(request)
    });
    if (session.downloadResponses.size > 100) {
      const oldest = session.downloadResponses.keys().next().value;
      if (oldest !== undefined) session.downloadResponses.delete(oldest);
    }
    if (session.page === page && request.isNavigationRequest() && request.frame() === page.mainFrame()) {
      const policy = framePolicy(headers);
      session.embed = policy.embed;
      session.frameOptions = policy.frameOptions;
    }
  });
  page.on('requestfailed', (request) => {
    const entry = request.__entry;
    if (entry) entry.failure = request.failure() ? request.failure().errorText : 'failed';
  });
  // 镜像开着且面板还在看截图：page 切换时把 screencast 跟到新页面上。
  if (session.mirror && session.capture !== false) ensureScreencast(session).catch(() => {});
}

/**
 * DENY 和收紧的 frame-ancestors 不能嵌。
 * SAMEORIGIN 先放行标记，由面板按自己的源再判断（同源可以嵌，跨端口不行）。
 */
function framePolicy(headers) {
  const xfo = String(headers['x-frame-options'] || '').toLowerCase();
  const csp = String(headers['content-security-policy'] || '');
  const ancestors = csp.match(/frame-ancestors\s+([^;]+)/i);
  if (ancestors && !ancestors[1].toLowerCase().split(/\s+/).includes('*')) {
    return { embed: false, frameOptions: '' };
  }
  if (/\bdeny\b/.test(xfo)) return { embed: false, frameOptions: 'deny' };
  if (/\bsameorigin\b/.test(xfo)) return { embed: true, frameOptions: 'sameorigin' };
  return { embed: true, frameOptions: '' };
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
  send({
    event: 'nav',
    session: session.id,
    url: sanitizeDiagnosticUrl(url),
    embed: session.embed !== false,
    frameOptions: session.frameOptions || '',
    ...state,
    ts: Date.now()
  });
}

function readDesktop() {
  const file = process.env.PILOT_DESKTOP_ENDPOINT || join(process.cwd(), 'desktop-browser.json');
  if (!existsSync(file)) return null;
  try {
    const data = JSON.parse(readFileSync(file, 'utf8'));
    if (!data || !data.token || !data.controlPort || !data.cdpPort || !data.pid) return null;
    process.kill(data.pid, 0);
    return data;
  } catch {
    return null;
  }
}

async function connectDesktop(desktop) {
  if (!desktopPromise) {
    const playwright = loadPlaywright();
    desktopPromise = playwright.chromium.connectOverCDP(`http://127.0.0.1:${desktop.cdpPort}`).then((browser) => {
      browser.on('disconnected', () => { desktopPromise = null; });
      return browser;
    }).catch((error) => {
      desktopPromise = null;
      throw error;
    });
  }
  return desktopPromise;
}

/** 外部 CDP 端点：用户自己已经开了调试端口的 Chrome，不归本进程启停。 */
async function connectExternal() {
  if (!externalPromise) {
    const playwright = loadPlaywright();
    externalPromise = playwright.chromium.connectOverCDP(EXTERNAL_CDP).catch((error) => {
      externalPromise = null;
      throw error;
    });
  }
  return externalPromise;
}

async function waitForDesktopPage(browser, tabId, targetId) {
  const marker = `/marker/${tabId}`;
  const deadline = Date.now() + 8000;
  while (Date.now() < deadline) {
    for (const context of browser.contexts()) {
      for (const page of context.pages()) {
        const url = page.url();
        if (url.includes(marker)) return page;
        if (targetId) {
          const cdp = await context.newCDPSession(page).catch(() => null);
          if (!cdp) continue;
          try {
            const info = await cdp.send('Target.getTargetInfo');
            if (info.targetInfo.targetId === targetId) return page;
          } finally {
            await cdp.detach().catch(() => {});
          }
        }
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error('桌面浏览器里没有出现这个标签。先打开 Pilot 桌面壳。');
}

function createSessionState(id, context, page, overrides = {}) {
  context.setDefaultTimeout(ACTION_TIMEOUT_MS);
  context.setDefaultNavigationTimeout(NAVIGATION_TIMEOUT_MS);
  return {
    id, context, page, browser: null, console: [], network: [], dead: false,
    desktop: false, external: false, desktopTabId: null,
    tabs: new Map(), pageToTabId: new WeakMap(), activeTabId: null, tabSeq: 0,
    allowedLoopbackOrigins: new Set(), currentLoopbackOrigin: null, blockedOrigins: [], downloadResponses: new Map(),
    mirror: false, capture: false,
    embed: true, frameOptions: '', mirrorWidth: DEFAULT_MIRROR_WIDTH, mirrorQuality: DEFAULT_MIRROR_QUALITY,
    cdp: null, cdpPage: null, lastFrameAt: 0, historyCdp: null, historyCdpPage: null,
    refFrames: {}, nextDialog: null, dialogs: [], recording: null,
    agentControlled: false, controlStatus: '', controlToken: randomUUID(), controlAborted: false,
    stopBindingInstalled: new WeakSet(),
    ...overrides
  };
}

async function desktopRequest(desktop, path, { method = 'GET', body, errorPrefix = '桌面浏览器请求失败' } = {}) {
  const headers = { 'x-pilot-token': desktop.token };
  const init = { method, headers };
  if (body !== undefined) {
    headers['content-type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  const response = await fetch(`http://127.0.0.1:${desktop.controlPort}${path}`, init);
  if (!response.ok) throw new Error(`${errorPrefix}（HTTP ${response.status}）`);
  return response.json().catch(() => ({}));
}

async function desktopTabsForSession(desktop, sessionId) {
  const body = await desktopRequest(desktop, '/tabs', { errorPrefix: '读取桌面标签失败' });
  return {
    activeId: body.activeId,
    tabs: (body.tabs || []).filter((tab) => tab.sessionId === sessionId)
  };
}

async function openDesktopTab(desktop, body, errorPrefix = '新建桌面标签失败') {
  const created = await desktopRequest(desktop, '/tabs', { method: 'POST', body, errorPrefix });
  const browser = await connectDesktop(desktop);
  const page = await waitForDesktopPage(browser, created.tabId, created.targetId);
  return { created, page };
}

async function openAndAttachDesktopTab(desktop, current, body, { viewport = DEFAULT_VIEWPORT, errorPrefix = '新建桌面标签失败' } = {}) {
  const { created, page } = await openDesktopTab(desktop, body, errorPrefix);
  current.desktopTabId = created.tabId;
  attachPage(current, page);
  await handlers.resize(viewport, current);
  return { created, page };
}

async function createDesktopSession(id, desktop) {
  const { created, page } = await openDesktopTab(desktop, { sessionId: id }, '桌面浏览器没有接上这个会话');
  const session = createSessionState(id, page.context(), page, { desktop: true, desktopTabId: created.tabId });
  attachPage(session, page);
  sessions.set(id, session);
  // Agent 的验证视口独立于右侧预览面板，隐藏标签也必须能截图。
  await handlers.resize(DEFAULT_VIEWPORT, session);
  return session;
}

/**
 * 外部 CDP 会话：连用户真实浏览器。沿用已有 Context（登录态就在里面），只开一个新标签。
 *
 * <p>不能给这个 Context 装 route：它属于用户全部标签，拦一个请求会连带影响人自己打开的页面。
 * 所以这一类的边界放在动作层：只允许操作放行清单里的主机。
 */
async function createExternalSession(id) {
  const browser = await connectExternal();
  if (!browser.isConnected()) {
    externalPromise = null;
    throw new Error(`外部浏览器已断开（${EXTERNAL_CDP}）。重新启动 Chrome 的调试端口后再试。`);
  }
  const context = browser.contexts()[0] || await browser.newContext();
  const page = await context.newPage();
  const session = createSessionState(id, context, page, { external: true });
  attachPage(session, page);
  sessions.set(id, session);
  return session;
}

/** 外部 CDP 会话只能操作放行清单里的主机：里面是用户真实的登录态。 */
function refuseExternalTarget(value) {
  if (externalAgentHostAllowed(value, HOST_RULES)) return;
  let host = String(value || '');
  try { host = new URL(host).hostname; } catch { /* 不是网址就按原文提示 */ }
  const hint = HOST_RULES.allow.length
    ? '不在放行清单（PILOT_BROWSER_ALLOW_HOSTS）里'
    : '没配放行清单（PILOT_BROWSER_ALLOW_HOSTS），外部浏览器一律不碰';
  throw new Error(`外部浏览器里的 ${host} ${hint}。人可以自己继续操作这个页面。`);
}

/**
 * 智能体能不能碰这个页面。
 *
 * <p>桌面里人已经打开的 http(s) 页面可以操作；无头浏览器只许本机源；
 * 外部 CDP 连的是用户自己的浏览器，必须显式配放行清单，清单外一律不碰。
 */
function agentMayTouchPage(url, current) {
  const external = Boolean(current && current.external);
  if (!url || (!external && String(url).includes('/marker/'))) return true;
  let parsed;
  try {
    parsed = new URL(String(url));
  } catch {
    return false;
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return false;
  if (external) return externalAgentHostAllowed(url, HOST_RULES);
  return Boolean(current && current.desktop) || Boolean(loopbackOrigin(url));
}

function refuseOffAllowlist(current) {
  const page = current.page;
  if (!page || page.isClosed()) return;
  if (current.external) {
    refuseExternalTarget(page.url());
    return;
  }
  if (current.externalOrigin && new URL(page.url()).origin === current.externalOrigin) return;
  if (agentMayTouchPage(page.url(), current)) return;
  throw new Error(`当前页面不在智能体允许的源站上（${page.url()}）。人可以继续浏览；智能体的点击、输入和跳转已停用。`);
}

async function notifyDesktopControl(session) {
  if (!session || !session.desktop) return;
  const desktop = readDesktop();
  if (!desktop) return;
  await desktopRequest(desktop, '/control', {
    method: 'POST',
    body: {
      sessionId: session.id,
      agentControlled: Boolean(session.agentControlled),
      controlStatus: String(session.controlStatus || '')
    }
  }).catch(() => {});
}

async function removeControlOverlay(page) {
  if (!page || page.isClosed()) return;
  await page.evaluate(() => {
    document.querySelectorAll('[data-pilot-agent-overlay]').forEach((node) => node.remove());
  }).catch(() => {});
}

async function triggerControlStop(session, source = 'page') {
  if (!session) return;
  session.controlAborted = true;
  session.agentControlled = false;
  session.controlStatus = '';
  await removeControlOverlay(session.page);
  await notifyDesktopControl(session);
  emitControlState(session, 'control-stopped', { source });
}

async function ensureStopBinding(session, page) {
  if (!session || !page || page.isClosed()) return;
  session.stopBindingInstalled ||= new WeakSet();
  session.stopBindingPromises ||= new WeakMap();
  if (session.stopBindingInstalled.has(page)) return;
  if (session.stopBindingPromises.has(page)) return session.stopBindingPromises.get(page);
  const pending = (async () => {
    try {
      await page.exposeBinding('__pilotStopControl', async (_source, token) => {
        if (token !== session.controlToken) return false;
        await triggerControlStop(session, 'page');
        return true;
      });
      session.stopBindingInstalled.add(page);
    } catch (error) {
      if (/already.*registered/i.test(String(error && error.message || error))) {
        session.stopBindingInstalled.add(page);
      }
    } finally {
      session.stopBindingPromises.delete(page);
    }
  })();
  session.stopBindingPromises.set(page, pending);
  return pending;
}

async function syncControlOverlay(session, page, cursorInfo = null) {
  if (!session || !page || page.isClosed()) return;
  if (!session.agentControlled) {
    await removeControlOverlay(page);
    return;
  }
  await ensureStopBinding(session, page);
  const status = String(cursorInfo?.status || session.controlStatus || '正在自动执行操作');
  session.controlStatus = status;
  await page.evaluate(({ status: statusText, token, cursor }) => {
    const root = document.documentElement || document.body;
    if (!root) return;
    let overlay = document.querySelector('[data-pilot-agent-overlay]');
    if (!overlay) {
      overlay = document.createElement('div');
      overlay.dataset.pilotAgentOverlay = 'true';
      overlay.setAttribute('aria-hidden', 'true');
      overlay.style.cssText = [
        'position:fixed', 'inset:0', 'z-index:2147483647', 'pointer-events:none', 'user-select:none',
        'box-shadow:inset 0 0 0 3px #3b82f6, inset 0 0 18px rgba(59,130,246,.36)'
      ].join(';');

      const boxEl = document.createElement('div');
      boxEl.dataset.pilotAgentTargetBox = 'true';
      boxEl.style.cssText = [
        'position:fixed', 'display:none', 'pointer-events:none',
        'border:2px solid #3b82f6', 'background:rgba(59,130,246,.12)',
        'border-radius:6px', 'box-shadow:0 0 0 2px rgba(255,255,255,.7)',
        'transition:left 120ms ease,top 120ms ease,width 120ms ease,height 120ms ease'
      ].join(';');

      const cursorEl = document.createElement('div');
      cursorEl.dataset.pilotAgentCursor = 'true';
      cursorEl.style.cssText = [
        'position:fixed', 'display:none', 'width:18px', 'height:18px', 'margin:-9px 0 0 -9px',
        'border-radius:50%', 'border:2px solid #fff', 'background:#2563eb',
        'box-shadow:0 0 0 4px rgba(59,130,246,.35), 0 3px 10px rgba(0,0,0,.3)',
        'pointer-events:none', 'transition:left 120ms ease,top 120ms ease'
      ].join(';');

      const bar = document.createElement('div');
      bar.dataset.pilotControlBar = 'true';
      bar.style.cssText = [
        'position:fixed', 'left:50%', 'bottom:14px', 'transform:translateX(-50%)',
        'display:flex', 'align-items:center', 'gap:10px', 'padding:6px 10px 6px 12px',
        'border-radius:999px', 'background:rgba(15,23,42,.92)', 'color:#f8fafc',
        'border:1px solid rgba(96,165,250,.55)', 'box-shadow:0 8px 24px rgba(15,23,42,.35)',
        'font:600 12px/1.3 system-ui,-apple-system,sans-serif', 'pointer-events:auto'
      ].join(';');

      const dot = document.createElement('span');
      dot.style.cssText = 'width:8px;height:8px;border-radius:50%;background:#60a5fa;box-shadow:0 0 0 3px rgba(96,165,250,.28);flex-shrink:0';

      const textEl = document.createElement('span');
      textEl.dataset.pilotAgentStatus = 'true';
      textEl.style.cssText = 'max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap';

      const stopBtn = document.createElement('button');
      stopBtn.type = 'button';
      stopBtn.dataset.pilotStopBtn = 'true';
      stopBtn.textContent = '停止控制 (Stop)';
      stopBtn.style.cssText = [
        'border:none', 'border-radius:999px', 'padding:4px 12px', 'cursor:pointer',
        'background:#dc2626', 'color:#fff', 'font:600 12px/1.2 system-ui,-apple-system,sans-serif',
        'flex-shrink:0'
      ].join(';');
      stopBtn.addEventListener('click', (event) => {
        if (!event.isTrusted) return;
        event.preventDefault();
        event.stopPropagation();
        stopBtn.disabled = true;
        stopBtn.textContent = '正在停止…';
        if (typeof window.__pilotStopControl === 'function') {
          window.__pilotStopControl(overlay.dataset.pilotToken || '');
        }
      });

      bar.append(dot, textEl, stopBtn);
      overlay.append(boxEl, cursorEl, bar);
      root.appendChild(overlay);
    }

    overlay.dataset.pilotToken = token;
    const textEl = overlay.querySelector('[data-pilot-agent-status]');
    if (textEl) textEl.textContent = `Pilot 正在控制 · ${statusText}`;
    const stopBtn = overlay.querySelector('[data-pilot-stop-btn]');
    if (stopBtn && stopBtn.disabled) {
      stopBtn.disabled = false;
      stopBtn.textContent = '停止控制 (Stop)';
    }

    const bar = overlay.querySelector('[data-pilot-control-bar]');
    if (bar) {
      const targetBottom = cursor?.box ? (cursor.box.y + cursor.box.height) : 0;
      const targetTop = cursor?.box ? cursor.box.y : 0;
      if (cursor?.box && targetBottom > innerHeight - 96 && targetTop > 96) {
        bar.style.top = '14px';
        bar.style.bottom = 'auto';
      } else {
        bar.style.bottom = '14px';
        bar.style.top = 'auto';
      }
    }

    const boxEl = overlay.querySelector('[data-pilot-agent-target-box]');
    if (boxEl) {
      if (cursor?.box) {
        boxEl.style.display = 'block';
        boxEl.style.left = `${Math.round(cursor.box.x)}px`;
        boxEl.style.top = `${Math.round(cursor.box.y)}px`;
        boxEl.style.width = `${Math.max(4, Math.round(cursor.box.width))}px`;
        boxEl.style.height = `${Math.max(4, Math.round(cursor.box.height))}px`;
      } else {
        boxEl.style.display = 'none';
      }
    }

    const cursorEl = overlay.querySelector('[data-pilot-agent-cursor]');
    if (cursorEl) {
      if (cursor && typeof cursor.x === 'number' && typeof cursor.y === 'number') {
        cursorEl.style.display = 'block';
        cursorEl.style.left = `${Math.round(cursor.x)}px`;
        cursorEl.style.top = `${Math.round(cursor.y)}px`;
      } else {
        cursorEl.style.display = 'none';
      }
    }
  }, { status, token: session.controlToken, cursor: cursorInfo }).catch(() => {});
}

const WITH_HIDDEN_OVERLAY_SOURCE = `const withHiddenOverlay = (fn) => {
  const overlay = document.querySelector('[data-pilot-agent-overlay]');
  const prevVis = overlay ? overlay.style.visibility : '';
  const prevPtr = overlay ? overlay.style.pointerEvents : '';
  if (overlay) {
    overlay.style.visibility = 'hidden';
    overlay.style.pointerEvents = 'none';
  }
  try {
    return fn();
  } finally {
    if (overlay) {
      overlay.style.visibility = prevVis;
      overlay.style.pointerEvents = prevPtr;
    }
  }
};`;

const DEEP_ELEMENT_FROM_POINT_SOURCE = `const deepElementFromPoint = (startRoot, px, py, pierceIframes = false) => {
  let root = startRoot;
  let gx = px;
  let gy = py;
  let hit = null;
  for (let guard = 0; guard < 12; guard += 1) {
    const next = typeof root?.elementFromPoint === 'function' ? root.elementFromPoint(gx, gy) : null;
    if (!next || next === hit) break;
    hit = next;
    if (next.shadowRoot) {
      root = next.shadowRoot;
      continue;
    }
    if (pierceIframes && String(next.tagName).toUpperCase() === 'IFRAME') {
      try {
        const childDoc = next.contentDocument;
        if (childDoc) {
          const r = next.getBoundingClientRect();
          gx -= r.left;
          gy -= r.top;
          root = childDoc;
          continue;
        }
      } catch { /* 跨域 iframe */ }
    }
    break;
  }
  return hit;
};`;

const ELEMENT_LABEL_SOURCE = `const accessibleLabel = (el, max = 48, fallbackSelector = 'button,a,[role="button"],summary') => (
  el.getAttribute?.('aria-label')
  || (el.labels && el.labels[0] && (el.labels[0].innerText || el.labels[0].textContent))
  || el.getAttribute?.('placeholder')
  || (fallbackSelector && el.matches?.(fallbackSelector) ? el.innerText : '')
  || ''
).replace(/\\s+/g, ' ').trim().slice(0, max);`;

const DESCRIBE_HIT_SOURCE = `const describeHit = (node, includeRole = false) => {
  if (!node) return '';
  const tag = String(node.tagName || '').toLowerCase();
  const id = node.id ? '#' + node.id : '';
  const role = includeRole && node.getAttribute?.('role') ? '[role="' + node.getAttribute('role') + '"]' : '';
  const txt = String(node.innerText || node.getAttribute?.('aria-label') || '').replace(/\\s+/g, ' ').trim().slice(0, 32);
  return tag + id + role + (txt ? ' "' + txt + '"' : '');
};`;

const CLEAR_REFS_SOURCE = `const clearRefsInTree = (node) => {
  if (!node) return;
  if (typeof node.removeAttribute === 'function') {
    node.removeAttribute('data-pilot-ref');
    node.removeAttribute('data-pilot-frame');
  }
  if (node.tagName && String(node.tagName).toUpperCase() === 'IFRAME') {
    try {
      const childDoc = node.contentDocument;
      if (childDoc && childDoc.body) clearRefsInTree(childDoc.body);
    } catch { /* 跨域 iframe 跳过 */ }
  }
  if (node.shadowRoot && node.shadowRoot.children) {
    for (const child of node.shadowRoot.children) clearRefsInTree(child);
  }
  if (node.children) {
    for (const child of node.children) clearRefsInTree(child);
  }
};
document.querySelectorAll('[data-pilot-ref]').forEach((node) => node.removeAttribute('data-pilot-ref'));
if (document.body) clearRefsInTree(document.body);`;

const ACTION_LABEL_FN = new Function('el', `${ELEMENT_LABEL_SOURCE}\nreturn accessibleLabel(el, 48, 'button,a,[role="button"]');`);
const PAGE_TEXT_FN = new Function(`${WITH_HIDDEN_OVERLAY_SOURCE}\nreturn withHiddenOverlay(() => (document.body && document.body.innerText) || '');`);
const INVALIDATE_REFS_FN = new Function(CLEAR_REFS_SOURCE);
const COORDINATE_HIT_FN = new Function('arg', `
  const { px, py } = arg;
  ${WITH_HIDDEN_OVERLAY_SOURCE}
  ${DEEP_ELEMENT_FROM_POINT_SOURCE}
  ${DESCRIBE_HIT_SOURCE}
  return withHiddenOverlay(() => describeHit(deepElementFromPoint(document, px, py, true)));
`);
const TOP_OCCLUSION_FN = new Function('arg', `
  const { px, py, topFrameId } = arg;
  ${WITH_HIDDEN_OVERLAY_SOURCE}
  ${DESCRIBE_HIT_SOURCE}
  return withHiddenOverlay(() => {
    const hit = document.elementFromPoint(px, py);
    if (!hit) return null;
    if (hit.getAttribute && hit.getAttribute('data-pilot-frame') === topFrameId) return null;
    if (hit.closest && hit.closest('iframe[data-pilot-frame="' + topFrameId + '"]')) return null;
    return describeHit(hit);
  });
`);
const TARGET_PROBE_FN = new Function('el', `
  const doc = el.ownerDocument || document;
  const win = doc.defaultView || window;
  ${WITH_HIDDEN_OVERLAY_SOURCE}
  ${DEEP_ELEMENT_FROM_POINT_SOURCE}
  ${ELEMENT_LABEL_SOURCE}
  ${DESCRIBE_HIT_SOURCE}
  return withHiddenOverlay(() => {
    const rect = el.getBoundingClientRect();
    const label = accessibleLabel(el, 48, 'button,a,[role="button"],summary');
    const visLeft = Math.max(0, rect.left);
    const visTop = Math.max(0, rect.top);
    const visRight = Math.min(win.innerWidth, rect.right);
    const visBottom = Math.min(win.innerHeight, rect.bottom);
    const visWidth = visRight - visLeft;
    const visHeight = visBottom - visTop;
    if (visWidth <= 0 || visHeight <= 0) {
      return { offscreen: true, label, rect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height } };
    }
    const containsDeep = (container, node) => {
      let cur = node;
      while (cur) {
        if (cur === container) return true;
        cur = cur.parentNode || cur.host || null;
      }
      return false;
    };
    const hitBelongs = (hit) => Boolean(hit) && (
      containsDeep(el, hit) || Boolean(el.labels && Array.from(el.labels).some((lbl) => containsDeep(lbl, hit)))
    );
    const insetX = Math.min(4, Math.max(1, visWidth * 0.15));
    const insetY = Math.min(4, Math.max(1, visHeight * 0.15));
    const cx = visLeft + visWidth / 2;
    const cy = visTop + visHeight / 2;
    const candidates = [
      [cx, cy], [visLeft + insetX, cy], [visRight - insetX, cy],
      [cx, visTop + insetY], [cx, visBottom - insetY],
      [visLeft + insetX, visTop + insetY], [visRight - insetX, visBottom - insetY]
    ];
    let firstBlocker = null;
    for (const [px, py] of candidates) {
      const hit = deepElementFromPoint(doc, px, py, false);
      if (hitBelongs(hit)) {
        return {
          ok: true, label, hitX: px, hitY: py,
          relX: Math.max(1, Math.min(Math.max(1, rect.width - 1), px - rect.left)),
          relY: Math.max(1, Math.min(Math.max(1, rect.height - 1), py - rect.top))
        };
      }
      if (!firstBlocker && hit) firstBlocker = describeHit(hit, true) || '未知元素';
    }
    return { ok: false, occludedBy: firstBlocker || '未知遮罩层', label, cx, cy };
  });
`);

async function showAgentAction(session, page, locator, kind, precomputed = null) {
  const box = precomputed?.box || await locator.boundingBox().catch(() => null);
  if (!box) return null;
  const x = precomputed?.x ?? Math.round(box.x + box.width / 2);
  const y = precomputed?.y ?? Math.round(box.y + box.height / 2);
  const verbs = {
    click: '点击', hover: '查看', fill: '填写', type: '输入',
    select: '选择', check: '勾选', uncheck: '取消勾选', scroll: '滚动',
    upload: '选择文件', download: '下载'
  };
  const target = precomputed?.label ?? await locator.evaluate(ACTION_LABEL_FN).catch(() => '');
  const status = `${verbs[kind] || '操作'}${target ? `：${String(target).replace(/\s+/g, ' ').trim().slice(0, 48)}` : ''}`;
  await syncControlOverlay(session, page, { x, y, box, status });
  return { status, box, x, y };
}

function sessionLogPath(session) {
  const name = String(session.id || 'session').replace(/[^a-zA-Z0-9_-]/g, '_');
  return join(process.cwd(), 'browser-logs', `${name}.log`);
}

function appendLog(session, line) {
  try {
    const file = sessionLogPath(session);
    mkdirSync(dirname(file), { recursive: true });
    appendFileSync(file, sanitizeDiagnosticText(line) + '\n');
  } catch {
    // 日志写失败不影响页面操作
  }
}

async function createSession(id) {
  // 网页上点过「打开内置浏览器」之后，新会话用那个有头窗口。没点过时桌面壳照旧。
  if (presentHeaded) {
    const browser = await getHeadedBrowser();
    return createLocalSession(id, browser);
  }
  // 配了外部 CDP 端点就优先连用户自己的浏览器（登录态在里面）。
  if (EXTERNAL_CDP) return createExternalSession(id);
  const desktop = readDesktop();
  if (desktop) return createDesktopSession(id, desktop);
  const browser = await getBrowser();
  return createLocalSession(id, browser);
}

async function createLocalSession(id, browser) {
  const context = await browser.newContext({
    viewport: DEFAULT_VIEWPORT,
    acceptDownloads: true,
    serviceWorkers: 'block'
  });
  const session = createSessionState(id, context, null, {
    browser,
    mirror: mirrorRequested.has(id),
    capture: mirrorCapture.get(id) !== false
  });
  // 主框架导航只允许当前已授权的本机源或外网 origin，跨端口/跨源重定向一律拦截并记录。
  await context.route('**/*', async (route) => {
    const request = route.request();
    const reqUrl = request.url();
    if (session.externalOrigin) {
      const allowed = await externalRequestAllowed({ url: reqUrl, method: request.method(),
        navigation: request.isNavigationRequest(), origin: session.externalOrigin,
        actionApproved: Boolean(session.actionApproved), hosts: HOST_RULES });
      if (!allowed) {
        if (request.isNavigationRequest() && request.frame() === session.page?.mainFrame()) {
          recordBlockedOrigin(session, session.page?.url(), reqUrl, 'cross_origin_redirect');
        }
        pushLimited(session.console, { type: 'blocked', text: sanitizeDiagnosticText(`已拦截未授权请求：${request.method()} ${reqUrl}`), at: '' });
        return route.abort('blockedbyclient');
      }
      return route.continue();
    }
    if (request.isNavigationRequest() && request.frame() === session.page?.mainFrame()) {
      if (reqUrl === 'about:blank') return route.continue();
      const targetOrigin = loopbackOrigin(reqUrl);
      if (!targetOrigin) {
        recordBlockedOrigin(session, session.page?.url(), reqUrl, 'non_loopback');
        pushLimited(session.console, { type: 'blocked', text: sanitizeDiagnosticText(`已拦截对非本机地址的导航：${reqUrl}`), at: '' });
        return route.abort('blockedbyclient');
      }
      if (session.historyNavigating) {
        if (session.allowedLoopbackOrigins.size > 0 && !session.allowedLoopbackOrigins.has(targetOrigin)) {
          recordBlockedOrigin(session, session.page?.url(), reqUrl, 'cross_port_redirect');
          pushLimited(session.console, { type: 'blocked', text: sanitizeDiagnosticText(`已拦截跨端口导航：${reqUrl}`), at: '' });
          return route.abort('blockedbyclient');
        }
      } else if (session.currentLoopbackOrigin && targetOrigin !== session.currentLoopbackOrigin) {
        recordBlockedOrigin(session, session.page?.url(), reqUrl, 'cross_port_redirect');
        pushLimited(session.console, { type: 'blocked', text: sanitizeDiagnosticText(`已拦截跨端口重定向：${reqUrl}`), at: '' });
        return route.abort('blockedbyclient');
      }
    }
    return route.continue();
  });
  // WebSocket 不经过 HTTP route；外网任务关闭此旁路，本地开发 HMR 仍可用。
  await context.routeWebSocket('**/*', (socket) => {
    if (session.externalOrigin) socket.close();
    else socket.connectToServer();
  });
  context.on('page', (page) => attachPage(session, page));
  // attachPage 会在 mirror 开启时自动挂 screencast（含首个页面）。
  attachPage(session, await context.newPage());
  sessions.set(id, session);
  return session;
}

// ---- 镜像（CDP screencast 事件通道）----------------------------------------------------
// 页面变化才产帧，静态页面零流量；ack 背压限速，帧到达后立刻 ack 以免 Chromium 停发。
// 页面事件与控制请求可能并发到达；每个会话串行启动/停止，避免遗留第二个 CDP 录屏会话。
function queueScreencast(session, action) {
  const pending = (session.screencastQueue || Promise.resolve()).catch(() => {}).then(action);
  session.screencastQueue = pending;
  return pending;
}

function ensureScreencast(session) {
  return queueScreencast(session, () => startScreencast(session));
}

async function startScreencast(session) {
  const page = session.page;
  const wanted = () => session.mirror && session.capture !== false && !session.dead
    && session.page === page && page && !page.isClosed();
  if (!wanted() || session.cdpPage === page) return;
  await closeScreencast(session);
  if (!wanted()) return;
  try {
    const cdp = await session.context.newCDPSession(page);
    if (!wanted()) {
      await cdp.detach();
      return;
    }
    session.cdp = cdp;
    session.cdpPage = page;
    cdp.on('Page.screencastFrame', async (frame) => {
      try {
        await cdp.send('Page.screencastFrameAck', { sessionId: frame.sessionId });
      } catch {
        // 会话已关
      }
      if (!wanted() || session.cdp !== cdp) return;
      const now = Date.now();
      if (now - session.lastFrameAt < MIRROR_MIN_FRAME_MS) return; // 限流：新帧稍后自然到来
      session.lastFrameAt = now;
      const vp = page.viewportSize() || DEFAULT_VIEWPORT;
      const w = vp.width || (frame.metadata && frame.metadata.deviceWidth) || null;
      const h = vp.height || (frame.metadata && frame.metadata.deviceHeight) || null;
      const pageUrl = page.url();
      appendRecordingFrameData(session, frame.data, w, h, pageUrl);
      emitMirrorFrame(session, frame.data, w, h, pageUrl, now);
    });
    const viewport = page.viewportSize() || { width: session.mirrorWidth, height: DEFAULT_VIEWPORT.height };
    await cdp.send('Page.startScreencast', {
      format: 'jpeg',
      quality: session.mirrorQuality,
      // 按视口 1:1 出帧。旧的 maxHeight = 宽×0.75 会把高页面缩小，底部像被裁掉。
      maxWidth: viewport.width,
      maxHeight: viewport.height,
      everyNthFrame: 1
    });
  } catch (error) {
    await closeScreencast(session);
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
  if (!session.mirror || session.capture === false || session.dead) return;
  const page = session.page;
  if (!page || page.isClosed()) return;
  try {
    const viewport = page.viewportSize() || DEFAULT_VIEWPORT;
    const shot = await page.screenshot({ type: 'jpeg', quality: session.mirrorQuality });
    if (!session.mirror || session.capture === false || session.dead || session.page !== page) return;
    session.lastFrameAt = Date.now();
    emitMirrorFrame(session, shot.toString('base64'), viewport.width, viewport.height, page.url());
  } catch {
    // 下一帧 screencast 会补上
  }
}

function stopScreencast(session) {
  return queueScreencast(session, () => closeScreencast(session));
}

async function closeScreencast(session) {
  const cdp = session.cdp;
  session.cdp = null;
  session.cdpPage = null;
  if (!cdp) return;
  try {
    await cdp.send('Page.stopScreencast');
  } catch {
    // 已断开
  } finally {
    await cdp.detach().catch(() => {});
  }
}

async function emitActionEvent(session, page, { kind, status, x, y, box, ref, viewport }) {
  const vp = viewport || page?.viewportSize() || DEFAULT_VIEWPORT;
  recordActionMarker(session, { kind, status, url: page?.url() || '', ref, x, y, box });
  if (session.recording && !session.cdp) await captureRecordingFrame(session, kind);
  send({
    event: 'agent-action',
    session: session.id,
    kind, status, x, y, box,
    viewportWidth: vp.width,
    viewportHeight: vp.height,
    ref,
    ts: Date.now()
  });
}

// 智能体动作坐标供镜像事件使用；状态事件不依赖是否已开视频流。
async function reportAction(session, kind, args, precomputed = null) {
  try {
    const page = session.page;
    const locator = precomputed?.locator || locatorFor(session, args);
    const box = precomputed?.box || await locator.boundingBox();
    if (box) {
      const shown = await showAgentAction(session, page, locator, kind, precomputed);
      const cx = shown?.x ?? Math.round(box.x + box.width / 2);
      const cy = shown?.y ?? Math.round(box.y + box.height / 2);
      const actionBox = {
        x: Math.round(box.x),
        y: Math.round(box.y),
        width: Math.round(box.width),
        height: Math.round(box.height)
      };
      await emitActionEvent(session, page, {
        kind,
        status: (shown && shown.status) || kind,
        x: cx,
        y: cy,
        box: actionBox,
        ref: String(args.ref || args.selector || '')
      });
    } else if (session.agentControlled && page && !page.isClosed()) {
      await syncControlOverlay(session, page);
    }
  } catch {
    // 页面在动，坐标拿不到就算了
  }
}

/** 坐标级视觉操作前的视口校准、控制光标高亮、事件上报与命中说明。 */
async function prepareCoordinates(current, xRaw, yRaw, kind, labelText = '') {
  const page = pageOf(current);
  const vp = page.viewportSize() || DEFAULT_VIEWPORT;
  const x = Math.min(Math.max(0, vp.width - 1), Math.max(0, Math.round(Number(xRaw))));
  const y = Math.min(Math.max(0, vp.height - 1), Math.max(0, Math.round(Number(yRaw))));
  const hitSummary = await page.evaluate(COORDINATE_HIT_FN, { px: x, py: y }).catch(() => '');

  const box = {
    x: Math.max(0, x - 14),
    y: Math.max(0, y - 14),
    width: Math.min(28, Math.max(8, vp.width - Math.max(0, x - 14))),
    height: Math.min(28, Math.max(8, vp.height - Math.max(0, y - 14)))
  };
  const verbs = { click: '坐标点击', dblclick: '坐标双击', right_click: '坐标右键', hover: '坐标悬停', drag: '拖拽', scroll: '坐标滚动' };
  const status = `${verbs[kind] || kind} (${x}, ${y})${labelText ? ` · ${labelText}` : hitSummary ? ` · ${hitSummary}` : ''}`;
  if (current.agentControlled) await syncControlOverlay(current, page, { x, y, box, status });
  await emitActionEvent(current, page, { kind, status, x, y, box, ref: `(${x},${y})`, viewport: vp });
  return { page, x, y, box, hitSummary, status };
}

/** 居中滚动、稳定布局并做穿透 Shadow DOM / 同源 iframe 的遮挡与命中点校验，避免点击偏移或误点悬浮头。 */
async function prepareTarget(current, args, kind, options = {}) {
  const page = pageOf(current);
  const chain = frameChainFor(current, args);
  const scrollIntoCenter = (el) => {
    if (typeof el.scrollIntoView === 'function') {
      el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
    }
  };
  let frameScope = page;
  for (const fid of chain) {
    if (!/^f\d+$/.test(fid)) throw new Error(`frame 格式不对：${fid}（应形如 f1 或 f1>f2）`);
    await frameScope.locator(`iframe[data-pilot-frame="${fid}"]`).first().evaluate(scrollIntoCenter).catch(() => {});
    frameScope = frameScope.frameLocator(`iframe[data-pilot-frame="${fid}"]`).first();
  }
  const locator = locatorFor(current, args);
  if (await locator.count() === 0) {
    throw new Error(`找不到目标元素（${args.ref || args.selector}）：页面上已不存在，请重新 snapshot。`);
  }
  await locator.evaluate(scrollIntoCenter).catch(() => {});
  await page.evaluate(() => new Promise((resolve) => {
    requestAnimationFrame(() => requestAnimationFrame(resolve));
  })).catch(() => {});

  const box = await locator.boundingBox().catch(() => null);
  if (options.skipVisibility) {
    return { locator, page, box, hitPosition: null, x: null, y: null, label: '' };
  }
  if (!box || box.width <= 0 || box.height <= 0) {
    throw new Error(`目标元素当前不可见或尺寸为 0（${args.ref || args.selector}），请检查是否折叠或先滚动/展开。`);
  }

  const probe = await locator.evaluate(TARGET_PROBE_FN).catch(() => null);

  if (options.requireHitTest && probe) {
    if (probe.offscreen) {
      throw new Error(`目标元素（${args.ref || args.selector}）滚出可视区域，无法精确命中。`);
    }
    if (probe.ok === false && probe.occludedBy) {
      throw new Error(`目标元素（${args.ref || args.selector}）当前被遮挡（遮挡层：${probe.occludedBy}），请先关闭弹窗/遮罩或滚动后再试。`);
    }
  }

  const targetX = probe?.ok ? Math.round(box.x + probe.relX) : Math.round(box.x + box.width / 2);
  const targetY = probe?.ok ? Math.round(box.y + probe.relY) : Math.round(box.y + box.height / 2);
  if (chain.length > 0 && options.requireHitTest) {
    const topOccluded = await page.evaluate(TOP_OCCLUSION_FN, { px: targetX, py: targetY, topFrameId: chain[0] }).catch(() => null);
    if (topOccluded) {
      throw new Error(`iframe 目标元素（${args.ref || args.selector}）当前被主页面元素遮挡（遮挡层：${topOccluded}），请先关闭弹窗/遮罩后再试。`);
    }
  }

  const precomputed = {
    locator, page, box,
    x: targetX,
    y: targetY,
    hitPosition: probe?.ok ? { x: probe.relX, y: probe.relY } : null,
    label: probe?.label || ''
  };
  await reportAction(current, kind, args, precomputed);
  return precomputed;
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
  const SENSITIVE_FIELD_RE = new RegExp(${JSON.stringify(SENSITIVE_FIELD_PATTERN_SOURCE)}, 'i');
  ${CLEAR_REFS_SOURCE}
  let counter = 0;
  let frameCounter = 0;
  const refFrames = {};
  let nodes = 0;
  let chars = 0;
  let truncated = false;
  const lines = [];
  const inOverlay = (el) => typeof el.closest === 'function'
    ? Boolean(el.closest('[data-pilot-agent-overlay]'))
    : el.hasAttribute('data-pilot-agent-overlay');
  // tree：整棵子树都不画。self：自己没有盒子，但子孙可能是固定定位或撑满视口，必须继续往下走。
  const concealment = (el) => {
    if (el.hasAttribute('hidden')) return 'tree';
    const win = (el.ownerDocument && el.ownerDocument.defaultView) || globalThis;
    const style = win && typeof win.getComputedStyle === 'function' ? win.getComputedStyle(el) : getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) return 'tree';
    const rect = el.getBoundingClientRect();
    if (rect.width <= 0 && rect.height <= 0 && el.tagName !== 'INPUT') return 'self';
    return '';
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
      const rootNode = typeof el.getRootNode === 'function' ? el.getRootNode() : (el.ownerDocument || document);
      const ownerDoc = el.ownerDocument || document;
      const key = labelled.split(' ')[0];
      const target = (rootNode && typeof rootNode.getElementById === 'function' ? rootNode.getElementById(key) : null)
        || (ownerDoc && typeof ownerDoc.getElementById === 'function' ? ownerDoc.getElementById(key) : null);
      if (target) return clean(target.textContent);
    }
    if (el.labels && el.labels.length) return clean(el.labels[0].textContent);
    if (el.tagName === 'INPUT' && ['button', 'submit', 'reset'].includes(el.type)) return clean(el.value);
    if (el.tagName === 'IMG') return clean(el.alt);
    return clean(el.textContent);
  };
  const interactive = (el) => INTERACTIVE_TAGS.has(el.tagName)
    || INTERACTIVE_ROLES.has((el.getAttribute('role') || '').toLowerCase())
    || el.hasAttribute('onclick') || (el.isContentEditable && el.getAttribute('contenteditable') !== 'inherit')
    || (el.tabIndex >= 0 && !['BODY', 'HTML', 'IFRAME'].includes(el.tagName));
  const sensitiveField = (el) => el.hasAttribute('data-sensitive') || el.type === 'hidden'
    || SENSITIVE_FIELD_RE.test([el.type, el.autocomplete, el.name, el.id].join(' '));
  const attrs = (el) => {
    const out = [];
    const tag = el.tagName.toLowerCase();
    if (tag === 'a' && el.getAttribute('href')) out.push('href=' + clean(el.getAttribute('href'), 80));
    if (tag === 'input') {
      out.push('type=' + (el.type || 'text'));
      if (el.type === 'checkbox' || el.type === 'radio') out.push(el.checked ? 'checked' : 'unchecked');
      else if (el.type === 'file') out.push('files=' + (el.files ? el.files.length : 0));
      else if (sensitiveField(el) && el.value) out.push('value=[已遮挡]');
      else if (el.value) out.push('value=' + JSON.stringify(clean(el.value, 60)));
      if (el.placeholder) out.push('placeholder=' + JSON.stringify(clean(el.placeholder, 60)));
      if (el.accept) out.push('accept=' + clean(el.accept, 40));
      if (el.multiple) out.push('multiple');
    } else if (tag === 'textarea') {
      if (sensitiveField(el) && el.value) out.push('value=[已遮挡]');
      else if (el.value) out.push('value=' + JSON.stringify(clean(el.value, 60)));
      if (el.placeholder) out.push('placeholder=' + JSON.stringify(clean(el.placeholder, 60)));
    } else if (tag === 'select') {
      const chosen = el.selectedOptions && el.selectedOptions[0];
      if (chosen) out.push('selected=' + JSON.stringify(clean(chosen.textContent, 40)));
      out.push('options=' + JSON.stringify(Array.from(el.options).slice(0, 12).map((o) => clean(o.textContent, 30))));
    } else if (el.isContentEditable) {
      const preview = clean(el.textContent, 60);
      if (preview) out.push('value=' + JSON.stringify(preview));
      const ph = el.getAttribute('placeholder') || el.getAttribute('data-placeholder') || el.getAttribute('aria-placeholder');
      if (ph) out.push('placeholder=' + JSON.stringify(clean(ph, 60)));
    }
    if (el.disabled) out.push('disabled');
    if (el.readOnly || el.getAttribute('aria-readonly') === 'true') out.push('readonly');
    if (el.required || el.getAttribute('aria-required') === 'true') out.push('required');
    if (el.getAttribute('aria-checked')) out.push('aria-checked=' + el.getAttribute('aria-checked'));
    if (el.getAttribute('aria-invalid') === 'true') out.push('invalid');
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
  const walk = (el, depth, frameChain = []) => {
    if (!el || truncated || SKIP.has(el.tagName.toUpperCase())) return;
    if (inOverlay(el)) return;
    const hide = concealment(el);
    if (hide === 'tree') return;
    const tag = el.tagName.toLowerCase();
    const role = (el.getAttribute('role') || '').toLowerCase();
    if (tag === 'iframe') {
      if (el.hasAttribute('data-sensitive')) {
        push(depth, '- iframe（敏感内容已隐藏）');
        return;
      }
      let childDoc = null;
      try { childDoc = el.contentDocument || null; } catch { childDoc = null; }
      if (childDoc && childDoc.body) {
        frameCounter += 1;
        const fid = 'f' + frameCounter;
        el.setAttribute('data-pilot-frame', fid);
        const frameLabel = el.getAttribute('title') || el.getAttribute('name') || clean(el.getAttribute('src'), 60);
        push(depth, '- iframe' + (frameLabel ? ' ' + JSON.stringify(frameLabel) : '') + ' [frame=' + fid + ']');
        walk(childDoc.body, depth + 1, [...frameChain, fid]);
      } else {
        const blockedTitle = el.getAttribute('title');
        push(depth, '- iframe（跨域或未加载，内容不在快照内）' + (blockedTitle ? ' ' + JSON.stringify(clean(blockedTitle)) : ''));
      }
      return;
    }
    if (hide !== 'self' && interactive(el)) {
      counter += 1;
      const ref = 'e' + counter;
      el.setAttribute('data-pilot-ref', ref);
      if (frameChain.length) refFrames[ref] = frameChain.slice();
      const name = labelOf(el);
      const kind = role || tag;
      push(depth, '- ' + kind + (name ? ' ' + JSON.stringify(name) : '') + ' [ref=' + ref + ']'
        + (attrs(el).length ? ' ' + attrs(el).join(' ') : ''));
      if (tag === 'select' || tag === 'option' || tag === 'textarea' || tag === 'input') return;
      if (el.shadowRoot && el.shadowRoot.children) {
        for (const child of el.shadowRoot.children) walk(child, depth + 1, frameChain);
      }
      for (const child of el.children) walk(child, depth + 1, frameChain);
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
    const heading = /^h[1-6]$/.test(tag);
    let own = '';
    for (const node of el.childNodes) {
      if (node.nodeType === 3) own += ' ' + node.textContent;
    }
    own = clean(own, 200);
    if (heading) {
      push(depth, '- ' + tag + ' ' + JSON.stringify(clean(el.textContent, 160)));
    } else if (own) {
      push(depth, '- text ' + JSON.stringify(own));
    }
    const nestedDepth = (own || heading || ['UL', 'OL', 'TABLE', 'FORM', 'NAV', 'MAIN', 'SECTION', 'ASIDE', 'DIALOG'].includes(el.tagName)) ? depth + 1 : depth;
    if (el.shadowRoot && el.shadowRoot.children) {
      for (const child of el.shadowRoot.children) walk(child, nestedDepth, frameChain);
    }
    for (const child of el.children) walk(child, nestedDepth, frameChain);
  };
  walk(document.body, 0, []);
  if (counter === 0 && document.body) {
    const controls = [];
    const seen = new Set();
    for (const el of document.querySelectorAll('button, a[href], input, textarea, select, summary, [role="button"], [role="textbox"], [role="link"], [contenteditable="true"]')) {
      if (!seen.has(el)) { seen.add(el); controls.push(el); }
    }
    const scanShadow = (node) => {
      if (!node || inOverlay(node)) return;
      if (node.shadowRoot && node.shadowRoot.children) {
        for (const child of node.shadowRoot.children) {
          if (interactive(child) && !seen.has(child)) { seen.add(child); controls.push(child); }
          scanShadow(child);
        }
      }
      if (node.children) {
        for (const child of node.children) scanShadow(child);
      }
    };
    scanShadow(document.body);
    for (const el of controls) {
      if (truncated || inOverlay(el) || concealment(el) === 'tree') continue;
      const rect = el.getBoundingClientRect();
      if (rect.width <= 0 && rect.height <= 0) continue;
      counter += 1;
      const ref = 'e' + counter;
      el.setAttribute('data-pilot-ref', ref);
      const name = labelOf(el);
      push(0, '- ' + (el.getAttribute('role') || el.tagName.toLowerCase()) + (name ? ' ' + JSON.stringify(name) : '')
        + ' [ref=' + ref + ']');
    }
  }
  const bodyText = document.body ? String(document.body.innerText || '') : '';
  const freshTask = bodyText.includes('从一个任务开始') || bodyText.includes('尚未选择工作区');
  return { title: document.title, url: location.href, lines, interactive: counter, truncated,
    empty: counter === 0, freshTask, refFrames };
}`;

// ---- 元素拾取 ------------------------------------------------------------------------------
// 面板里点「选择元素」后，下一次点击不再转发给页面，而是把点到的东西描述成可粘贴的引用。
const PICK_FN = new Function('arg', `
  const { x, y } = arg;
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
  ${WITH_HIDDEN_OVERLAY_SOURCE}
  ${DEEP_ELEMENT_FROM_POINT_SOURCE}
  const hit = withHiddenOverlay(() => deepElementFromPoint(document, x, y, false));
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
`);

async function snapshot(current) {
  const page = pageOf(current);
  const result = await page.evaluate(`(${SNAPSHOT_SCRIPT})`.concat(`(${JSON.stringify({ nodes: MAX_SNAPSHOT_NODES, chars: MAX_SNAPSHOT_CHARS })})`));
  current.refFrames = result && result.refFrames ? result.refFrames : {};
  const header = [`页面: ${result.title || '(无标题)'}`, `地址: ${result.url}`,
    `可交互元素: ${result.interactive}（用 [ref=eN] 作为 click/fill 的 ref）`];
  if (result.empty) {
    header.push('这个页面没有可点击元素。不要重复打开同一地址做验收；说明缺了什么并结束这一步。');
  }
  if (result.freshTask) {
    header.push('这是没有工作区和附件的新任务页。要验收缩略图或删除，先在页面上选好工作区并放入附件，不要回到源码里把同一文件再读一遍。');
  }
  return header.concat('', result.lines).join('\n');
}

async function screenshotMasks(page) {
  const masks = [page.locator(SENSITIVE_FIELD_SELECTOR)];
  const mainOrigin = new URL(page.url()).origin;
  const frames = await page.frames();
  const childFrames = frames.filter(frame => frame.parentFrame() === page.mainFrame());
  const crossOriginFrames = new Set(childFrames.map(frame => frame.url()).filter(url => {
      try { return new URL(url).origin !== mainOrigin; }
      catch { return true; }
    }));
  const framesByName = new Map(childFrames.filter(frame => frame.name()).map(frame => [frame.name(), frame.url()]));
  const iframes = await page.locator('iframe').evaluateAll(elements => elements.map(el => ({
    src: el.src || '', name: el.name || '', srcdoc: el.hasAttribute('srcdoc')
  })));
  iframes.forEach((frame, index) => {
    const frameUrl = framesByName.get(frame.name) || (childFrames.length === iframes.length
      ? childFrames[index].url() : frame.src);
    if (frame.srcdoc || crossOriginFrames.has(frameUrl)) masks.push(page.locator('iframe').nth(index));
  });
  return masks;
}

function frameChainFor(current, args) {
  if (Array.isArray(args?.frame)) return args.frame.map(String).filter(Boolean);
  if (typeof args?.frame === 'string' && args.frame.trim()) {
    return args.frame.split(/[>/]/).map((s) => s.trim()).filter(Boolean);
  }
  if (args?.ref && current?.refFrames && Array.isArray(current.refFrames[args.ref])) {
    return current.refFrames[args.ref];
  }
  return [];
}

function locatorFor(current, args) {
  const page = pageOf(current);
  let scope = page;
  for (const fid of frameChainFor(current, args)) {
    if (!/^f\d+$/.test(fid)) throw new Error(`frame 格式不对：${fid}（应形如 f1 或 f1>f2）`);
    scope = scope.frameLocator(`iframe[data-pilot-frame="${fid}"]`).first();
  }
  if (args.ref) {
    if (!/^e\d+$/.test(String(args.ref))) throw new Error(`ref 格式不对：${args.ref}（应形如 e3，来自最近一次 snapshot）`);
    return scope.locator(`[data-pilot-ref="${args.ref}"]`).first();
  }
  if (args.selector) return scope.locator(String(args.selector)).first();
  throw new Error('需要 ref（来自 snapshot）或 selector。');
}

/** 页面跳走、关掉或用户点击「停止控制」时，正在等的点击/输入立刻停，不等超时。 */
function stopIfPageGone(page, work, session = null, { allowNavigate = false } = {}) {
  let gone = false;
  let timer;
  const mark = () => { gone = true; };
  if (!allowNavigate) page.once('framenavigated', mark);
  page.once('close', mark);
  const watch = new Promise((_, reject) => {
    timer = setInterval(() => {
      if (session && session.controlAborted) {
        clearInterval(timer);
        reject(new Error('用户已点击停止控制，操作已中止'));
        return;
      }
      if (!gone && !page.isClosed()) return;
      clearInterval(timer);
      reject(new Error('页面已跳转或浏览器已关闭，操作已停止'));
    }, 40);
  });
  return Promise.race([work(), watch]).finally(() => {
    clearInterval(timer);
    if (!allowNavigate) page.off('framenavigated', mark);
    page.off('close', mark);
  });
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
  const tabHint = current.tabs && current.tabs.size > 1 && current.activeTabId ? ` [标签 ${current.activeTabId}]` : '';
  return `当前页面: ${sanitizeDiagnosticText(title || '(无标题)')} <${sanitizeDiagnosticUrl(page.url())}>${tabHint}`;
}

async function pageTitle(page) {
  try {
    return sanitizeDiagnosticText((await page.title()) || '');
  } catch {
    return '';
  }
}

async function detectHumanGate(page) {
  if (!page || page.isClosed()) return null;
  const url = page.url();
  const sensitiveKind = classifySensitivePage(url, SENSITIVE_RULES);
  if (sensitiveKind === 'payment') {
    return { kind: 'payment', reason: '检测到支付或结账页面，请由用户在页面上亲自完成支付。' };
  }
  if (sensitiveKind === 'auth') {
    return { kind: 'auth', reason: '检测到登录或身份验证页面，请由用户在页面上完成登录或 MFA。' };
  }
  try {
    const captcha = await page.evaluate(() => {
      const selectors = [
        'iframe[src*="recaptcha" i]', 'iframe[src*="hcaptcha" i]',
        'iframe[src*="challenges.cloudflare.com" i]',
        '.g-recaptcha', '.h-captcha', '.cf-turnstile', 'altcha-widget', '#challenge-form'
      ];
      if (selectors.some((sel) => document.querySelector(sel))) return true;
      const title = String(document.title || '');
      return /just a moment|attention required|verify you are human/i.test(title);
    });
    if (captcha) {
      return { kind: 'captcha', reason: '检测到人机验证或 CAPTCHA，请由用户接管页面手动完成。' };
    }
  } catch {
    // 页面正在跳转或不可脚本探测时忽略
  }
  return null;
}

function readHeadBytes(file, max = 512) {
  const fd = openSync(file, 'r');
  try {
    const buf = Buffer.alloc(max);
    const read = readSync(fd, buf, 0, max, 0);
    return buf.subarray(0, read);
  } finally {
    closeSync(fd);
  }
}

async function elementMissing(error, args) {
  const text = String(error.message || error).split('\n')[0];
  if (/waiting for locator|Timeout/i.test(text)) {
    return new Error(`找不到可操作的元素（${args.ref || args.selector}）：${text}。页面可能已变化，请重新 snapshot 后再操作。`);
  }
  return error;
}

async function setCheckedState(current, args, desired) {
  refuseOffAllowlist(current);
  const kind = desired ? 'check' : 'uncheck';
  const { locator, page, hitPosition } = await prepareTarget(current, args, kind);
  try {
    const info = await locator.evaluate((el) => {
      const role = (el.getAttribute('role') || '').toLowerCase();
      const isNativeOrAria = (el.tagName === 'INPUT' && (el.type === 'checkbox' || el.type === 'radio'))
        || ['checkbox', 'menuitemcheckbox', 'switch', 'radio'].includes(role);
      const current = el.tagName === 'INPUT' ? Boolean(el.checked)
        : (el.getAttribute('aria-checked') === 'true' || el.getAttribute('aria-pressed') === 'true');
      return { isNativeOrAria, current };
    }).catch(() => ({ isNativeOrAria: true, current: !desired }));

    await stopIfPageGone(page, async () => {
      if (info.isNativeOrAria) {
        await locator.setChecked(desired, { timeout: ACTION_TIMEOUT_MS });
      } else if (info.current !== desired) {
        await locator.click(hitPosition ? { position: hitPosition, timeout: ACTION_TIMEOUT_MS } : { timeout: ACTION_TIMEOUT_MS });
      }
    }, current);
  } catch (error) {
    throw await elementMissing(error, args);
  }
  await settle(page);
  const actual = await locator.evaluate((el) => {
    if (el.tagName === 'INPUT') return Boolean(el.checked);
    return el.getAttribute('aria-checked') === 'true' || el.getAttribute('aria-pressed') === 'true';
  }).catch(() => desired);
  return { text: `${desired ? '已勾选' : '已取消勾选'} ${args.ref || args.selector}（checked=${actual}）\n${await describePage(current)}` };
}

async function finishNavigate(current, page, response) {
  const status = response ? response.status() : 0;
  const gate = await detectHumanGate(page);
  const diag = sessionDiagnostics(current);
  if (current.agentControlled) await syncControlOverlay(current, page);
  return {
    text: `已打开，HTTP ${status || '未知'}\n${await describePage(current)}${gate ? `\n提示：${gate.reason}` : ''}`,
    url: page.url(),
    title: await pageTitle(page),
    gate,
    ...diag
  };
}

async function navigateHistory(current, method, movedText, emptyText) {
  const page = pageOf(current);
  current.historyNavigating = true;
  let response;
  try {
    response = await page[method]({ waitUntil: 'load', timeout: NAVIGATION_TIMEOUT_MS });
    const origin = loopbackOrigin(page.url());
    if (origin && !current.externalOrigin) current.currentLoopbackOrigin = origin;
  } finally {
    current.historyNavigating = false;
  }
  return { text: `${response ? movedText : emptyText}\n${await describePage(current)}`, url: page.url(), title: await pageTitle(page), ...sessionDiagnostics(current) };
}

// ---- 操作 -------------------------------------------------------------------------------
const handlers = {
  async open_window() {
    presentHeaded = true;
    const browser = await getHeadedBrowser();
    const pages = browser.contexts().flatMap((context) => context.pages());
    if (!pages.length) {
      const context = await browser.newContext({ viewport: DEFAULT_VIEWPORT });
      await context.newPage();
    } else {
      await pages[0].bringToFront().catch(() => {});
    }
    return { text: '已打开浏览器窗口。智能体会操作这个窗口。' };
  },

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
    if (EXTERNAL_CDP) {
      const browser = await connectExternal();
      const version = browser.version();
      // 外部浏览器是用户自己的进程，探测完不能关。
      return {
        text: `已接入外部 Chrome（CDP ${EXTERNAL_CDP}）${version ? `，Chromium ${version}` : ''}`,
        version,
        headless: false,
        capabilities: ['mirror'],
        external: true
      };
    }
    const desktop = readDesktop();
    if (desktop) {
      const browser = await connectDesktop(desktop);
      return {
        text: `桌面浏览器可用（CDP ${desktop.cdpPort}）`,
        version: browser.version(),
        capabilities: ['mirror'],
        desktop: true
      };
    }
    loadPlaywright();
    const browser = await getBrowser();
    const version = browser.version();
    const executable = browser.__executable;
    const headless = browser.__headless;
    // 探测只验证能启动，随后关掉，不占用常驻内存。（有头模式下这会闪一下窗口，属可接受代价。）
    await browser.close();
    managedBrowsers.default = null;
    return { text: `浏览器可用：${executable}（Chromium ${version}${headless ? '' : '，有头'}）`,
             executable, version, headless, capabilities: ['mirror'] };
  },

  async navigate(args, current) {
    if (current.external) {
      // 外部浏览器是用户的真实浏览器：只去放行清单里的主机，且不装 route。
      if (!args.url) throw new Error('navigate 需要 url。');
      refuseExternalTarget(args.url);
      let externalResponse;
      try {
        externalResponse = await pageOf(current).goto(String(args.url), { waitUntil: 'load', timeout: NAVIGATION_TIMEOUT_MS });
      } catch (error) {
        throw new Error(`外部浏览器打开 ${args.url} 失败：${String(error.message || error).split('\n')[0]}`);
      }
      return finishNavigate(current, current.page, externalResponse);
    }
    if (args.approvedExternal) {
      if (!await publicHttps(args.url, undefined, HOST_RULES)) throw new Error(externalBlockedReason(args.url));
      current.externalOrigin = new URL(args.url).origin;
    } else {
      const origin = args.url ? loopbackOrigin(args.url) : null;
      if (!origin || current.externalOrigin) {
        throw new Error(`只允许打开本机地址（http(s)://localhost、127.0.0.1、[::1]）：${args.url}`);
      }
      current.allowedLoopbackOrigins.add(origin);
      current.currentLoopbackOrigin = origin;
    }
    let page = current.page;
    if (current.desktop && (!page || page.isClosed())) {
      const desktop = readDesktop();
      if (desktop) {
        await openAndAttachDesktopTab(desktop, current, { sessionId: current.id, fresh: true });
      }
    }
    page = pageOf(current);
    let response;
    try {
      const currentHref = page.url();
      const targetHref = String(args.url);
      let sameTarget = currentHref === targetHref;
      if (!sameTarget) {
        try { sameTarget = new URL(currentHref).href === new URL(targetHref).href; } catch { /* ignore */ }
      }
      if (!sameTarget) {
        response = await page.goto(targetHref, { waitUntil: 'load', timeout: NAVIGATION_TIMEOUT_MS });
      }
    } catch (error) {
      const text = String(error.message || error).split('\n')[0];
      if (/ERR_CONNECTION_REFUSED/.test(text)) {
        throw new Error(`无法连接 ${args.url}：连接被拒绝。服务可能没有启动或端口不对；先用 run_command 后台启动并确认就绪。`);
      }
      if (/ERR_BLOCKED_BY_CLIENT/.test(text) && current.blockedOrigins?.length) {
        const latest = current.blockedOrigins[current.blockedOrigins.length - 1];
        throw new Error(`已拦截跨源或跨端口重定向：${latest.to}（${latest.reason}）`);
      }
      throw new Error(`打开 ${args.url} 失败：${text}`);
    }
    const landedUrl = page.url();
    if (current.externalOrigin) {
      let landedOrigin = '';
      try { landedOrigin = new URL(landedUrl).origin; } catch { /* ignore */ }
      if (landedOrigin && landedOrigin !== current.externalOrigin) {
        recordBlockedOrigin(current, args.url, landedUrl, 'cross_origin_redirect');
        throw new Error(`已拦截跨源重定向：目标已跳转到 ${sanitizeDiagnosticUrl(landedUrl)}，请重新 navigate 并确认。`);
      }
    } else if (!current.desktop && !current.external && current.currentLoopbackOrigin) {
      const landedLoopback = loopbackOrigin(landedUrl);
      if (landedLoopback && landedLoopback !== current.currentLoopbackOrigin) {
        recordBlockedOrigin(current, args.url, landedUrl, 'cross_port_redirect');
        throw new Error(`已拦截跨端口重定向：当前仅允许 ${current.currentLoopbackOrigin}，跳转目标 ${sanitizeDiagnosticUrl(landedUrl)}。`);
      }
    }
    return finishNavigate(current, page, response);
  },

  async reload(args, current) {
    const page = pageOf(current);
    await page.reload({ waitUntil: 'load', timeout: NAVIGATION_TIMEOUT_MS });
    const gate = await detectHumanGate(page);
    return { text: `已刷新\n${await describePage(current)}`, url: page.url(), title: await pageTitle(page), gate, ...sessionDiagnostics(current) };
  },

  back: (_args, current) => navigateHistory(current, 'goBack', '已后退', '没有可后退的历史'),

  forward: (_args, current) => navigateHistory(current, 'goForward', '已前进', '没有可前进的历史'),

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
      return { text: '已滚动', url: page.url() };
    }
    await page.mouse.click(x, y, { button: args.button === 'right' ? 'right' : 'left' });
    return { text: '已点击', url: page.url() };
  },

  /** 面板里的「选择元素」：返回点击位置元素的描述，用户可以把这段当引用发给模型。 */
  async pick(args, current) {
    const page = pageOf(current);
    const blocked = sensitiveMessage(page, '已隐藏元素详情', '');
    if (blocked) throw new Error(blocked);
    const info = await page.evaluate(PICK_FN, { x: Math.round(Number(args.x) || 0), y: Math.round(Number(args.y) || 0) });
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
    // 外部浏览器是用户自己的窗口：面板不能改它的视口，否则会把人正在看的页面挤变形。
    if (current.external) return { text: '外部浏览器按人自己的窗口尺寸显示，未调整视口', url: page.url() };
    const width = Math.min(1920, Math.max(280, Math.round(Number(args.width) || 0)));
    const height = Math.min(1600, Math.max(220, Math.round(Number(args.height) || 0)));
    const existing = page.viewportSize();
    if (!current.desktop && existing && existing.width === width && existing.height === height) {
      return { text: '尺寸未变', url: page.url() };
    }
    await page.setViewportSize({ width, height });
    if (current.desktop) {
      const desktop = readDesktop();
      if (desktop) {
        await desktopRequest(desktop, '/emulate', {
          method: 'POST',
          body: { tabId: current.desktopTabId, width, height },
          errorPrefix: '桌面视口调整失败'
        });
      }
    }
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
    return { text: '已输入', url: page.url() };
  },

  async snapshot(args, current) {
    const page = pageOf(current);
    const blocked = sensitiveMessage(page, '已隐藏页面内容');
    const gate = await detectHumanGate(page);
    return {
      text: blocked || sanitizeDiagnosticText(await snapshot(current)),
      url: page.url(),
      title: await pageTitle(page),
      gate,
      ...sessionDiagnostics(current)
    };
  },

  // 固定的只读诊断，无任意表达式、事件触发、DOM 写入或网络请求。
  async inspect(args, current) {
    const page = pageOf(current);
    const blocked = sensitiveMessage(page, '已隐藏布局和元素信息');
    if (blocked) return { text: blocked, url: page.url() };
    const overview = await page.evaluate(() => {
      const candidates = [...document.querySelectorAll('main,aside,header,footer,nav,[role="main"],[data-pilot-ref],button,input,textarea,select')]
        .filter((el) => !el.closest('[data-pilot-agent-overlay]'));
      const visible = candidates.filter(el => {
        const rect = el.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0 && getComputedStyle(el).visibility !== 'hidden';
      });
      return {
        viewport: { width: innerWidth, height: innerHeight, devicePixelRatio,
          scrollWidth: document.documentElement.scrollWidth, scrollHeight: document.documentElement.scrollHeight },
        elements: visible.slice(0, 30).map(el => {
          const rect = el.getBoundingClientRect();
          return { tag: el.tagName.toLowerCase(), id: el.id, ref: el.getAttribute('data-pilot-ref'),
            label: (el.getAttribute('aria-label') || el.getAttribute('placeholder') || (el.matches('button,a') ? el.textContent : '') || '').trim().slice(0, 80),
            rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height } };
        }),
        truncated: visible.length > 30
      };
    });
    let element = null;
    if (args.ref || args.selector) {
      element = await locatorFor(current, args).evaluate(el => {
        const rect = el.getBoundingClientRect(), style = getComputedStyle(el);
        const properties = ['display', 'visibility', 'position', 'z-index', 'overflow-x', 'overflow-y',
          'color', 'background-color', 'font-size', 'line-height', 'padding', 'margin', 'transform'];
        return { tag: el.tagName.toLowerCase(), rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
          style: Object.fromEntries(properties.map(key => [key, style.getPropertyValue(key)])) };
      });
    }
    const cleanUrl = sanitizeDiagnosticUrl(page.url());
    const cleanTitle = await pageTitle(page);
    return {
      text: JSON.stringify({ url: cleanUrl, title: cleanTitle, ...overview, element,
        hint: '需要具体元素的计算样式时继续 inspect(ref 或 selector)；位置和尺寸不需要 evaluate。' }),
      url: cleanUrl,
      title: cleanTitle
    };
  },

  async click(args, current) {
    refuseOffAllowlist(current);
    const button = args.button === 'right' ? 'right' : 'left';
    const clickCount = Number(args.clickCount) === 2 ? 2 : 1;
    if (!args.ref && !args.selector && Number.isFinite(Number(args.x)) && Number.isFinite(Number(args.y))) {
      const kind = clickCount === 2 ? 'dblclick' : button === 'right' ? 'right_click' : 'click';
      const { page, x, y, hitSummary } = await prepareCoordinates(current, args.x, args.y, kind);
      await stopIfPageGone(page, () => page.mouse.click(x, y, { button, clickCount }), current);
      await settle(page);
      return { text: `已按坐标点击 (${x}, ${y})${hitSummary ? `（命中 ${hitSummary}）` : ''}\n${await describePage(current)}` };
    }
    const { locator, page, hitPosition } = await prepareTarget(current, args, 'click', { requireHitTest: true });
    const clickOpts = { timeout: ACTION_TIMEOUT_MS, button, clickCount };
    if (hitPosition) clickOpts.position = hitPosition;
    try {
      await stopIfPageGone(page, () => locator.click(clickOpts), current);
    } catch (error) {
      throw await elementMissing(error, args);
    }
    await settle(page);
    const state = await locator.evaluate((el) => {
      if (el.tagName === 'INPUT' && (el.type === 'checkbox' || el.type === 'radio')) {
        return el.checked ? 'checked=true' : 'checked=false';
      }
      const expanded = el.getAttribute('aria-expanded');
      if (expanded) return `aria-expanded=${expanded}`;
      const pressed = el.getAttribute('aria-pressed');
      if (pressed) return `aria-pressed=${pressed}`;
      return '';
    }).catch(() => '');
    return { text: `已点击 ${args.ref || args.selector}${state ? `（${state}）` : ''}\n${await describePage(current)}` };
  },

  async hover(args, current) {
    refuseOffAllowlist(current);
    if (!args.ref && !args.selector && Number.isFinite(Number(args.x)) && Number.isFinite(Number(args.y))) {
      const { page, x, y, hitSummary } = await prepareCoordinates(current, args.x, args.y, 'hover');
      await stopIfPageGone(page, () => page.mouse.move(x, y), current);
      return { text: `已按坐标悬停 (${x}, ${y})${hitSummary ? `（命中 ${hitSummary}）` : ''}\n${await describePage(current)}` };
    }
    const { locator, page, hitPosition } = await prepareTarget(current, args, 'hover', { requireHitTest: true });
    const hoverOpts = { timeout: ACTION_TIMEOUT_MS };
    if (hitPosition) hoverOpts.position = hitPosition;
    try {
      await stopIfPageGone(page, () => locator.hover(hoverOpts), current);
    } catch (error) {
      throw await elementMissing(error, args);
    }
    return { text: `已悬停 ${args.ref || args.selector}\n${await describePage(current)}` };
  },

  async text(args, current) {
    const page = pageOf(current);
    const blocked = sensitiveMessage(page, '已隐藏页面文本');
    if (blocked) return { text: blocked, url: sanitizeDiagnosticUrl(page.url()) };
    const text = await page.evaluate(PAGE_TEXT_FN).catch(() => '');
    const clipped = text.length > 8000 ? text.slice(0, 8000) + '\n…（已截断）' : text;
    return { text: sanitizeDiagnosticText(clipped || '页面没有可读文本'), url: sanitizeDiagnosticUrl(page.url()), title: await pageTitle(page) };
  },

  async links(args, current) {
    const page = pageOf(current);
    const blocked = sensitiveMessage(page, '已隐藏链接');
    if (blocked) return { text: blocked, url: sanitizeDiagnosticUrl(page.url()) };
    const links = await page.evaluate(() => Array.from(document.querySelectorAll('a[href]')).slice(0, 80).map((node) => ({
      text: (node.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 80),
      href: node.href
    })));
    const lines = links.map((link) => `${sanitizeDiagnosticText(link.text) || '(无文本)'}  ${sanitizeDiagnosticUrl(link.href)}`);
    return { text: lines.length ? lines.join('\n') : '页面上没有链接', url: sanitizeDiagnosticUrl(page.url()), title: await pageTitle(page) };
  },

  async new_tab(args, current) {
    if (current.desktop) {
      const desktop = readDesktop();
      if (!desktop) throw new Error('桌面浏览器未连接。');
      const viewport = current.page?.viewportSize() || DEFAULT_VIEWPORT;
      const { created, page } = await openAndAttachDesktopTab(desktop, current, {
        sessionId: current.id,
        fresh: true,
        background: args.background !== false,
        agentId: args.agentId || null,
        label: args.label || null
      }, { viewport });
      if (args.url) {
        const nav = await handlers.navigate({ ...args, url: args.url }, current);
        return { ...nav, tabId: created.tabId, text: `已新建标签 ${created.tabId} 并打开\n${await describePage(current)}` };
      }
      return { text: `已新建标签 ${created.tabId}`, tabId: created.tabId, url: page.url() };
    }
    const page = await current.context.newPage();
    attachPage(current, page);
    const tabId = current.activeTabId;
    if (args.url) {
      const nav = await handlers.navigate({ ...args, url: args.url }, current);
      return { ...nav, tabId, text: `已新建并切换到标签 ${tabId}\n${await describePage(current)}` };
    }
    return { text: `已新建并切换到标签 ${tabId}`, tabId, url: page.url() };
  },

  async tabs(args, current) {
    if (current.desktop) {
      const desktop = readDesktop();
      if (!desktop) return { text: '桌面浏览器未连接。' };
      const { activeId, tabs: mine } = await desktopTabsForSession(desktop, current.id);
      if (!mine.length) return { text: '还没有标签。' };
      return { text: mine.map((tab) => `${tab.id}${tab.id === activeId ? '  当前' : ''}  ${sanitizeDiagnosticText(tab.title)}  ${sanitizeDiagnosticUrl(tab.url)}`).join('\n') };
    }
    const entries = [...(current.tabs || new Map()).entries()].filter(([, page]) => page && !page.isClosed());
    if (!entries.length) return { text: '还没有标签。' };
    const lines = [];
    for (const [id, page] of entries) {
      const title = await pageTitle(page);
      const active = page === current.page ? '  当前' : '';
      lines.push(`${id}${active}  ${title || '(无标题)'}  ${sanitizeDiagnosticUrl(page.url())}`);
    }
    return { text: lines.join('\n') };
  },

  async switch_tab(args, current) {
    if (!args.tabId) throw new Error('switch_tab 需要 tabId。');
    const tabId = String(args.tabId);
    if (current.desktop) {
      const desktop = readDesktop();
      if (!desktop) throw new Error('桌面浏览器未连接。');
      const { tabs: mine } = await desktopTabsForSession(desktop, current.id);
      const tab = mine.find((item) => item.id === tabId);
      if (!tab) throw new Error('标签不属于当前会话。');
      const browser = await connectDesktop(desktop);
      const page = await waitForDesktopPage(browser, tab.id, tab.targetId);
      current.desktopTabId = tab.id;
      attachPage(current, page);
      return { text: `已在后台使用标签 ${tab.id}。你正在看的页面没有被换掉。`, url: page.url(), title: await pageTitle(page) };
    }
    const target = current.tabs?.get(tabId);
    if (!target || target.isClosed()) throw new Error('标签不属于当前会话或已关闭。');
    current.activeTabId = tabId;
    attachPage(current, target);
    const switchedOrigin = loopbackOrigin(target.url());
    if (switchedOrigin && !current.externalOrigin) current.currentLoopbackOrigin = switchedOrigin;
    return { text: `已切换到标签 ${tabId}\n${await describePage(current)}`, url: target.url(), title: await pageTitle(target) };
  },

  async close_tab(args, current) {
    if (!args.tabId) throw new Error('close_tab 需要 tabId。');
    const tabId = String(args.tabId);
    if (current.desktop) {
      const desktop = readDesktop();
      if (!desktop) throw new Error('桌面浏览器未连接。');
      const { tabs: mine } = await desktopTabsForSession(desktop, current.id);
      if (!mine.some((tab) => tab.id === tabId)) throw new Error('标签不属于当前会话。');
      await desktopRequest(desktop, '/tabs/close', {
        method: 'POST',
        body: { tabId },
        errorPrefix: '关闭桌面标签失败'
      });
      return { text: `已关闭标签 ${tabId}` };
    }
    const target = current.tabs?.get(tabId);
    if (!target) throw new Error('标签不属于当前会话。');
    current.tabs.delete(tabId);
    if (!target.isClosed()) await target.close().catch(() => {});
    if (current.page === target || !current.page || current.page.isClosed()) {
      const remaining = [...current.tabs.entries()].filter(([, page]) => page && !page.isClosed());
      if (remaining.length) {
        const [nextId, nextPage] = remaining[remaining.length - 1];
        current.activeTabId = nextId;
        attachPage(current, nextPage);
      } else {
        current.activeTabId = null;
        current.page = null;
      }
    }
    return { text: `已关闭标签 ${tabId}${current.activeTabId ? `（当前标签：${current.activeTabId}）` : ''}` };
  },

  async download(args, current) {
    refuseOffAllowlist(current);
    if (!args.path) throw new Error('download 需要 path。');
    const page = pageOf(current);
    const blocked = sensitiveMessage(page, '已限制自动下载', '请由用户接管后自行检查下载。');
    if (blocked) throw new Error(blocked);
    const { locator, hitPosition } = await prepareTarget(current, args, 'download', { requireHitTest: true });
    const clickOpts = { timeout: ACTION_TIMEOUT_MS };
    if (hitPosition) clickOpts.position = hitPosition;
    const [download] = await Promise.all([
      page.waitForEvent('download', { timeout: ACTION_TIMEOUT_MS }),
      stopIfPageGone(page, () => locator.click(clickOpts), current)
    ]);
    const suggested = sanitizeDownloadFilename(download.suggestedFilename(), 'download');

    const downloadUrl = download.url();
    const responseMeta = current.downloadResponses?.get(downloadUrl);
    let mimeType = responseMeta?.contentType || '';
    if (!mimeType && /^data:/i.test(downloadUrl)) {
      const match = /^data:([^;,]+)/i.exec(downloadUrl);
      if (match) mimeType = match[1];
    }
    const redirects = responseMeta?.redirects && responseMeta.redirects.length > 1 ? responseMeta.redirects : [];

    let tempPath = null;
    let headBytes = null;
    let sizeBytes = null;
    try {
      tempPath = await download.path().catch(() => null);
      if (tempPath && existsSync(tempPath)) {
        sizeBytes = statSync(tempPath).size;
        headBytes = readHeadBytes(tempPath, 512);
      }
    } catch {
      // 流模式暂未落盘时，saveAs 后再次复查
    }

    const safety = inspectDownloadSafety({
      filename: suggested,
      mimeType,
      headBytes,
      sizeBytes,
      maxBytes: MAX_DOWNLOAD_BYTES
    });
    if (!safety.allowed) {
      await download.cancel().catch(() => {});
      await download.delete().catch(() => {});
      throw new Error(safety.reason);
    }

    const destPath = join(dirname(String(args.path)), `${Date.now()}-${++downloadSequence}-${suggested}`);
    mkdirSync(dirname(destPath), { recursive: true });
    await download.saveAs(destPath);

    const savedSize = existsSync(destPath) ? statSync(destPath).size : (sizeBytes ?? 0);
    const savedHead = existsSync(destPath) ? readHeadBytes(destPath, 512) : headBytes;
    const postSafety = inspectDownloadSafety({
      filename: suggested,
      mimeType,
      headBytes: savedHead,
      sizeBytes: savedSize,
      maxBytes: MAX_DOWNLOAD_BYTES
    });
    if (!postSafety.allowed) {
      rmSync(destPath, { force: true });
      throw new Error(postSafety.reason);
    }

    const source = sanitizeDiagnosticUrl(page.url());
    const lines = [
      `已下载文件：${suggested}`,
      `文件大小：${savedSize} 字节`,
      `安全审查依据：${postSafety.basis}`,
      `来源页面：${source}`,
      `保存位置：${destPath}`
    ];
    if (redirects.length) lines.push(`重定向链：${redirects.join(' → ')}`);
    return {
      text: lines.join('\n'),
      url: source,
      title: await pageTitle(page),
      download: {
        name: suggested,
        path: destPath,
        mimeType: postSafety.mimeType || mimeType,
        sizeBytes: savedSize,
        basis: postSafety.basis,
        redirects
      }
    };
  },

  async fill(args, current) {
    refuseOffAllowlist(current);
    if (typeof args.text !== 'string') throw new Error('fill 需要 text。');
    const { locator, page, hitPosition } = await prepareTarget(current, args, 'fill');
    const meta = await locator.evaluate((el, patternSource) => {
      const sensitive = el.hasAttribute('data-sensitive') || el.type === 'hidden'
        || new RegExp(patternSource, 'i').test([el.type, el.autocomplete, el.name, el.id].join(' '));
      return {
        isNative: el.tagName === 'INPUT' || el.tagName === 'TEXTAREA',
        inputType: String(el.type || '').toLowerCase(),
        sensitive
      };
    }, SENSITIVE_FIELD_PATTERN_SOURCE).catch(() => ({ isNative: true, inputType: 'text', sensitive: false }));
    const replaceViaKeyboard = () => stopIfPageGone(page, async () => {
      await locator.click(hitPosition ? { position: hitPosition, timeout: ACTION_TIMEOUT_MS } : { timeout: ACTION_TIMEOUT_MS });
      await page.keyboard.press('ControlOrMeta+A');
      await page.keyboard.press('Backspace');
      if (args.text) await page.keyboard.insertText(args.text);
    }, current);
    try {
      if (meta.isNative) {
        await stopIfPageGone(page, () => locator.fill(args.text, { timeout: ACTION_TIMEOUT_MS }), current);
        if (!meta.sensitive && !['number', 'date', 'datetime-local', 'time', 'month', 'week', 'range', 'color'].includes(meta.inputType)) {
          const readback = await locator.inputValue().catch(() => null);
          if (readback !== null && readback !== args.text) await replaceViaKeyboard();
        }
      } else {
        await replaceViaKeyboard();
      }
      if (args.submit) await stopIfPageGone(page, () => page.keyboard.press('Enter'), current);
    } catch (error) {
      throw await elementMissing(error, args);
    }
    await settle(page);
    let verifyNote = '';
    if (meta.sensitive) {
      verifyNote = '（敏感值已遮挡）';
    } else if (meta.isNative) {
      const actual = await locator.inputValue().catch(() => null);
      if (actual !== null && actual !== args.text) {
        verifyNote = `（实际值已规范化/截断，长度 ${actual.length}）`;
      }
    }
    return { text: `已填入 ${args.ref || args.selector}${args.submit ? '并提交' : ''}${verifyNote}\n${await describePage(current)}` };
  },

  async type(args, current) {
    refuseOffAllowlist(current);
    if (typeof args.text !== 'string') throw new Error('type 需要 text。');
    const { locator, page, hitPosition } = await prepareTarget(current, args, 'type');
    const clickOpts = { timeout: ACTION_TIMEOUT_MS };
    if (hitPosition) clickOpts.position = hitPosition;
    try {
      await stopIfPageGone(page, async () => {
        await locator.click(clickOpts);
        if (args.clear) {
          await page.keyboard.press('ControlOrMeta+A');
          await page.keyboard.press('Backspace');
        }
        const chunks = args.text.match(/[\x20-\x7e]+|[^\x20-\x7e]+/g) || [];
        for (const chunk of chunks) {
          if (chunk === '\n') await page.keyboard.press('Enter');
          else if (/^[\x20-\x7e]+$/.test(chunk)) await page.keyboard.type(chunk, { delay: 15 });
          else await page.keyboard.insertText(chunk);
        }
        if (args.submit) await page.keyboard.press('Enter');
      }, current);
    } catch (error) {
      throw await elementMissing(error, args);
    }
    await settle(page);
    return { text: `已逐字输入到 ${args.ref || args.selector}${args.submit ? '并提交' : ''}\n${await describePage(current)}` };
  },

  async press(args, current) {
    refuseOffAllowlist(current);
    if (!args.key) throw new Error('press 需要 key，例如 Enter、Escape、Control+A。');
    await pageOf(current).keyboard.press(String(args.key));
    await settle(pageOf(current));
    return { text: `已按下 ${args.key}\n${await describePage(current)}` };
  },

  async select(args, current) {
    refuseOffAllowlist(current);
    const values = Array.isArray(args.values) ? args.values.map(String) : [];
    if (values.length === 0) throw new Error('select 需要 values。');
    const { locator, page } = await prepareTarget(current, args, 'select');
    try {
      // 字符串同时匹配 option 的 value 与可见文本。
      const chosen = await stopIfPageGone(page, () => locator.selectOption(values, { timeout: ACTION_TIMEOUT_MS }), current);
      await settle(page);
      return { text: `已选择 ${JSON.stringify(chosen)}\n${await describePage(current)}` };
    } catch (error) {
      throw await elementMissing(error, args);
    }
  },

  async check(args, current) {
    return setCheckedState(current, args, true);
  },

  async uncheck(args, current) {
    return setCheckedState(current, args, false);
  },

  async scroll(args, current) {
    refuseOffAllowlist(current);
    const page = pageOf(current);
    const dir = String(args.direction || 'down').toLowerCase();
    const vp = page.viewportSize() || DEFAULT_VIEWPORT;
    const step = Number.isFinite(Number(args.amount)) && Number(args.amount) > 0
      ? Math.round(Number(args.amount))
      : Math.round(vp.height * 0.75);
    const dx = dir === 'left' ? -step : dir === 'right' ? step : 0;
    const dy = dir === 'up' ? -step : dir === 'down' ? step : 0;

    if (args.ref || args.selector) {
      if (!args.direction && args.amount == null) {
        const { page: targetPage } = await prepareTarget(current, args, 'scroll');
        await settle(targetPage);
        return { text: `已滚动至目标元素 ${args.ref || args.selector}\n${await describePage(current)}` };
      }
      const { locator } = await prepareTarget(current, args, 'scroll');
      const moved = await locator.evaluate((el, { dx: deltaX, dy: deltaY }) => {
        el.scrollBy({ left: deltaX, top: deltaY, behavior: 'instant' });
        return { scrollTop: Math.round(el.scrollTop), scrollLeft: Math.round(el.scrollLeft) };
      }, { dx, dy });
      await settle(page);
      return { text: `已滚动容器 ${args.ref || args.selector}（top=${moved.scrollTop}, left=${moved.scrollLeft}）\n${await describePage(current)}` };
    }

    if (Number.isFinite(Number(args.x)) && Number.isFinite(Number(args.y))) {
      const { x, y } = await prepareCoordinates(current, args.x, args.y, 'scroll', `${dir} ${step}px`);
      await page.mouse.move(x, y);
      await page.mouse.wheel(dx, dy);
      await settle(page);
      return { text: `已在 (${x}, ${y}) 处向 ${dir} 滚动 ${step}px\n${await describePage(current)}` };
    }

    const pos = await page.evaluate(({ dx: deltaX, dy: deltaY }) => {
      window.scrollBy({ left: deltaX, top: deltaY, behavior: 'instant' });
      return {
        scrollX: Math.round(window.scrollX),
        scrollY: Math.round(window.scrollY),
        maxY: Math.max(0, Math.round(document.documentElement.scrollHeight - window.innerHeight))
      };
    }, { dx, dy });
    if (current.agentControlled) await syncControlOverlay(current, page);
    await settle(page);
    return { text: `已向 ${dir} 滚动（scrollY=${pos.scrollY}/${pos.maxY}）\n${await describePage(current)}` };
  },

  async drag(args, current) {
    refuseOffAllowlist(current);
    const page = pageOf(current);
    const hasSrcDom = Boolean(args.ref || args.selector);
    const hasDstDom = Boolean(args.targetRef || args.targetSelector);
    let srcLoc = null;
    let dstLoc = null;
    let sx;
    let sy;
    let tx;
    let ty;
    let draggableAttr = false;

    if (hasSrcDom) {
      const src = await prepareTarget(current, args, 'drag', { requireHitTest: true });
      srcLoc = src.locator;
      sx = src.x;
      sy = src.y;
      draggableAttr = await srcLoc.evaluate((el) => el.getAttribute('draggable') === 'true').catch(() => false);
    } else if (Number.isFinite(Number(args.x)) && Number.isFinite(Number(args.y))) {
      const coordSrc = await prepareCoordinates(current, args.x, args.y, 'drag', '起点');
      sx = coordSrc.x;
      sy = coordSrc.y;
    } else {
      throw new Error('drag 需要起点（ref/selector 或 x, y）。');
    }

    const dstArgs = {
      ref: args.targetRef,
      selector: args.targetSelector,
      frame: args.targetFrame || args.frame
    };
    if (hasDstDom) {
      const dst = await prepareTarget(current, dstArgs, 'drag', { requireHitTest: true });
      dstLoc = dst.locator;
      tx = dst.x;
      ty = dst.y;
      if (srcLoc && !draggableAttr) {
        const refreshed = await srcLoc.boundingBox().catch(() => null);
        if (refreshed) {
          sx = Math.round(refreshed.x + refreshed.width / 2);
          sy = Math.round(refreshed.y + refreshed.height / 2);
        }
      }
    } else if (Number.isFinite(Number(args.targetX)) && Number.isFinite(Number(args.targetY))) {
      const vp = page.viewportSize() || DEFAULT_VIEWPORT;
      tx = Math.min(Math.max(0, vp.width - 1), Math.max(0, Math.round(Number(args.targetX))));
      ty = Math.min(Math.max(0, vp.height - 1), Math.max(0, Math.round(Number(args.targetY))));
    } else {
      throw new Error('drag 需要终点（targetRef/targetSelector 或 targetX, targetY）。');
    }

    const steps = Math.min(40, Math.max(2, Math.round(Number(args.steps) || 12)));
    try {
      await stopIfPageGone(page, async () => {
        if (srcLoc && dstLoc && draggableAttr) {
          await srcLoc.dragTo(dstLoc, { timeout: ACTION_TIMEOUT_MS });
        } else {
          await page.mouse.move(sx, sy);
          await page.mouse.down();
          await page.mouse.move(tx, ty, { steps });
          await page.mouse.up();
        }
      }, current);
    } catch (error) {
      throw await elementMissing(error, args);
    }
    await prepareCoordinates(current, tx, ty, 'drag', '落点');
    await settle(page);
    return { text: `已从 (${sx}, ${sy}) 拖拽至 (${tx}, ${ty})\n${await describePage(current)}` };
  },

  async dialog(args, current) {
    if (typeof args.accept === 'boolean') {
      current.nextDialog = { accept: args.accept, promptText: String(args.promptText ?? '') };
      const modeText = args.accept
        ? `确认${args.promptText !== undefined && args.promptText !== '' ? `（输入 ${JSON.stringify(String(args.promptText))}）` : ''}`
        : '取消';
      return { text: `已预设下一次原生弹窗（alert/confirm/prompt）：${modeText}` };
    }
    const items = (current.dialogs || []).slice(-10);
    if (!items.length) return { text: '当前会话尚未捕获浏览器原生弹窗。' };
    return {
      text: items.map((d) => `[${d.type}] ${d.handled}${d.promptText ? ` (${JSON.stringify(d.promptText)})` : ''}: ${d.message}`).join('\n')
    };
  },

  async upload(args, current) {
    refuseOffAllowlist(current);
    const files = Array.isArray(args.files) ? args.files.map(String) : [];
    if (files.length === 0) throw new Error('upload 需要 files（绝对路径）。');
    for (const file of files) {
      if (!existsSync(file)) throw new Error(`文件不存在：${file}`);
    }
    const page = pageOf(current);
    const locator = locatorFor(current, args);
    const isFileInput = await locator.evaluate((el) => el.tagName === 'INPUT' && String(el.type || '').toLowerCase() === 'file')
      .catch(() => false);
    try {
      if (isFileInput) {
        await prepareTarget(current, args, 'upload', { skipVisibility: true });
        await stopIfPageGone(page, () => locator.setInputFiles(files, { timeout: ACTION_TIMEOUT_MS }), current);
      } else {
        const { hitPosition } = await prepareTarget(current, args, 'upload', { requireHitTest: true });
        const clickOpts = { timeout: ACTION_TIMEOUT_MS };
        if (hitPosition) clickOpts.position = hitPosition;
        const [chooser] = await Promise.all([
          page.waitForEvent('filechooser', { timeout: ACTION_TIMEOUT_MS }),
          stopIfPageGone(page, () => locator.click(clickOpts), current)
        ]);
        await chooser.setFiles(files);
      }
    } catch (error) {
      throw await elementMissing(error, args);
    }
    await settle(page);
    return { text: `已向 ${args.ref || args.selector} 上传 ${files.length} 个文件\n${await describePage(current)}` };
  },

  async screenshot(args, current) {
    if (!args.path) throw new Error('screenshot 需要 path。');
    const page = pageOf(current);
    mkdirSync(dirname(args.path), { recursive: true });
    let path = String(args.path);
    const sensitive = sensitivePageUrl(page.url(), SENSITIVE_RULES);
    const masks = sensitive
      ? [page.locator('body'), ...(args.ref || args.selector ? [locatorFor(current, args)] : [])]
      : await screenshotMasks(page);
    const options = { path, type: 'png', fullPage: Boolean(args.fullPage), mask: masks,
      maskColor: SENSITIVE_MASK_COLOR, style: AGENT_OVERLAY_STYLE };
    const capture = (opts) => {
      if (args.ref || args.selector) {
        const { fullPage: _ignored, ...elementOpts } = opts;
        return locatorFor(current, args).screenshot(elementOpts);
      }
      return page.screenshot(opts);
    };
    await capture(options);
    if (statSync(path).size > MAX_SCREENSHOT_BYTES) {
      path = path.replace(/\.png$/i, '') + '.jpg';
      await capture({ ...options, path, type: 'jpeg', quality: 70 });
    }
    return {
      text: `已截图${sensitive ? '（敏感登录/支付页已整页遮挡）' : ''}\n${await describePage(current)}`,
      screenshot: path,
      url: page.url(),
      title: await pageTitle(page),
      sensitive,
      ...sessionDiagnostics(current)
    };
  },

  async console(args, current) {
    const entries = current.console.slice();
    if (args.clear) current.console.length = 0;
    const filtered = args.level ? entries.filter((entry) => entry.type === args.level) : entries;
    const file = sessionLogPath(current);
    const diag = sessionDiagnostics(current);
    if (filtered.length === 0) return { text: `控制台没有记录。\n完整日志: ${file}`, ...diag };
    const shown = filtered.slice(-100);
    return {
      text: `控制台记录 ${filtered.length} 条（显示最近 ${shown.length} 条）。完整日志: ${file}\n`
        + shown.map((entry) => `[${entry.type}] ${sanitizeDiagnosticText(entry.text)}${entry.at ? ' @ ' + sanitizeDiagnosticUrl(entry.at) : ''}`).join('\n'),
      ...diag
    };
  },

  async network(args, current) {
    let entries = current.network.slice();
    if (args.failedOnly) entries = entries.filter((entry) => entry.failure || (entry.status && entry.status >= 400));
    if (args.filter) {
      const needle = String(args.filter).toLowerCase();
      entries = entries.filter((entry) => entry.url.toLowerCase().includes(needle));
    }
    if (args.clear) current.network.length = 0;
    const file = sessionLogPath(current);
    const diag = sessionDiagnostics(current);
    if (entries.length === 0) return { text: `没有匹配的网络请求。\n完整日志: ${file}`, ...diag };
    const shown = entries.slice(-100);
    return {
      text: `网络请求 ${entries.length} 条（显示最近 ${shown.length} 条）。完整日志: ${file}\n`
        + shown.map((entry) => `${entry.method} ${sanitizeDiagnosticUrl(entry.url)} → ${entry.failure ? 'FAILED ' + sanitizeDiagnosticText(entry.failure)
          : entry.status === null ? '(等待响应)' : entry.status} [${entry.type}]`).join('\n'),
      ...diag
    };
  },

  async wait(args, current) {
    const page = pageOf(current);
    const timeout = Math.min(Math.max(Number(args.ms) || 0, 0), 15000);
    if (args.url) {
      const target = String(args.url);
      try {
        await stopIfPageGone(page, () => page.waitForURL((u) => {
          const href = typeof u === 'string' ? u : u.href;
          return href === target || href.includes(target);
        }, { timeout: timeout || 8000 }), current, { allowNavigate: true });
      } catch (error) {
        if (current?.controlAborted) throw error;
        throw new Error(`等待 URL 包含 ${JSON.stringify(target)} 超时（当前：${sanitizeDiagnosticUrl(page.url())}）。`);
      }
      return { text: `URL 已匹配：${sanitizeDiagnosticUrl(page.url())}` };
    }
    if (args.text) {
      const state = args.state === 'hidden' ? 'hidden' : 'visible';
      try {
        await stopIfPageGone(page, () => page.getByText(String(args.text)).first().waitFor({ state, timeout: timeout || 8000 }), current, { allowNavigate: true });
      } catch (error) {
        if (current?.controlAborted) throw error;
        throw new Error(`等待文本 ${JSON.stringify(args.text)}（${state}）超时。`);
      }
      return { text: `文本状态已达成（${state}）：${args.text}` };
    }
    if (args.selector) {
      const state = args.state === 'hidden' ? 'hidden' : 'visible';
      try {
        await stopIfPageGone(page, () => page.locator(String(args.selector)).first().waitFor({ state, timeout: timeout || 8000 }), current, { allowNavigate: true });
      } catch (error) {
        if (current?.controlAborted) throw error;
        throw new Error(`等待元素 ${args.selector}（${state}）超时。`);
      }
      return { text: `元素状态已达成（${state}）：${args.selector}` };
    }
    await stopIfPageGone(page, () => page.waitForTimeout(timeout || 1000), current, { allowNavigate: true });
    return { text: `已等待 ${timeout || 1000}ms` };
  },

  async batch(args, current) {
    refuseOffAllowlist(current);
    const steps = Array.isArray(args.steps) ? args.steps : [];
    if (steps.length === 0 || steps.length > 50) {
      throw new Error('batch 的 steps 必须是 1–50 条动作数组。');
    }
    const allowed = new Set(['click', 'dblclick', 'right_click', 'hover', 'fill', 'type', 'press',
      'select', 'check', 'uncheck', 'scroll', 'drag', 'wait', 'dialog']);
    const stopOnError = args.stopOnError !== false;
    const delayMs = Math.min(1000, Math.max(0, Math.round(Number(args.delayMs) || 0)));
    pageOf(current);
    const summary = [];

    for (let i = 0; i < steps.length; i += 1) {
      if (current.controlAborted) {
        throw new Error(`用户已停止自动控制，批量操作在 ${i}/${steps.length} 步后中止。`);
      }
      const rawStep = steps[i] && typeof steps[i] === 'object' ? steps[i] : {};
      const rawAction = String(rawStep.op || rawStep.action || '').toLowerCase();
      if (!allowed.has(rawAction)) {
        throw new Error(`batch 第 ${i + 1} 步不支持动作：${rawAction || '(空)'}`);
      }
      const stepArgs = { ...rawStep };
      let op = rawAction;
      if (rawAction === 'dblclick') {
        op = 'click';
        stepArgs.clickCount = 2;
      } else if (rawAction === 'right_click') {
        op = 'click';
        stepArgs.button = 'right';
      }
      if (current.agentControlled) {
        current.controlStatus = `批量 ${i + 1}/${steps.length}：${controlStatusLabel(op, stepArgs)}`;
        await syncControlOverlay(current, pageOf(current));
      }
      try {
        const res = await handlers[op](stepArgs, current);
        const firstLine = String(res?.text || op).split('\n')[0];
        summary.push(`${i + 1}. [OK] ${firstLine}`);
      } catch (error) {
        if (current.controlAborted) {
          throw error;
        }
        const msg = sanitizeDiagnosticText(String(error?.message || error).split('\n')[0]);
        if (stopOnError) {
          throw new Error(`批量操作第 ${i + 1}/${steps.length} 步 (${rawAction}) 失败：${msg}`);
        }
        summary.push(`${i + 1}. [ERR] ${rawAction}: ${msg}`);
      }
      if (delayMs > 0 && i + 1 < steps.length) {
        if (current.controlAborted) {
          throw new Error(`用户已停止自动控制，批量操作在 ${i + 1}/${steps.length} 步后中止。`);
        }
        const activePage = pageOf(current);
        await stopIfPageGone(activePage, () => activePage.waitForTimeout(delayMs), current, { allowNavigate: true });
      }
    }
    const header = `已完成批量操作 ${steps.length} 步\n${await describePage(current)}`;
    return { text: `${header}\n${summary.join('\n')}` };
  },

  async record(args, current) {
    const mode = String(args.mode || 'status').toLowerCase();
    const page = current.page && !current.page.isClosed() ? current.page : null;
    if (mode === 'start') {
      const vp = page?.viewportSize() || DEFAULT_VIEWPORT;
      current.recording = {
        startedAt: Date.now(),
        width: vp.width,
        height: vp.height,
        quality: Math.min(75, Math.max(30, Math.round(Number(args.quality) || 55))),
        maxFrames: 180,
        minIntervalMs: 250,
        lastFrameAt: 0,
        capturing: false,
        frames: [],
        actions: []
      };
      if (page) {
        await captureRecordingFrame(current, 'record-start', true);
      }
      return {
        text: '已开始录制浏览器操作过程。',
        url: page ? page.url() : '',
        title: page ? await pageTitle(page) : ''
      };
    }
    if (mode === 'stop') {
      const rec = current.recording;
      if (!rec) {
        return { text: '当前没有进行中的浏览器录屏。' };
      }
      for (let wait = 0; wait < 16 && rec.capturing; wait += 1) {
        await new Promise((resolve) => setTimeout(resolve, 30));
      }
      if (page) {
        await captureRecordingFrame(current, 'record-stop', true);
      }
      current.recording = null;
      const endedAt = Date.now();
      let frames = rec.frames.slice();
      const buildPayload = (frameList) => JSON.stringify({
        version: 1,
        startedAt: rec.startedAt,
        endedAt,
        durationMs: Math.max(0, endedAt - rec.startedAt),
        viewport: { width: rec.width, height: rec.height },
        frames: frameList,
        actions: rec.actions
      });
      let serialized = buildPayload(frames);
      while (Buffer.byteLength(serialized, 'utf8') > 8 * 1024 * 1024 && frames.length > 2) {
        frames = frames.filter((_, idx) => idx === 0 || idx === frames.length - 1 || idx % 2 === 0);
        serialized = buildPayload(frames);
      }
      const outPath = args.path ? String(args.path) : join(process.cwd(), 'browser-recordings', `rec-${rec.startedAt}.replay.json`);
      mkdirSync(dirname(outPath), { recursive: true });
      writeFileSync(outPath, serialized, 'utf8');
      return {
        text: `已保存过程录像（${frames.length} 帧，${rec.actions.length} 条动作，耗时 ${Math.max(0, endedAt - rec.startedAt)}ms）\n录像文件: ${outPath}`,
        recording: {
          path: outPath,
          frames: frames.length,
          actions: rec.actions.length,
          durationMs: Math.max(0, endedAt - rec.startedAt)
        },
        url: page ? page.url() : '',
        title: page ? await pageTitle(page) : '',
        ...sessionDiagnostics(current)
      };
    }
    if (!current.recording) {
      return { text: '当前未在录制过程录像。' };
    }
    const rec = current.recording;
    return { text: `正在录制中（已采集 ${rec.frames.length} 帧，${rec.actions.length} 条动作）` };
  },

  async evaluate(args, current) {
    refuseOffAllowlist(current);
    if (!args.expression) throw new Error('evaluate 需要 expression。');
    const page = pageOf(current);
    const blocked = sensitiveMessage(page, '已禁止执行页面脚本', '请由用户接管完成操作后继续。');
    if (blocked) throw new Error(blocked);
    const value = await page.evaluate(String(args.expression));
    let text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
    if (text === undefined) text = 'undefined';
    if (text.length > 8000) text = text.slice(0, 8000) + '\n…（已截断）';
    return { text };
  },

  async describe(args, current) {
    const page = pageOf(current);
    const gate = await detectHumanGate(page);
    return {
      text: `${await describePage(current)}${gate ? `\n门控提示：${gate.reason}` : ''}`,
      url: page.url(),
      title: await pageTitle(page),
      gate,
      ...sessionDiagnostics(current)
    };
  },

  async 'clear-overlay'(args, current) {
    current.agentControlled = false;
    current.controlStatus = '';
    await removeControlOverlay(current.page);
    await notifyDesktopControl(current);
    emitControlState(current);
    return { text: '已清理控制指示覆盖层' };
  },

  async 'control-state'(args, current) {
    if (args.resume) current.controlAborted = false;
    if (current.controlAborted && args.agentControlled) {
      throw new Error('用户已点击停止控制，已暂停智能体操作。');
    }
    current.agentControlled = Boolean(args.agentControlled);
    current.controlStatus = String(args.status || (current.agentControlled ? '正在自动执行操作' : ''));
    if (current.page && !current.page.isClosed()) {
      await syncControlOverlay(current, current.page);
    }
    await notifyDesktopControl(current);
    emitControlState(current);
    return { text: current.agentControlled ? `已开启控制锁定：${current.controlStatus}` : '已释放控制锁定' };
  },

  async 'stop-control'(args, current) {
    await triggerControlStop(current, args.source || 'ui');
    return { text: '已停止智能体控制' };
  },

  async 'invalidate-refs'(args, current) {
    current.refFrames = {};
    const page = current.page;
    if (page && !page.isClosed()) {
      await page.evaluate(INVALIDATE_REFS_FN).catch(() => {});
    }
    return { text: '已失效旧元素引用' };
  },

  async close(args) {
    return { text: '已关闭' };
  },

  // ---- 镜像与窗口控制 -------------------------------------------------------------------
  async 'mirror-start'(args, current) {
    current.mirrorWidth = Math.min(1920, Math.max(320, Math.round(Number(args.maxWidth) || DEFAULT_MIRROR_WIDTH)));
    current.mirrorQuality = Math.min(90, Math.max(20, Math.round(Number(args.quality) || DEFAULT_MIRROR_QUALITY)));
    current.mirror = true;
    current.capture = args.capture !== false;
    if (current.capture) await ensureScreencast(current);
    else await stopScreencast(current);
    if (current.page && !current.page.isClosed()) await reportNavigation(current, current.page.url());
    return { text: current.capture ? '镜像已开启' : '截图已暂停' };
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

const AGENT_DRIVING_OPS = new Set([
  'navigate', 'new_tab', 'reload', 'back', 'forward', 'click', 'hover', 'fill', 'type', 'press',
  'select', 'check', 'uncheck', 'scroll', 'drag', 'dialog', 'batch', 'upload', 'download',
  'evaluate', 'switch_tab', 'close_tab', 'resize'
]);

const CONTROL_ONLY_OPS = new Set([
  'mirror-start', 'mirror-stop', 'focus', 'clear-overlay', 'invalidate-refs', 'control-state', 'stop-control'
]);

function controlStatusLabel(op, request) {
  const target = request.ref || request.selector || (Number.isFinite(Number(request.x)) ? `(${request.x},${request.y})` : '');
  const suffix = target ? ` ${String(target).slice(0, 32)}` : '';
  switch (op) {
    case 'navigate': return '打开页面';
    case 'new_tab': return '新建标签';
    case 'reload': return '刷新页面';
    case 'back': return '返回上一页';
    case 'forward': return '前进下一页';
    case 'click': return `点击${suffix}`;
    case 'hover': return `悬停${suffix}`;
    case 'fill': return `填写${suffix}`;
    case 'type': return `输入${suffix}`;
    case 'press': return `按键 ${String(request.key || '')}`.trim();
    case 'select': return `选择${suffix}`;
    case 'check': return `勾选${suffix}`;
    case 'uncheck': return `取消勾选${suffix}`;
    case 'scroll': return `滚动 ${String(request.direction || '页面')}`;
    case 'drag': return `拖拽${suffix}`;
    case 'dialog': return '预设弹窗处理';
    case 'batch': return `批量执行 ${Array.isArray(request.steps) ? request.steps.length : 0} 步`;
    case 'upload': return `上传文件${suffix}`;
    case 'download': return `下载文件${suffix}`;
    case 'evaluate': return '执行检查脚本';
    case 'switch_tab': return '切换标签';
    case 'close_tab': return '关闭标签';
    case 'resize': return '调整视口';
    default: return '正在自动执行操作';
  }
}

async function dispatch(request) {
  const op = request.op;
  if (op === 'ping' || op === 'probe' || op === 'open_window') return handlers[op]();
  if (op === 'close') {
    const existing = sessions.get(request.session);
    if (existing) {
      existing.recording = null;
      existing.agentControlled = false;
      existing.controlStatus = '';
      await notifyDesktopControl(existing);
      sessions.delete(request.session);
      try {
        // 外部 CDP 与桌面内置浏览器的 Context 属于共享上下文：只关我们开的那一个标签。
        if (existing.external || existing.desktop) await existing.page.close();
        else await existing.context.close();
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
  // 镜像控制、置前、控制锁与覆盖层/引用清理只作用于已存在的会话：浏览器没起来时不强制拉起。
  if (CONTROL_ONLY_OPS.has(op)) {
    if (op === 'mirror-start') {
      mirrorRequested.add(request.session);
      mirrorCapture.set(request.session, request.capture !== false);
    }
    if (op === 'mirror-stop') {
      mirrorRequested.delete(request.session);
      mirrorCapture.delete(request.session);
    }
    const existing = sessions.get(request.session);
    if (!existing || existing.dead) {
      return { text: op === 'mirror-start' ? '镜像已登记，浏览器启动后自动开始' : '没有活动页面' };
    }
    return handlers[op](request, existing);
  }
  const handler = handlers[op];
  if (!handler) throw new Error(`未知操作：${op}`);
  if (request.userInitiated && op === 'navigate' && !loopbackOrigin(request.url)) {
    request.approvedExternal = true;
  }
  let current;
  const existing = sessions.get(request.session);
  // 外部 CDP 会话本身就是用户的真实浏览器，不再另开隔离上下文。
  if (op === 'navigate' && request.approvedExternal && !existing?.externalOrigin && !existing?.external) {
    // 外网任务使用独立上下文；关闭本地旧上下文，但不关闭用户桌面浏览器。
    if (existing && !existing.desktop) await existing.context.close();
    current = await createLocalSession(request.session, await getBrowser());
  } else {
    current = await session(request.session);
  }
  if (!request.userInitiated && AGENT_DRIVING_OPS.has(op)) {
    if (current.controlAborted) {
      throw new Error('用户已停止自动控制，请等待用户恢复后再继续操作。');
    }
    const status = controlStatusLabel(op, request);
    const newlyActivated = !current.agentControlled;
    current.agentControlled = true;
    current.controlStatus = status;
    if (newlyActivated) {
      emitControlState(current);
      await notifyDesktopControl(current);
      if (current.page && !current.page.isClosed()) {
        await syncControlOverlay(current, current.page);
      }
    }
  }
  if (current.externalOrigin && (externalMutations.has(op) || ['mouse', 'keys'].includes(op))) {
    if (!request.userInitiated) requireExternalApproval(request, current.page.url());
    current.actionApproved = true;
    try { return await handler(request, current); }
    finally { current.actionApproved = false; }
  }
  return handler(request, current);
}

async function handle(request) {
  try {
    const result = await dispatch(request);
    if (result && typeof result === 'object' && request.session && !['ping', 'probe', 'open_window', 'shutdown', 'close'].includes(request.op) && !CONTROL_ONLY_OPS.has(request.op)) {
      const current = sessions.get(request.session);
      if (current) {
        if (!result.url && current.page && !current.page.isClosed()) {
          result.url = current.page.url();
        }
        if (result.consoleErrors === undefined) {
          const diag = sessionDiagnostics(current);
          result.consoleErrors = diag.consoleErrors;
          result.failedRequests = diag.failedRequests;
          result.blockedOrigins = diag.blockedOrigins;
        }
      }
    }
    if (typeof result?.text === 'string') result.text = sanitizeDiagnosticText(result.text);
    if (typeof result?.url === 'string') result.url = sanitizeDiagnosticUrl(result.url);
    send({ id: request.id, ok: true, ...result });
  } catch (error) {
    send({ id: request.id, ok: false, error: sanitizeDiagnosticText(String(error && error.message ? error.message : error).split('\n')[0]) });
  }
}

// 同一会话的操作串行，不同会话并行；停止控制信号不排队，立即抢占中止。
function enqueue(request) {
  if (request.op === 'stop-control') {
    handle(request);
    return;
  }
  const key = request.session || '_';
  const previous = queues.get(key) || Promise.resolve();
  const next = previous.then(() => handle(request));
  queues.set(key, next.catch(() => {}));
}

export { SNAPSHOT_SCRIPT };

const invokedDirectly = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (!invokedDirectly) {
  // 测试只借用快照脚本，不占用标准输入。
} else {
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
    for (const slot of ['default', 'headed']) {
      const browser = managedBrowsers[slot] ? await managedBrowsers[slot] : null;
      if (browser) await browser.close();
    }
  } catch {
    // 退出时忽略
  }
  process.exit(0);
});
process.on('unhandledRejection', (error) => {
  process.stderr.write('unhandledRejection: ' + String(error && error.stack ? error.stack : error) + '\n');
});
send({ id: 0, ok: true, text: 'ready' });
}
