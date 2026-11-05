import {spawn,execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createInterface} from 'node:readline';
import {randomUUID} from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createServer} from 'node:http';
import {connect} from 'node:net';
const exec=promisify(execFile),root=path.resolve(fileURLToPath(new URL('..',import.meta.url)));
const name='pilot-network-health-'+randomUUID();
const libraries=path.join(root,'bridge/build/install/bridge/lib'),tests=path.join(root,'bridge/build/classes/kotlin/test');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
// 仅验收用的短时代理，认证且只允许 iroh 服务的 443 CONNECT；不解密 TLS。
const proxy=process.argv.includes('--with-proxy')?createServer((req,res)=>res.writeHead(405).end()):null;
const tunnels=new Set(),password=randomUUID();
let proxyConnections=0;
if(proxy){
 proxy.on('connect',(req,socket,head)=>{
  let target;try{target=new URL('https://'+req.url);}catch{socket.destroy();return;}
  if(req.headers['proxy-authorization']!=='Basic '+Buffer.from('fixture:'+password).toString('base64')||
     !/(^|\.)iroh\.(link|network)\.?$/i.test(target.hostname)||!['','443'].includes(target.port)){socket.destroy();return;}
  const upstream=connect(443,target.hostname,()=>{proxyConnections++;socket.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length)upstream.write(head);socket.pipe(upstream);upstream.pipe(socket);});
  for(const item of [socket,upstream]){tunnels.add(item);item.on('close',()=>tunnels.delete(item));item.on('error',()=>{socket.destroy();upstream.destroy();});}
 });
 await new Promise((resolve,reject)=>{proxy.once('error',reject);proxy.listen(0,'0.0.0.0',resolve);});
}
const args=['run','-i','--rm','--pull','never','--name',name,'--read-only','--tmpfs','/tmp:rw,exec,nosuid,size=512m','--network','bridge','-v',libraries+':/probe:ro','-v',tests+':/probe-test:ro'];
if(proxy){
 const address='http://fixture:'+password+'@host.docker.internal:'+proxy.address().port;
 args.push('--env','HTTP_PROXY='+address,'--env','HTTPS_PROXY='+address);
 if(process.platform==='linux')args.push('--add-host','host.docker.internal:host-gateway');
}
args.push('--entrypoint','java',process.env.PILOT_PROBE_JAVA_IMAGE||'maven:3.9-eclipse-temurin-25','--enable-native-access=ALL-UNNAMED','-cp','/probe-test:/probe/*','com.pilot.mobile.bridge.NetworkHealthSmokeKt');
const child=spawn('docker',args,{stdio:['pipe','pipe','pipe']});
const lines=createInterface({input:child.stdout});
let stage=0,passed=false,ticket;
const completion=new Promise((resolve,reject)=>{
 const timer=setTimeout(()=>reject(new Error('隔离容器网络恢复验收超时')),120000);
 child.once('error',error=>{clearTimeout(timer);reject(error);});
 child.stderr.on('data',chunk=>process.stderr.write(chunk));
 child.once('exit',code=>{clearTimeout(timer);code===0&&passed?resolve():reject(new Error('隔离网络验收未通过 '+code));});
});
completion.catch(()=>child.kill());
async function probe(){
 try{await exec(java,['--enable-native-access=ALL-UNNAMED','-cp',[tests,path.join(libraries,'*')].join(path.delimiter),'com.pilot.mobile.bridge.NetworkHealthSmokeKt','client',ticket],{timeout:55000,maxBuffer:1024*1024});}
 catch{throw new Error('主机与容器的实际中继往返失败，不计通过（不输出路由票据）');}
}
try{
 for await(const line of lines){
  if(line.startsWith('PILOT_NETWORK_STAGE_READY ')&&stage===0){ticket=line.slice('PILOT_NETWORK_STAGE_READY '.length);stage=1;await probe();console.log('已完成首次跨网探针，断开专用容器网络');await exec('docker',['network','disconnect','bridge',name],{timeout:10000});}
  else if(line==='PILOT_NETWORK_STAGE_OFFLINE'&&stage===1){stage=2;console.log('已检测实际离线，恢复专用容器网络');await exec('docker',['network','connect','bridge',name],{timeout:10000});}
  else if(line==='PILOT_NETWORK_STAGE_RESTORED'&&stage===2){stage=3;await probe();child.stdin.end('verified\n');}
  else if(line.startsWith('PASS:')){passed=true;console.log(line);}
  else if(line.startsWith('FAIL:'))console.error(line);
 }
 await completion;
 if(proxy)console.log('仅测试代理的 CONNECT 次数：'+proxyConnections);
}finally{lines.close();await exec('docker',['rm','-f',name],{timeout:10000}).catch(()=>{});for(const socket of tunnels)socket.destroy();if(proxy)await new Promise(resolve=>proxy.close(resolve));}
