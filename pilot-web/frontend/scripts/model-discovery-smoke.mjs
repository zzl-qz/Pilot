import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { prepareDevelopmentHost } from '../../../pilot-desktop/brand-host.mjs';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const desktopRoot = fileURLToPath(new URL('../../../pilot-desktop', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-model-discovery-'));
let mode = 'success', saveMode = 'success', probes = [], saves = [], held = [], heldSaves = [], heldSettings = [], holdSettings = false, saved, settingsReads = 0, clears = 0;
const models = ['gpt-test-a', 'gpt-test-b', 'gpt-test-c', 'agent-test-d', 'agent-test-e', 'agent-test-f'];
const reset = () => {
  mode = 'success'; saveMode = 'success'; probes = []; saves = []; held = []; heldSaves = []; heldSettings = []; holdSettings = false; settingsReads = 0; clears = 0;
  saved = { model: 'configured-only', modelEndpoint: 'https://saved.example/v1', modelApiKeySet: true, modelApiKeyMasked: '已隐藏', modelOptions: ['configured-only'] };
};
reset();
async function until(condition) {
  for (let i = 0; i < 200; i++) {
    if (condition()) return;
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  assert.fail('等待隔离接口收到请求超时');
}
const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="icon" href="data:,"><title>Pilot 模型选择验收</title></head><body><div id="root"></div><script type="module">
import React from 'react';import {createRoot} from 'react-dom/client';import App from '/src/components/App.jsx';import {useStore} from '/src/store.js';import {api} from '/src/api.js';
import '/src/styles.css';import '/src/shell-vanilla.css';import '/src/styles-addendum.css';import '/src/workbench.css';
document.documentElement.dataset.theme='dark';
const real={settings:api.settings,saveModel:api.saveModel,clearModelKey:api.clearModelKey,probeModels:api.probeModels};
for(const name of Object.keys(api))if(typeof api[name]==='function')api[name]=async()=>({});Object.assign(api,real);
for(const name of ['bookmarks','workProducts','skillDrafts','schedules'])api[name]=async()=>[];
api.files=async()=>({entries:[]});api.filesFlat=async()=>({files:[]});
const settings=await api.settings();useStore.setState({init:async()=>{},sessionId:'model-test',sessionTitle:'模型列表选择验收',settingsData:settings,modelOptions:settings.modelOptions,capabilities:{model:settings.model},projects:[],sessions:[]});
window.store=useStore;createRoot(document.getElementById('root')).render(React.createElement(App));
</script></body></html>`;
const server = await createServer({ root, publicDir: join(root, '../src/main/resources/static'), server: { host: '127.0.0.1', port: 0, proxy: {} }, plugins: [{
  name: 'model-discovery-fixture', configureServer(server) {
    server.middlewares.use('/__models', async (_req, res) => { res.setHeader('Content-Type', 'text/html;charset=utf-8'); res.end(await server.transformIndexHtml('/__models', fixture)); });
    server.middlewares.use('/api/settings', async (req, res) => {
      res.setHeader('Content-Type', 'application/json');
      const text = req.method === 'GET' ? '' : await Array.fromAsync(req).then(chunks => Buffer.concat(chunks).toString());
      const body = text ? JSON.parse(text) : null;
      if (req.method === 'GET' && req.url === '/') {
        settingsReads++;
        const snapshot = { ...saved };
        if (holdSettings) await new Promise(resolve => heldSettings.push(resolve));
        res.end(JSON.stringify(snapshot)); return;
      }
      if (req.url === '/model/models') {
        probes.push(body);
        const requestedMode = mode;
        if (body.endpoint.includes('slow')) await new Promise(resolve => held.push(resolve));
        else await new Promise(resolve => setTimeout(resolve, 80));
        if (requestedMode === 'error') { res.statusCode = 503; res.end(JSON.stringify({ message: '测试端点暂不可用' })); return; }
        const list = requestedMode === 'many' ? Array.from({ length: 30 }, (_, i) => `model-${String(i).padStart(2, '0')}`) : body.endpoint.includes('new') ? ['new-model-a', 'new-model-b'] : models;
        res.end(JSON.stringify(requestedMode === 'malformed' ? { models: {} } : { models: requestedMode === 'empty' ? [] : list })); return;
      }
      if ((req.url === '/model' && req.method === 'PUT') || (req.url === '/model/api-key' && req.method === 'DELETE')) {
        const clearing = req.method === 'DELETE';
        if (clearing) clears++; else saves.push(body);
        const requestedMode = saveMode;
        if (requestedMode === 'slow') await new Promise(resolve => heldSaves.push(resolve));
        if (requestedMode === 'error') { res.statusCode = 503; res.end(JSON.stringify({ message: '测试保存暂不可用' })); return; }
        if (requestedMode === 'malformed') { res.end(JSON.stringify({})); return; }
        saved = { ...saved, model: body?.model || saved.model, modelEndpoint: body?.endpoint || saved.modelEndpoint,
          modelApiKeySet: clearing ? false : Boolean(body?.apiKey || saved.modelApiKeySet), modelApiKeyMasked: clearing ? '' : '新隐藏值' };
      }
      res.end(JSON.stringify(saved));
    });
  }
}] });
await server.listen();
const url = `http://127.0.0.1:${server.httpServer.address().port}/__models`;
let browser, desktop;
async function run(page, name) {
  reset(); page.setDefaultTimeout(10000); const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.setViewportSize({ width: 1440, height: 900 }); await page.goto(url);
  await page.locator('#settings-open').click();
  await page.locator('.settings-nav').getByRole('button', { name: '模型', exact: true }).click();
  const settings = page.locator('#settings'), fetch = settings.locator('#settings-model-fetch');
  await fetch.click(); await settings.getByRole('option', { name: models[0], exact: true }).waitFor();
  assert.equal(await settings.getByRole('option').count(), 6, '有数量提示时必须真实渲染全部选项');
  await settings.getByRole('option', { name: models[3], exact: true }).click();
  assert.equal(await settings.locator('#settings-model-name').inputValue(), models[3]);
  await settings.getByRole('option', { name: models[3], exact: true }).press('End');
  assert.equal(await settings.locator('#settings-model-name').inputValue(), models[5]);
  assert.equal(saves.length, 0, '选择仅修改表单，不自动保存');
  const readsBeforeSave = settingsReads;
  saveMode = 'slow';
  await settings.locator('#settings-model-save').click();
  await until(() => heldSaves.length === 1);
  const feedback = settings.locator('#settings-model-save-feedback');
  await feedback.getByText('正在保存模型配置…', { exact: true }).waitFor();
  assert.equal(await settings.getByRole('button', { name: '保存中…', exact: true }).isDisabled(), true);
  assert.equal(await settings.locator('#settings-model-name').isDisabled(), true);
  assert.equal(await settings.locator('#settings-model-clear-key').isDisabled(), true);
  assert.equal(await settings.getByRole('option').first().isDisabled(), true);
  assert.deepEqual(await page.evaluate(() => Promise.all([store.getState().saveModel({ model: 'duplicate' }), store.getState().clearModelKey()])), [false, false]);
  assert.equal(saves.length, 1, '全局写入锁挡住重复保存和清除');
  assert.equal(clears, 0);
  await page.waitForFunction(() => document.querySelector('#settings-model-save').disabled);
  heldSaves.splice(0).forEach(resolve => resolve()); saveMode = 'success';
  await page.waitForFunction(value => store.getState().settingsData.model === value, models[5]);
  await feedback.getByText(new RegExp(`已保存：模型「${models[5]}」`)).waitFor();
  assert.equal(settingsReads, readsBeforeSave, '直接应用保存响应，不再等待额外 GET 设置');
  assert.equal(await page.evaluate(() => store.getState().capabilities.model), models[5]);
  assert.equal(saves.length, 1); assert.equal(saves[0].model, models[5]);
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 900 }); await fetch.click();
    await settings.getByRole('option', { name: models[0], exact: true }).waitFor();
    await settings.getByRole('option', { name: models[0], exact: true }).click();
    await settings.locator('#settings-model-save').click();
    await feedback.getByText(new RegExp(`已保存：模型「${models[0]}」`)).waitFor();
    assert.equal(await feedback.isVisible(), true, '保存结果必须位于弹窗内');
    assert.ok(await settings.locator('.settings-body').evaluate(el => el.scrollWidth <= el.clientWidth + 1));
    const option = await settings.getByRole('option').first().boundingBox(), body = await settings.locator('.settings-body').boundingBox();
    assert.ok(option.y >= body.y && option.y + option.height <= body.y + body.height, '探测后首个选项应可见');
    await page.screenshot({ path: join(output, `${name}-${width}.png`), animations: 'disabled' });
  }
  await page.setViewportSize({ width: 768, height: 480 });
  await settings.locator('#settings-model-name').fill('short-window-model');
  await settings.locator('#settings-model-save').evaluate(el => el.scrollIntoView({ block: 'end' }));
  await settings.locator('#settings-model-save').click();
  await feedback.getByText(/已保存：模型「short-window-model」/).waitFor();
  const statusBox = await feedback.boundingBox(), panelBox = await settings.locator('.settings-body').boundingBox();
  assert.ok(statusBox.y >= panelBox.y && statusBox.y + statusBox.height <= panelBox.y + panelBox.height + 1, '窗口较矮、保存按钮位于底部时反馈也必须进入可见区域');
  await page.screenshot({ path: join(output, `${name}-short-window.png`), animations: 'disabled' });
  await page.setViewportSize({ width: 1440, height: 900 });
  // 写入失败和网络中断留在当前表单，保留输入与密钥，明确允许重试。
  await settings.locator('#settings-model-name').fill('retry-model');

  await settings.locator('#settings-model-key').fill('fixture-new-key');
  saveMode = 'error'; await settings.locator('#settings-model-save').click();
  await feedback.getByText('保存失败：测试保存暂不可用', { exact: true }).waitFor();
  assert.equal(await feedback.getAttribute('role'), 'alert');
  assert.equal(await settings.locator('#settings-model-key').inputValue(), 'fixture-new-key');
  assert.equal(await settings.locator('#settings-model-name').inputValue(), 'retry-model');
  assert.equal(saved.model, 'short-window-model', '失败不修改已保存配置');
  assert.equal(await settings.locator('#settings-model-save').isEnabled(), true);
  await page.route('**/api/settings/model', route => route.abort());
  await settings.locator('#settings-model-save').click();
  await feedback.getByText(/保存失败/).waitFor();
  await page.unroute('**/api/settings/model');
  saveMode = 'malformed'; await settings.locator('#settings-model-save').click();
  await feedback.getByText(/无法确认保存结果/).waitFor();
  saveMode = 'success'; await settings.locator('#settings-model-save').click();
  await feedback.getByText(/API Key 已更新/).waitFor();
  assert.equal(await settings.locator('#settings-model-key').inputValue(), '', '成功后清空输入中的明文 Key');
  assert.equal(await settings.locator('#settings-model-name').inputValue(), 'retry-model');
  assert.equal(await settings.locator('#settings-model-key').getAttribute('placeholder'), '已保存 新隐藏值（不填沿用）');
  assert.equal((await settings.innerText()).includes('fixture-new-key'), false);
  await settings.locator('#settings-model-name').fill('');
  assert.equal(await settings.locator('#settings-model-save').isDisabled(), true);
  assert.equal(await feedback.count(), 0, '编辑后不能继续显示旧成功提示');
  await settings.locator('#settings-model-name').fill('retry-model');

  // 较早开始的 GET 不得把已确认保存的模型覆盖回旧配置。
  holdSettings = true; const staleRead = page.evaluate(() => store.getState().loadSettings());
  await until(() => heldSettings.length === 1); holdSettings = false;
  await settings.locator('#settings-model-name').fill('fresh-model');
  await settings.locator('#settings-model-save').click(); await feedback.getByText(/已保存：模型「fresh-model」/).waitFor();
  heldSettings.splice(0).forEach(resolve => resolve()); await staleRead;
  assert.equal(await page.evaluate(() => store.getState().settingsData.model), 'fresh-model');

  // 使用真实 20 秒请求时限，验证卡住的保存会退出 busy 并显示可操作的错误。
  saveMode = 'slow'; await settings.locator('#settings-model-save').click();
  await until(() => heldSaves.length === 1);
  await feedback.getByText(/保存失败：请求超时/).waitFor({ timeout: 25000 });
  assert.equal(await settings.locator('#settings-model-save').isEnabled(), true);
  heldSaves.splice(0).forEach(resolve => resolve()); saveMode = 'success';
  await settings.locator('#settings-model-save').click(); await feedback.getByText(/已保存：模型「fresh-model」/).waitFor();

  // 关闭重开时仍有写入锁；旧表单的异步回调不影响新表单。
  saveMode = 'slow'; await settings.locator('#settings-model-name').fill('reopened-model');
  await settings.locator('#settings-model-save').click(); await feedback.getByText(/正在保存/).waitFor();
  await until(() => heldSaves.length === 1);
  await page.keyboard.press('Escape'); await page.locator('#settings-open').click();
  await feedback.getByText(/正在保存/).waitFor();
  assert.equal(await settings.locator('#settings-model-save').isDisabled(), true);
  heldSaves.splice(0).forEach(resolve => resolve()); saveMode = 'success';
  await feedback.getByText(/已保存：模型「reopened-model」/).waitFor();
  assert.equal(await settings.locator('#settings-model-name').inputValue(), 'reopened-model');

  // 清 Key 同样在当前弹窗反馈，失败保留状态，成功更新掩码和按钮。
  await settings.locator('#settings-model-name').fill('unsaved-model');
  await settings.locator('#settings-model-endpoint').fill('https://unsaved.example/v1');
  saveMode = 'error'; await settings.locator('#settings-model-clear-key').click(); await page.locator('#confirm-ok').click();
  await feedback.getByText(/清除失败：测试保存暂不可用/).waitFor();
  assert.equal(await settings.locator('#settings-model-clear-key').isEnabled(), true);
  saveMode = 'success'; await settings.locator('#settings-model-clear-key').click(); await page.locator('#confirm-ok').click();
  await feedback.getByText(/已清除保存的 API Key/).waitFor();
  assert.equal(await settings.locator('#settings-model-clear-key').isDisabled(), true);
  assert.equal(await settings.locator('#settings-model-key').getAttribute('placeholder'), '尚未保存密钥');
  assert.equal(await settings.locator('#settings-model-name').inputValue(), 'unsaved-model', '清除 Key 不丢弃未保存的模型名');
  assert.equal(await settings.locator('#settings-model-endpoint').inputValue(), 'https://unsaved.example/v1');
  assert.equal(saved.model, 'reopened-model');
  mode = 'many'; await fetch.click(); await settings.getByRole('searchbox', { name: '筛选模型' }).waitFor();
  assert.equal(await settings.getByRole('option').count(), 30, '第 12 个之后的模型也必须可选');
  await settings.getByRole('option').first().focus();
  for (let i = 0; i < 20; i++) {
    await page.keyboard.press('Tab');
    assert.ok(await page.evaluate(() => Boolean(document.activeElement.closest('#settings'))), 'Tab 不应因未参与键盘顺序的模型选项而跳出设置弹窗');
  }
  await settings.getByRole('searchbox', { name: '筛选模型' }).fill('model-29');
  await settings.getByRole('option', { name: 'model-29', exact: true }).click();
  assert.equal(await settings.locator('#settings-model-name').inputValue(), 'model-29');
  await settings.getByRole('searchbox', { name: '筛选模型' }).fill('does-not-exist');
  await settings.getByText(/没有匹配的模型/).waitFor();
  mode = 'empty'; await fetch.click(); await settings.getByText(/没有返回任何模型/).waitFor();
  assert.equal(await settings.getByRole('option').count(), 0);
  await settings.locator('#settings-model-name').fill('manual-model');
  mode = 'error'; await fetch.click(); await settings.getByRole('alert').filter({ hasText: '测试端点暂不可用' }).waitFor();
  assert.equal(await settings.locator('#settings-model-name').inputValue(), 'manual-model');
  mode = 'malformed'; await fetch.click(); await settings.getByRole('alert').filter({ hasText: '模型列表格式无效' }).waitFor();

  mode = 'success'; await settings.locator('#settings-model-endpoint').fill('https://slow.example/v1');
  await fetch.click(); await page.waitForFunction(() => document.querySelector('#settings-model-fetch').disabled);
  assert.equal(await settings.getByRole('option').count(), 0);
  await settings.locator('#settings-model-endpoint').fill('https://new.example/v1'); await fetch.click();
  await settings.getByRole('option', { name: 'new-model-a', exact: true }).waitFor();
  held.splice(0).forEach(resolve => resolve()); await page.waitForTimeout(100);
  assert.equal(await settings.getByRole('option').count(), 2);
  assert.equal(await settings.getByRole('option', { name: models[0], exact: true }).count(), 0, '旧端点晚响应不能覆盖新列表');
  await settings.locator('#settings-model-key').fill('fixture-key');
  assert.equal(await settings.getByRole('option').count(), 0, '更换密钥后旧列表失效');
  await fetch.click(); await settings.getByRole('option', { name: 'new-model-a', exact: true }).waitFor();
  assert.equal(probes.at(-1).apiKey, 'fixture-key');
  assert.equal((await settings.innerText()).includes('fixture-key'), false);
  await page.keyboard.press('Escape');

  // 输入区入口也应使用本次 HTTP 返回的列表，而非全局旧候选或前 12 个。
  await page.locator('#model-chip').click();
  const menu = page.locator('#model-menu');
  await menu.getByLabel('端点', { exact: true }).fill('https://new.example/v1');
  assert.equal(await menu.getByRole('option').count(), 0);
  mode = 'many'; await menu.getByRole('button', { name: '探测模型', exact: true }).click();
  await menu.getByRole('searchbox', { name: '筛选模型' }).waitFor();
  assert.equal(await menu.getByRole('option').count(), 30);
  await menu.getByRole('searchbox', { name: '筛选模型' }).fill('model-29');
  await menu.getByRole('option', { name: 'model-29', exact: true }).click();
  assert.equal(await menu.getByLabel('模型名称', { exact: true }).inputValue(), 'model-29');
  saveMode = 'error'; await menu.getByRole('button', { name: '保存', exact: true }).click();
  await menu.getByRole('alert').getByText(/保存失败：测试保存暂不可用/).waitFor();
  assert.equal(await menu.isVisible(), true, '输入区保存失败保留菜单');
  saveMode = 'success';
  const savedBefore = saves.length; await menu.getByRole('button', { name: '保存', exact: true }).click();
  await page.waitForFunction(() => !document.querySelector('#model-menu'));
  assert.equal(saves.length, savedBefore + 1); assert.equal(saved.model, 'model-29'); assert.equal(saved.modelEndpoint, 'https://new.example/v1');

  // 关闭表单后完成的请求不能复活旧列表。
  mode = 'success'; await page.locator('#model-chip').click();
  await menu.getByLabel('端点', { exact: true }).fill('https://slow.example/v1');
  await menu.getByRole('button', { name: '探测模型', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('#model-menu button[aria-busy="true"]'));
  await page.keyboard.press('Escape'); held.splice(0).forEach(resolve => resolve());
  await page.locator('#model-chip').click();
  assert.equal(await menu.getByRole('listbox', { name: '探测到的模型' }).count(), 0);
  assert.deepEqual(errors, []);
  console.log(`${name}: PASS 模型探测/选择、保存中/弹窗内反馈/全局防重复/密钥清空/错误重试/断网/畸形响应/清 Key/重开竞态、三个宽度`);
}
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true });
  await run(await browser.newPage(), 'chromium'); await browser.close(); browser = null;
  const env = { ...process.env, PILOT_URL: url, PILOT_BROWSER_HOME: join(output, 'browser') };
  delete env.ELECTRON_RUN_AS_NODE; delete env.PILOT_DESKTOP_SMOKE;
  desktop = await _electron.launch({ executablePath: prepareDevelopmentHost(), args: [desktopRoot, `--user-data-dir=${join(output, 'profile')}`], env });
  await run(await desktop.firstWindow(), 'electron');
  console.log('Screenshots:', output);
} finally {
  held.splice(0).forEach(resolve => resolve());
  heldSaves.splice(0).forEach(resolve => resolve());
  heldSettings.splice(0).forEach(resolve => resolve());
  await browser?.close(); await desktop?.close(); await server.close();
}
