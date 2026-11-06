#!/usr/bin/env node
// 在实际宿主上执行，Gitee/GitHub runner 和本地使用同一入口。只使用隔离数据，不调用模型。
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile,readdir} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {release} from 'node:os';
import {prepareDesktopRuntime} from '../pilot-desktop/prepare-runtime.mjs';
const root=fileURLToPath(new URL('..',import.meta.url));
const output=resolve(process.env.PILOT_DESKTOP_REPORT_DIR||join(root,'pilot-desktop/out/host-report',`${process.platform}-${process.arch}`));
await mkdir(output,{recursive:true});
const report={platform:process.platform,arch:process.arch,osRelease:release(),environment:process.env.CI?'CI runner':'local process',startedAt:new Date().toISOString(),stages:[],success:false};
const redact=text=>text.replace(/pilot:\/\/pair\/[A-Za-z0-9_-]+/g,'[配对码已隐藏]');
let active;
for(const signal of ['SIGTERM','SIGINT'])process.once(signal,()=>{active?.kill(signal);process.exitCode=1;});
async function stage(name,action){
  const started=Date.now();const item={name,success:false};report.stages.push(item);
  try{await action();item.success=true;}catch(error){item.error=redact(error.message);throw error;}
  finally{item.seconds=(Date.now()-started)/1000;await writeFile(join(output,'host.json'),JSON.stringify(report,null,2));}
}
async function run(name,args){
  await stage(name,async()=>{
    let logs='';
    const code=await new Promise((resolveCode,reject)=>{
      active=spawn(process.execPath,args,{cwd:root,env:{...process.env,PILOT_DESKTOP_REPORT_DIR:output},stdio:['ignore','pipe','pipe'],windowsHide:true});
      const timer=setTimeout(()=>active?.kill('SIGTERM'),6*60_000);
      const capture=chunk=>{const value=redact(String(chunk));logs=(logs+value).slice(-2*1024*1024);process.stdout.write(value);};
      active.stdout.on('data',capture);active.stderr.on('data',capture);
      active.once('error',error=>{clearTimeout(timer);reject(error);});
      active.once('close',value=>{clearTimeout(timer);resolveCode(value);});
    });
    active=null;await writeFile(join(output,name+'.log'),logs);
    assert.equal(code,0,`${name} 失败，查看宿主报告`);
  });
}
try{
  await stage('runtime-cache-reuse',async()=>{
    const options={env:{...process.env,PILOT_OFFLINE:'true'},run:async()=>{throw new Error('缓存复用不能下载或重新安装');}};
    const first=await prepareDesktopRuntime(options),second=await prepareDesktopRuntime(options);
    assert.ok(first.ready&&second.ready,first.message||second.message);assert.equal(first.binary,second.binary);
    report.electronVersion=JSON.parse(await readFile(join(root,'pilot-desktop/node_modules/electron/package.json'),'utf8')).version;
  });
  const tests=(await readdir(join(root,'pilot-desktop'))).filter(name=>name.endsWith('.test.mjs')).sort().map(name=>join('pilot-desktop',name));
  await run('desktop-units',['--test',...tests]);
  await run('source-launcher',['pilot-desktop/launcher-smoke.mjs']);
  await run('desktop-security',['pilot-desktop/security-smoke.mjs']);
  await run('desktop-reconnect',['pilot-desktop/ui-smoke.mjs']);
  await run('native-bridge',['pilot-mobile/scripts/direct-qr-smoke.mjs','--direct']);
  await run('component-repair',['pilot-mobile/scripts/environment-smoke.mjs']);
  await run('bridge-recovery',['pilot-mobile/scripts/backend-smoke.mjs','--direct','--recovery']);
  report.success=true;
  console.log(`宿主验证通过：${process.platform}/${process.arch}；环境 ${report.environment}`);
}catch(error){console.error(redact(error.message));process.exitCode=1;}
finally{
  report.finishedAt=new Date().toISOString();
  await writeFile(join(output,'host.json'),JSON.stringify(report,null,2));
  console.log('报告：'+output);
}
