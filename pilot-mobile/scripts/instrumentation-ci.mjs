// 专用模拟器入口：真实连接桥 + 隔离业务夹具，不依赖公共中继或模型服务。
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, writeFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { redactPairingCodes, resolvePilotAdb } from './smoke-helpers.mjs';

const exec = promisify(execFile);
const root = fileURLToPath(new URL('..', import.meta.url));
const serial = process.argv[2];
const { adb } = await resolvePilotAdb(serial);
const directory = path.join(root, 'build', 'instrumentation-ci');
await rm(directory, { recursive: true, force: true });
await mkdir(directory, { recursive: true });
const xml = text => text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
const results = [];
let active;
const interrupted = () => { active?.kill('SIGTERM'); process.exitCode = 1; };
process.on('SIGTERM', interrupted); process.on('SIGINT', interrupted);
async function run(script, timeout) {
  const started = Date.now();
  let output = '';
  const result = await new Promise(resolve => {
    active = spawn(process.execPath, [path.join(root, 'scripts', script), serial, ...(script === 'android-smoke.mjs' && process.argv.includes('--network-switch') ? ['--network-switch'] : [])], { env: process.env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    const timer = setTimeout(() => { active?.kill('SIGTERM'); }, timeout);
    const consume = chunk => { output += chunk; process.stdout.write(redactPairingCodes(String(chunk))); };
    active.stdout.on('data', consume); active.stderr.on('data', consume);
    active.once('error', error => { output += error.message; clearTimeout(timer); resolve(false); });
    active.once('close', code => { clearTimeout(timer); resolve(code === 0); });
  });
  active = null;
  await writeFile(path.join(directory, script + '.log'), redactPairingCodes(output));
  results.push({ name: script, seconds: (Date.now() - started) / 1000, success: result, output: redactPairingCodes(output).slice(-12000) });
  if (!result) throw new Error(script + ' 验收失败，查看 instrumentation-ci 报告');
}
try {
  await exec(adb, ['-s', serial, 'logcat', '-c']);
  await run('android-smoke.mjs', 12 * 60_000);
  await run('scanner-smoke.mjs', 6 * 60_000);
} catch (error) {
  console.error(error.message); process.exitCode = 1;
} finally {
  // 失败也保存证据；仅收集该模拟器，不上传宿主配置、配对码或用户设备数据。
  for (const [name, args, binary] of [
    ['logcat.txt', ['logcat', '-d', '-t', '4000'], false],
    ['screen.png', ['exec-out', 'screencap', '-p'], true],
  ]) {
    try {
      const result = await exec(adb, ['-s', serial, ...args], { encoding: binary ? 'buffer' : 'utf8', timeout: 15000, maxBuffer: 12 * 1024 * 1024 });
      await writeFile(path.join(directory, name), binary ? result.stdout : redactPairingCodes(result.stdout));
    } catch { console.error('未能保存 ' + name); }
  }
  const failures = results.filter(r => !r.success).length;
  await writeFile(path.join(directory, 'results.xml'), `<?xml version="1.0" encoding="UTF-8"?><testsuite name="Pilot Android instrumentation" tests="${results.length}" failures="${failures}">${results.map(r => `<testcase name="${xml(r.name)}" time="${r.seconds}">${r.success ? '' : `<failure message="场景失败">${xml(r.output)}</failure>`}</testcase>`).join('')}</testsuite>`);
  await writeFile(path.join(directory, 'summary.json'), JSON.stringify(results.map(({ output, ...r }) => r), null, 2));
  await exec(adb, ['-s', serial, 'shell', 'am', 'force-stop', 'com.pilot.mobile.qa']).catch(() => {});
}
