import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';
import { browserOptions } from './browser-options.mjs';
import { fileURLToPath } from 'node:url';

// 选中文字保存想法：来源记录、预填、来源可用/不可用展示。
const root = fileURLToPath(new URL('..', import.meta.url));
const selectionFixture = `<html><head><link rel="icon" href="data:,"></head><body><div class="thread" id="thread">
<article class="msg msg-user" data-sequence="3"><p>我之前的提问原文</p></article>
<article class="msg msg-assistant" data-sequence="4"><p>建议补一条并发测试，覆盖失败和成功路径。</p></article>
</div><div class="thread" id="thread"><article class="msg msg-assistant" data-sequence="4">建议原文</article></div><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import { SelectionQuote } from '/src/components/SideChat.jsx';
import { useStore } from '/src/store.js';
import '/src/styles.css';
window.store = useStore;
useStore.setState({ embeddedSessionId: null, noteSeed: null });
createRoot(document.getElementById('root')).render(React.createElement(SelectionQuote));
</script></body></html>`;

const notesFixture = `<html><head><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import { SessionNotes } from '/src/components/SessionNotes.jsx';
import { useStore } from '/src/store.js';
import { api } from '/src/api.js';
import '/src/styles.css';
window.store = useStore; window.calls = [];
document.body.insertAdjacentHTML('beforeend', '<div class="thread"><article class="msg msg-assistant" data-sequence="4">建议原文</article></div>');
window.notes = [
  { id: 'n1', content: '可定位的想法', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
    source: { sequence: 4, excerpt: '建议补一条并发测试', role: 'assistant', available: true } },
  { id: 'n2', content: '来源已清理的想法', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
    source: { sequence: 99, excerpt: '旧原文', role: 'user', available: false } }
];
api.createNote = async (session, content, source) => {
  window.calls.push({ session, content, source });
  const note = { id: 'n3', content, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', source: { ...source, available: true } };
  window.notes = [note, ...window.notes];
  return note;
};
useStore.setState({
  sessionId: 's1', notes: window.notes, notesState: 'ready', notesError: '', notesSaving: '',
  noteSeed: { text: '建议补一条并发测试', source: { sequence: 4, excerpt: '建议补一条并发测试', role: 'assistant' } }
});
createRoot(document.getElementById('root')).render(React.createElement(SessionNotes, { onClose: () => {} }));
</script></body></html>`;

const server = await createServer({
  root,
  publicDir: '../src/main/resources/static',
  server: { host: '127.0.0.1', port: 0, hmr: false, proxy: {} },
  plugins: [{
    name: 'note-source-fixture',
    configureServer(dev) {
      for (const [path, html] of [['/__selection', selectionFixture], ['/__notes', notesFixture]]) {
        dev.middlewares.use(path, async (_request, response) => {
          response.setHeader('Content-Type', 'text/html; charset=utf-8');
          response.end(await dev.transformIndexHtml(path, html));
        });
      }
    }
  }]
});
await server.listen();
const base = `http://127.0.0.1:${server.httpServer.address().port}`;
let browser;
try {
browser = await chromium.launch(browserOptions({ headless: true }));
  const page = await browser.newPage({ viewport: { width: 1000, height: 700 } });
  page.setDefaultTimeout(8000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));

  // 选中 AI 回复 → 菜单打开全局想法库并预填选中文字
  await page.goto(`${base}/__selection`);
  await page.locator('[data-sequence="4"] p').waitFor();
  await page.evaluate(() => {
    const range = document.createRange();
    range.selectNodeContents(document.querySelector('[data-sequence="4"] p'));
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  });
  const action = page.getByRole('button', { name: '保存为想法' });
  await action.waitFor();
  await action.click();
  await page.waitForFunction(() => window.store.getState().ui.ideasOpen);
  const seed = await page.evaluate(() => window.store.getState().ui.ideaSeed);
  assert.match(seed.text, /建议补一条并发测试/);

  // 兼容旧会话想法的来源记录
  await page.goto(`${base}/__notes`);
  const input = page.locator('#notes-input');
  await input.waitFor();
  assert.equal(await input.inputValue(), '建议补一条并发测试');
  assert.match(await page.locator('.note-source.is-seed').innerText(), /第 4 条消息/);
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await page.waitForFunction(() => window.calls.length === 1);
  const call = await page.evaluate(() => window.calls[0]);
  assert.equal(call.session, 's1');
  assert.equal(call.content, '建议补一条并发测试');
  assert.deepEqual(call.source, { sequence: 4, excerpt: '建议补一条并发测试', role: 'assistant' });
  assert.equal(await page.evaluate(() => window.store.getState().noteSeed), null, '保存后清掉预填来源');

  // 来源可用条目可定位，来源被清理的条目保留原文并说明
  const chips = page.locator('.notes-list .note-source');
  await page.waitForFunction(() => document.querySelectorAll('.notes-list .note-source').length >= 2);
  assert.match(await chips.first().innerText(), /AI 回复 · 第 4 条/);
  assert.match(await page.locator('.note-source.is-missing').innerText(), /来源不可用（第 99 条已清理）/);

  // 来源可定位：点击后滚到对应消息并高亮
  await page.evaluate(() => {
    window.scrolled = [];
    const original = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = function (...args) { window.scrolled.push(this.getAttribute('data-sequence')); return original.apply(this, args); };
  });
  await chips.nth(1).click();
  await page.waitForFunction(() => window.scrolled?.includes('4'));
  assert.equal(await page.evaluate(() => document.querySelector('[data-sequence="4"]').classList.contains('is-note-source')), true);

  assert.deepEqual(errors, []);
  console.log('PASS 选中文字保存想法：菜单入口、来源记录、预填与来源状态展示');
} finally {
  await browser?.close();
  await server.close();
}