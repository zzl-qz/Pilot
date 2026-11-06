#!/usr/bin/env node
// 真实后台与隔离中转夹具：不访问模型厂商、不读取个人数据、不调用付费服务。
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
const root=fileURLToPath(new URL('..',import.meta.url));
const temp=await mkdtemp(join(tmpdir(),'pilot-capability-中文 with spaces-'));
const token=randomUUID(), key=randomUUID(), nextKey=randomUUID();
let probes=0,child,base,logs='';
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const gateway=createServer(async(req,res)=>{
  const chunks=[];for await(const chunk of req)chunks.push(chunk);
  assert.ok([key,nextKey].some(value=>req.headers.authorization==='Bearer '+value),'只向已配置的中转站发送对应凭证');
  res.setHeader('Content-Type','application/json');
  if(req.method==='GET'&&req.url.startsWith('/relay/v1/models')){
    res.end(JSON.stringify({data:[{id:'opaque-model',supported_parameters:['tools']}]}));return;
  }
  if(req.method==='POST'&&req.url.startsWith('/relay/v1/responses')){
    const payload=JSON.parse(Buffer.concat(chunks));
    assert.equal(payload.tool_choice,'required');assert.equal(payload.max_output_tokens,128);assert.equal(payload.max_tool_calls,1);assert.equal(payload.store,false);
    assert.ok(typeof payload.input==='string'&&!payload.input.includes(temp));
    probes++;
    res.end(JSON.stringify({status:'completed',output:[{type:'web_search_call',status:'completed'}],usage:{input_tokens:12,output_tokens:3}}));return;
  }
  res.writeHead(404);res.end(JSON.stringify({error:'route not found'}));
});
try{
  await new Promise(resolve=>gateway.listen(0,'127.0.0.1',resolve));
  const endpoint=`http://127.0.0.1:${gateway.address().port}/relay/v1/chat/completions`;
  const java=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
  child=spawn(java,['--enable-native-access=ALL-UNNAMED','-jar',join(root,'pilot-server/target/pilot-server-0.0.1-SNAPSHOT.jar'),
    '--spring.profiles.active=test','--server.address=127.0.0.1','--server.port=0',`--pilot.data.dir=${join(temp,'data')}`,
    `--pilot.agent.workspace=${temp}`,`--pilot.mobile.libraries=${join(temp,'missing-bridge')}`,'--pilot.mobile.provision=false',
    '--pilot.runtime.provision=false','--pilot.desktop.launch=false','--pilot.desktop.provision=false','--pilot.browser.enabled=false',
    '--pilot.web-search.local=false',`--pilot.model.api-key=${key}`,`--pilot.model.endpoint=${endpoint}`,'--pilot.model.model=opaque-model',
    '--pilot.model.capability-discovery.enabled=true','--pilot.security.mode=token',`--pilot.security.token=${token}`],
    {cwd:temp,env:{...process.env,PILOT_OFFLINE:'true'},stdio:['ignore','pipe','pipe'],windowsHide:true});
  base=await new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>reject(Error('隔离后台启动超时')),30000);
    const capture=chunk=>{logs=(logs+chunk).slice(-24000);const match=logs.match(/Tomcat started on port (\d+)/);if(match){clearTimeout(timer);resolve('http://127.0.0.1:'+match[1]);}};
    child.stdout.on('data',capture);child.stderr.on('data',capture);
    child.once('error',error=>{clearTimeout(timer);reject(error);});
    child.once('exit',code=>{clearTimeout(timer);reject(Error('隔离后台提前退出 '+code));});
  });
  async function waitFor(predicate){const until=Date.now()+8000;while(!await predicate()){if(Date.now()>until)throw Error('自动验证状态未收敛');await pause(30);}}
  async function api(path,method='GET',body){
    const response=await fetch(base+path,{method,headers:{'X-Pilot-Token':token,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});
    assert.ok(response.ok,`隔离接口失败：${path} HTTP ${response.status}`);
    return response.json();
  }
  // 不打开设置面板、不请求状态、不点检测按钮，也应在启动后自动完成。
  await waitFor(()=>probes===1);
  await api('/api/health');
  let status;
  await waitFor(async()=>{status=await api('/api/settings/model/runtime');return status.capabilityDiscovery?.state==='verified';});
  assert.deepEqual(status.nativeCapabilities,['WEB_SEARCH']);
  assert.equal(status.capabilityDiscovery.protocol,'openai-responses');
  assert.ok(!JSON.stringify(status).includes(key));
  for(let i=0;i<8;i++)await api('/api/settings/model/runtime');
  assert.equal(probes,1,'状态读取和普通请求不能反复探测');
  await api('/api/settings/model','PUT',{model:'another-opaque-alias',apiKey:nextKey});
  await waitFor(()=>probes===2);
  await waitFor(async()=>{status=await api('/api/settings/model/runtime');return status.model==='another-opaque-alias'&&status.capabilityDiscovery?.state==='verified';});
  await api('/api/settings/model/runtime','PUT',{model:status.model,endpoint:status.endpoint,options:{...status.options,webSearchMode:'local'}});
  await api('/api/settings/model','PUT',{apiKey:key});
  status=await api('/api/settings/model/runtime');
  assert.deepEqual(status.nativeCapabilities,[]);assert.equal(status.options.webSearchMode,'local');
  assert.equal(probes,2,'明确本地模式不能继续发起能力探测');
  console.log('PASS: 真实后台启动自动验证、只填地址密钥模型、无按钮触发、凭证/模型变化自动重验、缓存复用、状态不泄露密钥、手动本地模式；所有模型请求均由本机中转夹具处理');
}catch(error){
  console.error(logs.replaceAll(key,'[密钥已隐藏]').replaceAll(nextKey,'[密钥已隐藏]').replaceAll(token,'[令牌已隐藏]'));
  throw error;
}finally{
  if(child&&child.exitCode===null){child.kill();await new Promise(resolve=>{const timer=setTimeout(()=>{child.kill('SIGKILL');resolve();},8000);child.once('exit',()=>{clearTimeout(timer);resolve();});});}
  gateway.closeAllConnections();await new Promise(resolve=>gateway.close(resolve));
  await rm(temp,{recursive:true,force:true,maxRetries:5,retryDelay:200});
}
