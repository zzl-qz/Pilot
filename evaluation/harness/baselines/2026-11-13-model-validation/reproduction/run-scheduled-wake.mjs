import { readFile, writeFile, mkdir, mkdtemp } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';
const state = JSON.parse(await readFile(process.argv[2], 'utf8'));
const server = state[process.argv[3]], root = state.root;
if (!server || !/^http:\/\/127\.0\.0\.1:\d+$/.test(server.base)) throw new Error('Require an explicitly owned loopback evaluator');
const out = join(root, 'runs/scheduled-wakeup'); await mkdir(out, { recursive: true });
const { verifyCandidate } = await import(pathToFileURL(join(root, 'evaluation/harness/runner.mjs')));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function api(path, body) {
  const response = await fetch(server.base + path, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) });
  if (!response.ok) throw new Error(`Evaluator API ${response.status}: ${path}`);
  return response.json();
}
const scriptPrelude = "import { createRequire } from 'node:module';\nimport { fileURLToPath } from 'node:url';\nimport { dirname } from 'node:path';\nconst require = createRequire(import.meta.url);\nconst __dirname = dirname(fileURLToPath(import.meta.url));\n";
const prompt = `这是一个受控的等待与自动唤醒验证，请实际完成：先运行 node scripts/status.mjs。若 ready 为 false，先把返回的 requestId 写入 src/checkpoint.json（形如 {"requestId":"..."}），然后使用 wait_until 工具，run_at 使用 status 返回的 wakeAt，reason 说明等待外部确认；调用后停止本轮，不要 sleep、轮询或自行创建 data/ready.json。若工具提示时间太早，重新运行 status 获取新的 wakeAt。调度器自动唤醒后，再运行 status；ready 为 true 时保留原 checkpoint 不改动，把 requestId 和 acknowledgement 写入 src/result.json，执行 node --test，确认通过后完成目标。所有数据、脚本和测试都是只读输入，不要改写；外部确认由评测器在观察到真实等待后提供。`;
const script = `const fs = require('node:fs');\nconst path = require('node:path');\nconst root = path.join(__dirname, '..');\nconst request = JSON.parse(fs.readFileSync(path.join(root, 'data/request.json')));\nlet ready = null;\ntry { ready = JSON.parse(fs.readFileSync(path.join(root, 'data/ready.json'))); } catch (error) { if (error.code !== 'ENOENT') throw error; }\nconsole.log(JSON.stringify({ requestId: request.requestId, ready: Boolean(ready), acknowledgement: ready?.acknowledgement ?? null, wakeAt: new Date(Date.now() + 180000).toISOString() }));\n`;
const test = `const test = require('node:test');\nconst assert = require('node:assert/strict');\nconst fs = require('node:fs');\nconst path = require('node:path');\nconst root = path.join(__dirname, '..');\nconst read = name => JSON.parse(fs.readFileSync(path.join(root, name)));\ntest('外部确认与已保存进度共同进入最终结果', () => {\n const request = read('data/request.json'); const ready = read('data/ready.json');\n assert.deepEqual(read('src/checkpoint.json'), { requestId: request.requestId });\n assert.deepEqual(read('src/result.json'), { requestId: request.requestId, acknowledgement: ready.acknowledgement });\n});\n`;
await writeFile(join(out, 'protocol.json'), JSON.stringify({ sourceCommit: state.sourceCommit, jarSha256: state.jarSha256, model: server.model, repeat: 3, concurrency: 3, deadlineMinutes: 20, wakeDelaySeconds: 180, prompt, scriptSha256: hash(scriptPrelude + script), testSha256: hash(test), acceptance: '真实 WAITING 和两个不同 runId；按预约时间唤醒；checkpoint 未改；独立原始测试通过；计划 COMPLETED' }, null, 2), { flag: 'wx' });
async function trial(attempt) {
 const dir = join(out, String(attempt)); await mkdir(dir);
 const report = { attempt, model: server.model, sourceCommit: state.sourceCommit, success: false, observations: [] };
 const save = () => writeFile(join(dir, 'report.json'), JSON.stringify(report, null, 2));
 let job;
 try {
  await mkdir(join(root, '.pilot-eval'), { recursive: true });
  const workspace = await mkdtemp(join(root, '.pilot-eval/wakeup-'));
  for (const d of ['src','data','scripts','test']) await mkdir(join(workspace, d));
  const requestId = randomUUID(); const initialRequest = JSON.stringify({ requestId }) + '\n';
  await writeFile(join(workspace, 'data/request.json'), initialRequest);
  // 使用可移植的 CommonJS 入口，避免 file URL 在空格或 Windows 路径上的解码差异。
  await writeFile(join(workspace, 'scripts/status.mjs'), scriptPrelude + script);
  const originalScript = await readFile(join(workspace, 'scripts/status.mjs'));
  await writeFile(join(workspace, 'test/wakeup.test.cjs'), test);
  const source = await api('/api/sessions', { title: `eval:scheduled-wakeup-source:${attempt}`, workspace });
  job = await api('/api/schedules', { name: `eval:scheduled-wakeup:${attempt}`, sourceSessionId: source.sessionId ?? source.id, content: prompt, runAt: new Date(Date.now() + 3000).toISOString(), timezone: 'UTC', fullAccess: true, isolated: false, requirements: { constraints: ['保留 checkpoint，禁止改写 data、scripts 和 test'], acceptance: [{ text: '自动唤醒后写出包含真实外部确认的 src/result.json，并通过 node --test', checked: false, evidence: '' }] } });
  report.scheduleId = job.id; report.sessionId = job.sessionId; report.workspace = workspace; await save();
  const start = Date.now(); let released = false, checkpointHash;
  while (Date.now() - start < 20 * 60 * 1000) {
   const jobs = await api('/api/schedules'); job = jobs.find(value => value.id === job.id);
   if (!job) throw new Error('Owned schedule disappeared');
   const last = report.observations.at(-1);
   if (!last || last.state !== job.state || last.runId !== job.runId || last.runAt !== job.runAt) { report.observations.push({ observedAt: new Date().toISOString(), state: job.state, runId: job.runId, runAt: job.runAt, attempts: job.attempts }); await save(); }
   if (!released && job.state === 'WAITING') {
    const checkpoint = await readFile(join(workspace, 'src/checkpoint.json'));
    if (JSON.parse(checkpoint).requestId !== requestId) throw new Error('Waiting without a valid checkpoint');
    checkpointHash = hash(checkpoint); report.waitingObserved = true; report.scheduledWakeAt = job.runAt; report.firstRunId = job.runId;
    const acknowledgement = randomUUID();
    report.confirmationSha256 = hash(JSON.stringify({ acknowledgement }) + '\n');
    await writeFile(join(workspace, 'data/ready.json'), JSON.stringify({ acknowledgement }) + '\n', { flag: 'wx' });
    report.confirmationReleasedAt = new Date().toISOString(); released = true; await save();
   }
   if (['COMPLETED','CANCELLED','NEEDS_ATTENTION','PAUSED'].includes(job.state)) break;
   await pause(2000);
  }
  report.finalSchedule = job;
  const events=[]; let after=0;
  for (let page=0;page<100;page++) { const data=await api(`/api/sessions/${job.sessionId}/history?after=${after}&limit=500`);events.push(...data.events);if(!data.hasMore)break;after=Math.max(...data.events.map(e=>e.sequence));if(page===99)throw new Error('History exceeds evidence bound'); }
  await writeFile(join(dir, 'events.jsonl'), events.map(e=>JSON.stringify(e)).join('\n')+'\n');
  report.checkpointPreserved = released && hash(await readFile(join(workspace, 'src/checkpoint.json'))) === checkpointHash;
  report.confirmationPreserved = released && hash(await readFile(join(workspace, 'data/ready.json'))) === report.confirmationSha256;
  report.artifacts = {};
  for (const name of ['data/request.json','data/ready.json','src/checkpoint.json','src/result.json']) {
   try { report.artifacts[name] = JSON.parse(await readFile(join(workspace,name),'utf8')); } catch { report.artifacts[name] = null; }
  }
  report.inputsPreserved = (await readFile(join(workspace, 'data/request.json'),'utf8')) === initialRequest && hash(await readFile(join(workspace, 'scripts/status.mjs'))) === hash(originalScript) && (await readFile(join(workspace, 'test/wakeup.test.cjs'),'utf8')) === test;
  report.waitToolObserved = events.some(e=>e.type==='tool.started' && e.payload.name==='wait_until');
  report.waitingStopObserved = events.some(e=>e.type==='run.failed' && e.payload.code==='WAITING');
  const restarted = events.find(e=>e.type==='run.started' && report.firstRunId && e.runId!==report.firstRunId);
  report.newRunAfterWake = Boolean(restarted && Date.parse(restarted.timestamp) >= Date.parse(report.scheduledWakeAt));
  report.acceptance = await verifyCandidate({ acceptance: ['node --test'] }, workspace, [['wakeup.test.cjs', Buffer.from(test)]]);
  report.success = job.state==='COMPLETED' && report.waitingObserved && report.waitToolObserved && report.waitingStopObserved && report.newRunAfterWake && report.checkpointPreserved && report.inputsPreserved && report.confirmationPreserved && report.acceptance.ok;
  report.seconds = (Date.now()-start)/1000;
  if (!['COMPLETED','CANCELLED','NEEDS_ATTENTION','PAUSED'].includes(job.state)) { await api(`/api/schedules/${job.id}/control`,{action:'cancel'}); report.cancelledAtDeadline=true; }
 } catch(error) {
  report.error=String(error);
  if(job && !['COMPLETED','CANCELLED','NEEDS_ATTENTION'].includes(job.state)) { try { await api(`/api/schedules/${job.id}/control`,{action:'cancel'});report.cancellationRequested=true; } catch {report.cancellationUnconfirmed=true;} }
 }
 await save(); console.log(JSON.stringify({attempt,success:report.success,error:report.error})); return report;
}
const results=await Promise.all([1,2,3].map(trial));
await writeFile(join(out,'completion.json'),JSON.stringify({recorded:results.length,passed:results.filter(r=>r.success).length},null,2));
