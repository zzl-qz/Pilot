import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';
import { browserOptions } from './browser-options.mjs';
import { fileURLToPath } from 'node:url';

// 取回排队消息：带附件、已有草稿、读取失败三种情况。
const root = fileURLToPath(new URL('..', import.meta.url));
const fixture = `<html><head><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import { MessageQueue } from '/src/components/MessageQueue.jsx';
import { useStore } from '/src/store.js';
import { api } from '/src/api.js';
import '/src/styles.css'; import '/src/components/conversation.css';
window.store = useStore; window.calls = []; window.notices = [];
window.entries = [{ content: '初始', text: '初始', attachments: [], imagePositions: [] }];
window.revision = 'r1';
window.draftText = '已有草稿';
window.draftFiles = [{ id: 'keep', name: 'keep.txt', mimeType: 'text/plain', size: 4, dataBase64: 'data:text/plain;base64,a2VlcA==' }];
function queueData() {
  return { items: window.entries.map(entry => entry.content), entries: window.entries, revision: window.revision, held: false, waitingReason: '' };
}
api.queue = async () => queueData();
api.deleteQueueItem = async (sessionId, index, revision) => {
  window.calls.push({ sessionId, index, revision });
  window.entries = window.entries.filter((_, i) => i !== index);
  window.revision = 'r' + (Number(window.revision.slice(1)) + 1);
  return queueData();
};
window.fetch = async url => {
  if (String(url).includes('fail')) return { ok: false, status: 500, arrayBuffer: async () => new ArrayBuffer(0) };
  const bytes = new TextEncoder().encode('image-bytes');
  return { ok: true, status: 200, arrayBuffer: async () => bytes.buffer };
};
useStore.setState({
  sessionId: 's1',
  queueEntries: window.entries,
  queueRevision: window.revision,
  showNotice: message => window.notices.push(message),
  composerApi: {
    getText: () => window.draftText,
    // 模拟真实 composer-input.ts 语义：setText 会清空附件芯片，replaceText 保留附件芯片
    setText: text => { window.draftText = text; window.draftFiles = []; },
    replaceText: text => { window.draftText = text; },
    appendText: text => { window.draftText += text; },
    getDraft: () => ({ text: window.draftText, imagePositions: [] }),
    insertAttachments: items => { window.draftFiles = [...window.draftFiles, ...items]; },
    attachmentIds: () => window.draftFiles.map(item => item.id),
    focus: () => {}
  }
});
function setEntries(entries) {
  window.entries = entries;
  window.revision = 'r1';
  window.store.setState({ queueEntries: entries, queueRevision: 'r1' });
}
window.setEntries = setEntries;
createRoot(document.getElementById('root')).render(React.createElement(MessageQueue));
</script></body></html>`;

const server = await createServer({
  root,
  publicDir: '../src/main/resources/static',
  server: { host: '127.0.0.1', port: 0, hmr: false, proxy: {} },
  plugins: [{
    name: 'queue-takeback-fixture',
    configureServer(dev) {
      dev.middlewares.use('/__takeback', async (_request, response) => {
        response.setHeader('Content-Type', 'text/html');
        response.end(await dev.transformIndexHtml('/__takeback', fixture));
      });
    }
  }]
});
await server.listen();
const url = `http://127.0.0.1:${server.httpServer.address().port}/__takeback`;
let browser;
try {
browser = await chromium.launch(browserOptions({ headless: true }));
  const page = await browser.newPage({ viewport: { width: 1100, height: 800 } });
  page.setDefaultTimeout(8000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(url);
  await page.locator('#message-queue').waitFor();

  // 1) 纯文本 + 已有草稿：正文追加到新行，已有附件保留
  await page.evaluate(() => window.setEntries([{ content: '取回的正文', text: '取回的正文', attachments: [], imagePositions: [] }]));
  await page.getByRole('button', { name: '放回输入框' }).click();
  await page.waitForFunction(() => window.calls.length === 1);
  assert.equal(await page.evaluate(() => window.draftText), '已有草稿\n取回的正文');
  assert.equal(await page.evaluate(() => window.draftFiles.length), 1, '已有附件保留');
  assert.equal(await page.locator('.queue-item').count(), 0);
  assert.match(await page.evaluate(() => window.notices.at(-1)), /已放回输入框/);

  // 2) 带图片：附件芯片带回，正文仍保留
  await page.evaluate(() => {
    window.draftText = '';
    window.draftFiles = [];
    window.store.setState({ composerApi: { ...window.store.getState().composerApi, attachmentIds: () => window.draftFiles.map(item => item.id) } });
    window.setEntries([{
      content: '看图', text: '看图', attachments: [{ name: 'a.png', mimeType: 'image/png', url: '/api/sessions/s1/attachments/a.png' }],
      imagePositions: [0]
    }]);
  });
  await page.getByRole('button', { name: '放回输入框' }).click();
  await page.waitForFunction(() => window.calls.length === 2);
  assert.equal(await page.evaluate(() => window.draftText), '看图');
  assert.deepEqual(await page.evaluate(() => window.draftFiles.map(item => item.name)), ['a.png']);
  assert.match(await page.evaluate(() => window.notices.at(-1)), /含 1 个附件/);

  // 3) 复合场景：分析模式 + 技能名/命令不一致的内置技能（code-review -> /review-code）+ 图片附件同时恢复且不互相冲掉
  await page.evaluate(() => {
    window.draftText = '';
    window.draftFiles = [];
    window.store.setState({
      skills: [{ name: 'code-review', command: '/review-code', description: '代码审阅', source: 'builtin' }],
      ui: { ...window.store.getState().ui, executionMode: 'act' }
    });
    window.setEntries([{
      content: '检查结果', text: '检查结果',
      attachments: [{ name: 'diff.png', mimeType: 'image/png', url: '/api/sessions/s1/attachments/diff.png' }],
      imagePositions: [0], mode: 'plan', skill: 'code-review'
    }]);
  });
  await page.getByRole('button', { name: '放回输入框' }).click();
  await page.waitForFunction(() => window.calls.length === 3);
  assert.equal(await page.evaluate(() => window.store.getState().ui.executionMode), 'plan');
  assert.equal(await page.evaluate(() => window.draftText), '/review-code 检查结果');
  assert.deepEqual(await page.evaluate(() => window.draftFiles.map(item => item.name)), ['diff.png']);
  assert.match(await page.evaluate(() => window.notices.at(-1)), /分析模式/);
  assert.match(await page.evaluate(() => window.notices.at(-1)), /\/review-code/);

  // 4) 附件读取失败：不删除队列，保留原文并提示
  await page.evaluate(() => {
    window.draftText = '保留的草稿';
    window.draftFiles = [];
    window.setEntries([{
      content: '坏的图', text: '坏的图', attachments: [{ name: 'fail.png', mimeType: 'image/png', url: '/fail.png' }],
      imagePositions: [0]
    }]);
  });
  await page.getByRole('button', { name: '放回输入框' }).click();
  await page.waitForFunction(() => window.notices.some(text => /仍在队列中/.test(text)));
  assert.equal(await page.evaluate(() => window.calls.length), 3, '读取失败不应删除队列');
  assert.equal(await page.evaluate(() => window.draftText), '保留的草稿');
  assert.equal(await page.locator('.queue-item').count(), 1);

  assert.deepEqual(errors, []);
  console.log('PASS 取回排队消息：纯文本、附件带回、已有草稿保留、失败保留队列');
} finally {
  await browser?.close();
  await server.close();
}