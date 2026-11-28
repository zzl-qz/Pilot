import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';

const exec = promisify(execFile);

export const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

export function resolveJava() {
  return process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    : 'java';
}

export function redactPairingCodes(text) {
  return String(text).replace(/pilot:\/\/pair\/[A-Za-z0-9_-]+/g, '[配对码已隐藏]');
}

export async function resolvePilotAdb(serial, { timeout = 120000, maxBuffer = 2 * 1024 * 1024 } = {}) {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk) throw new Error('请设置 ANDROID_HOME');
  if (!/^emulator-\d+$/.test(serial || '')) throw new Error('只接受 Pilot 专用模拟器编号（例如 emulator-5580）');
  const adb = path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  const runAdb = async (...args) => {
    try { return await exec(adb, ['-s', serial, ...args], { timeout, maxBuffer }); }
    catch (error) {
      // execFile 的原始异常包含整条命令；配对参数不能随着设备故障写进日志。
      const details = redactPairingCodes(`${error.stdout || ''}\n${error.stderr || ''}`).slice(-4000);
      throw new Error(`设备 ${serial} 的 ${args.slice(0, 3).join(' ')} 操作中断（${error.code ?? 'unknown'}），本轮不计通过。\n${details}`);
    }
  };
  if (!/^pilot[-_]/i.test((await runAdb('emu', 'avd', 'name')).stdout)) {
    throw new Error('不能操作真机或其他项目的模拟器');
  }
  return { adb, runAdb };
}

export function dockerJavaProbeArgs({
  network = 'bridge',
  interactive = false,
  name,
  mounts = [],
  dockerArgs = [],
  jvmArgs = [],
  classpath = '/probe/*',
  entryArgs = [],
} = {}) {
  return [
    'run',
    ...(interactive ? ['-i'] : []),
    '--rm', '--pull', 'never',
    ...(name ? ['--name', name] : []),
    '--read-only', '--tmpfs', '/tmp:rw,exec,nosuid,size=512m',
    '--network', network,
    ...mounts.flatMap(mount => ['-v', mount]),
    ...dockerArgs,
    '--entrypoint', 'java',
    process.env.PILOT_PROBE_JAVA_IMAGE || 'maven:3.9-eclipse-temurin-25',
    ...jvmArgs, '--enable-native-access=ALL-UNNAMED', '-cp', classpath, ...entryArgs,
  ];
}

export async function waitFor(read, predicate, { timeoutMs = 20000, intervalMs = 200, onTimeout } = {}) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (true) {
    last = await read();
    if (predicate(last)) return last;
    if (Date.now() >= deadline) {
      if (onTimeout) throw new Error(typeof onTimeout === 'function' ? onTimeout(last) : onTimeout);
      return last;
    }
    await delay(intervalMs);
  }
}

export async function startIsolatedServer({
  java = resolveJava(),
  serverJar,
  cwd,
  dataDir,
  workspace,
  mobileLibraries,
  token,
  extraArgs = [],
  env = process.env,
  timeoutMs = 30000,
}) {
  const args = [
    '--enable-native-access=ALL-UNNAMED', '-jar', serverJar,
    '--server.address=127.0.0.1', '--server.port=0',
    `--pilot.data.dir=${dataDir}`,
    `--pilot.agent.workspace=${workspace}`,
    `--pilot.mobile.libraries=${mobileLibraries}`,
    '--pilot.runtime.provision=false',
    '--pilot.web-search.local=false',
    '--pilot.model.api-key=',
    '--pilot.model.endpoint=http://127.0.0.1:9/v1/chat/completions',
    '--pilot.security.mode=token',
    `--pilot.security.token=${token}`,
    ...extraArgs,
  ];
  const child = spawn(java, args, { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] });
  const stop = async (force = false) => {
    if (child.exitCode !== null || child.signalCode !== null) return;
    child.kill(force ? 'SIGKILL' : 'SIGTERM');
    await new Promise(resolve => {
      const timer = setTimeout(() => { child.kill('SIGKILL'); resolve(); }, 10000);
      child.once('exit', () => { clearTimeout(timer); resolve(); });
    });
  };
  try {
    const baseUrl = await new Promise((resolve, reject) => {
      let output = '';
      const timer = setTimeout(() => reject(new Error('隔离服务启动超时，请先构建服务端 Jar 并使用 Java 25')), timeoutMs);
      const capture = chunk => {
        output = (output + chunk).slice(-262144);
        const match = output.match(/Tomcat started on port (\d+)/);
        if (match) { clearTimeout(timer); resolve(`http://127.0.0.1:${match[1]}`); }
      };
      child.stdout.on('data', capture);
      child.stderr.on('data', capture);
      child.once('error', () => { clearTimeout(timer); reject(new Error('无法启动隔离服务，请检查 Java 和 Jar')); });
      child.once('exit', code => { clearTimeout(timer); reject(new Error(`隔离服务提前退出 ${code}`)); });
    });
    return { baseUrl, pid: child.pid, process: child, stop };
  } catch (error) {
    await stop(true).catch(() => {});
    throw error;
  }
}
