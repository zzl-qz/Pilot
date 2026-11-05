import assert from 'node:assert/strict';
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { randomBytes, randomUUID } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const exec = promisify(execFile);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const directory = await mkdtemp(path.join(tmpdir(), 'pilot-mobile-backend-'));
const data = path.join(directory, 'data');
const workspace = path.join(directory, 'workspace'); await mkdir(workspace);
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const token = randomUUID();
const libraries = process.env.PILOT_TEST_MOBILE_LIBRARIES || path.join(root, 'bridge/build/install/bridge/lib');
const serverJar = process.env.PILOT_TEST_SERVER_JAR || path.resolve(root, '../pilot-server/target/pilot-server-0.0.1-SNAPSHOT.jar');
const classpath = [path.join(root, 'bridge/build/classes/kotlin/test'), path.join(libraries, '*')].join(path.delimiter);
const probe = path.resolve(root, '../pilot-server/src/test/resources/mobile/BridgeProcessProbe.java');
const recovery = process.argv.includes('--recovery');
const phoneKey = randomBytes(32).toString('base64');
const requestId = randomUUID();
let server;
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

async function start(mobileLibraries = libraries) {
  server = spawn(java, ['--enable-native-access=ALL-UNNAMED', '-jar', serverJar, '--server.address=127.0.0.1', '--server.port=0', `--pilot.data.dir=${data}`, `--pilot.agent.workspace=${workspace}`, `--pilot.mobile.libraries=${mobileLibraries}`, '--pilot.runtime.provision=false', '--pilot.web-search.local=false', '--pilot.model.api-key=', '--pilot.model.endpoint=http://127.0.0.1:9/v1/chat/completions', '--pilot.security.mode=token', `--pilot.security.token=${token}`], { cwd: directory, stdio: ['ignore', 'pipe', 'pipe'] });
  return new Promise((resolve, reject) => {
    let output = '';
    const timer = setTimeout(() => reject(new Error('真实服务启动超时，请先构建服务端 Jar 并使用 Java 25')), 30000);
    const capture = chunk => {
      output = (output + chunk).slice(-262144);
      const match = output.match(/Tomcat started on port (\d+)/);
      if (match) { clearTimeout(timer); resolve(`http://127.0.0.1:${match[1]}`); }
    };
    server.stdout.on('data', capture); server.stderr.on('data', capture);
    server.once('error', () => { clearTimeout(timer); reject(new Error('无法启动隔离服务，请检查 Java 和 Jar')); });
    server.once('exit', code => { clearTimeout(timer); reject(new Error(`真实服务提前退出 ${code}`)); });
  });
}
async function stop(force = false) {
  if (!server || server.exitCode !== null) return;
  const target = server;
  target.kill(force ? 'SIGKILL' : 'SIGTERM');
  await new Promise(resolve => {
    const timer = setTimeout(() => { target.kill('SIGKILL'); resolve(); }, 10000);
    target.once('exit', () => { clearTimeout(timer); resolve(); });
  });
}
async function bridgeProcesses(operation = 'list') {
  const result = await exec(java, [probe, String(server.pid), path.join(data,'mobile'), operation], {timeout:10000});
  return result.stdout.trim().split(/\r?\n/).filter(Boolean);
}
async function waitBridge(previous = '') {
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    const pids = await bridgeProcesses();
    if (pids.length === 1 && pids[0] !== previous) return pids[0];
    await delay(1000);
  }
  throw new Error('没有打开电脑端连接页面时，连接组件未能自动恢复');
}
async function checkPhone(code, reconnect = false) {
  const result = await exec(java, ['--enable-native-access=ALL-UNNAMED', '-cp', classpath, 'com.pilot.mobile.bridge.BackendSmokeKt'], {
    env: {...process.env, PILOT_TEST_INVITE:code, PILOT_TEST_PHONE_KEY:phoneKey, PILOT_TEST_REQUEST_ID:requestId, PILOT_TEST_RECONNECT:String(reconnect)},
    timeout:90000, maxBuffer:1024 * 1024,
  });
  console.log(result.stdout.trim());
}
try {
  const incompleteLibraries = path.join(directory, '不完整的连接组件 with spaces');
  await mkdir(incompleteLibraries);
  let base = await start(incompleteLibraries);
  const failed = await fetch(`${base}/api/mobile/connection`, {headers:{'X-Pilot-Token':token}});
  assert.equal(failed.status, 503);
  assert.equal(failed.headers.get('cache-control'), 'no-store');
  const failure = await failed.json();
  assert.equal(failure.code, 'MOBILE_CONNECTION_UNAVAILABLE');
  assert.match(failure.message, /node scripts\/mobile\.mjs build-bridge/);
  assert.ok(!JSON.stringify(failure).includes(token) && !JSON.stringify(failure).includes(incompleteLibraries), '启动错误不能泄露令牌或本机路径');
  await stop();
  console.log('PASS: 真实服务启动连接桥失败时返回明确修复说明，独立于 Spring 默认错误页，未泄露凭证或路径');
  base = await start();
  const created = await fetch(`${base}/api/projects`, { method:'POST', headers:{'X-Pilot-Token':token,'Content-Type':'application/json'},body:JSON.stringify({name:'移动契约验收',folders:[workspace]}) });
  if (!created.ok) throw new Error(`测试项目创建失败 ${created.status}`);
  const before = await fetch(`${base}/api/mobile/connection`, {headers:{'X-Pilot-Token':token}});
  if (!before.ok || !(await before.json()).running) throw new Error('后台托管连接桥启动失败');
  const response = await fetch(`${base}/api/mobile/connection/pair`, {method:'POST',headers:{'X-Pilot-Token':token,'Content-Type':'application/json'},body:'{"reuse":true}'});
  if (!response.ok) throw new Error('工作台配对码生成失败');
  const {code} = await response.json();
  await checkPhone(code);
  if (recovery) {
    const oldPid = (await bridgeProcesses('stop')).at(0);
    assert.ok(oldPid, '必须只终止该隔离服务的连接桥');
    await waitBridge(oldPid);
    // 不请求 /api/mobile/connection，证明无需电脑端打开窗口触发恢复。
    await checkPhone(code, true);
    const child = (await bridgeProcesses()).at(0);
    await stop(true);
    const deadline = Date.now() + 10000;
    let orphan = true;
    while (orphan && Date.now() < deadline) {
      const result = await exec(java,[probe,child,path.join(data,'mobile'),'alive'],{timeout:10000});
      orphan = result.stdout.trim() === 'true';
      if (orphan) await delay(500);
    }
    assert.equal(orphan,false,'强制退出后台后不能遗留连接桥');
    base = await start();
    await waitBridge();
    await checkPhone(code, true);
    console.log('PASS: 连接桥异常退出自动恢复、后台重启自动恢复、原配对和任务及幂等回执保留；模型调用为 0');
  }
} finally { await stop(); await rm(directory,{recursive:true,force:true}); }
