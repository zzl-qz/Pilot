import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { browserOptions } from './browser-options.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-agent-profiles-'));

const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="icon" href="data:,"><title>Pilot 子代理管理验收</title></head><body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import { UiProvider } from '/src/components/UiProvider.jsx';
import { Modals } from '/src/components/Modals.jsx';
import { CapabilitiesPanel } from '/src/components/inspector/CapabilitiesPanel.jsx';
import { useStore } from '/src/store.js';
import { api } from '/src/api.js';
import '/src/styles.css';
import '/src/shell-vanilla.css';
import '/src/styles-addendum.css';
import '/src/workbench.css';

document.documentElement.dataset.theme = 'dark';
window.store = useStore;
window.calls = [];

const builtinExplorer = {
  name: 'explorer',
  description: '只读代码库探索员',
  tools: ['read_file', 'glob_files'],
  files: ['src/**'],
  denyFiles: ['secrets/**'],
  commands: ['git status'],
  denyCommands: ['rm -rf *'],
  writable: false,
  source: 'builtin',
  content: '---\\ndescription: 只读代码库探索员\\ntools: [read_file, glob_files]\\nwritable: false\\n---\\n只读探索。\\n'
};
const projectGuard = {
  name: 'proj-guard',
  description: '项目级只读守护',
  tools: ['read_file'],
  files: [],
  denyFiles: [],
  commands: [],
  denyCommands: [],
  writable: false,
  source: 'project',
  content: '---\\ndescription: 项目级只读守护\\nwritable: false\\n---\\n项目只读。\\n'
};
const userMap = new Map();

function buildAvailable() {
  const list = [projectGuard];
  if (userMap.has('explorer')) list.push(userMap.get('explorer'));
  else list.push(builtinExplorer);
  for (const [key, value] of userMap.entries()) {
    if (key !== 'explorer') list.push(value);
  }
  return list.sort((a, b) => a.name.localeCompare(b.name));
}

for (const name of Object.keys(api)) if (typeof api[name] === 'function') api[name] = async () => ({});
api.capabilities = async (sessionId) => {
  window.calls.push(['capabilities', sessionId]);
  return { model: 'local-model', permissionMode: 'AUTO', workspace: '/tmp/demo', tools: [], mcpServers: [], localCapabilities: [], plugins: [] };
};
api.agentProfiles = async (sessionId) => {
  window.calls.push(['list', sessionId]);
  return {
    available: buildAvailable(),
    invalid: [{ name: 'broken', file: '/tmp/demo/.pilot/agents/broken.md', reason: '代理定义必须以 --- 开头声明 frontmatter' }],
    projectRoot: '/tmp/demo/.pilot/agents',
    userRoot: '/tmp/pilot-data/agents'
  };
};
api.saveAgentProfile = async (name, content) => {
  window.calls.push(['save', name, content]);
  const descMatch = /description:\\s*(.+)/.exec(content);
  const writable = /writable:\\s*true/.test(content);
  const saved = {
    name,
    description: descMatch ? descMatch[1].trim() : '自定义角色',
    tools: ['read_file'],
    files: [],
    denyFiles: [],
    commands: [],
    denyCommands: [],
    writable,
    source: 'user',
    content,
    hasUserOverride: true
  };
  userMap.set(name, saved);
  return saved;
};
api.deleteAgentProfile = async (name) => {
  window.calls.push(['delete', name]);
  userMap.delete(name);
  return null;
};

useStore.setState({
  sessionId: 'agent-ui-test',
  ui: { ...useStore.getState().ui, settingsOpen: true, settingsSection: 'agents' },
  showNotice: (msg) => window.calls.push(['notice', msg])
});

function Harness() {
  return React.createElement(UiProvider, null,
    React.createElement('div', { style: { display: 'grid', gridTemplateColumns: '1fr 360px', gap: 12, padding: 12 } },
      React.createElement(CapabilitiesPanel),
      React.createElement(Modals)
    )
  );
}
createRoot(document.getElementById('root')).render(React.createElement(Harness));
</script></body></html>`;

const server = await createServer({
  root,
  publicDir: join(root, '../src/main/resources/static'),
  server: { host: '127.0.0.1', port: 0, proxy: {} },
  plugins: [{
    name: 'agent-profiles-fixture',
    configureServer(srv) {
      srv.middlewares.use('/__agents', async (_req, res) => {
        res.setHeader('Content-Type', 'text/html;charset=utf-8');
        res.end(await srv.transformIndexHtml('/__agents', fixture));
      });
    }
  }]
});

await server.listen();
const url = `http://127.0.0.1:${server.httpServer.address().port}/__agents`;
let browser;
const errors = [];

try {
  browser = await chromium.launch(browserOptions({ headless: true }));
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  page.setDefaultTimeout(8000);
  page.on('pageerror', err => errors.push(String(err)));
  await page.goto(url);

  const list = page.locator('#settings-agent-profiles-list');
  await list.getByText('explorer').waitFor();
  assert.equal(await list.getByText('proj-guard').count(), 1);
  assert.equal(await page.locator('.agent-profile-invalid').getByText(/broken/).count(), 1, '展示无效代理校验提示');

  // 1. 查看内置角色只读定义
  const explorerItem = list.locator('li', { hasText: 'explorer' });
  await explorerItem.getByRole('button', { name: '查看定义' }).click();
  await explorerItem.locator('.agent-profile-preview').waitFor();
  assert.match(await explorerItem.locator('.agent-profile-preview').innerText(), /只读探索/);

  // 2. 新建用户级角色：连续输入多字符名称不能锁死，点击保存必须提交真实请求
  await page.locator('#agent-profile-create').click();
  const nameInput = page.locator('#agent-profile-name');
  await nameInput.waitFor();
  await nameInput.pressSequentially('qa-bot');
  assert.equal(await nameInput.isDisabled(), false, '新建模式下输入多个字符时名称输入框不能被锁死');
  assert.equal(await nameInput.inputValue(), 'qa-bot');
  await page.locator('#agent-profile-content').fill('---\ndescription: QA 验收员\ntools: [read_file]\nwritable: false\n---\n负责验收测试。\n');
  await page.locator('#agent-profile-save').click();
  await list.getByText('qa-bot').waitFor();
  await page.screenshot({ path: join(output, 'agent-profiles-dark.png'), animations: 'disabled' });

  // 3. 编辑已有用户级角色：名称输入框必须锁定，内容修改生效
  const qaItem = list.locator('li', { hasText: 'qa-bot' });
  await qaItem.getByRole('button', { name: '编辑' }).click();
  assert.equal(await nameInput.isDisabled(), true, '编辑已有用户级角色时名称必须锁定');
  await page.locator('#agent-profile-content').fill('---\ndescription: QA 终审员\ntools: [read_file]\nwritable: true\n---\n负责终审。\n');
  await page.locator('#agent-profile-save').click();
  await qaItem.getByText('QA 终审员').waitFor();

  // 4. 删除需要二次确认，确认后调用 DELETE 并移出列表
  await qaItem.getByRole('button', { name: '删除' }).click();
  const confirmDialog = page.locator('#confirm-dialog');
  await confirmDialog.waitFor();
  await confirmDialog.locator('#confirm-ok').click();
  await page.waitForFunction(() => !document.querySelector('#settings-agent-profiles-list')?.textContent?.includes('qa-bot'));

  // 5. 基于内置角色创建用户级覆写
  await explorerItem.getByRole('button', { name: '创建用户级覆写' }).click();
  assert.equal(await nameInput.isDisabled(), true, '覆写内置角色时名称保持一致并锁定');
  assert.equal(await nameInput.inputValue(), 'explorer');
  await page.locator('#agent-profile-content').fill('---\ndescription: 自定义探索员\ntools: [read_file]\nwritable: false\n---\n个人探索规则。\n');
  await page.locator('#agent-profile-save').click();
  await list.locator('li', { hasText: 'explorer' }).getByText('用户级').waitFor();

  // 6. 浅色主题截图 + 能力面板展示五类边界且刷新按钮同步拉取子代理
  await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; });
  await page.screenshot({ path: join(output, 'agent-profiles-light.png'), animations: 'disabled' });

  await page.evaluate(() => window.store.getState().setUi({ settingsOpen: false }));
  const cap = page.locator('#panel-capabilities');
  await cap.getByText('子代理角色').waitFor();
  const capText = await cap.innerText();
  for (const marker of ['explorer', 'proj-guard', '工具：', '自定义探索员']) {
    assert.ok(capText.includes(marker), `能力面板缺少 ${marker}`);
  }
  const beforeCalls = await page.evaluate(() => window.calls.length);
  await cap.getByRole('button', { name: '刷新' }).click();
  await page.waitForFunction((n) => window.calls.length >= n + 2, beforeCalls);
  const recentTypes = await page.evaluate((n) => window.calls.slice(n).map(c => c[0]), beforeCalls);
  assert.ok(recentTypes.includes('capabilities') && recentTypes.includes('list'), `刷新应同时拉取能力与子代理：${recentTypes}`);
  assert.deepEqual(errors, []);
  console.log('chromium: PASS 子代理设置页增删改/覆写/校验/二次确认、能力面板展示与联动刷新。Screenshots:', output);
} finally {
  await browser?.close();
  await server.close();
}
