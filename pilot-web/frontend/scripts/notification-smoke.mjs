import assert from 'node:assert/strict';
import { build, preview } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-notification-ui-'));
const entry = `.notification-fixture-${process.pid}.html`;
const fixture = `<html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><div id="root"></div><script type="module">
import React from 'react';import{createRoot}from'react-dom/client';import Notifications from'/src/components/Notifications.jsx';import{ErrorBoundary}from'/src/components/ErrorBoundary.jsx';import{useStore}from'/src/store.js';import{api}from'/src/api.js';import'/src/styles.css';import'/src/workbench.css';
window.store=useStore;window.receipts=[];window.notices=[];window.failSave=false;window.failRead=false;window.crash=true;
window.Notification=class{static permission='denied';static async requestPermission(){return this.permission;}constructor(title,options){this.title=title;this.options=options;notices.push(this);setTimeout(()=>this.onshow?.(),0);}close(){}};
let settings={version:0,desktop:false,webhookUrl:''},claim=true;
api.deliverySettings=async()=>{if(failRead)throw Error('读取失败，可重试');return settings;};api.deliveries=async()=>[{id:'d',resultId:'result',title:'验证结果',channel:'DESKTOP',state:'FAILED',attempts:1,error:'系统权限拒绝',version:1}];
api.saveDeliverySettings=async body=>{if(failSave)throw Error('保存失败，保留输入');return settings={...body,version:body.version+1};};
api.claimDelivery=async()=>{if(!claim)return null;claim=false;return{id:'d',lease:'lease',resultId:'result',title:'验证结果',summary:'已完成'};};api.deliveryReceipt=async(id,body)=>{receipts.push({id,...body});return{};};api.retryDelivery=async()=>({});
useStore.getState().setUi({notificationsOpen:true});
function Fragile(){if(window.crash)throw Error('隔离测试错误');return React.createElement('p',null,'面板恢复正常');}
createRoot(document.getElementById('root')).render(React.createElement(React.Fragment,null,React.createElement(Notifications),React.createElement(ErrorBoundary,{name:'验证面板',onReset:()=>{window.crash=false;}},React.createElement(Fragile))));
</script></body></html>`;
try { await writeFile(join(root, entry), fixture); await build({ root, build: { outDir: output, emptyOutDir: false, rollupOptions: { input: join(root, entry) } } }); }
finally { await rm(join(root, entry), { force: true }); }
const server = await preview({ root, build: { outDir: output }, preview: { host: '127.0.0.1', port: 0, proxy: {} } });
const url = `http://127.0.0.1:${server.httpServer.address().port}/${entry}`;
let browser, desktop;
async function run(page, name) {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  page.setDefaultTimeout(8000); await page.goto(url);
  await page.getByRole('heading', { name: '通知与投递' }).waitFor();
  const toggle = page.getByLabel('桌面通知（保存时授权当前设备）');
  await toggle.check(); await page.getByRole('button', { name: '保存通知设置', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: '系统通知未获授权' }).waitFor();
  assert.equal(await toggle.isChecked(), true);
  await page.evaluate(() => { Notification.permission = 'granted'; window.failSave = true; });
  await page.getByLabel('Webhook 地址', { exact: true }).fill('https://example.test/hook');
  await page.getByRole('button', { name: '保存通知设置', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: '保存失败，保留输入' }).waitFor();
  assert.equal(await page.getByLabel('Webhook 地址', { exact: true }).inputValue(), 'https://example.test/hook');
  await page.evaluate(() => { window.failSave = false; });
  await page.getByRole('button', { name: '保存通知设置', exact: true }).click();
  await page.waitForFunction(() => receipts.some(row => row.id === 'd' && row.lease === 'lease' && row.success));
  assert.equal(await page.evaluate(() => notices.length), 1);
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 900 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  }
  await page.getByRole('button', { name: '关闭通知设置' }).click();
  await page.getByRole('button', { name: '重试', exact: true }).click();
  await page.getByText('面板恢复正常', { exact: true }).waitFor();
  await page.evaluate(() => notices[0].onclick());
  assert.equal(await page.evaluate(() => store.getState().ui.resultTarget), 'result');
  await page.evaluate(() => { window.failRead = true; store.getState().setUi({ notificationsOpen: true }); });
  await page.getByRole('alert').filter({ hasText: '读取失败，可重试' }).waitFor();
  assert.equal(await page.getByRole('button', { name: '保存通知设置', exact: true }).isDisabled(), true);
  await page.evaluate(() => { window.failRead = false; });
  await page.getByRole('button', { name: '重新加载', exact: true }).click();
  await page.waitForFunction(() => !document.querySelector('#notifications-title').closest('dialog').querySelector('button.btn-primary').disabled);
  await page.screenshot({ path: join(output, `${name}-notifications.png`) });
  assert.deepEqual(errors.filter(error => !error.includes('隔离测试错误')), []);
  console.log(`${name}: PASS permission/save failure/delivery receipt/result target/retry/error boundary/responsive`);
}
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true }); await run(await browser.newPage(), 'chromium'); await browser.close(); browser = null;
  const main = join(output, 'main.cjs'); await writeFile(main, `const{app,BrowserWindow}=require('electron');app.whenReady().then(()=>{const w=new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}});w.loadURL(${JSON.stringify(url)});});`);
  const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE;
  desktop = await _electron.launch({ executablePath: electronBinary(), args: [main], env }); await run(await desktop.firstWindow(), 'electron');
  console.log('Evidence:', output);
} finally { await browser?.close(); await desktop?.close(); await new Promise(resolve => server.httpServer.close(resolve)); }
