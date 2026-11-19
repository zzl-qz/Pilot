import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';
import { fileURLToPath } from 'node:url';

// 刷新后恢复未发送的附件（文件 + 图片 + 图片位置），移除或发送后清理副本。
const root = fileURLToPath(new URL('..', import.meta.url));
const fixture = `<html><head><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import { Composer } from '/src/components/Composer.jsx';
import { UiProvider } from '/src/components/UiProvider.jsx';
import { useStore } from '/src/store.js';
window.store = useStore;
import { api } from '/src/api.js';
import { installSubmissionFixture } from '/scripts/submission-fixture.mjs';
import '/src/styles.css'; import '/src/shell-vanilla.css'; import '/src/styles-addendum.css'; import '/src/workbench.css';
const attachmentLimits = { maxFiles: 64, maxFileBytes: 2097152, maxTotalBytes: 26214400, maxRequestBytes: 37000000 };
api.settings = async () => ({ attachmentLimits });
installSubmissionFixture(api);
window.sent = [];
api.sendMessage = async (id, body) => { window.sent.push({ id, body }); return { sessionId: id, runId: 'r' }; };
window.notices = [];
const notice = window.store.getState().showNotice;
useStore.setState({ sessionId: 'draft-a', settingsData: { attachmentLimits }, showNotice: message => { notices.push(message); notice(message); } });
createRoot(document.getElementById('root')).render(React.createElement(UiProvider, null, React.createElement(Composer)));
</script></body></html>`;

const server = await createServer({
  configFile: false,
  root,
  publicDir: '../src/main/resources/static',
  server: { host: '127.0.0.1', port: 0, hmr: false, proxy: {} },
  plugins: [{
    name: 'draft-fixture',
    configureServer(dev) {
      dev.middlewares.use('/__draft', async (_request, response) => {
        response.setHeader('Content-Type', 'text/html');
        response.end(await dev.transformIndexHtml('/__draft', fixture));
      });
    }
  }]
});
await server.listen();
const url = `http://127.0.0.1:${server.httpServer.address().port}/__draft`;
let browser;
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 } });
  page.setDefaultTimeout(8000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(url);
  const input = page.locator('#input-flow');
  await input.waitFor();
  await page.evaluate(() => localStorage.clear());
  await page.reload();
  await input.waitFor();

  const transfer = async (name, content, type) => {
    await page.locator('#input-flow').evaluate((el, { name, content, type }) => {
      const data = new DataTransfer();
      data.items.add(new File([content], name, { type }));
      el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
    }, { name, content, type });
    await page.waitForFunction(() => document.querySelector('#input-flow')?.getAttribute('aria-busy') === 'false');
  };

  const readComposer = () => page.evaluate(() => ({
    text: window.store.getState().composerApi.getText(),
    positions: window.store.getState().composerApi.getDraft().imagePositions,
    // 附件芯片挂在输入框下方的 rail 上，正文里只剩文字，所以位置一栏恒为空
    placement: [...document.querySelectorAll('[data-chip="attachment"]')]
      .map(chip => (chip.closest('.composer-attachments') ? 'rail' : 'body'))
  }));

  await input.fill('请检查附件');
  const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z4xkAAAAASUVORK5CYII=';
  await transfer('note.txt', 'hello', 'text/plain');
  await page.evaluate(async (base64) => {
    const bytes = Uint8Array.from(atob(base64), c => c.charCodeAt(0));
    const file = new File([bytes], 'shot.png', { type: 'image/png' });
    const el = document.querySelector('#input-flow');
    const data = new DataTransfer();
    data.items.add(file);
    el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
  }, png);
  await page.waitForFunction(() => document.querySelectorAll('[data-chip="attachment"]').length === 2);
  await page.waitForFunction(() => localStorage.getItem('pilot.draft.files.draft-a'));
  const before = await readComposer();
  assert.deepEqual(before.positions, [], '附件不在正文里，图片位置为空');
  assert.deepEqual(before.placement, ['rail', 'rail'], '附件芯片都在 rail 里');

  // 刷新：正文、附件与 rail 位置一起恢复
  await page.reload();
  await page.locator('#input-flow').waitFor();
  await page.waitForFunction(() => document.querySelectorAll('[data-chip="attachment"]').length === 2);
  const after = await readComposer();
  assert.deepEqual(after.placement, ['rail', 'rail'], '刷新后附件仍在 rail 里');
  assert.equal(after.text.trim(), before.text.trim(), '正文恢复（芯片两侧空白允许差异）');
  assert.deepEqual(after.positions, before.positions);
  assert.equal(await page.locator('.chip-thumb').count(), 1, '图片缩略图恢复');
  // rail 里的方块只露文件类型徽标，完整文件名放在 title 上
  assert.equal(await page.locator('[data-chip="attachment"][title*="note.txt"]').count(), 1, '文件附件恢复');

  // 移除附件后副本清掉（只删附件，不动正文）
  await page.locator('[data-chip="attachment"]').first().locator('.chip-remove').click();
  await page.waitForFunction(() => !localStorage.getItem('pilot.draft.files.draft-a') || JSON.parse(localStorage.getItem('pilot.draft.files.draft-a')).files.length === 1);
  await page.locator('[data-chip="attachment"]').first().locator('.chip-remove').click();
  await page.waitForFunction(() => localStorage.getItem('pilot.draft.files.draft-a') === null);
  assert.equal((await page.evaluate(() => window.store.getState().composerApi.getText())).trim(), before.text.trim(), '移除附件不改正文');

  // 存储配额不足时明确提示，而不是静默丢失
  await page.evaluate(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (key, value) {
      if (String(key).startsWith('pilot.draft.files.')) throw new Error('QuotaExceeded');
      return original.call(this, key, value);
    };
  });
  await transfer('big.bin', 'x'.repeat(2048), 'application/octet-stream');
  await page.waitForFunction(() => window.notices.some(text => /无法写入|超过/.test(text)));
  assert.equal(await page.locator('[data-chip="attachment"]').count(), 1, '附件仍在输入框里，可继续发送');
  await page.evaluate(() => { Storage.prototype.setItem = Storage.prototype.setItem; });

  // 发送成功后清理草稿副本
  await page.evaluate(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (key, value) { return original.call(this, key, value); };
  });
  await page.locator('#send').click();
  await page.waitForFunction(() => document.querySelectorAll('[data-chip="attachment"]').length === 0);
  assert.equal(await page.evaluate(() => localStorage.getItem('pilot.draft.files.draft-a')), null);
  assert.equal(await page.evaluate(() => localStorage.getItem('pilot.draft.draft-a')), null);
  assert.deepEqual(errors, []);
  console.log('PASS 草稿附件：刷新恢复正文/文件/图片/位置，移除与发送后清理，配额失败有提示');
} finally {
  await browser?.close();
  await server.close();
}