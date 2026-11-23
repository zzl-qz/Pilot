import assert from 'node:assert/strict';
import { build, preview } from 'vite';
import { chromium } from 'playwright-core';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { browserOptions } from './browser-options.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-alerts-ui-'));
const entry = `.alerts-ui-${process.pid}.html`;
const entryPath = join(root, entry);

const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import App from '/src/components/App.jsx';
import { useStore } from '/src/store.js';
import { api, ApiError } from '/src/api.js';
import { applyEvent } from '/src/thread-reducer.js';
import '/src/styles.css';
import '/src/shell-vanilla.css';
import '/src/styles-addendum.css';
import '/src/workbench.css';

document.documentElement.dataset.theme = 'dark';
window.store = useStore;
window.api = api;
window.ApiError = ApiError;
window.applyEvent = applyEvent;
window.calls = [];
window.failQuestion = false;
window.questionErrorStatus = 500;

for (const name of Object.keys(api)) {
  if (typeof api[name] === 'function') {
    api[name] = async (...args) => {
      window.calls.push({ name, args });
      return {};
    };
  }
}

const sessions = [
  { id: 's1', title: '当前主会话', workspace: '/tmp/ws1', status: 'running', archived: false, isBlank: false },
  { id: 's2', title: '后台发布会话', workspace: '/tmp/ws2', status: 'waiting_approval', archived: false, isBlank: false }
];

api.health = async () => ({ status: 'ok' });
api.projects = async () => [];
api.settings = async () => ({
  attachmentLimits: { maxFiles: 64, maxFileBytes: 2097152, maxTotalBytes: 26214400, maxRequestBytes: 37000000 }
});
api.sessions = async () => structuredClone(sessions);
api.session = async (id) => sessions.find((s) => s.id === id) || sessions[0];
api.history = async () => ({ events: [], hasMore: false, historyRevision: 1 });
api.plan = async () => ({ plan: [] });
api.task = async () => ({ constraints: [], acceptance: [] });
api.queue = async () => ({ items: [] });
api.context = async () => ({ usedTokens: 100, limitTokens: 1000, percent: 10 });
api.capabilities = async () => ({ mcpServers: [] });
api.validation = async () => null;
api.workProducts = async () => [];
api.skillDrafts = async () => [];
api.skills = async () => ({ skills: [] });
api.pendingInteractions = async () => ({ approvals: [], questions: [] });
api.deliverySettings = async () => ({ version: 1, desktop: false, webhookUrl: '' });
api.answerQuestion = async (sessionId, callId, choice) => {
  window.calls.push({ name: 'answerQuestion', args: [sessionId, callId, choice] });
  if (window.failQuestion) {
    throw new ApiError('提交失败测试', window.questionErrorStatus);
  }
  return { ok: true };
};
api.approval = async (callId, approved, reason) => {
  window.calls.push({ name: 'approval', args: reason ? [callId, approved, reason] : [callId, approved] });
  return { ok: true };
};
api.permissionRules = async () => ({ allow: ['read_file'], deny: [], ask: [], loadIssues: [] });
api.savePermissionRules = async (body) => {
  window.calls.push({ name: 'savePermissionRules', args: [body] });
  return body;
};

window.FakeEventSource = class {
  listeners = new Map();
  readyState = 1;
  constructor(url) { this.url = url; }
  addEventListener(type, fn) { this.listeners.set(type, fn); }
  close() { this.readyState = 2; }
};
window.EventSource = window.FakeEventSource;

createRoot(document.getElementById('root')).render(React.createElement(App));
</script></body></html>`;

try {
  await writeFile(entryPath, fixture);
  await build({ root, build: { outDir: output, emptyOutDir: false, rollupOptions: { input: entryPath } } });
} finally {
  await rm(entryPath, { force: true });
}
await cp(join(root, '../src/main/resources/static/fonts'), join(output, 'fonts'), { recursive: true });
const server = await preview({ root, build: { outDir: output }, preview: { host: '127.0.0.1', port: 0, proxy: {} } });
const url = `http://127.0.0.1:${server.httpServer.address().port}/${entry}`;

let browser;
try {
  browser = await chromium.launch(browserOptions({ headless: true }));
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('pageerror', (err) => errors.push(err.message));
  page.setDefaultTimeout(8000);
  await page.goto(url);
  await page.waitForFunction(() => Boolean(window.store?.getState?.().ready));

  // 1. QuestionDock: 错误重试保留用户输入 + 输入法 Enter 拦截 + Alt+1 快捷提交 + 409 过期清理
  await page.evaluate(() => {
    window.store.setState({
      pendingQuestions: {
        q1: {
          callId: 'q1',
          question: '选择发布策略？',
          options: ['滚动发布', '蓝绿部署'],
          requestedAt: Date.now(),
          expiresAt: Date.now() + 25_000
        }
      }
    });
  });

  const qDock = page.locator('#question-dock');
  await qDock.waitFor();
  assert.match(await qDock.innerText(), /选择发布策略/);
  await qDock.locator('.approval-countdown.is-urgent').waitFor();

  const customInput = qDock.getByLabel('自己填写答案');
  await customInput.fill('灰度发布');
  // 模拟输入法候选阶段按 Enter（keyCode 229）：不能误提交
  await customInput.dispatchEvent('keydown', { key: 'Enter', keyCode: 229 });
  assert.equal(await page.evaluate(() => window.calls.filter((c) => c.name === 'answerQuestion').length), 0);

  // 触发失败后保留自定义输入并显示错误提示
  await page.evaluate(() => { window.failQuestion = true; window.questionErrorStatus = 500; });
  await qDock.getByRole('button', { name: '提交', exact: true }).click();
  await qDock.getByRole('alert').filter({ hasText: /提交失败测试/ }).waitFor();
  assert.equal(await customInput.inputValue(), '灰度发布');

  // Alt+1 从输入框外部快捷选择第一项并自动关闭卡片
  await page.evaluate(() => {
    window.failQuestion = false;
    document.body.focus();
    (document.activeElement)?.blur?.();
  });
  await page.keyboard.press('Alt+1');
  await qDock.waitFor({ state: 'hidden' });
  assert.deepEqual(
    await page.evaluate(() => window.calls.filter((c) => c.name === 'answerQuestion').at(-1)?.args),
    ['s1', 'q1', '滚动发布']
  );

  // macOS Option+1 会将 event.key 变成 '¡'，必须回退读取 event.code === 'Digit1'
  await page.evaluate(() => {
    window.store.setState({
      pendingQuestions: {
        q1_mac: { callId: 'q1_mac', question: 'macOS Option+1 测试？', options: ['方案A', '方案B'], requestedAt: Date.now() }
      }
    });
  });
  await qDock.waitFor();
  await page.evaluate(() => {
    window.dispatchEvent(new KeyboardEvent('keydown', { key: '¡', code: 'Digit1', altKey: true, bubbles: true, cancelable: true }));
  });
  await qDock.waitFor({ state: 'hidden' });
  assert.deepEqual(
    await page.evaluate(() => window.calls.filter((c) => c.name === 'answerQuestion').at(-1)?.args),
    ['s1', 'q1_mac', '方案A']
  );

  // 409 过期提问自动移除卡片并提示用户
  await page.evaluate(() => {
    window.failQuestion = true;
    window.questionErrorStatus = 409;
    window.store.setState({
      pendingQuestions: {
        q2: { callId: 'q2', question: '已超时的提问', options: ['继续'], requestedAt: Date.now() }
      }
    });
  });
  await qDock.waitFor();
  await qDock.getByRole('button', { name: /继续/ }).click();
  await qDock.waitFor({ state: 'hidden' });
  await page.locator('#notice-text').filter({ hasText: /已超时或已被回答/ }).waitFor();

  // 2. ApprovalDock: 高风险标注 + 倒计时 + Alt+Y / Alt+N 快捷键
  await page.evaluate(() => {
    window.failQuestion = false;
    window.store.setState({
      pendingApprovals: {
        a1: {
          callId: 'a1',
          agent: null,
          name: 'run_command',
          args: 'rm -rf ./dist',
          requestedAt: Date.now(),
          expiresAt: Date.now() + 20_000,
          structuredArgs: { command: 'rm -rf ./dist' }
        },
        a2: {
          callId: 'a2',
          agent: null,
          name: 'edit_file',
          args: 'README.md',
          requestedAt: Date.now(),
          expiresAt: Date.now() + 200_000
        }
      }
    });
  });
  const aDock = page.locator('#approval-dock');
  await aDock.waitFor();
  await aDock.locator('.approval-card.is-high-risk').waitFor();
  await aDock.locator('.approval-risk-badge', { hasText: '高风险操作' }).waitFor();
  await page.keyboard.press('Alt+y');
  await page.waitForFunction(() => window.calls.some((c) => c.name === 'approval' && c.args[0] === 'a1' && c.args[1] === true));
  await page.evaluate(() => {
    window.store.setState((s) => {
      const next = { ...s.pendingApprovals };
      delete next.a1;
      return { pendingApprovals: next };
    });
  });
  await page.keyboard.press('Alt+n');
  await page.waitForFunction(() => window.calls.some((c) => c.name === 'approval' && c.args[0] === 'a2' && c.args[1] === false));

  // 3. 跨会话顶栏提醒条 + 侧栏待审批角标 + 前往处理切会话
  await page.evaluate(() => {
    window.store.getState().syncPendingInteractions({
      approvals: [{
        callId: 'bg-a1',
        sessionId: 's2',
        sessionTitle: '后台发布会话',
        toolName: 'run_command',
        argsSummary: 'rm -rf ./release',
        structuredArgs: { command: 'rm -rf ./release' },
        requestedAt: '2026-01-01T00:00:00Z'
      }],
      questions: []
    });
  });
  const bgBar = page.locator('#bg-attention-bar');
  await bgBar.waitFor();
  assert.match(await bgBar.innerText(), /后台发布会话/);
  await page.locator('[data-session-id="s2"] .session-pending-badge', { hasText: '待批准' }).waitFor();
  await bgBar.getByRole('button', { name: '批准', exact: true }).click();
  await page.waitForFunction(() => window.calls.some((c) => c.name === 'approval' && c.args[0] === 'bg-a1' && c.args[1] === true));

  // 再次注入后台待选择并通过「前往处理」跳转会话
  await page.evaluate(() => {
    window.store.getState().syncPendingInteractions({
      approvals: [],
      questions: [{
        callId: 'bg-q1',
        sessionId: 's2',
        sessionTitle: '后台发布会话',
        question: '确认版本？',
        options: ['v2'],
        requestedAt: '2026-01-01T00:00:00Z'
      }]
    });
  });
  await bgBar.waitFor();
  await page.locator('[data-session-id="s2"] .session-pending-badge', { hasText: '待选择' }).waitFor();
  await bgBar.getByRole('button', { name: '前往处理', exact: true }).click();
  await page.waitForFunction(() => window.store.getState().sessionId === 's2');

  // 4. 设置 -> 提醒面板（浏览器回退说明 + 提示音设置 + 勿扰 + 发送键）
  await page.evaluate(() => window.store.getState().setUi({ settingsOpen: true, settingsSection: 'alerts' }));
  await page.getByRole('heading', { name: '提醒', exact: true }).waitFor();
  await page.locator('.alert-env-note', { hasText: '当前正在浏览器标签页中使用' }).waitFor();
  await page.getByRole('button', { name: '30 分钟', exact: true }).click();
  await page.waitForFunction(() => Number(window.store.getState().alertPrefs.dndUntil || 0) > Date.now());
  await page.getByRole('button', { name: '正常提醒', exact: true }).click();
  await page.getByRole('button', { name: '试听' }).first().click();
  await page.evaluate(() => window.store.getState().setUi({ settingsOpen: false }));

  // 4b. P0 扩展：改动 Diff 预览 + 一键允许规则 + 拒绝附带理由 + 批量低风险批准（跳过高风险）
  await page.evaluate(() => {
    window.calls = [];
    window.store.setState({
      sessionId: 's1',
      workspace: '/tmp/ws1',
      pendingApprovals: {
        e1: {
          callId: 'e1',
          agent: null,
          name: 'edit_file',
          args: 'src/App.tsx',
          requestedAt: Date.now(),
          structuredArgs: { path: 'src/App.tsx', previewDiff: '--- a/src/App.tsx\n+++ b/src/App.tsx\n@@ 替换 #1 @@\n-old()\n+fixed()' }
        },
        r1: {
          callId: 'r1',
          agent: null,
          name: 'run_command',
          args: 'npm test',
          requestedAt: Date.now(),
          structuredArgs: { command: 'npm test' }
        },
        h1: {
          callId: 'h1',
          agent: null,
          name: 'run_command',
          args: 'rm -rf ./dist',
          requestedAt: Date.now(),
          structuredArgs: { command: 'rm -rf ./dist' }
        }
      }
    });
  });
  await aDock.waitFor();
  await aDock.locator('.approval-diff-preview summary').click();
  await aDock.locator('.approval-diff-lines', { hasText: '+fixed()' }).waitFor();
  await aDock.getByRole('button', { name: '允许修改 src/', exact: true }).click();
  await page.waitForFunction(() => window.calls.some((c) => c.name === 'savePermissionRules' && c.args[0].allow.includes('edit_file(src/**)')));

  const highCard = aDock.locator('.approval-card.is-high-risk');
  await highCard.getByRole('button', { name: '拒绝并说明', exact: true }).click();
  await highCard.getByLabel('拒绝原因或改法建议').fill('只清理 build 不要删 dist');
  await highCard.getByRole('button', { name: '提交拒绝', exact: true }).click();
  await page.waitForFunction(() => window.calls.some((c) => c.name === 'approval' && c.args[0] === 'h1' && c.args[1] === false && c.args[2] === '只清理 build 不要删 dist'));

  // 批量批准 2 项非高风险，保留高风险 h2 不自动放行
  await page.evaluate(() => {
    window.calls = [];
    window.store.setState({
      pendingApprovals: {
        b1: { callId: 'b1', agent: null, name: 'run_command', args: 'npm test', structuredArgs: { command: 'npm test' } },
        b2: { callId: 'b2', agent: null, name: 'edit_file', args: 'src/a.ts', structuredArgs: { path: 'src/a.ts' } },
        h2: { callId: 'h2', agent: null, name: 'run_command', args: 'rm -rf ./out', structuredArgs: { command: 'rm -rf ./out' } }
      }
    });
  });
  await aDock.getByRole('button', { name: '批量批准 2 项低风险', exact: true }).click();
  await page.waitForFunction(() => window.calls.some((c) => c.args?.[0] === 'b1') && window.calls.some((c) => c.args?.[0] === 'b2'));
  assert.equal(await page.evaluate(() => window.calls.some((c) => c.args?.[0] === 'h2')), false);
  await page.evaluate(() => window.store.setState({ pendingApprovals: {} }));

  // 4c. P2 扩展：可操作 Toast、Shift+Tab 切换模式、快捷键速查弹窗、侧栏筛选、下一步建议条与空态检查清单
  await page.evaluate(() => {
    window.undone = false;
    window.store.getState().showNotice('已归档对话', { label: '撤销', onAction: () => { window.undone = true; } });
  });
  await page.locator('#notice .notice-action', { hasText: '撤销' }).click();
  assert.equal(await page.evaluate(() => window.undone), true);

  await page.locator('#input-flow').click();
  await page.keyboard.press('Shift+Tab');
  assert.equal(await page.evaluate(() => window.store.getState().ui.executionMode), 'plan');
  await page.keyboard.press('Shift+Tab');
  assert.equal(await page.evaluate(() => window.store.getState().ui.executionMode), 'act');

  const isMac = await page.evaluate(() => /Mac|iPhone|iPad/i.test(navigator.platform));
  await page.keyboard.press(isMac ? 'Meta+/' : 'Control+/');
  await page.locator('#shortcuts-modal').waitFor();
  await page.keyboard.press('Escape');
  await page.locator('#shortcuts-modal').waitFor({ state: 'hidden' });

  // 侧栏待处理筛选
  await page.locator('.session-filter-pill', { hasText: '待处理' }).click();
  await page.locator('#session-list .session-item', { hasText: '后台发布会话' }).waitFor();
  await page.locator('.session-filter-pill', { hasText: '全部' }).click();

  // 空态首次就绪检查卡 & 结束后下一步建议卡
  await page.evaluate(() => {
    window.store.setState({
      workspace: '',
      settingsData: { model: '', modelApiKeySet: false, attachmentLimits: { maxFiles: 64, maxFileBytes: 2097152, maxTotalBytes: 26214400, maxRequestBytes: 37000000 } },
      thread: { rows: [], windowEvents: [], cursor: 0, replaying: false, history: { hasMore: false, hasNewer: false, loading: false, forwardError: false, error: false, pageSize: 80, loadId: 0 }, activityCount: 0 }
    });
  });
  await page.locator('.onboarding-checklist').waitFor();
  const modelStep = page.locator('.onboarding-steps li').first();
  await modelStep.getByText('1. 配置对话模型与 API Key', { exact: true }).waitFor();
  assert.equal(await modelStep.evaluate((el) => el.classList.contains('is-done')), false);

  // 仅有 workspace 但未配置 API Key 时仍显示检查清单
  await page.evaluate(() => {
    window.store.setState({ workspace: '/tmp/ws1' });
  });
  await page.locator('.onboarding-checklist').waitFor();
  assert.equal(await modelStep.evaluate((el) => el.classList.contains('is-done')), false);

  // 模拟保存模型配置期间（modelSave.busy = true），EmptyState 不得递归卡死或误弹提示
  await page.evaluate(() => {
    window.store.setState((draft) => {
      draft.ui.notice = null;
      draft.modelSave = { busy: true, message: '正在保存模型配置…', error: false };
    });
  });
  await page.waitForTimeout(50);
  assert.equal(await page.evaluate(() => window.store.getState().ui.notice), null);

  // 配置模型与密钥且已有工作区后，恢复 modelSave 并验证就绪检查自动收起
  await page.evaluate(() => {
    window.store.setState((draft) => {
      draft.modelSave = { busy: false, message: '', error: false };
      draft.settingsData = {
        model: 'gpt-test',
        modelEndpoint: 'https://example.test/v1',
        modelApiKeySet: true,
        attachmentLimits: { maxFiles: 64, maxFileBytes: 2097152, maxTotalBytes: 26214400, maxRequestBytes: 37000000 }
      };
    });
  });
  await page.locator('.onboarding-checklist').waitFor({ state: 'hidden' });

  await page.evaluate(() => {
    window.store.setState((draft) => {
      draft.workspace = '/tmp/ws1';
      window.applyEvent(draft, { type: 'run.started', sequence: 1, runId: 'r_done', payload: { input: '改文件' } }, { live: true });
      window.applyEvent(draft, { type: 'run.completed', sequence: 2, runId: 'r_done', payload: { summary: '已完成', checks: [] } }, { live: true });
      const last = draft.thread.rows.at(-1);
      if (last && last.type === 'result') {
        last.changedFiles = [{ path: 'src/App.tsx', status: 'M', added: 2, removed: 1, countsKnown: true }];
      }
    });
  });
  await page.locator('.result-next-steps').getByRole('button', { name: '运行验证测试', exact: true }).click();
  assert.match(await page.locator('#input-flow').innerText(), /相关测试/);

  // 5. 浅色主题 + 390px 窄屏零横向溢出检查
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => {
    window.store.getState().setTheme('light');
    window.store.setState({
      sessionId: 's1',
      pendingApprovals: {
        m1: { callId: 'm1', agent: null, name: 'run_command', args: 'rm -rf ./mobile-check', structuredArgs: { command: 'rm -rf ./mobile-check' }, requestedAt: Date.now() }
      },
      pendingQuestions: {
        mq1: { callId: 'mq1', question: '移动端长标题选择测试：这是一段很长很长的说明文本，需要自动换行不能横向撑破视口', options: ['选项一：安全继续执行完整回归校验'], requestedAt: Date.now() }
      }
    });
  });
  await qDock.waitFor();
  await bgBar.waitFor();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  assert.ok(overflow <= 1, `390px 移动端不应产生横向溢出，实际溢出: ${overflow}`);
  await page.screenshot({ path: join(output, 'interaction-alerts-mobile.png'), animations: 'disabled' });
  assert.deepEqual(errors, []);
  console.log('交互细节与提醒全流程验证通过:', output);
} finally {
  await browser?.close();
  await new Promise((resolve) => server.httpServer.close(resolve));
  await rm(output, { recursive: true, force: true }).catch(() => {});
}
