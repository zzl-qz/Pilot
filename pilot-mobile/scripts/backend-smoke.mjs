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
const direct = process.argv.includes('--direct');
const phoneKey = randomBytes(32).toString('base64');
const requestFile = path.join(directory, 'request-id.txt');
let server;
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

async function start(mobileLibraries = libraries, relay = '', mode = direct ? 'direct' : 'auto') {
  server = spawn(java, ['--enable-native-access=ALL-UNNAMED', '-jar', serverJar, '--server.address=127.0.0.1', '--server.port=0', `--pilot.data.dir=${data}`, `--pilot.agent.workspace=${workspace}`, `--pilot.mobile.libraries=${mobileLibraries}`, `--pilot.mobile.relay=${relay}`, `--pilot.mobile.mode=${mode}`, '--pilot.runtime.provision=false', '--pilot.web-search.local=false', '--pilot.model.api-key=', '--pilot.model.endpoint=http://127.0.0.1:9/v1/chat/completions', '--pilot.security.mode=token', `--pilot.security.token=${token}`], { cwd: directory, stdio: ['ignore', 'pipe', 'pipe'] });
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
    env: {...process.env, PILOT_TEST_INVITE:code, PILOT_TEST_PHONE_KEY:phoneKey, PILOT_TEST_REQUEST_FILE:requestFile, PILOT_TEST_RECONNECT:String(reconnect), PILOT_TEST_DIRECT_ROUTE_REFRESH:String(direct)},
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
  let invite = await response.json();
  assert.equal(invite.connectionMode, direct ? 'direct-only' : 'automatic', '使用本轮指定的连接模式');
  // 出码已改为本地即时操作；跨网/重启验收须使用后台就绪后自动刷新的路由，模拟界面提示的扫码时点。
  if (direct) {
    assert.equal(invite.networkState, 'local');
    assert.equal(invite.development, false);
  } else {
    const expiresAt = invite.expiresAt, networkDeadline = Date.now()+35000;
    let network;
    do {
      network = await (await fetch(base+'/api/mobile/connection',{headers:{'X-Pilot-Token':token}})).json();
      if (network.networkState === 'ready') break;
      await delay(500);
    } while (Date.now()<networkDeadline);
    assert.equal(network.networkState,'ready','公共服务未完成初始化，不能把本地连接成功当作跨网恢复');
    assert.equal(network.invite.expiresAt,expiresAt,'路由就绪不能延长配对授权');
    invite = network.invite;
  }
  const {code} = invite;
  await checkPhone(code);
  async function reconnectCode() {
    if (!direct) return code;
    // 纯直连测试不使用公共发现；新进程的 UDP 端口变化，刷新路由但保留原设备身份与授权。
    const deadline = Date.now()+15000;
    while (Date.now()<deadline) {
      const response = await fetch(base+'/api/mobile/connection/pair',{method:'POST',headers:{'X-Pilot-Token':token,'Content-Type':'application/json'},body:'{}'});
      if (response.ok) {
        const current = await response.json();
        assert.equal(current.connectionMode,'direct-only');
        return current.code;
      }
      await delay(200);
    }
    throw new Error('监督进程恢复后无法取得当前本地路由');
  }
  if (recovery) {
    const oldPid = (await bridgeProcesses('stop')).at(0);
    assert.ok(oldPid, '必须只终止该隔离服务的连接桥');
    const recovered = await waitBridge(oldPid);
    // 先确认监督进程已自动恢复，再读取纯直连的当前路由；自动模式始终沿用旧票据。
    await checkPhone(await reconnectCode(), true);
    assert.deepEqual(await bridgeProcesses(),[recovered],'读取路由不能替代监督进程恢复或另起连接桥');
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
    await checkPhone(await reconnectCode(), true);
    console.log('PASS: 连接桥异常退出自动恢复、后台重启自动恢复、原配对和任务及幂等回执保留；模型调用为 0');
  }
  if (!recovery) {
    await stop();
    base = await start(libraries, 'https://127.0.0.1:9');
    const status = await (await fetch(base+'/api/mobile/connection', {headers:{'X-Pilot-Token':token}})).json();
    assert.equal(status.connectionMode,'relay-enabled','只有明确配置后端中继才进入中继模式');
    const started = Date.now();
    const response = await fetch(base+'/api/mobile/connection/pair',{method:'POST',headers:{'X-Pilot-Token':token,'Content-Type':'application/json'},body:'{}'});
    assert.equal(response.status,200);
    const configured = await response.json();
    assert.ok(configured.code && configured.image && Date.now()-started < 3000,'自建中继不可用也应在本地快速出码');
    const deadline = Date.now()+25000;
    let current;
    do {
      await delay(500);
      current = await (await fetch(base+'/api/mobile/connection',{headers:{'X-Pilot-Token':token}})).json();
    } while (current.networkState !== 'unavailable' && Date.now()<deadline);
    assert.equal(current.networkState,'unavailable');
    assert.equal(current.invite.code,configured.code,'网络准备失败不能销毁或刷新授权');
    console.log('PASS: 自建中继故障只影响后台就绪状态，本地仍快速出码；不回退默认公共中继');
    await stop();
    base = await start(libraries, '', 'direct');
    const direct = await (await fetch(base+'/api/mobile/connection/pair',{method:'POST',headers:{'X-Pilot-Token':token,'Content-Type':'application/json'},body:'{}'})).json();
    assert.equal(direct.connectionMode,'direct-only');
    assert.equal(direct.networkState,'local');
    assert.equal(direct.development,false);
    console.log('PASS: 后端显式 direct 配置仍完全关闭公共发现与中继');
  }
} finally { await stop(); await rm(directory,{recursive:true,force:true,maxRetries:5,retryDelay:200}); }
