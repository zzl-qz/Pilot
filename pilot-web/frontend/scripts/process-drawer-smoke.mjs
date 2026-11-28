import assert from 'node:assert/strict';
import { build, preview } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import { browserOptions, artifactDirectory } from './browser-options.mjs';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(artifactDirectory(), 'pilot-process-drawer-'));
const entry = `.process-drawer-${process.pid}.html`;
const file = join(root, entry);

const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Pilot 过程折叠视图</title><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import App from '/src/components/App.jsx';
import { useStore } from '/src/store.js';
import { api } from '/src/api.js';
import '/src/styles.css';

document.documentElement.dataset.theme = 'dark';
window.store = useStore;
for (const name of Object.keys(api)) if (typeof api[name] === 'function') api[name] = async () => ({});
api.files = async () => ({ entries: [] });
api.results = async () => ({ items: [] });
api.schedules = async () => [];
api.worktreeInfo = async () => ({ branch: 'master' });
api.file = async (_s, path) => ({ path, content: 'line 1\\nline 2\\nline 3', size: 24 });

const base = 1760000000000;
const makeLongTools = () => [
  { callId: 'c1', name: 'read_file', args: 'path=docs/tasks.md', status: 'ok', meta: '18 ms', body: '1 | # Tasks\\n… 显示第 1 到第 122 行，文件共 122 行 …', startedAt: base, finishedAt: base + 18 },
  { callId: 'c2', name: 'command_status', args: 'taskId=cmd_41f9aa02 waitSeconds=10', status: 'ok', meta: '240 ms', body: '状态: RUNNING\\n已运行 12s，继续等待核心与服务端测试完成', startedAt: base + 1000, finishedAt: base + 1240 },
  { callId: 'c3', name: 'command_status', args: 'taskId=cmd_41f9aa02 waitSeconds=10', status: 'ok', meta: '320 ms', body: '状态: COMPLETED\\n退出码: 0\\nTests run: 184, Failures: 0', startedAt: base + 12000, finishedAt: base + 12320 },
  { callId: 'c4', name: 'read_file', args: 'path=pilot-server/src/test/java/com/pilot/server/agent/GoalExecutionPolicyTest.java', status: 'ok', meta: '24 ms', body: '355 | @Test void policyAllowsRetry() {\\n… 显示第 355 到第 415 行，文件共 480 行 …', startedAt: base + 14000, finishedAt: base + 14024 },
  { callId: 'c5', name: 'edit_file', args: 'path=pilot-server/src/main/java/com/pilot/server/agent/PreFlightLocator.java', status: 'ok', meta: '31 ms', body: '已修改 pilot-server/src/main/java/com/pilot/server/agent/PreFlightLocator.java：替换 2 处（17 行 → 31 行）', startedAt: base + 20000, finishedAt: base + 20031 },
  { callId: 'c6', name: 'edit_file', args: 'path=pilot-server/src/main/java/com/pilot/server/agent/PreFlightLocator.java', status: 'ok', meta: '28 ms', body: '已修改 pilot-server/src/main/java/com/pilot/server/agent/PreFlightLocator.java：替换 1 处（6 行 → 16 行）', startedAt: base + 35000, finishedAt: base + 35028 },
  { callId: 'c7', name: 'edit_file', args: 'path=pilot-server/src/main/java/com/pilot/server/agent/PreFlightLocator.java', status: 'ok', meta: '22 ms', body: '已修改 pilot-server/src/main/java/com/pilot/server/agent/PreFlightLocator.java：替换 1 处（0 行 → 17 行）', startedAt: base + 48000, finishedAt: base + 48022 },
  { callId: 'c8', name: 'edit_file', args: 'path=pilot-server/src/main/java/com/pilot/server/agent/InvestigationTracker.java', status: 'ok', meta: '19 ms', body: '已修改 pilot-server/src/main/java/com/pilot/server/agent/InvestigationTracker.java：替换 1 处（1 行 → 1 行）', startedAt: base + 55000, finishedAt: base + 55019 },
  { callId: 'c9', name: 'edit_file', args: 'path=pilot-server/src/test/java/com/pilot/server/agent/AgentRunServiceTest.java', status: 'ok', meta: '21 ms', body: '已修改 pilot-server/src/test/java/com/pilot/server/agent/AgentRunServiceTest.java：替换 1 处（0 行 → 4 行）', startedAt: base + 61000, finishedAt: base + 61021 },
  { callId: 'c10', name: 'run_command', args: 'command=./mvnw -pl pilot-core,pilot-server test', status: 'ok', meta: '14200 ms', body: '命令: ./mvnw -pl pilot-core,pilot-server test\\n退出码: 1\\n耗时: 14200 ms\\nSpotless check failed on 1 file', startedAt: base + 65000, finishedAt: base + 79200 },
  { callId: 'c11', name: 'read_file', args: 'path=pom.xml', status: 'ok', meta: '14 ms', body: '1 | <project>\\n… 显示第 1 到第 90 行，文件共 240 行 …', startedAt: base + 81000, finishedAt: base + 81014 },
  { callId: 'c12', name: 'run_command', args: 'command=./mvnw spotless:apply', status: 'ok', meta: '4100 ms', body: '命令: ./mvnw spotless:apply\\n退出码: 0\\n耗时: 4100 ms', startedAt: base + 83000, finishedAt: base + 87100 },
  { callId: 'c13', name: 'search_text', args: 'pattern=PreFlightLocator', status: 'ok', meta: '36 ms', body: '共匹配 9 处（4 个文件）', startedAt: base + 90000, finishedAt: base + 90036 },
  { callId: 'c14', name: 'create_file', args: 'path=pilot-web/frontend/src/components/Thread.tsx', status: 'ok', meta: '29 ms', body: '已创建 pilot-web/frontend/src/components/Thread.tsx（4210 字节，112 行）', startedAt: base + 95000, finishedAt: base + 95029 },
  { callId: 'c15', name: 'read_file', args: 'path=pilot-web/frontend/src/components/conversation.css', status: 'ok', meta: '16 ms', body: '105 | .turn-process {\\n… 显示第 105 到第 220 行，文件共 660 行 …', startedAt: base + 100000, finishedAt: base + 100016 },
  { callId: 'c16', name: 'run_command', args: 'command=npm run check', status: 'ok', meta: '2500 ms', body: '命令: npm run check\\n退出码: 0\\n耗时: 2500 ms\\nbuilt in 2.5s', startedAt: base + 120000, finishedAt: base + 122500 },
  { callId: 'c17', name: 'run_command', args: 'command=git status --short', status: 'ok', meta: '45 ms', body: '命令: git status --short\\n退出码: 0\\nM Thread.tsx', startedAt: base + 1227000, finishedAt: base + 1227045 }
];

window.seedLong = () => {
  const tools = makeLongTools();
  const thinkingLines = Array.from({ length: 18 }, (_, i) => \`第 \${i + 1} 步思考：核对调用链、检查跨平台路径解析与滚动边界表现，确保所有状态切换不抖动。\`).join('\\n');
  useStore.setState({
    init: async () => {},
    sessionId: 'process-fixture',
    sessionTitle: '过程折叠与限高滑动验证',
    workspace: '/workspace/pilot',
    running: false,
    projects: [{ id: 'pilot', name: 'pilot', folders: ['/workspace/pilot'] }],
    sessions: [{ id: 'process-fixture', title: '过程折叠与限高滑动验证', projectId: 'pilot', status: 'COMPLETED' }],
    thread: {
      ...useStore.getState().thread,
      rows: [
        { type: 'user', key: 'u1', sequence: 1, text: '帮我修复测试并把过程列表改成默认收起、限高滑动的样式。', attachments: [], imagePositions: [], start: 1, end: 1 },
        {
          type: 'plan',
          key: 'p1',
          start: 2,
          end: 2,
          items: [
            { step: '定位测试失败原因并修复核心逻辑', status: 'completed' },
            { step: '将执行过程改为默认收起、限高滑动抽屉', status: 'in_progress' },
            { step: '补充明暗主题与三宽度自动化验证', status: 'pending' }
          ]
        },
        {
          type: 'turn',
          key: 't1',
          runIds: ['r1'],
          start: 3,
          end: 20,
          tools,
          toolIndex: Object.fromEntries(tools.map(t => [t.callId, t])),
          thinking: { text: thinkingLines, live: false, action: '', startedAt: base, finishedAt: base + 1227000 },
          reply: {
            text: [
              '## 修复与排版结论',
              '',
              '已完成修复与界面改造：执行过程和思考记录现在默认收起，点开后在固定高度内顺滑滚动，单步详情（如 \`PreFlightLocator.java\` 与 \`Thread.tsx\`）按需展开。',
              '',
              '### 改动要点',
              '',
              '- 默认收起思考与操作流，展开后固定限高并提供上下渐隐',
              '- 调整标题层级、表格与代码块质感，避免正文与标题主次倒挂',
              '',
              '> 所有滚动与焦点调整均只作用于局部容器，不影响整页锚定。',
              '',
              '| 模块 | 变化 | 状态 |',
              '| --- | --- | --- |',
              '| Thread.tsx | 新增过程滚动视口与复制/预览条 | 已通过 |',
              '| conversation.css | 重构过程抽屉与正文排版 | 已通过 |',
              '',
              '\`\`\`ts',
              'export const maxProcessHeight = 320;',
              '\`\`\`'
            ].join('\\n'),
            live: false
          },
          stats: { startedAt: base, finishedAt: base + 1227000, modelRequests: 8, inputTokens: 48200, outputTokens: 2150, contextTokens: 28400, contextLimit: 300000, exact: true },
          agents: {}
        },
        {
          type: 'result',
          key: 'result:r1',
          tone: 'ok',
          title: '执行结束',
          detail: null,
          changedFiles: [
            { path: 'pilot-web/frontend/src/components/Thread.tsx', added: 186, removed: 42 },
            { path: 'pilot-web/frontend/src/components/conversation.css', added: 210, removed: 31 },
            { path: 'pilot-server/src/main/java/com/pilot/server/agent/PreFlightLocator.java', added: 64, removed: 23 }
          ],
          runIds: ['r1'],
          start: 21,
          end: 21
        }
      ]
    }
  });
  useStore.getState().setTheme('dark');
  useStore.getState().setUi({ sidebarOpen: false, inspectorOpen: false });
};

window.seedShort = () => {
  const tools = makeLongTools().slice(0, 2);
  useStore.setState(s => {
    const idx = s.thread.rows.findIndex(r => r.type === 'turn');
    if (idx >= 0) s.thread.rows[idx] = { ...s.thread.rows[idx], tools, toolIndex: Object.fromEntries(tools.map(t => [t.callId, t])) };
  });
};

window.seedEmpty = () => {
  useStore.setState(s => ({
    ...s,
    thread: { ...s.thread, rows: [] }
  }));
};

seedLong();
createRoot(document.getElementById('root')).render(React.createElement(App));
</script></body></html>`;

try {
  await writeFile(file, fixture);
  await build({ root, build: { outDir: output, emptyOutDir: false, rollupOptions: { input: file } } });
} finally {
  await rm(file, { force: true });
}
await cp(join(root, '../src/main/resources/static/fonts'), join(output, 'fonts'), { recursive: true });
const server = await preview({ root, build: { outDir: output }, preview: { host: '127.0.0.1', port: 0, proxy: {} } });
const url = `http://127.0.0.1:${server.httpServer.address().port}/${entry}`;

let browser;
let electron;

async function verify(page, name) {
  page.setDefaultTimeout(8000);
  const errors = [];
  page.on('pageerror', err => errors.push(err.message));
  await page.setViewportSize({ width: 1280, height: 860 });
  await page.goto(url);
  await page.locator('.tool-group.conversation-tools').waitFor();

  // 1. 默认收起：思考块和操作组默认不展开；计划卡正确渲染各状态分级与进度条
  assert.equal(await page.locator('.think-block').getAttribute('open'), null, '思考块默认收起');
  assert.equal(await page.locator('.tool-group.conversation-tools').getAttribute('open'), null, '操作组默认收起');
  assert.equal(await page.locator('.tool-group.conversation-tools .tool-row').count(), 0, '收起时组内操作行完全不挂载');
  assert.equal(await page.locator('.think-body').count(), 0, '收起时思考正文不挂载');
  assert.equal(await page.locator('.plan-card .plan-list').count(), 0, '收起时计划步骤不挂载');
  assert.match(await page.locator('.tool-group-duration').innerText(), /20m 27s/);
  assert.equal(await page.locator('.changed-card .process-kind-icon').count(), 3, '改动文件卡展示对应文件图标');
  if (name === 'chromium') {
    await page.screenshot({ path: join(output, `${name}-collapsed-dark.png`), animations: 'disabled' });
  }
  // 1b. 展开计划卡才构建步骤节点，状态分级与进度条回填正确
  await page.locator('.plan-card summary').click();
  assert.equal(await page.locator('.plan-item.is-completed').count(), 1, '已完成计划项应用状态类');
  assert.equal(await page.locator('.plan-item.is-in_progress').count(), 1, '进行中计划项应用状态类');
  assert.equal(await page.locator('.plan-item.is-pending').count(), 1, '未开始计划项应用状态类');
  await page.locator('.plan-card summary').click();

  // 2. 点击展开操作组 -> 限高可滚动（非无限高度），初始在顶部只有底部渐隐
  await page.locator('.tool-group.conversation-tools > summary').click();
  const list = page.locator('.tool-group.conversation-tools .tool-group-list');
  await list.waitFor({ state: 'visible' });
  assert.ok(await page.locator('.tool-group.conversation-tools .tool-row').count() > 0, '展开后才构建操作行');
  const box = await list.boundingBox();
  assert.ok(box && box.height <= 324, `操作列表限高滚动，实际高度 ${box?.height}`);
  const scrollMetrics = await list.evaluate(el => ({ scrollHeight: el.scrollHeight, clientHeight: el.clientHeight }));
  assert.ok(scrollMetrics.scrollHeight > scrollMetrics.clientHeight + 80, '17 项操作超出限高容器并支持内部滑动');
  assert.equal(await page.locator('.tool-row.is-open').count(), 0, '单步详情默认全部收起');

  const shell = page.locator('.tool-group.conversation-tools .process-scroll-shell');
  await page.waitForFunction(() => document.querySelector('.conversation-tools .process-scroll-shell')?.dataset.fade === 'bottom');
  assert.equal(await shell.getAttribute('data-fade'), 'bottom', '位于顶部时仅显示底部渐隐');

  // 3. 向下滑动到中间 -> 上下双渐隐，并验证悬浮滚动滑块、文件图标、行号、增减徽标与非零退出码
  await shell.scrollIntoViewIfNeeded();
  await list.evaluate(el => { el.scrollTop = 90; });
  await page.waitForFunction(() => document.querySelector('.conversation-tools .process-scroll-shell')?.dataset.fade === 'both');
  assert.equal(await shell.getAttribute('data-fade'), 'both', '滑动到中间时上下均有渐隐提示');
  assert.equal(await shell.getAttribute('data-scrollable'), 'true', '溢出时显示悬浮细胶囊滚动条');
  const thumbBox = await shell.locator('.process-scroll-thumb').boundingBox();
  assert.ok(thumbBox && thumbBox.height >= 28 && thumbBox.width >= 3, '悬浮滚动滑块尺寸可见');
  assert.ok((await page.locator('.process-kind-icon.is-java').count()) >= 5);
  assert.ok((await page.locator('.process-kind-icon.is-markdown').count()) >= 1);
  assert.match(await page.locator('.tool-line-range').first().innerText(), /#L1-122/);
  assert.match(await page.locator('.tool-diff').first().innerText(), /\+31\s*-17/);
  assert.match(await page.locator('.tool-exit-code').first().innerText(), /退出码 1/);
  assert.ok(await page.locator('.tool-args').filter({ hasText: './mvnw -pl pilot-core,pilot-server test' }).count() === 1, '命令保留完整参数而非截断到首词');
  assert.ok(await page.locator('.tool-args').filter({ hasText: /^PreFlightLocator$/ }).count() === 1, '搜索项去掉原始 pattern= 前缀');

  if (name === 'chromium') {
    await page.screenshot({ path: join(output, `${name}-expanded-scroll-dark.png`), animations: 'disabled' });
    await page.locator('.msg-assistant .bubble-md').scrollIntoViewIfNeeded();
    await page.screenshot({ path: join(output, `${name}-markdown-dark.png`), animations: 'disabled' });
    await shell.scrollIntoViewIfNeeded();
  }

  // 3b. 拖拽悬浮滚动滑块向下滚动
  const dragThumb = await shell.locator('.process-scroll-thumb').boundingBox();
  const beforeDragTop = await list.evaluate(el => el.scrollTop);
  await page.mouse.move(dragThumb.x + dragThumb.width / 2, dragThumb.y + dragThumb.height / 2);
  await page.mouse.down();
  await page.mouse.move(dragThumb.x + dragThumb.width / 2, dragThumb.y + dragThumb.height / 2 + 80);
  await page.mouse.up();
  const afterDragTop = await list.evaluate(el => el.scrollTop);
  assert.ok(afterDragTop > beforeDragTop + 20, `拖拽滑块带动列表下滑：${beforeDragTop} → ${afterDragTop}`);

  // 4. 滑到底部 -> 仅顶部渐隐
  await list.evaluate(el => { el.scrollTop = el.scrollHeight; });
  await page.waitForFunction(() => document.querySelector('.conversation-tools .process-scroll-shell')?.dataset.fade === 'top');
  assert.equal(await shell.getAttribute('data-fade'), 'top', '滑到底部时仅显示顶部渐隐');

  // 5. 点击单条操作展开详情 + 预览/复制按钮 + 再次点击收起
  await list.evaluate(el => { el.scrollTop = 100; });
  const editRow = page.locator('.tool-row').filter({ hasText: 'PreFlightLocator.java' }).first();
  await editRow.locator('button.conversation-tool-line').click();
  await editRow.locator('.tool-body').waitFor({ state: 'visible' });
  assert.match(await editRow.locator('.tool-body-target').innerText(), /pilot-server\/src\/main\/java\/com\/pilot\/server\/agent\/PreFlightLocator\.java/);
  // 详情按钮允许随功能扩展追加（如「查看差异」），只校验必需动作仍在。
  const bodyActions = await editRow.locator('.tool-body-action').allInnerTexts();
  for (const required of ['预览文件', '复制']) {
    assert.ok(bodyActions.includes(required), `详情操作按钮包含「${required}」：${bodyActions.join('、')}`);
  }
  if (name === 'chromium') {
    await page.screenshot({ path: join(output, `${name}-row-detail-dark.png`), animations: 'disabled' });
  }
  await editRow.locator('button.conversation-tool-line').click();
  await editRow.locator('.tool-body').waitFor({ state: 'hidden' });

  // 5b. 点击靠近视口底部的行展开详情时，内部滚动区自动微调使详情可见
  await list.evaluate(el => { el.scrollTop = el.scrollHeight; });
  const lastRow = page.locator('.tool-row').last();
  await lastRow.locator('button.conversation-tool-line').click();
  await lastRow.locator('.tool-body').waitFor({ state: 'visible' });
  const bodyVisible = await lastRow.locator('.tool-body').evaluate(el => {
    const view = el.closest('.tool-group-list').getBoundingClientRect();
    const box = el.getBoundingClientRect();
    return box.bottom <= view.bottom + 2 && box.top >= view.top - 2;
  });
  assert.ok(bodyVisible, '底部行展开后详情自动滚入可视区域');
  await lastRow.locator('button.conversation-tool-line').click();

  // 6. 思考块点击展开同样限高可滚动
  await page.locator('.think-block > summary').click();
  const thinkBody = page.locator('.think-body');
  await thinkBody.waitFor({ state: 'visible' });
  const thinkBox = await thinkBody.boundingBox();
  assert.ok(thinkBox && thinkBox.height <= 224, `思考正文限高滚动，实际高度 ${thinkBox?.height}`);
  await page.locator('.think-block > summary').click();

  // 7. 浅色主题 + 三种视口宽度均无横向溢出
  await page.evaluate(() => store.getState().setTheme('light'));
  await list.evaluate(el => { el.scrollTop = 90; });
  await page.waitForFunction(() => document.querySelector('.conversation-tools .process-scroll-shell')?.dataset.fade === 'both');
  if (name === 'chromium') {
    await page.screenshot({ path: join(output, `${name}-expanded-scroll-light.png`), animations: 'disabled' });
  }
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 860 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `${width}px 无横向溢出`);
    if (name === 'chromium' && width === 390) {
      await page.screenshot({ path: join(output, `${name}-mobile-light.png`), animations: 'disabled' });
    }
  }

  // 8. 键盘 Enter 收起 / Space 展开
  await page.setViewportSize({ width: 1280, height: 860 });
  const summary = page.locator('.tool-group.conversation-tools > summary');
  await summary.focus();
  await page.keyboard.press('Enter');
  assert.equal(await page.locator('.tool-group.conversation-tools').getAttribute('open'), null, 'Enter 收起操作组');
  await page.keyboard.press('Space');
  assert.equal(await page.locator('.tool-group.conversation-tools').getAttribute('open'), '', 'Space 展开操作组');

  // 9. 短列表（不溢出）不显示渐隐遮罩和悬浮滚动滑块
  await page.evaluate(() => seedShort());
  await page.waitForFunction(() => document.querySelector('.conversation-tools .process-scroll-shell')?.dataset.fade === 'none');
  assert.equal(await shell.getAttribute('data-fade'), 'none', '短列表不溢出时不显示渐隐');
  assert.equal(await shell.getAttribute('data-scrollable'), 'false', '短列表不溢出时隐藏滚动滑块');

  // 10. 正文 Markdown 排版层级校验（h2 > h3 > 正文、列表圆点与代码块无内嵌灰底）与空态卡片
  const typo = await page.evaluate(() => {
    const body = parseFloat(getComputedStyle(document.querySelector('.msg-assistant .bubble-md')).fontSize);
    const h2 = parseFloat(getComputedStyle(document.querySelector('.msg-assistant .bubble-md h2')).fontSize);
    const h3 = parseFloat(getComputedStyle(document.querySelector('.msg-assistant .bubble-md h3')).fontSize);
    const ulStyle = getComputedStyle(document.querySelector('.msg-assistant .bubble-md ul')).listStyleType;
    const codeBg = getComputedStyle(document.querySelector('.msg-assistant .bubble-md .md-code pre code')).backgroundColor;
    return { body, h2, h3, ulStyle, codeBg };
  });
  assert.ok(typo.h2 > typo.h3 && typo.h3 > typo.body, `Markdown 标题字号层级递增：h2=${typo.h2}, h3=${typo.h3}, body=${typo.body}`);
  assert.equal(typo.ulStyle, 'disc', 'Markdown 无序列表恢复圆点标记');
  assert.equal(typo.codeBg, 'rgba(0, 0, 0, 0)', '代码块内部 code 保持透明无二次套壳');

  await page.evaluate(() => { seedEmpty(); store.getState().setTheme('light'); });
  await page.locator('#empty-state').waitFor({ state: 'visible' });
  assert.equal(await page.locator('.conversation-prompt-copy').count(), 3, '空态快捷卡片采用标题与说明分层结构');
  if (name === 'chromium') {
    await page.screenshot({ path: join(output, `${name}-empty-light.png`), animations: 'disabled' });
    await page.evaluate(() => store.getState().setTheme('dark'));
    await page.screenshot({ path: join(output, `${name}-empty-dark.png`), animations: 'disabled' });
  }
  await page.setViewportSize({ width: 390, height: 860 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), '空态 390px 无横向溢出');

  assert.deepEqual(errors, []);
  console.log(`${name}: PASS 默认收起、限高滚动、上下渐隐、单步详情、文件图标/增减徽标、正文排版、空态卡片与三宽度`);
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
