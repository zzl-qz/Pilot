import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { mkdtemp, rm, readFile, writeFile, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const exec = promisify(execFile);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
if (!sdk) throw new Error('请设置 ANDROID_HOME');
const adb = path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
const serial = process.argv[2];
if (!serial) throw new Error('传入专用测试设备编号：node scripts/android-smoke.mjs emulator-5580');
const runAdb = async (...args) => {
  try { return await exec(adb, ['-s', serial, ...args], { timeout: 120000, maxBuffer: 1024 * 1024 }); }
  catch (error) {
    // execFile 的原始异常包含整条命令；配对参数不能随着设备故障写进日志。
    const details = `${error.stdout || ''}\n${error.stderr || ''}`.replace(/pilot:\/\/pair\/[A-Za-z0-9_-]+/g, '[配对码已隐藏]').slice(-4000);
    throw new Error(`设备 ${serial} 的 ${args.slice(0, 3).join(' ')} 操作中断（${error.code ?? 'unknown'}），本轮不计通过。\n${details}`);
  }
};
const directory = await mkdtemp(path.join(tmpdir(), 'pilot-mobile-qa-'));
const events = [{ sequence: 1, type: 'run.started', runId: 'run_test', payload: { input: '正在核对测试夹具' } }];
const subscribers = new Set();
let posted = 0;
const receipts = new Map();
let approved = 0, steered = 0, paused = 0, continued = 0, cancelled = 0;
let activeRun = 'run_test', queue = [], revision = '1';
let fullSnapshots = 0, unchangedSnapshots = 0, parallelTaskReads = false, firstHistoryLimit;
const taskReads = new Set();
let approvals = [{ callId: 'call_test', toolName: 'run_command', argsSummary: '只运行本地验收夹具', sessionId:'ses_test', title:'移动连接验收' }];
function emit(type, payload, runId = activeRun || 'run_test') {
  const event = { sequence: events.length + 1, type, runId, payload };
  events.push(event); for (const subscriber of subscribers) subscriber.write(`data: ${JSON.stringify(event)}\n\n`);
}
const session = { id: 'ses_test', projectId:'project_test', title: '移动连接验收', workspace: '测试项目', status: 'RUNNING' };
const other = { id:'ses_other', title:'另一个项目的任务', workspace:'第二项目', projectId:'project_other', status:'WAITING_APPROVAL' };
const otherApproval = {callId:'call_other', toolName:'run_command', argsSummary:'这属于另一个任务', sessionId:'ses_other', title:other.title};
const projects = [{id:'project_test',name:'测试项目',folders:[]},{id:'project_other',name:'第二项目',folders:[]}];
const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  res.setHeader('Content-Type', 'application/json');
  const json = value => res.end(JSON.stringify(value));
  // 每个任务读取人为增加 500ms，确认历史、运行状态、队列真实重叠，串行实现无法通过。
  const taskRead = url.pathname.match(/\/(history|runs\/active|queue)$/)?.[1];
  if (req.method === 'GET' && taskRead) {
    taskReads.add(taskRead);
    if (['history', 'runs/active', 'queue'].every(key => taskReads.has(key))) parallelTaskReads = true;
    await new Promise(resolve => setTimeout(resolve, 500));
    taskReads.delete(taskRead);
  }
  if (url.pathname.endsWith('/events')) {
    res.setHeader('Content-Type', 'text/event-stream'); res.write(': connected\n\n');
    for (const event of events.filter(e => e.sequence > Number(url.searchParams.get('after') || 0))) res.write(`data: ${JSON.stringify(event)}\n\n`);
    subscribers.add(res);
    const timer = setInterval(() => res.write(': ping\n\n'), 1000);
    res.on('close', () => { clearInterval(timer); subscribers.delete(res); });
  } else if (url.pathname === '/api/mobile/workbench') {
    const snapshot = {version:1,sessions:[session,other],projects,approvals:[...approvals,otherApproval],results:{items:[]},capabilities:{idempotency:true,conditionalSnapshot:true}};
    const revision = createHash('sha256').update(JSON.stringify(snapshot)).digest('hex');
    if (url.searchParams.get('revision') === revision) { unchangedSnapshots++; json({version:1,revision,unchanged:true}); }
    else { fullSnapshots++; json({...snapshot,revision}); }
  }
  else if (url.pathname === '/api/sessions') json([session,other]);
  else if (url.pathname === '/api/projects') json(projects);
  else if (url.pathname === '/api/sessions/ses_test' && req.method === 'GET') json(session);
  else if (url.pathname.endsWith('/history')) { firstHistoryLimit ??= Number(url.searchParams.get('limit')); json({ events, hasMore: false, total: events.length }); }
  else if (url.pathname.endsWith('/runs/active')) json({ runId: activeRun });
  else if (url.pathname.endsWith('/queue')) json({ items: queue, entries: [], revision });
  else if (url.pathname.endsWith('/context')) json({usedTokens:1200,limitTokens:16000,percent:7.5,estimated:true,segments:[{role:'system',messages:1,chars:900},{role:'user',messages:2,chars:1600}],usage:{inputTokens:1200,outputTokens:80}});
  else if (url.pathname === '/api/approvals') json(approvals);
  else if (url.pathname === '/api/approvals/call_test' && req.method === 'POST') {
    let raw = ''; for await (const chunk of req) raw += chunk;
    if (JSON.parse(raw).approved !== true) { res.statusCode = 400; json({ error: '审批参数不匹配' }); return; }
    approved++; approvals = []; json({ resolved: true });
  } else if (url.pathname.endsWith('/queue/0/steer') && req.method === 'POST') {
    let raw = ''; for await (const chunk of req) raw += chunk;
    const body = JSON.parse(raw);
    if (body.revision !== revision || body.runId !== activeRun) { res.statusCode = 409; json({ error: '队列已变化' }); return; }
    steered++; emit('run.steered', { input: queue[0] }); queue = []; revision = '3'; json({ items: queue, revision });
  } else if (url.pathname.endsWith('/pause') && req.method === 'POST') {
    paused++; emit('run.cancelled', { reason: 'paused' }); activeRun = ''; session.status = 'PAUSED'; json({ accepted: true });
  } else if (url.pathname.endsWith('/continue') && req.method === 'POST') {
    continued++; session.status = 'RUNNING'; activeRun = 'run_next'; emit('run.continued', { attempt: 1, maxAttempts: 1, reason: '继续' }); json({ runId: activeRun });
  } else if (url.pathname.endsWith('/cancel') && req.method === 'POST') {
    cancelled++; session.status = 'CANCELLED'; emit('run.cancelled', { reason: 'cancelled' }); activeRun = ''; json({ accepted: true });
  }
  else if (url.pathname.endsWith('/messages') && req.method === 'POST') {
    let raw = ''; for await (const chunk of req) raw += chunk;
    const payload = JSON.parse(raw);
    if (payload.content === '模拟断线提交') {
      const key = req.headers['x-pilot-request-id'];
      if (!key || !/^[a-f0-9]{64}:[a-f0-9-]{36}$/.test(key)) { res.statusCode = 400; json({error:'缺少持久请求标识'}); return; }
      if (receipts.has(key)) { json(receipts.get(key)); return; }
      posted++; receipts.set(key,{sessionId:'ses_test',runId:'run_test'});
      emit('assistant.delta',{text:'断线指令只执行了一次'});
      res.destroy(); return;
    }
    if (payload.content !== '请补充边界情况') { res.statusCode = 400; json({ error: '内容不匹配' }); return; }
    posted++;
    // 保持请求在途，验证继续编辑的草稿不会被旧请求成功回调清空。
    await new Promise(resolve => setTimeout(resolve, 3000));
    queue = ['请补充边界情况']; revision = '2';
    emit('assistant.delta', { text: '已收到手机指令' });
    json({ runId: 'run_test', sessionId: 'ses_test' });
  } else { res.statusCode = 404; json({ error: 'unknown fixture endpoint' }); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const bridge = spawn(java, ['-cp', path.join(root, 'bridge/build/install/bridge/lib/*'), 'com.pilot.mobile.bridge.MainKt', '--pilot-port', String(server.address().port), '--data-dir', directory, '--no-relay', '--no-open'], { stdio: ['ignore', 'pipe', 'pipe'] });
let bridgeErrors = '';
bridge.stderr.on('data', chunk => { bridgeErrors += chunk; });
try {
  const admin = await new Promise((resolve, reject) => {
    let output = '';
    const timeout = setTimeout(() => reject(new Error('桥接启动超时')), 30000);
    bridge.once('exit', code => { clearTimeout(timeout); reject(new Error(`桥接退出 ${code}: ${bridgeErrors}`)); });
    bridge.stdout.on('data', chunk => { output += chunk; const match = output.match(/PILOT_MOBILE_ADMIN (\S+)/); if (match) { clearTimeout(timeout); resolve(new URL(match[1])); } });
  });
  const auth = admin.hash.slice(1); admin.hash = '';
  const response = await fetch(new URL('/api/pair', admin), { method: 'POST', headers: { 'X-Pilot-Pairing': auth } });
  if (!response.ok) throw new Error('生成配对码失败');
  const invite = await response.json();
  await runAdb('install', '-r', path.join(root, 'app/build/outputs/apk/qa/app-qa.apk'));
  await runAdb('install', '-r', path.join(root, 'app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk'));
  // 仅重置独立 .qa 包，用户安装的 com.pilot.mobile 不受影响。
  await runAdb('shell', 'pm', 'clear', 'com.pilot.mobile.qa');
  const output = await runAdb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.pilot.mobile.MobileSmokeTest#nativePairingTaskAndReconnect', '-e', 'pairingCode', invite.code, 'com.pilot.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner');
  console.log(output.stdout.replaceAll(invite.code, '[配对码已隐藏]'));
  if (!output.stdout.includes('OK (1 test)')) throw new Error('配对与任务流程验收失败，不继续依赖它的恢复用例');
  const restored = await runAdb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.pilot.mobile.MobileSmokeTest#restoresDeviceAndDraftAfterProcessRestart', 'com.pilot.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner');
  console.log(restored.stdout);
  if (!restored.stdout.includes('OK (1 test)')) throw new Error('重启恢复验收失败');
  const nextInvite = await (await fetch(new URL('/api/pair', admin), {method:'POST',headers:{'X-Pilot-Pairing':auth}})).json();
  const boundaries = await runAdb('shell','am','instrument','-w','-r','-e','class','com.pilot.mobile.MobileSmokeTest#attachmentLimitsAndMultipleComputerStorage','-e','pairingCode',nextInvite.code,'com.pilot.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner');
  console.log(boundaries.stdout.replaceAll(nextInvite.code,'[配对码已隐藏]'));
  if (!boundaries.stdout.includes('OK (1 test)')) throw new Error('附件与多电脑隔离验收失败');
  const design = await runAdb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.pilot.mobile.MobileDesignTest', 'com.pilot.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner');
  console.log(design.stdout);
  if (!design.stdout.includes('OK (4 tests)')) throw new Error('小屏、大字体、主题与消息排版验收失败');
  const counts = { posted, approved, steered, paused, continued, cancelled };
  if (!output.stdout.includes('OK (1 test)') || posted !== 2 || [approved,steered,paused,continued,cancelled].some(count => count !== 1)) throw new Error(`Android 验收失败：${JSON.stringify(counts)}`);
  if (!parallelTaskReads || firstHistoryLimit !== 60 || unchangedSnapshots === 0) throw new Error(`性能回归：${JSON.stringify({parallelTaskReads,firstHistoryLimit,fullSnapshots,unchangedSnapshots})}`);
  console.log('性能验收：' + JSON.stringify({parallelTaskReads,firstHistoryLimit,fullSnapshots,unchangedSnapshots,perReadDelayMs:500}));
  const artifacts = path.join(root, 'build/mobile-qa'); await mkdir(artifacts, { recursive: true });
  for (const name of ['pairing', 'tasks', 'conversation', 'inbox', 'devices', 'usage']) {
    await runAdb('pull', `/sdcard/Android/data/com.pilot.mobile.qa/files/${name}.png`, path.join(artifacts, `${name}.png`));
  }
  for (const name of ['small-search-empty', 'pairing-large-text', 'approval-large-text', 'conversation-small', 'devices-light', 'devices-dark', 'projects-small']) {
    await runAdb('pull', `/sdcard/Android/data/com.pilot.mobile.qa/files/design-${name}.png`, path.join(artifacts, `design-${name}.png`));
  }
  await runAdb('shell', 'pm', 'clear', 'com.pilot.mobile.qa');
  const scanning = await runAdb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.pilot.mobile.PairingScannerTest', 'com.pilot.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner');
  console.log(scanning.stdout);
  if (!scanning.stdout.includes('OK (2 tests)')) throw new Error('竖屏扫码、取消与粘贴切换验收失败');
  await runAdb('pull', '/sdcard/Android/data/com.pilot.mobile.qa/files/scanner-portrait.png', path.join(artifacts, 'scanner-portrait.png'));
  console.log('通过：应用级多任务、审批归属、原生配对、QUIC/SSE、指令与控制、断线回执去重、独立进程恢复草稿、设备移除。');
} finally {
  bridge.kill(); for (const res of subscribers) res.end(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
  await rm(directory, { recursive: true, force: true });
}
