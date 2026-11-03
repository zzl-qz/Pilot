import { createServer, build, preview } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-schedules-ui-'));
const fixture = `<html><head><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react'; import {createRoot} from 'react-dom/client'; import App from '/src/components/App.jsx'; import {useStore} from '/src/store.js'; import {api} from '/src/api.js';
import '/src/styles.css'; import '/src/shell-vanilla.css'; import '/src/styles-addendum.css'; import '/src/workbench.css';
document.documentElement.dataset.theme='dark'; window.store=useStore; window.jobs=[]; window.calls=[]; window.fail=false;
for(const name of Object.keys(api)) if(typeof api[name]==='function') api[name]=async()=>({});
api.schedules=async()=>{if(window.fail)throw Error('模拟连接断开');return structuredClone(window.jobs);};
api.createSchedule=async(body)=>{calls.push(body);const row={...body,id:'schedule-'+calls.length,sessionId:'scheduled-session',state:'SCHEDULED',message:'等待执行',history:[]};jobs.unshift(row);return row;};
api.controlSchedule=async(id,body)=>{const row=jobs.find(j=>j.id===id);row.state={pause:'PAUSED',resume:'SCHEDULED',cancel:'CANCELLED'}[body.action]||row.state;if(body.runAt)row.runAt=body.runAt;return row;};
api.sessions=async()=>[{sessionId:'source',title:'Pilot 调度开发',workspace:'/workspace/pilot'}];
api.worktreeInfo=async()=>({});api.context=async()=>({});api.queue=async()=>({items:[]});api.filesFlat=async()=>({files:[]});
useStore.setState({init:async()=>{},activate:async(id)=>{window.activated=id;},sessionId:'source',workspace:'/workspace/pilot',sessionTitle:'Pilot 调度开发',sessions:[{id:'source',title:'Pilot 调度开发',workspace:'/workspace/pilot'}],projects:[]});
if(new URL(location.href).searchParams.has('preview')) {
const at=new Date(Date.now()+3600000).toISOString();jobs.push({id:'preview-1',name:'检查依赖升级并修复测试',content:'参考成熟开源 agent 的调度设计，修复项目中的重启恢复问题。',state:'SCHEDULED',runAt:at,timezone:'Asia/Shanghai',message:'等待执行',sessionId:'scheduled-session',requirements:{automation:{fullAccess:true}},history:[]},{id:'preview-2',name:'跟进 CI 并整理验证结果',content:'检查 CI 状态，修复失败测试并验证。',state:'WAITING',runAt:at,timezone:'Asia/Shanghai',message:'等待 CI 完成，稍后唤醒',sessionId:'scheduled-session',history:[{runId:'r1',startedAt:new Date().toISOString(),status:'WAITING'}]},{id:'preview-3',name:'核对文件同步的跨平台行为',content:'核对 Windows、macOS 和 Linux 的路径与失败反馈。',state:'NEEDS_ATTENTION',runAt:at,timezone:'Asia/Shanghai',message:'当前环境缺少验证工具，请查看执行记录',sessionId:'scheduled-session',history:[]});
useStore.getState().setUi({schedulesOpen:true});}
createRoot(document.getElementById('root')).render(React.createElement(App));
</script></body></html>`;
let server;
let entry = '__schedules';
if(process.argv.includes('--production')) {
  entry = `.schedule-fixture-${process.pid}.html`;
  const file = join(root, entry);
  try {
    await writeFile(file, fixture);
    await build({root,build:{outDir:output,emptyOutDir:false,rollupOptions:{input:file}}});
  } finally { await rm(file,{force:true}); }
  const production = await preview({root,build:{outDir:output},preview:{host:'127.0.0.1',port:0,proxy:{}}});
  server = {httpServer:production.httpServer,listen:async()=>{},close:()=>new Promise(resolve=>production.httpServer.close(resolve))};
} else server = await createServer({root,server:{host:'127.0.0.1',port:0,proxy:{}},plugins:[{name:'schedule-fixture',configureServer(server){server.middlewares.use('/__schedules',async(req,res)=>{res.setHeader('Content-Type','text/html');res.end(await server.transformIndexHtml('/__schedules',fixture));});}}]});
let browser, electron;
async function run(page, name, url) {
  page.setDefaultTimeout(8000); const errors=[]; page.on('pageerror',e=>errors.push(e.message));
  await page.setViewportSize({width:1440,height:900}); await page.goto(url);
  await page.getByRole('button',{name:'定时任务',exact:true}).click();
  await page.getByRole('heading',{name:'安排下一件事'}).waitFor();
  await page.getByRole('button',{name:'创建第一个定时任务'}).click();
  await page.getByLabel('任务名称',{exact:true}).fill('升级依赖并验证');
  await page.getByLabel('任务目标',{exact:true}).fill('参考开源实现升级依赖，修复测试。');
  await page.getByLabel('验收条件',{exact:true}).fill('相关测试通过\n说明复用理由');
  await page.getByRole('button',{name:'10 分钟后'}).click();
  await page.getByRole('button',{name:'创建计划',exact:true}).click();
  await page.getByRole('heading',{name:'升级依赖并验证',exact:true}).waitFor();
  const request=await page.evaluate(()=>calls[0]);
  assert.equal(request.requirements.acceptance.length,2); assert.equal(request.fullAccess,true); assert.ok(new Date(request.runAt)>new Date());
  const changed = new Date(Date.now()+7200000);
  const local = await page.evaluate(at=>{const d=new Date(at);return [d.getFullYear(),String(d.getMonth()+1).padStart(2,'0'),String(d.getDate()).padStart(2,'0')].join('-')+'T'+[d.getHours(),d.getMinutes()].map(v=>String(v).padStart(2,'0')).join(':');},changed.toISOString());
  await page.getByLabel(/^修改执行时间/).fill(local);
  await page.getByRole('button',{name:'保存时间',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.schedule-reschedule input').value==='');
  assert.equal(await page.evaluate(()=>jobs[0].runAt),new Date(local).toISOString());
  await page.getByRole('button',{name:'暂停任务',exact:true}).click();
  await page.getByRole('button',{name:'恢复计划',exact:true}).click();
  await page.getByRole('button',{name:'取消任务',exact:true}).click();
  await page.waitForFunction(()=>jobs[0].state==='CANCELLED');
  await page.getByRole('button',{name:'打开执行对话',exact:true}).click();
  assert.equal(await page.evaluate(()=>window.activated),'scheduled-session');
  await page.locator('#input-flow').fill('稍后检查项目');
  await page.getByRole('button',{name:'定时执行',exact:true}).click();
  assert.equal(await page.getByLabel('任务目标',{exact:true}).inputValue(),'稍后检查项目');
  await page.keyboard.press('Escape');
  assert.equal(await page.locator('#input-flow').innerText(),'稍后检查项目');
  await page.getByRole('button',{name:'定时任务',exact:true}).click();
  await page.evaluate(()=>{window.fail=true;}); await page.getByRole('button',{name:'刷新',exact:true}).click();
  await page.getByRole('alert').filter({hasText:'模拟连接断开'}).waitFor();
  await page.evaluate(()=>{window.fail=false;}); await page.getByRole('button',{name:'重新加载'}).click();
  await page.waitForFunction(()=>!document.querySelector('.schedule-error'));
  for(const width of [1440,768,390]) {
    await page.setViewportSize({width,height:900}); await page.goto(url+'?preview=1');
    await page.locator('.schedule-row').first().waitFor();
    assert.ok(await page.locator('dialog').evaluate(el=>el.scrollWidth<=el.clientWidth+1));
    await page.screenshot({path:join(output,`${name}-${width}-list.png`)});
    await page.locator('.schedule-row').first().click();
    await page.screenshot({path:join(output,`${name}-${width}-detail.png`)});
    await page.getByRole('button',{name:'新建任务',exact:true}).click();
    assert.ok(await page.locator('dialog').evaluate(el=>el.scrollWidth<=el.clientWidth+1));
    await page.screenshot({path:join(output,`${name}-${width}-form.png`)});
  }
  await page.evaluate(()=>document.documentElement.dataset.theme='light');
  await page.screenshot({path:join(output,`${name}-390-light.png`)});
  assert.deepEqual(errors,[]); console.log(`${name}: PASS create/reschedule/pause/resume/cancel/results/draft/error/responsive`);
}
try {
  await server.listen();const url=`http://127.0.0.1:${server.httpServer.address().port}/${entry}`;
  if(process.argv.includes('--serve')) {console.log(url+'?preview=1');await new Promise(()=>{});}
  browser=await chromium.launch({channel:'chrome',headless:true}); await run(await browser.newPage(),'chromium',url); await browser.close();browser=null;
  const main=join(output,'main.cjs'); await writeFile(main,`const {app,BrowserWindow}=require('electron');app.whenReady().then(()=>{let w=new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}});w.loadURL(${JSON.stringify(url)});});`);
  const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;
  electron=await _electron.launch({executablePath:electronBinary(),args:[main],env});await run(await electron.firstWindow(),'electron',url);
  console.log('Screenshots:',output);
} finally {await browser?.close();await electron?.close();await server.close();}
