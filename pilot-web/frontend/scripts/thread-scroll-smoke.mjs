import assert from 'node:assert/strict';
import { build, preview } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// 真实 App / reducer / store，历史接口只返回隔离夹具，不连接模型或用户后台。
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-thread-scroll-'));
const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Pilot 历史滚动回归</title><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';import {createRoot} from 'react-dom/client';import App from '/src/components/App.jsx';import {useStore} from '/src/store.js';import {api} from '/src/api.js';import {applyEvent,initialThread} from '/src/thread-reducer.js';
import '/src/styles.css';import '/src/shell-vanilla.css';import '/src/styles-addendum.css';import '/src/workbench.css';
(async()=>{
document.documentElement.dataset.theme='dark';window.store=useStore;window.api=api;window.historyCalls=[];window.events=[];
const mode=new URL(location.href).searchParams.get('mode')||'long';window.failEarlier=mode==='failure';
for(const name of Object.keys(api))if(typeof api[name]==='function')api[name]=async()=>({});
api.workProducts=api.skillDrafts=api.schedules=api.bookmarks=async()=>[];api.queue=async()=>({items:[]});api.filesFlat=async()=>({files:[]});
api.history=async(_id,options={})=>{
  historyCalls.push(options.before??null);await new Promise(resolve=>setTimeout(resolve,20));
  if(options.before&&window.failEarlier)throw Error('测试历史暂不可用');
  const matching=events.filter(event=>options.before==null||event.sequence<options.before);
  const page=matching.slice(-(options.limit||80));return {events:page,hasMore:matching.length>page.length};
};
useStore.setState({init:async()=>{},sessionId:'scroll-test',sessionTitle:'检查长回复和更早的问题',workspace:'/workspace/pilot',thread:initialThread(),projects:[]});
window.send=(type,payload={},runId='latest')=>{const event={type,payload,runId,sequence:events.length+1,timestamp:Date.now()};events.push(event);useStore.setState(draft=>applyEvent(draft,event,{live:true}));};
send('run.started',{input:'之前的第一个问题'},'old');send('assistant.delta',{text:'之前的回答仍可查看。'},'old');send('run.completed',{summary:'之前的回答仍可查看。'},'old');
if(mode==='long'){
  send('run.started',{input:'当前的长回复问题'});
  for(let i=0;i<330;i++)send('assistant.delta',{text:i%10===0?'\\n\\n第'+(i/10+1)+'段：检查长回复、事件窗口和历史消息。':'保留完整问答。'});
  send('run.completed',{summary:'完整结论。'.repeat(70)});
}else{
  // 连续多页没有可见行；缓冲插入不改变 rows.length，也必须继续取下一页。
  for(let i=0;i<240;i++)send('execution.updated');
  if(mode!=='empty')send('run.completed',{summary:'最后一条结束记录'});
  await useStore.getState().loadHistory();
}
createRoot(document.getElementById('root')).render(React.createElement(App));
})();
</script></body></html>`;
const entry = `.thread-scroll-fixture-${process.pid}.html`, file = join(root,entry);
try {
  await writeFile(file,fixture);
  await build({root,build:{outDir:output,emptyOutDir:false,rollupOptions:{input:file}}});
} finally { await rm(file,{force:true}); }
await cp(join(root,'../src/main/resources/static/fonts'),join(output,'fonts'),{recursive:true});
const server = await preview({root,build:{outDir:output},preview:{host:'127.0.0.1',port:0,proxy:{}}});
const url = `http://127.0.0.1:${server.httpServer.address().port}/${entry}`;
let browser, electron;
async function run(page, name) {
  page.setDefaultTimeout(10000);
  const errors = [];page.on('pageerror', error => errors.push(error.message));
  await page.setViewportSize({ width: 1280, height: 840 });
  await page.goto(url);
  await page.locator('.thread').waitFor();
  await page.evaluate(() => document.fonts.ready);
  assert.equal(await page.evaluate(() => store.getState().thread.rows.some(row=>row.text==='当前的长回复问题')), true, '长回复收尾保留本轮提问');
  assert.ok(await page.evaluate(() => store.getState().thread.windowEvents.length)>0, '收尾保留有效翻页游标');
  await page.waitForFunction(() => {const el=document.querySelector('.thread');return el.scrollTop>500;});
  const before=await page.locator('.thread').evaluate(el=>el.scrollTop);
  await page.locator('.thread').hover();await page.mouse.wheel(0,-500);
  await page.waitForFunction(before=>document.querySelector('.thread').scrollTop<before-200,before);
  await page.waitForTimeout(250);
  const reading=await page.locator('.thread').evaluate(el=>el.scrollTop);
  await page.evaluate(()=>send('assistant.delta',{text:'\\n继续输出。'.repeat(80)}));
  await page.waitForTimeout(200);
  const after=await page.locator('.thread').evaluate(el=>el.scrollTop);
  assert.ok(Math.abs(after-reading)<3,'向上阅读不被新增内容拉回底部: '+reading+' → '+after);
  await page.mouse.wheel(0,-100000);
  await page.waitForFunction(()=>store.getState().thread.rows.some(row=>row.text==='之前的第一个问题'));
  await page.mouse.wheel(0,-100000);
  await page.waitForFunction(()=>document.querySelector('.thread').scrollTop<5);
  await page.waitForTimeout(250);
  assert.ok(await page.getByText('之前的第一个问题',{exact:true}).isVisible());
  await page.screenshot({path:join(output,name+'-earlier.png'),animations:'disabled'});
  await page.getByRole('button',{name:'回到底部',exact:true}).click();
  await page.waitForFunction(()=>{const el=document.querySelector('.thread');return el.scrollHeight-el.clientHeight-el.scrollTop<5;});

  await page.goto(url+'?mode=sparse');
  await page.waitForFunction(()=>store.getState().thread.rows.some(row=>row.text==='之前的第一个问题'));
  assert.equal(await page.evaluate(()=>store.getState().thread.history.hasMore),false,'不足一屏时跨过全部不可见事件页');
  assert.ok(await page.evaluate(()=>historyCalls.length)<10,'分页请求有界');
  assert.equal(await page.evaluate(()=>store.getState().thread.windowEvents[0].sequence),1);

  await page.goto(url+'?mode=empty');
  await page.waitForFunction(()=>store.getState().thread.rows.some(row=>row.text==='之前的第一个问题'));
  assert.equal(await page.evaluate(()=>store.getState().thread.history.hasMore),false,'末页全是不可见事件时仍能加载历史');

  await page.goto(url+'?mode=failure');
  const retry=page.getByRole('button',{name:'加载更早失败，点击重试',exact:true});await retry.waitFor();
  const failedCount=await page.evaluate(()=>historyCalls.length);await page.waitForTimeout(400);
  assert.equal(await page.evaluate(()=>historyCalls.length),failedCount,'失败等待重试，不循环打接口');
  await page.evaluate(()=>window.failEarlier=false);await retry.click();
  await page.waitForFunction(()=>store.getState().thread.rows.some(row=>row.text==='之前的第一个问题'));
  assert.equal(await page.evaluate(()=>store.getState().thread.history.error),false);
  for(const width of [1440,768,390]){
    await page.setViewportSize({width,height:840});await page.goto(url);await page.locator('.thread').waitFor();
    await page.waitForFunction(()=>{const el=document.querySelector('.thread');return el.scrollTop>500;});
    // 直接聚焦当前窗口顶部的入口，不触发滚轮自动分页。
    await page.getByRole('button',{name:'加载更早消息',exact:true}).waitFor();
    // 显式入口也能通过键盘操作，适用于没有滚轮的设备。
    const more=page.getByRole('button',{name:'加载更早消息',exact:true});await more.focus();await page.keyboard.press('Enter');
    await page.waitForFunction(()=>store.getState().thread.rows.some(row=>row.text==='之前的第一个问题'));
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'无页面横向溢出');
    await page.screenshot({path:join(output,name+'-'+width+'.png'),animations:'disabled'});
  }
  assert.deepEqual(errors,[]);
  console.log(name+': PASS 长回复收尾 / 滚轮上下翻页 / 阅读中增量 / 回到底部 / 跨不可见页和空行窗口 / 失败重试 / 键盘入口 / 三种宽度');
}
try{
  if(process.argv.includes('--serve')){console.log(url);await new Promise(()=>{});}
  browser=await chromium.launch({channel:'chrome',headless:true});await run(await browser.newPage(),'chromium');await browser.close();browser=null;
  if(!process.argv.includes('--web-only')){
    const main=join(output,'main.cjs');await writeFile(main,`const {app,BrowserWindow}=require('electron');app.setPath('userData',${JSON.stringify(join(output,'profile'))});app.whenReady().then(()=>new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}}).loadURL(${JSON.stringify(url)}));`);
    const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;
    electron=await _electron.launch({executablePath:electronBinary(),args:[main],env});await run(await electron.firstWindow(),'electron');
  }
  console.log('Screenshots:',output);
}finally{await browser?.close();await electron?.close();await new Promise(resolve=>server.httpServer.close(resolve));}
