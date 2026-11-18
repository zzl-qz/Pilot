/**
 * 轮次停住后：工具不能再转圈计时，组头不能写「正在执行」，改动卡要是统一样式。
 */
import { createServer } from 'vite';
import { chromium } from 'playwright-core';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = fileURLToPath(new URL('..', import.meta.url));
const chromeCandidates = [
  process.env.CHROME,
  join(root, '../../.pilot/browser/browsers/chromium-1248/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing'),
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
].filter(Boolean);

const fixture = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import { useStore } from '/src/store.js';
import { Thread } from '/src/components/Thread.jsx';
import '/src/styles.css';
import '/src/shell-vanilla.css';
import '/src/styles-addendum.css';
import '/src/workbench.css';
import '/src/components/conversation.css';
document.documentElement.dataset.theme = 'dark';
window.store = useStore;
window.bootError = null;
window.addEventListener('error', (event) => { window.bootError = String(event.error || event.message || event); });
useStore.setState({
  init: async () => {},
  running: false,
  runId: null,
  sessionId: 's1',
  workspace: '/tmp',
  projects: [],
  ready: true,
  thread: {
    rows: [],
    history: { earlier: false, later: false, loadingEarlier: false, loadingLater: false },
    windowEvents: [],
    cursor: 0,
  },
  ui: { notice: null, bottomSignal: 0, sidebarOpen: false, inspectorOpen: false },
  requestEarlierHistory: async () => {},
  requestLaterHistory: async () => {},
  prefetchEarlier: async () => {},
  applyEarlierPage: () => {},
  rollbackRun: async () => {},
  showNotice: () => {},
});
try {
  createRoot(document.getElementById('root')).render(React.createElement(Thread));
} catch (error) {
  window.bootError = String(error);
}
</script></body></html>`;

const server = await createServer({
  root,
  server: { host: '127.0.0.1', port: 0, proxy: {} },
  plugins: [{
    name: 'hang-fixture',
    configureServer(dev) {
      dev.middlewares.use('/__hang', async (_req, res) => {
        res.setHeader('Content-Type', 'text/html');
        res.end(await dev.transformIndexHtml('/__hang', fixture));
      });
    },
  }],
});
await server.listen();
const port = server.httpServer.address().port;
const url = `http://127.0.0.1:${port}/__hang`;

let executablePath;
for (const candidate of chromeCandidates) {
  try {
    const { access } = await import('node:fs/promises');
    await access(candidate);
    executablePath = candidate;
    break;
  } catch {
    // 试下一个
  }
}

const browser = await chromium.launch({
  headless: true,
  executablePath,
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
});
const page = await browser.newPage();
page.setDefaultTimeout(15000);
const errors = [];
page.on('pageerror', (error) => errors.push(String(error)));

await page.goto(url);
try {
  await page.waitForFunction(() => window.store?.getState, { timeout: 15000 });
} catch (error) {
  const detail = await page.evaluate(() => ({
    bootError: window.bootError || null,
    hasStore: typeof window.store,
    body: document.body?.innerText?.slice(0, 500) || '',
  })).catch(() => ({}));
  throw new Error(`store 未挂上: ${JSON.stringify(detail)} / ${error}`);
}

await page.evaluate(() => {
  const startedAt = Date.now() - 28 * 60 * 1000;
  window.store.setState((draft) => {
    draft.running = false;
    draft.thread.rows = [
      {
        type: 'turn',
        key: 'turn:hang',
        tools: [
          {
            callId: 'c1',
            name: 'command_status',
            args: 'taskId=cmd_16162ef8abcd waitSeconds=5',
            status: 'run',
            meta: '',
            body: '',
            startedAt,
          },
          {
            callId: 'c2',
            name: 'browser',
            args: 'click',
            status: 'run',
            meta: '',
            body: '',
            startedAt: startedAt + 10_000,
          },
        ],
        toolIndex: {},
        thinking: null,
        reply: {
          text: '这一轮停住了，可以接着跑',
          live: false,
          preview: '',
          needsBreak: false,
          breakReason: '',
          committed: '',
        },
        stats: null,
        agents: {},
        runIds: ['run_hang'],
      },
      {
        type: 'result',
        key: 'result:run_hang',
        tone: 'warn',
        title: '还有步骤没做完',
        detail: '计划仍有未完成项',
        changedFiles: [{ path: 'Thread.tsx', added: 9, removed: 5, diff: 'diff --git a/Thread.tsx\n@@\n-a\n+b\n' }],
        runIds: ['run_hang'],
        resumable: true,
      },
    ];
  });
});

await page.waitForSelector('.tool-group-label');
await page.waitForTimeout(400);

const ui = await page.evaluate(() => ({
  label: document.querySelector('.tool-group-label')?.textContent || '',
  attention: document.querySelector('.conversation-tool-attention')?.textContent || '',
  names: [...document.querySelectorAll('.conversation-tool-name')].map((el) => el.textContent.trim()),
  args: [...document.querySelectorAll('.tool-args')].map((el) => el.textContent.trim()),
  counts: document.querySelector('.tool-group-count')?.textContent || '',
  states: [...document.querySelectorAll('.conversation-tool-state')].map((el) => ({
    text: el.textContent.trim(),
    cls: el.className,
  })),
  result: document.querySelector('.turn-card-title')?.textContent || '',
  change: (document.querySelector('.changed-card')?.textContent || '').replace(/\s+/g, ' ').trim(),
  clocks: [...document.querySelectorAll('.conversation-tool-state')]
    .map((el) => el.textContent.trim())
    .filter((text) => /\d+:\d+/.test(text)),
  runningBanner: [...document.querySelectorAll('.tool-group-label, .conversation-tool-attention')]
    .map((el) => el.textContent || '')
    .filter((text) => /正在执行/.test(text)),
}));

assert.equal(errors.length, 0, errors.join('\n'));
assert.match(ui.result, /还有步骤没做完/);
assert.match(ui.change, /已编辑|查看变更|撤销/);
assert.doesNotMatch(ui.label, /正在执行/);
assert.equal(ui.runningBanner.length, 0, JSON.stringify(ui));
assert.equal(ui.clocks.length, 0, JSON.stringify(ui));
assert.ok(ui.states.length >= 2, JSON.stringify(ui));
assert.ok(
  ui.states.every((state) => state.cls.includes('interrupted') || /中断/.test(state.text)),
  JSON.stringify(ui.states),
);
assert.match(ui.attention, /需留意/);
assert.deepEqual(ui.names, ['查命令', '浏览器']);
assert.match(ui.args.join(' '), /最多等 5 秒/);
assert.match(ui.args.join(' '), /点击/);
assert.doesNotMatch(ui.args.join(' '), /waitSeconds|taskId=/);
assert.match(ui.counts, /查命令/);
assert.doesNotMatch(ui.counts, /command_status/);

console.log(JSON.stringify({ ok: true, ui }, null, 2));
await browser.close();
await server.close();
