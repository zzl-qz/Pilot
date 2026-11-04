import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-sandbox-ui-'));
const fixture = `<html lang="zh-CN" data-theme="dark"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react'; import {createRoot} from 'react-dom/client'; import App from '/src/components/App.jsx'; import {useStore} from '/src/store.js'; import {api} from '/src/api.js'; import {initialThread} from '/src/thread-reducer.js';
import '/src/styles.css'; import '/src/shell-vanilla.css'; import '/src/styles-addendum.css'; import '/src/workbench.css';
for (const key of Object.keys(api)) if (typeof api[key] === 'function' && key !== 'sandboxManifest') api[key]=async()=>({});
api.files=async()=>({entries:[]}); api.results=async()=>({items:[]}); api.schedules=async()=>[];
useStore.setState({init:async()=>{},sessionId:'s1',sessionTitle:'恢复中断的变更',projects:[],sessions:[],workspace:'/workspace/pilot'});
useStore.getState().setUi({sidebarOpen:false,inspectorOpen:false}); window.store=useStore;
window.seed=()=>useStore.setState({thread:{...initialThread(),rows:[{key:'b'+Math.random(),type:'sandbox',sandboxId:'b',title:'修复计算与测试',detail:'修改 src/sum.mjs，新增边界测试。'}]}});
seed(); createRoot(document.getElementById('root')).render(React.createElement(App));
</script></body></html>`;
const server = await createServer({root,server:{host:'127.0.0.1',port:0,proxy:{}},plugins:[{name:'sandbox-fixture',configureServer(s){s.middlewares.use('/__sandbox',async(req,res)=>{res.setHeader('Content-Type','text/html');res.end(await s.transformIndexHtml('/__sandbox',fixture));});}}]});
let browser, electron;
async function run(page, name, url) {
  page.setDefaultTimeout(8000); const errors=[]; page.on('pageerror', e=>errors.push(e.message));
  let state={manifest:{files:[{path:'src/sum.mjs'}]},reviewHash:'v1',disposition:'pending',verification:{current:true,status:'passed'},recovery:null}; let failure=''; let manifestFail=false; const posts=[];
  await page.route('**/api/sessions/s1/multi/sandboxes/b/**', async route => {
    const request=route.request(); const action=request.url().split('/').at(-1);
    if(action==='manifest') return route.fulfill({status:manifestFail?503:200,json:manifestFail?{message:'读取版本失败'}:state});
    const body=request.postDataJSON(); posts.push({action,body});
    if(failure) return route.fulfill({status:failure==='unverified'?412:409,json:{message:failure==='unverified'?'未执行校验':failure}});
    if(action==='recover') state={...state,recovery:null};
    return route.fulfill(action==='accept'?{status:200,json:{ok:true,written:['src/sum.mjs']}}:{status:204,body:''});
  });
  await page.setViewportSize({width:1280,height:800}); await page.goto(url);
  const accept=page.getByRole('button',{name:'接受变更',exact:true}); const reject=page.getByRole('button',{name:'拒绝',exact:true});
  await accept.waitFor(); await page.getByText(/校验通过/).waitFor(); await accept.click(); await page.getByText('已接受',{exact:true}).waitFor();
  assert.equal(posts.at(-1).body.candidateHash,'v1');
  await page.evaluate(()=>seed()); failure='候选版本已变化'; await accept.click();
  await page.getByRole('status').filter({hasText:failure}).waitFor(); assert.equal(await page.getByText('已接受',{exact:true}).count(),0);
  failure='恢复冲突'; await reject.click(); await page.getByRole('status').filter({hasText:failure}).waitFor();
  assert.equal(await page.getByText('已拒绝',{exact:true}).count(),0);
  failure=''; state={...state,recovery:{sessionId:'s1',sandboxId:'b',phase:'applying',paths:['src/sum.mjs']}};
  await page.evaluate(()=>seed()); const recover=page.getByRole('button',{name:'恢复写入状态',exact:true}); await recover.waitFor();
  assert.equal(await accept.isDisabled(),true); await recover.click();
  await page.getByRole('status').filter({hasText:'恢复已完成'}).waitFor(); await accept.click(); await page.getByText('已接受',{exact:true}).waitFor();
  failure='unverified'; state={...state,reviewHash:'v2'}; await page.evaluate(()=>seed());
  page.once('dialog',async dialog=>{failure='';await dialog.accept();}); await accept.click(); await page.getByText('已接受',{exact:true}).waitFor();
  assert.equal(posts.at(-1).body.acknowledgeUnverified,true); assert.equal(posts.at(-1).body.candidateHash,'v2');
  assert.equal(posts.at(-2).body.candidateHash,'v2');
  manifestFail=true; await page.evaluate(()=>seed()); await page.getByRole('alert').filter({hasText:'读取版本失败'}).waitFor();
  assert.equal(await accept.isDisabled(),true); manifestFail=false; await page.getByRole('button',{name:'重新加载',exact:true}).click();
  await reject.click(); await page.getByText('已拒绝',{exact:true}).waitFor();
  state={...state,recovery:{sessionId:'s1',sandboxId:'b',phase:'applying',paths:['src/sum.mjs']}};
  await page.evaluate(()=>seed()); await recover.waitFor();
  for(const width of [1280,390]) {
    await page.setViewportSize({width,height:800});
    await page.reload(); await recover.waitFor();
    await page.evaluate(() => document.fonts.ready);
    assert.equal(await page.locator('.topbar').count(), 1);
    assert.equal(await page.locator('.sandbox-card').evaluate(el=>el.scrollWidth<=el.clientWidth+1),true);
    await page.screenshot({path:join(output,`${name}-${width}.png`)});
  }
  state={...state,recovery:null,disposition:'accepted'}; await page.evaluate(()=>seed());
  await page.getByText('已接受',{exact:true}).waitFor(); assert.equal(await accept.isDisabled(),true);
  assert.deepEqual(errors,[]); console.log(`${name}: PASS version/conflict/reject-failure/recovery/confirmation/load-error/responsive`);
}
try {
  await server.listen(); const url=`http://127.0.0.1:${server.httpServer.address().port}/__sandbox`;
  browser=await chromium.launch({channel:'chrome',headless:true}); await run(await browser.newPage(),'chromium',url); await browser.close(); browser=null;
  if (!process.argv.includes('--web-only')) {
    const main=join(output,'main.cjs'); await writeFile(main,`const {app,BrowserWindow}=require('electron');app.whenReady().then(()=>{const w=new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}});w.loadURL(${JSON.stringify(url)});});`);
    const env={...process.env}; delete env.ELECTRON_RUN_AS_NODE;
    electron=await _electron.launch({executablePath:electronBinary(),args:[main],env}); await run(await electron.firstWindow(),'electron',url);
  }
  console.log('Screenshots:',output);
} finally { await browser?.close(); await electron?.close(); await server.close(); }
