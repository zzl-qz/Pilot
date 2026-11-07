// 走实际 start.mjs 源码入口，验证缓存启动、GUI 控制口和正常退出清理。
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createServer} from 'node:net';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
const root=fileURLToPath(new URL('.',import.meta.url));
const {chromium}=createRequire(new URL('../pilot-web/frontend/package.json',import.meta.url))('playwright-core');
const temp=await mkdtemp(join(tmpdir(),'pilot-launcher-中文 with spaces-'));
const port=await new Promise(resolve=>{const probe=createServer();probe.listen(0,'127.0.0.1',()=>{const selected=probe.address().port;probe.close(()=>resolve(selected));});});
const env={...process.env,PILOT_DESKTOP_SMOKE:'1',PILOT_OFFLINE:'true',PILOT_BROWSER_HOME:temp,PILOT_DESKTOP_CDP_PORT:String(port)};
delete env.ELECTRON_RUN_AS_NODE;
const launcher=spawn(process.execPath,[join(root,'start.mjs'),`--user-data-dir=${join(temp,'profile')}`],{cwd:root,env,stdio:['ignore','pipe','pipe'],windowsHide:true});
let output='',startError,info,browser;
launcher.stdout.on('data',chunk=>{output=(output+chunk).slice(-12000);});
launcher.stderr.on('data',chunk=>{output=(output+chunk).slice(-12000);});
launcher.once('error',error=>{startError=error;});
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function exited(pid){try{process.kill(pid,0);return false;}catch(error){return error.code==='ESRCH';}}
try{
  const end=Date.now()+20000;
  while(!info&&Date.now()<end){
    if(startError)throw startError;
    if(launcher.exitCode!==null)throw new Error('桌面源码入口提前退出：'+output);
    try{info=JSON.parse(await readFile(join(temp,'desktop-browser.json'),'utf8'));}catch{await pause(100);}
  }
  assert.ok(info,'桌面源码入口未启动 GUI 或未写出控制端点');
  assert.ok(Number.isFinite(info.startMillis),'端点必须记录进程启动时间，用于识别 PID 复用');
  const endpoint=`http://127.0.0.1:${info.controlPort}`;
  assert.equal((await fetch(endpoint+'/tabs')).status,401);
  assert.equal((await fetch(endpoint+'/tabs',{headers:{'x-pilot-token':info.token}})).status,200);
  browser=await chromium.connectOverCDP(`http://127.0.0.1:${info.cdpPort}`);
  const page=browser.contexts().flatMap(context=>context.pages()).find(item=>item.url()==='about:blank');
  assert.ok(page,'源码入口必须创建真实桌面窗口');
  await page.close();
  const deadline=Date.now()+10000;
  while(launcher.exitCode===null&&Date.now()<deadline)await pause(100);
  assert.equal(launcher.exitCode,0,'关闭窗口后源码启动器应正常退出');
  assert.equal(await exited(info.pid),true,'退出后不能遗留 Electron 宿主进程');
  await assert.rejects(readFile(join(temp,'desktop-browser.json')));
  console.log('PASS: 实际源码入口离线复用缓存、真实 GUI、控制口鉴权、关闭退出和端点清理');
}finally{
  await browser?.close().catch(()=>{});
  if(info&&!await exited(info.pid)){try{process.kill(info.pid,'SIGTERM');}catch{}}
  if(launcher.exitCode===null)launcher.kill('SIGTERM');
  await rm(temp,{recursive:true,force:true,maxRetries:5,retryDelay:200});
}
