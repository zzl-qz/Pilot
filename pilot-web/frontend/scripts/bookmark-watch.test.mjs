import test from 'node:test';
import assert from 'node:assert/strict';
globalThis.window = { fetch: async () => new Response('[]') };
const { watchBookmarks, afterNavigationPaint } = await import('../src/lib/bookmark-watch.js');
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(r => {resolve=r;}); return {promise,resolve}; };
function environment() {
  const win = new EventTarget(), doc = new EventTarget(); doc.hidden=false;
  let poll; win.setInterval=fn=>{poll=fn;return 1;}; win.clearInterval=()=>{poll=null;};
  const storage = (session='s') => {
    const event = new Event('storage'); Object.assign(event,{key:'pilot.bookmarks-changed',newValue:JSON.stringify({session})});win.dispatchEvent(event);
  };
  return {win,doc,storage,poll:()=>poll?.()};
}
test('后台不轮询，恢复可见、联网和其它窗口写入均刷新；关闭后不再工作', async () => {
  const e=environment();let calls=0,invalidations=0;
  const stop=watchBookmarks({session:'s',window:e.win,document:e.doc,refresh:async()=>{calls++;},invalidate:()=>{invalidations++;}});
  await tick();assert.equal(calls,1);
  e.doc.hidden=true;e.poll();e.storage();await tick();assert.equal(calls,1);
  e.doc.hidden=false;e.doc.dispatchEvent(new Event('visibilitychange'));await tick();assert.equal(calls,2);
  e.storage('other');await tick();assert.equal(calls,2);
  e.storage();await tick();assert.equal(calls,3);
  e.win.dispatchEvent(new Event('online'));await tick();assert.equal(calls,4);
  stop();const invalidated=invalidations;e.storage();e.poll();e.win.dispatchEvent(new Event('focus'));await tick();
  assert.equal(calls,4);assert.equal(invalidations,invalidated);
});
test('在途读取遇到跨窗口更新时作废旧结果，并串行补读，避免刷新风暴与旧数据覆盖', async () => {
  const e=environment(), first=deferred(), second=deferred();let calls=0,revision=0;const applied=[];
  const stop=watchBookmarks({session:'s',window:e.win,document:e.doc,invalidate:()=>revision++,refresh:async()=>{
    const own=revision;const result=await (++calls===1?first.promise:second.promise);
    if(own===revision)applied.push(result);
  }});
  for(let i=0;i<20;i++)e.storage();assert.equal(calls,1);
  first.resolve('旧书签');await tick();assert.equal(calls,2);assert.deepEqual(applied,[]);
  second.resolve('最新书签');await tick();assert.deepEqual(applied,['最新书签']);stop();
});
test('后台分页定位不依赖暂停的 animationFrame，超时兜底会取消帧', async () => {
  const raf=globalThis.requestAnimationFrame,cancel=globalThis.cancelAnimationFrame;
  const canceled=[];globalThis.requestAnimationFrame=()=>7;globalThis.cancelAnimationFrame=id=>canceled.push(id);
  try {await afterNavigationPaint();assert.deepEqual(canceled,[7]);}
  finally {globalThis.requestAnimationFrame=raf;globalThis.cancelAnimationFrame=cancel;}
});
