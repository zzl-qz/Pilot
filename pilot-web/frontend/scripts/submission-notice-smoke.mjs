import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';
import { browserOptions } from './browser-options.mjs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// 损坏的提交凭据：不能阻断其他会话；界面给出导出与删除入口，刷新后仍然可见。
const root = fileURLToPath(new URL('..', import.meta.url));
const fixture = (sessionId) => `<html><head><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import { SubmissionNotice } from '/src/components/SubmissionNotice.jsx';
import { Modals } from '/src/components/Modals.jsx';
import { useStore } from '/src/store.js';
import '/src/styles.css';
window.store = useStore;
createRoot(document.getElementById('root')).render(React.createElement('div', null,
  React.createElement(SubmissionNotice, { sessionId: '${sessionId}' }),
  React.createElement(Modals)));
</script></body></html>`;

const server = await createServer({
  root,
  publicDir: '../src/main/resources/static',
  server: { host: '127.0.0.1', port: 0, hmr: false, proxy: {} },
  plugins: [{
    name: 'submission-fixture',
    configureServer(dev) {
      dev.middlewares.use('/__submission', async (request, response) => {
        response.setHeader('Content-Type', 'text/html');
        const html = fixture(new URL(request.url, 'http://x').searchParams.get('session') || 's1');
        response.end(await dev.transformIndexHtml('/__submission', html));
      });
    }
  }]
});
await server.listen();
const url = `http://127.0.0.1:${server.httpServer.address().port}/__submission`;
let browser;
try {
browser = await chromium.launch(browserOptions({ headless: true }));
  const page = await browser.newPage({ viewport: { width: 1000, height: 700 } });
  page.setDefaultTimeout(8000);
  page.on('pageerror', error => console.error('PAGEERROR', error.message));
  page.on('console', message => { if (message.type() === 'error') console.error('CONSOLE', message.text()); });
  await page.goto(url);
  await page.evaluate(() => {
    localStorage.clear();
    localStorage.setItem('pilot.pending-submission:broken', '{not json');
    localStorage.setItem('pilot.pending-submission:valid',
      JSON.stringify({ requestId: 'valid', sessionId: 's1', createdAt: 1, preview: '正常待确认消息' }));
  });
  await page.reload();
  const notice = page.locator('.submission-notice');
  await notice.waitFor().catch(async error => { console.error('BODY', (await page.content()).slice(0, 1500)); throw error; });
  assert.match(await notice.innerText(), /1 条提交凭据已损坏/);
  assert.match(await notice.innerText(), /正常待确认消息/);
  assert.equal(await page.getByRole('button', { name: '删除损坏记录' }).count(), 1);
  assert.equal(await page.getByRole('button', { name: '查询提交结果' }).count(), 1);

  // 刷新后损坏记录仍在（不是一次性的内存标记）
  await page.reload();
  await page.locator('.submission-notice').waitFor();
  assert.match(await page.locator('.submission-notice').innerText(), /提交凭据已损坏/);

  // 删除需要二次确认；取消不删除
  await page.getByRole('button', { name: '删除损坏记录' }).click();
  await page.locator('#confirm-dialog').waitFor();
  await page.locator('#confirm-cancel').click();
  assert.equal(await page.evaluate(() => localStorage.getItem('pilot.pending-submission:broken')), '{not json');

  await page.getByRole('button', { name: '删除损坏记录' }).click();
  await page.locator('#confirm-ok').click();
  await page.waitForFunction(() => !document.querySelector('.submission-broken'));
  assert.equal(await page.evaluate(() => localStorage.getItem('pilot.pending-submission:broken')), null);
  // 正常凭据不受影响，仍然需要人工核对
  assert.match(await page.locator('.submission-notice').innerText(), /正常待确认消息/);
  assert.equal(await page.evaluate(() => Boolean(localStorage.getItem('pilot.pending-submission:valid'))), true);

  // 其他会话不显示这条待确认，但损坏记录对所有会话可见
  const other = await browser.newPage({ viewport: { width: 1000, height: 700 } });
  await other.goto(url + '?session=s2');
  await other.evaluate(() => localStorage.setItem('pilot.pending-submission:broken2', 'oops'));
  await other.reload();
  await other.locator('.submission-notice').waitFor();
  const text = await other.locator('.submission-notice').innerText();
  assert.match(text, /提交凭据已损坏/);
  assert.doesNotMatch(text, /正常待确认消息/);
  await other.close();
  console.log('PASS 损坏凭据：不阻断其他会话、跨刷新保留、二次确认后删除');
} finally {
  await browser?.close();
  await server.close();
}