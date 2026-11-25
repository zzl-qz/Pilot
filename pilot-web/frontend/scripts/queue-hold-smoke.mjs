import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';
import { browserOptions } from './browser-options.mjs';
import { fileURLToPath } from 'node:url';

// 队列暂停/恢复：暂停不打断当前轮、队列保留可编辑、刷新后仍显示暂停。
const root = fileURLToPath(new URL('..', import.meta.url));
const fixture = `<html><head><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import { MessageQueue } from '/src/components/MessageQueue.jsx';
import { useStore } from '/src/store.js';
import { api } from '/src/api.js';
import '/src/styles.css'; import '/src/shell-vanilla.css'; import '/src/styles-addendum.css'; import '/src/workbench.css'; import '/src/components/conversation.css';
window.store = useStore; window.api = api; window.calls = []; window.notices = [];
window.entries = [
  { content: '任务一', text: '任务一', attachments: [], imagePositions: [] },
  { content: '任务二', text: '任务二', attachments: [], imagePositions: [] }
];
window.held = false;
function queueData() {
  return {
    items: window.entries.map(entry => entry.content),
    entries: window.entries,
    revision: 'r-' + window.entries.length,
    held: window.held,
    waitingReason: window.held ? '队列已暂停，恢复后才会开始下一条' : ''
  };
}
api.queue = async () => queueData();
api.holdQueue = async (sessionId, held) => { window.calls.push({ sessionId, held }); window.held = held; return queueData(); };
api.updateQueueItem = async (sessionId, index, body) => { window.calls.push({ sessionId, index, body }); window.entries[index] = { ...window.entries[index], content: body.content, text: body.content }; return queueData(); };
useStore.setState({
  sessionId: 's1', queueEntries: window.entries, queueRevision: 'r-2', queueHeld: false, queueWaitingReason: '',
  showNotice: message => window.notices.push(message)
});
createRoot(document.getElementById('root')).render(React.createElement(MessageQueue));
</script></body></html>`;

const server = await createServer({
  root,
  publicDir: '../src/main/resources/static',
  server: { host: '127.0.0.1', port: 0, hmr: false, proxy: {} },
  plugins: [{
    name: 'queue-hold-fixture',
    configureServer(dev) {
      dev.middlewares.use('/__hold', async (_request, response) => {
        response.setHeader('Content-Type', 'text/html');
        response.end(await dev.transformIndexHtml('/__hold', fixture));
      });
    }
  }]
});
await server.listen();
const url = `http://127.0.0.1:${server.httpServer.address().port}/__hold`;
let browser;
try {
  browser = await chromium.launch(browserOptions());
  const page = await browser.newPage({ viewport: { width: 1000, height: 700 } });
  page.setDefaultTimeout(8000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(url);
  await page.locator('#message-queue').waitFor();

  // 暂停：调用接口，标题显示等待原因，按钮变恢复
  await page.getByRole('button', { name: '暂停队列' }).click();
  await page.waitForFunction(() => window.calls.length === 1);
  assert.deepEqual(await page.evaluate(() => window.calls[0]), { sessionId: 's1', held: true });
  await page.waitForFunction(() => document.querySelector('.queue-head-meta')?.textContent?.includes('暂停'));
  assert.equal(await page.getByRole('button', { name: '恢复队列' }).count(), 1);
  assert.equal(await page.evaluate(() => window.store.getState().queueHeld), true);

  // 暂停期间仍可编辑
  await page.getByRole('button', { name: '改这条' }).first().click();
  await page.getByRole('textbox', { name: '编辑排队消息' }).fill('暂停时改的任务一');
  await page.getByRole('button', { name: '保存' }).click();
  await page.waitForFunction(() => window.calls.some(call => call.body?.content === '暂停时改的任务一'));
  assert.equal(await page.evaluate(() => window.held), true, '编辑不改变暂停状态');

  // 恢复：调用接口，标题恢复正常
  await page.getByRole('button', { name: '恢复队列' }).click();
  await page.waitForFunction(() => window.calls.filter(call => 'held' in call).length === 2);
  assert.equal((await page.evaluate(() => window.calls.filter(call => 'held' in call)))[1].held, false);
  await page.waitForFunction(() => !document.querySelector('.queue-head-meta')?.textContent?.includes('暂停'));
  assert.equal(await page.evaluate(() => window.store.getState().queueHeld), false);

  // 刷新：服务端仍暂停时显示恢复按钮和原因
  await page.evaluate(() => { window.held = true; window.store.setState({ queueHeld: true, queueWaitingReason: '队列已暂停，恢复后才会开始下一条' }); });
  await page.reload();
  await page.locator('#message-queue').waitFor();
  await page.evaluate(async () => {
    window.held = true;
    await window.store.getState().loadQueue();
  });
  await page.waitForFunction(() => document.querySelector('.queue-head-meta')?.textContent?.includes('暂停'));
  assert.equal(await page.getByRole('button', { name: '恢复队列' }).count(), 1);

  // 暂停/恢复失败时说明队列状态没有变，并告诉用户还能再试
  await page.evaluate(() => { window.api.holdQueue = async () => { throw Error('网络断开'); }; });
  await page.getByRole('button', { name: '恢复队列' }).click();
  await page.waitForFunction(() => window.notices.some(text => /切换队列状态失败/.test(text)));
  const holdFailure = await page.evaluate(() => window.notices.at(-1));
  assert.match(holdFailure, /网络断开/);
  assert.match(holdFailure, /可以再试一次/);
  assert.equal(await page.evaluate(() => window.store.getState().queueHeld), true, '失败后暂停状态不变');
  assert.equal(await page.getByRole('button', { name: '恢复队列' }).count(), 1);
  assert.deepEqual(errors, []);
  console.log('PASS 队列暂停/恢复：接口调用、等待原因、暂停时仍可编辑、刷新后保持');
} finally {
  await browser?.close();
  await server.close();
}