import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium, _electron } from 'playwright-core';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import { mkdtemp, writeFile, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-products-ui-'));
const capture = !process.argv.includes('--no-screenshots');
const keep = new Set(['workProducts', 'productPreview', 'skillDrafts', 'decideSkill', 'downloadProduct', 'workbenchFiles', 'workbenchPreview', 'workbenchPreviewImage', 'workbenchAnnotations', 'workbenchVersions', 'workbenchText', 'workbenchOpen', 'workbenchDownload', 'workbenchRevert']);
const fixture = `<html lang="zh-CN" data-theme="dark"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react'; import {createRoot} from 'react-dom/client'; import App from '/src/components/App.jsx'; import {useStore} from '/src/store.js'; import {api} from '/src/api.js'; import {initialThread} from '/src/thread-reducer.js';
import '/src/styles.css'; import '/src/shell-vanilla.css'; import '/src/styles-addendum.css'; import '/src/workbench.css';
const keep = new Set(${JSON.stringify([...keep])}); for (const key of Object.keys(api)) if (typeof api[key] === 'function' && !keep.has(key)) api[key]=async()=>({});
api.files=async()=>({entries:[]}); api.results=async()=>({items:[]}); api.schedules=async()=>[];
useStore.setState({init:async()=>{},sessionId:'s1',sessionTitle:'整理调研结论与可复用的方法',projects:[],sessions:[],workspace:'/workspace/pilot',thread:initialThread()});
useStore.getState().setUi({sidebarOpen:false,inspectorOpen:true,inspectorTab:'products'}); window.store=useStore;
createRoot(document.getElementById('root')).render(React.createElement(App));
</script></body></html>`;
const loginHtml = await readFile(new URL('../../../pilot-server/src/main/resources/remote-connect.html', import.meta.url), 'utf8');
const server = await createServer({ root, server: {host:'127.0.0.1',port:0,proxy:{}}, plugins:[{name:'products-fixture',configureServer(s){
  s.middlewares.use('/__products',async(req,res)=>{res.setHeader('Content-Type','text/html');res.end(await s.transformIndexHtml('/__products',fixture));});
  s.middlewares.use('/connect', (req,res)=>{res.setHeader('Content-Type','text/html');res.end(loginHtml.replaceAll('__NONCE__','fixture'));});
}}]});
let browser, electron;
async function run(page, name, url) {
  page.setDefaultTimeout(10000); const errors=[]; page.on('pageerror',e=>errors.push(e.message));
  let draft={id:'d1',name:'research-report',description:'将已核对的资料整理为可交付报告',body:'## 操作步骤\n\n1. 阅读原始来源，记录时间。\n2. 交叉核对结论与数字。\n3. 生成报告，检查引用后保存。\n\n遇到来源不可用时标记缺失，不推测内容。',evidence:'本次报告已检查 3 条引用与汇总数据；未验证付费数据源。',status:'PENDING',version:1};
  const product={id:'p1',title:'技术选型调研报告',filename:'选型报告.md',summary:'对比部署方式、维护成本与适用场景，附原始资料。',bytes:4200,createdAt:'2026-11-04T02:00:00Z',sources:[{url:'https://example.org/reference',observedRead:true},{url:'https://example.org/extra',observedRead:false}]};
  let failure='', downloadFailure=false, listFailure=false, previewFailure=false, imageFailure=true;
  const decisions=[], officeCalls=[];
  const asset = (id, filename, format) => ({ id, filename, format, path:`交付资料/${filename}`, bytes:512, sha256:'v2', modifiedAt:'2026-11-09T00:00:00Z' });
  const officeFiles=[asset('sheet','检查表.xlsx','xlsx'),asset('document','部署记录.pdf','pdf')];
  const pixel=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRgAAAAAASUVORK5CYII=','base64');
  await page.route('**/api/sessions/*/**',async route=>{
    const req=route.request(), path=new URL(req.url()).pathname;
    if (path.startsWith('/api/sessions/s2/')) return route.fulfill({json:[]});
    if (path.includes('/workbench/files')) {
      if (path.endsWith('/files')) return route.fulfill({json:officeFiles});
      if (path.endsWith('/preview/1')) return imageFailure
        ? route.fulfill({status:503,json:{error:'预览生成失败'}})
        : route.fulfill({contentType:'image/png',body:pixel});
      if (path.endsWith('/preview')) return route.fulfill({json:{fileId:'document',pages:1,notice:'当前文件的页面预览'}});
      if (path.endsWith('/text')) return route.fulfill({json:{text:'验收记录'}});
      if (path.endsWith('/versions')) return route.fulfill({json:[{version:1,label:'审批前',createdAt:'2026-11-08T00:00:00Z'}]});
      if (path.endsWith('/annotations')) return route.fulfill({json:[]});
      if (path.endsWith('/open')) return route.fulfill({json:{ok:false,reason:'无法启动 PDF 应用。',alternative:'请下载文件后打开。'}});
      if (path.endsWith('/download')) return route.fulfill({status:503,body:'offline'});
      if (path.endsWith('/revert')) {
        officeCalls.push(req.postDataJSON());
        return route.fulfill({json:{version:1,label:'审批前'}});
      }
    }
    if (path.endsWith('/products')) return route.fulfill(listFailure ? {status:503,json:{message:'成果暂时不可用'}} : {json:[product]});
    if (path.endsWith('/preview')) return route.fulfill(previewFailure ? {status:503,json:{message:'快照读取失败'}} : {json:{format:'markdown',text:'# 调研结论\n\n优先采用已有组件，在单一项目验证后扩展。\n\n| 方案 | 适用场景 |\n| --- | --- |\n| 方案 A | 本机任务 |\n| 方案 B | 远程协作 |',notice:'保存时的快照；引用有读取记录不代表结论已验证。',truncated:false}});
    if (path.endsWith('/download')) return route.fulfill(downloadFailure ? {status:503,json:{message:'下载暂时不可用'}} : {status:200,contentType:'text/markdown',body:'# 调研结论\n测试文件'});
    if (path.endsWith('/skill-drafts')) return route.fulfill({json:[draft]});
    if (/\/skill-drafts\/d1\//.test(path)) {
      decisions.push({action:path.split('/').at(-1),...req.postDataJSON()});
      if(failure) return route.fulfill({status:409,json:{message:failure}});
      draft={...draft,status:path.endsWith('/approve')?'APPROVED':path.endsWith('/revert')?'REVERTED':'REJECTED',version:draft.version+2};
      return route.fulfill({json:draft});
    }
    return route.fulfill({json:{}});
  });
  await page.setViewportSize({width:1440,height:900}); await page.goto(url);
  await page.getByRole('button',{name:/技术选型调研报告/}).click();
  await page.getByRole('heading',{name:'调研结论',exact:true}).waitFor();
  await page.getByRole('button',{name:'审阅工作区文件',exact:true}).click();
  assert.equal(await page.evaluate(()=>store.getState().ui.inspectorTab),'files');
  const fileWorkbench=page.getByRole('region',{name:'文件工作台',exact:true});
  await fileWorkbench.getByRole('button',{name:/部署记录\.pdf/}).click();
  await fileWorkbench.getByRole('alert').filter({hasText:'预览生成失败'}).waitFor();
  imageFailure=false;
  await fileWorkbench.getByRole('button',{name:'重试预览'}).click();
  const previewImage=fileWorkbench.getByRole('img',{name:'部署记录.pdf 第 1 页预览'});
  await previewImage.waitFor();
  await page.waitForFunction(()=>document.querySelector('.fw-preview-image')?.naturalWidth>0);
  await page.waitForResponse(response=>new URL(response.url()).pathname.endsWith('/workbench/files'));
  await fileWorkbench.getByRole('heading',{name:'部署记录.pdf',exact:true}).waitFor();
  await fileWorkbench.getByRole('button',{name:'用本机应用打开'}).click();
  await fileWorkbench.getByRole('alert').filter({hasText:'请下载文件后打开'}).waitFor();
  await fileWorkbench.getByRole('button',{name:'下载文件'}).click();
  await fileWorkbench.getByRole('alert').filter({hasText:'下载失败（503）'}).waitFor();
  await fileWorkbench.getByRole('button',{name:'回退',exact:true}).click();
  await page.getByRole('dialog',{name:'回退文件版本？'}).getByRole('button',{name:'取消',exact:true}).click();
  assert.deepEqual(officeCalls,[]);
  await fileWorkbench.getByRole('button',{name:'回退',exact:true}).click();
  await page.getByRole('dialog',{name:'回退文件版本？'}).getByRole('button',{name:'确认回退',exact:true}).click();
  await fileWorkbench.getByRole('status').filter({hasText:'已回退到 v1'}).waitFor();
  assert.deepEqual(officeCalls,[{version:1}]);
  assert.equal(await fileWorkbench.getByRole('list',{name:'工作区文件'}).isVisible(),true);
  assert.match(await fileWorkbench.locator('.fw-file.is-active').innerText(),/部署记录\.pdf/);
  await page.getByRole('tab',{name:'成果',exact:true}).click();
  await page.getByRole('button',{name:/技术选型调研报告/}).click();
  await page.getByRole('heading',{name:'调研结论',exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>document.activeElement?.textContent),'技术选型调研报告');
  assert.equal(await page.getByText('有工具读取记录',{exact:true}).count(),1);
  assert.equal(await page.getByText('未记录工具读取',{exact:true}).count(),1);
  downloadFailure=true; await page.getByRole('button',{name:'下载文件',exact:true}).click();
  await page.getByRole('alert').filter({hasText:'下载暂时不可用'}).waitFor(); downloadFailure=false;
  if(name==='chromium') {
    const waiting=page.waitForEvent('download'); await page.getByRole('button',{name:'下载文件',exact:true}).click();
    const download=await waiting; assert.equal(download.suggestedFilename(),'选型报告.md');
    await download.saveAs(join(output,`${name}-report.md`));
    assert.equal(await readFile(join(output,`${name}-report.md`),'utf8'),'# 调研结论\n测试文件');
  }
  for(const width of [1440,768,390]) {
    await page.setViewportSize({width,height:900}); await page.evaluate(()=>document.fonts.ready);
    await page.locator('.products-inspector').evaluate(el=>Promise.all(el.getAnimations().map(a=>a.finished)));
    assert.equal(await page.locator('.topbar').count(),1);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth <= innerWidth),true,`${name} page overflow at ${width}`);
    assert.equal(await page.locator('.work-products').evaluate(el=>el.scrollWidth <= el.clientWidth+1),true);
    assert.equal(await page.getByRole('button',{name:'下载文件',exact:true}).isVisible(),true);
    if(capture)await page.screenshot({animations:'disabled',path:join(output,`${name}-report-${width}.png`)});
  }
  await page.evaluate(()=>store.getState().setTheme('light'));
  if(capture)await page.screenshot({animations:'disabled',path:join(output,`${name}-light-390.png`)});
  await page.evaluate(()=>store.getState().setTheme('dark'));
  await page.getByRole('button',{name:'返回成果列表',exact:true}).click();
  assert.equal(await page.evaluate(()=>document.activeElement?.dataset.workKey),'product:p1');
  await page.getByRole('button',{name:/research-report/}).click();
  if(capture)await page.screenshot({animations:'disabled',path:join(output,`${name}-draft-390.png`)});
  failure='技能文件已被其他操作修改'; await page.getByRole('button',{name:'接受技能',exact:true}).click();
  await page.getByRole('alert').filter({hasText:failure}).waitFor();
  assert.equal(await page.getByRole('button',{name:'撤回此版本'}).count(),0);
  failure=''; await page.getByRole('button',{name:'接受技能',exact:true}).click();
  await page.getByRole('button',{name:'撤回此版本'}).waitFor(); assert.equal(decisions.at(-1).version,1);
  await page.getByRole('button',{name:'撤回此版本'}).click();
  const confirmation = page.getByRole('dialog',{name:'撤回技能版本？'});
  await confirmation.getByRole('button',{name:'取消',exact:true}).click();
  assert.equal(decisions.at(-1).action, 'approve', '取消不发送撤回请求');
  await page.getByRole('button',{name:'撤回此版本'}).click();
  await confirmation.getByRole('button',{name:'确认撤回',exact:true}).click();
  await page.getByText('已撤回 · 当前项目',{exact:true}).waitFor(); assert.equal(decisions.at(-1).version,3);
  draft={...draft,status:'PENDING',version:7}; await page.getByRole('button',{name:'刷新成果'}).click();
  await page.getByRole('button',{name:'拒绝草稿'}).click(); await page.getByText('已拒绝 · 当前项目',{exact:true}).waitFor();
  await page.getByRole('button',{name:'返回成果列表'}).click(); listFailure=true;
  await page.getByRole('button',{name:'刷新成果'}).click(); await page.getByRole('alert').filter({hasText:'成果暂时不可用'}).waitFor();
  listFailure=false; await page.getByRole('button',{name:'刷新成果'}).click();
  previewFailure=true; await page.getByRole('button',{name:/技术选型调研报告/}).click(); await page.getByRole('alert').filter({hasText:'快照读取失败'}).waitFor();
  previewFailure=false; await page.getByRole('button',{name:'重试预览'}).click(); await page.getByRole('heading',{name:'调研结论',exact:true}).waitFor();
  await page.evaluate(()=>window.store.setState({sessionId:'s2'}));
  await page.getByText(/^还没有交付文件。/).waitFor(); assert.equal(await page.getByText('技术选型调研报告',{exact:true}).count(),0);
  await page.route('**/api/access/login',route=>route.fulfill({status:401,json:{error:'访问令牌错误'}}));
  await page.goto(new URL('/connect',url).href);
  await page.getByLabel('访问令牌').fill('wrong-token'); await page.getByRole('button',{name:'连接',exact:true}).click();
  await page.getByRole('alert').filter({hasText:'访问令牌错误'}).waitFor(); assert.equal(new URL(page.url()).search,'');
  if(capture)await page.screenshot({animations:'disabled',path:join(output,`${name}-connect-390.png`)});
  await page.unroute('**/api/access/login');
  await page.route('**/api/access/login',async route=>{assert.equal(route.request().headers()['x-pilot-token'],'correct-token');return route.fulfill({status:204});});
  await page.route(new URL('/',url).href,route=>route.fulfill({contentType:'text/html',body:'<meta charset="UTF-8"><p>已连接工作台</p>'}));
  await page.getByLabel('访问令牌').fill('correct-token'); await page.getByRole('button',{name:'连接',exact:true}).click();
  await page.getByText('已连接工作台').waitFor().catch(async e=>{console.log('login failure',page.url(),await page.locator('body').innerText());throw e;}); assert.equal(new URL(page.url()).search,'');
  assert.deepEqual(errors,[]); console.log(`${name}: PASS preview/download-failure/approval-conflict/revert/reject/empty/error/retry/responsive/login`);
}
try {
  await server.listen(); const url=`http://127.0.0.1:${server.httpServer.address().port}/__products`;
  browser=await chromium.launch({channel:'chrome',headless:true}); await run(await browser.newPage(),'chromium',url); await browser.close(); browser=null;
  if(!process.argv.includes('--web-only')) {
    const main=join(output,'main.cjs'); await writeFile(main,`const {app,BrowserWindow}=require('electron');app.whenReady().then(()=>{const w=new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}});w.loadURL(${JSON.stringify(url)});});`);
    const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;
    electron=await _electron.launch({executablePath:electronBinary(),args:[main],env});await run(await electron.firstWindow(),'electron',url);
  }
  console.log('Screenshots:',output);
} finally {await browser?.close();await electron?.close();await server.close();}
