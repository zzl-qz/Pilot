import { installMicrophonePermissions } from './media-permission.mjs';
import { app, BrowserWindow, WebContentsView, ipcMain, session, Menu, nativeImage, systemPreferences, dialog, clipboard } from 'electron';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { hostname } from 'node:os';
import { formatPageLogs, markerPath, partitionName, snapBounds, userPageUrl } from './protocol.mjs';
import { offlinePage } from './offline-page.mjs';
import { windowSizeMenuItem, workbenchMenuItems, workbenchShortcut } from './platform-menu.mjs';


const here = dirname(fileURLToPath(import.meta.url));
const smoke = process.env.PILOT_DESKTOP_SMOKE === '1';
app.setName('Pilot');
process.on('uncaughtException', (error) => {
  process.stderr.write('uncaught: ' + (error && error.stack || error) + '\n');
});
process.on('unhandledRejection', (error) => {
  process.stderr.write('unhandled: ' + (error && error.stack || error) + '\n');
});

function browserHome() {
  if (process.env.PILOT_BROWSER_HOME) return process.env.PILOT_BROWSER_HOME;
  const starts = [process.cwd(), here];
  for (const start of starts) {
    let dir = start;
    for (let i = 0; i < 6; i++) {
      if (existsSync(join(dir, 'pom.xml')) && existsSync(join(dir, 'pilot-server', 'pom.xml'))) {
        return join(dir, '.pilot', 'browser');
      }
      const parent = dirname(dir);
      if (parent === dir) break;
      dir = parent;
    }
  }
  return join(here, '.pilot', 'browser');
}

const cdpPort = Number(process.env.PILOT_DESKTOP_CDP_PORT) || (42000 + (process.pid % 1000));
app.commandLine.appendSwitch('remote-debugging-port', String(cdpPort));
app.commandLine.appendSwitch('remote-debugging-address', '127.0.0.1');

const token = randomBytes(24).toString('hex');
const tabs = new Map();
const sessionTabs = new Map();
let shell = null;
let activeId = null;
let workspace = '';
let endpointFile = '';
const recents = [];
const tabsByContentsId = new Map();
let pageHooksReady = false;

function shellUrl() {
  return process.env.PILOT_URL || 'http://127.0.0.1:9999';
}

function activeTab() {
  return tabs.get(activeId) || null;
}

function historyOf(contents) {
  const history = contents.navigationHistory;
  if (history && typeof history.canGoBack === 'function') {
    return { canGoBack: history.canGoBack(), canGoForward: history.canGoForward() };
  }
  return {
    canGoBack: typeof contents.canGoBack === 'function' ? contents.canGoBack() : false,
    canGoForward: typeof contents.canGoForward === 'function' ? contents.canGoForward() : false
  };
}

function tabTitle(tab) {
  const contents = tab.view.webContents;
  const url = contents.getURL();
  const title = contents.getTitle();
  if (!url || url.includes('/marker/') || !title || title === 'pilot' || title === '新标签') {
    try {
      const host = new URL(url).hostname;
      if (host && !url.includes('/marker/')) return host;
    } catch { /* 还没有地址 */ }
    return '新标签';
  }
  return title;
}

function state() {
  const current = activeTab();
  const nav = current ? historyOf(current.view.webContents) : { canGoBack: false, canGoForward: false };
  return {
    tabs: [...tabs.values()].map((tab) => ({
      id: tab.id,
      title: tabTitle(tab),
      url: tab.view.webContents.getURL(),
      loading: tab.view.webContents.isLoading(),
      favicon: tab.favicon || '',
      sessionId: tab.sessionId || null,
      detached: Boolean(tab.detached),
      emulated: tab.emulated || null
    })),
    activeId,
    url: current ? current.view.webContents.getURL() : '',
    canGoBack: nav.canGoBack,
    canGoForward: nav.canGoForward,
    loading: Boolean(current && current.view.webContents.isLoading()),
    cover: current && !current.attached ? current.cover || null : null,
    emulated: current?.emulated || null,
    error: current?.loadError || '',
    recents: recents.slice(0, 8)
  };
}

function recentsFile() {
  return join(browserHome(), 'recents.json');
}

function loadRecents() {
  try {
    const data = JSON.parse(readFileSync(recentsFile(), 'utf8'));
    if (!Array.isArray(data)) return;
    recents.splice(0, recents.length, ...data.filter((item) => item && item.url).slice(0, 12));
  } catch {
    // 第一次打开没有这份记录
  }
}

function saveRecents() {
  try {
    mkdirSync(browserHome(), { recursive: true });
    writeFileSync(recentsFile(), JSON.stringify(recents.slice(0, 12)));
  } catch {
    // 写失败不影响浏览
  }
}

function rememberRecent(contents) {
  const url = contents.getURL();
  if (!url || url.includes('/marker/') || url.startsWith('about:') || url.startsWith('data:')) return;
  let title = contents.getTitle() || '';
  if (!title || title === url || title === 'pilot' || title === '新标签') {
    try { title = new URL(url).hostname; } catch { title = url; }
  }
  const tab = tabsByContentsId.get(contents.id);
  const favicon = tab?.favicon || '';
  const next = [{ url, title, favicon }, ...recents.filter((item) => item.url !== url)].slice(0, 12);
  recents.splice(0, recents.length, ...next);
  saveRecents();
}

function broadcast() {
  if (shell && !shell.isDestroyed()) {
    shell.webContents.send('pilot-browser-state', state());
  }
}

function place(tab) {
  if (!tab || tab.detached || tab.view.webContents.isDestroyed()) return;
  const bounds = tab.bounds || { x: 0, y: 0, width: 0, height: 0, visible: false };
  const show = tab.id === activeId && bounds.visible && shell && !shell.isDestroyed();
  if (show) {
    if (!tab.attached && shell) {
      shell.contentView.addChildView(tab.view);
      tab.attached = true;
    }
    tab.view.setVisible(true);
    tab.view.setBounds({ x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height });
    tab.cover = null;
    return;
  }
  if (tab.attached && shell && !shell.isDestroyed()) {
    rememberCover(tab);
    shell.contentView.removeChildView(tab.view);
    tab.attached = false;
  }
  tab.view.setVisible(false);
}

function rememberCover(tab) {
  if (tab.covering || tab.view.webContents.isDestroyed()) return;
  tab.covering = true;
  tab.view.webContents.capturePage().then((image) => {
    tab.cover = image.toDataURL();
    broadcast();
  }).catch(() => {
    // 页面正在关闭
  }).finally(() => {
    tab.covering = false;
  });
}

function pushLog(list, item) {
  list.push(item);
  if (list.length > 200) list.shift();
}

function hookPageSession(ses) {
  if (pageHooksReady) return;
  pageHooksReady = true;
  const note = (details) => {
    const tab = details.webContentsId == null ? null : tabsByContentsId.get(details.webContentsId);
    if (!tab || !details.url || details.url.startsWith('data:')) return;
    const entry = {
      method: details.method,
      url: details.url,
      statusCode: details.statusCode || 0,
      error: details.error || ''
    };
    pushLog(tab.network, entry);
    if (shell && !shell.isDestroyed() && tab.id === activeId) {
      shell.webContents.send('pilot-browser-log', { network: true, line: formatPageLogs([entry], 'network') });
    }
  };
  ses.webRequest.onCompleted(note);
  ses.webRequest.onErrorOccurred(note);
  ses.on('will-download', (_event, item) => {
    const dir = join(browserHome(), 'downloads');
    mkdirSync(dir, { recursive: true });
    const name = item.getFilename() || 'download';
    item.setSavePath(join(dir, `${Date.now()}-${name}`));
    item.once('done', (_done, state) => {
      if (shell && !shell.isDestroyed()) {
        shell.webContents.send('pilot-browser-download', { state, name, path: item.getSavePath() });
      }
    });
  });
}

function wire(tab) {
  const contents = tab.view.webContents;
  tabsByContentsId.set(contents.id, tab);
  const refresh = () => broadcast();
  const noted = () => {
    rememberRecent(contents);
    refresh();
  };
  contents.on('did-navigate', noted);
  contents.on('did-navigate-in-page', refresh);
  contents.on('page-title-updated', noted);
  contents.on('did-start-loading', () => {
    tab.loadError = '';
    refresh();
  });
  contents.on('did-stop-loading', refresh);
  contents.on('did-fail-load', (_event, code, description, url, isMainFrame) => {
    if (!isMainFrame || code === -3) return;
    tab.loadError = description ? `${description}（${url || ''}）` : `页面没有打开（${code}）`;
    refresh();
  });
  contents.on('page-favicon-updated', (_event, favicons) => {
    tab.favicon = Array.isArray(favicons) && favicons.length ? favicons[0] : '';
    rememberRecent(contents);
    refresh();
  });
  contents.on('context-menu', (_event, params) => {
    const template = [
      {
        label: '加进输入框',
        enabled: Boolean(params.selectionText),
        click: () => shell?.webContents.send('pilot-browser-quote', String(params.selectionText || '').slice(0, 4000))
      },
      { type: 'separator' },
      { label: '复制', role: 'copy', enabled: params.editFlags.canCopy },
      { label: '粘贴', role: 'paste', enabled: params.editFlags.canPaste }
    ];
    Menu.buildFromTemplate(template).popup();
  });
  contents.on('console-message', (details, level, message, line, sourceId) => {
    const text = details?.message || message || '';
    if (!text) return;
    const entry = {
      level: details?.level || ['debug', 'info', 'warning', 'error'][level] || 'info',
      message: text,
      lineNumber: details?.lineNumber || line || 0,
      sourceId: details?.sourceId || sourceId || ''
    };
    pushLog(tab.console, entry);
    if (shell && !shell.isDestroyed() && tab.id === activeId) {
      shell.webContents.send('pilot-browser-log', { network: false, line: formatPageLogs([entry], 'console') });
    }
  });
  contents.setWindowOpenHandler(({ url }) => {
    const next = userPageUrl(url);
    if (next) createTab({ url: next, sessionId: null });
    return { action: 'deny' };
  });
  installWorkbenchKeys(contents);
  contents.on('before-input-event', (event, input) => {
    const key = String(input.key || '').toLowerCase();
    const mod = input.meta || input.control;
    if (!mod || input.type !== 'keyDown') return;
    if (key === 'l') {
      event.preventDefault();
      shell?.webContents.send('pilot-browser-focus-address');
    } else if (key === 't') {
      event.preventDefault();
      createTab({ url: null, sessionId: null });
    } else if (key === 'w') {
      event.preventDefault();
      closeTab(tab.id);
    }
  });
}

function createTab({ url, sessionId }) {
  if (!shell) throw new Error('窗口还没准备好');
  const id = randomBytes(8).toString('hex');
  const view = new WebContentsView({
    webPreferences: {
      session: session.fromPartition(partitionName(workspace)),
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false,
      plugins: true
    }
  });
  view.setBackgroundColor('#111111');
  view.setVisible(false);
  view.setBounds({ x: 0, y: 0, width: 0, height: 0 });
  const tab = {
    id, view, sessionId: sessionId || null, bounds: null, detached: false, window: null,
    attached: false, cover: null, emulated: null, covering: false,
    console: [], network: [], loadError: '', favicon: ''
  };
  tabs.set(id, tab);
  if (sessionId) sessionTabs.set(sessionId, id);
  hookPageSession(session.fromPartition(partitionName(workspace)));
  shell.contentView.addChildView(view);
  tab.attached = true;
  wire(tab);
  activeId = id;
  view.webContents.loadURL(`http://127.0.0.1:${controlPort}${markerPath(id)}`);
  if (url) {
    view.webContents.once('did-finish-load', () => {
      const next = userPageUrl(url);
      if (next) view.webContents.loadURL(next);
    });
  }
  place(tab);
  broadcast();
  return tab;
}

function ensureTab(sessionId, options = {}) {
  if (!options.fresh) {
    const existingId = sessionId ? sessionTabs.get(sessionId) : null;
    const existing = existingId ? tabs.get(existingId) : null;
    if (existing) {
      activeId = existing.id;
      place(existing);
      broadcast();
      return existing;
    }
  }
  return createTab({ url: options.url || null, sessionId: sessionId || null });
}

function emulate(tab, width, height) {
  const contents = tab?.view.webContents;
  if (!contents || contents.isDestroyed()) return;
  if (!width || !height) {
    contents.disableDeviceEmulation?.();
    tab.emulated = null;
  } else {
    contents.enableDeviceEmulation?.({
      screenPosition: 'mobile',
      screenSize: { width, height },
      viewSize: { width, height },
      deviceScaleFactor: 0,
      scale: 1
    });
    tab.emulated = { width, height };
  }
  broadcast();
}

function pickElement(tab) {
  // 隔离世界里的监听，页面脚本删不掉；只认用户真实点击。
  return tab.view.webContents.executeJavaScriptInIsolatedWorld(999, [{ code: `new Promise((resolve) => {
    const onClick = (event) => {
      if (!event.isTrusted) return;
      event.preventDefault();
      event.stopPropagation();
      document.removeEventListener('click', onClick, true);
      const el = event.target;
      const text = String(el.innerText || el.value || '').replace(/\\s+/g, ' ').trim().slice(0, 180);
      const selector = el.id ? '#' + CSS.escape(el.id) : el.tagName.toLowerCase();
      resolve([
        '页面元素：' + el.tagName.toLowerCase() + (text ? ' 文本 ' + JSON.stringify(text) : ''),
        '选择器：' + selector
      ].join('\\n'));
    };
    document.addEventListener('click', onClick, true);
  })` }]);
}

function zoomBy(delta) {
  const contents = activeTab()?.view.webContents;
  if (!contents || contents.isDestroyed()) return;
  const next = Math.min(2, Math.max(0.5, (contents.getZoomFactor() || 1) + delta));
  contents.setZoomFactor(next);
}

function closeTab(id) {
  const tab = tabs.get(id);
  if (!tab) return;
  tabs.delete(id);
  if (!tab.view.webContents.isDestroyed()) tabsByContentsId.delete(tab.view.webContents.id);
  if (tab.sessionId && sessionTabs.get(tab.sessionId) === id) sessionTabs.delete(tab.sessionId);
  if (tab.detached && tab.window && !tab.window.isDestroyed()) tab.window.close();
  else if (shell && !shell.isDestroyed()) shell.contentView.removeChildView(tab.view);
  if (!tab.view.webContents.isDestroyed()) tab.view.webContents.close();
  if (activeId === id) activeId = tabs.keys().next().value || null;
  place(activeTab());
  broadcast();
}

function selectTab(id) {
  if (!tabs.has(id)) return;
  activeId = id;
  for (const tab of tabs.values()) place(tab);
  broadcast();
}

function detachActive() {
  const tab = activeTab();
  if (!tab || tab.detached || !shell) return;
  shell.contentView.removeChildView(tab.view);
  const win = new BrowserWindow({
    width: 1100,
    height: 760,
    title: 'Pilot 浏览器',
    backgroundColor: '#ffffff'
  });
  tab.detached = true;
  tab.window = win;
  win.contentView.addChildView(tab.view);
  const fit = () => {
    const [width, height] = win.getContentSize();
    tab.view.setBounds({ x: 0, y: 0, width, height });
    tab.view.setVisible(true);
  };
  fit();
  win.on('resize', fit);
  win.on('closed', () => {
    tab.detached = false;
    tab.window = null;
    if (shell && !shell.isDestroyed() && !tab.view.webContents.isDestroyed()) {
      shell.contentView.addChildView(tab.view);
      place(tab);
    }
    broadcast();
  });
  broadcast();
}

let controlPort = 0;

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => {
      if (chunks.length === 0) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch (error) {
        reject(error);
      }
    });
    request.on('error', reject);
  });
}

function startControl() {
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    if (request.method === 'GET' && url.pathname.startsWith('/marker/')) {
      const id = decodeURIComponent(url.pathname.slice('/marker/'.length));
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      response.end(`<!doctype html><title>新标签</title><body style="margin:0;background:#111"><p id="ready" data-tab="${id}" hidden>pilot-tab-${id}</p></body>`);
      return;
    }
    if (request.headers['x-pilot-token'] !== token) {
      response.writeHead(401);
      response.end('unauthorized');
      return;
    }
    try {
      if (request.method === 'POST' && url.pathname === '/tabs') {
        const body = await readBody(request);
        const tab = ensureTab(body.sessionId || null, { fresh: Boolean(body.fresh), url: body.url || null });
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify({ tabId: tab.id, tabs: state().tabs }));
        return;
      }
      if (request.method === 'POST' && url.pathname === '/tabs/select') {
        const body = await readBody(request);
        selectTab(body.tabId);
        const tab = tabs.get(body.tabId);
        if (tab?.sessionId) sessionTabs.set(tab.sessionId, tab.id);
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify(state()));
        return;
      }
      if (request.method === 'POST' && url.pathname === '/tabs/close') {
        const body = await readBody(request);
        closeTab(body.tabId);
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify(state()));
        return;
      }
      if (request.method === 'POST' && url.pathname === '/emulate') {
        const body = await readBody(request);
        const tab = body.tabId ? tabs.get(body.tabId) : activeTab();
        emulate(tab, Number(body.width) || 0, Number(body.height) || 0);
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify({ ok: true, emulated: tab?.emulated || null }));
        return;
      }
      if (request.method === 'GET' && url.pathname === '/tabs') {
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify(state()));
        return;
      }
      response.writeHead(404);
      response.end('not found');
    } catch (error) {
      response.writeHead(500);
      response.end(String(error.message || error));
    }
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      controlPort = server.address().port;
      resolve(server);
    });
  });
}

function writeEndpoint() {
  const home = browserHome();
  mkdirSync(home, { recursive: true });
  endpointFile = join(home, 'desktop-browser.json');
  writeFileSync(endpointFile, JSON.stringify({
    pid: process.pid,
    cdpPort,
    controlPort,
    token,
    host: hostname()
  }));
}

function clearEndpoint() {
  if (endpointFile) rmSync(endpointFile, { force: true });
}

ipcMain.handle('pilot-browser-ensure', (_event, sessionId) => {
  ensureTab(sessionId || null);
  return state();
});
ipcMain.handle('pilot-browser-navigate', (_event, url) => {
  const tab = activeTab() || createTab({ url: null, sessionId: null });
  const next = userPageUrl(url);
  if (!next) return { ok: false, error: '地址无效' };
  tab.view.webContents.loadURL(next);
  return { ok: true, url: next };
});
ipcMain.handle('pilot-browser-go', (_event, action) => {
  const contents = activeTab()?.view.webContents;
  if (!contents) return { ok: false };
  const history = contents.navigationHistory;
  if (action === 'back') {
    if (typeof history?.goBack === 'function') history.goBack();
    else contents.goBack?.();
  } else if (action === 'forward') {
    if (typeof history?.goForward === 'function') history.goForward();
    else contents.goForward?.();
  }
  else if (action === 'reload') contents.reload();
  else if (action === 'stop') contents.stop();
  return { ok: true };
});
ipcMain.handle('pilot-browser-new', () => {
  createTab({ url: null, sessionId: null });
  return state();
});
ipcMain.handle('pilot-browser-close', (_event, id) => {
  closeTab(id);
  return state();
});
ipcMain.handle('pilot-browser-select', (_event, id) => {
  selectTab(id);
  return state();
});
ipcMain.handle('pilot-browser-detach', () => {
  detachActive();
  return state();
});
ipcMain.handle('pilot-browser-menu', () => new Promise((resolve) => {
  let settled = false;
  const finish = (action) => {
    if (settled) return;
    settled = true;
    resolve(action);
  };
  const menu = Menu.buildFromTemplate([
    { label: '页内查找', click: () => finish('find') },
    { label: '放大', click: () => { zoomBy(0.1); finish('zoom'); } },
    { label: '缩小', click: () => { zoomBy(-0.1); finish('zoom'); } },
    { label: '实际大小', click: () => { zoomBy(1 - (activeTab()?.view.webContents.getZoomFactor() || 1)); finish('zoom'); } },
    { type: 'separator' },
    { label: '选择页面元素', click: () => finish('pick') },
    { label: '在独立窗口打开', click: () => { detachActive(); finish('detach'); } },
    { label: '开发工具', click: () => { activeTab()?.view.webContents.openDevTools({ mode: 'detach' }); finish('devtools'); } },
    { type: 'separator' },
    { label: '页面控制台', click: () => finish('console') },
    { label: '网络记录', click: () => finish('network') }
  ]);
  menu.popup({ callback: () => finish('') });
}));
ipcMain.handle('pilot-browser-find', (_event, text) => {
  const contents = activeTab()?.view.webContents;
  if (!contents || contents.isDestroyed()) return { ok: false };
  const query = String(text || '');
  if (!query) {
    contents.stopFindInPage('clearSelection');
    return { ok: true };
  }
  contents.findInPage(query);
  return { ok: true };
});
ipcMain.handle('pilot-browser-logs', (_event, network) => {
  const tab = activeTab();
  if (!tab) return { ok: false, text: network ? '没有网络记录' : '没有控制台记录' };
  return { ok: true, text: formatPageLogs(network ? tab.network : tab.console, network ? 'network' : 'console') };
});
ipcMain.handle('pilot-browser-devtools', () => {
  activeTab()?.view.webContents.openDevTools({ mode: 'detach' });
  return { ok: true };
});
ipcMain.handle('pilot-browser-pick', () => {
  const tab = activeTab();
  if (!tab) return { ok: false, error: '没有打开的页面' };
  return pickElement(tab).then((text) => ({ ok: true, text })).catch((error) => ({ ok: false, error: String(error.message || error) }));
});
ipcMain.handle('pilot-browser-reset-emulation', () => {
  emulate(activeTab(), 0, 0);
  return state();
});
ipcMain.on('pilot-browser-bounds', (_event, bounds) => {
  const tab = activeTab();
  if (!tab || !shell || shell.isDestroyed()) return;
  const zoom = shell.webContents.getZoomFactor() || 1;
  const [contentWidth, contentHeight] = shell.getContentSize();
  const snapped = snapBounds({
    x: Number(bounds?.x) * zoom,
    y: Number(bounds?.y) * zoom,
    width: Number(bounds?.width) * zoom,
    height: Number(bounds?.height) * zoom,
    visible: bounds?.visible
  });
  const x = Math.min(snapped.x, contentWidth);
  const y = Math.min(snapped.y, contentHeight);
  tab.bounds = {
    x,
    y,
    width: Math.max(0, Math.min(snapped.width, contentWidth - x)),
    height: Math.max(0, Math.min(snapped.height, contentHeight - y)),
    visible: snapped.visible
  };
  place(tab);
});
ipcMain.on('pilot-browser-workspace', (_event, value) => {
  workspace = String(value || '');
});

function appIcon() {
  const file = join(here, 'assets', 'icon.png');
  if (!existsSync(file)) return undefined;
  const image = nativeImage.createFromPath(file);
  return image.isEmpty() ? undefined : image;
}

let workbenchReady = false;
function dispatchWorkbenchCommand(command) {
  if (!shell || shell.isDestroyed()) return;
  if (workbenchReady) shell.webContents.send('pilot-workbench-command', command);
  else if (shell.webContents.getURL().startsWith('data:')) shell.loadURL(shellUrl()).catch(() => {});
  else shell.webContents.reloadIgnoringCache();
}
function installWorkbenchKeys(contents) {
  contents.on('before-input-event', (event, input) => {
    // 合成输入与长按也拦住默认菜单动作，避免绕过草稿保护反复重载。
    const command = workbenchShortcut({ ...input, isComposing: false, isAutoRepeat: false });
    if (command) {
      event.preventDefault();
      if (!input.isComposing && !input.isAutoRepeat) dispatchWorkbenchCommand(command);
    }
  });
}

async function openMobileConnection() {
  if (!shell || shell.isDestroyed()) throw new Error('请先打开 Pilot 工作台');
  shell.show(); shell.focus();
  shell.webContents.send('pilot-mobile-open');
}

function trustedWorkbench(event) {
  try {
    return shell && !shell.isDestroyed() && event.sender === shell.webContents
      && event.senderFrame === shell.webContents.mainFrame
      && new URL(event.senderFrame.url).origin === new URL(shellUrl()).origin;
  } catch { return false; }
}

ipcMain.on('pilot-workbench-ready', event => { if (trustedWorkbench(event)) workbenchReady = true; });
ipcMain.handle('pilot-workbench-reload', event => {
  if (!trustedWorkbench(event)) return { ok: false, error: '请从 Pilot 工作台重新载入界面' };
  shell.webContents.reloadIgnoringCache();
  return { ok: true };
});

ipcMain.handle('pilot-mobile-open', async event => {
  if (!trustedWorkbench(event)) return { ok: false, error: '请从 Pilot 工作台打开手机连接。' };
  await openMobileConnection();
  return { ok: true };
});

ipcMain.handle('pilot-mobile-copy', async (event, code) => {
  if (!trustedWorkbench(event) || typeof code !== 'string' || !/^pilot:\/\/pair\/[A-Za-z0-9_-]{20,8192}$/.test(code)) {
    return { ok: false };
  }
  try {
    // 只写入用户选择的配对码，不向页面开放系统剪贴板读取能力。
    if (typeof clipboard.writeText === 'function') clipboard.writeText(code);
    else await clipboard.write([{ 'text/plain': new Blob([code], { type: 'text/plain' }) }]);
    return { ok: true };
  } catch { return { ok: false }; }
});

function installMenu() {
  if (smoke) return;
  const template = [
    ...(process.platform === 'darwin' ? [{
      label: 'Pilot',
      submenu: [
        { role: 'about', label: '关于 Pilot' },
        { type: 'separator' },
        { role: 'hide', label: '隐藏 Pilot' },
        { role: 'hideOthers', label: '隐藏其他' },
        { role: 'unhide', label: '全部显示' },
        { type: 'separator' },
        { role: 'quit', label: '退出 Pilot' }
      ]
    }] : []),
    {
      label: '编辑',
      submenu: [
        { role: 'undo', label: '撤销' },
        { role: 'redo', label: '重做' },
        { type: 'separator' },
        { role: 'cut', label: '剪切' },
        { role: 'copy', label: '复制' },
        { role: 'paste', label: '粘贴' },
        { role: 'pasteAndMatchStyle', label: '粘贴并匹配样式' },
        { role: 'selectAll', label: '全选' }
      ]
    },
    {
      label: '视图',
      submenu: [
        ...workbenchMenuItems(dispatchWorkbenchCommand),
        { type: 'separator' },
        { role: 'zoomIn', label: '放大界面' },
        { role: 'zoomOut', label: '缩小界面' },
        { role: 'resetZoom', label: '恢复实际大小' },
        { type: 'separator' },
        { role: 'togglefullscreen', label: '进入全屏' }
      ]
    },
    {
      label: '设备',
      submenu: [{ label: '连接手机…', click: async () => {
        try { await openMobileConnection(); }
        catch (error) { dialog.showErrorBox('无法连接手机', error.message); }
      } }],
    },
    {
      label: '窗口',
      submenu: [
        { role: 'minimize', label: '最小化' },
        windowSizeMenuItem(),
        { type: 'separator' },
        { role: 'close', label: '关闭窗口' }
      ]
    }
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

app.whenReady().then(async () => {
  loadRecents();
  await startControl();
  const icon = appIcon();
  if (icon && process.platform === 'darwin' && app.dock) app.dock.setIcon(icon);
  app.setAboutPanelOptions({ applicationName: 'Pilot', applicationVersion: '0.0.1' });
  installMenu();
  shell = new BrowserWindow({
    width: smoke ? 800 : 1280,
    height: smoke ? 600 : 840,
    title: 'Pilot',
    backgroundColor: '#111111',
    icon,
    webPreferences: {
      preload: join(here, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  installWorkbenchKeys(shell.webContents);
  shell.webContents.on('did-start-navigation', (_event, _url, _inPlace, mainFrame) => { if (mainFrame) workbenchReady = false; });
  installMicrophonePermissions(shell.webContents, shellUrl(), systemPreferences);
  shell.webContents.on('did-fail-load', (_event, code, _desc, _url, isMainFrame) => {
    if (!isMainFrame || code === -3 || smoke || _url?.startsWith('data:')) return;
    shell.webContents.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(offlinePage(shellUrl()))}`).catch(() => {});
  });
  if (!smoke) shell.loadURL(shellUrl()).catch(() => {});
  else shell.loadURL('about:blank');
  writeEndpoint();
  shell.on('closed', () => {
    shell = null;
  });
}).catch((error) => {
  process.stderr.write(String(error && error.stack || error) + '\n');
  app.quit();
});

app.on('before-quit', clearEndpoint);
app.on('window-all-closed', () => {
  clearEndpoint();
  app.quit();
});
