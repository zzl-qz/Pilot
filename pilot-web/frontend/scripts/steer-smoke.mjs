import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';
import { browserOptions } from './browser-options.mjs';
import { fileURLToPath } from 'node:url';

// 「纠正本轮」：运行中直接提交纠正、不清空失败文本、本轮结束未应用时把内容放回输入框。
const root = fileURLToPath(new URL('..', import.meta.url));
const fixture = `<html><head><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import { Composer } from '/src/components/Composer.jsx';
import { UiProvider } from '/src/components/UiProvider.jsx';
import { useStore } from '/src/store.js';
window.api = api;
import { api } from '/src/api.js';
import { installSubmissionFixture } from '/scripts/submission-fixture.mjs';
import '/src/styles.css'; import '/src/shell-vanilla.css'; import '/src/styles-addendum.css'; import '/src/workbench.css';
window.store = useStore;
const attachmentLimits = { maxFiles: 64, maxFileBytes: 2097152, maxTotalBytes: 26214400, maxRequestBytes: 37000000 };
api.settings = async () => ({ attachmentLimits });
installSubmissionFixture(api);
window.steers = [];
window.notices = [];
api.steer = async (sessionId, body, requestId) => { window.steers.push({ sessionId, body, requestId }); return { runId: body.runId }; };
const notice = useStore.getState().showNotice;
useStore.setState({
  sessionId: 'steer-a', runId: 'run-1', running: true, modelReady: () => true,
  settingsData: { attachmentLimits }, thread: { rows: [] },
  showNotice: message => { notices.push(message); notice(message); }
});
createRoot(document.getElementById('root')).render(React.createElement(UiProvider, null, React.createElement(Composer)));
</script></body></html>`;

const server = await createServer({
  configFile: false,
  root,
  publicDir: '../src/main/resources/static',
  server: { host: '127.0.0.1', port: 0, hmr: false, proxy: {} },
  plugins: [{
    name: 'steer-fixture',
    configureServer(dev) {
      dev.middlewares.use('/__steer', async (_request, response) => {
        response.setHeader('Content-Type', 'text/html');
        response.end(await dev.transformIndexHtml('/__steer', fixture));
      });
    }
  }]
});
await server.listen();
const url = `http://127.0.0.1:${server.httpServer.address().port}/__steer`;
let browser;
try {
browser = await chromium.launch(browserOptions({ headless: true }));
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 } });
  page.setDefaultTimeout(8000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(url);
  const input = page.locator('#input-flow');
  await input.waitFor();
  await page.evaluate(() => localStorage.clear());

  // 运行中 + 有文字才出现；按钮可键盘操作
  await input.fill('这轮先只核对文案');
  const steer = page.getByRole('button', { name: /纠正本轮/ });
  await steer.waitFor();
  assert.equal(await steer.getAttribute('aria-disabled'), null);
  await page.evaluate(() => document.getElementById('steer-current').focus());
  const focused = await page.evaluate(() => document.activeElement?.id);
  assert.equal(focused, 'steer-current', '按钮可获得焦点');

  await page.keyboard.press('Enter');
  await page.waitForFunction(() => window.steers.length === 1);
  const call = await page.evaluate(() => window.steers[0]);
  assert.equal(call.sessionId, 'steer-a');
  assert.equal(call.body.runId, 'run-1', '绑定点击时看到的运行');
  assert.equal(call.body.content, '这轮先只核对文案');
  assert.equal(await page.evaluate(() => window.store.getState().composerApi.getText()), '', '提交成功后清空输入');
  assert.match(await page.evaluate(() => window.notices.at(-1)), /等待本轮/);
  assert.match(await page.getByRole('button', { name: /纠正等待应用/ }).innerText(), /等待应用/);

  // 收到对应的执行事件后显示已应用
  await page.evaluate(() => window.store.setState(state => ({
    thread: { ...state.thread, rows: [...(state.thread?.rows || []), { type: 'user', text: '这轮先只核对文案', steered: true }] }
  })));
  await page.waitForFunction(() => window.notices.some(text => /纠正已应用/.test(text)));
  assert.equal(await page.getByRole('button', { name: /纠正本轮/ }).count(), 0, '没有草稿时不显示');

  // 旧运行结束立即接跑新运行：纠正未命中事件，内容放回输入框
  await input.fill('再核对标题');
  await page.getByRole('button', { name: /纠正本轮/ }).click();
  await page.waitForFunction(() => window.steers.length === 2);
  await page.evaluate(() => window.store.setState({ runId: 'run-2' }));
  await page.waitForFunction(() => window.notices.some(text => /本轮已结束，纠正可能未被应用/.test(text)));
  assert.equal(await page.evaluate(() => window.store.getState().composerApi.getText()), '再核对标题', '未应用时保留文本');

  // 提交失败时保留文本
  await page.evaluate(() => { window.api.steer = async () => { throw new Error('网络失败'); }; });
  await input.fill('第三段');
  await page.getByRole('button', { name: /纠正本轮/ }).click();
  await page.waitForFunction(() => window.notices.some(text => /网络失败|未提交|失败|尚未确认|拒绝/.test(text)));
  assert.equal(await page.evaluate(() => window.store.getState().composerApi.getText()), '第三段');
  assert.deepEqual(errors, []);
  console.log('PASS 纠正本轮：运行绑定、等待应用、事件确认、切换运行保留文本、失败保留文本');
} finally {
  await browser?.close();
  await server.close();
}