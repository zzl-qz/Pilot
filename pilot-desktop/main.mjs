import { saveSessionMarkdown } from './session-export.mjs';
import { selectProjectDirectories } from './directory-picker.mjs';
import { installMicrophonePermissions } from './media-permission.mjs';
import { configuredWorkbenchUrl, workbenchOrigin, workbenchIpc, installWorkbenchNavigation } from './workbench-security.mjs';
import { app, BrowserWindow, WebContentsView, ipcMain, session, Menu, Tray, globalShortcut, powerSaveBlocker, nativeImage, systemPreferences, dialog, clipboard, Notification, screen } from 'electron';
import { createDesktopAlertManager } from './desktop-alerts.mjs';
import { buildTrayMenuTemplate, createOfflineReconnector, createPowerAwakeController, normalizeWindowBounds, shouldHideOnClose } from './desktop-shell.mjs';
import { startBrowserControl } from './browser-control.mjs';
import { randomBytes, randomInt } from 'node:crypto';
import net from 'node:net';
import { mkdirSync, writeFileSync, readFileSync, rmSync, existsSync, lstatSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { hostname, homedir } from 'node:os';
import {
  formatPageLogs,
  isRegularFileStat,
  isWindowsUncPath,
  isDangerousDownloadExtension,
  markerPath,
  partitionName,
  parseClipboardFilePaths,
  sanitizeDownloadFilename,
  snapBounds,
  userPageUrl
} from './protocol.mjs';
import { offlinePage } from './offline-page.mjs';
import { windowSizeMenuItem, workbenchMenuItems, workbenchShortcut } from './platform-menu.mjs';


const here = dirname(fileURLToPath(import.meta.url));
const smoke = process.env.PILOT_DESKTOP_SMOKE === '1';
const workbenchUrl = configuredWorkbenchUrl();
const origin = workbenchOrigin(workbenchUrl);
app.setName('Pilot');
const singleInstanceLocked = smoke || process.env.PILOT_DISABLE_SINGLE_INSTANCE === '1' || app.requestSingleInstanceLock();
if (!singleInstanceLocked) {
  app.exit(0);
  process.exit(0);
}
if (!smoke && process.env.PILOT_DISABLE_SINGLE_INSTANCE !== '1') app.on('second-instance', () => focusMainShell());
if (process.env.PILOT_DISABLE_SINGLE_INSTANCE === '1') process.stderr.write('PILOT_DISABLE_SINGLE_INSTANCE is enabled; single-instance protection is disabled.\n');
if (process.platform === 'win32') app.setAppUserModelId('com.pilot.desktop');
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

function defaultEndpointFile() {
  if (process.env.PILOT_DESKTOP_ENDPOINT) return process.env.PILOT_DESKTOP_ENDPOINT;
  if (process.env.PILOT_BROWSER_HOME) return join(process.env.PILOT_BROWSER_HOME, 'desktop-browser.json');
  const base = process.platform === 'win32' && process.env.LOCALAPPDATA
    ? join(process.env.LOCALAPPDATA, 'Pilot') : join(homedir(), '.pilot');
  return join(base, 'run', 'desktop-browser.json');
}

let cdpPort = Number(process.env.PILOT_DESKTOP_CDP_PORT) || (42000 + randomInt(20000));
try {
  cdpPort = await availableCdpPort(cdpPort);
} catch {
  /* 端口探测失败时回退原端口 */
}
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
const hookedSessions = new WeakSet();

const workbench = workbenchIpc(ipcMain, () => shell, workbenchUrl);

function activeTab() {
  return tabs.get(activeId) || null;
}

function hideInactiveViews() {
  for (const tab of tabs.values()) {
    if (tab.detached || tab.view.webContents.isDestroyed()) continue;
    tab.view.setVisible(tab.id === activeId);
  }
}

function availableCdpPort(requested) {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', error => {
      if (error.code !== 'EADDRINUSE') return reject(error);
      server.removeAllListeners('error');
      server.once('error', reject);
      server.listen(0, '127.0.0.1');
    });
    server.listen(requested, '127.0.0.1', () => {
      const port = server.address().port;
      server.close(error => error ? reject(error) : resolve(port));
    });
  });
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
      targetId: tab.view.webContents.getOrCreateDevToolsTargetId(),
      title: tabTitle(tab),
      url: tab.view.webContents.getURL(),
      loading: tab.view.webContents.isLoading(),
      favicon: tab.favicon || '',
      sessionId: tab.sessionId || null,
      ownerSessionId: tab.ownerSessionId || null,
      agentId: tab.agentId || '',
      label: tab.label || '',
      detached: Boolean(tab.detached),
      emulated: tab.emulated || null,
      agentControlled: Boolean(tab.agentControlled),
      controlStatus: tab.controlStatus || ''
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
    workbench.send('pilot-browser-state', state());
  }
}

function place(tab) {
  if (!tab || tab.detached || tab.view.webContents.isDestroyed()) return;
  hideInactiveViews();
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
  if (hookedSessions.has(ses)) return;
  hookedSessions.add(ses);
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
      workbench.send('pilot-browser-log', { network: true, line: formatPageLogs([entry], 'network') });
    }
  };
  ses.webRequest.onCompleted(note);
  ses.webRequest.onErrorOccurred(note);
  ses.on('will-download', (_event, item) => {
    const dir = join(browserHome(), 'downloads');
    mkdirSync(dir, { recursive: true });
    const name = sanitizeDownloadFilename(item.getFilename());
    if (isDangerousDownloadExtension(name)) {
      item.cancel();
      if (shell && !shell.isDestroyed()) {
        workbench.send('pilot-browser-download', {
          state: 'blocked',
          name,
          error: `已阻止高风险可执行或脚本文件下载（${name}）`
        });
      }
      return;
    }
    const savePath = join(dir, `${Date.now()}-${name}`);
    item.setSavePath(savePath);
    item.once('done', (_done, downloadState) => {
      if (shell && !shell.isDestroyed()) {
        workbench.send('pilot-browser-download', { state: downloadState, name, path: savePath });
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
  contents.on('render-process-gone', () => {
    tab.agentControlled = false;
    tab.controlStatus = '';
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
        click: () => workbench.send('pilot-browser-quote', String(params.selectionText || '').slice(0, 4000))
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
      workbench.send('pilot-browser-log', { network: false, line: formatPageLogs([entry], 'console') });
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
      workbench.send('pilot-browser-focus-address');
    } else if (key === 't') {
      event.preventDefault();
      createTab({ url: null, sessionId: null });
    } else if (key === 'w') {
      event.preventDefault();
      closeTab(tab.id);
    }
  });
}

function createTab({ url, sessionId, ownerSessionId = null, agentId = '', label = '', background = false }) {
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
  // 隐藏标签也保留有效布局；0×0 会使首次截图与 DOM 可见性检查失败。
  view.setBounds({ x: 0, y: 0, width: 1280, height: 800 });
  const tab = {
    id, view, sessionId: sessionId || null,
    ownerSessionId: ownerSessionId || sessionId || null,
    agentId: agentId ? String(agentId) : '',
    label: label ? String(label) : '',
    bounds: null, detached: false, window: null,
    attached: false, cover: null, emulated: null, covering: false,
    agentControlled: false, controlStatus: '',
    console: [], network: [], loadError: '', favicon: ''
  };
  tabs.set(id, tab);
  if (sessionId) sessionTabs.set(sessionId, id);
  hookPageSession(session.fromPartition(partitionName(workspace)));
  shell.contentView.addChildView(view);
  tab.attached = true;
  wire(tab);
  const keepCurrent = Boolean(background && activeId && tabs.has(activeId));
  if (!keepCurrent) {
    activeId = id;
  }
  view.webContents.loadURL(`http://127.0.0.1:${controlPort}${markerPath(id)}`);
  if (url) {
    view.webContents.once('did-finish-load', () => {
      const next = userPageUrl(url);
      if (next) view.webContents.loadURL(next);
    });
  }
  if (!keepCurrent) {
    place(tab);
  }
  broadcast();
  return tab;
}

function ensureTab(sessionId, options = {}) {
  if (!options.fresh) {
    const existingId = sessionId ? sessionTabs.get(sessionId) : null;
    const existing = existingId ? tabs.get(existingId) : null;
    if (existing) {
      if (options.agentId && !existing.agentId) existing.agentId = String(options.agentId);
      if (options.label && !existing.label) existing.label = String(options.label);
      if (!options.background) {
        activeId = existing.id;
        place(existing);
      }
      broadcast();
      return existing;
    }
  }
  return createTab({
    url: options.url || null,
    sessionId: sessionId || null,
    ownerSessionId: options.ownerSessionId || sessionId || null,
    agentId: options.agentId || '',
    label: options.label || '',
    background: Boolean(options.background)
  });
}

function emulate(tab, width, height) {
  const contents = tab?.view.webContents;
  if (!contents || contents.isDestroyed()) return;
  if (!width || !height) {
    contents.disableDeviceEmulation?.();
    tab.emulated = null;
  } else {
    contents.enableDeviceEmulation?.({
      screenPosition: 'desktop',
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
    if (tabs.get(tab.id) === tab && shell && !shell.isDestroyed() && !tab.view.webContents.isDestroyed()) {
      shell.contentView.addChildView(tab.view);
      tab.attached = true;
      place(tab);
    }
    broadcast();
  });
  broadcast();
}

let controlPort = 0;

async function startControl() {
  controlPort = await startBrowserControl(token, {
    state,
    open(body) {
      const tab = ensureTab(body.sessionId || null, {
        fresh: Boolean(body.fresh),
        url: body.url || null,
        ownerSessionId: body.ownerSessionId || body.sessionId || null,
        agentId: body.agentId || '',
        label: body.label || '',
        background: Boolean(body.background)
      });
      return { tabId: tab.id, targetId: tab.view.webContents.getOrCreateDevToolsTargetId(), tabs: state().tabs };
    },
    select(body) {
      selectTab(body.tabId);
      const tab = tabs.get(body.tabId);
      if (tab?.sessionId) sessionTabs.set(tab.sessionId, tab.id);
      return state();
    },
    close(body) {
      closeTab(body.tabId);
      return state();
    },
    emulate(body) {
      const selected = body.tabId ? [tabs.get(body.tabId)]
        : body.sessionId ? [...tabs.values()].filter(tab => tab.sessionId === body.sessionId) : [activeTab()];
      const targets = selected.filter(Boolean);
      if (!targets.length) return null;
      for (const tab of targets) emulate(tab, Number(body.width) || 0, Number(body.height) || 0);
      return { ok: true, emulated: targets[0].emulated || null };
    },
    control(body) {
      const matches = body.tabId ? [tabs.get(body.tabId)]
        : body.sessionId ? [...tabs.values()].filter(tab => tab.sessionId === body.sessionId) : [activeTab()];
      const targets = matches.filter(Boolean);
      if (!targets.length) return null;
      const active = Boolean(body.agentControlled);
      const status = active ? String(body.controlStatus || '') : '';
      for (const tab of targets) {
        tab.agentControlled = active;
        tab.controlStatus = status;
      }
      broadcast();
      return { ok: true, agentControlled: active, controlStatus: status };
    }
  });
}

function writeEndpoint() {
  endpointFile = defaultEndpointFile();
  mkdirSync(dirname(endpointFile), { recursive: true, mode: 0o700 });
  writeFileSync(endpointFile, JSON.stringify({
    pid: process.pid,
    // 进程启动时刻：后端据此识别「PID 被系统复用」的残留端点，避免误判窗口已开。
    startMillis: Math.round(Date.now() - process.uptime() * 1000),
    cdpPort,
    controlPort,
    token,
    host: hostname()
  }), { mode: 0o600 });
}

function clearEndpoint() {
  if (!endpointFile) return;
  try {
    if (JSON.parse(readFileSync(endpointFile, 'utf8')).pid === process.pid) rmSync(endpointFile, { force: true });
  } catch (error) {
    if (error.code !== 'ENOENT') process.stderr.write(`endpoint cleanup skipped: ${error.message}\n`);
  }
}

const tabActions = {
  ensure: sessionId => ensureTab(sessionId || null),
  new: () => createTab({ url: null, sessionId: null }),
  close: closeTab,
  select: selectTab,
  detach: detachActive,
  'reset-emulation': () => emulate(activeTab(), 0, 0)
};
for (const [name, action] of Object.entries(tabActions)) {
  workbench.handle('pilot-browser-' + name, (_event, input) => {
    action(input);
    return state();
  });
}
workbench.handle('pilot-browser-navigate', (_event, url) => {
  const tab = activeTab() || createTab({ url: null, sessionId: null });
  const next = userPageUrl(url);
  if (!next) return { ok: false, error: '地址无效' };
  tab.view.webContents.loadURL(next);
  return { ok: true, url: next };
});
workbench.handle('pilot-browser-go', (_event, action) => {
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
workbench.handle('pilot-browser-menu', (event) => new Promise((resolve) => {
  let settled = false;
  const finish = (action) => {
    if (settled) return;
    settled = true;
    resolve(action);
  };
  const choose = (action, effect) => { if (!workbench.trusted(event)) return finish(''); effect?.(); finish(action); };
  const menu = Menu.buildFromTemplate([
    { label: '页内查找', click: () => choose('find') },
    { label: '放大', click: () => choose('zoom', () => zoomBy(0.1)) },
    { label: '缩小', click: () => choose('zoom', () => zoomBy(-0.1)) },
    { label: '实际大小', click: () => choose('zoom', () => zoomBy(1 - (activeTab()?.view.webContents.getZoomFactor() || 1))) },
    { type: 'separator' },
    { label: '选择页面元素', click: () => choose('pick') },
    { label: '在独立窗口打开', click: () => choose('detach', detachActive) },
    { label: '开发工具', click: () => choose('devtools', () => activeTab()?.view.webContents.openDevTools({ mode: 'detach' })) },
    { type: 'separator' },
    { label: '页面控制台', click: () => choose('console') },
    { label: '网络记录', click: () => choose('network') }
  ]);
  menu.popup({ callback: () => finish('') });
}));
workbench.handle('pilot-browser-find', (_event, text) => {
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
workbench.handle('pilot-browser-logs', (_event, network) => {
  const tab = activeTab();
  if (!tab) return { ok: false, text: network ? '没有网络记录' : '没有控制台记录' };
  return { ok: true, text: formatPageLogs(network ? tab.network : tab.console, network ? 'network' : 'console') };
});
workbench.handle('pilot-browser-devtools', () => {
  activeTab()?.view.webContents.openDevTools({ mode: 'detach' });
  return { ok: true };
});
workbench.handle('pilot-browser-pick', () => {
  const tab = activeTab();
  if (!tab) return { ok: false, error: '没有打开的页面' };
  return pickElement(tab).then((text) => ({ ok: true, text })).catch((error) => ({ ok: false, error: String(error.message || error) }));
});
workbench.on('pilot-browser-bounds', (_event, bounds) => {
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
workbench.on('pilot-browser-workspace', (_event, value) => {
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
  if (workbenchReady && workbench.current()) workbench.send('pilot-workbench-command', command);
  else if (!workbench.current()) shell.loadURL(workbenchUrl).catch(() => {});
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
  if (!workbench.send('pilot-mobile-open')) throw new Error('请先连接 Pilot 工作台，再打开手机连接');
}

workbench.on('pilot-workbench-ready', () => { workbenchReady = true; });
workbench.handle('pilot-workbench-reload', event => {
  shell.webContents.reloadIgnoringCache();
  return { ok: true };
});

workbench.handle('pilot-mobile-open', async event => {
  await openMobileConnection();
  return { ok: true };
});

workbench.handle('pilot-mobile-copy', async (event, code) => {
  if (typeof code !== 'string' || !/^pilot:\/\/pair\/[A-Za-z0-9_-]{20,8192}$/.test(code)) {
    return { ok: false };
  }
  try {
    // 只写入用户选择的配对码，不向页面开放系统剪贴板读取能力。
    if (typeof clipboard.writeText === 'function') clipboard.writeText(code);
    else await clipboard.write([{ 'text/plain': new Blob([code], { type: 'text/plain' }) }]);
    return { ok: true };
  } catch { return { ok: false }; }
});

// workbench.handle 在打开弹框前和返回后都检查主窗口、主框架与 origin。

// 系统文件管理器复制文件后，浏览器剪贴板事件通常只有路径；按用户主动 Ctrl+V 读取一次。
const CLIPBOARD_IMAGE_MIME = new Map([
  ['.png', 'image/png'], ['.jpg', 'image/jpeg'], ['.jpeg', 'image/jpeg'],
  ['.webp', 'image/webp'], ['.gif', 'image/gif']
]);

function clipboardLimits(raw) {
  const value = raw && typeof raw === 'object' ? raw : {};
  const limit = (item, fallback, max) => {
    const number = Number(item);
    return Number.isSafeInteger(number) && number > 0 ? Math.min(number, max) : fallback;
  };
  return {
    maxFiles: limit(value.maxFiles, 64, 64),
    maxFileBytes: limit(value.maxFileBytes, 2 * 1024 * 1024, 64 * 1024 * 1024),
    maxTotalBytes: limit(value.maxTotalBytes, 25 * 1024 * 1024, 128 * 1024 * 1024)
  };
}

function clipboardPath(value) {
  let text = String(value || '').replace(/^\uFEFF/, '').trim();
  if (!text || text === 'copy' || text.startsWith('#')) return '';
  if ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith("'") && text.endsWith("'"))) {
    text = text.slice(1, -1);
  }
  if (/^file:/i.test(text)) {
    try { return fileURLToPath(new URL(text)); } catch { return ''; }
  }
  if (process.platform === 'win32') {
    if (isWindowsUncPath(text)) return '';
    return /^[A-Za-z]:[\\/]/.test(text) ? text : '';
  }
  return text.startsWith('/') ? text : '';
}

function clipboardFilePaths(limit) {
  // Windows 不区分大小写：同一个文件可能以不同大小写出现两次，去重但不能改写真实大小写
  const found = new Map();
  const key = path => (process.platform === 'win32' ? path.toLowerCase() : path);
  const addPaths = values => {
    for (const line of values) {
      if (found.size >= limit) return;
      const path = clipboardPath(line);
      if (path && !found.has(key(path))) found.set(key(path), path);
    }
  };
  let formats = [];
  try { formats = clipboard.availableFormats() || []; } catch { /* 无系统剪贴板 */ }
  for (const format of formats) {
    try {
      const data = /FileNameW|CF_HDROP/i.test(format) ? clipboard.readBuffer(format) : clipboard.read(format);
      addPaths(parseClipboardFilePaths(format, data, process.platform));
    } catch { /* 该格式不可读，继续看其他格式 */ }
  }
  if (!found.size) {
    try { addPaths(clipboard.readText().split(/\r?\n/)); } catch { /* 忽略 */ }
  }
  return [...found.values()];
}

async function readClipboardFiles(rawLimits) {
  const limits = clipboardLimits(rawLimits);
  const human = bytes => (bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.round(bytes / 1024)} KB`);
  const paths = clipboardFilePaths(limits.maxFiles);
  if (!paths.length) {
    return { ok: false, error: '系统剪贴板中没有文件。复制文件后再按 Ctrl+V，或直接拖入文件。' };
  }
  if (paths.length > limits.maxFiles) {
    return { ok: false, error: `一次最多附加 ${limits.maxFiles} 个文件。` };
  }
  const files = [];
  let total = 0;
  for (const path of paths) {
    const name = basename(path) || '附件';
    let info;
    try { info = lstatSync(path); } catch { return { ok: false, error: `无法读取「${name}」，文件可能已移动或没有权限。` }; }
    if (!isRegularFileStat(info)) {
      return { ok: false, error: `「${name}」不是普通文件；文件夹请压缩后再拖入，或拖入其中的文件。` };
    }
    if (info.size <= 0) {
      return { ok: false, error: `「${name}」是空文件，无法添加。` };
    }
    if (info.size > limits.maxFileBytes) {
      return { ok: false, error: `「${name}」超过单文件上限（${human(limits.maxFileBytes)}）。` };
    }
    total += info.size;
    if (total > limits.maxTotalBytes) {
      return { ok: false, error: `附件总量超过单条消息上限（${human(limits.maxTotalBytes)}）。` };
    }
    let bytes;
    try { bytes = await readFile(path); } catch { return { ok: false, error: `无法读取「${name}」的内容。` }; }
    const mimeType = CLIPBOARD_IMAGE_MIME.get(name.slice(name.lastIndexOf('.')).toLowerCase()) || 'application/octet-stream';
    files.push({
      name, mimeType, size: bytes.length,
      dataBase64: `data:${mimeType};base64,${bytes.toString('base64')}`
    });
  }
  return { ok: true, files };
}

workbench.handle('pilot:save-session-markdown', (event, name, text) => saveSessionMarkdown(name, text, async options => {
  const result = await dialog.showSaveDialog(shell, options);
  if (!workbench.trusted(event)) throw new Error('工作台已关闭或切换，请重新导出。');
  return result;
}));
workbench.handle('pilot:select-directories', () => selectProjectDirectories({ origin, parent: shell, dialog }));
workbench.handle('pilot:read-clipboard-files', (_event, limits) => readClipboardFiles(limits));

let alerts = null;
let tray = null;
let quitting = false;
let activeShortcut = '';
let boundsSaveTimer = null;
const runtimeStatus = {
  runningCount: 0,
  pendingCount: 0,
  dndActive: false,
  keepAwake: false,
  minimizeToTray: false
};
const powerAwake = createPowerAwakeController({ powerSaveBlocker });

function windowBoundsFile() {
  return join(browserHome(), 'window-bounds.json');
}

function loadSavedBounds() {
  if (smoke) return null;
  try {
    return JSON.parse(readFileSync(windowBoundsFile(), 'utf8'));
  } catch {
    return null;
  }
}

function scheduleSaveBounds() {
  if (smoke || !shell || shell.isDestroyed()) return;
  clearTimeout(boundsSaveTimer);
  boundsSaveTimer = setTimeout(() => {
    boundsSaveTimer = null;
    if (!shell || shell.isDestroyed()) return;
    try {
      const maximized = shell.isMaximized();
      const raw = maximized && typeof shell.getNormalBounds === 'function' ? shell.getNormalBounds() : shell.getBounds();
      mkdirSync(browserHome(), { recursive: true });
      writeFileSync(windowBoundsFile(), JSON.stringify({ ...raw, maximized }));
    } catch { /* 写窗口边界失败不影响主功能 */ }
  }, 400);
}

function focusMainShell() {
  if (!shell || shell.isDestroyed()) return;
  if (shell.isMinimized()) shell.restore();
  shell.show();
  shell.focus();
}

function refreshTrayMenu() {
  if (!tray || tray.isDestroyed?.()) return;
  const parts = [];
  if (runtimeStatus.pendingCount > 0) parts.push(`${runtimeStatus.pendingCount} 项待处理`);
  if (runtimeStatus.runningCount > 0) parts.push(`${runtimeStatus.runningCount} 项执行中`);
  tray.setToolTip(parts.length ? `Pilot · ${parts.join(' / ')}` : 'Pilot 工作台');
  const template = buildTrayMenuTemplate({
    runningCount: runtimeStatus.runningCount,
    pendingCount: runtimeStatus.pendingCount,
    dndActive: runtimeStatus.dndActive,
    keepAwake: runtimeStatus.keepAwake,
    windowVisible: Boolean(shell && !shell.isDestroyed() && shell.isVisible()),
    handlers: {
      toggleWindow: () => {
        if (!shell || shell.isDestroyed()) return;
        if (shell.isVisible() && !shell.isMinimized()) shell.hide();
        else focusMainShell();
        refreshTrayMenu();
      },
      focusPending: () => {
        focusMainShell();
        dispatchWorkbenchCommand('focus-pending');
      },
      newTask: () => {
        focusMainShell();
        dispatchWorkbenchCommand('new-session');
      },
      toggleDnd: () => {
        workbench.send('pilot-desktop-pref-action', 'toggle-dnd');
      },
      toggleKeepAwake: () => {
        workbench.send('pilot-desktop-pref-action', 'toggle-keep-awake');
      },
      quit: () => {
        quitting = true;
        app.quit();
      }
    }
  });
  tray.setContextMenu(Menu.buildFromTemplate(template));
}

workbench.handle('pilot:alert-show', (_event, payload) => alerts ? alerts.show(payload) : { ok: false });
workbench.handle('pilot:alert-dismiss', (_event, id) => alerts ? alerts.dismiss(id) : { ok: false });
workbench.handle('pilot:notify-show', (_event, payload) => alerts ? alerts.showNativeNotification(payload) : { ok: false });
workbench.handle('pilot:window-attention', (_event, payload) => alerts ? alerts.setWindowAttention(payload) : { ok: false });
workbench.handle('pilot:window-focus', () => alerts ? alerts.focusMainWindow() : { ok: false });
workbench.handle('pilot:desktop-runtime-sync', (_event, payload) => {
  if (payload && typeof payload === 'object') {
    runtimeStatus.runningCount = Math.max(0, Number(payload.runningCount) || 0);
    runtimeStatus.pendingCount = Math.max(0, Number(payload.pendingCount) || 0);
    runtimeStatus.dndActive = Boolean(payload.dndActive);
    runtimeStatus.keepAwake = Boolean(payload.keepAwake);
    runtimeStatus.minimizeToTray = Boolean(payload.minimizeToTray);
    powerAwake.update({ keepAwake: runtimeStatus.keepAwake, runningCount: runtimeStatus.runningCount });
    refreshTrayMenu();
  }
  return { ok: true };
});
workbench.handle('pilot:desktop-shortcut-set', (_event, accelerator) => {
  const next = String(accelerator ?? '').trim().slice(0, 64);
  if (activeShortcut) {
    try { globalShortcut.unregister(activeShortcut); } catch { /* 忽略注销异常 */ }
    activeShortcut = '';
  }
  if (!next) {
    return { ok: true, accelerator: '' };
  }
  try {
    const ok = globalShortcut.register(next, () => {
      focusMainShell();
      dispatchWorkbenchCommand('focus-composer');
    });
    if (!ok) {
      return { ok: false, accelerator: '', error: `快捷键「${next}」已被系统或其他应用占用，请更换组合键。` };
    }
    activeShortcut = next;
    return { ok: true, accelerator: activeShortcut };
  } catch (error) {
    return { ok: false, accelerator: '', error: `快捷键格式不受支持或被桌面环境拦截：${error?.message || error}` };
  }
});

ipcMain.on('pilot-alert-act', (event, payload) => {
  const contents = alerts?.webContents();
  if (!contents || event.sender !== contents) return;
  alerts.handleAlertAct(payload);
});
ipcMain.on('pilot-alert-resize', (event, height) => {
  const contents = alerts?.webContents();
  if (!contents || event.sender !== contents) return;
  alerts.handleAlertResize(height);
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
  const initialBounds = smoke
    ? { width: 800, height: 600, maximized: false }
    : normalizeWindowBounds(loadSavedBounds(), screen.getAllDisplays?.() || []);
  shell = new BrowserWindow({
    ...(Number.isFinite(initialBounds.x) && Number.isFinite(initialBounds.y) ? { x: initialBounds.x, y: initialBounds.y } : {}),
    width: initialBounds.width,
    height: initialBounds.height,
    title: 'Pilot',
    backgroundColor: '#111111',
    icon,
    webPreferences: {
      preload: join(here, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      additionalArguments: [`--pilot-workbench-origin=${origin}`]
    }
  });
  if (initialBounds.maximized && !smoke) {
    shell.maximize();
  }
  const offlineReconnector = createOfflineReconnector({
    healthUrl: `${origin}/api/health`,
    onReady: () => {
      if (shell && !shell.isDestroyed() && !workbench.current()) {
        shell.loadURL(workbenchUrl).catch(() => {});
      }
    }
  });
  alerts = createDesktopAlertManager({
    BrowserWindow,
    Notification,
    screen,
    app,
    getShell: () => shell,
    onAction: (action) => workbench.send('pilot-alert-action', action),
    platform: process.platform,
    preloadPath: join(here, 'alert-preload.cjs'),
    icon,
    smoke
  });
  if (!smoke && icon && typeof Tray === 'function') {
    try {
      const trayIcon = process.platform === 'darwin' ? icon.resize({ width: 18, height: 18 }) : icon;
      tray = new Tray(trayIcon);
      tray.on('click', () => {
        focusMainShell();
        refreshTrayMenu();
      });
      refreshTrayMenu();
    } catch {
      tray = null;
      if (runtimeStatus.minimizeToTray) runtimeStatus.minimizeToTray = false;
    }
  }
  shell.on('resize', scheduleSaveBounds);
  shell.on('move', scheduleSaveBounds);
  shell.on('maximize', scheduleSaveBounds);
  shell.on('unmaximize', scheduleSaveBounds);
  shell.on('show', refreshTrayMenu);
  shell.on('hide', refreshTrayMenu);
  shell.on('close', (event) => {
    if (quitting || smoke) return;
    if (shouldHideOnClose({ minimizeToTray: runtimeStatus.minimizeToTray, trayAvailable: Boolean(tray) })) {
      event.preventDefault();
      shell.hide();
      refreshTrayMenu();
      return;
    }
    if (runtimeStatus.runningCount > 0 || runtimeStatus.pendingCount > 0) {
      event.preventDefault();
      const total = runtimeStatus.runningCount + runtimeStatus.pendingCount;
      void dialog.showMessageBox(shell, {
        type: 'question',
        title: '关闭 Pilot 主窗口',
        message: `当前仍有 ${total} 项运行中或待处理的任务。`,
        detail: tray
          ? '隐藏到后台会继续执行并在完成或需要批准时提醒你；直接退出将中断本地桌面会话视图。'
          : '直接退出将中断本地桌面会话视图。当前系统没有可用托盘，无法隐藏到后台。',
        buttons: tray ? ['隐藏到后台继续', '仍要退出', '取消'] : ['仍要退出', '取消'],
        defaultId: 0,
        cancelId: tray ? 2 : 1
      }).then(({ response }) => {
        if (!shell || shell.isDestroyed()) return;
        if (response === 0 && tray) {
          shell.hide();
          refreshTrayMenu();
        } else if ((tray && response === 1) || (!tray && response === 0)) {
          quitting = true;
          shell.close();
        }
      }).catch(() => {});
    }
  });
  shell.on('focus', () => {
    try { shell.flashFrame(false); } catch { /* 平台不支持任务栏闪烁时忽略 */ }
    workbench.send('pilot-window-focus', { focused: true });
  });
  shell.on('blur', () => {
    workbench.send('pilot-window-focus', { focused: false });
  });
  installWorkbenchNavigation(shell.webContents, workbenchUrl, {
    // 等取消导航生效后再检查当前文档，避免将候选 URL 当成已提交页面。
    openPage: url => setImmediate(() => workbench.send('pilot-browser-open', url)),
    notify: message => setImmediate(() => workbench.send('pilot-navigation-notice', message))
  });
  installWorkbenchKeys(shell.webContents);
  shell.webContents.on('did-navigate', () => { workbenchReady = false; });
  shell.webContents.on('did-finish-load', () => {
    if (workbench.current()) offlineReconnector.stop();
  });
  installMicrophonePermissions(shell.webContents, workbenchUrl, systemPreferences);
  shell.webContents.on('did-fail-load', (_event, code, _desc, _url, isMainFrame) => {
    if (!isMainFrame || code === -3 || smoke || _url?.startsWith('data:')) return;
    shell.webContents.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(offlinePage(workbenchUrl))}`).catch(() => {});
    offlineReconnector.start();
  });
  shell.webContents.on('render-process-gone', (_event, details) => {
    if (smoke || shell?.isDestroyed()) return;
    process.stderr.write(`主窗口渲染进程异常退出（${details?.reason || 'unknown'}），正在恢复。\n`);
    const contents = shell.webContents;
    setTimeout(() => {
      if (!shell || shell.isDestroyed()) return;
      try {
        if (contents.isCrashed()) contents.reload();
      } catch {
        contents.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent('<meta charset="utf-8"><h1>Pilot 已恢复</h1><p>渲染进程意外退出，请重新加载工作台。</p><button onclick="location.reload()">重新加载</button>')}`).catch(() => {});
      }
    }, 250);
  });
  if (!smoke) shell.loadURL(workbenchUrl).catch(() => {});
  else shell.loadURL('about:blank');
  writeEndpoint();
  shell.on('closed', () => {
    for (const tab of tabs.values()) {
      if (tab.detached && tab.window && !tab.window.isDestroyed()) tab.window.close();
    }
    clearTimeout(boundsSaveTimer);
    offlineReconnector.stop();
    alerts?.destroy();
    alerts = null;
    shell = null;
  });
}).catch((error) => {
  process.stderr.write(String(error && error.stack || error) + '\n');
  app.quit();
});

app.on('activate', () => {
  focusMainShell();
});

app.on('before-quit', () => {
  quitting = true;
  clearTimeout(boundsSaveTimer);
  powerAwake.destroy();
  try { globalShortcut.unregisterAll(); } catch { /* 忽略 */ }
  tray?.destroy?.();
  tray = null;
  alerts?.destroy();
  clearEndpoint();
});
app.on('window-all-closed', () => {
  alerts?.destroy();
  clearEndpoint();
  app.quit();
});
