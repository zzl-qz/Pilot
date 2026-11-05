import { build, preview } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-sessions-ui-'));
const entry = `.sessions-fixture-${process.pid}.html`, file = join(root, entry);
const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Pilot 会话管理</title><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';import {createRoot} from 'react-dom/client';import App from '/src/components/App.jsx';import {useStore} from '/src/store.js';import {api} from '/src/api.js';
import '/src/styles.css';import '/src/shell-vanilla.css';import '/src/styles-addendum.css';import '/src/workbench.css';
document.documentElement.dataset.theme='dark';window.store=useStore;window.api=api;window.cancelled=0;
const seed=[{id:'recent',title:'完善模型选择与请求同步',createdAt:'2026-11-04T00:00:00Z'}, {id:'older',title:'检查历史会话的恢复行为',createdAt:'2026-10-01T00:00:00Z'}, {id:'third',title:'补充跨平台验证记录',createdAt:'2026-09-01T00:00:00Z'}].map(s=>({...s,sessionId:s.id,projectId:'pilot',status:'COMPLETED',updatedAt:s.createdAt,pinned:false,unread:false,isBlank:false,archived:false}));
let db=JSON.parse(localStorage.getItem('fixture-sessions')||'null')||seed;const save=()=>localStorage.setItem('fixture-sessions',JSON.stringify(db));
const update=(id,values)=>{db=db.map(s=>s.id===id?{...s,...values}:s);save();return {...db.find(s=>s.id===id)};};
for(const key of Object.keys(api))if(typeof api[key]==='function')api[key]=async()=>({});
api.sessions=async()=>structuredClone(db);api.session=async id=>({...db.find(s=>s.id===id)});api.schedules=async()=>[];api.queue=async()=>({items:[]});api.bookmarks=async()=>[];api.filesFlat=async()=>({files:[]});
api.renameSession=async(id,title)=>{if(window.failRename)throw Error('模拟连接失败');return update(id,{title,titleManual:true});};
api.sessionPreferences=async(id,values)=>update(id,values);api.archiveSession=async(id,archived)=>update(id,{archived});api.deleteSession=async id=>{db=db.filter(s=>s.id!==id);save();};
api.sendMessage=async id=>{if(window.failSend)throw Error('模拟发送失败');return update(id,{updatedAt:new Date().toISOString()});};
useStore.setState({init:async()=>{},connectSse:()=>{},loadHistory:async()=>{},loadPlan:async()=>{},loadQueue:async()=>{},loadTask:async()=>{},refreshContext:async()=>{},refreshActiveRun:async()=>{},loadSkills:async()=>{},sessionId:'recent',sessionTitle:db.find(s=>s.id==='recent')?.title||'',sessions:db,workspace:'',cancelRun:()=>window.cancelled++,projects:[{id:'pilot',name:'Pilot',folders:[]}],ready:true});
useStore.getState().loadSessions().then(()=>createRoot(document.getElementById('root')).render(React.createElement(App)));
if(new URLSearchParams(location.search).has('menu'))setTimeout(()=>{useStore.getState().setUi({sidebarOpen:true});setTimeout(()=>document.querySelector('[data-session-id="recent"] .session-more')?.click(),300);},500);
</script></body></html>`;
try {await writeFile(file,fixture);await build({root,build:{outDir:output,emptyOutDir:false,rollupOptions:{input:file}}});} finally {await rm(file,{force:true});}
await cp(join(root,'../src/main/resources/static/fonts'),join(output,'fonts'),{recursive:true});
const server=await preview({root,build:{outDir:output},preview:{host:'127.0.0.1',port:0,proxy:{}}});
const url=`http://127.0.0.1:${server.httpServer.address().port}/${entry}`;
let browser,electron;
async function run(page,name) {
  page.setDefaultTimeout(8000);const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/api/**',route=>route.fulfill({json:{}}));
  await page.setViewportSize({width:1440,height:900});await page.goto(url);
  const row=id=>page.locator('[data-session-id="'+id+'"]');
  const open=async id=>{await row(id).hover();await row(id).locator('.session-more').click();await page.getByRole('menu').waitFor();};
  const action=async label=>page.getByRole('menuitem',{name:label,exact:true}).click();
  const order=()=>page.locator('.project-group > .project-sessions > .session-row').evaluateAll(rows=>rows.map(el=>el.dataset.sessionId));
  assert.deepEqual(await order(),['recent','older','third']);
  await open('older');await page.screenshot({path:join(output,name+'-1440-menu.png')});
  await page.keyboard.press('ArrowDown');assert.equal(await page.evaluate(()=>document.activeElement.textContent),'置顶');
  await page.evaluate(()=>store.setState({running:true}));await page.keyboard.press('Escape');assert.equal(await page.evaluate(()=>cancelled),0);await page.evaluate(()=>store.setState({running:false}));
  await row('older').click({button:'right'});await action('重命名');
  const input=page.getByLabel('对话名称'),save=page.getByRole('button',{name:'保存',exact:true});
  await input.fill(' ');assert.equal(await save.isDisabled(),true);await input.fill('字'.repeat(121));assert.equal(await save.isDisabled(),true);
  await input.fill('整理会话管理与排序');await page.evaluate(()=>window.failRename=true);await save.click();await page.getByText('操作未成功，请重试。').waitFor();assert.equal(await input.inputValue(),'整理会话管理与排序');
  assert.equal(await input.evaluate(el=>{const event=new KeyboardEvent('keydown',{key:'Enter',isComposing:true,bubbles:true,cancelable:true});el.dispatchEvent(event);return event.defaultPrevented;}),true);
  await page.evaluate(()=>window.failRename=false);await save.click();await page.getByRole('dialog').waitFor({state:'hidden'});
  assert.equal(await row('older').locator('.session-more').evaluate(el=>el===document.activeElement),true);
  assert.equal(await row('older').locator('.session-title').textContent(),'整理会话管理与排序');assert.equal(await page.evaluate(()=>store.getState().sessionTitle),'完善模型选择与请求同步');
  await open('older');await action('置顶');await page.waitForFunction(()=>document.querySelector('.session-row').dataset.sessionId==='older');
  await open('older');await action('标记为未读');await row('older').locator('.session-unread').waitFor();
  await page.reload();await row('older').locator('.session-pin').waitFor();await row('older').locator('.session-unread').waitFor();assert.equal((await order())[0],'older');
  await row('older').locator('.session-item').click();await page.waitForFunction(()=>store.getState().sessionId==='older'&&!store.getState().sessions.find(s=>s.id==='older').unread);
  await open('older');await action('取消置顶');await page.waitForFunction(()=>document.querySelector('.session-row').dataset.sessionId==='recent');
  await page.evaluate(()=>window.failSend=true);assert.equal(await page.evaluate(()=>store.getState().submitInput('失败时不移动',[])),false);assert.equal((await order())[0],'recent');
  await page.evaluate(()=>window.failSend=false);assert.equal(await page.evaluate(()=>store.getState().submitInput('继续完善旧会话',[])),true);
  await page.waitForFunction(()=>document.querySelector('.session-row').dataset.sessionId==='older');await page.reload();await row('older').waitFor();assert.equal((await order())[0],'older');
  await open('recent');await action('重命名');await input.fill('当前对话的新名称');await save.click();await page.waitForFunction(()=>store.getState().sessionTitle==='当前对话的新名称');
  await open('third');await action('归档');await page.getByText('已归档 1',{exact:true}).click();await open('third');await action('恢复到列表');await page.waitForFunction(()=>!store.getState().sessions.find(s=>s.id==='third').archived);
  await open('third');await action('永久删除');await page.getByRole('dialog',{name:'删除对话'}).waitFor();assert.equal(await row('third').count(),1);await page.getByRole('button',{name:'取消',exact:true}).click();assert.equal(await row('third').count(),1);
  await open('third');await action('永久删除');await page.getByRole('button',{name:'删除',exact:true}).click();await row('third').waitFor({state:'detached'});
  // Browser clipboard capability failures provide selectable text, never a false success.
  await page.evaluate(()=>{Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async()=>{throw Error('denied');}}});document.execCommand=()=>false;});
  await open('older');await action('复制会话 ID');await page.getByLabel('可手动复制的内容').waitFor();assert.equal(await page.getByLabel('可手动复制的内容').inputValue(),'older');await page.keyboard.press('Escape');
  await row('older').locator('.session-item').focus();await page.keyboard.press('Shift+F10');await page.getByRole('menu').waitFor();await page.keyboard.press('Escape');
  for(const width of [1440,768,390]) {
    await page.setViewportSize({width,height:900});
    await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    if(await page.locator('#sidebar-toggle').getAttribute('aria-expanded')==='false')await page.locator('#sidebar-toggle').click();
    await open('older');
    const box=await page.getByRole('menu').boundingBox();assert.ok(box.x>=0&&box.y>=0&&box.x+box.width<=width&&box.y+box.height<=900);
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    await page.screenshot({path:join(output,name+'-'+width+'-menu.png')});await page.keyboard.press('Escape');
  }
  await page.evaluate(()=>document.documentElement.dataset.theme='light');await open('older');await page.screenshot({path:join(output,name+'-390-light.png')});await page.keyboard.press('Escape');
  assert.deepEqual(errors,[]);console.log(name+': PASS rename/error/limits/pin/unread/archive/delete/activity/reload/keyboard/clipboard/responsive');
}
try {
  if(process.argv.includes('--serve')) {console.log(url+'?menu');await new Promise(()=>{});}
  browser=await chromium.launch({channel:'chrome',headless:true});await run(await browser.newPage(),'chromium');await browser.close();browser=null;
  const main=join(output,'main.cjs');await writeFile(main,`const {app,BrowserWindow}=require('electron');app.whenReady().then(()=>{const w=new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}});w.loadURL(${JSON.stringify(url)});});`);
  const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;electron=await _electron.launch({executablePath:electronBinary(),args:[main],env});await run(await electron.firstWindow(),'electron');console.log('Screenshots:',output);
} finally {await browser?.close();await electron?.close();await new Promise(resolve=>server.httpServer.close(resolve));}
