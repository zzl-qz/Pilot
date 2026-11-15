import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 验证结论相关界面的渲染检查。
 *
 * <p>这几处界面（成果卡的验证徽标、能力页的插件分区、「旁边」面板的两个页签）
 * 在提交前只过了 typecheck 与单元测试，**从没被真正渲染过**。
 * 这里用一个只装必要数据的 fixture 页面把它们画出来，断言用户能看到的文字与状态。
 *
 * <p>不连接真实服务、不调用模型：所有接口由 page.route 拦截后返回固定数据。
 */
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-verification-ui-'));

const PRODUCTS = [
  {
    id: 'p_verified', sessionId: 's1', runId: 'r1', title: '已验证的报告', summary: '有证据',
    filename: 'report.md', sourcePath: 'report.md', mediaType: 'text/markdown', bytes: 2048,
    sha256: 'a'.repeat(64), sources: [], createdAt: '2026-11-15T10:00:00Z',
    verification: { status: 'verified', reason: '', code: 'ok', candidateHash: 'abcdef1234567890',
      evidenceId: 'val_1', recordedAt: '2026-11-15T10:00:00Z' }
  },
  {
    id: 'p_stale', sessionId: 's1', runId: 'r1', title: '过期的表格', summary: '改过之后没重验',
    filename: 'sheet.md', sourcePath: 'sheet.md', mediaType: 'text/markdown', bytes: 1024,
    sha256: 'b'.repeat(64), sources: [], createdAt: '2026-11-15T11:00:00Z',
    verification: { status: 'stale', reason: '证据针对的是旧产物，文件已经变化', code: 'stale_candidate',
      candidateHash: 'deadbeefcafe', evidenceId: 'val_2', recordedAt: '2026-11-15T11:00:00Z' }
  }
];

const CAPABILITIES = {
  workspace: '/workspace/pilot', model: 'gpt-6-sol', permissionMode: 'ASK',
  tools: [{ name: 'read_file', description: '读文件', readOnly: true, available: true, reason: '' }],
  mcpServers: [], localCapabilities: [], skills: [],
  plugins: [
    { kind: 'skill', name: 'good', source: 'project', status: 'ok', reason: '与当前核心一致', details: [],
      contracts: ['tool.read_file@1'] },
    { kind: 'hook', name: 'gone_tool → echo x', source: '', status: 'broken',
      reason: 'matcher 指向的工具不存在，这条钩子不会再执行；升级后不会报错，只会静默不生效',
      details: ['matcher「gone_tool」没有命中任何已注册工具'], contracts: [] }
  ],
  pluginCounts: { ok: 1, changed: 0, broken: 1, unknown: 0 },
  pluginReport: '| 类型 | 名称 | 状态 | 说明 |\n| --- | --- | --- | --- |\n| hook | gone_tool | broken | 静默不生效 |\n'
};

const NOTES = [
  { id: 'n1', content: '把登录提示改成可执行建议', createdAt: '2026-11-15T10:00:00Z',
    updatedAt: '2026-11-15T10:00:00Z', status: 'inbox', link: null, source: null }
];

function page(title, script) {
  return `<!doctype html><html lang="zh-CN" data-theme="dark"><head><meta charset="utf-8">
<title>${title}</title><link rel="icon" href="data:,"></head>
<body><div id="root"></div>
<script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import { useStore } from '/src/store.js';
${script}
</script></body></html>`;
}

const fixtures = {
  '/__products': page('products', `
    import WorkProductsPanel from '/src/components/WorkProductsPanel.jsx';
    import '/src/styles.css'; import '/src/styles-addendum.css';
    useStore.setState({ init: async () => {}, sessionId: 's1', running: false, notes: [] });
    createRoot(document.getElementById('root')).render(React.createElement(WorkProductsPanel));`),
  '/__capabilities': page('capabilities', `
    import { CapabilitiesPanel } from '/src/components/inspector/CapabilitiesPanel.jsx';
    import '/src/styles.css'; import '/src/styles-addendum.css';
    useStore.setState({ init: async () => {}, sessionId: 's1', capabilitiesState: 'ready', notes: [] });
    createRoot(document.getElementById('root')).render(React.createElement(CapabilitiesPanel));`),
  '/__beside': page('beside', `
    import { BesidePanel } from '/src/components/BesidePanel.jsx';
    import '/src/styles.css'; import '/src/styles-addendum.css';
    useStore.setState({ init: async () => {}, sessionId: 's1', notesState: 'ready', notes: ${JSON.stringify(NOTES)},
      ui: { ...useStore.getState().ui, besideTab: 'notes', inspectorOpen: true, inspectorTab: 'chat' } });
    createRoot(document.getElementById('root')).render(React.createElement(BesidePanel));`)
};

const server = await createServer({
  root,
  server: { host: '127.0.0.1', port: 0, proxy: {} },
  plugins: [{
    name: 'verification-fixture',
    configureServer(hooks) {
      hooks.middlewares.use((req, res, next) => {
        const path = (req.url || '').split('?')[0];
        if (!fixtures[path]) return next();
        // 必须过 transformIndexHtml：否则内联脚本里的裸导入（react 等）不会被重写，
        // 浏览器直接报 "Failed to resolve module specifier"，页面一片空白。
        hooks.transformIndexHtml(path, fixtures[path]).then(html => {
          res.setHeader('Content-Type', 'text/html; charset=utf-8');
          res.end(html);
        }, next);
      });
    }
  }]
});
await server.listen();
const base = server.resolvedUrls.local[0].replace(/\/$/, '');

const browser = await chromium.launch();
const failures = [];

async function open(name, path, width = 1440) {
  const context = await browser.newContext({ viewport: { width, height: 900 } });
  const view = await context.newPage();
  view.setDefaultTimeout(10000);
  const pageErrors = [];
  view.on('pageerror', error => pageErrors.push(error.message));
  await view.route('**/api/**', route => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/products')) return route.fulfill({ json: PRODUCTS });
    if (url.pathname.endsWith('/skill-drafts')) return route.fulfill({ json: [] });
    if (url.pathname.endsWith('/capabilities')) return route.fulfill({ json: CAPABILITIES });
    if (url.pathname.endsWith('/skills')) return route.fulfill({ json: [] });
    if (url.pathname.endsWith('/notes')) return route.fulfill({ json: NOTES });
    return route.fulfill({ json: {} });
  });
  await view.goto(`${base}${path}`);
  return { view, pageErrors, context };
}

async function check(name, fn) {
  try {
    await fn();
    console.log(`PASS ${name}`);
  } catch (error) {
    failures.push(`${name}: ${error.message}`);
    console.log(`FAIL ${name}: ${error.message}`);
  }
}

// 1. 成果卡的验证徽标：两种状态必须肉眼可分，过期态要给出「重新验证」
await check('成果卡显示验证结论并可重新验证', async () => {
  const { view, pageErrors, context } = await open('products', '/__products');
  await view.getByText('已验证的报告').waitFor();
  const verified = view.locator('.work-verdict[data-tone="ok"]', { hasText: '已验证' });
  const stale = view.locator('.work-verdict[data-tone="stale"]', { hasText: '证据已过期' });
  assert.equal(await verified.count(), 1, '已验证的成果应有一枚绿色徽标');
  assert.equal(await stale.count(), 1, '过期的成果应有独立徽标，不能和未验证同色');
  await view.screenshot({ path: join(output, 'products.png'), fullPage: true });
  await view.getByRole('button', { name: /过期的表格/ }).click();
  await view.getByText('证据针对的是旧产物，文件已经变化').waitFor();
  assert.equal(await view.getByRole('button', { name: '重新验证' }).count(), 1, '过期成果应提供重新验证入口');
  assert.deepEqual(pageErrors, []);
  await context.close();
});

// 2. 能力页：接入总览先报坏掉的，钩子分区列出静默失效
await check('能力页显示插件总览与钩子状态', async () => {
  const { view, pageErrors, context } = await open('capabilities', '/__capabilities');
  await view.getByText('接入总览').waitFor();
  const summary = await view.locator('[data-plugin-summary]').innerText();
  assert.match(summary, /1 项会失效/, `总览应先说坏掉的：${summary}`);
  assert.match(summary, /共 2 项/);
  const hooks = view.locator('.cap-section', { hasText: '钩子' });
  assert.match(await hooks.innerText(), /会失效/);
  assert.match(await hooks.innerText(), /不会报错/);
  await view.screenshot({ path: join(output, 'capabilities.png'), fullPage: true });
  assert.deepEqual(pageErrors, []);
  await context.close();
});

// 3. 「旁边」面板：两个页签在，默认停在「记」，点击可切到「问」
await check('旁边面板两个页签可切换', async () => {
  const { view, pageErrors, context } = await open('beside', '/__beside');
  const notesTab = view.getByRole('tab', { name: '记' });
  const chatTab = view.getByRole('tab', { name: '问' });
  await notesTab.waitFor();
  assert.equal(await notesTab.getAttribute('aria-selected'), 'true', '默认应停在「记」');
  assert.equal(await chatTab.getAttribute('aria-selected'), 'false');
  await chatTab.click();
  assert.equal(await chatTab.getAttribute('aria-selected'), 'true');
  assert.equal(await notesTab.getAttribute('aria-selected'), 'false');
  // 两个页签都保持挂载：切到「问」之后，「记」的内容还在 DOM 里（只是 hidden），草稿不会丢。
  // 注意用 CSS 定位而不是 getByRole：hidden 的元素不在可访问性树里，按角色找不到。
  const notesRegion = view.locator('#notes-menu');
  assert.equal(await notesRegion.count(), 1, '「记」应当仍在挂载状态');
  assert.equal(await notesRegion.isVisible(), false, '切到「问」后「记」应隐藏');
  assert.equal(await notesRegion.locator('textarea#notes-input').count(), 1, '隐藏时输入框仍在 DOM 里');
  // 切回来内容还在
  await notesTab.click();
  assert.equal(await notesRegion.isVisible(), true);
  await view.getByText('把登录提示改成可执行建议').waitFor();
  // 状态徽标必须一直可见：它藏进悬停才出现的按钮组里，上方筛选器就没有对照物了。
  const statusChip = view.locator('.note-status[data-status="inbox"]');
  assert.equal(await statusChip.count(), 1);
  assert.equal(await statusChip.isVisible(), true, '状态徽标不应依赖悬停');
  assert.equal(await statusChip.innerText(), '待安排');
  await view.screenshot({ path: join(output, 'beside.png'), fullPage: true });
  assert.deepEqual(pageErrors, []);
  await context.close();
});

// 4. 窄屏：状态徽标不能被挤出视口
await check('窄屏下成果卡无横向溢出', async () => {
  const { view, context } = await open('products-narrow', '/__products', 390);
  await view.getByText('已验证的报告').waitFor();
  const overflow = await view.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth);
  assert.ok(overflow <= 0, `不应出现横向滚动，实测溢出 ${overflow}px`);
  await view.screenshot({ path: join(output, 'products-narrow.png'), fullPage: true });
  await context.close();
});

await browser.close();
await server.close();

console.log(`\n截图目录：${output}`);
if (failures.length) {
  console.error(`\n${failures.length} 项失败：\n${failures.join('\n')}`);
  process.exit(1);
}
console.log('\n全部通过');