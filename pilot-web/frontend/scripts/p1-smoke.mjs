import { openGlobalFunction } from './global-navigation.mjs';
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
const output = await mkdtemp(join(tmpdir(), 'pilot-p1-ui-'));
const fixture = `<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react'; import {createRoot} from 'react-dom/client'; import App from '/src/components/App.jsx'; import {useStore} from '/src/store.js'; import {api} from '/src/api.js';
import '/src/styles.css'; import '/src/shell-vanilla.css'; import '/src/styles-addendum.css'; import '/src/workbench.css';
document.documentElement.dataset.theme='dark';window.store=useStore;window.calls=[];window.series=[];window.templates=[];window.searchFail=false;window.staleTemplate=false;
for(const name of Object.keys(api)) if(typeof api[name]==='function')api[name]=async()=>({});
api.schedules=async()=>[];api.results=async()=>({items:[]});api.worktreeInfo=async()=>({});api.queue=async()=>({items:[]});api.filesFlat=async()=>({files:[]});
api.sessions=async()=>[{sessionId:'source',title:'无人值守维护',workspace:'/workspace/pilot'}];
api.scheduleTimes=async(body)=>{calls.push({kind:'times',...body});if(body.timezone==='Invalid/Zone')throw Error('未知时区');const at=new Date(body.localTime+'Z').toISOString();return {runAt:at,next:[at,new Date(Date.parse(at)+86400000).toISOString()]};};
api.templates=async()=>structuredClone(templates);
api.saveTemplate=async(body)=>{calls.push({kind:'template',...body});if(window.staleTemplate)throw Error('模板已更新，请刷新后重试');const row={...body,id:body.id||'template-'+(templates.length+1),version:body.version+1};templates=templates.filter(t=>t.id!==row.id);templates.push(row);return structuredClone(row);};
api.deleteTemplate=async(id,version)=>{calls.push({kind:'delete-template',id,version});templates=templates.filter(t=>t.id!==id);};
api.recurring=async()=>structuredClone(series);
api.createRecurring=async(body)=>{calls.push({kind:'recurring',...body});const row={...body,id:'series-'+(series.length+1),name:body.task.name,timezone:body.task.timezone,state:'ACTIVE',nextAt:body.task.runAt,activeId:null,occurrences:0,message:'等待首次执行',version:1};series.push(row);return row;};
api.controlRecurring=async(id,body)=>{calls.push({kind:'series-control',id,...body});const row=series.find(r=>r.id===id);row.state={pause:'PAUSED',resume:'ACTIVE',cancel:'CANCELLED'}[body.action];row.version++;return structuredClone(row);};
const key=s=>'mock-p1-server-bookmarks:'+s;
api.bookmarks=async(s)=>JSON.parse(localStorage.getItem(key(s))||'[]');
api.saveBookmark=async(s,sequence)=>{calls.push({kind:'bookmark',s,sequence});const rows=await api.bookmarks(s);if(!rows.some(r=>r.sequence===sequence))rows.push({sequence,title:'检查跨平台文件同步',preview:'参考开源调度实现'});localStorage.setItem(key(s),JSON.stringify(rows));return rows.at(-1);};
api.deleteBookmark=async(s,sequence)=>localStorage.setItem(key(s),JSON.stringify((await api.bookmarks(s)).filter(r=>r.sequence!==sequence)));
api.importBookmarks=async(s,sequences)=>{calls.push({kind:'migration',s,sequences});for(const sequence of sequences)await api.saveBookmark(s,sequence);return {bookmarks:await api.bookmarks(s),skipped:[]};};
if(!localStorage.getItem('mock-p1-seeded')){localStorage.setItem('pilot.message-bookmarks:source',JSON.stringify([{sequence:1,title:'旧书签',preview:'迁移测试'}]));localStorage.setItem('mock-p1-seeded','1');}
api.searchMessages=async(body)=>{calls.push({kind:'search',...body});if(window.searchFail)throw Error('搜索连接断开');if(window.searchWait)await window.searchWait;return{items:body.q==='无匹配'?[]:[{sessionId:'source',title:'无人值守维护',sequence:2,anchor:1,role:'assistant',text:'已修复中文路径处理，参考成熟开源任务链路并完成验证。',bookmarked:false}],hasMore:false,nextOffset:1};};
window.config={version:0,desktop:false,webhookUrl:''};window.deliveries=[{id:'failed',version:2,resultId:'result-1',title:'跨平台维护需要处理',channel:'WEBHOOK',state:'FAILED',attempts:6,nextAt:new Date().toISOString(),error:'Webhook 返回 HTTP 503'}];window.pendingNotice=null;window.notices=[];
class FakeNotification{static permission='denied';static async requestPermission(){return this.permission;}constructor(title,options){notices.push({title,...options});setTimeout(()=>this.onshow?.(),10);}close(){}}
window.Notification=FakeNotification;
api.deliverySettings=async()=>structuredClone(config);
api.saveDeliverySettings=async(body)=>{calls.push({kind:'notification-settings',...body});config={...body,version:body.version+1};return structuredClone(config);};
api.deliveries=async()=>structuredClone(deliveries);
api.claimDelivery=async()=>{const value=window.pendingNotice;window.pendingNotice=null;return value;};
api.deliveryReceipt=async(id,body)=>calls.push({kind:'receipt',id,...body});
api.retryDelivery=async(id,version)=>{calls.push({kind:'notification-retry',id,version});deliveries[0].state='PENDING';deliveries[0].version++;};
window.makeRows=()=>[{type:'user',key:'u1',sequence:1,text:'检查跨平台文件同步'},{type:'turn',key:'a1',runIds:['r1'],tools:[],reply:{text:'已修复中文目录路径处理。',live:false},stats:{}}];
useStore.setState({init:async()=>{},activate:async(id)=>{useStore.setState({sessionId:id});window.activated=id;},sessionId:'source',workspace:'/workspace/pilot',sessionTitle:'无人值守维护',sessions:[{id:'source',title:'无人值守维护',workspace:'/workspace/pilot'}],projects:[],thread:{...useStore.getState().thread,rows:makeRows(),windowEvents:[{sequence:1},{sequence:2}],history:{...useStore.getState().thread.history,hasMore:false}}});
const screen=new URL(location.href).searchParams.get('screen');
if(screen==='series'){series.push({id:'preview',name:'工作日检查依赖升级',cron:'0 0 9 * * MON-FRI',timezone:'Asia/Shanghai',misfire:'ONCE',state:'ACTIVE',nextAt:'2026-11-04T01:00:00Z',occurrences:12,message:'已完成上一轮，等待下一次',version:1});useStore.getState().setUi({recurringOpen:true});}
if(screen==='search')useStore.getState().setUi({searchOpen:true});
if(screen==='notifications')useStore.getState().setUi({notificationsOpen:true});
if(screen==='form')useStore.getState().setUi({schedulesOpen:true,scheduleDraft:'参考开源实现检查项目依赖，修复失败测试并说明验证范围。',scheduleRepeat:true});
createRoot(document.getElementById('root')).render(React.createElement(App));
</script></body></html>`;
let server;
let entry = '__p1';
if(process.argv.includes('--production')) {
  entry = `.p1-fixture-${process.pid}.html`;
  const file = join(root, entry);
  try {
    await writeFile(file, fixture);
    await build({root,build:{outDir:output,emptyOutDir:false,rollupOptions:{input:file}}});
  } finally { await rm(file,{force:true}); }
  await cp(join(root,'../src/main/resources/static/fonts'),join(output,'fonts'),{recursive:true});
  const production = await preview({root,build:{outDir:output},preview:{host:'127.0.0.1',port:0,proxy:{}}});
  server = {httpServer:production.httpServer,listen:async()=>{},close:()=>new Promise(resolve=>production.httpServer.close(resolve))};
} else server = await createServer({root,server:{host:'127.0.0.1',port:0,proxy:{}},plugins:[{name:'p1-fixture',configureServer(server){server.middlewares.use('/__p1',async(req,res)=>{res.setHeader('Content-Type','text/html');res.end(await server.transformIndexHtml('/__p1',fixture));});}}]});
let browser, electron;
async function run(page,name,url) {
  page.setDefaultTimeout(10000);const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.setViewportSize({width:1440,height:900});await page.goto(url);
  await page.waitForFunction(()=>calls.some(c=>c.kind==='migration'));await page.locator('.thread-nav-tick.is-saved').waitFor();
  assert.equal(await page.evaluate(()=>localStorage.getItem('pilot.message-bookmarks:source')),null);
  assert.ok(await page.evaluate(()=>localStorage.getItem('pilot.message-bookmarks:source:migration-backup')));
  await openGlobalFunction(page, '定时任务');await page.getByRole('button',{name:'新建任务',exact:true}).click();
  await page.getByLabel('任务名称',{exact:true}).fill('工作日维护');await page.getByLabel('任务目标',{exact:true}).fill('参考开源实现检查依赖并修复测试');await page.getByLabel('验收条件',{exact:true}).fill('相关测试通过');
  await page.getByText(/^任务模板/).click();await page.getByLabel('模板名称',{exact:true}).fill('维护模板');await page.getByRole('button',{name:'另存为模板',exact:true}).click();
  await page.waitForFunction(()=>templates.length===1);assert.equal(await page.evaluate(()=>templates[0].version),1);
  await page.getByLabel('任务目标',{exact:true}).fill('更新后的维护目标');await page.getByRole('button',{name:'更新所选模板',exact:true}).click();await page.waitForFunction(()=>templates[0].version===2);
  await page.getByLabel('任务目标',{exact:true}).fill('临时输入');await page.getByLabel('使用模板',{exact:true}).selectOption('');await page.getByLabel('使用模板',{exact:true}).selectOption('template-1');assert.equal(await page.getByLabel('任务目标',{exact:true}).inputValue(),'更新后的维护目标');
  await page.getByLabel('执行时区',{exact:true}).fill('Invalid/Zone');await page.getByRole('button',{name:'预览执行时间'}).click();await page.getByRole('alert').filter({hasText:'未知时区'}).waitFor();
  await page.getByLabel('执行时区',{exact:true}).fill('UTC');await page.getByLabel('重复周期',{exact:true}).selectOption('weekdays');await page.getByLabel('错过策略',{exact:true}).selectOption('SKIP');
  await page.getByRole('button',{name:'24 小时后'}).click();
  const preset = await page.getByLabel('执行时间',{exact:true}).inputValue();
  assert.ok(Math.abs(Date.parse(preset+'Z')-Date.now()-86400000)<65000,'快捷时间按所选 UTC 时区填写');
  await page.getByRole('button',{name:'预览执行时间'}).click();await page.locator('.schedule-times li').first().waitFor();
  await page.getByRole('button',{name:'创建计划',exact:true}).click();await page.getByRole('heading',{name:'周期计划',exact:true}).waitFor();
  const request=await page.evaluate(()=>calls.find(c=>c.kind==='recurring'));assert.equal(request.misfire,'SKIP');assert.equal(request.task.timezone,'UTC');assert.ok(request.cron.endsWith('MON-FRI'));assert.equal(request.task.isolated,true);
  await page.getByRole('button',{name:'暂停周期',exact:true}).click();await page.getByRole('button',{name:'恢复周期',exact:true}).click();await page.getByRole('button',{name:'取消周期',exact:true}).click();await page.waitForFunction(()=>series[0].state==='CANCELLED');await page.keyboard.press('Escape');
  await page.getByRole('button',{name:'搜索消息与书签',exact:true}).click();await page.getByLabel('搜索消息内容').fill('中文');await page.getByRole('button',{name:'搜索',exact:true}).click();await page.locator('.search-hit').waitFor();
  await page.getByRole('button',{name:'收藏所在轮次'}).click();assert.equal(await page.evaluate(()=>calls.filter(c=>c.kind==='bookmark').at(-1).sequence),1);
  await page.locator('.search-hit').click();await page.waitForFunction(()=>!document.querySelector('.search-dialog'));await page.waitForFunction(()=>document.querySelector('[data-sequence="1"]')?.classList.contains('is-navigation-target'));
  await page.getByRole('button',{name:'搜索消息与书签',exact:true}).click();await page.getByLabel('搜索消息内容').fill('无匹配');await page.getByRole('button',{name:'搜索',exact:true}).click();await page.getByText('没有匹配的消息，请更换关键词或搜索范围。').waitFor();
  await page.getByLabel('搜索消息内容').fill('中文');await page.evaluate(()=>{window.searchFail=true;});await page.getByRole('button',{name:'搜索',exact:true}).click();await page.getByRole('alert').filter({hasText:'搜索连接断开'}).waitFor();await page.evaluate(()=>{window.searchFail=false;});
  await page.evaluate(()=>{window.searchWait=new Promise(r=>window.resolveSearch=r);});await page.getByRole('button',{name:'搜索',exact:true}).click();await page.getByLabel('搜索消息内容').fill('新条件');await page.evaluate(()=>{window.resolveSearch();window.searchWait=null;});assert.equal(await page.locator('.search-hit').count(),0);await page.keyboard.press('Escape');
  await page.getByRole('button',{name:/任务收件箱/}).click();await page.getByRole('button',{name:'结果操作',exact:true}).click();await page.getByRole('menuitem',{name:'通知与投递',exact:true}).click();await page.getByRole('heading',{name:'通知与投递',exact:true}).waitFor();
  await page.getByLabel(/桌面通知/).check();await page.getByRole('button',{name:'保存通知设置'}).click();await page.getByRole('alert').filter({hasText:'系统通知未获授权'}).waitFor();assert.equal(await page.evaluate(()=>config.desktop),false);
  await page.evaluate(()=>{Notification.permission='granted';window.pendingNotice={id:'notice-1',lease:'lease-1',resultId:'result-1',title:'维护完成',summary:'相关测试通过'};});
  await page.getByLabel('Webhook 地址').fill('https://example.invalid/pilot-notify');await page.getByRole('button',{name:'保存通知设置'}).click();await page.waitForFunction(()=>calls.some(c=>c.kind==='receipt'&&c.success));
  assert.equal(await page.evaluate(()=>notices[0].tag),'notice-1');assert.equal(await page.evaluate(()=>config.desktop),true);
  const created=await page.evaluate(()=>calls.filter(c=>c.kind==='recurring').length);await page.getByRole('button',{name:'重试通知'}).click();await page.waitForFunction(()=>deliveries[0].state==='PENDING');assert.equal(await page.evaluate(()=>calls.filter(c=>c.kind==='recurring').length),created);await page.keyboard.press('Escape');
  // A new client page sees bookmarks from the simulated service.
  await page.reload();await page.locator('.thread-nav-tick.is-saved').waitFor();
  for(const width of [1440,768,390]) {
    await page.setViewportSize({width,height:900});
    for(const screen of ['series','form','search','notifications']) {
      await page.goto(url+'?screen='+screen);await page.locator('dialog').waitFor();
      assert.ok(await page.locator('dialog').evaluate(el=>el.scrollWidth<=el.clientWidth+1));
      await checkDialogLayout(page);
      await page.screenshot({path:join(output,`${name}-${width}-${screen}.png`)});
    }
  }
  await page.evaluate(()=>document.documentElement.dataset.theme='light');await page.screenshot({path:join(output,`${name}-390-light.png`)});
  assert.deepEqual(errors,[]);console.log(name+': PASS recurring/templates/timezone/search/bookmark-migration/notification-permission/retry/stale-response/responsive');
}

try {
  await server.listen();const url=`http://127.0.0.1:${server.httpServer.address().port}/${entry}`;
  if(process.argv.includes('--serve')) {console.log(url+'?screen=series');await new Promise(()=>{});}
  browser=await chromium.launch({channel:'chrome',headless:true}); await run(await browser.newPage(),'chromium',url); await browser.close();browser=null;
  // Keep storage isolated and let run() perform the only initial fixture navigation.
  // Loading it here as well can finish migration before run() resets the call log.
  const main=join(output,'main.cjs'); await writeFile(main,`const {app,BrowserWindow}=require('electron');app.setPath('userData',${JSON.stringify(join(output,'electron-profile'))});app.whenReady().then(()=>{let w=new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}});w.loadURL('about:blank');});`);
  const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;
  electron=await _electron.launch({executablePath:electronBinary(),args:[main],env});await run(await electron.firstWindow(),'electron',url);
  console.log('Screenshots:',output);
} finally {await browser?.close();await electron?.close();await server.close();}
