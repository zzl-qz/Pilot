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
const projectFolders = ['C:\\工作区\\Pilot', '/workspace/pilot'];
const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Pilot 会话管理</title><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';import {createRoot} from 'react-dom/client';import App from '/src/components/App.jsx';import {useStore} from '/src/store.js';import {api} from '/src/api.js';
import '/src/styles.css';import '/src/shell-vanilla.css';import '/src/styles-addendum.css';import '/src/workbench.css';
document.documentElement.dataset.theme='dark';window.store=useStore;window.api=api;window.cancelled=0;window.archiveAttempts=0;
const seed=[{id:'recent',title:'完善模型选择与请求同步',createdAt:'2026-11-04T00:00:00Z'}, {id:'older',title:'检查历史会话的恢复行为',createdAt:'2026-10-01T00:00:00Z'}, {id:'third',title:'补充跨平台验证记录',createdAt:'2026-09-01T00:00:00Z'}].map(s=>({...s,sessionId:s.id,projectId:'pilot',status:'COMPLETED',updatedAt:s.createdAt,pinned:false,unread:false,isBlank:false,archived:false}));
let db=JSON.parse(localStorage.getItem('fixture-sessions')||'null')||seed;const save=()=>localStorage.setItem('fixture-sessions',JSON.stringify(db));
const update=(id,values)=>{db=db.map(s=>s.id===id?{...s,...values}:s);save();return {...db.find(s=>s.id===id)};};
for(const key of Object.keys(api))if(typeof api[key]==='function')api[key]=async()=>({});
api.sessions=async()=>structuredClone(db);api.session=async id=>({...db.find(s=>s.id===id)});api.schedules=async()=>[];api.queue=async()=>({items:[]});api.bookmarks=async()=>[];api.filesFlat=async()=>({files:[]});
api.renameSession=async(id,title)=>{if(window.failRename)throw Error('模拟连接失败');return update(id,{title,titleManual:true});};
api.sessionPreferences=async(id,values)=>update(id,values);api.archiveSession=async(id,archived)=>{archiveAttempts++;if(window.failArchive)throw Error('模拟归档失败');return update(id,{archived});};api.deleteSession=async id=>{if(window.holdDelete)await new Promise(resolve=>window.finishDelete=resolve);if(window.failDelete)throw Error('模拟删除失败');db=db.filter(s=>s.id!==id);save();};
api.sendMessage=async id=>{if(window.failSend)throw Error('模拟发送失败');return update(id,{updatedAt:new Date().toISOString()});};
useStore.setState({init:async()=>{},connectSse:()=>{},loadHistory:async()=>{},loadPlan:async()=>{},loadQueue:async()=>{},loadTask:async()=>{},refreshContext:async()=>{},refreshActiveRun:async()=>{},loadSkills:async()=>{},sessionId:'recent',sessionTitle:db.find(s=>s.id==='recent')?.title||'',sessions:db,workspace:'',cancelRun:()=>window.cancelled++,projects:[{id:'pilot',name:'Pilot',folders:${JSON.stringify(projectFolders)}}],ready:true});
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
  await page.keyboard.press('ArrowDown');
  await page.waitForFunction(()=>document.activeElement.textContent.trim()==='重命名');
  await page.keyboard.press('ArrowDown');assert.equal(await page.evaluate(()=>document.activeElement.textContent.trim()),'置顶');
  await page.evaluate(()=>store.setState({running:true}));await page.keyboard.press('Escape');assert.equal(await page.evaluate(()=>cancelled),0);await page.evaluate(()=>store.setState({running:false}));
  await row('older').click({button:'right'});await action('重命名');
  const input=page.getByLabel('对话名称'),save=page.getByRole('button',{name:'保存',exact:true});
  await input.fill(' ');assert.equal(await save.isDisabled(),true);await input.fill('字'.repeat(121));assert.equal(await save.isDisabled(),true);
  await input.fill('整理会话管理与排序');await page.evaluate(()=>window.failRename=true);await save.click();await page.getByText('操作未成功，请重试。').waitFor();assert.equal(await input.inputValue(),'整理会话管理与排序');
  await page.screenshot({path:join(output,`${name}-rename-error.png`),animations:'disabled'});
  assert.equal(await input.evaluate(el=>{const event=new KeyboardEvent('keydown',{key:'Enter',isComposing:true,bubbles:true,cancelable:true});el.dispatchEvent(event);return event.defaultPrevented;}),true);
  await page.evaluate(()=>window.failRename=false);await save.click();await page.getByRole('dialog').waitFor({state:'hidden'});
  await page.waitForFunction(()=>document.activeElement===document.querySelector('[data-session-id="older"] .session-item'));
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
  await page.evaluate(()=>window.failArchive=true);
  await open('third');await action('归档');
  const retry=page.getByRole('dialog',{name:'会话操作失败',exact:true});
  await retry.getByRole('alert').waitFor();
  assert.equal(await page.evaluate(()=>store.getState().sessions.find(s=>s.id==='third').archived),false);
  await page.evaluate(()=>window.failArchive=false);
  await retry.getByRole('button',{name:'重试',exact:true}).click();
  await page.waitForFunction(()=>store.getState().sessions.find(s=>s.id==='third').archived);
  assert.equal(await page.evaluate(()=>archiveAttempts),2);
  await page.getByRole('button',{name:'收起「Pilot」',exact:true}).click();
  await page.getByRole('searchbox',{name:'搜索对话',exact:true}).fill('跨平台');
  await row('third').waitFor();
  await page.getByRole('button',{name:'清除 搜索对话',exact:true}).click();
  assert.equal(await row('third').isVisible(),false,'清除筛选后恢复原来的项目折叠状态');
  await page.getByRole('button',{name:'展开「Pilot」',exact:true}).click();
  await page.getByText('已归档 1',{exact:true}).click();
  await open('third');await action('恢复到列表');await page.waitForFunction(()=>!store.getState().sessions.find(s=>s.id==='third').archived);
  const deletion=page.getByRole('alertdialog',{name:'删除对话',exact:true});
  await open('third');await action('永久删除');await deletion.waitFor();assert.equal(await row('third').count(),1);
  await page.waitForFunction(()=>document.activeElement.textContent.trim()==='取消');
  await deletion.getByRole('button',{name:'取消',exact:true}).click();assert.equal(await row('third').count(),1);
  await page.evaluate(()=>{window.failDelete=true;window.holdDelete=true;});
  await open('third');await action('永久删除');await deletion.getByRole('button',{name:'删除',exact:true}).click();
  await page.waitForFunction(()=>typeof window.finishDelete==='function');
  await page.keyboard.press('Escape');assert.equal(await deletion.isVisible(),true,'等待删除结果时继续保留确认框');
  await page.evaluate(()=>{window.holdDelete=false;window.finishDelete();});
  await deletion.getByRole('button',{name:'重试删除',exact:true}).waitFor();
  assert.equal(await row('third').count(),1);
  await page.evaluate(()=>window.failDelete=false);
  await deletion.getByRole('button',{name:'重试删除',exact:true}).click();await row('third').waitFor({state:'detached'});
  // Browser clipboard capability failures provide selectable text, never a false success.
  await page.evaluate(()=>{Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async()=>{throw Error('denied');}}});document.execCommand=()=>false;});
  await open('older');await action('复制会话 ID');
  const manualCopy = page.getByLabel('可手动复制的内容');
  await manualCopy.waitFor();assert.equal(await manualCopy.inputValue(),'older');
  await page.waitForFunction(()=>{
    const field=document.querySelector('[aria-label="可手动复制的内容"]');
    return field===document.activeElement && field.selectionStart===0 && field.selectionEnd===field.value.length;
  });
  await page.keyboard.press('Escape');
  await manualCopy.waitFor({state:'hidden'});
  await row('older').locator('.session-item').focus();await page.keyboard.press('Shift+F10');
  await page.getByRole('menu').waitFor();
  await page.waitForFunction(()=>document.activeElement.textContent.trim()==='重命名');
  await page.keyboard.press('Escape');
  await page.getByRole('menu').waitFor({state:'hidden'});
  await page.waitForFunction(()=>document.activeElement===document.querySelector('[data-session-id="older"] .session-item'));

  await page.getByRole('button',{name:'项目设置',exact:true}).click();
  await page.getByRole('dialog',{name:'编辑项目'}).waitFor();
  await page.keyboard.press('Escape');
  await page.waitForFunction(()=>document.activeElement.getAttribute('aria-label')==='项目设置');
  await page.getByRole('button',{name:'新建项目',exact:true}).click();
  await page.getByRole('dialog',{name:'新建项目',exact:true}).waitFor();await page.keyboard.press('Escape');
  await page.getByRole('button',{name:'在此项目新建对话',exact:true}).click();
  assert.deepEqual(await page.evaluate(()=>store.getState().draftSession),{projectId:'pilot',workspace:projectFolders[0]});
  await page.reload();await row('older').waitFor();
  for(const width of [1440,768,390]) {
    await page.setViewportSize({width,height:900});
    await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    if(await page.locator('#sidebar-toggle').getAttribute('aria-expanded')==='false')await page.locator('#sidebar-toggle').click();
    for (const theme of ['dark','light']) {
      await page.evaluate(theme=>store.getState().setTheme(theme),theme);
      await page.screenshot({path:join(output,`${name}-${width}-sidebar-${theme}.png`),animations:'disabled'});
    }
    await page.evaluate(()=>store.getState().setTheme('dark'));
    await row('older').locator('.session-more').focus();await page.keyboard.press('Enter');await page.getByRole('menu').waitFor();
    const box=await page.getByRole('menu').boundingBox();assert.ok(box.x>=0&&box.y>=0&&box.x+box.width<=width&&box.y+box.height<=900);
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    await page.screenshot({path:join(output,name+'-'+width+'-menu.png'),animations:'disabled'});await page.keyboard.press('Escape');
    if (width <= 960) {
      await page.locator('#sidebar-toggle').click();
      assert.equal(await page.locator('#sidebar-toggle').evaluate(el=>el===document.activeElement),true);
    }
  }
  assert.deepEqual(errors,[]);console.log(name+': PASS rename/error/limits/pin/unread/archive/error/delete/activity/reload/keyboard/clipboard/project-actions/responsive');
}

async function checkTouch(browser, missingAnchor) {
  const page = await browser.newPage({viewport:{width:390,height:844},hasTouch:true,isMobile:true,reducedMotion:'reduce'});
  page.setDefaultTimeout(6000);
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  try {
    if(missingAnchor) await page.addInitScript(()=>{
      const supports=CSS.supports.bind(CSS);
      CSS.supports=(...args)=>args[0]==='anchor-name'?false:supports(...args);
    });
    await page.route('**/api/**',route=>route.fulfill({json:{}}));
    await page.goto(url);
    await page.locator('#sidebar-toggle').tap();
    const row=page.locator('[data-session-id="older"]');
    const more=row.locator('.session-more');
    await more.tap();
    const surfaceRole=missingAnchor?'dialog':'menu';
    const optionRole=missingAnchor?'button':'menuitem';
    const surface=page.getByRole(surfaceRole,{name:'对话操作：检查历史会话的恢复行为',exact:true});
    const rename=surface.getByRole(optionRole,{name:'重命名',exact:true});
    await rename.waitFor();
    const box=await surface.boundingBox();
    assert.ok(box && box.x>=0 && box.x+box.width<=391 && box.y>=0 && box.y+box.height<=845);
    await page.screenshot({path:join(output,missingAnchor?'touch-session-sheet.png':'touch-session-menu.png'),animations:'disabled'});
    await rename.tap();
    const dialog=page.getByRole('dialog',{name:'重命名对话',exact:true});
    await dialog.getByLabel('对话名称').fill('手机上整理这条对话');
    await dialog.getByRole('button',{name:'保存',exact:true}).tap();
    await dialog.waitFor({state:'hidden'});
    await row.getByText('手机上整理这条对话',{exact:true}).waitFor();
    assert.equal(await page.evaluate(()=>store.getState().sessionId),'recent','菜单操作仍留在原来的对话');

    const target=await row.locator('.session-item').boundingBox();
    const longPressMenu=page.getByRole(surfaceRole,{name:'对话操作：手机上整理这条对话',exact:true});
    const cdp=await page.context().newCDPSession(page);
    await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:target.x+24,y:target.y+target.height/2}]});
    try { await longPressMenu.getByRole(optionRole,{name:'重命名',exact:true}).waitFor(); }
    finally { await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]}); }
    assert.equal(await page.evaluate(()=>store.getState().sessionId),'recent','长按不打开或切换对话');
    await longPressMenu.waitFor();
    await page.keyboard.press('Escape');
    await longPressMenu.waitFor({state:'hidden'});
    assert.equal(await page.locator('#sidebar-toggle').getAttribute('aria-expanded'),'true','Esc 只关闭会话菜单');
    await row.locator('.session-item').tap();
    await page.waitForFunction(()=>store.getState().sessionId==='older');
    assert.deepEqual(errors,[]);
    console.log(`chromium: PASS touch tap/long-press/rename (${missingAnchor?'no CSS anchor':'anchored'})`);
  } finally { await page.close(); }
}

try {
  if(process.argv.includes('--serve')) {console.log(url+'?menu');await new Promise(()=>{});}
  browser=await chromium.launch({channel:'chrome',headless:true});
  await run(await browser.newPage(),'chromium');
  await checkTouch(browser,false);await checkTouch(browser,true);
  await browser.close();browser=null;
  const main=join(output,'main.cjs');await writeFile(main,`const {app,BrowserWindow}=require('electron');app.setPath('userData',${JSON.stringify(join(output,'profile'))});app.whenReady().then(()=>{const w=new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}});w.loadURL(${JSON.stringify(url)});});`);
  const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;electron=await _electron.launch({executablePath:electronBinary(),args:[main],env});await run(await electron.firstWindow(),'electron');console.log('Screenshots:',output);
} finally {await browser?.close();await electron?.close();await new Promise(resolve=>server.httpServer.close(resolve));}
