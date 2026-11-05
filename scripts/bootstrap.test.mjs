import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, readFile, writeFile, cp, chmod, rm, readdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const root=resolve(fileURLToPath(new URL('..',import.meta.url)));
const windows=process.platform==='win32';
const powershell=process.env.PILOT_TEST_POWERSHELL || (windows?'powershell.exe':'pwsh');
const hasPowerShell=spawnSync(powershell,['-NoProfile','-Command','$PSVersionTable.PSVersion.Major'],{encoding:'utf8',timeout:10000}).status===0;
const originalJavaHome=process.env.JAVA_HOME;
const javaHome=process.env.PILOT_TEST_JAVA_HOME || process.env.JAVA_HOME;
const java=javaHome?join(javaHome,'bin',windows?'java.exe':'java'):'java';
const javaVersion=spawnSync(java,['-version'],{encoding:'utf8',timeout:10000});
const hasJava25=/version "(?:2[5-9]|[3-9][0-9])/.test(`${javaVersion.stdout}${javaVersion.stderr}`);
const quote=value=>"'"+String(value).replaceAll("'","''")+"'";
async function fixture(){
 const directory=await mkdtemp(join(tmpdir(),'Pilot 环境 with spaces & 100%-'));
 for(const file of ['scripts/start.ps1','scripts/bootstrap-java.sh','start.sh','pilot-core/src/main/resources/bootstrap/runtime.properties']){
  const target=join(directory,file);await mkdir(resolve(target,'..'),{recursive:true});await cp(join(root,file),target);
 }
 const source=join(directory,'pilot-core/src/main/java/com/pilot/core/runtime/bootstrap/SourceEnvironment.java');
 await mkdir(resolve(source,'..'),{recursive:true});
 await writeFile(source,'public class SourceEnvironment { public static void main(String[] args) throws Exception { java.nio.file.Files.write(java.nio.file.Path.of(System.getenv("PILOT_TEST_CAPTURE")), java.util.List.of(args)); } }');
 const env={...process.env,JAVA_HOME:javaHome||'',PILOT_BOOTSTRAP_DIR:join(directory,'cache'),PILOT_TEST_CAPTURE:join(directory,'capture.txt'),PILOT_OFFLINE:'true',PILOT_FORCE_MANAGED_JAVA:'false',PILOT_START_CHECK_ONLY:'true'};
 return {directory,env,close:()=>rm(directory,{recursive:true,force:true})};
}
function run(command,args,env,cwd){return spawnSync(command,args,{env,cwd,encoding:'utf8',timeout:30000,windowsHide:true});}
const detail=result=>`${result.stdout||''}\n${result.stderr||''}`;
test('Unix 启动器复用 JDK 且保留中文空格和特殊字符参数',{skip:windows||!hasJava25},async()=>{
 const f=await fixture();try{
  const value='--pilot.data.dir=中文 path & 100%';
  const result=run('bash',[join(f.directory,'start.sh'),value],f.env,f.directory);
  assert.equal(result.status,0,detail(result));
  const args=(await readFile(f.env.PILOT_TEST_CAPTURE,'utf8')).trim().split(/\r?\n/);
  assert.deepEqual(args,['prepare',f.directory,value]);
  assert.equal(process.env.JAVA_HOME,originalJavaHome);
 }finally{await f.close();}
});
test('离线缺少私有 JDK 时快速失败，不访问下载地址',async()=>{
 const f=await fixture();try{
  f.env.PILOT_FORCE_MANAGED_JAVA='true';
  const result=windows?run(powershell,['-NoProfile','-ExecutionPolicy','Bypass','-File',join(f.directory,'scripts/start.ps1')],f.env,f.directory):run('bash',[join(f.directory,'start.sh')],f.env,f.directory);
  assert.notEqual(result.status,0);assert.match(detail(result),/offline/i);
  assert.equal((await readdir(f.directory)).includes('capture.txt'),false);
 }finally{await f.close();}
});
test('PowerShell 脚本语法可解析',{skip:!hasPowerShell},()=>{
 const files=['scripts/start.ps1','pilot-core/src/main/resources/bootstrap/install-vcredist.ps1'].map(file=>quote(join(root,file))).join(',');
 const script=`foreach($f in @(${files})){$t=$null;$e=$null;[void][System.Management.Automation.Language.Parser]::ParseFile($f,[ref]$t,[ref]$e);if($e.Count){throw ($e|Out-String)}}`;
 const result=run(powershell,['-NoProfile','-Command',script],process.env,root);
 assert.equal(result.status,0,detail(result));
});
test('Windows 启动逻辑模拟：版本信息在 stderr 时仍能选择 JDK 并传递参数',{skip:!hasPowerShell||!hasJava25},async()=>{
 const f=await fixture();try{
  if(!windows){
   const fake=join(f.directory,'模拟 Windows JDK');await mkdir(join(fake,'bin'),{recursive:true});
   await writeFile(join(fake,'bin/javac.exe'),'fixture');
   await writeFile(join(fake,'bin/java.exe'),'#!/bin/sh\nif [ "$1" = "-XshowSettings:properties" ]; then printf "java.specification.version = 25\\njava.home = %s\\nos.arch = amd64\\n" "$PILOT_FAKE_JAVA_HOME" >&2; exit 0; fi\nexec "$PILOT_REAL_JAVA" "$@"\n');
   await chmod(join(fake,'bin/java.exe'),0o755);f.env.JAVA_HOME=fake;f.env.PILOT_FAKE_JAVA_HOME=fake;f.env.PILOT_REAL_JAVA=java;
  }
  const value='--pilot.data.dir=中文 path & 100%';
  const result=run(powershell,['-NoProfile','-ExecutionPolicy','Bypass','-File',join(f.directory,'scripts/start.ps1'),value],f.env,f.directory);
  assert.equal(result.status,0,detail(result));
  assert.deepEqual((await readFile(f.env.PILOT_TEST_CAPTURE,'utf8')).trim().split(/\r?\n/),['prepare',f.directory,value]);
 }finally{await f.close();}
});
test('Windows 下载校验失败不会执行包或发布安装目录',{skip:!hasPowerShell},async()=>{
 const f=await fixture();try{
  f.env.PILOT_OFFLINE='false';f.env.PILOT_FORCE_MANAGED_JAVA='true';
  const wrapper=join(f.directory,'bad-download.ps1');
  await writeFile(wrapper,`function Invoke-WebRequest {param($Uri,$OutFile,$UseBasicParsing,$TimeoutSec,$Proxy) [IO.File]::WriteAllText($OutFile,'tampered')}; & ${quote(join(f.directory,'scripts/start.ps1'))}`);
  const result=run(powershell,['-NoProfile','-ExecutionPolicy','Bypass','-File',wrapper],f.env,f.directory);
  assert.notEqual(result.status,0);assert.match(detail(result),/checksum failed/i);
  const entries=await readdir(f.env.PILOT_BOOTSTRAP_DIR);assert.equal(entries.some(name=>name.startsWith('jdk25-')||name.startsWith('.jdk25-')),false);
 }finally{await f.close();}
});
for(const invalid of ['NotTrusted','wrongPublisher'])test(`微软安装器校验拒绝 ${invalid}，不执行系统安装`,{skip:!hasPowerShell},async()=>{
 const f=await fixture();try{
  const marker=join(f.directory,'should-not-install');
  const signature=invalid==='NotTrusted'?"Status='NotTrusted';SignerCertificate=@{Subject='O=Microsoft Corporation'}":"Status='Valid';SignerCertificate=@{Subject='O=Microsoft Corporation Evil'}";
  const script=`function Get-AuthenticodeSignature {param($FilePath) [pscustomobject]@{${signature}}}; function Start-Process {[IO.File]::WriteAllText(${quote(marker)},'unexpected')}; & ${quote(join(root,'pilot-core/src/main/resources/bootstrap/install-vcredist.ps1'))} -Installer 'fixture.exe'`;
  const result=run(powershell,['-NoProfile','-Command',script],f.env,f.directory);
  assert.notEqual(result.status,0);await assert.rejects(readFile(marker));
 }finally{await f.close();}
});
test('微软安装流程模拟：可信签名、无自动重启、授权拒绝不无限重试',{skip:!hasPowerShell},async()=>{
 const f=await fixture();try{
  const marker=join(f.directory,'installer-args');
  const script=`function Get-AuthenticodeSignature {param($FilePath) [pscustomobject]@{Status='Valid';SignerCertificate=@{Subject='CN=Microsoft Corporation, O=Microsoft Corporation, L=Redmond'}}}; function Start-Process {param($FilePath,$ArgumentList,[switch]$PassThru,[switch]$Wait) [IO.File]::WriteAllLines(${quote(marker)},$ArgumentList); [pscustomobject]@{ExitCode=0}}; & ${quote(join(root,'pilot-core/src/main/resources/bootstrap/install-vcredist.ps1'))} -Installer 'fixture.exe'`;
  let result=run(powershell,['-NoProfile','-Command',script],f.env,f.directory);assert.equal(result.status,0,detail(result));
  assert.deepEqual((await readFile(marker,'utf8')).trim().split(/\r?\n/),['/install','/passive','/norestart']);
  result=run(powershell,['-NoProfile','-Command',script.replace('[pscustomobject]@{ExitCode=0}',"throw 'UAC cancelled'")],f.env,f.directory);
  assert.notEqual(result.status,0);assert.match(detail(result),/not completed/i);
 }finally{await f.close();}
});
