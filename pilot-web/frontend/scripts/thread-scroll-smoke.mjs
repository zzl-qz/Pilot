import { browserOptions, artifactDirectory } from './browser-options.mjs';
// Linux 的截图需映射到虚拟显示；隐藏/恢复行为另由真实 SSE 生命周期测试覆盖。
import assert from 'node:assert/strict';
import { build, preview } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { prepareDevelopmentHost } from '../../../pilot-desktop/brand-host.mjs';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// 真实 App / reducer / store，历史接口只返回隔离夹具，不连接模型或用户后台。
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(artifactDirectory(), 'pilot-thread-scroll-'));
const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Pilot 历史滚动回归</title><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';import {createRoot} from 'react-dom/client';import App from '/src/components/App.jsx';import {useStore} from '/src/store.js';import {api} from '/src/api.js';import {applyEvent,initialThread} from '/src/thread-reducer.js';
import '/src/styles.css';
(async()=>{
document.documentElement.dataset.theme='dark';window.store=useStore;window.api=api;window.historyCalls=[];window.events=[];
window.detailCalls=[];window.historySizes=[];window.diffCalls=[];
const mode=new URL(location.href).searchParams.get('mode')||'long';window.failEarlier=mode==='failure';
for(const name of Object.keys(api))if(typeof api[name]==='function')api[name]=async()=>({});
api.workProducts=api.skillDrafts=api.schedules=api.bookmarks=async()=>[];api.queue=async()=>({items:[]});api.filesFlat=async()=>({files:[]});
api.history=async(_id,options={})=>{
  historyCalls.push(options.before??null);await new Promise(resolve=>setTimeout(resolve,20));
  if(options.before&&window.failEarlier)throw Error('测试历史暂不可用');
  const matching=events.filter(event=>options.before==null||event.sequence<options.before);
  const page=matching.slice(-(options.limit||80));
  const compact=page.map(event=>event.type==='tool.completed'&&event.payload.resultSummary?{...event,detailAvailable:true,payload:{...event.payload,resultSummary:Array.from(event.payload.resultSummary.split('\\n')[0]).slice(0,80).join('')}}:event);
  const response=options.compactTools?compact:page;
  historySizes.push([new TextEncoder().encode(JSON.stringify(page)).length,new TextEncoder().encode(JSON.stringify(response)).length]);
  return {events:response,hasMore:matching.length>page.length,historyRevision:0};
};
api.event=async(_id,sequence,revision)=>{
  detailCalls.push(sequence);await new Promise(resolve=>setTimeout(resolve,20));
  if(detailCalls.length===1)throw Error('测试详情暂不可用');
  return {event:events.find(event=>event.sequence===sequence),historyRevision:revision};
};
useStore.setState({init:async()=>{},sessionId:'scroll-test',sessionTitle:'检查长回复和更早的问题',workspace:'/workspace/pilot',thread:initialThread(),projects:[]});
window.send=(type,payload={},runId='latest')=>{const event={type,payload,runId,sessionId:'scroll-test',sequence:events.length+1,timestamp:Date.now()};events.push(event);useStore.setState(draft=>applyEvent(draft,event,{live:true}));};
send('run.started',{input:'之前的第一个问题'},'old');send('assistant.delta',{text:'之前的回答仍可查看。'},'old');send('run.completed',{summary:'之前的回答仍可查看。'},'old');
if(mode==='heavy'){
  send('run.started',{input:'很多操作的旧问题'},'run_old_tools');
  for(let i=0;i<30;i++){
    const callId='detail-'+i;
    send('tool.started',{callId,name:'run_command',summary:'command=echo case-'+i},'run_old_tools');
    send('tool.completed',{callId,resultSummary:'详细工具输出 '+i+'\\n'+'正文内容。'.repeat(5000),latencyMs:12},'run_old_tools');
  }
  send('run.ended',{outcome:'COMPLETED',summary:'已完成',changedFiles:['old.txt','binary.png']},'run_old_tools');
  api.runChanges=async(_id,summary=false)=>{
    diffCalls.push(summary);
    return {files:[{path:'old.txt',added:3,removed:1,diff:summary?null:'diff --git a/old.txt b/old.txt\\n@@ -1 +1,3 @@\\n-old\\n+new patch\\n+line 2\\n+line 3'},
      {path:'binary.png',countsKnown:false,note:'文件是二进制',diff:''}]};
  };
  for(let i=0;i<79;i++)send('execution.updated');
  send('assistant.delta',{text:('最新回复\\n\\n').repeat(180)});
  await useStore.getState().loadHistory();
}
if(mode==='long'){
  send('run.started',{input:'当前的长回复问题'});
  send('plan.updated',{plan:[{step:'检查长回复的滚动位置',status:'completed'},{step:'核对回到底部按钮的停靠位置',status:'in_progress'},{step:'回归三种宽度与侧栏变化',status:'pending'}]});
  for(let i=0;i<330;i++)send('assistant.delta',{text:i%10===0?'\\n\\n第'+(i/10+1)+'段：检查长回复、事件窗口和历史消息。':'保留完整问答。'});
  send('run.completed',{summary:'完整结论。'.repeat(70)});
}else if(mode!=='heavy'){
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
async function assertLatestPosition(page, label) {
  const button = page.getByRole('button', { name: '回到底部', exact: true });
  await button.waitFor();
  const bounds = await page.evaluate(() => {
    const rect = selector => {
      const { x, y, width, height } = document.querySelector(selector).getBoundingClientRect();
      return { x, y, width, height };
    };
    const thread = document.querySelector('.thread');
    const plan = document.querySelector('.thread-pinned-plan');
    return { button: rect('.conversation-latest'), column: rect('.thread-column'), region: rect('.thread-scroll-region'), scroll: rect('.thread'), composer: rect('.conversation-composer'), plan: plan ? rect('.thread-pinned-plan') : null, scrollbar: thread.offsetWidth - thread.clientWidth };
  });
  const { button: b, column: c, region: r, composer, plan } = bounds;
  // 部分平台的常驻滚动条会占据消息列右侧空间；按钮与输入列共用对称留白。
  assert.ok(Math.abs(b.x + b.width / 2 - c.x - c.width / 2) <= bounds.scrollbar / 2 + 2,
    label + ': 回底按钮对齐消息列中心 ' + JSON.stringify(bounds));
  assert.ok(b.y >= r.y && b.y + b.height <= r.y + r.height && b.y + b.height <= composer.y,
    label + ': 回底按钮位于消息区内，不遮挡输入框');
  assert.ok(b.y >= bounds.scroll.y + bounds.scroll.height,
    label + ': 回底按钮在消息滚动区之外，不遮正文 ' + JSON.stringify(bounds));
  assert.ok(plan, label + ': 夹具带执行计划条，可验证按钮避让');
  assert.ok(b.y + b.height <= plan.y,
    label + ': 回底按钮停在计划条之上，不重叠 ' + JSON.stringify(bounds));
  assert.ok(b.width >= 32 && b.height >= 32, label + ': 保留按钮点击区域');
}
async function run(page, name, { screenshots = true } = {}) {
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
  await assertLatestPosition(page, name + '-1280');
  // DSH 式全自动无感翻页：上滑靠近顶部时自动加载所有更早历史，无需手动点击按钮，也不弹出手动按钮打扰。
  await page.mouse.wheel(0,-100000);
  await page.waitForFunction(()=>store.getState().thread.rows.some(row=>row.text==='之前的第一个问题'));
  assert.equal(await page.getByRole('button',{name:'加载更早消息',exact:true}).count(),0,'正常自动加载时不渲染手动加载按钮');
  assert.ok(await page.evaluate(()=>historyCalls.filter(before=>before!==null).length>=1),'接近顶部时自动请求更早历史');
  await page.locator('.thread').evaluate(el=>{el.scrollTop=0;});
  assert.ok(await page.getByText('之前的第一个问题',{exact:true}).isVisible());
  if (screenshots) await page.screenshot({path:join(output,name+'-earlier.png'),animations:'disabled'});
  await page.getByRole('button',{name:'回到底部',exact:true}).click();
  await page.waitForFunction(()=>{const el=document.querySelector('.thread');return el.scrollHeight-el.clientHeight-el.scrollTop<5;});

  await page.goto(url+'?mode=heavy');
  await page.waitForFunction(()=>document.querySelector('.thread')?.scrollTop>500);
  await page.locator('.thread').hover();
  await page.mouse.wheel(0,-100000);
  await page.getByText('很多操作的旧问题',{exact:true}).waitFor().catch(async error => {
    console.error(await page.evaluate(() => ({ historyCalls, scrollTop: document.querySelector('.thread').scrollTop,
      rows: store.getState().thread.rows.map(row => [row.type, row.text, row.start]), history: store.getState().thread.history })));
    throw error;
  });
  assert.equal(await page.locator('.tool-group .tool-row').count(),0,'折叠时不挂载操作行');
  assert.equal(await page.evaluate(()=>detailCalls.length),0,'上滑不读取工具详情');
  const sizes=await page.evaluate(()=>historySizes.find(([full])=>full>100000));
  assert.ok(sizes && sizes[1]<sizes[0]/10,'历史分页响应保留摘要');
  console.log(name+': 30 项操作历史页 '+sizes[0]+' → '+sizes[1]+' 字节');
  const unknown=page.getByRole('button',{name:'查看 binary.png 的改动'});
  assert.match(await unknown.innerText(),/行数未知/);
  const changed=page.getByRole('button',{name:'查看 old.txt 的改动'});
  assert.match(await changed.innerText(),/\+3.*-1/s);
  assert.deepEqual(await page.evaluate(()=>diffCalls),[true],'翻页只读取改动统计');
  await changed.hover();
  await page.locator('[data-changes-hover-preview]').getByText('new patch',{exact:true}).waitFor();
  assert.deepEqual(await page.evaluate(()=>diffCalls),[true,false],'悬停预览时读取补丁');
  await page.keyboard.press('Escape');
  const group=page.locator('.tool-group').first();
  await group.locator('summary').click();
  assert.equal(await page.evaluate(()=>detailCalls.length),0,'打开操作组只展示摘要');
  const detailLine=group.locator('.conversation-tool-line').last();
  await detailLine.click();
  await page.getByRole('alert').filter({hasText:'详情加载失败'}).waitFor();
  await group.getByRole('button',{name:'重试',exact:true}).click();
  await group.locator('.tool-body pre').filter({hasText:'详细工具输出 29'}).waitFor();
  assert.equal(await page.evaluate(()=>detailCalls.length),2,'只对同一条展开操作读取详情和重试');
  await detailLine.click();
  await detailLine.click();
  assert.equal(await page.evaluate(()=>detailCalls.length),2,'同一行重新打开使用已读取的详情');

  await page.goto(url+'?mode=sparse');
  // 夹具先生成事件，再异步读取最后一页并挂载 App。等待真实消息出现，跳过挂载前的种子状态。
  await page.getByText('之前的第一个问题',{exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>store.getState().thread.history.hasMore),false,'不足一屏时跨过全部不可见事件页');
  assert.ok(await page.evaluate(()=>historyCalls.length)<10,'分页请求有界');
  assert.equal(await page.evaluate(()=>store.getState().thread.windowEvents[0].sequence),1);

  await page.goto(url+'?mode=empty');
  await page.getByText('之前的第一个问题',{exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>store.getState().thread.history.hasMore),false,'末页全是不可见事件时仍能加载历史');

  await page.goto(url+'?mode=failure');
  await page.mouse.wheel(0,-100000);
  const retry=page.getByRole('button',{name:'加载更早失败，点击重试',exact:true});await retry.waitFor();
  const failedCount=await page.evaluate(()=>historyCalls.length);await page.waitForTimeout(400);
  assert.equal(await page.evaluate(()=>historyCalls.length),failedCount,'失败等待重试，不循环打接口');
  await page.evaluate(()=>window.failEarlier=false);await retry.click();
  await page.waitForFunction(()=>store.getState().thread.rows.some(row=>row.text==='之前的第一个问题'));
  assert.equal(await page.evaluate(()=>store.getState().thread.history.error),false);
  for(const width of [1440,768,390]){
    await page.setViewportSize({width,height:840});await page.goto(url);await page.locator('.thread').waitFor();
    await page.waitForFunction(()=>{const el=document.querySelector('.thread');return el.scrollTop>500;});
    // Chromium 覆盖真实键盘路径；隐藏 Electron 窗口用滚动事件覆盖多宽度几何。
    if (screenshots) { await page.locator('.thread').focus();await page.keyboard.press('Home'); }
    else await page.locator('.thread').evaluate(el=>{el.scrollTop=0;el.dispatchEvent(new Event('scroll'));});
    await page.waitForFunction(()=>store.getState().thread.rows.some(row=>row.text==='之前的第一个问题'));
    await page.locator('.thread').hover();await page.mouse.wheel(0,-500);
    await assertLatestPosition(page, name + '-' + width);
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'无页面横向溢出');
    if (screenshots) await page.screenshot({path:join(output,name+'-'+width+'.png'),animations:'disabled'});
  }
  // 窗口不变，侧栏和审阅面板改变消息列宽度时也要重新定位。
  await page.setViewportSize({width:1440,height:840});await page.goto(url);
  await page.waitForFunction(()=>document.querySelector('.thread')?.scrollTop>500);
  await page.locator('.thread').hover();await page.mouse.wheel(0,-500);
  for(const ui of [
    {sidebarOpen:false,inspectorOpen:false},
    {sidebarOpen:true,inspectorOpen:true,inspectorTab:'changes'},
    {sidebarOpen:false,inspectorOpen:true,inspectorTab:'changes'}
  ]) {
    await page.evaluate(ui=>store.getState().setUi(ui),ui);
    await page.waitForTimeout(250);
    await assertLatestPosition(page, name + '-' + JSON.stringify(ui));
  }
  assert.deepEqual(errors,[]);
  console.log(name+': PASS 长回复收尾 / 接近顶部自动加载并保留手动入口 / 阅读中增量 / 回到底部 / 跨不可见页和空行窗口 / 失败重试 / 键盘入口 / 三种宽度');
}
try{
  if(process.argv.includes('--serve')){console.log(url);await new Promise(()=>{});}
  browser=await chromium.launch(browserOptions({ headless: true }));await run(await browser.newPage(),'chromium');await browser.close();browser=null;
  if(!process.argv.includes('--web-only')){
    const desktopRoot = fileURLToPath(new URL('../../../pilot-desktop', import.meta.url));
    const env = { ...process.env, PILOT_URL: url, PILOT_BROWSER_HOME: join(output, 'browser') };
    delete env.ELECTRON_RUN_AS_NODE;
    delete env.PILOT_DESKTOP_SMOKE;
    electron = await _electron.launch({ chromiumSandbox: true, executablePath: prepareDevelopmentHost(),
      args: [desktopRoot, `--user-data-dir=${join(output, 'profile')}`], env });
    await run(await electron.firstWindow(), 'electron');
  }
  console.log('Screenshots:',output);
}finally{await browser?.close();await electron?.close();await new Promise(resolve=>server.httpServer.close(resolve));}
