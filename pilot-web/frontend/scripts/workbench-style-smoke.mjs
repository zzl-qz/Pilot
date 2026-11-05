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
  await page.screenshot({path:join(output,`${name}-${theme}-${width}.png`)});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  const box=await page.locator('#run-state').boundingBox();assert.ok(box.width>32&&box.x+box.width<=width);
  const composer=await page.locator('.composer').boundingBox();assert.ok(composer.x>=0&&composer.y+composer.height<=900);
 }
 if(process.argv.includes('--capture-only'))return;
 await page.setViewportSize({width:1440,height:900});await page.goto(url);await page.locator('#run-state').waitFor();
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
  await page.screenshot({path:join(output,`${name}-state-${tone}-${label}.png`)});
  await page.setViewportSize({width:390,height:900});
  assert.ok(await page.locator('.run-state-label').evaluate(el=>el.scrollWidth<=el.clientWidth),`状态文字在窄屏被截断: ${label}`);
  await page.setViewportSize({width:1440,height:900});
 }
 await page.evaluate(()=>store.setState({lastOutcome:'completed'}));
 await page.locator('#input-flow').fill('继续检查');assert.equal(await page.locator('#send').isDisabled(),false);
 await page.evaluate(async()=>{await Promise.allSettled(document.getAnimations().filter(a=>a.effect.getComputedTiming().iterations!==Infinity).map(a=>a.finished));await new Promise(requestAnimationFrame);});
 assert.equal(await page.locator('.composer').evaluate(el=>getComputedStyle(el).borderTopWidth),'1px');
 await page.screenshot({path:join(output,`${name}-composer-focus.png`)});
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
 browser=await chromium.launch({channel:'chrome',headless:true});await run(await browser.newPage(),'chromium');await browser.close();browser=null;
 if(!process.argv.includes('--capture-only')){
  const main=join(output,'main.cjs');await writeFile(main,`const {app,BrowserWindow}=require('electron');app.whenReady().then(()=>{let w=new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}});w.loadURL(${JSON.stringify(url)});});`);
  const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;electron=await _electron.launch({executablePath:electronBinary(),args:[main],env});await run(await electron.firstWindow(),'electron');
 }
 console.log('Screenshots:',output);
}finally{await browser?.close();await electron?.close();await new Promise(resolve=>server.httpServer.close(resolve));}
