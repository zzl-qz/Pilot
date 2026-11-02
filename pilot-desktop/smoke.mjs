import { spawn, execFileSync } from 'node:child_process';
import { prepareDevelopmentHost } from './brand-host.mjs';
import { createServer } from 'node:http';
import net from 'node:net';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { join as joinPath } from 'node:path';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { setTimeout as delay } from 'node:timers/promises';

const root = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(root, '../pilot-web/frontend/package.json'));
const { chromium } = require('playwright-core');
const home = mkdtempSync(join(tmpdir(), 'pilot-desktop-'));
const electron = prepareDevelopmentHost();
const cdpPort = await new Promise((resolve, reject) => {
  const server = net.createServer();
  server.once('error', reject);
  server.listen(0, '127.0.0.1', () => {
    const { port } = server.address();
    server.close(() => resolve(port));
  });
});

const env = { ...process.env, PILOT_DESKTOP_SMOKE: '1', PILOT_BROWSER_HOME: home, PILOT_DESKTOP_CDP_PORT: String(cdpPort) };
delete env.ELECTRON_RUN_AS_NODE;
const child = spawn(electron, [root], {
  env,
  stdio: ['ignore', 'pipe', 'pipe']
});
let logs = '';
child.stdout.on('data', (chunk) => { logs += chunk; });
child.stderr.on('data', (chunk) => { logs += chunk; });

try {
  const endpoint = join(home, 'desktop-browser.json');
  let info = null;
  for (let i = 0; i < 80; i++) {
    try {
      info = JSON.parse(readFileSync(endpoint, 'utf8'));
      break;
    } catch {
      await delay(250);
    }
  }
  if (!info) {
    let listing = '';
    try {
      listing = readdirSync(home).map((name) => `${name}=${readFileSync(joinPath(home, name), 'utf8')}`).join(' | ');
    } catch (error) { listing = error.message; }
    throw new Error(`桌面壳没有写出端点，进程 ${child.exitCode}，目录 ${listing}\n` + logs);
  }
  if (process.platform === 'darwin') {
    const name = execFileSync('/usr/bin/osascript', ['-l', 'JavaScript', '-e',
      `ObjC.import('AppKit'); $.NSRunningApplication.runningApplicationWithProcessIdentifier(${info.pid}).localizedName.js`
    ], { encoding: 'utf8' }).trim();
    if (name !== 'Pilot') throw new Error('macOS 识别的应用名称不对：' + name);
    console.log('MACOS_APP_NAME_OK: ' + name);
  }
  const created = await fetch(`http://127.0.0.1:${info.controlPort}/tabs`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-pilot-token': info.token },
    body: JSON.stringify({ sessionId: 'smoke' })
  });
  if (!created.ok) throw new Error(`创建标签失败 HTTP ${created.status}`);
  const tab = await created.json();
  const denied = await fetch(`http://127.0.0.1:${info.controlPort}/tabs`);
  if (denied.status !== 401) throw new Error(`没有令牌也进了控制口：HTTP ${denied.status}`);

  const browser = await chromium.connectOverCDP(`http://127.0.0.1:${info.cdpPort}`);
  const marker = `/marker/${tab.tabId}`;
  let page = null;
  for (let i = 0; i < 40 && !page; i++) {
    for (const context of browser.contexts()) {
      page = context.pages().find((item) => item.url().includes(marker)) || page;
    }
    if (!page) await delay(100);
  }
  if (!page) throw new Error('CDP 里没有这块浏览器页面');
  const text = await page.locator('#ready').getAttribute('data-tab');
  if (text !== tab.tabId) throw new Error('标记不对：' + text);
  const visible = await page.locator('body').innerText();
  if (visible.includes('pilot-tab-')) throw new Error('占位页把内部标记画出来了：' + visible);
  const shell = browser.contexts().flatMap((context) => context.pages()).find((item) => item.url() === 'about:blank');
  if (shell && shell === page) throw new Error('连到了外壳窗口，不是浏览器标签');
  const site = createServer((_req, res) => {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end('<!doctype html><h1 id="hi">local-ok</h1>');
  });
  await new Promise((resolve) => site.listen(0, '127.0.0.1', resolve));
  const sitePort = site.address().port;
  await page.goto(`http://127.0.0.1:${sitePort}/`, { waitUntil: 'domcontentloaded' });
  const heading = await page.locator('#hi').innerText();
  site.close();
  if (heading !== 'local-ok') throw new Error('导航后的页面内容不对：' + heading);
  await browser.close();
  console.log('SMOKE_OK');
} finally {
  child.kill('SIGTERM');
  rmSync(home, { recursive: true, force: true });
}
