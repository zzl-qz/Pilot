import assert from 'node:assert/strict';
import { build, preview } from 'vite';
import { chromium } from 'playwright-core';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { artifactDirectory, browserOptions } from './browser-options.mjs';

// 联网搜索设置页：提供商切换、分商端点隔离、SOCKS5 代理提示、优先自配开关、未保存提示、实时搜索与本机重启。
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(artifactDirectory(), 'pilot-web-search-ui-'));
const entry = `.web-search-ui-${process.pid}.html`, file = join(root, entry);
const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import { SettingsModal } from '/src/components/Settings.tsx';
import { UiProvider } from '/src/components/UiProvider.jsx';
import { useStore } from '/src/store.js';
import { api } from '/src/api.js';
import '/src/styles.css';
import '/src/shell-vanilla.css';
import '/src/styles-addendum.css';
import '/src/workbench.css';

document.documentElement.dataset.theme = 'dark';
window.calls = [];
const state = {
  provider: 'auto',
  effectiveProvider: 'searxng',
  apiEndpoint: '',
  apiKeySet: false,
  apiKeyMasked: '',
  searxngEndpoint: 'http://127.0.0.1:8888',
  fallbackEnabled: true,
  preferPilotSearch: false,
  proxy: '',
  proxyNotice: '',
  requestTimeoutSeconds: 20,
  localEnabled: true,
  localManaged: true,
  localRunning: false,
  localPortOpen: true,
  localSettingsUpToDate: false,
  localSettingsPath: '/tmp/.pilot/searxng/settings.yml',
  localLogPath: '/tmp/.pilot/searxng/searxng.log',
  localMessage: '端口由外部 SearXNG 进程占用，且托管配置为旧版',
  nativeWebSearchActive: true,
  nativeWebSearchSummary: '当前模型（gpt-4o）默认启用了模型商内置搜索；如需改用下方博查/Tavily/SearXNG，请开启「优先使用 Pilot 自配置搜索」。',
  providers: [
    { id: 'auto', label: '自动选择', description: '自动优先 API → SearXNG → 公开源', requiresApiKey: false, defaultEndpoint: '', apiEndpoint: '', apiKeySet: false, apiKeyMasked: '' },
    { id: 'bocha', label: '博查 Bocha（国内直连）', description: '中文网页与时效检索质量高', requiresApiKey: true, defaultEndpoint: 'https://api.bochaai.com/v1/web-search', apiEndpoint: '', apiKeySet: false, apiKeyMasked: '' },
    { id: 'tavily', label: 'Tavily', description: '面向 Agent 的网页搜索', requiresApiKey: true, defaultEndpoint: 'https://api.tavily.com/search', apiEndpoint: '', apiKeySet: false, apiKeyMasked: '' },
    { id: 'brave', label: 'Brave Search', description: '独立网页索引', requiresApiKey: true, defaultEndpoint: 'https://api.search.brave.com/res/v1/web/search', apiEndpoint: '', apiKeySet: false, apiKeyMasked: '' },
    { id: 'serper', label: 'Serper (Google)', description: 'Google JSON API', requiresApiKey: true, defaultEndpoint: 'https://google.serper.dev/search', apiEndpoint: '', apiKeySet: false, apiKeyMasked: '' },
    { id: 'exa', label: 'Exa', description: '语义与技术资料检索', requiresApiKey: true, defaultEndpoint: 'https://api.exa.ai/search', apiEndpoint: '', apiKeySet: false, apiKeyMasked: '' },
    { id: 'searxng', label: '本机 / 自建 SearXNG', description: '免密钥元搜索', requiresApiKey: false, defaultEndpoint: 'http://127.0.0.1:8888', apiEndpoint: '', apiKeySet: false, apiKeyMasked: '' },
    { id: 'public', label: '公开只读知识源', description: 'Wikipedia / GitHub / StackOverflow / arXiv', requiresApiKey: false, defaultEndpoint: '', apiEndpoint: '', apiKeySet: false, apiKeyMasked: '' }
  ]
};
const clone = () => JSON.parse(JSON.stringify(state));
for (const name of Object.keys(api)) if (typeof api[name] === 'function') api[name] = async () => ({});
api.webSearchSettings = async () => { window.calls.push(['get']); return clone(); };
api.saveWebSearchSettings = async body => {
  window.calls.push(['save', body]);
  if (body.provider) state.provider = body.provider;
  if (typeof body.fallbackEnabled === 'boolean') state.fallbackEnabled = body.fallbackEnabled;
  if (typeof body.preferPilotSearch === 'boolean') {
    state.preferPilotSearch = body.preferPilotSearch;
    state.nativeWebSearchActive = !body.preferPilotSearch;
    state.nativeWebSearchSummary = body.preferPilotSearch
      ? '已开启「优先使用 Pilot 自配置搜索」：当前模型（gpt-4o）将统一调用下方配置的搜索源。'
      : state.nativeWebSearchSummary;
  }
  if (typeof body.proxy === 'string') {
    state.proxy = body.proxy;
    state.proxyNotice = /^socks5/i.test(body.proxy) ? 'SOCKS5 代理当前仅作用于本机 SearXNG' : '';
  }
  const p = state.providers.find(x => x.id === state.provider);
  if (p && p.requiresApiKey && typeof body.apiEndpoint === 'string') {
    p.apiEndpoint = body.apiEndpoint;
    state.apiEndpoint = body.apiEndpoint;
  }
  if (body.apiKey && p) {
    p.apiKeySet = true;
    p.apiKeyMasked = 'sk-boc…9999';
    state.apiKeySet = true;
    state.apiKeyMasked = 'sk-boc…9999';
  }
  return clone();
};
api.clearWebSearchApiKey = async provider => {
  window.calls.push(['clear', provider]);
  const p = state.providers.find(x => x.id === provider);
  if (p) { p.apiKeySet = false; p.apiKeyMasked = ''; }
  state.apiKeySet = false;
  state.apiKeyMasked = '';
  return clone();
};
api.testWebSearch = async body => {
  window.calls.push(['test', body]);
  return {
    ok: true,
    sourceId: 'bocha',
    sourceLabel: '博查 Bocha（国内直连）',
    triedSources: ['bocha'],
    hits: [{ title: 'Spring Boot 3 官方文档', url: 'https://spring.io/projects/spring-boot', snippet: 'Spring Boot 3 文档摘要', publishedDate: '2026-05-01', engines: ['bocha'], score: 1.0 }],
    answers: ['Spring Boot 3 基于 Java 17+'],
    warnings: [],
    error: null,
    elapsedMs: 128
  };
};
api.restartLocalWebSearch = async () => {
  window.calls.push(['restart']);
  state.localRunning = true;
  state.localSettingsUpToDate = true;
  state.localMessage = '已升级配置至 v3 并重启本机 SearXNG';
  return clone();
};
useStore.setState({ ui: { ...useStore.getState().ui, settingsOpen: true, settingsSection: 'search' } });
createRoot(document.getElementById('root')).render(React.createElement(UiProvider, null, React.createElement(SettingsModal)));
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
const pageErrors = [];
try {
  browser = await chromium.launch(browserOptions({ headless: true }));
  const page = await browser.newPage({ viewport: { width: 1280, height: 860 } });
  page.setDefaultTimeout(8000);
  page.on('pageerror', err => pageErrors.push(String(err)));
  await page.goto(url);
  await page.locator('#web-search-providers').waitFor();

  assert.equal(await page.locator('#web-search-providers button').count(), 8, '应展示全部 8 个搜索提供商');
  await page.getByText('当前模型正在使用模型商内置搜索').waitFor();
  await page.getByText('配置可升级').waitFor();

  // 选择博查，填写 API Key、自定义端点、SOCKS5 代理并开启强制自配
  await page.getByRole('button', { name: /博查 Bocha/ }).click();
  await page.getByText('有未保存修改').waitFor();
  await page.locator('#web-search-api-key').fill('sk-bocha-live-secret-9999');
  await page.locator('#web-search-api-endpoint').fill('https://proxy.corp/bocha');
  await page.locator('#web-search-proxy').fill('socks5://127.0.0.1:1080');
  await page.getByText(/SOCKS5 代理当前仅作用于本机 SearXNG/).waitFor();
  await page.locator('#web-search-prefer-pilot').getByText('强制自配').click();
  await page.getByRole('button', { name: '保存设置' }).click();
  await page.getByText(/出网代理已立即生效于云端与公开搜索/).waitFor();

  assert.equal(await page.locator('#web-search-api-key').inputValue(), '', '保存后必须清空明文密钥输入框');
  const placeholder = await page.locator('#web-search-api-key').getAttribute('placeholder');
  assert.match(placeholder || '', /sk-boc…9999/);
  await page.locator('#web-search-providers .web-search-badge', { hasText: '已配密钥' }).waitFor();

  // 切到 Tavily，确认博查的自定义端点不会串到 Tavily
  await page.getByRole('button', { name: /Tavily/ }).click();
  assert.equal(await page.locator('#web-search-api-endpoint').inputValue(), '', '切换提供商后不应沿用另一商的自定义端点');
  await page.getByRole('button', { name: /博查 Bocha/ }).click();
  assert.equal(await page.locator('#web-search-api-endpoint').inputValue(), 'https://proxy.corp/bocha');

  // 实时测试搜索（回车提交）
  await page.locator('#web-search-test-query').fill('Spring Boot 3');
  await page.locator('#web-search-test-query').press('Enter');
  await page.getByText('Spring Boot 3 官方文档').waitFor();
  await page.getByText(/耗时 128 ms/).waitFor();

  // 清除密钥
  await page.getByRole('button', { name: '清除密钥' }).click();
  await page.getByText('已清除当前提供商的搜索 API Key。').waitFor();
  assert.equal(await page.locator('#web-search-providers .web-search-badge').count(), 0);

  // 同步并重启本机 SearXNG
  await page.getByRole('button', { name: '同步配置并重启本机 SearXNG' }).click();
  await page.getByText('已升级配置至 v3 并重启本机 SearXNG').first().waitFor();
  await page.getByText('运行中').first().waitFor();

  assert.deepEqual(pageErrors, [], '页面不应有未捕获异常');
  console.log('联网搜索设置页 Playwright UI 全量细节验收通过。');
} finally {
  await browser?.close();
  await new Promise(resolve => server.httpServer.close(resolve));
  await rm(output, { recursive: true, force: true });
}
