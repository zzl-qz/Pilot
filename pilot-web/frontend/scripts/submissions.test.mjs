import test from 'node:test';
import assert from 'node:assert/strict';
globalThis.window = { fetch: async () => { throw Error('unexpected network'); } };
const { createSubmissions } = await import('../src/lib/submissions.js');
const { ApiError } = await import('../src/api.js');
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
function fixture() {
  const data = new Map();
  const storage = { get length() { return data.size; }, key: i => [...data.keys()][i], getItem: k => data.get(k) ?? null, setItem: (k,v) => data.set(k,v), removeItem: k => data.delete(k) };
  const calls = []; let id = 0;
  const client = {
    submissionToken: async () => ({ requestId: `request-${++id}` }),
    sendMessage: async (sessionId, body, requestId) => { calls.push({ sessionId, body, requestId }); return { sessionId, runId: 'r' }; },
    submissionResult: async () => ({ state: 'unknown' }),
    acknowledgeSubmission: async () => {}
  };
  return { data, storage, calls, client, manager: createSubmissions(client, () => storage) };
}
test('凭据先持久化再发送，成功后清理；相同正文的两次明确意图使用不同标识', async () => {
  const f = fixture();
  f.client.sendMessage = async (sessionId, body, requestId) => {
    assert.equal(f.manager.pending(sessionId)[0].requestId, requestId);
    assert.equal([...f.data.values()][0].includes('dataBase64'), false);
    f.calls.push(requestId); return { sessionId };
  };
  for (let i=0;i<2;i++) assert.equal((await f.manager.submit('s', { content: '任务', attachments: [{ dataBase64:'abc' }] }, () => true)).state, 'accepted');
  assert.equal(f.data.size, 0); assert.equal(new Set(f.calls).size, 2);
});
test('执行后丢失响应只查询一次回执，不重发 POST', async () => {
  const f = fixture();
  f.client.sendMessage = async (...args) => { f.calls.push(args); throw TypeError('响应丢失'); };
  f.client.submissionResult = async () => ({ state: 'accepted' });
  assert.equal((await f.manager.submit('s', {content:'任务'}, () => true)).state, 'accepted');
  assert.equal(f.calls.length,1); assert.equal(f.manager.pending('s').length,0);
});
test('未知结果跨刷新保留，连续点击不会重新申请凭据或重复提交', async () => {
  const f = fixture(); f.client.sendMessage = async (...args) => { f.calls.push(args); throw Error('超时'); };
  assert.equal((await f.manager.submit('s', { content:'任务' }, () => true)).state, 'unknown');
  const reopened = createSubmissions(f.client, () => f.storage);
  for (let i=0;i<3;i++) assert.equal((await reopened.submit('s', {content:'任务'}, () => true)).state,'blocked');
  assert.equal(f.calls.length,1);
  f.client.submissionResult = async () => ({state:'accepted'});
  assert.equal((await reopened.check(reopened.pending('s')[0])).state,'accepted');
  assert.equal(f.calls.length,1); assert.equal(f.manager.pending('s').length,0);
});
test('回执确认失败跨刷新保留确认凭据，重新联网只重试确认而不执行消息', async () => {
  const f=fixture();let acknowledgments=0;
  f.client.acknowledgeSubmission=async()=>{acknowledgments++;throw Error('确认丢包');};
  assert.equal((await f.manager.submit('s',{content:'任务'},()=>true)).state,'accepted');
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(f.data.size,1);assert.equal(f.manager.pending('s').length,0);
  await new Promise(resolve=>setImmediate(resolve));
  f.client.acknowledgeSubmission=async()=>{acknowledgments++;};
  const reopened=createSubmissions(f.client,()=>f.storage);
  assert.equal(reopened.pending('another-session').length,0);
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(f.data.size,0);assert.equal(f.calls.length,1);assert.ok(acknowledgments>=2);
});
test('明确 4xx 拒绝允许修改后新提交；未知 409 与 5xx 保留凭据', async () => {
  for (const [error, expected] of [[new ApiError('校验失败',400),'rejected'],[new ApiError('执行中',409,true),'unknown'],[new ApiError('磁盘失败',507),'unknown']]) {
    const f=fixture(); f.client.sendMessage=async()=>{throw error;};
    assert.equal((await f.manager.submit('s',{content:'任务'},()=>true)).state,expected);
    assert.equal(f.manager.pending('s').length,expected==='rejected'?0:1);
  }
});
test('凭据获取失败、存储不可用或损坏时不发送业务请求，锁会释放', async () => {
  const f=fixture(); const token=f.client.submissionToken;
  f.client.submissionToken=async()=>{throw Error('offline');};
  assert.equal((await f.manager.submit('s',{content:'任务'},()=>true)).state,'rejected');
  f.client.submissionToken=token;
  f.storage.setItem=()=>{throw Error('QuotaExceeded');};
  assert.equal((await f.manager.submit('s',{content:'任务'},()=>true)).state,'rejected');
  assert.equal(f.calls.length,0);
  f.data.set('pilot.pending-submission:broken','broken');
  assert.equal((await f.manager.submit('s',{content:'任务'},()=>true)).state,'rejected');
});
test('同窗口并发提交只执行一次；令牌期间切换会话取消尚未发送的任务', async () => {
  const f=fixture(), gate=deferred(); f.client.submissionToken=()=>gate.promise;
  const first=f.manager.submit('s',{content:'任务'},()=>false);
  assert.equal((await f.manager.submit('s',{content:'任务'},()=>true)).state,'blocked');
  gate.resolve({requestId:'id'}); assert.equal((await first).state,'blocked');
  assert.equal(f.calls.length,0); assert.equal(f.data.size,0);
  f.client.submissionToken=async()=>({requestId:'next'});
  assert.equal((await f.manager.submit('s',{content:'任务'},()=>true)).state,'accepted');
});
test('查询失败、空响应和确认回执失败均不会自动重做任务', async () => {
  const f=fixture(); f.client.sendMessage=async(...args)=>{f.calls.push(args);return null;};
  f.client.submissionResult=async()=>{throw Error('offline');};
  assert.equal((await f.manager.submit('s',{content:'任务'},()=>true)).state,'unknown');
  const record=f.manager.pending('s')[0];
  assert.equal((await f.manager.check(record)).state,'unknown');
  f.client.submissionResult=async()=>({state:'rejected',response:{message:'无效消息'}});
  f.client.acknowledgeSubmission=async()=>{throw Error('offline');};
  assert.deepEqual(await f.manager.check(record),{state:'rejected',message:'无效消息',response:{message:'无效消息'}});
  assert.equal(f.calls.length,1); assert.equal(f.data.size,1); assert.equal(f.manager.pending('s').length,0);
});
