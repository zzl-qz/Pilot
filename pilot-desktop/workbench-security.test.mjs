import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { configuredWorkbenchUrl, workbenchOrigin, isWorkbenchUrl, trustedWorkbench, workbenchIpc, installWorkbenchNavigation } from './workbench-security.mjs';

const home = 'http://127.0.0.1:9999';

test('独立启动读取服务端口，自动启动传入的完整地址优先', () => {
  assert.equal(configuredWorkbenchUrl({}), home + '/');
  assert.equal(configuredWorkbenchUrl({ PILOT_URL: ' ', SERVER_PORT: ' 12456 ' }), 'http://127.0.0.1:12456/');
  assert.equal(configuredWorkbenchUrl({ PILOT_URL: 'https://[::1]:12800/app?q=1', SERVER_PORT: '0' }),
    'https://[::1]:12800/app?q=1');
  for (const SERVER_PORT of ['0', '65536', '-1', '4e3', '9999/path']) {
    assert.throws(() => configuredWorkbenchUrl({ SERVER_PORT }), /SERVER_PORT/);
  }
  assert.throws(() => configuredWorkbenchUrl({ PILOT_URL: 'http://user:password@localhost' }), /无用户名密码/);
});

function fixture() {
  const frame = { origin: home, url: home+'/', detached:false };
  const contents = Object.assign(new EventEmitter(), { mainFrame:frame, isDestroyed:()=>false, getURL:()=>frame.url, send:()=>{}, loadURL:async()=>{} });
  const window = { isDestroyed:()=>false, webContents:contents };
  return { frame, contents, window, event:{sender:contents,senderFrame:frame} };
}

test('只有指定窗口的主框架及实际 HTTP(S) 来源可调用 IPC', () => {
  const f=fixture();
  assert.equal(trustedWorkbench(f.event,f.window,home),true);
  assert.equal(trustedWorkbench({...f.event,sender:{}},f.window,home),false);
  assert.equal(trustedWorkbench({...f.event,senderFrame:{...f.frame}},f.window,home),false,'同源子框架也不能调用');
  assert.equal(trustedWorkbench({...f.event,senderFrame:null},f.window,home),false);
  for (const [url,origin] of [[home+'/', 'null'], ['https://untrusted.test/',home], ['about:blank',home], ['blob:'+home+'/id',home], ['data:text/html,hello','null'], ['http://127.0.0.1:9998/', 'http://127.0.0.1:9998']]) {
    Object.assign(f.frame,{url,origin});
    assert.equal(trustedWorkbench(f.event,f.window,home),false,url);
  }
  Object.assign(f.frame,{url:home,origin:home,detached:true});
  assert.equal(trustedWorkbench(f.event,f.window,home),false);
  f.window.isDestroyed=()=>true;
  assert.equal(trustedWorkbench(f.event,f.window,home),false);
  assert.equal(trustedWorkbench(f.event,null,home),false);
});

test('URL 使用精确 origin，拒绝凭证、危险协议、相似主机和无效配置', () => {
  assert.equal(workbenchOrigin(home+'/path?q=value'),home);
  for (const value of ['http://127.0.0.1.evil.test:9999','http://127.0.0.1:9998','https://127.0.0.1:9999','data:text/html,a','blob:'+home+'/id','file:///tmp/workbench','http://user:password@127.0.0.1:9999']) {
    assert.equal(isWorkbenchUrl(value,home),false,value);
  }
  assert.throws(()=>workbenchOrigin('data:text/html,a'));
  assert.throws(()=>workbenchOrigin('broken'));
});

test('统一入口拒绝不可信 invoke 与单向消息，不执行副作用或推送数据', async () => {
  const f=fixture(), handlers=new Map(), listeners=new Map();
  const guard=workbenchIpc({handle:(name,fn)=>handlers.set(name,fn),on:(name,fn)=>listeners.set(name,fn)},()=>f.window,home);
  let calls=0, sends=0;
  f.frame.send=()=>sends++;
  guard.handle('sensitive',()=>{calls++;return {ok:true,text:'private fixture'};});
  guard.on('bounds',()=>calls++);
  const stranger={...f.event,senderFrame:{origin:'https://foreign.test',url:'https://foreign.test/'}};
  assert.equal((await handlers.get('sensitive')(stranger)).code,'UNTRUSTED_WORKBENCH');
  listeners.get('bounds')(stranger);
  assert.equal(calls,0);
  assert.equal((await handlers.get('sensitive')(f.event)).text,'private fixture');
  listeners.get('bounds')(f.event);
  assert.equal(calls,2);
  assert.equal(guard.send('logs','fixture'),true);
  f.frame.origin='https://foreign.test';
  assert.equal(guard.send('logs','fixture'),false);
  assert.equal(sends,1);
  const swapped=fixture();let oldFrameSent=0,newFrameSent=0;
  const next={origin:'https://foreign.test',url:'https://foreign.test/',send:()=>newFrameSent++};
  swapped.frame.send=()=>oldFrameSent++;
  Object.defineProperty(swapped.frame,'origin',{get:()=>{swapped.contents.mainFrame=next;return home;}});
  const bound=workbenchIpc({},()=>swapped.window,home);
  assert.equal(bound.send('logs','fixture'),true);
  assert.equal(oldFrameSent,1,'只投递到已校验的原框架');
  assert.equal(newFrameSent,0,'切换后的主框架不能收到原页面通知');
});

test('异步响应等待期间页面离开或框架销毁，不把结果交给新页面', async () => {
  const f=fixture(), handlers=new Map();
  const guard=workbenchIpc({handle:(name,fn)=>handlers.set(name,fn)},()=>f.window,home);
  let finish;
  guard.handle('logs',()=>new Promise(resolve=>{finish=resolve;}));
  const pending=handlers.get('logs')(f.event);
  f.frame.origin='https://foreign.test';
  finish({ok:true,text:'secret fixture'});
  assert.equal((await pending).code,'UNTRUSTED_WORKBENCH');
});

test('工作台导航、重定向、子框架和弹窗限制，外部网页有可用替代入口', async () => {
  const f=fixture(), opened=[], notices=[];
  f.contents.setWindowOpenHandler=fn=>{f.popup=fn;};
  let loaded='';f.contents.loadURL=async url=>{loaded=url;};
  installWorkbenchNavigation(f.contents,home,{openPage:url=>opened.push(url),notify:text=>notices.push(text)});
  const navigate=(url,isMainFrame=true,type='will-frame-navigate')=>{
    let prevented=false;
    f.contents.emit(type,{url,isMainFrame,preventDefault:()=>{prevented=true;}});
    return prevented;
  };
  assert.equal(navigate(home+'/next'),false);
  assert.equal(navigate('https://example.test/page'),true);
  assert.deepEqual(opened,['https://example.test/page']);
  f.contents.emit('will-frame-navigate',{url:'https://example.test/legacy',isMainFrame:true,preventDefault:()=>{}},'https://example.test/legacy',false,true,1234,5678);
  assert.equal(opened.pop(),'https://example.test/legacy');
  assert.equal(navigate('https://example.test/child',false),true);
  assert.equal(navigate('https://example.test/redirect',true,'will-redirect'),true);
  assert.equal(opened.length,1);
  assert.equal(navigate('about:blank',false),false);
  assert.equal(navigate('about:blank'),true);
  assert.equal(navigate('javascript:alert(1)'),true);
  assert.equal(notices.length,3);
  assert.deepEqual(f.popup({url:'https://example.test/new'}),{action:'deny'});
  assert.equal(opened.at(-1),'https://example.test/new');
  f.popup({url:home+'/same-origin'});
  assert.equal(loaded,'','同源弹窗也不能替换工作台');
  assert.equal(opened.at(-1),home+'/same-origin');
  f.popup({url:'file:///tmp/secret'});
  assert.equal(opened.length,3);
});
