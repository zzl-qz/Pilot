import { build, preview } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('..',import.meta.url));
const output=await mkdtemp(join(tmpdir(),'pilot-workbench-style-'));
const entry=`.workbench-style-${process.pid}.html`,file=join(root,entry);
const fixture=`<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Pilot 工作台</title><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';import {createRoot} from 'react-dom/client';import App from '/src/components/App.jsx';import {useStore} from '/src/store.js';import {api} from '/src/api.js';
import '/src/styles.css';import '/src/shell-vanilla.css';import '/src/styles-addendum.css';import '/src/workbench.css';
window.store=useStore;window.api=api;document.documentElement.dataset.theme='dark';document.documentElement.dataset.accent='sky';
for(const k of Object.keys(api))if(typeof api[k]==='function')api[k]=async()=>({});
api.schedules=api.bookmarks=api.workProducts=api.skillDrafts=async()=>[];api.queue=async()=>({items:[]});api.worktreeInfo=async()=>({branch:'main'});
api.files=async()=>({entries:['pilot-core','pilot-server','pilot-web','docs'].map(name=>({name,path:name,directory:true}))});
const sessions=['完善工作台的状态与层次','检查模型选择与请求同步','优化旧会话的活动排序','补充跨平台交互验证'].map((title,i)=>({id:'style-'+i,title,projectId:'pilot',status:i===2?'RUNNING':'COMPLETED',isBlank:false,pinned:i===0,unread:i===1}));
const rows=[{type:'user',key:'u1',sequence:1,text:'检查这次改动，重点看看会话状态和输入区。'},{type:'turn',key:'t1',runIds:['r1'],tools:[],reply:{text:'已整理工作台的状态与交互层次。\\n\\n**当前会话**、任务状态和消息内容分别呈现，发送前仍会等待模型配置保存完成。',live:false},stats:{startedAt:1000,finishedAt:5000,modelRequests:1,inputTokens:12040,outputTokens:328,contextTokens:12368,contextLimit:300000,exact:true}},{type:'result',key:'r1',tone:'text',title:'执行结束',detail:'',runIds:['r1']},{type:'user',key:'u2',sequence:2,text:'深色和浅色主题都检查一下，窄屏也要能正常使用。'},{type:'turn',key:'t2',runIds:['r2'],tools:[],reply:{text:'接下来检查三种窗口宽度，以及键盘焦点、菜单和等待批准时的状态。\\n\\n结果会保留在当前会话，方便继续审阅。',live:false},stats:{startedAt:6000,finishedAt:9000,modelRequests:1,inputTokens:12800,outputTokens:186,contextTokens:12986,contextLimit:300000,exact:true}},{type:'result',key:'r2',tone:'text',title:'执行结束',detail:'',runIds:['r2']}];
useStore.setState({init:async()=>{},sessionId:'style-0',sessionTitle:sessions[0].title,workspace:'/workspace/pilot',sessions,projects:[{id:'pilot',name:'pilot',folders:['/workspace/pilot']}],settingsData:{model:'gpt-6.1-sol'},task:{goal:'检查工作台状态与交互层次',status:'COMPLETED',acceptance:[]},thread:{...useStore.getState().thread,rows,history:{...useStore.getState().thread.history,hasMore:false}},contextStatus:{usedTokens:12986,limitTokens:300000},lastOutcome:'completed'});
useStore.getState().setUi({resultUnread:2,inspectorOpen:false,themeMode:'dark',accent:'sky'});
if(new URLSearchParams(location.search).has('review')){
 const changeDiff='diff --git a/src/workspace.ts b/src/workspace.ts\\n--- a/src/workspace.ts\\n+++ b/src/workspace.ts\\n@@ -7,4 +7,5 @@\\n export function workspaceLabel(path: string) {\\n-  return path.split("/").pop();\\n+  const parts = path.split(/[\\\\/]/).filter(Boolean);\\n+  return parts.at(-1) ?? path;\\n }\\n';
 const report={gitRepository:true,files:[{path:'src/workspace.ts',status:'M',added:2,removed:1},{path:'src/preview.tsx',status:'M',added:8,removed:3},{path:'docs/验收记录.md',status:'??',added:12,removed:0}],diff:changeDiff};
 api.diff=async()=>report;api.file=async()=>{throw Error('无法读取工作区中的文件');};
 useStore.setState({changeReport:report,lastRunId:'r2'});
 useStore.getState().setUi({sidebarOpen:innerWidth>960,inspectorOpen:true,inspectorTab:'changes',changeView:'src/workspace.ts'});
}
createRoot(document.getElementById('root')).render(React.createElement(App));
</script></body></html>`;
try{await writeFile(file,fixture);await build({root,build:{outDir:output,emptyOutDir:false,rollupOptions:{input:file}}});}finally{await rm(file,{force:true});}
await cp(join(root,'../src/main/resources/static/fonts'),join(output,'fonts'),{recursive:true});
const server=await preview({root,build:{outDir:output},preview:{host:'127.0.0.1',port:0,proxy:{}}});
const url=`http://127.0.0.1:${server.httpServer.address().port}/${entry}`;
let browser,electron;
async function run(page,name){
 page.setDefaultTimeout(7000);const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/**',route=>route.fulfill({json:{}}));
 for(const theme of ['dark','light'])for(const width of [1440,768,390]){
  await page.setViewportSize({width,height:900});await page.goto(url);await page.locator('.msg-assistant').last().waitFor();
  await page.evaluate(theme=>store.getState().setTheme(theme),theme);
  await page.evaluate(async()=>{await document.fonts.ready;await Promise.allSettled(document.getAnimations().filter(a=>a.effect.getComputedTiming().iterations!==Infinity).map(a=>a.finished));});
  await page.screenshot({path:join(output,`${name}-${theme}-${width}.png`),timeout:20000,animations:'disabled'});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  const box=await page.locator('#run-state').boundingBox();assert.ok(box.width>32&&box.x+box.width<=width);
  const composer=await page.locator('.composer').boundingBox();assert.ok(composer.x>=0&&composer.y+composer.height<=900);
  const workspace=page.locator('#workspace-name');
  await workspace.click();
  const workspaceDialog=page.getByRole('dialog',{name:'工作区',exact:true});
  await workspaceDialog.getByRole('textbox',{name:'分支',exact:true}).waitFor();
  await workspaceDialog.evaluate(el=>Promise.allSettled(el.getAnimations().map(animation=>animation.finished)));
  assert.ok(await workspaceDialog.evaluate(el=>el.scrollWidth<=el.clientWidth+1),'工作区弹窗无横向溢出');
  assert.equal(await workspaceDialog.getByRole('button',{name:'/workspace/pilot',exact:true}).getAttribute('aria-current'),'true');
  await page.screenshot({path:join(output,`${name}-workspace-${theme}-${width}.png`),animations:'disabled'});
  await page.keyboard.press('Escape');
  await workspaceDialog.waitFor({state:'hidden'});
  assert.equal(await workspace.evaluate(el=>el===document.activeElement),true,'工作区关闭后回到触发按钮');
 }
 await page.setViewportSize({width:1440,height:900});await page.goto(url);await page.locator('#run-state').waitFor();
 await page.evaluate(()=>{
  api.worktreeInfo=async()=>{throw Error('模拟检出状态读取失败');};
  store.setState({openWorktree:async branch=>{window.submittedBranch=branch;}});
 });
 await page.locator('#workspace-name').click();
 const workspaceDialog=page.getByRole('dialog',{name:'工作区',exact:true});
 await workspaceDialog.getByRole('alert').filter({hasText:'模拟检出状态读取失败'}).waitFor();
 await workspaceDialog.getByRole('textbox',{name:'分支',exact:true}).fill('  feature/ui  ');
 await workspaceDialog.getByRole('textbox',{name:'分支',exact:true}).press('Enter');
 await workspaceDialog.waitFor({state:'hidden'});
 assert.equal(await page.evaluate(()=>window.submittedBranch),'feature/ui');
 await page.evaluate(()=>store.setState(s=>({thread:{...s.thread,rows:[...s.thread.rows,
  {type:'plan',key:'plan-done',items:[{step:'准备输入',status:'completed'},{step:'执行任务',status:'completed'},{step:'检查结果',status:'completed'},{step:'整理交付',status:'completed'}]},
  {type:'result',key:'continued-error',tone:'error',title:'运行失败 · MODEL_ERROR',detail:'模型调用失败：模型请求被中断',runIds:['previous-run'],continued:true,changedFiles:[]},
  {type:'result',key:'resumable-run',tone:'warn',title:'运行已取消',detail:'运行已被用户取消。',runIds:['interrupted-run'],resumable:true,changedFiles:[]}
 ]}})));
 await page.locator('.plan-card.is-complete').waitFor();
 assert.equal(await page.locator('.plan-card.is-complete .plan-list').isVisible(),false,'完成计划默认收起步骤');
 await page.locator('.plan-card.is-complete summary').click();
 assert.equal(await page.locator('.plan-card.is-complete .plan-item').count(),4,'完成计划仍可展开查看全部步骤');
 await page.locator('.plan-card.is-complete summary').click();
 assert.equal(await page.locator('.turn-card.is-continued .turn-card-detail').isVisible(),false,'已继续执行的错误默认不展开诊断详情');
 await page.locator('.turn-card.is-continued summary').click();
 assert.equal(await page.locator('#task-resume').count(),1,'需要恢复时输入区只显示一个继续操作');
 assert.equal(await page.locator('.turn-card .btn.is-continue').count(),0,'历史运行结果不重复显示继续按钮');
 await page.getByText('模型调用失败：模型请求被中断',{exact:true}).waitFor();
 await page.locator('.turn-card.is-continued summary').click();
 await page.screenshot({path:join(output,`${name}-history-hierarchy.png`),timeout:20000,animations:'disabled'});
 await page.reload();await page.locator('#run-state').waitFor();
 await page.evaluate(()=>{
  const state=store.getState();
  state.setUi({inspectorOpen:true,inspectorTab:'preview',previewPath:'pilot-core/src/main/java/Example.java'});
  store.setState({_previewData:{path:'pilot-core/src/main/java/Example.java',content:'public class Example {\n  public static void main(String[] args) { System.out.println("a long line that should remain horizontally scrollable in code preview"); }\n}',size:140}});
 });
 await page.locator('#preview-body').waitFor();
 assert.equal(await page.locator('.inspector-rail [role="tab"]').count(),5,'常用面板保持精简');
 assert.equal(await page.getByRole('tab',{name:'文件工作台'}).count(),0,'文件专项能力不与预览、变更并列');
 assert.equal(await page.getByRole('menuitem',{name:'活动',exact:true}).count(),0,'低频工具默认收起');
 await page.getByRole('button',{name:'更多工具',exact:true}).click();
 assert.equal(await page.getByRole('menuitem',{name:'活动',exact:true}).count(),1,'低频工具仍可从更多菜单访问');
 await page.keyboard.press('Escape');
 await page.getByRole('menu',{name:'更多工具'}).waitFor({state:'hidden'});
 assert.equal(await page.getByRole('button',{name:'更多工具',exact:true}).getAttribute('aria-expanded'),'false','Escape 收起更多工具菜单');
 assert.deepEqual(await page.locator('.inspector-rail [role="tab"]').evaluateAll(items=>items.map(el=>el.getAttribute('aria-label'))), ['成果', '对话', '变更', '预览', '命令']);
 await page.getByRole('tab',{name:'预览',exact:true}).focus();
 await page.keyboard.press('ArrowDown');
 assert.equal(await page.evaluate(()=>document.activeElement.id),'inspector-tab-commands','向下方向键移动标签焦点');
 await page.keyboard.press('ArrowUp');
 assert.equal(await page.locator('.preview-wrap').getAttribute('aria-pressed'),'false','代码预览默认保持源码行结构');
 assert.equal(await page.locator('#preview-body').evaluate(el=>getComputedStyle(el).whiteSpace),'pre','代码预览默认横向滚动，不把源码挤成窄列');
 await page.screenshot({path:join(output,`${name}-preview-desktop.png`),timeout:20000,animations:'disabled'});
 for(const width of [1440,1100,961,768,390]){
  await page.setViewportSize({width,height:900});
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
  await page.waitForFunction(()=>![...document.querySelector('.activity.inspector')?.getAnimations()||[]].some(animation=>animation.playState==='running'));
  await page.waitForFunction(()=>document.querySelector('.activity.inspector')?.getBoundingClientRect().width>0);
  const panel=await page.locator('.activity.inspector').boundingBox();
  assert.ok(panel.x>=0&&panel.x+panel.width<=width+1,`预览面板在 ${width}px 视口内`);
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),`预览面板在 ${width}px 不制造横向溢出`);
  if(width===1440)assert.ok(panel.width>=450,`桌面预览面板获得源码可读宽度，实际 ${panel.width}px`);
  if(width<=960){
   const layout=await page.locator('.activity.inspector').evaluate(el=>({className:el.parentElement?.parentElement?.className,viewport:innerWidth,inline:el.parentElement?.parentElement?.getAttribute('style'),position:getComputedStyle(el).position,width:getComputedStyle(el).width,activity:el.getBoundingClientRect().width,rail:getComputedStyle(document.querySelector('.navigation-rail')).display}));
   assert.ok(Math.abs(panel.x)<1&&Math.abs(panel.width-width)<1,`窄屏预览进入单面板布局，实际 ${panel.x}/${panel.width}，布局 ${JSON.stringify(layout)}`);
   if(width===390)await page.screenshot({path:join(output,`${name}-preview-mobile.png`),timeout:20000,animations:'disabled'});
  }
 }
 await page.setViewportSize({width:1440,height:900});
 // 窄屏切回桌面会更新 React 布局并播放宽度过渡，先等面板稳定再记录拖拽起点。
 await page.waitForFunction(()=>{
  const panel=document.querySelector('.activity.inspector');
  return panel?.getBoundingClientRect().width>=450 && !panel.getAnimations().some(animation=>animation.playState==='running');
 });
 const previewGutter=await page.locator('#gutter-right').boundingBox();
 const previewWidthBefore=await page.locator('.activity.inspector').evaluate(el=>el.getBoundingClientRect().width);
 await page.mouse.move(previewGutter.x+5,300);await page.mouse.down();await page.mouse.move(previewGutter.x-35,300);await page.mouse.up();
 await page.waitForFunction(before=>document.querySelector('.activity.inspector').getBoundingClientRect().width>=before+35,previewWidthBefore);
 const previewWidthAfter=await page.locator('.activity.inspector').evaluate(el=>el.getBoundingClientRect().width);
 assert.ok(previewWidthAfter>=previewWidthBefore+35,`预览拖拽从当前自适应宽度连续调整：${previewWidthBefore} → ${previewWidthAfter}，手柄 ${JSON.stringify(previewGutter)}`);
 await page.locator('.preview-wrap').click();
 assert.equal(await page.locator('#preview-body').evaluate(el=>getComputedStyle(el).whiteSpace),'pre-wrap','用户可切换源码自动换行');
 await page.reload();await page.locator('#run-state').waitFor();
 for(const [patch,tone,label] of [
  [{running:false,lastOutcome:'completed',queueHeld:false,pendingApprovals:{}},'ready','就绪'],
  [{running:true,lastOutcome:'',pendingApprovals:{},lastEventAt:Date.now()},'running','正在思考'],
  [{pendingApprovals:{approval:{}}},'attention','等待批准'],
  [{pendingApprovals:{},lastEventAt:Date.now()-90000},'attention','暂无新进展'],
  [{running:false,queueHeld:true},'paused','已暂停'],
  [{queueHeld:false,lastOutcome:'NEED_INPUT'},'attention','等待补充'],
  [{lastOutcome:'HTTP_502'},'error','运行失败']]){
  await page.evaluate(patch=>store.setState(patch),patch);
  await page.waitForFunction(({tone,label})=>document.getElementById('run-state').dataset.tone===tone&&document.getElementById('run-state').textContent.includes(label),{tone,label});
  await page.screenshot({path:join(output,`${name}-state-${tone}-${label}.png`),timeout:20000,animations:'disabled'});
  await page.setViewportSize({width:390,height:900});
  assert.ok(await page.locator('.run-state-label').evaluate(el=>el.scrollWidth<=el.clientWidth),`状态文字在窄屏被截断: ${label}`);
  await page.setViewportSize({width:1440,height:900});
 }
 await page.evaluate(()=>store.setState({lastOutcome:'completed'}));
 await page.locator('#input-flow').fill('继续检查');assert.equal(await page.locator('#send').isDisabled(),false);
 await page.evaluate(async()=>{await Promise.allSettled(document.getAnimations().filter(a=>a.effect.getComputedTiming().iterations!==Infinity).map(a=>a.finished));await new Promise(requestAnimationFrame);});
 assert.equal(await page.locator('.composer').evaluate(el=>getComputedStyle(el).borderTopWidth),'1px');
 await page.screenshot({path:join(output,`${name}-composer-focus.png`),timeout:20000,animations:'disabled'});
 await page.locator('#input-flow').fill('');assert.equal(await page.locator('#send').isDisabled(),true);
 if(await page.locator('#sidebar-toggle').getAttribute('aria-expanded')!=='true')await page.locator('#sidebar-toggle').click();
 await page.locator('.session-more').first().focus();await page.keyboard.press('Enter');await page.getByRole('menu').waitFor();await page.keyboard.press('Escape');
 await page.emulateMedia({reducedMotion:'reduce'});await page.evaluate(()=>store.setState({running:true,lastEventAt:Date.now()}));
 assert.equal(await page.locator('#run-state').evaluate(el=>getComputedStyle(el,'::before').animationName),'none');
 // 状态文字在两套背景上均需可读，包含 color-mix 后的实际渲染颜色。
 for(const theme of ['dark','light'])for(const tone of ['ready','running','attention','error','paused']){
  const ratio=await page.evaluate(async({theme,tone})=>{
   store.getState().setTheme(theme);await new Promise(requestAnimationFrame);
   const el=document.querySelector('#run-state');el.dataset.tone=tone;
   const ctx=document.createElement('canvas').getContext('2d');
   const luminance=color=>{
    ctx.clearRect(0,0,1,1);ctx.fillStyle=color;ctx.fillRect(0,0,1,1);
    return [...ctx.getImageData(0,0,1,1).data].slice(0,3).map(v=>v/255)
     .map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4)
     .reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0);
   };
   const css=getComputedStyle(el),a=luminance(css.color),b=luminance(css.backgroundColor);
   return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);
  },{theme,tone});
  assert.ok(ratio>=4.5,`${theme}/${tone} contrast ${ratio.toFixed(2)} < 4.5`);
 }
 assert.deepEqual(errors,[]);console.log(name+': PASS themes/responsive/status/approval/failure/stall/focus/disabled/menu/reduced-motion/contrast');
}
try{
 if(process.argv.includes('--serve')){console.log(url);await new Promise(()=>{});}
 browser=await chromium.launch({channel:'chrome',headless:true});await run(await browser.newPage(),'chromium');
 const touchContext=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true});
 const touchPage=await touchContext.newPage();await touchPage.route('**/api/**',route=>route.fulfill({json:{}}));await touchPage.goto(url);
 await touchPage.evaluate(()=>store.getState().setUi({inspectorOpen:true,inspectorTab:'preview'}));
 await touchPage.locator('#panel-tabs').waitFor();
 assert.ok((await touchPage.getByRole('tab',{name:'变更',exact:true}).boundingBox()).height>=44,'触屏设备保留可见面板名称和足够的点击面积');
 await touchPage.getByRole('button',{name:'更多工具',exact:true}).click();
 const moreBox=await touchPage.getByRole('menu',{name:'更多工具'}).boundingBox();
 assert.ok(moreBox.x>=0&&moreBox.x+moreBox.width<=390,'触屏更多工具菜单保持在视口内');
 assert.equal(await touchPage.getByRole('menuitem',{name:'能力',exact:true}).count(),1,'触屏仍能访问低频工具');
 await touchPage.screenshot({path:join(output,'chromium-touch-preview.png'),timeout:20000,animations:'disabled'});
 await touchPage.getByRole('menuitem',{name:'能力',exact:true}).click();
 assert.equal(await touchPage.evaluate(()=>store.getState().ui.inspectorTab),'capabilities','触屏菜单可以切换工具面板');
 await touchContext.close();await browser.close();browser=null;
 if(!process.argv.includes('--capture-only')){
  const main=join(output,'main.cjs');await writeFile(main,`const {app,BrowserWindow}=require('electron');app.whenReady().then(()=>{let w=new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}});w.loadURL(${JSON.stringify(url)});});`);
  const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;electron=await _electron.launch({executablePath:electronBinary(),args:[main],env});await run(await electron.firstWindow(),'electron');
 }
 console.log('Screenshots:',output);
}finally{await browser?.close();await electron?.close();await new Promise(resolve=>server.httpServer.close(resolve));}
