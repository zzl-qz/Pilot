import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const exec=promisify(execFile);
const root=path.resolve(fileURLToPath(new URL('..',import.meta.url)));
const libraries=path.join(root,'bridge/build/install/bridge/lib');
const source=path.join(root,'scripts/DirectQrSmoke.java');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const result=await exec(java,['--enable-native-access=ALL-UNNAMED','-cp',path.join(libraries,'*'),source],{timeout:30000,maxBuffer:1024*1024});
console.log(result.stdout.trim());
if(process.argv.includes('--container')){
 const isolated=await exec('docker',['run','--rm','--pull','never','--read-only','--tmpfs','/tmp:rw,exec,nosuid,size=512m','--network','none',
  '--entrypoint','java','-v',libraries+':/probe:ro','-v',source+':/DirectQrSmoke.java:ro',process.env.PILOT_PROBE_JAVA_IMAGE||'maven:3.9-eclipse-temurin-25',
  '--enable-native-access=ALL-UNNAMED','-cp','/probe/*','/DirectQrSmoke.java'],{timeout:40000,maxBuffer:1024*1024});
 console.log('Linux 完全断网容器：'+isolated.stdout.trim());
}
