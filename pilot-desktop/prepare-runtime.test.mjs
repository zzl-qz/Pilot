import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm,chmod,readdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {dirname,join,relative} from 'node:path';
import {electronBinary} from './brand-host.mjs';
import {prepareDesktopRuntime,runtimeReady,binaryArchitectures} from './prepare-runtime.mjs';
async function fixture(){
 const root=await mkdtemp(join(tmpdir(),'pilot-desktop-runtime-中文 '));const folder=join(root,'node_modules/electron');await mkdir(folder,{recursive:true});
 await writeFile(join(folder,'package.json'),'{"version":"45.2.1"}');await writeFile(join(folder,'install.js'),'fixture');
 return {root,folder,close:()=>rm(root,{recursive:true,force:true})};
}
async function installed(f,platform,arch,version='45.2.1'){
 const file=electronBinary(f.root,platform);await mkdir(dirname(file),{recursive:true});const bytes=Buffer.alloc(128);
 if(platform==='win32'){bytes.write('MZ');bytes.writeUInt32LE(64,60);bytes.write('PE\0\0',64);bytes.writeUInt16LE(arch==='arm64'?0xaa64:0x8664,68);}
 else if(platform==='darwin'){bytes.writeUInt32LE(0xfeedfacf,0);bytes.writeUInt32LE(arch==='arm64'?0x100000c:0x1000007,4);}
 else{bytes.write('\x7fELF');bytes[5]=1;bytes.writeUInt16LE(arch==='arm64'?183:62,18);}
 await writeFile(file,bytes);await chmod(file,0o755);await writeFile(join(f.folder,'dist/version'),version);await writeFile(join(f.folder,'path.txt'),relative(join(f.folder,'dist'),file));
}
for(const [platform,arch] of [['win32','x64'],['win32','arm64'],['darwin','arm64'],['darwin','x64'],['linux','arm64'],['linux','x64']])test(`桌面冷启动 ${platform}/${arch} 自动准备并校验，缓存不重复下载`,async()=>{
 const f=await fixture();try{
  let calls=0;
  const options={root:f.root,platform,arch,env:{HTTPS_PROXY:'http://proxy.test:8080',electron_use_remote_checksums:'true'},brand:root=>electronBinary(root,platform),run:async(command,args,opts)=>{
   calls++;assert.equal(command,process.execPath);assert.deepEqual(args,[join(f.folder,'install.js')]);assert.equal(opts.cwd,f.root);assert.equal(opts.shell,undefined);assert.equal(opts.env.ELECTRON_INSTALL_ARCH,arch);assert.equal(opts.env.ELECTRON_INSTALL_PLATFORM,platform);assert.equal(opts.env.electron_use_remote_checksums,undefined);assert.equal(opts.env.electron_config_cache,join(dirname(f.root),'.pilot/cache/electron'));await installed(f,platform,arch);
  }};
  const result=await prepareDesktopRuntime(options);assert.equal(result.ready,true);assert.equal(calls,1);assert.equal(runtimeReady(f.root,platform,arch),true);
  assert.equal((await prepareDesktopRuntime(options)).ready,true);assert.equal(calls,1);
 }finally{await f.close();}
});
test('已存在但版本或架构错误的桌面运行时不能当成就绪',async()=>{
 const f=await fixture();try{
  await installed(f,'win32','arm64','44.0.0');assert.equal(runtimeReady(f.root,'win32','x64'),false);
  assert.equal((await prepareDesktopRuntime({root:f.root,platform:'win32',arch:'x64',env:{},brand:()=> 'ready',run:async()=>installed(f,'win32','x64')})).ready,true);
  assert.ok((await readdir(f.folder)).some(name=>name.startsWith('.dist-invalid-')));
 }finally{await f.close();}
});
test('离线与禁用自动补全时不执行下载，也不报告假成功',async()=>{
 for(const env of [{PILOT_OFFLINE:'true'},{PILOT_DESKTOP_PROVISION:'false'}]){
  const f=await fixture();try{let calls=0;const value=await prepareDesktopRuntime({root:f.root,env,run:async()=>{calls++;},brand:()=>{throw new Error('不应生成桌面壳');}});assert.equal(value.ready,false);assert.equal(calls,0);}finally{await f.close();}
 }
});
test('安装失败或二进制损坏会停止，不继续启动桌面',async()=>{
 const f=await fixture();try{
  await assert.rejects(prepareDesktopRuntime({root:f.root,env:{},brand:()=>{throw new Error('不应启动');},run:async()=>{throw new Error('fixture install failed');}}),/fixture install failed/);
  await assert.rejects(prepareDesktopRuntime({root:f.root,env:{},brand:()=>{throw new Error('不应启动');},run:async()=>{}}),/校验未通过/);
  const file=join(f.root,'broken');await writeFile(file,'broken');assert.deepEqual(binaryArchitectures(file),[]);
 }finally{await f.close();}
});
