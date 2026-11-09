import { checkDialogLayout } from './dialog-layout.mjs';
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
import React from 'react'; import {createRoot} from 'react-dom/client'; import App from '/src/components/App.jsx'; import {useStore} from '/src/store.js'; import {api} from '/src/api.js'; window.api=api;
import '/src/styles.css'; import '/src/shell-vanilla.css'; import '/src/styles-addendum.css'; import '/src/workbench.css';
document.documentElement.dataset.theme='dark'; window.store=useStore; window.calls=[]; window.fail=new URL(location.href).searchParams.has('failure'); window.previewFail=false; window.handoffFail=false; window.pendingDetail=null; window.verificationPhase='VERIFIED';
if(new URL(location.href).searchParams.has('no-anchor')){const supports=CSS.supports.bind(CSS);CSS.supports=(...args)=>args[0]==='anchor-name'?false:supports(...args);}
for(const name of Object.keys(api)) if(typeof api[name]==='function') api[name]=async()=>({});
const at='2026-11-03T03:00:00Z';
window.rows=[{id:'schedule-1',revision:'v1',sessionId:'scheduled-session',title:'修复调度恢复并完成验证',source:'SCHEDULE',state:'COMPLETED',summary:'复用现有投递链路，修复任务重启后的恢复逻辑。相关测试通过，改动保存在独立工作区。',at,runId:'run-1',scheduleId:'schedule-1',read:false,handled:false,changedFiles:['src/schedule/dispatch.java','src/schedule/recovery.java'],checks:['恢复流程与重复投递测试通过'],openSteps:[],workspace:{mode:'ISOLATED',path:'/workspace/pilot-worktrees/任务 01',base:'/workspace/pilot',commit:'a46f98117f',handedOffAt:null}},
{id:'run-2',revision:'v2',sessionId:'session-2',title:'核对跨平台文件行为',source:'CHAT',state:'NEEDS_ATTENTION',summary:'环境缺少 Windows 运行器，保留进度等待补充验证。',at,runId:'run-2',read:false,handled:false,changedFiles:[],checks:[],openSteps:['Windows 实机验证'],workspace:null}];
window.research=[{id:'research-1',sources:['https://github.com/OpenHands/OpenHands','javascript:alert(1)'],decision:'沿用已有执行器与 worktree；参考独立执行上下文，只补结果投递与恢复边界。',license:'未复制外部源码；复用前核对具体依赖许可证。',sourceEvidence:['e_fetch_1'],observedAt:1793674800000,structured:true,raw:''}];
api.results=async()=>{if(window.fail)throw Error('模拟连接断开');return {items:structuredClone(window.rows),truncated:false};};
api.result=async(id)=>{if(window.pendingDetail)await window.pendingDetail;return {item:structuredClone(rows.find(i=>i.id===id)),research:id==='schedule-1'?research:[],verification:id==='schedule-1'?window.verificationPhase:'UNKNOWN',acceptance:[{text:'恢复后不重复执行',checked:true,evidence:'单元测试通过'}]};};
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
  async function refreshResults() {
    await page.getByRole('button',{name:'结果操作',exact:true}).click();
    await page.getByRole('menuitem',{name:'刷新结果',exact:true}).click();
  }
  await page.setViewportSize({width:1440,height:900}); await page.goto(url);
  await page.getByRole('button',{name:/任务收件箱/}).click();
  await page.locator('.result-row').first().waitFor(); assert.equal(await page.locator('.result-row').count(),2);
  const menu = page.getByRole('button',{name:'结果操作',exact:true});
  await menu.focus(); await page.keyboard.press('ArrowDown');
  await page.waitForFunction(()=>document.activeElement?.getAttribute('role')==='menuitem'
    && document.activeElement.textContent.trim()==='刷新结果');
  await page.keyboard.press('Escape');
  await page.waitForFunction(()=>document.activeElement?.getAttribute('aria-label')==='结果操作' || document.activeElement?.textContent.includes('结果操作'));
  assert.equal(await page.locator('dialog[open]').count(),1,'菜单 Escape 不关闭外层收件箱');
  await page.getByRole('radio',{name:'待处理',exact:true}).focus(); await page.keyboard.press('ArrowRight');
  assert.equal(await page.getByRole('radio',{name:'未读',exact:true}).getAttribute('aria-checked'),'true');
  await page.keyboard.press('ArrowLeft');
  await page.locator('.result-row').first().click();
  await page.getByRole('heading',{name:'修复调度恢复并完成验证',exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>rows[0].read),true); assert.equal(await page.evaluate(()=>rows[0].handled),false);
  assert.equal(await page.locator('a[href^="javascript:"]').count(),0);
  assert.equal(await page.locator('.research-sources a').count(),1);
  await page.getByRole('button',{name:'标记未读',exact:true}).click();
  await page.getByRole('button',{name:'标记已读',exact:true}).waitFor(); assert.equal(await page.evaluate(()=>rows[0].read),false);
  await page.getByRole('button',{name:'标记已处理',exact:true}).click();
  await page.getByRole('button',{name:'重新打开',exact:true}).waitFor(); assert.equal(await page.evaluate(()=>rows[0].handled),true);
  await page.getByRole('radio',{name:'已处理',exact:true}).click(); assert.equal(await page.locator('.result-row').count(),1);
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
  await page.waitForFunction(()=>document.activeElement?.getAttribute('aria-label')?.includes('任务收件箱'));
  await page.getByRole('button',{name:/任务收件箱/}).click();
  await page.evaluate(()=>{window.fail=true;}); await refreshResults();
  await page.getByRole('alert').filter({hasText:'模拟连接断开'}).waitFor();
  await page.evaluate(()=>{window.fail=false;}); await page.getByRole('button',{name:'重新加载结果',exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('.results-error'));
  // A slow result must not replace the newer selection.
  await page.evaluate(()=>{window.pendingDetail=new Promise(resolve=>window.resolveDetail=resolve);});
  await page.locator('.result-row').first().click(); await page.locator('.result-row').last().click();
  await page.evaluate(()=>{window.resolveDetail();window.pendingDetail=null;});
  await page.getByRole('heading',{name:'核对跨平台文件行为',exact:true}).waitFor();
  assert.equal(await page.locator('.result-detail h2').innerText(),'核对跨平台文件行为');
  for (const [phase, label] of [['CHECKS_PASSED','记录的检查通过'],['CHECKS_FAILED','检查未通过'],['CHECKS_STALE','验证依据已变化，需重新验证'],['UNVERIFIED','尚未验证'],['COMPLETED','执行已结束'],['FUTURE_PHASE','验证状态未知'],['VERIFIED','目标核验通过']]) {
    await page.evaluate(value => { window.verificationPhase=value; }, phase);
    await page.locator('.result-row').last().click();
    await page.getByRole('heading',{name:'核对跨平台文件行为',exact:true}).waitFor();
    await page.locator('.result-row').first().click();
    await page.locator(`[data-verification-phase="${phase}"]`).waitFor();
    assert.equal(await page.locator('[data-verification-phase]').innerText(),label);
    if (phase !== 'VERIFIED') assert.equal(await page.getByText('目标核验通过',{exact:true}).count(),0);
  }
  await page.keyboard.press('Escape');
  await page.evaluate(()=>store.getState().setUi({inspectorOpen:true,inspectorTab:'research'}));
  await page.getByText('当前任务的调研依据',{exact:true}).waitFor();
  // 首次读取失败不能与真正的空列表混为一谈，重试成功后清除错误。
  await page.goto(url+'?preview=1&failure=1');
  await page.getByRole('alert').filter({hasText:'模拟连接断开'}).waitFor();
  assert.equal(await page.getByRole('heading',{name:/暂无.*结果/}).count(),0);
  assert.equal(await page.getByText('0 条结果',{exact:true}).count(),0);
  await page.evaluate(()=>{window.fail=false;});
  await page.getByRole('button',{name:'重新加载结果',exact:true}).click();
  await page.locator('.result-row').first().waitFor();
  assert.equal(await page.locator('.results-error').count(),0);
  await page.getByRole('radio',{name:'已处理',exact:true}).click();
  await page.getByRole('heading',{name:'暂无已处理结果',exact:true}).waitFor();
  await page.getByRole('radio',{name:'待处理',exact:true}).click();

  // 迟到的列表响应不能覆盖一次更新的读取。
  await page.evaluate(()=>{
    const original=api.results; let first=true;
    api.results=()=>{if(!first)return original();first=false;return new Promise(resolve=>window.resolveList=()=>resolve({items:[],truncated:false}));};
  });
  await refreshResults();
  await page.locator('.result-row').first().click();
  await page.locator('.result-detail h2').waitFor();
  await page.waitForFunction(()=>document.querySelector('.results-count')?.textContent==='2 条结果');
  await page.evaluate(()=>window.resolveList());
  assert.equal(await page.locator('.result-row').count(),2,'迟到的空结果被忽略');

  // 请求超过轮询周期仍能完成；后台轮询不能反复使正在读取的响应失效。
  await page.evaluate(()=>{
    const original=api.results; window.slowListCalls=0;
    api.results=()=>{window.slowListCalls++;return new Promise(resolve=>{
      window.finishSlowList=()=>{api.results=original;resolve(original());};
    });};
  });
  await refreshResults();
  await page.waitForTimeout(15500);
  assert.equal(await page.evaluate(()=>window.slowListCalls),1,'慢请求期间不追加后台读取');
  await page.evaluate(()=>window.finishSlowList());
  await page.waitForFunction(()=>document.querySelector('.results-count')?.textContent==='2 条结果');

  // 显式模式、系统变化以及多次开关弹窗共用同一个颜色状态。
  for (const mode of ['light','dark','system']) {
    await page.emulateMedia({colorScheme:'dark'});
    await page.evaluate(mode=>store.getState().setTheme(mode),mode);
    const expected=mode==='light'?'light':'dark';
    await page.waitForFunction(expected=>document.documentElement.dataset.theme===expected,expected);
    await page.waitForFunction(expected=>getComputedStyle(document.querySelector('.results-dialog')).backgroundColor===(expected==='light'?'rgb(255, 255, 255)':'rgb(38, 38, 38)'),expected);
    if(mode==='system'){
      await page.emulateMedia({colorScheme:'light'});
      await page.waitForFunction(()=>document.documentElement.dataset.theme==='light');
      assert.equal(await page.evaluate(()=>document.documentElement.dataset.themeMode),'system');
    }
  }
  await page.evaluate(()=>store.getState().setTheme('dark'));

  // 锚点定位不支持时，同一业务动作仍然可直接调用。
  await page.goto(url+'?preview=1&no-anchor=1');
  await page.locator('.result-row').first().waitFor();
  assert.equal(await page.getByRole('button',{name:'结果操作',exact:true}).count(),0);
  await page.evaluate(()=>{window.fail=true;});
  await page.getByRole('button',{name:'刷新结果',exact:true}).click();
  await page.getByRole('alert').filter({hasText:'模拟连接断开'}).waitFor();
  await page.getByRole('button',{name:'通知与投递',exact:true}).click();
  await page.getByRole('heading',{name:'通知与投递',exact:true}).waitFor();
  console.log(`${name}: PASS Astryx keyboard/filter/focus/theme, first-load failure, empty state, late list and capability fallback`);
  for(const width of [1440,768,390]) {
    await page.setViewportSize({width,height:900}); await page.goto(url+'?preview=1');
    await page.locator('.result-row').first().waitFor();
    assert.ok(await page.locator('dialog').evaluate(el=>el.scrollWidth<=el.clientWidth+1));
    await checkDialogLayout(page, {close:'.results-close',header:'.results-header',body:'.results-body'});
    const listBounds=await page.locator('.results-dialog').boundingBox();
    await page.screenshot({path:join(output,`${name}-${width}-list.png`),animations:'disabled'});
    await page.locator('.result-row').first().click(); await page.locator('.result-detail h2').waitFor();
    assert.ok(await page.locator('dialog').evaluate(el=>el.scrollWidth<=el.clientWidth+1));
    const detailBounds=await page.locator('.results-dialog').boundingBox();
    assert.ok(Math.abs(detailBounds.y-listBounds.y)<1,'详情不改变弹窗位置');
    await page.screenshot({path:join(output,`${name}-${width}-detail.png`),animations:'disabled'});
    if(width<=900){
      await page.getByRole('button',{name:'返回结果列表',exact:true}).click();
      assert.equal(await page.locator('.result-row').first().evaluate(el=>el===document.activeElement),true,'返回后聚焦原结果');
    }
  }
  // 未读结果在打开后会自动移出筛选列表；返回时仍须有可见的键盘焦点。
  await page.getByRole('radio',{name:'未读',exact:true}).click();
  await page.locator('.result-row').first().click();
  await page.waitForFunction(()=>document.querySelectorAll('.result-row').length===0);
  await page.getByRole('button',{name:'返回结果列表',exact:true}).click();
  assert.equal(await page.getByRole('radio',{name:'未读',exact:true}).evaluate(el=>el===document.activeElement),true);
  await page.getByRole('radio',{name:'待处理',exact:true}).click();
  await page.evaluate(()=>store.getState().setTheme('light'));
  // 主题背景与分段控件的选中动画独立完成；确认最终文字和底色都可读再留图。
  await page.waitForFunction(()=>{
    const selected=document.querySelector('.results-dialog [role="radio"][aria-checked="true"]');
    const style=getComputedStyle(selected);
    return getComputedStyle(document.querySelector('.results-dialog')).backgroundColor==='rgb(255, 255, 255)'
      && style.backgroundColor==='rgb(255, 255, 255)' && style.color==='rgb(0, 0, 0)';
  });
  await page.screenshot({path:join(output,`${name}-390-light.png`),animations:'disabled'});
  assert.deepEqual(errors,[]); console.log(`${name}: PASS verification-states/receipts/filter/research/handoff/conflict/stale-preview/stale-response/error/responsive`);
  if (process.argv.includes('--production')) {
    const chunk = '**/assets/ResultInbox-*.js';
    let failLoad;
    const stalledChunk = new Promise(resolve => { failLoad = resolve; });
    await page.route(chunk, async route => {
      await stalledChunk;
      await route.abort();
    });
    try {
      await page.setViewportSize({width:1440,height:900});
      await page.goto(url);
      const opener = page.getByRole('button',{name:/任务收件箱/});
      const waiting = page.getByRole('dialog',{name:'打开任务收件箱',exact:true});
      await opener.click();
      await waiting.getByText('正在打开任务收件箱…',{exact:true}).waitFor();
      await page.keyboard.press('Escape');
      await waiting.waitFor({state:'hidden'});
      await page.waitForFunction(()=>document.activeElement?.getAttribute('aria-label')?.includes('任务收件箱'));
      await opener.click();
      failLoad();
      await waiting.getByRole('alert').filter({hasText:'任务收件箱加载失败'}).waitFor();
      await waiting.getByRole('button',{name:'关闭',exact:true}).click();
      await waiting.waitFor({state:'hidden'});
      await page.getByRole('textbox',{name:'消息输入框',exact:true}).fill('加载失败后仍可输入');
      assert.deepEqual(errors,[]);
      console.log(`${name}: PASS delayed surface cancellation and missing chunk recovery`);
    } finally {
      failLoad();
      await page.unroute(chunk);
    }
  }
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
