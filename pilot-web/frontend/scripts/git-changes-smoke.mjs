import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { browserOptions } from './browser-options.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-git-changes-'));
const entry = `/__git_changes_${process.pid}`;

const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import { ChangesPanel } from '/src/components/inspector/ChangesPanel.tsx';
import { UiProvider } from '/src/components/UiProvider.jsx';
import { useStore } from '/src/store.js';
import { api } from '/src/api.js';
import '/src/styles.css';
import '/src/styles-addendum.css';
import '/src/workbench.css';

document.documentElement.dataset.theme = 'dark';
window.calls = [];
window.store = useStore;
window.discardShouldFail = false;

const report = {
  gitRepository: true,
  files: [
    { path: 'src/sample.ts', status: ' M', added: 1, removed: 0 },
    { path: 'src/new.ts', status: '??', added: 2, removed: 0 }
  ],
  diff: 'diff --git a/src/sample.ts b/src/sample.ts\\n--- a/src/sample.ts\\n+++ b/src/sample.ts\\n@@ -1 +1 @@\\n-old\\n+new'
};

for (const name of Object.keys(api)) if (typeof api[name] === 'function') api[name] = async () => ({});
api.diff = async () => report;
api.gitBranches = async () => ({ gitRepository: true, current: 'main', branches: ['main', 'feature/demo'], error: null });
api.gitStashes = async () => ({ gitRepository: true, stashes: [{ reference: 'stash@{0}', message: 'local work' }], error: null });
api.gitSwitch = async (_id, branch) => {
  window.calls.push(['switch', branch]);
  return { ok: false, error: '本地改动会与目标分支冲突' };
};
api.gitStashPush = async (_id, message) => {
  window.calls.push(['push', message]);
  return { ok: true, error: null };
};
api.gitStashPop = async (_id, reference) => {
  window.calls.push(['pop', reference]);
  return { ok: true, error: null };
};
api.discardUnstaged = async (_id, path) => {
  window.calls.push(['discard', path]);
  if (window.discardShouldFail) return { ok: false, error: '该文件没有未暂存改动' };
  return { ok: true, error: null };
};
api.restoreChanges = async (_id, body) => {
  window.calls.push(['restore', body]);
  return { restored: [], deleted: body.paths || [], failed: [], error: null };
};
api.file = async () => ({ content: 'export const x = 1;\\n' });

useStore.setState({
  sessionId: 'git-smoke',
  workspace: '/workspace',
  changeReport: report,
  changesState: '',
  running: false,
  runId: null,
  lastRunId: null
});
useStore.getState().setUi({ inspectorOpen: true, inspectorTab: 'changes', reviewRunId: null, changeView: '' });
createRoot(document.getElementById('root')).render(React.createElement(UiProvider, null, React.createElement(ChangesPanel)));
</script></body></html>`;

const server = await createServer({
  root,
  publicDir: join(root, '../src/main/resources/static'),
  server: { host: '127.0.0.1', port: 0, hmr: false, proxy: {} },
  plugins: [{
    name: 'git-changes-fixture',
    configureServer(dev) {
      dev.middlewares.use(entry, async (_request, response) => {
        response.setHeader('Content-Type', 'text/html;charset=utf-8');
        response.end(await dev.transformIndexHtml(entry, fixture));
      });
    }
  }]
});

await server.listen();
const url = `http://127.0.0.1:${server.httpServer.address().port}${entry}`;
let browser;

try {
  browser = await chromium.launch(browserOptions({ headless: true }));
  const page = await browser.newPage({ viewport: { width: 1280, height: 860 } });
  page.setDefaultTimeout(8000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(url);

  // 1. 折叠 Git 工具状态下，DiffView 放弃未暂存失败也能直接看到错误提示
  await page.locator('button.change-row[title="src/sample.ts"]').click();
  await page.evaluate(() => { window.discardShouldFail = true; });
  await page.locator('button.change-view-undo').click();
  await page.getByText('放弃未暂存改动？').waitFor();
  await page.locator('.change-view-bar').getByRole('button', { name: '确认', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: '该文件没有未暂存改动' }).waitFor();

  // 2. DiffView 再次确认成功放弃未暂存改动
  await page.evaluate(() => { window.discardShouldFail = false; });
  await page.locator('.change-view-bar').getByRole('button', { name: '确认', exact: true }).click();
  await page.getByRole('status').filter({ hasText: '已放弃 src/sample.ts 的未暂存改动' }).waitFor();

  // 3. 展开「本地 Git 工具」测试切换分支冲突、stash 保存与弹出
  const tools = page.locator('details').filter({ hasText: '本地 Git 工具' });
  await tools.locator('summary').click();
  await page.getByLabel('切换本地分支').selectOption('feature/demo');
  await page.getByRole('alert').filter({ hasText: '本地改动会与目标分支冲突' }).waitFor();
  await page.getByLabel('stash 说明').fill('checkpoint');
  await page.getByRole('button', { name: '保存 stash' }).click();
  await page.getByRole('button', { name: '弹出' }).click();
  await page.getByRole('status').filter({ hasText: '已弹出 stash@{0}' }).waitFor();
  await page.screenshot({ path: join(output, 'git-changes-dark.png'), animations: 'disabled' });

  // 4. 展开未跟踪文件，验证其撤销仍走 restoreChanges（删除未跟踪文件）而不是 discardUnstaged
  await page.locator('.change-untracked-toggle').click();
  await page.locator('button.change-row[title="src/new.ts"]').hover();
  await page.getByRole('button', { name: '撤销 src/new.ts 的改动' }).click();
  await page.locator('.change-row.is-confirm').getByRole('button', { name: '撤销', exact: true }).click();
  await page.waitForFunction(() => window.calls.some(([action]) => action === 'restore'));

  await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; });
  await page.screenshot({ path: join(output, 'git-changes-light.png'), animations: 'disabled' });

  const calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.map(c => c[0]), ['discard', 'discard', 'switch', 'push', 'pop', 'restore']);
  assert.deepEqual(errors, []);
  console.log('chromium: PASS 单文件放弃未暂存(含失败提示)、未跟踪删除、stash 存取、本地分支切换冲突。Screenshots:', output);
} finally {
  await browser?.close();
  await server.close();
}
