import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { prepareDevelopmentHost } from '../../../pilot-desktop/brand-host.mjs';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// 隔离的只读 HTTP/SSE 后端，使用真实 App、store、api 与桌面主进程，不调用模型。
const root = fileURLToPath(new URL('..', import.meta.url));
const desktopRoot = fileURLToPath(new URL('../../../pilot-desktop', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-workbench-refresh-'));
const calls = [], streams = new Set();
let revision = 0, fail = false;
const workspace = '/workspace/pilot';
const session = id => ({ sessionId: id, title: id === 'two' ? `手机更新 ${revision}` : '第一个任务', workspace, projectId: 'p' });
const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="icon" href="data:,"><title>Pilot 同步验收</title></head><body><div id="root"></div><script type="module">
import React from 'react';import {createRoot} from 'react-dom/client';import App from '/src/components/App.jsx';import {useStore} from '/src/store.js';
import '/src/styles.css';import '/src/shell-vanilla.css';import '/src/styles-addendum.css';import '/src/workbench.css';
window.store=useStore;window.pageInstance=crypto.randomUUID();
createRoot(document.getElementById('root')).render(React.createElement(App));
</script></body></html>`;
const server = await createServer({ root, server: { host: '127.0.0.1', port: 0, proxy: {} }, plugins: [{
  name: 'workbench-refresh-fixture', configureServer(server) {
    server.middlewares.use('/__refresh', async (_req, res) => { res.setHeader('Content-Type', 'text/html;charset=utf-8'); res.end(await server.transformIndexHtml('/__refresh', fixture)); });
    server.middlewares.use('/__embedded', (_req, res) => { res.setHeader('Content-Type', 'text/html'); res.end('<h1>独立浏览器页面</h1><script>window.pageInstance=crypto.randomUUID()</script>'); });
    server.middlewares.use('/api', (req, res) => {
      const path = new URL('/api' + req.url, 'http://localhost').pathname;
      calls.push({ path, method: req.method });
      if (path.endsWith('/events') || path.endsWith('/browser/stream')) {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
        res.write(': connected\n\n'); streams.add(res); res.on('close', () => streams.delete(res)); return;
      }
      res.setHeader('Content-Type', 'application/json');
      if (fail && path === '/api/projects') { res.statusCode = 503; res.end(JSON.stringify({ message: '模拟服务离线' })); return; }
      let data = {};
      if (path === '/api/projects') data = [{ id: 'p', name: `项目版本 ${revision}`, folders: [workspace] }];
      else if (path === '/api/sessions') data = [session('one'), session('two'), ...(revision ? [session('mobile-new')] : [])];
      else if (/^\/api\/sessions\/[^/]+$/.test(path)) data = session(path.split('/').at(-1));
      else if (path.endsWith('/history')) data = { events: Array.from({ length: 25 }, (_, i) => ({ type: 'user.message', sequence: i + 1, payload: { text: `历史问题 ${i + 1}：` + '保留当前阅读位置与输入内容。'.repeat(8) } })), hasMore: false };
      else if (path.endsWith('/runs/active')) data = null;
      else if (path.endsWith('/queue')) data = { entries: revision ? [{ content: `手机追加消息 ${revision}` }] : [], revision: String(revision) };
      else if (path.endsWith('/context')) data = { usedTokens: 100 + revision, limitTokens: 10000 };
      else if (path.endsWith('/plan')) data = { plan: [] };
      else if (path.endsWith('/files')) data = { entries: [{ path: `file-${revision}.txt`, name: `file-${revision}.txt`, directory: false }] };
      else if (path.endsWith('/bookmarks') || path.endsWith('/products') || path.endsWith('/skill-drafts') || path === '/api/schedules') data = [];
      else if (path.endsWith('/results')) data = { items: [] };
      res.end(JSON.stringify(data));
    });
  }
}] });
await server.listen();
const url = `http://127.0.0.1:${server.httpServer.address().port}/__refresh`;
let browser, desktop;
const mod = process.platform === 'darwin' ? 'Meta' : 'Control';
async function settled(page) {
  await page.waitForFunction(() => window.store?.getState().ready && window.store.getState().composerApi && !window.store.getState().ui.refreshing);
}
async function run(page, name) {
  revision = 0; fail = false;
  page.setDefaultTimeout(10000); await page.setViewportSize({ width: 1440, height: 900 });
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto(url); await settled(page);
  await page.evaluate(() => store.getState().activate('two'));
  await page.getByRole('button', { name: '工作区文件', exact: true }).click();
  await page.getByRole('treeitem', { name: 'file-0.txt' }).waitFor();
  const input = page.locator('#input-flow');
  await input.fill('保留中文草稿和附件');
  await input.evaluate(el => { const data = new DataTransfer(); data.items.add(new File(['content'], '边界测试.txt', { type: 'text/plain' })); el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true })); });
  await page.locator('[data-chip="attachment"]').waitFor();
  // 等待字体和真实向上滚动完成，避免把首屏贴底过程误当成已停留在阅读中途。
  await page.evaluate(() => document.fonts.ready);
  await page.locator('.thread').hover();
  await page.mouse.wheel(0, -800);
  await page.waitForFunction(() => { const el = document.querySelector('.thread'); return el && el.scrollTop < el.scrollHeight - el.clientHeight - 30; });
  // 阅读中途刷新，不触发重新激活或历史重建。
  await page.locator('.thread').evaluate(el => { el.scrollTop = 250; el.dispatchEvent(new Event('scroll')); });
  const before = await page.evaluate(() => ({ instance: pageInstance, text: store.getState().composerApi.getText(), scroll: document.querySelector('.thread').scrollTop }));
  const historyCalls = calls.filter(call => call.path.endsWith('/history')).length;
  revision = 1;
  await page.locator('#refresh-workbench').click(); await settled(page);
  assert.equal(await page.evaluate(() => store.getState().sessionTitle), '手机更新 1');
  assert.equal(await page.evaluate(() => store.getState().sessions.length), 3);
  assert.equal(await page.evaluate(() => store.getState().queueEntries[0].content), '手机追加消息 1');
  await page.getByRole('treeitem', { name: 'file-1.txt' }).waitFor();
  assert.equal(await page.evaluate(() => pageInstance), before.instance);
  assert.equal(await page.evaluate(() => store.getState().composerApi.getText()), before.text);
  assert.equal(await page.locator('[data-chip="attachment"]').count(), 1);
  assert.ok(Math.abs(await page.locator('.thread').evaluate(el => el.scrollTop) - before.scroll) < 3);
  assert.equal(calls.filter(call => call.path.endsWith('/history')).length, historyCalls);

  fail = true; await page.locator('#refresh-workbench').click();
  await page.getByText(/刷新失败：模拟服务离线/).waitFor(); await settled(page);
  assert.equal(await page.evaluate(() => store.getState().projects[0].name), '项目版本 1');
  fail = false; revision = 2; await page.keyboard.press('F5'); await settled(page);
  assert.equal(await page.evaluate(() => store.getState().sessionTitle), '手机更新 2');
  assert.equal(await page.evaluate(() => pageInstance), before.instance);

  // 有未发送文件时重新载入要给出可执行的替代操作。
  await page.keyboard.press(`${mod}+r`);
  await page.getByText(/有未发送的附件/).waitFor();
  assert.equal(await page.evaluate(() => pageInstance), before.instance);
  await page.getByRole('button', { name: '移除 边界测试.txt' }).click();
  await page.keyboard.press(`${mod}+r`);
  await page.waitForFunction(old => window.pageInstance && pageInstance !== old && window.store?.getState().ready, before.instance);
  assert.equal(await page.evaluate(() => store.getState().sessionId), 'two', '重新载入恢复选中的任务，不跳到第一个');
  assert.match(await input.innerText(), /保留中文草稿和附件/);

  // 配额失败和输入法合成时不丢弃草稿。
  await page.evaluate(() => { window.realSet = Storage.prototype.setItem; Storage.prototype.setItem = function () { throw Error('quota'); }; store.getState().composerApi.setText('配额耗尽后的新草稿'); });
  assert.equal(await page.evaluate(() => store.getState().reloadWorkbench()), false);
  await page.getByText(/草稿未能完整保存/).waitFor();
  await page.evaluate(() => { Storage.prototype.setItem = realSet; document.querySelector('#input-flow').dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true })); });
  assert.equal(await page.evaluate(() => store.getState().reloadWorkbench()), false);
  await page.getByText(/请先完成输入法中的文字/).waitFor();
  assert.equal(await input.evaluate(el => !el.dispatchEvent(new KeyboardEvent('keydown', { key: 'r', ctrlKey: true, isComposing: true, bubbles: true, cancelable: true }))), true, '合成输入期间也阻止浏览器默认重新载入');
  await page.evaluate(() => document.querySelector('#input-flow').dispatchEvent(new CompositionEvent('compositionend', { bubbles: true })));

  revision = 3; await page.evaluate(() => window.dispatchEvent(new Event('online')));
  await page.waitForFunction(() => store.getState().sessionTitle === '手机更新 3');
  await settled(page);
  // 后台页面会主动暂停轮询；先确认测试窗口可见，再等待真实的默认 15 秒轮询。
  await page.bringToFront();
  await page.waitForFunction(() => !document.hidden);
  // 验证其他设备新建任务无需刷新页面。
  revision = 4;
  await page.waitForFunction(() => store.getState().projects[0].name === '项目版本 4', null, { timeout: 30000 });
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await page.locator('#refresh-workbench').waitFor({ state: 'visible' });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: join(output, `${name}-${width}.png`), animations: 'disabled' });
  }
  assert.deepEqual(errors, []);
  assert.equal(calls.some(call => /\/(messages|cancel|pause)$/.test(call.path) && call.method !== 'GET'), false);
  console.log(`${name}: PASS HTTP/SSE 同步、附件/草稿/滚动保留、失败重试、快捷键、重载恢复、存储/输入法边界、真实轮询、三种宽度`);
}
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true });
  await run(await browser.newPage(), 'chromium'); await browser.close(); browser = null;
  const env = { ...process.env, PILOT_URL: url, PILOT_BROWSER_HOME: join(output, 'browser') };
  delete env.ELECTRON_RUN_AS_NODE; delete env.PILOT_DESKTOP_SMOKE;
  desktop = await _electron.launch({ executablePath: prepareDevelopmentHost(), args: [desktopRoot, `--user-data-dir=${join(output, 'profile')}`], env });
  const page = await desktop.firstWindow();
  await desktop.evaluate(({ BrowserWindow }) => { const win = BrowserWindow.getAllWindows()[0]; win.show(); win.focus(); });
  await run(page, 'electron');
  await page.setViewportSize({ width: 1440, height: 900 });
  revision = 5;
  await desktop.evaluate(({ Menu }) => Menu.getApplicationMenu().getMenuItemById('workbench-refresh').click());
  await page.waitForFunction(() => store.getState().sessionTitle === '手机更新 5'); await settled(page);
  const old = await page.evaluate(() => pageInstance);
  await desktop.evaluate(({ Menu }) => Menu.getApplicationMenu().getMenuItemById('workbench-reload').click());
  await page.waitForFunction(old => pageInstance !== old && window.store?.getState().ready, old);
  assert.equal(await page.evaluate(() => store.getState().sessionId), 'two');

  await page.evaluate(async target => { await pilotDesktop.ensureTab('two'); await pilotDesktop.navigate(target); pilotDesktop.setBounds({ x: 500, y: 200, width: 400, height: 300, visible: true }); }, url.replace('/__refresh', '/__embedded'));
  await page.waitForTimeout(500);
  revision = 6;
  const embeddedId = await desktop.evaluate(({ webContents }) => webContents.getAllWebContents().find(c => c.getURL().endsWith('/__embedded')).id);
  const embeddedInstance = await desktop.evaluate(({ webContents }, id) => webContents.fromId(id).executeJavaScript('pageInstance'), embeddedId);
  await desktop.evaluate(({ webContents }, id) => { const contents = webContents.fromId(id); contents.focus(); contents.sendInputEvent({ type: 'keyDown', keyCode: 'F5' }); contents.sendInputEvent({ type: 'keyUp', keyCode: 'F5' }); }, embeddedId);
  await page.waitForFunction(() => store.getState().sessionTitle === '手机更新 6');
  assert.equal(await desktop.evaluate(({ webContents }, id) => webContents.fromId(id).executeJavaScript('pageInstance'), embeddedId), embeddedInstance);
  const external = url.replace('127.0.0.1', 'localhost').replace('/__refresh', '/__embedded');
  await page.evaluate(target => { const link = document.createElement('a'); link.href = target; document.body.append(link); link.click(); link.remove(); }, external);
  await page.waitForFunction(() => store.getState().ui.inspectorOpen && store.getState().ui.inspectorTab === 'browser');
  await page.waitForFunction(() => store.getState().ui.notice?.text === '已在内置浏览器打开链接');
  assert.equal(page.url(), url, '外部链接不能替换工作台');
  let externalLoaded = false; const externalDeadline = Date.now() + 10000;
  while (!externalLoaded && Date.now() < externalDeadline) { externalLoaded = await desktop.evaluate(({ webContents }, target) => webContents.getAllWebContents().some(c => c.getURL() === target), external); if (!externalLoaded) await new Promise(resolve => setTimeout(resolve, 50)); }
  assert.ok(externalLoaded, '内置浏览器加载目标网页');
  // 主进程主动加载不经过导航事件；独立检查 preload 和 IPC 的防护。
  await desktop.evaluate(({ BrowserWindow }, target) => BrowserWindow.getAllWindows()[0].loadURL(target), url.replace('127.0.0.1', 'localhost'));

  assert.equal(await page.evaluate(() => typeof window.pilotDesktop), 'undefined', '不同来源不获得桌面桥');
  console.log('PASS: 真实桌面原生菜单、内置浏览器焦点下刷新工作台、重载 IPC 来源限制');
  console.log('Screenshots:', output);
} finally {
  await browser?.close(); await desktop?.close();
  for (const response of streams) response.end();
  await server.close();
}
