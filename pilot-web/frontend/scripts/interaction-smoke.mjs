import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import { createServer } from 'vite';
import { chromium, _electron } from 'playwright-core';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkQueue } from './queue-cases.mjs';
import { checkVoice } from './voice-cases.mjs';
import { checkWorkbench } from './workbench-cases.mjs';
import { checkReview } from './review-cases.mjs';
import { checkSideChat } from './side-chat-cases.mjs';
const root = fileURLToPath(new URL('..', import.meta.url));
const temp = await mkdtemp(join(tmpdir(), 'pilot-interaction-'));
const fixture = `<html><head><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react'; import {createRoot} from 'react-dom/client';
import App from '/src/components/App.jsx'; import {useStore} from '/src/store.js'; import {api} from '/src/api.js';
import '/src/styles.css'; import '/src/shell-vanilla.css'; import '/src/styles-addendum.css'; import '/src/workbench.css';
document.documentElement.dataset.theme='dark'; window.store=useStore; window.api=api; window.realTranscribe=api.transcribeVoice; window.calls=[];
for(const name of Object.keys(api)) if(typeof api[name]==='function') api[name]=async (...args)=>{calls.push({name,args});return {};};
api.workProducts=api.skillDrafts=async()=>[];
api.worktreeInfo=async()=>({branch:'feature/ui',worktree:false});
api.filesFlat=api.workspaceFiles=async()=>({files:['src/组件.jsx','src/App.jsx','src/space file.js']});
api.context=async()=>({usedTokens:10,limitTokens:100}); api.queue=async()=>({items:[]});
window.diff='diff --git a/src/App.jsx b/src/App.jsx\\n--- a/src/App.jsx\\n+++ b/src/App.jsx\\n@@ -1,3 +1,3 @@\\n const a = 1;\\n-old();\\n+fixed();\\n done();\\n';
window.report={gitRepository:true,files:[{path:'src/App.jsx',status:'M',added:1,removed:1}],diff:window.diff};
api.diff=async()=>window.report; api.runChanges=async()=>({...window.report,files:[{...window.report.files[0],diff:window.diff}]});
useStore.setState({init:async()=>{},sessionId:'test-a',workspace:'/workspace/demo',currentProjectId:'p',sessionTitle:'审阅输入与交互',projects:[{id:'p',name:'Pilot',folders:['/workspace/demo']}],lastRunId:'run-a',sessionEpoch:1});
if (new URL(location.href).searchParams.has('queue')) {
  let entries=[{content:'按照参考图精简按钮，把操作放到消息队列里'},{content:'完成后检查粘贴附件、语音和窄屏布局'}];
  const snapshot=()=>({entries:entries.map(e=>({...e})),revision:String(entries.length)});
  api.queue=async()=>snapshot();
  api.steerQueueItem=async(_id,index)=>{entries.splice(index,1);return snapshot();};
  api.deleteQueueItem=api.steerQueueItem;
  api.updateQueueItem=async(_id,index,body)=>{entries[index]={content:body.content};return snapshot();};
  api.moveQueueItem=async(_id,from,to)=>{entries.splice(to,0,entries.splice(from,1)[0]);return snapshot();};
  api.sendMessage=async(_id,body)=>{entries.push({content:body.content});return {};};
  api.cancelRun=async()=>{useStore.setState({running:false,queueHeld:true});};
  useStore.setState({running:true,runId:'run-a',sessionTitle:'精简输入与队列交互',settingsData:{model:'gpt-6-sol'},queueEntries:entries.map(e=>({...e})),queueRevision:'2',thread:{...useStore.getState().thread,rows:[{key:'u',type:'user',text:'按参考图优化输入区，加入语音输入'},{key:'t',type:'turn',runIds:['run-a'],tools:[],reply:{text:'正在整理输入区的操作与队列交互。'},stats:{}}],cursor:1}});
}
createRoot(document.getElementById('root')).render(React.createElement(App));
</script></body></html>`;
const server = await createServer({ root, server:{host:'127.0.0.1',port:0,proxy:{}},plugins:[{name:'interaction-fixture',configureServer(server){
  server.middlewares.use('/__interaction',async(_req,res)=>{res.setHeader('Content-Type','text/html');res.end(await server.transformIndexHtml('/__interaction',fixture));});
}}]});
let browser, electron;
async function run(page,name,url) {
  page.setDefaultTimeout(5000);
  await page.setViewportSize({width:1440,height:900});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(url); await page.locator('#input-flow').waitFor();
  const input=page.locator('#input-flow'), chips=page.locator('[data-chip=attachment]');
  const mod=process.platform==='darwin'?'Meta':'Control';
  async function paste(name='note.txt') {
    await input.evaluate((el,name)=>{const data=new DataTransfer();data.items.add(new File(['content'],name,{type:'text/plain'}));el.dispatchEvent(new ClipboardEvent('paste',{clipboardData:data,bubbles:true,cancelable:true}));},name);
    await page.waitForFunction(()=>document.querySelector('#input-flow').getAttribute('aria-busy')==='false');
  }
  async function clear(){await page.evaluate(()=>store.getState().composerApi.setText(''));await input.focus();}
  // Pointer and keyboard completion preserve chips, prefix and suffix. Unicode and spaces in paths.
  await input.fill('前文 '); await paste(); await input.press('End'); await input.pressSequentially('@组');
  await page.getByRole('option',{name:'src/组件.jsx',exact:true}).click();
  assert.equal(await chips.count(),1); assert.match(await input.innerText(),/前文/);
  assert.match(await page.evaluate(()=>store.getState().composerApi.getText()),/@src\/组件.jsx/);
  await input.pressSequentially('/test'); await input.press('Enter'); assert.equal(await chips.count(),1);
  assert.match(await input.innerText(),/运行项目测试/); assert.equal((await input.innerText()).includes('（test）'),false);
  await clear();await input.fill('前文 @App 后文');
  await input.evaluate(el=>{const r=document.createRange();r.setStart(el.firstChild,7);r.collapse(true);const s=getSelection();s.removeAllRanges();s.addRange(r);el.dispatchEvent(new InputEvent('input',{bubbles:true}));});
  await page.getByRole('option',{name:'src/App.jsx',exact:true}).click(); assert.match(await input.innerText(),/后文/);
  // Switching workspace under the same session invalidates cached references.
  await page.evaluate(()=>{api.filesFlat=async()=>({files:['fresh.js']});store.setState({workspace:'/workspace/next'});});
  await clear();await input.fill('@fresh');await page.getByRole('option',{name:'fresh.js',exact:true}).click();
  await page.evaluate(()=>store.setState({workspace:'/workspace/demo'}));
  // First-message references work without creating a session; all new-task entries retain workspace.
  await page.locator('#new-session').click();assert.equal(await page.evaluate(()=>store.getState().workspace),'/workspace/demo');
  assert.equal(await page.evaluate(()=>store.getState().sessionId),null);
  await input.fill('@space');await page.getByRole('option',{name:'src/space file.js',exact:true}).click();
  assert.match(await page.evaluate(()=>store.getState().composerApi.getText()),/@src\/space file.js/);
  await input.press(`${mod}+n`);assert.equal(await page.locator('#picker').count(),0);
  assert.equal(await page.evaluate(()=>store.getState().workspace),'/workspace/demo');
  // Stale async @ response must not reopen menu after Esc or replace another session's index.
  await page.evaluate(()=>{store.setState({fileIndex:{items:[]}});api.workspaceFiles=()=>new Promise(resolve=>window.resolveFiles=resolve);});
  await clear();await input.fill('@');await page.waitForFunction(()=>!!window.resolveFiles);
  const beforeLoadingEnter=await page.evaluate(()=>calls.length); await input.press('Enter'); assert.equal(await page.evaluate(()=>calls.length),beforeLoadingEnter,'读取引用时 Enter 不发送');
  await input.press('Escape');await page.evaluate(()=>resolveFiles({files:['late.txt']}));await page.waitForTimeout(50);
  assert.equal(await page.locator('.ref-menu').count(),0);
  // Popovers: only one open, outside click / Escape close before global task cancellation.
  await page.evaluate(()=>store.setState({sessionId:'test-a',running:true,runId:'run-a'}));
  for(const id of ['perm','model','context']) {
    await page.locator(`#${id}-chip`).click();await page.keyboard.press('Escape');
    assert.equal(await page.locator(`#${id}-chip`).getAttribute('aria-expanded'),'false');
    assert.equal(await page.evaluate(()=>calls.filter(c=>c.name==='cancelRun').length),0);
    await page.locator(`#${id}-chip`).click();await page.locator('.empty-title').click();
    assert.equal(await page.locator(`#${id}-chip`).getAttribute('aria-expanded'),'false');
  }
  await page.locator('#perm-chip').click();await page.keyboard.press('ArrowDown');assert.equal(await page.evaluate(()=>document.activeElement.getAttribute('role')),'menuitemradio');await page.locator('#model-chip').click();
  assert.equal(await page.locator('#perm-menu').count(),0);await page.keyboard.press('Escape');
  // Failed settings save preserves the form; this fixture never changes real credentials.
  await page.locator('#model-chip').click();
  await page.locator('#model-menu').getByRole('button',{name:'模型设置'}).click();
  await page.getByLabel('模型名称',{exact:true}).fill('test-model');
  await page.evaluate(()=>api.saveModel=async()=>{throw Error('offline');});
  await page.locator('#settings-model-save').click();
  assert.equal(await page.getByLabel('模型名称',{exact:true}).inputValue(),'test-model');
  await page.keyboard.press('Escape');
  await input.fill('暂停请求中不能发'); await page.evaluate(()=>store.setState({pausing:true}));
  await input.press('Enter');assert.equal(await page.evaluate(()=>calls.filter(c=>c.name==='sendMessage').length),0);
  await page.evaluate(()=>store.setState({pausing:false}));
  // One primary action: stop with empty draft, queue with text/files; steer lives on its queue row.
  await clear();await page.getByRole('button',{name:'停止当前任务',exact:true}).click();
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.name==='cancelRun').length),1);
  assert.equal(await page.getByLabel('运行中发送方式').count(),0);
  assert.equal(await page.locator('#task-live-controls').count(),0);
  await input.fill('排队要求');await page.locator('#send').click();
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.name==='sendMessage').length),1);
  await page.evaluate(()=>{store.setState({queueEntries:[{text:'按图优化'}],queueRevision:'v1'});api.steerQueueItem=async(...args)=>{calls.push({name:'steerQueueItem',args});};});
  await page.getByRole('button',{name:'引导这条排队消息'}).click();
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.name==='steerQueueItem').length),1);
  assert.deepEqual(await page.evaluate(()=>calls.find(c=>c.name==='steerQueueItem').args),['test-a',0,{runId:'run-a',revision:'v1'}]);
  await page.evaluate(()=>{store.setState({queueEntries:[{text:'失败时保留'}],queueRevision:'v2'});api.queue=async()=>({entries:[{content:'失败时保留'}],revision:'v2'});api.steerQueueItem=async()=>{throw Error('任务已结束');};});
  await page.getByRole('button',{name:'引导这条排队消息'}).click();await page.waitForFunction(()=>!store.getState().queueBusy);
  assert.match(await page.locator('.queue-text').innerText(),/失败时保留/);
  assert.match(await page.locator('#notice').innerText(),/引导未发送/);
  await page.evaluate(()=>api.queue=async()=>({items:[]}));
  await paste();await input.press('Escape');assert.equal(await chips.count(),1,'停止任务保留草稿附件');
  // Queue edit failure / blank / success, then draft remains usable.
  await clear();await page.evaluate(()=>{store.setState({running:false,queueEntries:[{text:'queued'}]});api.queue=async()=>({entries:[{text:'queued'}]});api.updateQueueItem=async()=>{throw Error('offline');};});
  await page.locator('.queue-text').click();await page.getByLabel('编辑排队消息').fill('changed');await page.locator('#queue-edit').getByText('保存',{exact:true}).click();
  assert.equal(await page.getByLabel('编辑排队消息').inputValue(),'changed');
  await page.getByLabel('编辑排队消息').fill(' ');assert.equal(await page.locator('#queue-edit').getByText('保存',{exact:true}).isDisabled(),true);
  await page.getByLabel('编辑排队消息').fill('saved');await page.evaluate(()=>{api.updateQueueItem=async()=>({});api.queue=async()=>({items:[]});});
  await page.locator('#queue-edit').getByText('保存',{exact:true}).click();await page.waitForFunction(()=>store.getState().ui.queueEdit===null);
  // In-flight send, switch session, late failure cannot mutate the new conversation or lose original draft.
  await input.fill('slow original');await paste('slow.txt');
  await page.evaluate(()=>{api.sendMessage=()=>new Promise((resolve,reject)=>{window.rejectSend=reject;window.resolveSend=resolve;});});
  await page.locator('#send').click();await page.waitForFunction(()=>!!window.rejectSend);
  await page.evaluate(()=>store.setState(s=>{s.sessionId='test-b';s.sessionEpoch++;s.thread.rows=[];}));
  await page.evaluate(()=>rejectSend(Error('late failure')));await page.waitForFunction(()=>document.querySelector('#input-flow').getAttribute('aria-busy')==='false');
  assert.equal(await input.innerText(),'');assert.equal(await page.evaluate(()=>store.getState().thread.rows.length),0);
  await page.evaluate(()=>store.setState(s=>{s.sessionId='test-a';s.sessionEpoch++;}));await page.waitForFunction(()=>document.querySelectorAll('[data-chip=attachment]').length===1);
  assert.match(await input.innerText(),/slow original/);
  // Presentation keeps unique content and actions, folds details, reveals failure.
  await page.evaluate(()=>{store.setState(s=>{s.thread.rows=[
    {key:'u',type:'user',text:'检查',sequence:1},
    {key:'p',type:'plan',items:[]},
    {key:'t',type:'turn',tools:[{callId:'t1',name:'run_command',args:'npm test',status:'error',body:'failure detail'}],reply:{text:'唯一结论'},stats:{inputTokens:12,outputTokens:4,finishedAt:Date.now()}},
    {key:'r',type:'result',title:'已完成',detail:'唯一结论',tone:'ok',runIds:['run-a']}
  ];});});
  assert.equal(await page.locator('.plan-card').count(),0);assert.equal(await page.getByText('唯一结论',{exact:true}).count(),1);
  assert.equal(await page.locator('.turn-stats-detail').getAttribute('open'),null);
  assert.equal(await page.locator('.tool-group').getAttribute('open'),'');
  assert.equal(await page.locator('.tool-row.is-open').count(),1);
  // Review expanded, line-range feedback preserves draft attachments; never sends automatically.
  await page.locator('#toggle-activity').click();await page.locator('#panel-changes').waitFor();
  await page.getByText('全屏审阅',{exact:true}).click();await page.locator('.change-row-name').getByText('App.jsx',{exact:true}).click();
  await page.getByRole('button',{name:'选择原文件第 2 行',exact:true}).click();
  await page.getByRole('button',{name:'选择现文件第 2 行',exact:true}).click({modifiers:['Shift']});
  await page.getByLabel('审阅意见').fill('处理空输入边界');
  const sentBefore=await page.evaluate(()=>calls.filter(c=>c.name==='sendMessage').length);
  await page.getByText('引用到输入框',{exact:true}).click();
  assert.equal(await chips.count(),1);assert.match(await input.innerText(),/处理空输入边界/);
  assert.match(await input.innerText(),/原文件:2 → 现文件:2/);
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.name==='sendMessage').length),sentBefore);
  await page.getByLabel('审阅范围').selectOption('run');await page.waitForTimeout(80);
  assert.match(await page.locator('.review-scope-note').innerText(),/差异仍相对 HEAD/);
  assert.equal(await page.locator('#changes-undo-all').count(),0);
  await page.getByLabel('审阅范围').selectOption('workspace');
  // Recovery has correct zero-file impact; only inspect, never restore.
  await page.evaluate(()=>store.getState().setUi({checkpointDialog:{sequence:1,info:{input:'原输入',runs:1,conversationAvailable:true,code:{available:true,files:[]}}}}));
  if(await page.locator('#checkpoint-dialog').count()) {
    assert.equal((await page.locator('#checkpoint-dialog').innerText()).includes('新建的文件会被删除'),false);
    await page.keyboard.press('Escape');
  }
  await checkReview(page, name, temp);
  await checkSideChat(page, name, temp);
  await checkVoice(page, name, temp);
  await checkQueue(page, name, temp);
  await checkWorkbench(page, name, temp).catch(async error => {
    console.error('Page errors:', errors);
    console.error(await page.locator('body').innerText());
    await page.screenshot({ path: join(temp, name + '-failure.png') });
    console.error('Failure screenshot:', join(temp, name + '-failure.png')); throw error;
  });
  // Final responsive evidence; reject page-level overflow and hidden stop action.
  await page.evaluate(()=>store.getState().setUi({inspectorOpen:false,sidebarOpen:false}));
  for(const width of [1440,768,390]) {
    await page.setViewportSize({width,height:900});await page.waitForFunction(()=>document.querySelector('.main').getBoundingClientRect().width >= innerWidth - (innerWidth > 960 ? 52 : 0) - 2);
    if(width<=960) await page.waitForFunction(()=>document.querySelector('#sidebar').getBoundingClientRect().right<=1);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true,`${width}px 不横向溢出`);
    assert.ok(await page.locator('.main').evaluate(el=>el.getBoundingClientRect().width)>=width-(width>960?52:0)-2,`${width}px 主区占满可用宽度`);
    await page.screenshot({path:join(temp,`${name}-${width}.png`)});
    if(width===390) {
      await page.evaluate(()=>store.setState({running:true,runId:'run-a'}));
      await page.waitForFunction(()=>document.querySelector('#send')?.getBoundingClientRect().right<=innerWidth);
      assert.ok(await page.locator('#send').isVisible());
      await page.screenshot({path:join(temp,`${name}-mobile-running.png`)});
      await page.evaluate(()=>store.setState({running:false}));
    }
  }
  await page.setViewportSize({width:1440,height:900});await page.evaluate(()=>store.getState().setUi({inspectorOpen:true,reviewExpanded:true,changeView:'src/App.jsx'}));
  await page.waitForTimeout(250); await page.screenshot({path:join(temp,`${name}-review.png`)});
  await page.setViewportSize({width:390,height:844});await page.reload();await input.waitFor();
  await page.waitForFunction(()=>document.querySelector('#sidebar').getBoundingClientRect().right<=1);
  await page.locator('#sidebar-toggle').click();await page.getByRole('button',{name:'关闭导航',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#sidebar').getBoundingClientRect().right<=1);
  assert.deepEqual(errors,[]);console.log(`${name}: PASS combined interactions, failure/race boundaries, review, responsive layouts`);
}
try {
  await server.listen();const url=`http://127.0.0.1:${server.httpServer.address().port}/__interaction`;
  if(process.argv.includes('--serve')) { console.log('Fixture:',url); await new Promise(()=>{}); }
  browser=await chromium.launch({channel:'chrome',headless:true,args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream']});await run(await browser.newPage(), 'chromium',url);await browser.close();browser=null;
  const main=join(temp,'main.cjs');await writeFile(main,`const {app,BrowserWindow}=require('electron');app.commandLine.appendSwitch('use-fake-device-for-media-stream');app.commandLine.appendSwitch('use-fake-ui-for-media-stream');app.whenReady().then(()=>{let w=new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}});w.loadURL(${JSON.stringify(url)});});`);
  const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;
  electron=await _electron.launch({executablePath:electronBinary(),args:[main],env});
  await run(await electron.firstWindow(),'electron',url);
  console.log('Screenshots:',temp);
} finally {await browser?.close();await electron?.close();await server.close();}
