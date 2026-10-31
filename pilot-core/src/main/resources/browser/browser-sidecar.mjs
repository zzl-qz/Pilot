// Pilot 浏览器 sidecar：由 Java 通过 stdin/stdout 的 JSON Lines 驱动一个 Playwright Chromium。
//
// 协议（一行一个 JSON）：
//   请求  {"id": 1, "session": "s1", "op": "navigate", ...参数}
//   响应  {"id": 1, "ok": true, "text": "...", "screenshot": "/abs/a.png"} 或 {"id": 1, "ok": false, "error": "..."}
// stdout 只放协议行；任何日志一律走 stderr。
//
// 隔离：整个进程共享一个 Chromium，每个 Pilot 会话一个独立 BrowserContext（cookie / storage 互不可见）。
// 导航只允许回环地址（localhost / 127.0.0.1 / [::1]）：本工具用来验证本机 Web 应用，不是通用上网。

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
  try {
    list.push(loadPlaywright().chromium.executablePath());
  } catch {
    // 没装 Playwright 自带 Chromium 就继续找系统浏览器
  }
  if (process.platform === 'darwin') {
    list.push('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
      '/Applications/Chromium.app/Contents/MacOS/Chromium',
      '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser');
  } else if (process.platform === 'win32') {
    const roots = [process.env['PROGRAMFILES'], process.env['PROGRAMFILES(X86)'], process.env['LOCALAPPDATA']]
      .filter(Boolean);
    for (const root of roots) {
      list.push(`${root}\\Google\\Chrome\\Application\\chrome.exe`,
        `${root}\\Microsoft\\Edge\\Application\\msedge.exe`,
        `${root}\\BraveSoftware\\Brave-Browser\\Application\\brave.exe`);
    }
  } else {
    list.push('/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium',
      '/usr/bin/chromium-browser', '/snap/bin/chromium', '/usr/bin/microsoft-edge');
  }
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

async function launchBrowser() {
  const { chromium } = loadPlaywright();
  const executables = candidateExecutables();
  if (executables.length === 0) {
    throw new Error('没有找到可用的 Chrome / Edge / Chromium。请安装其中之一，或设置环境变量 PILOT_BROWSER_PATH 指向浏览器可执行文件。');
  }
  const failures = [];
  for (const executablePath of executables) {
    try {
      const browser = await chromium.launch({ executablePath, headless: true, args: launchArgs(), timeout: 20000 });
      browser.__executable = executablePath;
      browser.on('disconnected', () => {
        browserPromise = null;
        for (const session of sessions.values()) session.dead = true;
        sessions.clear();
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
  const session = { id, context, page: null, console: [], network: [], dead: false };
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
  attachPage(session, await context.newPage());
  sessions.set(id, session);
  return session;
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
    return { text: 'pong', version };
  },

  async probe() {
    loadPlaywright();
    const browser = await getBrowser();
    const version = browser.version();
    const executable = browser.__executable;
    // 探测只验证能启动，随后关掉，不占用常驻内存。
    await browser.close();
    browserPromise = null;
    return { text: `浏览器可用：${executable}（Chromium ${version}）`, executable, version };
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
    return { text: `已打开，HTTP ${status || '未知'}\n${await describePage(current)}` };
  },

  async reload(args, current) {
    const page = pageOf(current);
    await page.reload({ waitUntil: 'load', timeout: NAVIGATION_TIMEOUT_MS });
    return { text: `已刷新\n${await describePage(current)}` };
  },

  async back(args, current) {
    const page = pageOf(current);
    const response = await page.goBack({ waitUntil: 'load', timeout: NAVIGATION_TIMEOUT_MS });
    return { text: (response ? '已后退' : '没有可后退的历史') + `\n${await describePage(current)}` };
  },

  async snapshot(args, current) {
    return { text: await snapshot(current) };
  },

  async click(args, current) {
    const locator = locatorFor(current, args);
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
