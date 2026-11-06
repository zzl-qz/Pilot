import assert from 'node:assert/strict';
import { build, preview } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-inspector-ui-'));
const entry = `.inspector-fixture-${process.pid}.html`;
const fixture = `<html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><div id="root"></div><script type="module">
import React from'react';import{createRoot}from'react-dom/client';import{Inspector}from'/src/components/Inspector.jsx';import{useStore}from'/src/store.js';import{api}from'/src/api.js';import'/src/styles.css';import'/src/styles-addendum.css';import'/src/workbench.css';
window.store=useStore;window.calls=[];window.listeners={};window.menuAction='find';window.quoted='';
const listen=(name,callback)=>{listeners[name]=callback;if(name==='state')queueMicrotask(()=>callback(browserState));return()=>{delete listeners[name];};};
window.browserState={tabs:[{id:'one',title:'新标签',url:'http://localhost/marker/one'}],activeId:'one',url:'http://localhost/marker/one',canGoBack:false,canGoForward:false,recents:[]};
if(new URL(location.href).searchParams.has('desktop'))window.pilotDesktop={setWorkspace:value=>calls.push(['workspace',value]),ensureTab:async id=>calls.push(['ensure',id]),onState:callback=>listen('state',callback),focusAddress:callback=>listen('focus',callback),onDownload:callback=>listen('download',callback),onQuote:callback=>listen('quote',callback),onLog:callback=>listen('log',callback),setBounds:bounds=>calls.push(['bounds',bounds]),
navigate:async url=>{calls.push(['navigate',url]);browserState={...browserState,url,tabs:[{id:'one',title:'本地预览',url}],canGoBack:true};listeners.state?.(browserState);},back:()=>calls.push(['back']),forward:()=>calls.push(['forward']),reload:()=>calls.push(['reload']),stop:()=>calls.push(['stop']),newTab:()=>calls.push(['new']),closeTab:id=>calls.push(['close',id]),selectTab:id=>calls.push(['select',id]),openMenu:async()=>menuAction,find:text=>calls.push(['find',text]),pick:async()=>({ok:true,text:'选中的元素'}),logs:async()=>({ok:true,text:'第一条日志'}),detach:()=>calls.push(['detach']),devtools:()=>calls.push(['devtools']),resetEmulation:()=>calls.push(['reset'])};
api.openBrowserWindow=async()=>({ok:false,message:'浏览器暂不可用，请重试'});api.browserStatus=async()=>({capable:false,reason:'没有浏览器进程'});
useStore.setState({sessionId:'inspector',workspace:'/workspace/pilot',composerApi:{getText:()=>quoted,setText:text=>{quoted=text;},appendText:text=>{quoted+=text;},focus:()=>{}},timeline:[{key:'t',sequence:1,type:'tool.started',kind:'tool',tone:'tool',status:'执行中',title:'读取配置',detail:'config.ts'},{key:'u1',sequence:2,type:'run.usage',kind:'usage',tone:'text',status:'用量'},{key:'u2',sequence:3,type:'run.usage',kind:'usage',tone:'text',status:'用量'}]});
useStore.getState().setUi({inspectorOpen:true,inspectorTab:'browser'});createRoot(document.getElementById('root')).render(React.createElement(Inspector));
</script></body></html>`;
try { await writeFile(join(root, entry), fixture); await build({ root, build: { outDir: output, emptyOutDir: false, rollupOptions: { input: join(root, entry) } } }); }
finally { await rm(join(root, entry), { force: true }); }
const server = await preview({ root, build: { outDir: output }, preview: { host: '127.0.0.1', port: 0, proxy: {} } });
const url = `http://127.0.0.1:${server.httpServer.address().port}/${entry}`;
let browser, desktop;
async function run(page, name) {
  const errors = []; page.on('pageerror', error => errors.push(error.message)); page.setDefaultTimeout(8000);
  await page.setViewportSize({ width: 1440, height: 900 }); await page.goto(url);
  await page.getByRole('button', { name: '打开内置浏览器', exact: true }).click();
  await page.getByText('浏览器暂不可用，请重试', { exact: true }).waitFor();
  await page.goto(url + '?desktop');
  await page.getByRole('textbox', { name: '搜索或输入网址', exact: true }).fill('localhost:1234/中文');
  await page.getByRole('textbox', { name: '搜索或输入网址', exact: true }).press('Enter');
  await page.waitForFunction(() => calls.some(row => row[0] === 'navigate' && row[1].includes('localhost:1234'))).catch(async error => {
    throw new Error(`${error.message}: ${JSON.stringify(await page.evaluate(() => ({ calls, text: document.body.innerText, address: document.querySelector('.browser-address-input')?.value })))}`);
  });
  await page.getByRole('button', { name: '后退', exact: true }).click();
  await page.getByRole('button', { name: '刷新页面', exact: true }).click();
  await page.getByRole('button', { name: '新标签', exact: true }).click();
  await page.getByRole('button', { name: '浏览器菜单', exact: true }).click();
  await page.getByRole('textbox', { name: '页内查找', exact: true }).fill('关键字');
  await page.getByRole('button', { name: '查找', exact: true }).click();
  await page.evaluate(() => { window.menuAction = 'pick'; });
  await page.getByRole('button', { name: '浏览器菜单', exact: true }).click();
  await page.waitForFunction(() => quoted.includes('选中的元素'));
  await page.evaluate(() => { window.menuAction = 'console'; });
  await page.getByRole('button', { name: '浏览器菜单', exact: true }).click();
  await page.getByText('第一条日志', { exact: true }).waitFor();
  await page.waitForFunction(() => Boolean(listeners.log));
  await page.evaluate(() => { listeners.log({ network: false, line: '第二条日志' }); listeners.quote('页面摘录'); listeners.download({ state: 'completed', name: '报告.txt' }); });
  await page.getByText(/第二条日志/).waitFor();
  assert.match(await page.evaluate(() => quoted), /页面摘录/);
  assert.equal(await page.evaluate(() => store.getState().ui.notice.text), '已下载 报告.txt');
  for (const action of ['back', 'reload', 'new', 'find']) assert.ok(await page.evaluate(action => calls.some(row => row[0] === action), action), action);
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 900 });
    assert.ok(await page.locator('#panel-browser').evaluate(el => el.scrollWidth <= el.clientWidth + 1));
  }
  await page.getByRole('tab', { name: '活动', exact: true }).click();
  await page.waitForFunction(() => Object.keys(listeners).length === 0);
  assert.ok(await page.evaluate(() => calls.some(row => row[0] === 'bounds' && row[1].visible === false)));
  await page.getByText('×2', { exact: true }).waitFor();
  await page.locator('#filters').getByRole('tab').filter({ hasText: '工具' }).click();
  assert.equal(await page.locator('#timeline > li').count(), 1);
  await page.getByLabel('搜索活动', { exact: true }).fill('不存在');
  await page.getByText('没有匹配的活动。', { exact: true }).waitFor();
  assert.deepEqual(errors, []); await page.screenshot({ path: join(output, `${name}-inspector.png`) });
  console.log(`${name}: PASS Web fallback/desktop bridge/navigation/find/pick/log/quote/download/cleanup/activity/responsive`);
}
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true }); await run(await browser.newPage(), 'chromium'); await browser.close(); browser = null;
  const main = join(output, 'main.cjs'); await writeFile(main, `const{app,BrowserWindow}=require('electron');app.whenReady().then(()=>{const w=new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}});w.loadURL(${JSON.stringify(url)});});`);
  const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE;
  desktop = await _electron.launch({ executablePath: electronBinary(), args: [main], env }); await run(await desktop.firstWindow(), 'electron');
  console.log('Evidence:', output);
} finally { await browser?.close(); await desktop?.close(); await new Promise(resolve => server.httpServer.close(resolve)); }
