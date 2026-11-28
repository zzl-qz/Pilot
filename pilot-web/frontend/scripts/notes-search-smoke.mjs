import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';
import { browserOptions } from './browser-options.mjs';
import { fileURLToPath } from 'node:url';

// 项目内想法搜索：结果、无结果、点击定位（切换会话 + 高亮想法）。
const root = fileURLToPath(new URL('..', import.meta.url));
const fixture = `<html><head><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import { SessionNotes } from '/src/components/SessionNotes.jsx';
import { useStore } from '/src/store.js';
import { api } from '/src/api.js';
import '/src/styles.css';
window.store = useStore; window.calls = [];
window.notes = [
  { id: 'n1', content: '登录失败路径要覆盖', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', source: null },
  { id: 'n2', content: '登录成功路径也覆盖', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', source: null }
];
api.searchNotes = async (projectId, q) => {
  window.calls.push({ projectId, q });
  if (q === '失败') return { items: [{ sessionId: 's1', sessionTitle: '项目一的一号会话', noteId: 'n1', content: '登录失败路径要覆盖', snippet: '…登录失败路径要覆盖…' }], hasMore: false };
  if (q === '空') return { items: [], hasMore: false };
  if (q === '出错') throw new Error('搜索服务暂不可用');
  return { items: [
    { sessionId: 's1', sessionTitle: '项目一的一号会话', noteId: 'n1', content: '登录失败路径要覆盖', snippet: '…登录失败路径要覆盖…' },
    { sessionId: 's1b', sessionTitle: '项目一的二号会话', noteId: 'n2', content: '登录成功路径也覆盖', snippet: '…登录成功路径也覆盖…' }
  ], hasMore: false };
};
useStore.setState({
  sessionId: 's1', currentProjectId: 'project-a', notes: window.notes, notesState: 'ready', notesError: '', notesSaving: '',
  noteSeed: null, noteFocus: null,
  activate: async id => { window.activated = id; useStore.setState({ sessionId: id }); return true; }
});
createRoot(document.getElementById('root')).render(React.createElement(SessionNotes, { onClose: () => {} }));
</script></body></html>`;

const server = await createServer({
  root,
  publicDir: '../src/main/resources/static',
  server: { host: '127.0.0.1', port: 0, hmr: false, proxy: {} },
  plugins: [{
    name: 'notes-search-fixture',
    configureServer(dev) {
      dev.middlewares.use('/__search', async (_request, response) => {
        response.setHeader('Content-Type', 'text/html; charset=utf-8');
        response.end(await dev.transformIndexHtml('/__search', fixture));
      });
    }
  }]
});
await server.listen();
const url = `http://127.0.0.1:${server.httpServer.address().port}/__search`;
let browser;
try {
browser = await chromium.launch(browserOptions({ headless: true }));
  const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
  page.setDefaultTimeout(8000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(url);
  // 搜索区默认折叠，先点开再等输入框，否则 #notes-search-input 不会渲染。
  await page.locator('.notes-search-toggle').click();
  const input = page.locator('#notes-search-input');
  const searchButton = page.locator('.notes-search-row').getByRole('button', { name: '搜索', exact: true });
  await input.waitFor();

  // 命中：展示会话名与片段
  await input.fill('登录');
  await searchButton.click();
  await page.waitForFunction(() => window.calls.length === 1);
  assert.deepEqual(await page.evaluate(() => window.calls[0]), { projectId: 'project-a', q: '登录' });
  const hits = page.locator('.notes-search-hits li');
  await page.waitForFunction(() => document.querySelectorAll('.notes-search-hits li').length === 2);
  assert.match(await hits.first().innerText(), /项目一的一号会话/);
  assert.match(await hits.first().innerText(), /登录失败路径要覆盖/);

  // 无结果
  await input.fill('空');
  await searchButton.click();
  await page.waitForFunction(() => window.calls.length === 2);
  await page.waitForFunction(() => document.querySelector('.notes-search-hits')?.innerText.includes('没有匹配的想法'));

  // 搜索失败提示
  await input.fill('出错');
  await searchButton.click();
  await page.waitForFunction(() => document.querySelector('.notes-search .notes-status.is-error')?.textContent.includes('搜索服务暂不可用'));

  // 点击结果：切换会话并定位想法
  await input.fill('登录');
  await searchButton.click();
  await page.waitForFunction(() => document.querySelectorAll('.notes-search-hits li').length === 2);
  await page.evaluate(() => {
    window.scrolled = [];
    const original = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = function (...args) { window.scrolled.push(this.getAttribute('data-note-id')); return original.apply(this, args); };
  });
  await hits.nth(1).getByRole('button', { name: '打开那条对话', exact: true }).click();
  await page.waitForFunction(() => window.activated === 's1b');
  await page.waitForFunction(() => window.scrolled?.includes('n2'));
  assert.equal(await page.evaluate(() => document.querySelector('[data-note-id="n2"]').classList.contains('is-note-focus')), true);
  assert.equal(await page.evaluate(() => window.store.getState().noteFocus), null, '定位后清掉目标');

  assert.deepEqual(errors, []);
  console.log('PASS 项目内想法搜索：会话名与片段、无结果、错误提示、点击定位');
} finally {
  await browser?.close();
  await server.close();
}