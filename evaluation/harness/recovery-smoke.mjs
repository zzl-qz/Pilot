import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:http';
import { closeSync, openSync } from 'node:fs';
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

// Deterministic fault injection, not a model-capability benchmark. Owns only its child JVM and temp data.
const jar = resolve(process.argv[2] ?? 'pilot-server/target/pilot-server-0.0.1-SNAPSHOT.jar');
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const root = await mkdtemp(join(tmpdir(), 'pilot-recovery-smoke-'));
const workspace = join(root, 'workspace with spaces');
await mkdir(workspace);
const held = new Set();
const calls = [];
let recovered = false;
let backend;
let launches = 0;
let base;

async function poll(check, label, timeout = 30000) {
  const until = Date.now() + timeout;
  while (Date.now() < until) {
    const value = await check();
    if (value) return value;
    await delay(100);
  }
  throw new Error(`Timeout: ${label}; artifacts: ${root}`);
}
function send(res, payload) {
  res.writeHead(200, { 'Content-Type': 'text/event-stream' });
  res.end(`data: ${JSON.stringify(payload)}\n\ndata: [DONE]\n\n`);
}
const model = createServer(async (req, res) => {
  try {
    if (req.method !== 'POST') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ data: [{ id: 'recovery-fixture' }] }));
      return;
    }
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks));
    if (!JSON.stringify(body.messages ?? []).includes('RECOVERY-')) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ choices: [{ message: { content: 'ok' }, finish_reason: 'stop' }] }));
      return;
    }
    const marker = JSON.stringify(body.messages).includes('RECOVERY-B') ? 'B' : 'A';
    const toolCalls = body.messages.flatMap(message => message.tool_calls ?? []);
    const before = toolCalls.find(call => call.id === `before-${marker}`);
    const after = toolCalls.find(call => call.id === `after-${marker}`);
    calls.push({ marker, recovered, before: !!before, after: !!after });
    if (before && !recovered) {
      held.add(marker);
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      res.write(': waiting for injected process termination\n\n');
      return;
    }
    if (recovered) {
      assert(before, 'Resumed model request lost the completed tool call');
      assert(body.messages.some(message => message.role === 'tool' && message.tool_call_id === before.id), 'Resumed request lost the completed tool result');
    }
    if (!before || !after) {
      const phase = before ? 'after' : 'before';
      send(res, { choices: [{ delta: { tool_calls: [{ index: 0, id: `${phase}-${marker}`, type: 'function', function: {
        name: 'create_file', arguments: JSON.stringify({ path: `${phase}-${marker}.txt`, content: `${phase}\n` })
      } }] }, finish_reason: 'tool_calls' }] });
    } else {
      send(res, { choices: [{ delta: { content: '已完成两个阶段，文件已保存。' }, finish_reason: 'stop' }] });
    }
  } catch (error) {
    res.destroy(error);
    console.error(error);
  }
});
await new Promise(resolve => model.listen(0, '127.0.0.1', resolve));
const modelEndpoint = `http://127.0.0.1:${model.address().port}`;
const portProbe = createServer();
await new Promise(resolve => portProbe.listen(0, '127.0.0.1', resolve));
const port = portProbe.address().port;
await new Promise(resolve => portProbe.close(resolve));
base = `http://127.0.0.1:${port}`;

async function api(path, body) {
  const response = await fetch(base + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000)
  });
  if (!response.ok) throw new Error(`${path}: ${response.status} ${(await response.text()).slice(0, 500)}`);
  return response.json();
}
async function replayUntil(id, after, through) {
  const abort = new AbortController();
  const sequences = [];
  let reader;
  try {
    const response = await fetch(`${base}/api/sessions/${id}/events?after=${after}&revision=0&protocol=2`, {
      signal: AbortSignal.any([abort.signal, AbortSignal.timeout(5000)])
    });
    assert.equal(response.status, 200);
    reader = response.body.getReader();
    const decoder = new TextDecoder();
    let pending = '';
    while (true) {
      const part = await reader.read();
      assert(!part.done, 'Event replay ended before the expected cursor');
      pending += decoder.decode(part.value, { stream: true });
      let end;
      while ((end = pending.indexOf('\n\n')) >= 0) {
        const frame = pending.slice(0, end);
        pending = pending.slice(end + 2);
        const data = frame.split('\n').find(line => line.startsWith('data:'));
        if (!data) continue;
        const event = JSON.parse(data.slice(5));
        assert.notEqual(event.type, 'stream.error');
        assert.notEqual(event.type, 'stream.reset');
        const sequence = event.type === 'stream.event' ? event.payload.sequence : event.sequence;
        if (sequence > after) {
          sequences.push(sequence);
          if (sequence === through) return sequences;
        }
      }
    }
  } finally {
    await reader?.cancel().catch(() => {});
    abort.abort();
  }
}
async function stop(signal) {
  if (!backend || backend.exitCode !== null || backend.signalCode !== null) return;
  const child = backend;
  const exited = once(child, 'exit');
  const timer = new AbortController();
  child.kill(signal);
  try {
    // Spring's normal graceful shutdown can wait 30 seconds for recently disconnected SSE requests.
    await Promise.race([exited, delay(45000, null, { signal: timer.signal }).then(() => { throw new Error('Owned backend failed to stop'); })]);
  } finally {
    timer.abort();
  }
}
async function start() {
  const log = openSync(join(root, `server-${++launches}.log`), 'w');
  backend = spawn(java, ['--enable-native-access=ALL-UNNAMED', '-jar', jar,
    '--server.address=127.0.0.1', `--server.port=${port}`, '--spring.profiles.active=evaluation',
    '--pilot.desktop.launch=false', '--pilot.desktop.provision=false', '--pilot.mobile.mode=direct',
    '--pilot.runtime.provision=false', '--pilot.web-search.local=false',
    '--pilot.browser.enabled=false', '--pilot.multi.enabled=false', '--pilot.skills.enabled=false',
    '--pilot.agent.completion-checks=false', '--pilot.agent.max-steps=10'], {
    cwd: root, stdio: ['ignore', log, log], env: { ...process.env,
      PILOT_MODEL_ENDPOINT: modelEndpoint, PILOT_MODEL_MODEL: 'recovery-fixture', PILOT_MODEL_API_KEY: 'synthetic-key',
      PILOT_WORKSPACE: workspace, PILOT_DATA_DIR: join(root, 'data'), PILOT_PERMISSION_MODE: 'AUTO'
    }
  });
  closeSync(log);
  await poll(async () => {
    if (backend.exitCode !== null || backend.signalCode !== null) throw new Error(`Backend exited; logs: ${root}`);
    try { return (await fetch(base + '/api/settings', { signal: AbortSignal.timeout(1000) })).ok; } catch { return false; }
  }, 'backend startup');
}

const sessions = [];
try {
  await start();
  for (const marker of ['A', 'B']) {
    const session = await api('/api/sessions', { title: `RECOVERY-${marker}`, workspace });
    const id = session.sessionId ?? session.id;
    sessions.push({ marker, id });
    await api(`/api/sessions/${id}/messages`, { content: `RECOVERY-${marker}：创建 before-${marker}.txt，再创建 after-${marker}.txt。` });
  }
  await poll(() => held.size === 2, 'two sessions waiting concurrently after their first persisted tool');
  for (const { marker } of sessions) assert.equal(await readFile(join(workspace, `before-${marker}.txt`), 'utf8'), 'before\n');
  await stop('SIGKILL');
  recovered = true;
  await start();
  for (const { id } of sessions) {
    const diagnostic = await api(`/api/sessions/${id}/diagnostics`);
    assert.notEqual(diagnostic.sessionStatus, 'RUNNING', 'Startup must not leave a dead run active');
  }
  for (const { id, marker } of sessions) {
    await api(`/api/sessions/${id}/continue`, {});
    const result = await poll(async () => {
      const d = await api(`/api/sessions/${id}/diagnostics`);
      if (['FAILED', 'CANCELLED'].includes(d.sessionStatus)) throw new Error(JSON.stringify(d));
      return d.sessionStatus === 'COMPLETED' && d.phase === 'FINISHED' ? d : null;
    }, `resume ${marker}`);
    assert.equal(result.retryAttempt, null);
    assert.equal(await readFile(join(workspace, `after-${marker}.txt`), 'utf8'), 'after\n');
    const history = await api(`/api/sessions/${id}/history?after=0&limit=500`);
    const events = history.events ?? history;
    assert.equal(events.filter(event => event.type === 'tool.started' && event.payload.callId === `before-${marker}`).length, 1, 'Already completed side effect must not execute again');
    assert.equal(new Set(events.map(event => event.sequence)).size, events.length, 'History cursor remains unique after restart');
    const split = events[Math.floor(events.length / 2)].sequence;
    const first = await replayUntil(id, 0, split);
    const rest = await replayUntil(id, split, events.at(-1).sequence);
    assert.deepEqual([...first, ...rest], events.map(event => event.sequence), 'Disconnect and cursor replay must neither omit nor duplicate history');
    if (marker === 'A') assert.notEqual((await api(`/api/sessions/${sessions[1].id}/diagnostics`)).sessionStatus, 'RUNNING', 'Resuming A must not start B');
  }
  const result = { passed: true, platform: process.platform, arch: process.arch, launches, sessions: sessions.length,
    assertions: ['two concurrently active sessions', 'hard process termination', 'durable tool results included on resume',
      'no repeated completed tool side effects', 'independent session resume', 'complete terminal diagnostics',
      'unique persisted event cursors', 'SSE disconnect and cursor replay without missing or duplicate events'],
    model: 'deterministic local HTTP fixture; not a real-model score', completionChecks: false, requests: calls };
  await writeFile(join(root, 'report.json'), JSON.stringify(result, null, 2));
  console.log(`Recovery smoke passed: ${join(root, 'report.json')}`);
} finally {
  await stop('SIGTERM').catch(async error => { console.error(error); await stop('SIGKILL'); });
  model.closeAllConnections();
  await new Promise(resolve => model.close(resolve));
}
