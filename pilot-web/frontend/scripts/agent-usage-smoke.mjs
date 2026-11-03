import assert from 'node:assert/strict';
import { build, preview } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-agent-usage-'));
const entry = `.agent-usage-fixture-${process.pid}.html`, file = join(root, entry);
const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Pilot 任务用量</title><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';import {createRoot} from 'react-dom/client';import App from '/src/components/App.jsx';import {useStore} from '/src/store.js';import {api} from '/src/api.js';import {applyEvent,initialThread} from '/src/thread-reducer.js';
import '/src/styles.css';import '/src/shell-vanilla.css';import '/src/styles-addendum.css';import '/src/workbench.css';
document.documentElement.dataset.theme='dark';window.store=useStore;
for(const name of Object.keys(api))if(typeof api[name]==='function')api[name]=async()=>({});
api.files=async()=>({entries:[]});api.results=async()=>({items:[]});api.schedules=async()=>[];
useStore.setState({init:async()=>{},sessionId:'usage-fixture',sessionTitle:'检查文件粘贴与请求用量',workspace:'/workspace/pilot',projects:[],sessions:[]});
useStore.getState().setUi({sidebarOpen:false,inspectorOpen:false});
let sequence=0;
window.send=(type,payload)=>useStore.setState(d=>applyEvent(d,{type,payload,runId:'run-usage',sequence:++sequence,sessionId:'usage-fixture',timestamp:1700000000000+sequence*1000},{live:false}));
window.seed=(count=16,agentCount=2)=>{
 useStore.setState({thread:initialThread(),agents:{},requestReports:[],latestContextReport:null,runUsage:null,running:false});useStore.getState().setUi({agentsDockOpen:false});
 send('run.started',{input:'检查文件粘贴与请求用量'});
 for(let i=0;i<agentCount;i++){
  send('subagent.started',{agentId:'agent-'+i,name:i?'核对桌面端剪贴板':'检查浏览器文件粘贴',task:'独立核对文件粘贴路径、浏览器能力检测与失败反馈。'});
  send('subagent.finished',{agentId:'agent-'+i,ok:i!==1,result:i===1?'缺少测试依赖，需要主任务继续处理。':'已经核对文件类型与粘贴入口。'});
 }
 for(let i=0;i<count;i++)send('model.request',{report:{id:'request-'+i,purpose:i<10?'main':'subagent',model:'gpt-6.1-sol',status:i===count-1?'failed':'completed',durationMs:2310,firstTokenMs:321,usage:{inputTokens:10000,outputTokens:100,cachedInputTokens:8000,exact:true,cacheReadObserved:true},cost:i%2?{currency:'USD',total:0.005,input:0.002,cachedInput:0.001,output:0.002}:{total:null,note:'未配置单价'},prefix:{change:'append_only'}}});
 send('run.usage',{modelRequests:count,inputTokens:count*10000,outputTokens:count*100,contextTokens:24000,contextLimit:300000,exact:true,details:{requests:count,pricedRequests:Math.floor(count/2),costs:{USD:Math.floor(count/2)*0.005},observedCacheRequests:count,observedCacheInputTokens:count*10000,cacheReadTokens:count*8000}});
 useStore.setState(d=>{d.running=false;const turn=d.thread.rows.find(r=>r.type==='turn');turn.stats.finishedAt=turn.stats.startedAt+123000;turn.reply={text:'已完成检查。浏览器与桌面端复用相同的文件校验；后续可在请求明细中核对费用与缓存命中。',live:false};});
};
seed();createRoot(document.getElementById('root')).render(React.createElement(App));
if(new URL(location.href).searchParams.has('inspect'))setTimeout(()=>document.querySelector('.turn-stats-detail')?.setAttribute('open',''),200);
</script></body></html>`;
try { await writeFile(file, fixture); await build({ root, build: { outDir: output, emptyOutDir: false, rollupOptions: { input: file } } }); }
finally { await rm(file, { force: true }); }
await cp(join(root, '../src/main/resources/static/fonts'), join(output, 'fonts'), { recursive: true });
const server = await preview({ root, build: { outDir: output }, preview: { host: '127.0.0.1', port: 0, proxy: {} } });
const url = `http://127.0.0.1:${server.httpServer.address().port}/${entry}`;
let browser, electron;
async function run(page, name) {
  page.setDefaultTimeout(8000);
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.setViewportSize({ width: 1280, height: 900 }); await page.goto(url);
  const stats = page.locator('.turn-stats-detail'); await stats.waitFor();
  await stats.locator(':scope > summary').click();
  if (process.argv.includes('--before')) {
    await stats.scrollIntoViewIfNeeded(); await page.screenshot({ path: join(output, `${name}-before.png`) }); return;
  }
  assert.equal(await page.evaluate(() => Object.keys(store.getState().agents).length), 2, '16 次模型请求只对应 2 个真实子智能体');
  assert.equal(await page.locator('.agent-group > summary').innerText(), '2 个子智能体 · 1 失败');
  assert.equal(await page.locator('.agent-card:visible').count(), 0, '智能体默认只显示一行汇总');
  assert.equal(await page.locator('#agents-dock').count(), 0, '已结束子任务不重复占据输入框上方');
  assert.equal(await stats.locator('.request-group').count(), 2);
  assert.equal(await stats.locator('.request-item:visible').count(), 0, '展开用量不铺开每次请求');
  const sub = stats.locator('.request-group[data-purpose="subagent"]');
  assert.match(await sub.locator(':scope > summary').innerText(), /子代理请求.*6 次/);
  assert.match(await sub.locator(':scope > summary').innerText(), /1 次失败/);
  assert.match(await sub.locator(':scope > summary').innerText(), /部分/);
  assert.ok((await stats.boundingBox()).height < 260, '16 次请求的默认用量面板保持紧凑');
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 900 }); await stats.scrollIntoViewIfNeeded();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '页面没有横向溢出');
    assert.ok(await stats.evaluate(el => el.scrollWidth <= el.clientWidth + 1), '用量明细没有横向溢出');
    await page.screenshot({ path: join(output, `${name}-${width}.png`) });
  }
  await sub.locator(':scope > summary').focus(); await page.keyboard.press('Enter');
  assert.equal(await sub.locator('.request-item:visible').count(), 6);
  const failed = sub.locator('.request-item').first();
  await failed.locator('summary').click();
  assert.match(await failed.innerText(), /请求失败/); assert.match(await failed.innerText(), /80%/);
  assert.match(await failed.innerText(), /0.32s/); assert.match(await failed.innerText(), /缓存读取/);
  await failed.locator('summary').click();
  assert.equal(await failed.locator('.request-values').isVisible(), false);
  await sub.locator(':scope > summary').focus(); await page.keyboard.press('Space');
  assert.equal(await sub.locator('.request-item:visible').count(), 0);
  await page.locator('.agent-group > summary').click();
  assert.equal(await page.locator('.agent-card:visible').count(), 2);
  const agent = page.locator('.agent-card.is-failed');
  await agent.locator('summary').click(); assert.match(await agent.innerText(), /缺少测试依赖/);
  await page.locator('.agent-group > summary').click();
  const dock = page.locator('#agents-dock');
  await page.locator('#agents-chip').focus(); await page.keyboard.press('Enter');
  assert.equal(await dock.locator('.agents-dock-list li').count(), 2);
  assert.doesNotMatch(await dock.innerText(), /运行中/);
  await dock.locator('button.agents-dock-head').focus(); await page.keyboard.press('Space');
  assert.equal(await dock.count(), 0);
  assert.equal(await page.evaluate(() => document.activeElement.id), 'agents-chip');
  await page.evaluate(() => seed(80, 12));
  await stats.locator(':scope > summary').evaluate(el => { if (!el.parentElement.open) el.click(); });
  await sub.locator(':scope > summary').click();
  assert.equal(await sub.locator('.request-item').count(), 70);
  assert.ok((await sub.locator('.request-list').boundingBox()).height <= 321, '长请求列表在内部滚动');
  await page.locator('.agent-group > summary').click();
  assert.ok((await page.locator('.agent-group-list').boundingBox()).height <= 281, '长智能体列表在内部滚动');
  await page.locator('.agent-group > summary').click();
  await page.evaluate(() => { store.getState().setTheme('light'); seed(16, 2); });
  await stats.locator(':scope > summary').click();
  await sub.locator(':scope > summary').evaluate(el => { if (el.parentElement.open) el.click(); });
  await page.screenshot({ path: join(output, `${name}-light.png`) });
  // 长模型名、未知用途、未知价格保留反馈，不能误报免费。
  await page.evaluate(() => send('model.request', { report: { id: 'unknown', purpose: 'future-purpose', model: 'very-long-model-name-'.repeat(15), status: 'failed', cost: { total: null } } }));
  const other = stats.locator('.request-group[data-purpose="other"]');
  await other.locator(':scope > summary').click(); await other.locator('.request-item summary').click();
  assert.match(await other.innerText(), /未计价/); assert.match(await other.innerText(), /端点未提供/);
  assert.ok(await stats.evaluate(el => el.scrollWidth <= el.clientWidth + 1));
  await page.evaluate(() => seed(0, 0));
  assert.equal(await page.locator('.agent-group').count(), 0); assert.equal(await dock.count(), 0);
  assert.equal(await stats.locator('.request-group').count(), 0);
  await page.evaluate(() => send('subagent.started', { agentId: 'running', task: '检查剩余文件' }));
  assert.match(await dock.innerText(), /1 运行中/);
  await page.evaluate(() => send('run.failed', { code: 'CANCELLED' }));
  assert.equal(await dock.count(), 0, '主任务取消后不能残留运行中的子任务');
  assert.deepEqual(errors, []);
  console.log(`${name}: PASS 请求与智能体计数、分组折叠、费用缺失/失败、键盘、长列表、明暗主题及三种宽度`);
}
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true }); await run(await browser.newPage(), 'chromium');
  await browser.close(); browser = null;
  if (!process.argv.includes('--before')) {
    const main = join(output, 'main.cjs');
    await writeFile(main, `const {app,BrowserWindow}=require('electron');app.whenReady().then(()=>new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}}).loadURL(${JSON.stringify(url)}));`);
    const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE;
    electron = await _electron.launch({ executablePath: electronBinary(), args: [main], env });
    await run(await electron.firstWindow(), 'electron');
  }
  if (process.env.PILOT_UI_INSPECTOR) await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [process.env.PILOT_UI_INSPECTOR, url + '?inspect', '--app', '--out', join(output, 'inspection')], { stdio: 'inherit' });
    child.on('error', reject); child.on('exit', code => code === 0 ? resolve() : reject(Error(`UI inspection exit ${code}`)));
  });
  console.log(`Screenshots: ${output}`);
} finally { await browser?.close(); await electron?.close(); await new Promise(resolve => server.httpServer.close(resolve)); }
