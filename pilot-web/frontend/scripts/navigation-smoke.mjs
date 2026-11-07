import { checkShell } from './shell-cases.mjs';
import { build, preview } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-navigation-ui-'));
const entry = `.navigation-fixture-${process.pid}.html`, file = join(root, entry);
const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Pilot 对话导航</title><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';import {createRoot} from 'react-dom/client';import App from '/src/components/App.jsx';import {useStore} from '/src/store.js';import {api} from '/src/api.js';
import '/src/styles.css';import '/src/shell-vanilla.css';import '/src/styles-addendum.css';import '/src/workbench.css';
document.documentElement.dataset.theme='dark';window.store=useStore;window.api=api;window.cancelled=0;
for(const name of Object.keys(api))if(typeof api[name]==='function')api[name]=async()=>({});api.schedules=async()=>[];api.worktreeInfo=async()=>({});api.queue=async()=>({items:[]});api.filesFlat=async()=>({files:[]});
api.bookmarks=async(session)=>JSON.parse(localStorage.getItem('mock-server-bookmarks:'+session)||'[]');
api.saveBookmark=async(session,sequence)=>{const values=await api.bookmarks(session);const row={sequence,title:'已收藏的消息 '+sequence,preview:'同步书签'};if(!values.some(b=>b.sequence===sequence))values.push(row);localStorage.setItem('mock-server-bookmarks:'+session,JSON.stringify(values));return row;};
api.deleteBookmark=async(session,sequence)=>localStorage.setItem('mock-server-bookmarks:'+session,JSON.stringify((await api.bookmarks(session)).filter(b=>b.sequence!==sequence)));
api.importBookmarks=async(session,sequences)=>{for(const seq of sequences)await api.saveBookmark(session,seq);return{bookmarks:await api.bookmarks(session),skipped:[]};};
const topics=['复用现有任务执行链路','加入持久化的到期计划','核对重启后的恢复边界','实现定时任务管理界面','保持人工暂停的状态','验证 Windows 和 Linux 路径','补充开源调研门禁','完成自动核验与结果通知','调整会话导航和消息书签','检查长对话中的滚动行为','验证浅色与窄屏布局','整理最终验证记录'];
window.makeRows=()=>topics.flatMap((title,i)=>[{type:'user',key:'message:'+i,sequence:i+1,text:title+'\\n请结合现有代码和成熟开源实现，说明复用理由。\\n保留工作区约束，并验证正常、失败及边界情况。'}, {type:'turn',key:'turn:'+i,runIds:['run:'+i],tools:[],reply:{text:'已检查「'+title+'」涉及的现有实现。\\n\\n'+Array(3).fill('执行与验证继续沿用原有链路。对应的测试、错误反馈和恢复路径已记录，可在这轮消息中查看。').join('\\n\\n'),live:false},stats:{}}]);
useStore.setState({init:async()=>{},sessionId:'nav-test',sessionTitle:'完善无人值守任务与对话导航',workspace:'/workspace/pilot',cancelRun:()=>window.cancelled++,thread:{...useStore.getState().thread,rows:makeRows(),history:{...useStore.getState().thread.history,hasMore:false}},projects:[]});
createRoot(document.getElementById('root')).render(React.createElement(App));
</script></body></html>`;
try {await writeFile(file,fixture);await build({root,build:{outDir:output,emptyOutDir:false,rollupOptions:{input:file}}});}finally{await rm(file,{force:true});}
await cp(join(root,'../src/main/resources/static/fonts'),join(output,'fonts'),{recursive:true});
const server=await preview({root,build:{outDir:output},preview:{host:'127.0.0.1',port:0,proxy:{}}});
const url=`http://127.0.0.1:${server.httpServer.address().port}/${entry}`;
let browser,electron;
async function run(page,name) {
  page.setDefaultTimeout(8000);const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.setViewportSize({width:1440,height:900});await page.goto(url);
  assert.equal(await page.locator('.workspace-files-body').count(),0,'工作区文件树默认收起，不占用会话列表空间');
  await page.getByRole('button',{name:'更多功能'}).click();
  await page.screenshot({path:join(output,`${name}-more-menu.png`),timeout:20000,animations:'disabled'});
  await page.getByRole('menuitem',{name:/定时任务/}).click();
  assert.equal(await page.evaluate(()=>store.getState().ui.schedulesOpen),true);
  assert.equal(await page.locator('.navigation-rail-more').getAttribute('open'),null,'选择功能后更多菜单自动收起');
  await page.keyboard.press('Escape');
  assert.equal(await page.getByRole('menuitem',{name:/文件工作台/}).count(),0,'专用文件工作台不再占全局入口');
  assert.equal(await page.getByRole('tab',{name:'文件工作台'}).count(),0,'文件专项能力不再与预览、变更并列');
  await page.evaluate(()=>store.getState().setUi({inspectorOpen:false,schedulesOpen:false}));
  const more = page.getByRole('button',{name:'更多功能'});
  await more.focus();await page.keyboard.press('Space');
  await page.getByRole('menu',{name:'更多功能'}).waitFor();
  await page.keyboard.press('Escape');
  assert.equal(await more.getAttribute('aria-expanded'),'false','Escape 关闭更多菜单并保留焦点入口');
  await page.locator('.thread-nav-tick').last().waitFor();assert.equal(await page.locator('.thread-nav-tick').count(),12);
  await page.waitForFunction(()=>document.querySelector('.thread-nav-tick:last-child').getAttribute('aria-current')==='location');
  await page.locator('.thread-nav-tick').nth(2).hover();await page.getByRole('dialog',{name:'消息预览'}).waitFor();
  await page.getByRole('button',{name:'添加书签',exact:true}).click();assert.equal(await page.locator('.thread-nav-tick.is-saved').count(),1);
  await page.screenshot({path:join(output,`${name}-1440-preview.png`),timeout:20000,animations:'disabled'});
  await page.locator('.thread-nav-preview-title').click();
  await page.waitForFunction(()=>{const el=document.querySelector('[data-sequence="3"]'),scroll=document.querySelector('.thread');return Math.abs(el.getBoundingClientRect().top-scroll.getBoundingClientRect().top-24)<3;});
  // Streaming content must not pull a navigated viewport back to the latest message.
  const before=await page.locator('.thread').evaluate(el=>el.scrollTop);
  await page.evaluate(()=>store.setState(s=>({thread:{...s.thread,rows:s.thread.rows.map((r,i)=>i===s.thread.rows.length-1?{...r,reply:{text:r.reply.text+'\\n正在继续验证。'.repeat(80),live:true}}:r)}})));
  await page.waitForTimeout(250);assert.ok(Math.abs(await page.locator('.thread').evaluate(el=>el.scrollTop)-before)<3);
  await page.locator('.thread-nav-tick').nth(4).focus();await page.keyboard.press('ArrowDown');
  assert.equal(await page.locator('.thread-nav-tick').nth(5).evaluate(el=>el===document.activeElement),true);
  await page.evaluate(()=>store.setState({running:true}));await page.keyboard.press('Escape');assert.equal(await page.evaluate(()=>cancelled),0);assert.equal(await page.locator('.thread-nav-preview').count(),0);
  await page.reload();await page.locator('.thread-nav-tick.is-saved').waitFor();
  await page.getByRole('button',{name:'打开对话导航和书签'}).click();await page.getByRole('button',{name:'书签 1',exact:true}).click();assert.equal(await page.locator('.thread-nav-item').count(),1);
  await page.getByRole('button',{name:'取消书签',exact:true}).click();assert.equal(await page.locator('.thread-nav-item').count(),0);
  // Quota failures are visible and must not report a bookmark as saved.
  await page.keyboard.press('Escape');await page.locator('.thread-nav-tick').nth(1).hover();
  await page.evaluate(()=>{window.realSet=Storage.prototype.setItem;Storage.prototype.setItem=function(){throw Error('quota');};});
  await page.getByRole('button',{name:'添加书签',exact:true}).click();await page.getByText(/书签保存失败/).waitFor();assert.equal(await page.locator('.thread-nav-tick.is-saved').count(),0);
  await page.evaluate(()=>Storage.prototype.setItem=window.realSet);
  await page.keyboard.press('Escape');
  // Session switches discard previews/bookmarks from the old conversation.
  await page.evaluate(()=>store.setState({sessionId:'nav-other'}));assert.equal(await page.locator('.thread-nav-tick.is-saved').count(),0);
  // Old bookmarks reuse history loading. Simulate one page returning its missing message.
  await page.evaluate(()=>{
    localStorage.setItem('pilot.message-bookmarks:nav-old',JSON.stringify([{sequence:1,id:'1',key:'message:0',title:'旧消息书签',preview:'分页后定位'}]));
    store.setState(s=>({sessionId:'nav-old',thread:{...s.thread,rows:makeRows().slice(10),windowEvents:[{sequence:6},{sequence:12}],history:{...s.thread.history,hasMore:true}},requestEarlierHistory:async()=>{store.setState(s=>({thread:{...s.thread,rows:makeRows(),windowEvents:[{sequence:1},{sequence:12}],history:{...s.thread.history,hasMore:false}}}));return true;}}));
  });
  await page.locator('.thread-nav-tick').first().click();await page.waitForFunction(()=>document.querySelector('[data-sequence="1"]')?.classList.contains('is-navigation-target'));
  // Long conversations keep the active tick visible inside the independently scrolling rail.
  await page.evaluate(()=>store.setState(s=>({sessionId:'nav-long',thread:{...s.thread,rows:Array.from({length:160},(_,i)=>({type:'user',key:'long:'+i,sequence:i+1,text:'历史问题 '+(i+1)+'：检查现有链路与验证记录。'})),history:{...s.thread.history,hasMore:false}}})));
  await page.getByRole('button',{name:'回到底部',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.thread-nav-tick:last-child').getAttribute('aria-current')==='location');
  const tick=await page.locator('.thread-nav-tick').last().boundingBox(), track=await page.locator('.thread-nav-track').boundingBox();
  assert.ok(tick.y>=track.y-1&&tick.y+tick.height<=track.y+track.height+1);
  for(const width of [1440,768,390]) {
    await page.setViewportSize({width,height:900});await page.reload();await page.locator('.thread-nav-menu').waitFor();
    await page.getByRole('button',{name:'打开对话导航和书签'}).click();await page.screenshot({path:join(output,`${name}-${width}-list.png`),timeout:20000,animations:'disabled'});
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    const box=await page.locator('.thread-nav-panel').boundingBox();assert.ok(box.x>=0&&box.x+box.width<=width&&box.y>=0&&box.y+box.height<=900);
    await page.keyboard.press('Escape');
  }
  await page.evaluate(()=>document.documentElement.dataset.theme='light');await page.getByRole('button',{name:'打开对话导航和书签'}).click();await page.screenshot({path:join(output,`${name}-390-light.png`),timeout:20000,animations:'disabled'});
  await checkShell(page,name,output);
  assert.deepEqual(errors,[]);console.log(name+': PASS preview/jump/bookmarks/persistence/history/streaming/keyboard/errors/session/responsive');
}
try {
  if(process.argv.includes('--serve')){console.log(url);await new Promise(()=>{});}
  browser=await chromium.launch({channel:'chrome',headless:true});await run(await browser.newPage(),'chromium');await browser.close();browser=null;
  const main=join(output,'main.cjs');await writeFile(main,`const {app,BrowserWindow}=require('electron');app.whenReady().then(()=>{let w=new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}});w.loadURL(${JSON.stringify(url)});});`);
  const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;electron=await _electron.launch({executablePath:electronBinary(),args:[main],env});await run(await electron.firstWindow(),'electron');console.log('Screenshots:',output);
} finally {await browser?.close();await electron?.close();await new Promise(resolve=>server.httpServer.close(resolve));}
