import assert from 'node:assert/strict';
import { build, preview } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import { browserOptions, artifactDirectory } from './browser-options.mjs';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(artifactDirectory(), 'pilot-transcript-polish-'));
const entry = `.transcript-polish-${process.pid}.html`;
const file = join(root, entry);

const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Pilot 对话区零抖动与排版验收</title><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import App from '/src/components/App.jsx';
import { useStore } from '/src/store.js';
import { api } from '/src/api.js';
import { applyEvent, initialThread } from '/src/thread-reducer.js';
import '/src/styles.css';
import '/src/shell-vanilla.css';
import '/src/styles-addendum.css';
import '/src/workbench.css';

window.EventSource = class MockEventSource {
  constructor() { this.readyState = 1; }
  addEventListener() {}
  close() { this.readyState = 2; }
};
document.documentElement.dataset.theme = 'dark';
window.store = useStore;
window.api = api;
window.initialThread = initialThread;
window.applyEvent = applyEvent;
window.metrics = { aroundCalls: 0, searchCalls: 0, runChangesCalls: 0 };

for (const name of Object.keys(api)) if (typeof api[name] === 'function') api[name] = async () => ({});
api.files = async () => ({ entries: [] });
api.results = async () => ({ items: [] });
api.schedules = async () => [];
api.bookmarks = async () => ({ items: [] });
api.worktreeInfo = async () => ({ branch: 'master' });
api.file = async (_s, path) => ({ path, content: 'export const ok = true;\\n', size: 24 });
api.runChanges = async () => { window.metrics.runChangesCalls++; return { files: [] }; };

window.dispatchEventIntoStore = (event, live = true) => {
  useStore.setState(draft => {
    applyEvent(draft, event, { live });
  });
};

window.seedLiveScenario = () => {
  const base = 1760000000000;
  useStore.setState({
    init: async () => {},
    sessionId: 'ses-live',
    sessionTitle: '流式渲染零抖动与历史直达验证',
    workspace: '/workspace/pilot',
    currentProjectId: 'p1',
    historyRevision: 3,
    sessionLoading: false,
    projects: [{ id: 'p1', name: 'pilot', folders: ['/workspace/pilot'] }],
    sessions: [
      { id: 'ses-live', title: '流式渲染零抖动与历史直达验证', projectId: 'p1', workspace: '/workspace/pilot', status: 'IDLE' },
      { id: 'ses-cold', title: '冷切换目标会话', projectId: 'p1', workspace: '/workspace/pilot', status: 'COMPLETED' }
    ],
    running: true,
    cancelling: false,
    pausing: false,
    lastOutcome: '',
    planItems: [
      { step: '修复流式 Markdown 尾块原地修补与语法高亮', status: 'completed' },
      { step: '验证固定停靠带、过程叙述保位与 1 跳锚点直达', status: 'in_progress' }
    ],
    thread: initialThread(),
    connectEvents: () => {},
    loadSessions: async () => [],
    loadTask: async () => {},
    loadQueue: async () => {},
    refreshContext: async () => {},
    loadFiles: async () => {},
    loadWorkspaceChanges: async () => {},
    loadNotes: async () => {},
    loadCommandTasks: async () => {}
  });
  useStore.getState().setUi({ sidebarOpen: false, inspectorOpen: false, searchOpen: false });
  // 先灌入两轮历史以便在 1440x900 下形成充足可滚动高度
  const seedEvents = [
    { sequence: 1, type: 'run.started', runId: 'run_old0', timestamp: new Date(base - 60000).toISOString(), payload: { input: '介绍一下跨平台历史记录和桌面端的兼容策略。' } },
    { sequence: 2, type: 'assistant.delta', runId: 'run_old0', timestamp: new Date(base - 59000).toISOString(), payload: { text: '跨平台策略覆盖 macOS、Windows 与 Linux 三端：\\n\\n- 快捷键修饰键统一检测平台（macOS 使用 Cmd，Windows/Linux 使用 Ctrl），并隔离输入法组合态与 AltGr。\\n- 剪贴板写入优先调用标准 Clipboard API，不可用时回退安全选中复制并明确提示，绝不静默失败。\\n- 文件路径与外部打开全部通过标准化抽象层处理，不硬编码 macOS 专属路径或命令。' } },
    { sequence: 3, type: 'run.completed', runId: 'run_old0', timestamp: new Date(base - 58000).toISOString(), payload: { summary: '兼容策略说明完成', changedFiles: [] } },
    { sequence: 4, type: 'run.started', runId: 'run_prev', timestamp: new Date(base).toISOString(), payload: { input: '先帮我梳理之前历史查询和流式输出容易抖动的根因。' } },
    { sequence: 5, type: 'assistant.delta', runId: 'run_prev', timestamp: new Date(base + 500).toISOString(), payload: { text: '已定位到四个主要抖动源：\\n\\n1. 调工具前把已流出的文本抽走塞进折叠思考，导致整段气泡高度瞬间塌陷。\\n2. 流式 Markdown 每次重建尾部代码块 DOM，横向滚动条与选中状态不断重置。\\n3. 底部「回到底部」按钮条件挂载导致 .thread 视口高度反复跳变 40px。\\n4. 历史分页缺少 Turn 边界对齐与批量改动摘要，回看时多次 N+1 二次撑高。' } },
    { sequence: 6, type: 'run.completed', runId: 'run_prev', timestamp: new Date(base + 1200).toISOString(), payload: { summary: '根因梳理完成', changedFiles: [] } }
  ];
  useStore.setState(draft => {
    for (const ev of seedEvents) applyEvent(draft, ev, { live: false });
    draft.running = true;
    draft.thread.history = { hasMore: false, hasNewer: false, loading: false, loadingNewer: false, total: 120 };
  });
};

window.seedLiveScenario();
createRoot(document.getElementById('root')).render(React.createElement(App));
</script></body></html>`;

await writeFile(file, fixture);
try {
  await build({ root, logLevel: 'silent', build: { outDir: output, emptyOutDir: false, rollupOptions: { input: file } } });
  await cp(join(root, 'public'), output, { recursive: true });
  await cp(join(root, '../src/main/resources/static/fonts'), join(output, 'fonts'), { recursive: true });
} finally {
  await rm(file, { force: true });
}
const server = await preview({ root, preview: { port: 0, host: '127.0.0.1' }, build: { outDir: output } });
const url = `http://127.0.0.1:${server.httpServer.address().port}/${entry}`;
let browser;
let electron;

async function verify(page, name) {
  const errors = [];
  page.on('pageerror', e => errors.push(`pageerror: ${e.message}`));
  page.on('console', m => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(url, { waitUntil: 'networkidle' });
  await page.evaluate(() => seedLiveScenario());
  await page.locator('.conversation-thread').waitFor({ state: 'visible' });

  // 1. 验证：调工具前流式叙述保持 append-only，不发生整段高度塌陷
  const now = 1760000100000;
  await page.evaluate(ts => {
    dispatchEventIntoStore({ sequence: 101, type: 'run.started', runId: 'run_live', timestamp: new Date(ts).toISOString(), payload: { input: '把流式消息区、排版与历史锚点直达全部做到生产级。' } }, true);
    dispatchEventIntoStore({ sequence: 102, type: 'assistant.reasoning', runId: 'run_live', timestamp: new Date(ts + 100).toISOString(), payload: { text: '先检查流式 Markdown 渲染器与历史控制器。' } }, true);
    dispatchEventIntoStore({ sequence: 103, type: 'assistant.delta', runId: 'run_live', timestamp: new Date(ts + 200).toISOString(), payload: { text: '我先读取 `markdown-render.ts` 与 `SessionHistoryController.java`，确认尾块原地修补与 1 跳锚点窗口的契约。' } }, true);
  }, now);
  await page.waitForFunction(() => document.querySelectorAll('.msg-assistant .bubble-md').length >= 3 && document.querySelectorAll('.msg-assistant .bubble-md')[2].textContent.includes('SessionHistoryController.java'));
  const beforeToolHeight = await page.locator('.thread-column').evaluate(el => el.getBoundingClientRect().height);

  await page.evaluate(ts => {
    dispatchEventIntoStore({ sequence: 104, type: 'tool.started', runId: 'run_live', timestamp: new Date(ts + 400).toISOString(), payload: { callId: 'c_read', name: 'read_file', summary: 'path=pilot-web/frontend/src/lib/content/markdown-render.ts' } }, true);
    dispatchEventIntoStore({ sequence: 105, type: 'tool.completed', runId: 'run_live', timestamp: new Date(ts + 430).toISOString(), payload: { callId: 'c_read', resultSummary: '1 | export function patchMarkdownBlock()', latencyMs: 30 } }, true);
  }, now);
  await page.locator('.turn-commentaries .turn-commentary').waitFor({ state: 'visible' });
  const commentaryCheck = await page.evaluate(() => {
    const commentary = document.querySelector('.turn-commentaries .turn-commentary');
    const turn = store.getState().thread.rows.findLast(r => r.type === 'turn');
    return {
      commentaryText: commentary?.textContent || '',
      commentaryHeight: commentary?.getBoundingClientRect().height || 0,
      thinkingText: turn?.thinking?.text || '',
      afterHeight: document.querySelector('.thread-column').getBoundingClientRect().height
    };
  });
  assert.ok(commentaryCheck.commentaryText.includes('SessionHistoryController.java'), '调工具后过程叙述应继续可见在 .turn-commentary 中');
  assert.ok(commentaryCheck.commentaryHeight >= 20, `过程叙述应保持可见高度: ${commentaryCheck.commentaryHeight}`);
  assert.equal(commentaryCheck.thinkingText.includes('SessionHistoryController.java'), false, '过程叙述绝不能被塞进折叠思考');
  assert.ok(beforeToolHeight - commentaryCheck.afterHeight <= 40, `调工具时仅移除空回复头尾壳，不得整段塌陷: before=${beforeToolHeight}, after=${commentaryCheck.afterHeight}`);

  // 2. 验证：流式代码块原地修补（0 次 <figure>/<pre> 重建，0px 横向 scrollLeft 归零）与 .conversation-reply-foot 高度 0 跳动
  const longLinePrefix = 'export function patchMarkdownBlock(existingNode: HTMLElement, nextBlock: MarkdownBlockRecord, streamingOptions: { smoothDrain: boolean; preserveScrollPosition: boolean }) {';
  await page.evaluate(({ ts, line }) => {
    dispatchEventIntoStore({
      sequence: 106, type: 'assistant.delta', runId: 'run_live', timestamp: new Date(ts + 600).toISOString(),
      payload: {
        text: '## 核心重构摘要\n\n> 本次升级聚焦运行区零抖动、编辑级排版与大历史直达，不改变外层工作台布局。\n\n| 维度 | 改造前 | 改造后 |\n| --- | --- | --- |\n| 流式代码块 | 整块替换、横向滚动归零 | 原地修补 `<figure>`、0 次重建 |\n| 远距历史跳转 | 逐页拉取 | `around=` 1 跳直达 + 回到最新 |\n\n```ts\n' + line + '\n  const preserved = existingNode.dataset.codeLang === nextBlock.language;'
      }
    }, true);
  }, { ts: now, line: longLinePrefix });

  await page.waitForFunction(() => {
    const figs = document.querySelectorAll('.msg-assistant figure.md-code');
    const last = figs[figs.length - 1];
    return Boolean(last && last.querySelector('code')?.textContent?.includes('preserved'));
  });

  // 记录初始 DOM 引用、滚动位置与流式占位 foot 高度
  const liveFootHeight = await page.evaluate(() => {
    const figs = document.querySelectorAll('.msg-assistant figure.md-code');
    const fig = figs[figs.length - 1];
    const pre = fig.querySelector('pre');
    window.__streamFig = fig;
    window.__streamPre = pre;
    pre.scrollLeft = 80;
    const foots = document.querySelectorAll('.conversation-reply-foot');
    return foots[foots.length - 1].getBoundingClientRect().height;
  });

  // 连续推送 4 段流式代码行，模拟实时吐字
  const extraChunks = [
    '\n  if (!preserved) return false;',
    '\n  const count = nextBlock.code.split("\\n").length;',
    '\n  return count > 0;',
    '\n}\n```\n\n```diff\n@@ -73,6 +73,7 @@\n-  height: auto;\n+  height: 40px;\n+  min-height: 40px;\n```\n\n全部优化已落地并通过自动化回归测试。'
  ];
  for (let i = 0; i < extraChunks.length; i++) {
    await page.evaluate(({ seq, ts, chunk }) => {
      dispatchEventIntoStore({ sequence: seq, type: 'assistant.delta', runId: 'run_live', timestamp: new Date(ts).toISOString(), payload: { text: chunk } }, true);
    }, { seq: 107 + i, ts: now + 700 + i * 50, chunk: extraChunks[i] });
    await page.waitForTimeout(35);
  }
  await page.waitForFunction(() => document.body.innerText.includes('全部优化已落地并通过自动化回归测试'));

  const streamCheck = await page.evaluate(() => {
    const figs = document.querySelectorAll('.msg-assistant figure.md-code');
    const tsFig = [...figs].find(el => el.dataset.codeLang === 'ts');
    const pre = tsFig?.querySelector('pre');
    const code = tsFig?.querySelector('code');
    return {
      sameFigure: tsFig === window.__streamFig,
      samePre: pre === window.__streamPre,
      scrollLeft: pre?.scrollLeft ?? -1,
      kwCount: code?.querySelectorAll('.tok-kw').length ?? 0,
      hasDiffAdded: Boolean(document.querySelector('figure.md-code pre.is-diff .diff-line.is-add')),
      hasStreamingPill: Boolean(document.querySelector('.conversation-streaming')),
      hasPlaceholderFoot: Boolean(document.querySelector('.conversation-reply-foot.is-placeholder'))
    };
  });
  assert.equal(streamCheck.sameFigure, true, '流式增量期间代码块 <figure> DOM 节点必须原地复用（0 次重建）');
  assert.equal(streamCheck.samePre, true, '流式增量期间代码块 <pre> DOM 节点必须原地复用');
  assert.equal(streamCheck.scrollLeft, 80, '流式追加代码行期间横向滚动条位置必须保持 80px 不归零');
  assert.ok(streamCheck.kwCount >= 3, `TS 代码块应带有语法高亮 token: ${streamCheck.kwCount}`);
  assert.equal(streamCheck.hasDiffAdded, true, 'diff 代码块应高亮新增行');
  assert.equal(streamCheck.hasStreamingPill, true, '流式生成中应显示温和的状态徽标');
  assert.equal(streamCheck.hasPlaceholderFoot, true, '流式生成中应渲染固定高度占位底栏避免结束后跳高');

  // 结束运行，检查 .conversation-reply-foot 高度变化 === 0px
  await page.evaluate(ts => {
    dispatchEventIntoStore({
      sequence: 112, type: 'run.completed', runId: 'run_live', timestamp: new Date(ts + 1500).toISOString(),
      payload: { summary: '已完成全部升级', changedFiles: ['pilot-web/frontend/src/components/Thread.tsx', 'pilot-web/frontend/src/components/conversation.css'] }
    }, true);
    store.setState(draft => {
      draft.running = false;
      const result = draft.thread.rows.findLast(r => r.type === 'result');
      if (result) {
        result.changedFiles = [
          { path: 'pilot-web/frontend/src/components/Thread.tsx', status: 'modified', added: 94, removed: 28 },
          { path: 'pilot-web/frontend/src/components/conversation.css', status: 'modified', added: 162, removed: 41 }
        ];
      }
    });
  }, now);
  await page.locator('.conversation-streaming').waitFor({ state: 'detached' });
  const settledFootHeight = await page.evaluate(() => {
    const foots = document.querySelectorAll('.conversation-reply-foot');
    return foots[foots.length - 1].getBoundingClientRect().height;
  });
  assert.equal(Math.round(settledFootHeight - liveFootHeight), 0, `流式结束时底栏高度变化应为 0px (live=${liveFootHeight}, settled=${settledFootHeight})`);

  // 3. 验证：滚动停靠带切换时 `.thread` 视口高度零跳动（0px）
  const threadHeightAtBottom = await page.locator('.conversation-thread > .thread').evaluate(el => {
    el.scrollTop = el.scrollHeight;
    return el.getBoundingClientRect().height;
  });
  await page.locator('.conversation-thread > .thread').hover();
  await page.mouse.wheel(0, -10000);
  await page.locator('.conversation-latest').waitFor({ state: 'visible' });
  const threadHeightScrolledUp = await page.locator('.conversation-thread > .thread').evaluate(el => el.getBoundingClientRect().height);
  assert.equal(Math.round(threadHeightAtBottom - threadHeightScrolledUp), 0, '回到底部按钮出现时 `.thread` 视口高度跳动必须为 0px');

  // 截取深色/浅色完整编辑级排版桌面图与移动端图（此时内容丰富，含过程叙述、表格、语法高亮代码、diff、结果卡）
  await page.evaluate(() => {
    store.getState().setTheme('dark');
    const tsPre = [...document.querySelectorAll('.msg-assistant figure.md-code')].find(el => el.dataset.codeLang === 'ts')?.querySelector('pre');
    if (tsPre) tsPre.scrollLeft = 0;
  });
  await page.locator('.conversation-thread > .thread').evaluate(el => { el.scrollTop = Math.max(0, el.scrollHeight - el.clientHeight - 280); el.dispatchEvent(new Event('scroll')); });
  await page.waitForTimeout(80);
  if (name === 'chromium') {
    await page.screenshot({ path: join(output, 'showcase-dark-1440.png'), animations: 'disabled' });
    await page.evaluate(() => store.getState().setTheme('light'));
    await page.waitForTimeout(60);
    await page.screenshot({ path: join(output, 'showcase-light-1440.png'), animations: 'disabled' });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('.conversation-thread > .thread').evaluate(el => { el.scrollTop = el.scrollHeight; el.dispatchEvent(new Event('scroll')); });
    await page.waitForTimeout(60);
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), '390px 浅色富内容无横向溢出');
    await page.screenshot({ path: join(output, 'showcase-light-390.png'), animations: 'disabled' });
    await page.evaluate(() => store.getState().setTheme('dark'));
    await page.waitForTimeout(60);
    await page.screenshot({ path: join(output, 'showcase-dark-390.png'), animations: 'disabled' });
    await page.setViewportSize({ width: 1440, height: 900 });
  }

  // 4. 验证：命令面板式即时搜索（免点提交）、关键词 <mark> 高亮、键盘 Enter 1 跳 around= 直达与「回到最新消息」
  await page.evaluate(() => {
    api.searchMessages = async params => {
      window.metrics.searchCalls++;
      return {
        items: [{ sessionId: 'ses-live', title: '流式渲染零抖动与历史直达验证', sequence: 12, anchor: 12, role: 'assistant', text: '远距历史锚点定位：已通过 around 参数 1 跳直达目标消息。', bookmarked: false }],
        hasMore: false,
        nextOffset: 1
      };
    };
    api.session = async id => ({ id, title: '流式渲染零抖动与历史直达验证', workspace: '/workspace/pilot', status: 'IDLE', running: false, historyRevision: 3 });
    api.history = async (_id, params = {}) => {
      if (params.around) {
        window.metrics.aroundCalls++;
        return {
          events: [
            { sequence: 11, type: 'run.started', runId: 'run_anchor', payload: { input: '查询很早之前的历史锚点' } },
            { sequence: 12, type: 'assistant.delta', runId: 'run_anchor', payload: { text: '远距历史锚点定位：已通过 around 参数 1 跳直达目标消息。' } },
            { sequence: 13, type: 'run.completed', runId: 'run_anchor', payload: { summary: 'ok' } }
          ],
          hasMore: true,
          hasNewer: true,
          total: 120,
          historyRevision: 3,
          runChanges: {}
        };
      }
      return {
        events: [
          { sequence: 110, type: 'run.started', runId: 'run_latest', payload: { input: '回到最新尾页' } },
          { sequence: 111, type: 'assistant.delta', runId: 'run_latest', payload: { text: '已回到最新对话流。' } },
          { sequence: 112, type: 'run.completed', runId: 'run_latest', payload: { summary: 'ok' } }
        ],
        hasMore: true,
        hasNewer: false,
        total: 120,
        historyRevision: 3,
        runChanges: {}
      };
    };
    store.getState().setUi({ searchOpen: true });
  });
  await page.locator('#message-search-query').waitFor({ state: 'visible' });
  await page.locator('#message-search-query').fill('锚点');
  // 300ms 防抖自动触发搜索，无需点击提交按钮
  await page.locator('.search-hit-mark').waitFor({ state: 'visible', timeout: 3000 });
  assert.equal(await page.locator('.search-hit-mark').first().textContent(), '锚点', '搜索结果应高亮匹配关键词');
  await page.keyboard.press('Enter');
  await page.locator('.conversation-return-latest').waitFor({ state: 'visible', timeout: 3000 });
  const aroundStats = await page.evaluate(() => ({ ...window.metrics, threadHeight: document.querySelector('.conversation-thread > .thread').getBoundingClientRect().height }));
  assert.equal(aroundStats.aroundCalls, 1, '远距搜索跳转应通过 around= 1 跳直达');

  await page.locator('.conversation-return-latest').click();
  await page.waitForFunction(() => document.body.innerText.includes('已回到最新对话流'));
  assert.equal(await page.locator('.conversation-return-latest').count(), 0);
  const afterReturnHeight = await page.locator('.conversation-thread > .thread').evaluate(el => el.getBoundingClientRect().height);
  assert.equal(Math.round(afterReturnHeight - aroundStats.threadHeight), 0, '「回到最新消息」停靠带切换前后视口高度跳动必须为 0px');

  // 5. 验证：冷切会话展示骨架（0 次闪回空态），切回原会话命中 LRU 快照缓存即显
  await page.evaluate(() => {
    window.__coldResolve = null;
    const coldGate = new Promise(r => { window.__coldResolve = r; });
    api.session = async id => {
      if (id === 'ses-cold') await coldGate;
      return { id, title: id === 'ses-cold' ? '冷切换目标会话' : '流式渲染零抖动与历史直达验证', workspace: '/workspace/pilot', status: 'IDLE', running: false, historyRevision: 3 };
    };
    api.history = async id => {
      if (id === 'ses-cold') await coldGate;
      return {
        events: [
          { sequence: 1, type: 'run.started', runId: 'run_' + id, payload: { input: '来自 ' + id } },
          { sequence: 2, type: 'assistant.delta', runId: 'run_' + id, payload: { text: '内容 ' + id } },
          { sequence: 3, type: 'run.completed', runId: 'run_' + id, payload: { summary: 'ok' } }
        ],
        hasMore: false,
        hasNewer: false,
        total: 3,
        historyRevision: 3,
        runChanges: {}
      };
    };
    void store.getState().activate('ses-cold');
  });
  await page.locator('.thread-skeleton-turn').first().waitFor({ state: 'visible' });
  assert.equal(await page.locator('#empty-state').count(), 0, '冷切换会话期间绝不闪回空态页');
  await page.evaluate(() => window.__coldResolve?.());
  await page.waitForFunction(() => document.body.innerText.includes('内容 ses-cold'));

  // 回切 ses-live：命中缓存立即呈现，无骨架闪烁
  const cachedImmediate = await page.evaluate(() => {
    const p = store.getState().activate('ses-live');
    const loadingDuringSwitch = store.getState().sessionLoading;
    const textImmediately = store.getState().thread.rows.find(r => r.type === 'turn')?.reply?.text || '';
    return p.then(() => ({ loadingDuringSwitch, textImmediately }));
  });
  assert.equal(cachedImmediate.loadingDuringSwitch, false, '命中快照回切不得展示骨架态');
  assert.equal(cachedImmediate.textImmediately, '已回到最新对话流。');

  // 6. 验证：DSH 运行态流式输出期间自动无缝加载更早历史（0 手动按钮 + 并发底部流式增长下锚点元素零抖动 <= 1.5px）
  await page.evaluate(() => {
    const baseTs = 1760000200000;
    const recentEvents = [];
    let seq = 41;
    for (let t = 5; t <= 10; t++) {
      const rid = 'run_dsh_' + t;
      recentEvents.push({ sequence: seq++, type: 'run.started', runId: rid, timestamp: new Date(baseTs + t * 4000).toISOString(), payload: { input: `第 ${t} 轮：检查自动历史预拉与运行期零抖动。` } });
      recentEvents.push({ sequence: seq++, type: 'assistant.delta', runId: rid, timestamp: new Date(baseTs + t * 4000 + 200).toISOString(), payload: { text: `第 ${t} 轮回复正文：这是足够高的多行内容，用于在 1440x900 视口中撑开清晰的历史锚点位置。\n\n- 段落 A：校验元素级锚点补偿不受底部流式追加干扰。\n- 段落 B：校验哨兵预加载不出现手动「加载更早消息」按钮。` } });
      recentEvents.push({ sequence: seq++, type: 'run.completed', runId: rid, timestamp: new Date(baseTs + t * 4000 + 600).toISOString(), payload: { summary: 'ok', changedFiles: [] } });
    }
    // 当前正在运行的新一轮（底部持续流式追加）
    recentEvents.push({ sequence: seq++, type: 'run.started', runId: 'run_dsh_live', timestamp: new Date(baseTs + 50000).toISOString(), payload: { input: '正在流式运行中的第 11 轮任务' } });
    recentEvents.push({ sequence: seq++, type: 'assistant.delta', runId: 'run_dsh_live', timestamp: new Date(baseTs + 50100).toISOString(), payload: { text: '实时输出启动中…\n' } });

    const olderEvents = [];
    let oseq = 1;
    for (let t = 1; t <= 4; t++) {
      const rid = 'run_dsh_' + t;
      olderEvents.push({ sequence: oseq++, type: 'run.started', runId: rid, timestamp: new Date(baseTs + t * 1000).toISOString(), payload: { input: `更早的第 ${t} 轮提问` } });
      olderEvents.push({ sequence: oseq++, type: 'assistant.delta', runId: rid, timestamp: new Date(baseTs + t * 1000 + 200).toISOString(), payload: { text: `更早的第 ${t} 轮历史内容。\n\n多行历史段落用于制造大幅高度前置插入，验证视口完全锁定不跳动。` } });
      olderEvents.push({ sequence: oseq++, type: 'run.completed', runId: rid, timestamp: new Date(baseTs + t * 1000 + 500).toISOString(), payload: { summary: 'ok', changedFiles: [] } });
    }

    window.metrics.earlierCalls = 0;
    window.metrics.lastMinTurns = 0;
    window.__earlierGate = null;
    api.history = async (_id, params = {}) => {
      if (params.before != null) {
        window.metrics.earlierCalls++;
        window.metrics.lastMinTurns = params.minTurns || 0;
        await new Promise(resolve => { window.__earlierGate = resolve; });
        return { events: olderEvents, hasMore: false, hasNewer: false, total: 100, historyRevision: 3, runChanges: {} };
      }
      return { events: recentEvents, hasMore: true, hasNewer: false, total: 100, historyRevision: 3, runChanges: {} };
    };

    store.setState(draft => {
      draft.sessionId = 'ses-live';
      draft.running = true;
      draft.runId = 'run_dsh_live';
      draft.thread = window.initialThread();
      for (const ev of recentEvents) window.applyEvent(draft, ev, { live: false });
      draft.running = true;
      Object.assign(draft.thread.history, { hasMore: true, hasNewer: false, loading: false, error: false, forwardError: false });
    });
  });

  await page.waitForFunction(() => document.querySelectorAll('[data-row-key]').length >= 8);
  assert.equal(await page.locator('button:has-text("加载更早消息")').count(), 0, 'DSH 模式下不得出现手动「加载更早消息」按钮');

  // 将视口先滚到底再滚到顶部哨兵触发区（模拟用户运行期间上翻回看历史），同时底部持续流式追加文本
  await page.evaluate(() => {
    const thread = document.querySelector('.conversation-thread > .thread');
    thread.scrollTop = thread.scrollHeight;
    thread.dispatchEvent(new Event('scroll'));
  });
  await page.waitForTimeout(80);
  await page.evaluate(() => {
    let liveSeq = 70;
    window.__liveTimer = setInterval(() => {
      dispatchEventIntoStore({
        sequence: liveSeq++,
        type: 'assistant.delta',
        runId: 'run_dsh_live',
        timestamp: new Date().toISOString(),
        payload: { text: '\n\n正在底部并发流式输出的增量段落 #' + liveSeq + '，用于验证前置历史插入免疫底部高度增长。' }
      }, true);
    }, 35);
    const thread = document.querySelector('.conversation-thread > .thread');
    thread.scrollTop = 260;
    thread.dispatchEvent(new Event('scroll'));
  });
  await page.waitForFunction(() => typeof window.__earlierGate === 'function', null, { timeout: 4000 });

  // 在前置页落地前记录当前视口内可见的 [data-row-key] 锚点元素屏幕纵坐标
  const anchorBefore = await page.evaluate(() => {
    const thread = document.querySelector('.conversation-thread > .thread');
    const top = thread.getBoundingClientRect().top;
    const rows = [...thread.querySelectorAll('[data-row-key]')];
    const target = rows.find(el => el.getBoundingClientRect().bottom > top + 24 && el.getBoundingClientRect().top < top + 500) || rows[0];
    return { key: target.dataset.rowKey, top: target.getBoundingClientRect().top };
  });
  await page.evaluate(() => window.__earlierGate?.());
  await page.waitForFunction(() => document.body.innerText.includes('更早的第 1 轮历史内容'), null, { timeout: 4000 });
  await page.waitForTimeout(90);

  const dshCheck = await page.evaluate(key => {
    clearInterval(window.__liveTimer);
    const el = document.querySelector(`[data-row-key="${CSS.escape(key)}"]`);
    return {
      afterTop: el ? el.getBoundingClientRect().top : NaN,
      earlierCalls: window.metrics.earlierCalls,
      lastMinTurns: window.metrics.lastMinTurns,
      manualButtonCount: [...document.querySelectorAll('button')].filter(b => b.textContent?.includes('加载更早消息')).length
    };
  }, anchorBefore.key);
  const driftPx = Math.abs(dshCheck.afterTop - anchorBefore.top);
  assert.ok(dshCheck.earlierCalls >= 1, '上翻进入哨兵预加载区应自动触发历史加载');
  assert.equal(dshCheck.lastMinTurns, 3, '历史分页请求应携带 minTurns=3');
  assert.equal(dshCheck.manualButtonCount, 0, '加载前后均不应要求手动点击「加载更早消息」');
  assert.ok(driftPx <= 1.5, `并发底部流式增长期间前置历史加载锚点漂移必须 <= 1.5px，实测 ${driftPx.toFixed(2)}px`);

  // 7. 验证：全局快捷键 Cmd/Ctrl + Shift + F 唤起搜索 + 角色筛选（提问/回复）+ 命中高亮 .is-navigation-target
  const modKey = process.platform === 'darwin' ? 'Meta' : 'Control';
  await page.evaluate(() => {
    window.metrics.searchParams = [];
    store.setState(draft => { draft.running = false; });
    store.getState().setUi({ searchOpen: false });
    api.searchMessages = async params => {
      window.metrics.searchParams.push(params);
      return {
        items: [{ sessionId: 'ses-live', title: '流式渲染零抖动与历史直达验证', sequence: 41, anchor: 41, role: params.role || 'assistant', text: '第 5 轮锚点搜索命中验证。', bookmarked: false }],
        hasMore: false,
        nextOffset: 1
      };
    };
  });
  await page.locator('.conversation-thread > .thread').click();
  await page.keyboard.press(`${modKey}+Shift+F`);
  await page.locator('#message-search-query').waitFor({ state: 'visible', timeout: 3000 });
  await page.locator('#message-search-query').fill('锚点');
  await page.locator('.search-hit-mark').waitFor({ state: 'visible', timeout: 3000 });
  await page.locator('.message-search-roles button:has-text("回复")').click();
  await page.waitForFunction(() => window.metrics.searchParams.some(p => p.role === 'assistant'));
  await page.locator('.message-search-roles button:has-text("提问")').click();
  await page.waitForFunction(() => window.metrics.searchParams.some(p => p.role === 'user'));
  if (name === 'chromium') {
    await page.screenshot({ path: join(output, 'search-role-filter-dark-1440.png'), animations: 'disabled' });
  }
  await page.locator('.search-hit').first().click();
  await page.locator('.is-navigation-target').first().waitFor({ state: 'visible', timeout: 3000 });

  // 8. 验证：Diff 词级高亮 + Unified/Split 分栏切换持久化 + Hunk 按块撤销 + 直接发送修改 + 命令实时尾流与输出过滤
  await page.evaluate(() => {
    window.metrics.discardHunkCalls = [];
    window.metrics.submittedInputs = [];
    window.metrics.stoppedTasks = [];
    const sampleDiff = [
      'diff --git a/src/app.ts b/src/app.ts',
      'index 1234567..89abcde 100644',
      '--- a/src/app.ts',
      '+++ b/src/app.ts',
      '@@ -1,4 +1,4 @@',
      ' export function createConfig() {',
      '-  const retryLimit = 2;',
      '+  const retryLimit = 5;',
      '   return { retryLimit };',
      ' }',
      '@@ -18,4 +18,4 @@',
      ' export function formatTitle(name: string) {',
      '-  return "Draft: " + name;',
      '+  return "Prod: " + name;',
      ' }'
    ].join('\n');
    const report = {
      workspace: '/workspace/pilot',
      gitRepository: true,
      files: [{ status: 'M', path: 'src/app.ts', added: 2, removed: 2 }],
      diff: sampleDiff,
      truncated: false,
      error: null
    };
    api.diff = async () => report;
    api.discardHunk = async (id, path, hunkPatch) => {
      window.metrics.discardHunkCalls.push({ id, path, hunkPatch });
      return { ok: true, error: null };
    };
    api.stopCommand = async (id, taskId) => {
      window.metrics.stoppedTasks.push({ id, taskId });
      return { taskId, status: 'stopped' };
    };
    api.commandLog = async () => ({
      text: '\x1b[32mPASS\x1b[0m suite 1\ncompiling module...\nrunning 42 tests\nfinished step 3\n',
      offset: 0,
      nextOffset: 64
    });
    const origSubmit = store.getState().submitInput;
    store.setState(draft => {
      draft.changeReport = report;
      draft.changesState = 'ready';
      draft.submitInput = async text => {
        window.metrics.submittedInputs.push(text);
        return true;
      };
      draft.loadCommandLog = async () => {};
      // 顺带往最后一条 Turn 注入一个运行中的 run_command 与一个超 20 行输出的完成工具
      draft.running = true;
      draft.commandTasks = [{ taskId: 'cmd-live-1', status: 'running', command: 'npm test' }];
      draft.commandLogTaskId = 'cmd-live-1';
      draft.commandLog = { text: '\x1b[32mPASS\x1b[0m suite 1\ncompiling module...\nrunning 42 tests\nfinished step 3\n', offset: 0, nextOffset: 64 };
      const longLines = Array.from({ length: 28 }, (_, i) => i === 12 ? 'MATCH_TARGET_ERROR line 13' : `normal output line ${i + 1}`).join('\n');
      const liveTurn = draft.thread.rows.findLast(r => r.type === 'turn');
      if (liveTurn) {
        liveTurn.tools = [
          { callId: 'call-long-1', name: 'read_file', args: 'path=src/app.ts', status: 'ok', meta: '18ms', body: longLines, startedAt: Date.now() - 8000, finishedAt: Date.now() - 7500, agentId: null },
          { callId: 'call-cmd-1', name: 'run_command', args: 'command=npm test', status: 'run', meta: '', body: '', startedAt: Date.now() - 6000, agentId: null }
        ];
      }
      void origSubmit;
    });
    store.getState().setUi({ inspectorOpen: true, inspectorTab: 'changes', changeView: 'src/app.ts' });
  });

  await page.locator('#change-diff .diff-word-add').first().waitFor({ state: 'visible', timeout: 3000 });
  assert.ok(await page.locator('#change-diff .diff-word-del').count() >= 2, '删行应渲染词级高亮 <mark class="diff-word-del">');
  assert.ok(await page.locator('#change-diff .diff-word-add').count() >= 2, '增行应渲染词级高亮 <mark class="diff-word-add">');

  // 切换到分栏 Diff 并验证持久化 + 截图验收
  await page.locator('.diff-mode-switch button:has-text("分栏")').click();
  await page.locator('.diff-split-table .diff-split-row').first().waitFor({ state: 'visible' });
  assert.equal(await page.evaluate(() => localStorage.getItem('pilot.diffMode')), 'split', '分栏偏好应持久化到 localStorage');

  if (name === 'chromium') {
    await page.evaluate(() => store.getState().setUi({ reviewExpanded: true }));
    await page.waitForTimeout(80);
    await page.screenshot({ path: join(output, 'diff-split-dark-1440.png'), animations: 'disabled' });
    await page.evaluate(() => store.getState().setTheme('light'));
    await page.waitForTimeout(60);
    await page.screenshot({ path: join(output, 'diff-split-light-1440.png'), animations: 'disabled' });
    await page.evaluate(() => { store.getState().setTheme('dark'); store.getState().setUi({ reviewExpanded: false }); });
    await page.waitForTimeout(60);
  }

  // 选中一行并测试「直接发送修改」
  await page.locator('.diff-split-cell.is-add button.n').first().click();
  await page.locator('.review-feedback textarea').fill('请把重试上限改成 3 次');
  await page.locator('.review-feedback button:has-text("直接发送修改")').click();
  const sentFeedback = await page.evaluate(() => window.metrics.submittedInputs);
  assert.equal(sentFeedback.length, 1);
  assert.ok(sentFeedback[0].includes('src/app.ts') && sentFeedback[0].includes('请把重试上限改成 3 次'), '直接发送修改应携带代码引用与批注');

  // 点击第 2 个 hunk 的「撤销此块」
  await page.locator('.diff-hunk-undo').nth(1).click();
  await page.waitForFunction(() => window.metrics.discardHunkCalls.length === 1);
  const hunkCall = await page.evaluate(() => window.metrics.discardHunkCalls[0]);
  assert.equal(hunkCall.path, 'src/app.ts');
  assert.ok(hunkCall.hunkPatch.startsWith('@@ -18,4 +18,4 @@'), '按块撤销应只提交第 2 个 hunk');
  assert.equal(hunkCall.hunkPatch.includes('retryLimit'), false, '第 2 个 hunk 补丁不应混入第 1 个 hunk');

  // 验证 LiveCommandTail（ANSI 剥离 + 停止命令）与超 20 行工具输出行内过滤
  await page.evaluate(() => store.getState().setUi({ inspectorOpen: false }));
  const lastToolGroup = page.locator('.tool-group').last();
  await lastToolGroup.locator('.tool-group-head').click();
  await lastToolGroup.locator('.tool-live-tail-log').waitFor({ state: 'visible', timeout: 3000 });
  const liveTailText = await lastToolGroup.locator('.tool-live-tail-log').textContent();
  assert.ok(liveTailText.includes('PASS suite 1') && !liveTailText.includes('\x1b['), '实时命令尾流应展示最新日志并剥离 ANSI 码');
  await lastToolGroup.locator('.tool-live-tail button:has-text("停止命令")').click();
  await page.waitForFunction(() => window.metrics.stoppedTasks.length === 1);
  assert.equal(await page.evaluate(() => window.metrics.stoppedTasks[0].taskId), 'cmd-live-1');

  await lastToolGroup.locator('.tool-row .conversation-tool-line').first().click();
  await lastToolGroup.locator('.tool-body-filter-input').waitFor({ state: 'visible' });
  await lastToolGroup.locator('.tool-body-filter-input').fill('MATCH_TARGET_ERROR');
  assert.equal(await lastToolGroup.locator('.tool-body-filter-count').textContent(), '1/28', '输出行内过滤应精确统计 1/28');
  assert.equal(await lastToolGroup.locator('.tool-body pre').textContent(), 'MATCH_TARGET_ERROR line 13');

  if (name === 'chromium') {
    await page.evaluate(() => {
      const thread = document.querySelector('.conversation-thread > .thread');
      if (thread) thread.scrollTop = thread.scrollHeight;
      const list = document.querySelector('.tool-group:last-of-type .tool-group-list');
      if (list) list.scrollTop = list.scrollHeight;
    });
    await page.waitForTimeout(80);
    await page.screenshot({ path: join(output, 'tool-live-tail-dark-1440.png'), animations: 'disabled' });
  }

  assert.deepEqual(errors, []);
  console.log(`${name}: PASS 过程保位(0塌陷)、流式代码块原地修补(0重建/0scrollLeft归零)、0px视口跳动、DSH并发流式自动加载历史(锚点漂移${driftPx.toFixed(2)}px/0手动按钮)、Cmd/Ctrl+Shift+F+角色筛选+锚点高亮、词级/分栏Diff+按块撤销+直接发送修改、实时命令尾流+输出过滤`);
}

try {
  browser = await chromium.launch(browserOptions({ headless: true }));
  await verify(await browser.newPage(), 'chromium');
  await browser.close();
  browser = null;

  if (!process.argv.includes('--web-only')) {
    const main = join(output, 'main.cjs');
    await writeFile(main, `const {app,BrowserWindow}=require('electron');app.whenReady().then(()=>new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}}).loadURL(${JSON.stringify(url)}));`);
    const env = { ...process.env };
    delete env.ELECTRON_RUN_AS_NODE;
    electron = await _electron.launch({ executablePath: electronBinary(), args: [main], env });
    await verify(await electron.firstWindow(), 'electron');
  }
  console.log(`Screenshots: ${output}`);
} finally {
  await browser?.close();
  await electron?.close();
  await new Promise(resolve => server.httpServer.close(resolve));
}
