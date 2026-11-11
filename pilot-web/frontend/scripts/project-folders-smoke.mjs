import assert from 'node:assert/strict';
import { build, preview } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-project-folders-'));
const entry = `.project-folders-${process.pid}.html`, file = join(root, entry);
const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="icon" href="data:,"><title>项目目录选择</title></head><body><div id="root"></div><script type="module">
import React from 'react';import {createRoot} from 'react-dom/client';import {Modals} from '/src/components/Modals.jsx';import {UiProvider} from '/src/components/UiProvider.js';import {useStore} from '/src/store.js';import {api} from '/src/api.js';
import '/src/styles.css';import '/src/shell-vanilla.css';import '/src/styles-addendum.css';import '/src/workbench.css';
document.documentElement.dataset.theme='dark';window.store=useStore;window.api=api;window.calls=[];
api.workspaceRoots=async()=>[{label:'工作目录',path:'/workspace'}];
api.workspaceBrowse=async path=>{if(path==='/missing'&&!window.recovered)throw Error('目录不存在或无法访问');return {path:path||'/workspace',parent:'/',directories:path==='/workspace'?[{name:'资料 空间',path:'/workspace/资料 空间'},{name:'frontend',path:'/workspace/frontend'}]:[]};};
api.createProject=async body=>{calls.push(body);if(window.failSave)throw Error('保存失败，请重试');return {id:'created',...body};};
useStore.setState({workspace:'/workspace',projects:[],loadProjects:async()=>{}});
window.openEditor=()=>useStore.getState().setUi({projectEditor:{name:'Pilot 项目',folders:[]}});
window.openEditor();createRoot(document.getElementById('root')).render(React.createElement(UiProvider,null,React.createElement(Modals)));
</script></body></html>`;
try {
  await writeFile(file, fixture);
  await build({ root, build: { outDir: output, emptyOutDir: false, rollupOptions: { input: file } } });
} finally { await rm(file, { force: true }); }
await cp(join(root, '../src/main/resources/static/fonts'), join(output, 'fonts'), { recursive: true });
const server = await preview({ root, build: { outDir: output }, preview: { host: '127.0.0.1', port: 0, proxy: {} } });
const url = `http://127.0.0.1:${server.httpServer.address().port}/${entry}`;
let browser, electron;

async function run(page, name) {
  page.setDefaultTimeout(8000);
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(url);
  await page.locator('#project-name').waitFor();
  assert.equal(await page.locator('#project-save').isDisabled(), true);
  await page.locator('#project-choose-folders').click();
  await page.getByRole('button', { name: '添加 资料 空间', exact: true }).click();
  await page.getByRole('button', { name: '添加 frontend', exact: true }).click();
  assert.equal(await page.locator('.project-folder-list li').count(), 2);
  assert.equal(await page.getByRole('button', { name: '已添加 frontend', exact: true }).isDisabled(), true);
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await page.screenshot({ path: join(output, `${name}-${width}.png`), fullPage: true });
    assert.equal(await page.locator('#project-modal').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true, `${width}px 弹窗不能横向溢出`);
  }
  await page.locator('#project-browse-path').fill('/missing');
  await page.locator('#project-browse-path').press('Enter');
  await page.getByRole('alert').filter({ hasText: '目录不存在' }).waitFor();
  assert.equal(await page.locator('#project-add-current').count(), 0, '读取失败不能继续添加旧目录');
  assert.equal(await page.evaluate(() => calls.length), 0, '目录路径回车不能提交整个项目');
  await page.evaluate(() => window.recovered = true);
  await page.getByRole('button', { name: '重试读取目录', exact: true }).click();
  await page.locator('#project-add-current').click();
  await page.getByRole('button', { name: '移除 /workspace/frontend', exact: true }).click();
  await page.evaluate(() => window.failSave = true);
  await page.locator('#project-save').click();
  await page.getByRole('alert').filter({ hasText: '保存失败' }).waitFor();
  assert.equal(await page.locator('.project-folder-list li').count(), 2);
  await page.evaluate(() => window.failSave = false);
  await page.locator('#project-save').click();
  await page.locator('#project-modal').waitFor({ state: 'hidden' });
  assert.deepEqual(await page.evaluate(() => calls.at(-1).folders), ['/workspace/资料 空间', '/missing']);

  // 模拟桌面桥不同平台返回的真实路径；原生 IPC 的来源隔离由桌面 security smoke 验证。
  await page.evaluate(() => {
    window.nativeResult = { ok: true, canceled: true, paths: [] };
    window.pilotDesktop = { selectDirectories: async () => window.nativeResult };
    openEditor();
  });
  await page.locator('#project-choose-folders').click();
  assert.equal(await page.locator('.project-folder-list li').count(), 0);
  await page.evaluate(() => window.nativeResult = { ok: true, canceled: false, paths: ['C:\\项目 空间', 'C:/项目 空间/', '\\\\server\\share', '/workspace/资料'] });
  await page.locator('#project-choose-folders').click();
  await page.waitForFunction(() => document.querySelectorAll('.project-folder-list li').length === 3);
  await page.evaluate(() => window.nativeResult = { ok: true, canceled: false, paths: Array.from({ length: 13 }, (_, i) => '/dir/' + i) });
  await page.locator('#project-choose-folders').click();
  await page.getByRole('alert').filter({ hasText: '最多绑定 12' }).waitFor();
  assert.equal(await page.locator('.project-folder-list li').count(), 3, '超限不能静默截掉或清空原选择');
  await page.evaluate(() => window.nativeResult = { ok: false, code: 'REMOTE_WORKSPACE', message: '当前连接远程服务，请选择服务端目录。' });
  await page.locator('#project-choose-folders').click();
  await page.getByRole('status').filter({ hasText: '远程服务' }).waitFor();
  await page.getByRole('region', { name: '浏览服务端文件夹', exact: true }).waitFor();
  await page.getByText('手动填写路径', { exact: true }).click();
  await page.locator('#project-folders').fill('relative/path');
  await page.locator('#project-save').click();
  await page.getByRole('alert').filter({ hasText: '绝对目录路径' }).waitFor();
  await page.locator('#project-folders').fill('/workspace/手工路径\n/workspace/第二个');
  await page.locator('#project-save').click();
  await page.locator('#project-modal').waitFor({ state: 'hidden' });
  assert.deepEqual(await page.evaluate(() => calls.at(-1).folders), ['/workspace/手工路径', '/workspace/第二个']);
  assert.deepEqual(errors, []);
}

try {
  browser = await chromium.launch({ channel: 'chrome', headless: true });
  await run(await browser.newPage(), 'chrome');
  await browser.close(); browser = null;
  const main = join(output, 'electron-fixture.cjs');
  await writeFile(main, `const {app,BrowserWindow}=require('electron');app.whenReady().then(()=>{const w=new BrowserWindow({width:1440,height:900,webPreferences:{contextIsolation:true,nodeIntegration:false}});w.loadURL(${JSON.stringify(url)});});`);
  const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE;
  electron = await _electron.launch({ executablePath: electronBinary(), args: [main], env });
  await run(await electron.firstWindow(), 'electron');
  console.log('目录多选、去重、取消、失败重试、远程降级、手动路径与三种屏宽通过。Screenshots:', output);
} finally {
  await browser?.close(); await electron?.close();
  await new Promise(resolve => server.httpServer.close(resolve));
}
