import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,mkdir,cp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
const root=path.resolve(fileURLToPath(new URL('../..',import.meta.url)));
const directory=await mkdtemp(path.join(tmpdir(),'pilot-environment-中文 with spaces-'));
const libraries=path.join(directory,'mobile-bridge/lib');await mkdir(libraries,{recursive:true});
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const token=randomUUID();
const env={...process.env,PILOT_BOOTSTRAP_DIR:path.join(directory,'bootstrap'),PILOT_OFFLINE:'true'};delete env.PILOT_BOOTSTRAP_PREPARED;
const child=spawn(java,['--enable-native-access=ALL-UNNAMED','-jar',path.join(root,'pilot-server/target/pilot-server-0.0.1-SNAPSHOT.jar'),'--server.address=127.0.0.1','--server.port=0',`--pilot.data.dir=${path.join(directory,'data')}`,`--pilot.agent.workspace=${directory}`,`--pilot.mobile.libraries=${libraries}`,'--pilot.mobile.provision=true','--pilot.mobile.relay=https://127.0.0.1:9','--pilot.runtime.provision=false','--pilot.desktop.launch=false','--pilot.desktop.provision=false','--pilot.browser.enabled=false','--pilot.web-search.local=false','--pilot.model.api-key=','--pilot.model.endpoint=http://127.0.0.1:9/v1/chat/completions','--pilot.security.mode=token',`--pilot.security.token=${token}`],{cwd:directory,env,stdio:['ignore','pipe','pipe']});
let base;
try{
 base=await new Promise((resolve,reject)=>{
  let output='';const timer=setTimeout(()=>reject(new Error('隔离后台启动超时')),30000);
  const capture=chunk=>{output=(output+chunk).slice(-65536);const match=output.match(/Tomcat started on port (\d+)/);if(match){clearTimeout(timer);resolve(`http://127.0.0.1:${match[1]}`);}};
  child.stdout.on('data',capture);child.stderr.on('data',capture);
  child.once('error',()=>{clearTimeout(timer);reject(new Error('无法启动隔离后台'));});
  child.once('exit',code=>{clearTimeout(timer);reject(new Error(`隔离后台提前退出 ${code}`));});
 });
 async function request(route,method='GET',extra={}){
  const r=await fetch(base+route,{method,headers:{'X-Pilot-Token':token,'Content-Type':'application/json',...extra},...(method==='POST'?{body:'{}'}:{})});
  return {status:r.status,body:await r.json()};
 }
 async function waitFor(predicate){
  const end=Date.now()+20000;let last;
  while(Date.now()<end){last=await request('/api/mobile/connection');if(predicate(last))return last;await new Promise(resolve=>setTimeout(resolve,100));}
  throw new Error(`环境状态未能收敛（HTTP ${last?.status}，阶段 ${last?.body?.environment?.state||'unknown'}）`);
 }
 const failed=await waitFor(result=>result.body?.environment?.state==='failed');
 assert.equal(failed.status,200);assert.equal(failed.body.running,false);
 assert.ok(!JSON.stringify(failed.body).includes(token)&&!JSON.stringify(failed.body).includes(directory));
 assert.equal((await request('/api/health')).status,200,'连接能力缺失不能阻止整个应用提供服务');
 assert.equal((await request('/api/mobile/connection/repair','POST',{Origin:'https://evil.example'})).status,403,'跨来源不能触发下载或系统安装');
 await cp(path.join(root,'pilot-mobile/bridge/build/install/bridge/lib'),libraries,{recursive:true});
 assert.equal((await request('/api/mobile/connection/repair','POST')).status,200);
 assert.equal((await request('/api/mobile/connection/repair','POST')).status,200);
 const ready=await waitFor(result=>result.body?.running===true);
 assert.equal(ready.body.version,2);
 console.log('PASS: 真实后台异步环境检查、缺组件时应用继续运行、无凭证回显、同源修复限制、显式修复后原生验证及连接桥启动；无模型调用，无外网依赖');
}finally{
 if(child.exitCode===null){child.kill();await new Promise(resolve=>{const timer=setTimeout(()=>{child.kill('SIGKILL');resolve();},10000);child.once('exit',()=>{clearTimeout(timer);resolve();});});}
 await rm(directory,{recursive:true,force:true});
}
