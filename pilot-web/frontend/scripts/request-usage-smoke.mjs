import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import { build, preview } from 'vite';
import { chromium, _electron } from 'playwright-core';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const root = fileURLToPath(new URL('..', import.meta.url));
const temp = await mkdtemp(join(tmpdir(), 'pilot-request-usage-'));
const categories = ['system','tools','rules','skills','mcp','agents','runtime','summary','conversation'];
const labels = ['系统提示','工具定义','项目约定与记忆','技能','MCP 工具','子代理定义','任务状态','压缩摘要','对话与工具结果'];
const report = { id:'request-1', purpose:'main', model:'test-model', provider:'generic', startedAt:100, durationMs:2310, firstTokenMs:321, status:'completed',
  usage:{inputTokens:61200,outputTokens:500,cachedInputTokens:48960,cacheWriteTokens:0,exact:true,cacheReadObserved:true,cacheWriteObserved:true},
  context:{totalTokens:61200,estimated:true,attachments:1,parts:categories.map((category,i)=>({category,label:labels[i],tokens:[1100,5100,156,4600,3700,1500,944,6900,37200][i]}))},
  cost:{currency:'USD',total:0.005,input:0.002,cachedInput:0.001,cacheWrite:0,output:0.002,source:'测试报价',note:'按模型用量与单价估算，以服务商账单为准'},
  prefix:{change:'append_only'} };
const totals = {requests:1,observedCacheRequests:1,observedCacheInputTokens:61200,cacheReadTokens:48960,cacheWriteTokens:0,pricedRequests:1,costs:{USD:0.005},exact:true};
const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Pilot 请求用量</title><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
      import React from 'react'; import {createRoot} from 'react-dom/client';
      import {Composer} from '/src/components/Composer.jsx'; import {ModelRuntimeSettings} from '/src/components/ModelRuntimeSettings.jsx';
      import {useStore} from '/src/store.js'; import {api} from '/src/api.js';
      import {applyEvent, initialThread, prependHistoryPage} from '/src/thread-reducer.js';
      import '/src/styles.css'; import '/src/shell-vanilla.css'; import '/src/styles-addendum.css'; import '/src/workbench.css';
      document.documentElement.dataset.theme='dark'; window.store=useStore; window.api=api; window.applyEvent=applyEvent; window.initialThread=initialThread;
      window.EventSource=class { constructor(){window.sse=this;this.handlers={};} addEventListener(type,handler){this.handlers[type]=handler;} close(){} };
      window.event=(event,live=true)=>{if(live) { window.sse.handlers[event.type]?.({data:JSON.stringify(event)}); useStore.getState().flushEvents(); } else useStore.setState(d=>applyEvent(d,event,{live:false}));};
      window.reset=()=>useStore.setState({sessionId:'usage-test',runId:null,running:false,cursor:0,requestReports:[],latestContextReport:null,runUsage:null,thread:initialThread(),pendingInputs:[],queue:[],contextStatus:{limitTokens:300000,usedTokens:61200,canCompact:true},settingsData:{model:'test-model',modelEndpoint:'https://example.test/v1'}});
      reset(); useStore.getState().connectSse('usage-test',0);
      api.modelRuntime=async()=>({model:'test-model',endpoint:'https://example.test/v1',options:{provider:'auto',cacheMode:'auto',pricing:{currency:'USD'}},effective:{provider:'generic'}});
      const realSaveModelRuntime=api.saveModelRuntime;
      api.saveModelRuntime=async(value)=>{window.saved=value; if(window.failSave)throw Error('测试保存失败');return realSaveModelRuntime(value);};
      const mount=createRoot(document.getElementById('root'));
      window.settingsVersion=0;
      window.renderSettings=()=>mount.render(React.createElement('div',{style:{maxWidth:620,margin:'24px auto',padding:16}},React.createElement(ModelRuntimeSettings,{key:++window.settingsVersion,model:'test-model',endpoint:'https://example.test/v1'})));
      mount.render(React.createElement('div',{style:{height:'100dvh',display:'flex',flexDirection:'column',justifyContent:'flex-end',padding:'20px'}},React.createElement(Composer)));
      if(new URL(location.href).searchParams.has('inspect')) {
        const report=${JSON.stringify(report)},details=${JSON.stringify(totals)};
        useStore.setState({requestReports:[report],latestContextReport:report,runUsage:{details}});
        api.context=async()=>({limitTokens:300000,usedTokens:61200,canCompact:true});
        setTimeout(()=>document.getElementById('context-chip')?.click(),200);
      }
    </script></body></html>`;
const entry = `.request-usage-fixture-${process.pid}.html`, file = join(root,entry);
try { await writeFile(file,fixture); await build({root,build:{outDir:temp,emptyOutDir:false,rollupOptions:{input:file}}}); }
finally { await rm(file,{force:true}); }
await cp(join(root,'../src/main/resources/static/fonts'),join(temp,'fonts'),{recursive:true});
const server=await preview({root,build:{outDir:temp},preview:{host:'127.0.0.1',port:0,proxy:{}}});
let browser,electron,url;
async function run(page,name){
  await page.route('**/api/settings/model/runtime',async route=>{
    assert.equal(route.request().method(),'PUT');
    assert.match(route.request().headers()['content-type'] || '',/application\/json/,'保存请求使用 JSON Content-Type');
    const body=route.request().postDataJSON();
    await route.fulfill({contentType:'application/json',body:JSON.stringify({...body,effective:{provider:'generic'}})});
  });
  const errors=[]; page.on('pageerror',e=>errors.push(e.message)); await page.goto(url); await page.locator('#context-chip').waitFor();
  assert.equal(await page.evaluate(()=>typeof sse.handlers['model.request']), 'function','真实 SSE 订阅包含明细');
  let seq=0;
  const send=async(type,payload,runId='run-a',live=true)=>page.evaluate(({type,payload,runId,sequence,live})=>window.event({type,payload,runId,sequence,sessionId:'usage-test',timestamp:Date.now()},live),{type,payload,runId,sequence:++seq,live});
  await send('run.started',{input:'检查请求成本'});
  await send('model.request',{report}); await send('model.request',{report});
  await send('run.usage',{modelRequests:1,inputTokens:61200,outputTokens:500,cachedInputTokens:48960,contextTokens:61200,contextLimit:300000,exact:true,details:totals});
  assert.equal(await page.evaluate(()=>store.getState().requestReports.length),1,'重复 SSE 不重复明细');
  assert.equal(await page.evaluate(()=>store.getState().thread.rows.find(row=>row.type==='turn').requests.length),1);
  await page.locator('#context-chip').click();
  const panel=page.getByRole('dialog',{name:'上下文与请求用量'}); await panel.waitFor();
  assert.equal(await panel.locator('.context-composition-list li').count(),9);
  assert.equal(await panel.locator('.context-composition-list').evaluate(el=>getComputedStyle(el).display),'grid','分类列表不被旧菜单样式覆盖');
  assert.match(await panel.innerText(),/80%/); assert.match(await panel.innerText(),/USD 0.005/);
  assert.match(await panel.innerText(),/媒体开销未计入/);
  assert.equal(await panel.getByRole('button',{name:'压缩上下文',exact:true}).isDisabled(),true,'运行期间禁止手动压缩');
  for(const width of [1440,768,390]){
    await page.setViewportSize({width,height:900});
    const box=await panel.boundingBox(); assert.ok(box.x>=0 && box.x+box.width<=width+1 && box.y>=0,`${name} ${width}: panel within viewport`);
    assert.equal(await panel.evaluate(el=>el.scrollWidth<=el.clientWidth+1),true,`${name} ${width}: no horizontal overflow`);
    await page.screenshot({path:join(temp,`${name}-${width}.png`)});
  }
  await page.setViewportSize({width:390,height:500});
  assert.equal(await panel.evaluate(el=>el.clientHeight<el.scrollHeight),true,'低矮视口可以滚动查看全部明细');
  assert.ok((await panel.locator('.context-composition-bar').boundingBox()).height>=6,'进度条不被低矮视口挤没');
  await page.evaluate(()=>document.documentElement.dataset.theme='light');
  await page.screenshot({path:join(temp,`${name}-light-compact.png`)});
  await page.setViewportSize({width:390,height:900}); await page.evaluate(()=>document.documentElement.dataset.theme='dark');
  await panel.getByRole('button',{name:'查看请求明细'}).click(); await panel.locator('summary').click();
  assert.match(await panel.innerText(),/缓存读取/); assert.match(await panel.innerText(),/0.32s/); assert.match(await panel.innerText(),/实际缓存命中以端点用量为准/);
  await page.keyboard.press('Escape'); await panel.waitFor({state:'hidden'}); assert.equal(await page.locator('#context-chip').evaluate(el=>el===document.activeElement),true);
  const failed={...report,id:'failed',purpose:'subagent',status:'failed',usage:{inputTokens:100,outputTokens:0,exact:false},cost:{total:null},startedAt:101};
  await send('model.request',{report:failed});
  await send('run.usage',{modelRequests:2,inputTokens:61300,outputTokens:500,contextTokens:61200,contextLimit:300000,exact:false,details:{...totals,requests:2}});
  await page.locator('#context-chip').click(); assert.match(await panel.innerText(),/费用合计不完整/);
  assert.equal(await panel.locator('.request-overview dd').first().innerText(),'—','缺失缓存不是 0%');
  await page.keyboard.press('Escape');
  // 手动压缩不污染上一任务的用量卡片；压缩后清除失效的请求组成。
  const oldStats=await page.evaluate(()=>store.getState().thread.rows.find(row=>row.type==='turn').stats);
  await send('model.request',{report:{...report,id:'compact',purpose:'compaction'}},'compact_x');
  await send('run.usage',{modelRequests:1,inputTokens:100,outputTokens:10,contextTokens:100,contextLimit:1,exact:true,details:totals},'compact_x');
  assert.deepEqual(await page.evaluate(()=>store.getState().thread.rows.find(row=>row.type==='turn').stats),oldStats);
  await send('context.compacted',{coveredMessages:4,charactersBefore:2000,charactersAfter:500,summary:'summary'},'compact_x');
  assert.equal(await page.evaluate(()=>store.getState().latestContextReport),null);
  // 同样的持久化事件按历史模式回放，零命中可显示，旧事件仍然未知。
  await page.evaluate(()=>reset());
  await send('run.started',{input:'历史回放'},'run-old',false);
  await send('model.request',{report:{...report,usage:{...report.usage,cachedInputTokens:0}}},'run-old',false);
  await send('run.usage',{modelRequests:1,inputTokens:61200,outputTokens:500,contextTokens:61200,contextLimit:300000,exact:true},'run-old',false);
  await page.locator('#context-chip').click(); assert.equal(await panel.locator('.request-overview dd').first().innerText(),'0%');
  assert.equal(await panel.locator('.request-overview dd').nth(2).innerText(),'—');
  await page.keyboard.press('Escape');
  await page.evaluate(()=>renderSettings());
  const input=page.getByLabel('普通输入每百万 token 单价'); await input.waitFor(); await input.fill('1.5');
  await page.getByLabel('缓存读取每百万 token 单价').fill('0');
  await page.getByRole('button',{name:'保存计价与缓存设置'}).click();
  await page.getByText('已保存，仅对该端点与模型的后续请求生效。').waitFor();
  assert.equal(await page.evaluate(()=>saved.options.pricing.input),1.5); assert.equal(await page.evaluate(()=>saved.options.pricing.cachedInput),0);
  assert.equal(await page.evaluate(()=>saved.options.pricing.output),null);
  await page.evaluate(()=>window.failSave=true); await page.getByRole('button',{name:'保存计价与缓存设置'}).click(); await page.getByRole('alert').waitFor();
  assert.match(await page.getByRole('alert').innerText(),/测试保存失败/); assert.equal(await input.inputValue(),'1.5');
  await page.evaluate(()=>{api.modelRuntime=async()=>({});renderSettings();});
  await page.getByText('端点未提供完整的缓存与计价配置，请刷新或升级服务端。').waitFor();
  assert.equal(await page.getByRole('button',{name:'重试',exact:true}).count(),1);
  assert.deepEqual(errors,[]); console.log(`${name}: PASS SSE / 去重 / 9 类构成 / 估价 / 命中覆盖 / 历史回放 / 压缩隔离 / 设置 / 键盘 / 3 个视口`);
}
try{
  url=`http://127.0.0.1:${server.httpServer.address().port}/${entry}`;
  browser=await chromium.launch({channel:'chrome',headless:true}); await run(await browser.newPage(),'chromium'); await browser.close(); browser=null;
  const main=join(temp,'main.cjs'); await writeFile(main,`const {app,BrowserWindow}=require('electron');app.whenReady().then(()=>{new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}}).loadURL(${JSON.stringify(url)});});`);
  const env={...process.env}; delete env.ELECTRON_RUN_AS_NODE;
  electron=await _electron.launch({executablePath:electronBinary(),args:[main],env}); await run(await electron.firstWindow(),'electron');
  if(process.env.PILOT_UI_INSPECTOR) await new Promise((resolve,reject)=>{
    // 这是独立弹层组件夹具，无页面标题；不为检查器向产品内添加多余的 h1。
    const processChild=spawn(process.execPath,[process.env.PILOT_UI_INSPECTOR,url+'?inspect','--app','--allow','no-title','--out',join(temp,'inspection')],{stdio:'inherit'});
    processChild.on('error',reject);processChild.on('exit',code=>code===0?resolve():reject(Error('UI inspection exit '+code)));
  });
  console.log(`Screenshots: ${temp}`);
}finally{await browser?.close();await electron?.close();await new Promise(resolve=>server.httpServer.close(resolve));}
