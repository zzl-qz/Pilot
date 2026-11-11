// 真实 Electron 边界回归。所有页面、浏览器分区和 IPC 探针都属于隔离测试数据。
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {mkdtemp,mkdir,rm,writeFile,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
import {prepareDevelopmentHost} from './brand-host.mjs';
import {partitionName} from './protocol.mjs';
const root=fileURLToPath(new URL('.',import.meta.url));
const {_electron}=createRequire(new URL('../pilot-web/frontend/package.json',import.meta.url))('playwright-core');
const temp=await mkdtemp(join(tmpdir(),'pilot-ipc-中文 with spaces-'));
const report=process.env.PILOT_DESKTOP_REPORT_DIR||join(root,'out','security-report');
await mkdir(report,{recursive:true});
await rm(join(report,'failure.png'),{force:true});
const passed=[];
let desktop,page,preloadId;
const foreign=createServer((req,res)=>{res.writeHead(200,{'content-type':'text/html;charset=utf-8'});res.end(req.url==='/tab'?'<h1>Browser fixture</h1><script>console.log("IPC_SECURITY_FIXTURE")</script>':'<h1 id="foreign">Foreign fixture</h1>');});
await new Promise(resolve=>foreign.listen(0,'127.0.0.1',resolve));
const other=`http://127.0.0.1:${foreign.address().port}`;
const site=createServer((req,res)=>{
  if(req.url==='/redirect'){res.writeHead(302,{location:other+'/redirected'});res.end();return;}
  res.writeHead(200,{'content-type':'text/html;charset=utf-8'});
  if(req.url==='/child'){res.end('<h1>Child fixture</h1>');return;}
  res.end(`<!doctype html><h1 id="home">Workbench fixture</h1><a id="external" href="${other}/tab">External</a><a id="redirect" href="/redirect">Redirect</a><a id="popup" target="_blank" href="${other}/popup">Popup</a><a id="same" href="/next">Same origin</a><iframe name="child" src="/child"></iframe><iframe name="opaque" sandbox="allow-scripts" src="/child"></iframe><script>
    window.opened=[]; window.notices=[];
    window.pilotDesktop?.onNavigationNotice(message=>notices.push(message));
    window.pilotDesktop?.onBrowserOpen(async url=>{opened.push(url);await pilotDesktop.ensureTab('security');await pilotDesktop.navigate(url);});
  </script>`);
});
await new Promise(resolve=>site.listen(0,'127.0.0.1',resolve));
const home=`http://127.0.0.1:${site.address().port}`;
const invokeCases=[
  ['pilot-browser-ensure','stranger'],['pilot-browser-navigate',other+'/bad'],['pilot-browser-go','reload'],['pilot-browser-new'],
  ['pilot-browser-close','fixture'],['pilot-browser-select','fixture'],['pilot-browser-detach'],['pilot-browser-menu'],
  ['pilot-browser-find','fixture'],['pilot-browser-logs',false],['pilot-browser-devtools'],['pilot-browser-pick'],
  ['pilot-browser-reset-emulation'],['pilot-workbench-reload'],['pilot-mobile-open'],['pilot-mobile-copy','invalid'],
  ['pilot:select-directories'],
];
const probe=join(temp,'probe.cjs');
await writeFile(probe,`const {contextBridge,ipcRenderer}=require('electron');const events=[];for(const channel of ['pilot-browser-log','pilot-browser-state','pilot-browser-download'])ipcRenderer.on(channel,()=>events.push(channel));contextBridge.exposeInMainWorld('ipcProbe',{invoke:(channel,...args)=>ipcRenderer.invoke(channel,...args),send:(channel,value)=>ipcRenderer.send(channel,value),events:()=>events});`);
const env={...process.env,PILOT_URL:home,PILOT_BROWSER_HOME:join(temp,'browser')};
delete env.ELECTRON_RUN_AS_NODE;delete env.PILOT_DESKTOP_SMOKE;
async function denies(target,label){
  const results=await target.evaluate(async cases=>{const result=[];for(const [name,...args] of cases)result.push(await ipcProbe.invoke(name,...args));return result;},invokeCases);
  for(const result of results)assert.equal(result.code,'UNTRUSTED_WORKBENCH',label);
  await target.evaluate(()=>{ipcProbe.send('pilot-browser-workspace','forbidden-workspace');ipcProbe.send('pilot-browser-bounds',{visible:true,x:0,y:0,width:900,height:700});ipcProbe.send('pilot-workbench-ready');});
  passed.push(label + ': all 17 invoke channels denied');
}
try{
  desktop=await _electron.launch({chromiumSandbox:true,executablePath:prepareDevelopmentHost(),args:[root,`--user-data-dir=${join(temp,'profile')}`],env,timeout:20000});
  assert.equal(await desktop.evaluate(({app})=>app.commandLine.hasSwitch('no-sandbox')),false);
  page=await desktop.firstWindow();await page.locator('#home').waitFor();
  preloadId=await desktop.evaluate(({BrowserWindow},filePath)=>BrowserWindow.getAllWindows()[0].webContents.session.registerPreloadScript({type:'frame',filePath}),probe);
  await page.reload();await page.waitForFunction(()=>Boolean(window.pilotDesktop&&window.ipcProbe));
  assert.equal(await page.evaluate(()=>typeof pilotDesktop.logs),'function');
  await page.evaluate(async()=>{pilotDesktop.setWorkspace('security-workspace');await pilotDesktop.ensureTab('security');});
  passed.push('trusted main frame: bridge and browser controls available');

  // 真实 preload/IPC 往返；只替换系统弹框，避免自动化弹出阻塞式原生窗口。
  await desktop.evaluate(({dialog})=>{
    process._pilotDirectoryCalls=[];
    dialog.showOpenDialog=async(parent,options)=>{
      process._pilotDirectoryCalls.push({parentId:parent.id,properties:options.properties});
      if(process._pilotDirectoryMode==='error')throw Error('fixture dialog failure');
      return {canceled:process._pilotDirectoryMode==='cancel',filePaths:['/fixture/中文 空间','/fixture/second']};
    };
  });
  assert.deepEqual(await page.evaluate(()=>pilotDesktop.selectDirectories()),{ok:true,canceled:false,paths:['/fixture/中文 空间','/fixture/second']});
  assert.deepEqual(await desktop.evaluate(()=>process._pilotDirectoryCalls[0].properties),['openDirectory','multiSelections']);
  await desktop.evaluate(()=>process._pilotDirectoryMode='cancel');
  assert.deepEqual(await page.evaluate(()=>pilotDesktop.selectDirectories()),{ok:true,canceled:true,paths:[]});
  await desktop.evaluate(()=>process._pilotDirectoryMode='error');
  assert.equal((await page.evaluate(()=>pilotDesktop.selectDirectories())).code,'DIALOG_FAILED');
  passed.push('trusted directory picker: multiple paths, cancellation and visible dialog failure');

  for(const name of ['child','opaque']){
    const frame=page.frame({name});assert.ok(frame,name);
    assert.equal(await frame.evaluate(()=>typeof window.pilotDesktop),'undefined');
  }
  passed.push('production subframes: desktop bridge absent');

  await desktop.evaluate(async({BrowserWindow,ipcMain},{url,preload,origin,policy})=>{
    // 仅测试窗口开启子框架 preload，使测试主动发送真实子框架 IPC；生产窗口保持默认隔离。
    const win=new BrowserWindow({show:false,webPreferences:{preload,additionalArguments:['--pilot-workbench-origin='+origin],nodeIntegrationInSubFrames:true,sandbox:true,contextIsolation:true}});
    const {workbenchIpc}=new Function(policy+';return {workbenchIpc};')();
    workbenchIpc(ipcMain,()=>win,origin).handle('pilot-test-frame-guard',()=>({ok:true}));
    await win.loadURL(url);
  },{url:home+'/rogue',preload:join(root,'preload.cjs'),origin:home,policy:(await readFile(new URL('./workbench-security.mjs',import.meta.url),'utf8')).replace(/^export /gm,'')});
  let rogue;const deadline=Date.now()+10000;
  while(!rogue&&Date.now()<deadline){rogue=desktop.windows().find(p=>p.url()===home+'/rogue');if(!rogue)await new Promise(resolve=>setTimeout(resolve,50));}
  assert.ok(rogue,'测试窗口必须就绪');await rogue.waitForFunction(()=>Boolean(window.ipcProbe&&window.pilotDesktop));
  await denies(rogue,'other same-origin window');
  assert.equal((await rogue.evaluate(()=>ipcProbe.invoke('pilot-test-frame-guard'))).ok,true);
  for(const name of ['child','opaque']){
    const frame=rogue.frame({name});await frame.waitForFunction(()=>Boolean(window.ipcProbe));
    assert.equal((await frame.evaluate(()=>ipcProbe.invoke('pilot-test-frame-guard'))).code,'UNTRUSTED_WORKBENCH');
    await denies(frame,name+' subframe IPC');
  }
  await rogue.close();
  await desktop.evaluate(({ipcMain})=>ipcMain.removeHandler('pilot-test-frame-guard'));

  await desktop.evaluate(({BrowserWindow})=>{
    process._pilotSecurityTrace=[];
    const c=BrowserWindow.getAllWindows()[0].webContents;
    for(const type of ['will-frame-navigate','will-navigate','will-redirect'])c.on(type,(event)=>{process._pilotSecurityTrace.push({type,url:event.url,isMainFrame:event.isMainFrame,origin:c.mainFrame.origin,current:c.getURL(),frame:c.mainFrame.url});});
  });
  await page.locator('#external').click({noWaitAfter:true});
  await page.waitForFunction(()=>opened.length>0,null,{timeout:10000});
  assert.equal(page.url(),home+'/');
  let logs;const logDeadline=Date.now()+10000;
  do{logs=await page.evaluate(()=>pilotDesktop.logs(false));if(logs.text?.includes('IPC_SECURITY_FIXTURE'))break;await new Promise(resolve=>setTimeout(resolve,50));}while(Date.now()<logDeadline);
  assert.ok(logs.ok&&logs.text.includes('IPC_SECURITY_FIXTURE'));
  assert.equal(await page.evaluate(()=>opened.length),1,'一次链接操作只分派一次');
  passed.push('cross-origin link: stays in workbench, opens isolated browser');
  const contentsId=await desktop.evaluate(({webContents},url)=>webContents.getAllWebContents().find(c=>c.getURL()===url).id,other+'/tab');
  assert.equal(await desktop.evaluate(({webContents},id)=>webContents.fromId(id).executeJavaScript('typeof window.pilotDesktop'),contentsId),'undefined');

  // 将测试探针注入浏览器分区，验证即使渲染进程主动发送 IPC 也被主进程拒绝。
  await desktop.evaluate(async({webContents},{id,filePath,url})=>{const c=webContents.fromId(id);c.session.registerPreloadScript({type:'frame',filePath});await c.loadURL(url);},{id:contentsId,filePath:probe,url:other+'/tab'});
  const guestResults=await desktop.evaluate(async({webContents},{id,cases})=>webContents.fromId(id).executeJavaScript(`Promise.all(${JSON.stringify(cases)}.map(([name,...args])=>ipcProbe.invoke(name,...args)))`),{id:contentsId,cases:invokeCases});
  for(const value of guestResults)assert.equal(value.code,'UNTRUSTED_WORKBENCH');
  passed.push('ordinary browser view: all 17 invoke channels denied');
  assert.equal(await desktop.evaluate(()=>process._pilotDirectoryCalls.length),3,'不可信 IPC 不得触发目录弹框');
  const isolated=await desktop.evaluate(({webContents,session},{id,partition})=>webContents.fromId(id).session===session.fromPartition(partition),{id:contentsId,partition:partitionName('security-workspace')});
  assert.equal(isolated,true,'不可信单向消息不能更改工作区分区');

  await page.evaluate(()=>document.querySelector('#popup').click());await page.waitForFunction(()=>opened.some(url=>url.endsWith('/popup')));
  assert.equal(await desktop.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().length),1);assert.equal(page.url(),home+'/');
  passed.push('window.open: no privileged child window, browser alternative available');
  await page.evaluate(target=>{window.open(target,'_blank');},home+'/same-origin-popup');
  await page.waitForFunction(()=>opened.some(url=>url.endsWith('/same-origin-popup')));
  assert.equal(page.url(),home+'/','同源弹窗也必须保留工作台和草稿');
  assert.equal(await desktop.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().length),1);
  passed.push('same-origin popup: workbench preserved, opens ordinary browser');
  const oldNotices=await page.evaluate(()=>notices.length);
  await page.evaluate(()=>document.querySelector('#redirect').click());await page.waitForFunction(n=>notices.length>n,oldNotices);
  assert.equal(new URL(page.url()).origin,home);
  assert.equal(await page.evaluate(()=>typeof window.pilotDesktop),'object');
  passed.push('cross-origin server redirect: rejected with feedback');

  await page.evaluate(()=>document.querySelector('#same').click());await page.waitForURL(home+'/next');
  assert.equal((await page.evaluate(()=>pilotDesktop.logs(false))).ok,true);
  passed.push('same-origin navigation: remains usable');
  // loadURL 是主进程 API，不触发 will-navigate；模拟意外主进程加载以独立验证 IPC 防线。
  const loading=page.waitForURL(other+'/foreign');
  await desktop.evaluate(async({BrowserWindow},url)=>{const win=BrowserWindow.getAllWindows().find(w=>w.webContents.getURL().startsWith('http:'));await win.loadURL(url);},other+'/foreign');
  await loading;await page.waitForFunction(()=>Boolean(window.ipcProbe));
  assert.equal(await page.evaluate(()=>typeof window.pilotDesktop),'undefined');
  await denies(page,'foreign document in original window');
  await desktop.evaluate(({webContents},id)=>new Promise((resolve,reject)=>{
    const contents=webContents.fromId(id);
    contents.once('console-message',()=>resolve());
    contents.executeJavaScript('console.log("IPC_BROADCAST_FIXTURE")').catch(reject);
  }),contentsId);
  await new Promise(resolve=>setTimeout(resolve,30));
  assert.deepEqual(await page.evaluate(()=>ipcProbe.events()),[],'不可信页面不收到浏览器状态与日志推送');
  passed.push('foreign document: production bridge absent, no data broadcasts');

  await page.screenshot({path:join(report,'foreign-document.png')});
  const endpoint=JSON.parse(await readFile(join(temp,'browser','desktop-browser.json'),'utf8'));
  const pid=endpoint.pid;
  await desktop.close();desktop=null;
  await assert.rejects(readFile(join(temp,'browser','desktop-browser.json')));
  passed.push('desktop exit: endpoint removed');
  await writeFile(join(report,'security.json'),JSON.stringify({success:true,platform:process.platform,arch:process.arch,pid,passed},null,2));
  console.log(`PASS: ${passed.length} real Electron security checks (${process.platform}/${process.arch})`);
  console.log('Report: '+report);
}catch(error){
  console.log('Navigation trace:',await desktop?.evaluate(()=>process._pilotSecurityTrace).catch(()=>null));
  console.log('Page state:',await page?.evaluate(()=>({url:location.href,opened:window.opened,notices:window.notices})).catch(()=>null));
  await page?.screenshot({path:join(report,'failure.png')}).catch(()=>{});
  await writeFile(join(report,'security.json'),JSON.stringify({success:false,platform:process.platform,arch:process.arch,passed,error:error.message},null,2));
  throw error;
}finally{
  if(desktop){if(preloadId)await desktop.evaluate(({BrowserWindow},id)=>BrowserWindow.getAllWindows()[0]?.webContents.session.unregisterPreloadScript(id),preloadId).catch(()=>{});await desktop.close();}
  for(const server of [site,foreign]){server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
  await rm(temp,{recursive:true,force:true,maxRetries:5,retryDelay:200});
}
