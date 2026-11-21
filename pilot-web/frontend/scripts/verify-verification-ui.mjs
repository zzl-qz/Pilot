import { browserOptions } from './browser-options.mjs';
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

/** 结论条要展示的是「证据过期」这一档——它最能说明产品在做什么。 */
const STRIP_REPORT = {
  current: false,
  code: 'stale_candidate',
  reason: '最近一次校验针对的是旧产物，代码已变化',
  currentCandidateHash: 'deadbeefcafe1234',
  latest: {
    id: 'val_2', sessionId: 's1', runId: 'r1', candidateHash: 'deadbeefcafe1234',
    files: ['src/login.js', 'src/messages.js'], checks: ['node --test → 通过'],
    status: 'passed', environmentFingerprint: 'win|amd64|jdk25', source: 'run',
    recordedAt: '2026-11-16T02:00:00Z'
  },
  recent: []
};

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
  '/__ideas': page('ideas', `
    import IdeaLibrary from '/src/components/IdeaLibrary.jsx';
    import { NavigationRail } from '/src/components/NavigationRail.jsx';
    import { Inspector } from '/src/components/Inspector.jsx';
    import '/src/styles.css'; import '/src/styles-addendum.css';
    const setUi = useStore.getState().setUi;
    useStore.setState({ sessionId: null, currentProjectId: 'a', notesState: 'ready', notes: ${JSON.stringify(NOTES)} });
    window.switchProject = () => {
      setUi({ inspectorOpen: false });
      useStore.setState({ currentProjectId: 'b', sessionId: 'new-in-b' });
    };
    function IdeasFixture() {
      const open = useStore(state => state.ui.ideasOpen);
      return React.createElement(React.Fragment, null, React.createElement(NavigationRail), React.createElement(Inspector),
        open && React.createElement(IdeaLibrary, { onClose: () => setUi({ ideasOpen: false }) }));
    }
    setUi({ inspectorOpen: true, inspectorTab: 'chat' });
    createRoot(document.getElementById('root')).render(React.createElement(IdeasFixture));`),
  '/__strip': page('strip', `
    import { VerificationStrip } from '/src/components/VerificationStrip.jsx';
    import '/src/styles.css'; import '/src/styles-addendum.css';
    const report = ${JSON.stringify(STRIP_REPORT)};
    useStore.setState({ init: async () => {}, sessionId: 's1', hasRun: true, notes: [],
      submitInput: async () => true, validation: { report, recent: report.latest ? [report.latest] : [] },
      // 运行中的 fixture 要带上能凑出「正在执行 · …」的条件，否则下面的断言分不清是删掉了还是本来就不画。
      runStartedAt: Date.now() - 60000,
      thread: { rows: [{ type: 'turn', tools: [{ status: 'run', name: 'run_command', args: { command: 'node scripts/check.mjs' } }] }] } });
    window.setRunning = (running) => useStore.setState({ running });
    createRoot(document.getElementById('root')).render(React.createElement(VerificationStrip));`),
  '/__task': page('task', `
    import { TaskContract } from '/src/components/Modals.jsx';
    import '/src/styles.css'; import '/src/styles-addendum.css';
    useStore.setState({ init: async () => {}, sessionId: 's1', running: false, notes: [],
      task: {
        taskId: 'task_1', originalGoal: '修复登录报错并补测试', constraints: [], corrections: [],
        acceptance: [{ text: '测试通过', checked: false, evidence: '之前跑过' }],
        status: 'AWAITING_REVIEW',
        execution: { mode: 'GOAL', phase: 'CHECKS_STALE', requests: 12,
          reason: '代码在这之后又改过', criteria: [], gap: '缺少当前版本的测试证据' }
      } });
    createRoot(document.getElementById('root')).render(React.createElement(TaskContract));`)
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

const browser = await chromium.launch(browserOptions());
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
  // 面板先渲染标题、能力清单随后到达；等汇总变成「有坏的」再读，否则读到的是空态文案
  const summaryBox = view.locator('[data-plugin-summary="broken"]');
  await summaryBox.waitFor();
  const summary = await summaryBox.innerText();
  assert.match(summary, /1 项会失效/, `总览应先说坏掉的：${summary}`);
  assert.match(summary, /共 2 项/);
  const hooks = view.locator('.cap-section', { hasText: '钩子' });
  assert.match(await hooks.innerText(), /会失效/);
  assert.match(await hooks.innerText(), /不会报错/);
  await view.screenshot({ path: join(output, 'capabilities.png'), fullPage: true });
  assert.deepEqual(pageErrors, []);
  await context.close();
});

await check('想法库独立于侧边对话，跨项目保存文字与附件', async () => {
  const { view, pageErrors, context } = await open('ideas', '/__ideas');
  const dialog = view.getByRole('dialog', { name: '想法库' });
  const trigger = view.locator('#nav-ideas');
  await view.getByRole('tab', { name: '侧边对话' }).waitFor();
  assert.equal(await view.locator('#panel-side-chat #idea-library').count(), 0);
  await trigger.click();
  await dialog.getByLabel('想法文字').fill('之后再做的想法');
  await dialog.getByLabel('想法附件').setInputFiles({ name: '记录.txt', mimeType: 'text/plain', buffer: Buffer.from('附件内容') });
  // 待保存附件：卡片上的 × 只移除那一个文件，不碰已输入的文字
  await dialog.getByLabel('想法附件').setInputFiles({ name: '临时.txt', mimeType: 'text/plain', buffer: Buffer.from('先加上再拿掉') });
  const pending = dialog.getByRole('list', { name: '待保存附件' });
  assert.equal(await pending.getByRole('listitem').count(), 2, '两个文件应各占一张卡片');
  await pending.getByRole('button', { name: '移除 临时.txt' }).click();
  assert.equal(await pending.getByRole('listitem').count(), 1, '× 应只移除被点的那个附件');
  assert.equal(await pending.getByText('记录.txt').count(), 1, '没点到的附件要留下');
  assert.equal(await dialog.getByLabel('想法文字').inputValue(), '之后再做的想法', '移除附件不能清空文字');
  await dialog.getByRole('button', { name: '保存想法' }).click();
  await dialog.getByText('已保存到想法库。').waitFor();
  await dialog.getByLabel('想法文字').evaluate(input => {
    const png = atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9T0AAAAASUVORK5CYII=');
    const transfer = new DataTransfer();
    transfer.items.add(new File([Uint8Array.from(png, byte => byte.charCodeAt(0))], '截图.png', { type: 'image/png' }));
    input.dispatchEvent(new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData: transfer }));
  });
  await dialog.getByRole('button', { name: '保存想法' }).click();
  await dialog.getByRole('list', { name: '已保存的想法' }).getByRole('img', { name: '截图.png' }).waitFor();
  // 缩略图点开大图，关闭后回到弹窗本身，不影响外层想法库
  await dialog.getByRole('button', { name: '查看 截图.png' }).click();
  const preview = dialog.getByRole('dialog', { name: '截图.png 预览' });
  await preview.waitFor();
  await dialog.getByRole('button', { name: '关闭预览' }).click();
  await preview.waitFor({ state: 'detached' });
  assert.equal(await dialog.getByLabel('想法文字').isVisible(), true, '关闭大图后想法库仍在');
  await dialog.getByText('查看当前会话的旧想法').click();
  await dialog.getByRole('button', { name: '复制到想法库' }).click();
  await dialog.getByText('已复制到想法库。').waitFor();
  await view.reload();
  await trigger.click();
  await dialog.getByText('之后再做的想法').waitFor();
  await dialog.getByText('把登录提示改成可执行建议').waitFor();
  await dialog.getByRole('img', { name: '截图.png' }).waitFor();
  await view.evaluate(() => window.switchProject());
  const download = view.waitForEvent('download');
  await dialog.getByRole('link', { name: '记录.txt' }).click();
  assert.equal((await download).suggestedFilename(), '记录.txt');
  const row = dialog.locator('.idea-card').filter({ hasText: '之后再做的想法' });
  await row.getByRole('button', { name: '删除想法' }).click();
  await row.getByRole('button', { name: '确认删除' }).click();
  await row.waitFor({ state: 'detached' });
  assert.deepEqual(pageErrors, []);
  await context.close();
});

// 4. 任务卡：过期的检查证据要与「未验证」分开，并提供重新验证
await check('任务卡把过期证据单独标出并可重新验证', async () => {
  const { view, pageErrors, context } = await open('task', '/__task');
  await view.getByText('任务契约').waitFor();
  await view.locator('#task-contract-summary').click();
  const block = view.locator('.task-verification');
  await block.waitFor();
  assert.equal(await block.getAttribute('data-tone'), 'stale', 'CHECKS_STALE 应落成 stale 档位');
  assert.equal(await block.getAttribute('data-verification-phase'), 'CHECKS_STALE');
  assert.match(await block.innerText(), /验证依据已变化/);
  assert.equal(await block.getByRole('button', { name: '重新验证' }).count(), 1);
  // 验收条件仍在，勾选状态没被界面擅自改动
  assert.equal(await view.getByText('之前跑过').count(), 1, '用户写的证据要保留可见');
  await view.screenshot({ path: join(output, 'task.png'), fullPage: true });
  assert.deepEqual(pageErrors, []);
  await context.close();
});

// 5. 输入框上方的结论条：主界面上能直接看到「证据已过期」与下一步动作
await check('主界面结论条显示过期状态并可展开依据', async () => {
  const { view, pageErrors, context } = await open('strip', '/__strip');
  const strip = view.locator('#verification-strip');
  try {
    await strip.waitFor();
  } catch (error) {
    // 页面是空的还是报错了，比「等不到元素」有用得多
    console.log('[debug] pageErrors:', pageErrors);
    console.log('[debug] body:', (await view.locator('body').innerText()).slice(0, 300));
    throw error;
  }
  assert.equal(await strip.getAttribute('data-tone'), 'stale');
  assert.match(await strip.innerText(), /证据已过期/);
  assert.match(await strip.innerText(), /旧产物/);
  assert.equal(await strip.getByRole('button', { name: '重新验证' }).count(), 1);
  await strip.getByRole('button', { name: '看依据' }).click();
  await strip.getByText(/node --test/).waitFor();
  await view.screenshot({ path: join(output, 'strip.png'), fullPage: true });
  // 这一轮跑起来时整条收起：当前动作与耗时由顶栏的运行状态负责，不再重复占一行。
  await view.evaluate(() => window.setRunning(true));
  await strip.waitFor({ state: 'detached' });
  await view.screenshot({ path: join(output, 'strip-running.png'), fullPage: true });
  assert.deepEqual(pageErrors, []);
  await context.close();
});

// 6. 窄屏：状态徽标不能被挤出视口
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