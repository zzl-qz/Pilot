import {spawn} from 'node:child_process';
import {existsSync,readFileSync,statSync,openSync,readSync,closeSync,renameSync,realpathSync} from 'node:fs';
import {join,relative,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import {desktopRoot,electronBinary,prepareDevelopmentHost} from './brand-host.mjs';

/** Reads the native header rather than launching an incompatible or incomplete executable. */
export function binaryArchitectures(file){
 let fd;
 try{
  fd=openSync(file,'r');const header=Buffer.alloc(64);if(readSync(fd,header,0,64,0)<32)return [];
  const machineName=value=>({0x8664:'x64',0xaa64:'arm64',62:'x64',183:'arm64',0x1000007:'x64',0x100000c:'arm64'}[value]);
  if(header.toString('ascii',0,4)==='\x7fELF')return [machineName(header[5]===1?header.readUInt16LE(18):header.readUInt16BE(18))].filter(Boolean);
  if(header.readUInt32LE(0)===0xfeedfacf)return [machineName(header.readUInt32LE(4))].filter(Boolean);
  if(header.readUInt16LE(0)===0x5a4d){
   const offset=header.readUInt32LE(60);if(offset>1024*1024)return [];
   const pe=Buffer.alloc(6);if(readSync(fd,pe,0,6,offset)<6||pe.toString('ascii',0,4)!=='PE\0\0')return [];
   return [machineName(pe.readUInt16LE(4))].filter(Boolean);
  }
  if(header.readUInt32BE(0)===0xcafebabe){
   const count=header.readUInt32BE(4);if(count>16)return [];
   const entries=Buffer.alloc(count*20);if(readSync(fd,entries,0,entries.length,8)<entries.length)return [];
   return Array.from({length:count},(_,i)=>machineName(entries.readUInt32BE(i*20))).filter(Boolean);
  }
  return [];
 }catch{return [];}finally{if(fd!==undefined)closeSync(fd);}
}
export function runtimeReady(root=desktopRoot,platform=process.platform,arch=process.arch){
 try{
  const folder=join(root,'node_modules/electron');
  const version=JSON.parse(readFileSync(join(folder,'package.json'),'utf8')).version;
  const binary=electronBinary(root,platform);
  // Windows 文件系统不表达 POSIX 执行位；在 Windows 上模拟其它平台时不能据此判为损坏。
  const executable=platform==='win32'||process.platform==='win32'||(statSync(binary).mode&0o111)!==0;
  return Boolean(version)&&readFileSync(join(folder,'dist/version'),'utf8').trim().replace(/^v/,'')===version
   &&readFileSync(join(folder,'path.txt'),'utf8').trim()===relative(join(folder,'dist'),binary)
   &&statSync(binary).isFile()&&executable&&binaryArchitectures(binary).includes(arch);
 }catch{return false;}
}
function execute(command,args,options){
 return new Promise((resolve,reject)=>{
  const child=spawn(command,args,options);
  const timer=setTimeout(()=>{child.kill();reject(new Error('桌面运行时下载超时，请检查网络后重试'));},15*60*1000);
  child.once('error',error=>{clearTimeout(timer);reject(error);});
  child.once('exit',code=>{clearTimeout(timer);code===0?resolve():reject(new Error('桌面运行时下载未完成，请检查网络、原生库或目录权限后重试'));});
 });
}
/** Electron now downloads on demand. Install the pinned binary before building the development shell. */
export async function prepareDesktopRuntime({root=desktopRoot,env=process.env,platform=process.platform,arch=process.arch,run=execute,brand=prepareDevelopmentHost}={}){
 if(!runtimeReady(root,platform,arch)){
  if(env.PILOT_OFFLINE==='true'||env.PILOT_DESKTOP_PROVISION==='false')return {ready:false,message:'桌面运行时缺失，离线模式或配置已关闭自动补全；可以继续使用 Web 工作台'};
  const folder=join(root,'node_modules/electron');const installer=join(folder,'install.js');
  if(!existsSync(installer))throw new Error('桌面依赖包不完整，请先安装 pilot-desktop 的 npm 依赖');
  const dist=join(folder,'dist');if(existsSync(dist))renameSync(dist,join(folder,`.dist-invalid-${randomUUID()}`));
  const childEnv={...env,ELECTRON_INSTALL_PLATFORM:platform,ELECTRON_INSTALL_ARCH:arch};
  if(!childEnv.electron_config_cache)childEnv.electron_config_cache=join(resolve(root,'..',env.PILOT_DEPENDENCY_CACHE||'.pilot/cache'),'electron');
  // Keep package-pinned checksums even when the user selects a download mirror.
  delete childEnv.electron_use_remote_checksums;delete childEnv.npm_config_electron_use_remote_checksums;
  console.log('Pilot: 正在下载并验证对应系统与架构的桌面运行时…');
  await run(process.execPath,[installer],{cwd:root,env:childEnv,stdio:'inherit',windowsHide:true});
  if(!runtimeReady(root,platform,arch))throw new Error('桌面运行时版本、架构或执行权限校验未通过，请检查安装目录');
 }
 return {ready:true,binary:brand(root)};
}
let direct=false;
try{direct=Boolean(process.argv[1])&&realpathSync(process.argv[1])===fileURLToPath(import.meta.url);}catch{}
if(direct)prepareDesktopRuntime().then(result=>{if(!result.ready)console.log(result.message);}).catch(error=>{console.error('准备桌面运行时失败：'+error.message);process.exitCode=1;});
