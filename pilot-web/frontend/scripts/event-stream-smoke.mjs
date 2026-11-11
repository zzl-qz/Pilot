import { createServer } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-event-ui-'));
const streams = new Map(), histories = new Map(), connects = new Map();
const frame = event => `event:${event.type}\ndata:${JSON.stringify(event)}\n\n`;
const control = (id, type, payload) => ({ sessionId: id, type, payload });
const event = (id, sequence, type, payload) => ({ sessionId: id, sequence, runId: 'r', type, payload, timestamp: new Date().toISOString() });
function publish(id, item, durable = true) {
  if (durable) histories.get(id).push(item);
  for (const response of streams.get(id) || []) response.write(frame(item));
}
const fixture = `<html><meta charset="utf-8"><body><pre id="output"></pre><script type="module">
import {useStore,closeStreams} from '/src/store.ts';import {initialThread} from '/src/thread-reducer.ts';
window.store=useStore;window.closeStreams=closeStreams;const id=new URL(location.href).searchParams.get('id');
useStore.setState({sessionId:id,sessionEpoch:1,thread:initialThread(),loadSessions:async()=>[],loadTask:async()=>{},loadQueue:async()=>{},refreshContext:async()=>{}});
useStore.subscribe(state=>{document.querySelector('#output').textContent=JSON.stringify({cursor:state.thread.cursor,rows:state.thread.rows});});
useStore.getState().connectSse(id,0);
</script></body></html>`;
const server = await createServer({ root, cacheDir: join(output, 'cache'), server: { host: '127.0.0.1', port: 0, proxy: {} }, plugins: [{ name: 'event-fixture', configureServer(server) {
  server.middlewares.use(async (req, res, next) => {
    const url = new URL(req.url, 'http://local');
    if (url.pathname === '/__events') { res.setHeader('Content-Type', 'text/html'); res.end(await server.transformIndexHtml('/__events', fixture)); return; }
    const match = url.pathname.match(/^\/api\/sessions\/([^/]+)\/events$/);
    if (match) {
      const id = match[1], after = Number(url.searchParams.get('after'));
      connects.set(id, [...(connects.get(id) || []), { at: Date.now(), after }]);
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      res.write(frame(control(id, 'stream.ready', { historyRevision: 0 })));
      for (const item of histories.get(id) || []) if (item.sequence > after) res.write(frame(item));
      const high = histories.get(id).at(-1).sequence;
      res.write(frame(control(id, 'stream.preview', { historyRevision: 0, baseSequence: high, runId: 'r', previewId: 'p', previewOffset: 2, text: high === 1 ? '你好' : '', reasoning: '', complete: true })));
      if (!streams.has(id)) streams.set(id, new Set()); streams.get(id).add(res);
      req.on('close', () => streams.get(id).delete(res)); return;
    }
    if (url.pathname.endsWith('/runs/active')) { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ runId: 'r' })); return; }
    next();
  });
} }] });
let browser, electron;
async function verify(page, id, background) {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  histories.set(id, [event(id, 1, 'run.started', { input: '恢复测试' })]);
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/__events?id=${id}`);
  await page.waitForFunction(() => store.getState().thread.rows.at(-1)?.reply?.preview === '你好', null, { polling: 100, timeout: 10000 });
  await background(true);
  // 后台帧停顿模拟：真实浏览器/桌面执行定时兜底。操作系统休眠另列为未验证。
  await page.evaluate(() => { window.requestAnimationFrame = () => 0; });
  for (let i = 0; i < 100; i++) publish(id, { ...event(id, 1, 'assistant.preview', { text: '中' }), previewId: 'p', previewOffset: i + 3 }, false);
  await page.waitForFunction(() => store.getState().thread.rows.at(-1).reply.preview.length === 102, null, { polling: 100, timeout: 10000 });
  const done = event(id, 2, 'assistant.delta', { text: '正式结果🙂' });
  publish(id, done); publish(id, done, false);
  await page.waitForFunction(() => store.getState().thread.cursor === 2, null, { polling: 100, timeout: 10000 });
  assert.equal(await page.evaluate(() => store.getState().thread.rows.at(-1).reply.text), '正式结果🙂');
  for (const response of streams.get(id)) response.end();
  histories.get(id).push(event(id, 3, 'assistant.delta', { text: '补发' }));
  await page.waitForFunction(() => store.getState().thread.cursor === 3, null, { polling: 100, timeout: 10000 });
  assert.equal(connects.get(id).at(-1).after, 2);
  assert.equal(await page.evaluate(() => store.getState().thread.rows.at(-1).reply.text), '正式结果🙂补发');
  await background(false);
  await page.screenshot({ path: join(output, `${id}.png`) });
  await page.evaluate(() => closeStreams());
  assert.deepEqual(errors, []);
  console.log(`${id}: PASS real SSE, snapshot, background fallback, duplicate, reconnect cursor`);
}
try {
  await server.listen();
  browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage(), other = await browser.newPage();
  await verify(page, 'chrome', async hidden => { await (hidden ? other : page).bringToFront(); });
  await browser.close(); browser = null;
  if (!process.argv.includes('--web-only')) {
    const main = join(output, 'main.cjs');
    await writeFile(main, `const {app,BrowserWindow}=require('electron');app.setPath('userData',${JSON.stringify(join(output,'profile'))});app.whenReady().then(()=>new BrowserWindow({show:true,webPreferences:{contextIsolation:true,nodeIntegration:false}}).loadURL('about:blank'));`);
    const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE;
    electron = await _electron.launch({ executablePath: electronBinary(), args: [main], env });
    await verify(await electron.firstWindow(), 'electron', hidden => electron.evaluate(({BrowserWindow}, hide) => {const w=BrowserWindow.getAllWindows()[0];if(hide)w.hide();else w.show();}, hidden));
  }
  console.log('验证产物:', output);
} finally {
  for (const set of streams.values()) for (const response of set) response.end();
  await browser?.close(); await electron?.close(); await server.close();
  await rm(join(output, 'cache'), { recursive: true, force: true });
}
