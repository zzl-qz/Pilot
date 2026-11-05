// 只允许专用 Pilot 模拟器和独立 QA 包；不修改真机或其他项目的数据。
import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const exec=promisify(execFile);
const root=fileURLToPath(new URL('..',import.meta.url));
const sdk=process.env.ANDROID_HOME||process.env.ANDROID_SDK_ROOT;
if(!sdk)throw new Error('请设置 ANDROID_HOME');
const serial=process.argv[2];
if(!/^emulator-\d+$/.test(serial||''))throw new Error('只接受 Pilot 专用模拟器编号');
const adb=path.join(sdk,'platform-tools',process.platform==='win32'?'adb.exe':'adb');
async function run(...args){return (await exec(adb,['-s',serial,...args],{timeout:30000,maxBuffer:2*1024*1024})).stdout.trim();}
assert.match(await run('emu','avd','name'),/^pilot[-_]/i,'不能使用其他项目的模拟器');
const artifacts=path.join(root,'build/scanner-qa');await mkdir(artifacts,{recursive:true});
function nodes(xml){
 return [...xml.matchAll(/<node\b[^>]*>/g)].map(([tag])=>Object.fromEntries([...tag.matchAll(/([\w-]+)="([^"]*)"/g)].map(([,key,value])=>[key,value])));
}
async function snapshot(){
 await run('shell','rm','-f','/sdcard/pilot-scanner-test.xml');
 const dumped=await run('shell','uiautomator','dump','/sdcard/pilot-scanner-test.xml');
 if(!dumped.includes('dumped'))return {xml:'',nodes:[]};
 const xml=await run('shell','cat','/sdcard/pilot-scanner-test.xml');
 return {xml,nodes:nodes(xml)};
}
async function waitFor(predicate){
 const end=Date.now()+25000;
 while(Date.now()<end){const state=await snapshot();if(predicate(state))return state;await new Promise(r=>setTimeout(r,150));}
 throw new Error('等待扫码页面状态超时');
}
const text=value=>state=>state.nodes.some(n=>n.text===value);
async function clickWhere(predicate){
 const state=await waitFor(s=>s.nodes.some(predicate));const node=state.nodes.find(predicate);
 const bounds=node.bounds.match(/\d+/g).map(Number);
 assert.equal(bounds.length,4);assert.ok(bounds[2]>bounds[0]&&bounds[3]>bounds[1],'控件必须可见');
 await run('shell','input','tap',String(Math.floor((bounds[0]+bounds[2])/2)),String(Math.floor((bounds[1]+bounds[3])/2)));
}
const click=value=>clickWhere(n=>n.text===value);
const deny=n=>n['resource-id']?.endsWith('/permission_deny_button')||n['resource-id']?.endsWith('/permission_deny_and_dont_ask_again_button');
async function screenshot(name){
 const {stdout}=await exec(adb,['-s',serial,'exec-out','screencap','-p'],{encoding:'buffer',maxBuffer:8*1024*1024,timeout:10000});
 await writeFile(path.join(artifacts,name+'.png'),stdout);
}
const rotation=await run('shell','settings','get','system','user_rotation');
const auto=await run('shell','settings','get','system','accelerometer_rotation');
const font=await run('shell','settings','get','system','font_scale');
try{
 await run('shell','am','force-stop','com.pilot.mobile.qa');
 await run('shell','input','keyevent','KEYCODE_HOME');
 await run('shell','pm','clear','com.pilot.mobile.qa');
 await run('shell','am','start','-S','-f','0x10008000','-n','com.pilot.mobile.qa/com.pilot.mobile.MainActivity');
 console.log('检查首次相机拒权');
 await click('扫码连接电脑');
 await clickWhere(deny);
 await waitFor(text('需要相机权限才能扫码'));await screenshot('permission-denied');
 await click('改用配对码连接');await waitFor(text('电脑生成的配对码'));
 console.log('检查第二次拒权');
 await click('取消');await click('扫码连接电脑');
 const second=await waitFor(s=>s.nodes.some(deny)||text('打开权限设置')(s));
 if(!text('打开权限设置')(second)){
  // Android 8 使用“不再询问”复选框，Android 11+ 连续拒绝后自动进入永久拒权。
  const neverAsk=n=>n['resource-id']?.endsWith('/do_not_ask_checkbox');
  if(second.nodes.some(neverAsk))await clickWhere(neverAsk);
  await clickWhere(deny);
 }
 await waitFor(text('打开权限设置'));await screenshot('permission-settings');
 await click('打开权限设置');
 await waitFor(s=>s.nodes.some(n=>n.package==='com.android.settings'));
 await run('shell','input','keyevent','KEYCODE_BACK');
 await waitFor(text('改用配对码连接'));
 await clickWhere(n=>n['content-desc']==='返回');
 const returned=await waitFor(text('扫码连接电脑'));assert.ok(!returned.xml.includes('扫码已取消'));
 await run('shell','pm','grant','com.pilot.mobile.qa','android.permission.CAMERA');
 await click('扫码连接电脑');
 await waitFor(text('扫描电脑上的配对码'));
 await run('shell','settings','put','system','accelerometer_rotation','0');
 await run('shell','settings','put','system','user_rotation','1');
 await run('shell','settings','put','system','font_scale','1.5');
 const portrait=await waitFor(text('扫描电脑上的配对码'));assert.match(portrait.xml,/<hierarchy rotation="0"/);
 const size=await run('shell','wm','size');const [,width,height]=size.match(/(\d+)x(\d+)/).map(Number);
 await run('shell','input','swipe',String(Math.floor(width/2)),String(Math.floor(height*.8)),String(Math.floor(width/2)),String(Math.floor(height*.35)),'300');
 await waitFor(text('改用配对码连接'));await screenshot('large-text-portrait');
 await click('改用配对码连接');await waitFor(text('电脑生成的配对码'));
 console.log('PASS: 相机首次拒权、永久拒权设置入口、拒权后粘贴、设置返回、取消无错误、系统横屏偏好下竖屏扫码、大字体替代操作');
 console.log('Screenshots: '+artifacts);
}finally{
 for(const [name,value] of [['user_rotation',rotation],['accelerometer_rotation',auto],['font_scale',font]]){
  if(value==='null')await run('shell','settings','delete','system',name);else await run('shell','settings','put','system',name,value);
 }
}
