import { createServer, build, preview } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-results-ui-'));
const fixture = `<html><head><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react'; import {createRoot} from 'react-dom/client'; import App from '/src/components/App.jsx'; import {useStore} from '/src/store.js'; import {api} from '/src/api.js';
import '/src/styles.css'; import '/src/shell-vanilla.css'; import '/src/styles-addendum.css'; import '/src/workbench.css';
document.documentElement.dataset.theme='dark'; window.store=useStore; window.calls=[]; window.fail=false; window.previewFail=false; window.handoffFail=false; window.pendingDetail=null;
for(const name of Object.keys(api)) if(typeof api[name]==='function') api[name]=async()=>({});
const at='2026-11-03T03:00:00Z';
window.rows=[{id:'schedule-1',revision:'v1',sessionId:'scheduled-session',title:'修复调度恢复并完成验证',source:'SCHEDULE',state:'COMPLETED',summary:'复用现有投递链路，修复任务重启后的恢复逻辑。相关测试通过，改动保存在独立工作区。',at,runId:'run-1',scheduleId:'schedule-1',read:false,handled:false,changedFiles:['src/schedule/dispatch.java','src/schedule/recovery.java'],checks:['恢复流程与重复投递测试通过'],openSteps:[],workspace:{mode:'ISOLATED',path:'/workspace/pilot-worktrees/任务 01',base:'/workspace/pilot',commit:'a46f98117f',handedOffAt:null}},
{id:'run-2',revision:'v2',sessionId:'session-2',title:'核对跨平台文件行为',source:'CHAT',state:'NEEDS_ATTENTION',summary:'环境缺少 Windows 运行器，保留进度等待补充验证。',at,runId:'run-2',read:false,handled:false,changedFiles:[],checks:[],openSteps:['Windows 实机验证'],workspace:null}];
window.research=[{id:'research-1',sources:['https://github.com/OpenHands/OpenHands','javascript:alert(1)'],decision:'沿用已有执行器与 worktree；参考独立执行上下文，只补结果投递与恢复边界。',license:'未复制外部源码；复用前核对具体依赖许可证。',sourceEvidence:['e_fetch_1'],observedAt:1793674800000,structured:true,raw:''}];
api.results=async()=>{if(window.fail)throw Error('模拟连接断开');return {items:structuredClone(window.rows),truncated:false};};
api.result=async(id)=>{if(window.pendingDetail)await window.pendingDetail;return {item:structuredClone(rows.find(i=>i.id===id)),research:id==='schedule-1'?research:[],verification:id==='schedule-1'?'VERIFIED':'UNKNOWN',acceptance:[{text:'恢复后不重复执行',checked:true,evidence:'单元测试通过'}]};};
api.resultReceipt=async(id,body)=>{calls.push({type:'receipt',id,...body});const row=rows.find(i=>i.id===id);if(body.revision!==row.revision)throw Error('结果已更新，请刷新');if(body.action==='read')row.read=true;if(body.action==='unread')row.read=false;if(body.action==='handle'){row.read=true;row.handled=true;}if(body.action==='reopen'){row.read=true;row.handled=false;}return structuredClone(row);};
api.scheduleHandoffPreview=async()=>{calls.push({type:'preview'});return {base:'/workspace/pilot',path:rows[0].workspace.path,commit:'a46f98117f',files:['src/schedule/dispatch.java'],diff:'diff --git a/src/schedule/dispatch.java b/src/schedule/dispatch.java\\n- duplicate();\\n+ claimOnce();',revision:'patch-v1',truncated:false,conflict:window.previewFail?'原目录存在冲突，尚未交回任何改动':''};};
api.scheduleHandoff=async(id,body)=>{calls.push({type:'handoff',id,...body});if(window.handoffFail)throw Error('预览后文件已变化，请重新预览再交回');rows[0].workspace.handedOffAt=at;return {};};
api.sessionResearch=async()=>research;api.runChanges=async()=>({files:[{diff:'- old\\n+ new'}]});
api.schedules=async()=>[];api.sessions=async()=>[{sessionId:'source',title:'Pilot 调度开发',workspace:'/workspace/pilot'}];
api.worktreeInfo=async()=>({});api.context=async()=>({});api.queue=async()=>({items:[]});api.filesFlat=async()=>({files:[]});
useStore.setState({init:async()=>{},activate:async(id)=>{window.activated=id;},sessionId:'source',workspace:'/workspace/pilot',sessionTitle:'Pilot 调度开发',sessions:[{id:'source',title:'Pilot 调度开发',workspace:'/workspace/pilot'}],projects:[]});
if(new URL(location.href).searchParams.has('preview'))useStore.getState().setUi({resultsOpen:true});
createRoot(document.getElementById('root')).render(React.createElement(App));
</script></body></html>`;
let server;
let entry = '__results';
if(process.argv.includes('--production')) {
  entry = `.result-fixture-${process.pid}.html`;
  const file = join(root, entry);
  try {
    await writeFile(file, fixture);
    await build({root,build:{outDir:output,emptyOutDir:false,rollupOptions:{input:file}}});
  } finally { await rm(file,{force:true}); }
  await cp(join(root,'../src/main/resources/static/fonts'),join(output,'fonts'),{recursive:true});
  const production = await preview({root,build:{outDir:output},preview:{host:'127.0.0.1',port:0,proxy:{}}});
  server = {httpServer:production.httpServer,listen:async()=>{},close:()=>new Promise(resolve=>production.httpServer.close(resolve))};
} else server = await createServer({root,server:{host:'127.0.0.1',port:0,proxy:{}},plugins:[{name:'result-fixture',configureServer(server){server.middlewares.use('/__results',async(req,res)=>{res.setHeader('Content-Type','text/html');res.end(await server.transformIndexHtml('/__results',fixture));});}}]});
let browser, electron;
async function run(page, name, url) {
  page.setDefaultTimeout(8000); const errors=[]; page.on('pageerror',e=>errors.push(e.message));
  await page.setViewportSize({width:1440,height:900}); await page.goto(url);
  await page.getByRole('button',{name:/任务收件箱/}).click();
  await page.locator('.result-row').first().waitFor(); assert.equal(await page.locator('.result-row').count(),2);
  await page.locator('.result-row').first().click();
  await page.getByRole('heading',{name:'修复调度恢复并完成验证',exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>rows[0].read),true); assert.equal(await page.evaluate(()=>rows[0].handled),false);
  assert.equal(await page.locator('a[href^="javascript:"]').count(),0);
  assert.equal(await page.locator('.research-sources a').count(),1);
  await page.getByRole('button',{name:'标记未读',exact:true}).click();
  await page.getByRole('button',{name:'标记已读',exact:true}).waitFor(); assert.equal(await page.evaluate(()=>rows[0].read),false);
  await page.getByRole('button',{name:'标记已处理',exact:true}).click();
  await page.getByRole('button',{name:'重新打开',exact:true}).waitFor(); assert.equal(await page.evaluate(()=>rows[0].handled),true);
  await page.getByRole('button',{name:'已处理',exact:true}).click(); assert.equal(await page.locator('.result-row').count(),1);
  await page.locator('.result-row').first().click(); await page.getByRole('button',{name:'重新打开',exact:true}).click();
  await page.getByRole('button',{name:'标记已处理',exact:true}).waitFor();
  await page.getByRole('button',{name:'预览交回改动',exact:true}).click(); await page.getByLabel('交回差异',{exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.type==='handoff').length),0);
  await page.evaluate(()=>{window.handoffFail=true;}); await page.getByRole('button',{name:'交回原目录',exact:true}).click();
  await page.getByRole('alert').filter({hasText:'预览后文件已变化'}).waitFor(); assert.equal(await page.locator('.result-handoff').count(),0);
  await page.evaluate(()=>{window.handoffFail=false;window.previewFail=true;}); await page.getByRole('button',{name:'预览交回改动',exact:true}).click();
  await page.getByRole('alert').filter({hasText:'原目录存在冲突'}).waitFor(); assert.equal(await page.getByRole('button',{name:'交回原目录',exact:true}).isDisabled(),true);
  await page.evaluate(()=>{window.previewFail=false;}); await page.getByRole('button',{name:'预览交回改动',exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('.result-handoff [role=alert]'));
  await page.getByRole('button',{name:'交回原目录',exact:true}).click(); await page.getByRole('status').filter({hasText:'交回；执行目录仍保留'}).waitFor();
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.type==='handoff').at(-1).revision),'patch-v1');
  await page.getByRole('button',{name:'查看本次运行差异'}).click(); await page.getByLabel('运行差异',{exact:true}).waitFor();
  await page.getByRole('button',{name:'打开执行对话',exact:true}).click(); assert.equal(await page.evaluate(()=>window.activated),'scheduled-session');
  await page.getByRole('button',{name:/任务收件箱/}).click();
  await page.evaluate(()=>{window.fail=true;}); await page.getByRole('button',{name:'刷新',exact:true}).click();
  await page.getByRole('alert').filter({hasText:'模拟连接断开'}).waitFor();
  await page.evaluate(()=>{window.fail=false;}); await page.getByRole('button',{name:'刷新',exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('.schedule-error'));
  // A slow result must not replace the newer selection.
  await page.evaluate(()=>{window.pendingDetail=new Promise(resolve=>window.resolveDetail=resolve);});
  await page.locator('.result-row').first().click(); await page.locator('.result-row').last().click();
  await page.evaluate(()=>{window.resolveDetail();window.pendingDetail=null;});
  await page.getByRole('heading',{name:'核对跨平台文件行为',exact:true}).waitFor();
  assert.equal(await page.locator('.result-detail h2').innerText(),'核对跨平台文件行为');
  await page.keyboard.press('Escape');
  await page.evaluate(()=>store.getState().setUi({inspectorOpen:true,inspectorTab:'research'}));
  await page.getByText('当前任务的调研依据',{exact:true}).waitFor();
  for(const width of [1440,768,390]) {
    await page.setViewportSize({width,height:900}); await page.goto(url+'?preview=1');
    await page.locator('.result-row').first().waitFor();
    assert.ok(await page.locator('dialog').evaluate(el=>el.scrollWidth<=el.clientWidth+1));
    await page.screenshot({path:join(output,`${name}-${width}-list.png`)});
    await page.locator('.result-row').first().click(); await page.locator('.result-detail h2').waitFor();
    assert.ok(await page.locator('dialog').evaluate(el=>el.scrollWidth<=el.clientWidth+1));
    await page.screenshot({path:join(output,`${name}-${width}-detail.png`)});
  }
  await page.evaluate(()=>document.documentElement.dataset.theme='light'); await page.screenshot({path:join(output,`${name}-390-light.png`)});
  assert.deepEqual(errors,[]); console.log(`${name}: PASS receipts/filter/research/handoff/conflict/stale-preview/stale-response/error/responsive`);
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
