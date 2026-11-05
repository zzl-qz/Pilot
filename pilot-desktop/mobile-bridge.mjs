import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { existsSync } from 'node:fs';
import { join } from 'node:path';

const exec = promisify(execFile);

export async function resolveMobileJava(options, run = exec) {
  const { java } = mobileBridgeOptions(options);
  const env = options.env || process.env;
  const platform = options.platform || process.platform;
  async function usable(command) {
    try {
      const result = await run(command, ['-version'], { env, timeout: 5000, windowsHide: true, maxBuffer: 16384 });
      return Number(`${result.stderr || ''}\n${result.stdout || ''}`.match(/version\s+"(\d+)/)?.[1]) >= 21;
    } catch { return false; }
  }
  for (const candidate of new Set([java, 'java'])) if (await usable(candidate)) return candidate;
  if (platform === 'darwin') {
    try {
      const result = await run('/usr/libexec/java_home', ['-v', '21+'], { env, timeout: 5000, maxBuffer: 4096 });
      const candidate = join(result.stdout.trim(), 'bin', 'java');
      if (await usable(candidate)) return candidate;
    } catch { /* 未安装合适的 JDK，交给统一错误提示。 */ }
  }
  throw new Error('手机连接需要 Java 21 或更新版本。请安装并配置 JAVA_HOME，然后重新打开连接窗口。');
}

export function mobileBridgeOptions({ pilotUrl, libraries, dataDir, env = process.env, platform = process.platform, arch = process.arch }) {
  const url = new URL(pilotUrl);
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) || url.username || url.password) {
    throw new Error('移动连接预览版需要连接本机 HTTP 服务。请在运行 Pilot 的电脑上开启移动连接。');
  }
  if (!((platform === 'darwin' && arch === 'arm64') || (platform === 'win32' && arch === 'x64') || (platform === 'linux' && ['arm64', 'x64'].includes(arch)))) {
    throw new Error('当前 iroh 发布包尚未覆盖此系统架构；支持 Apple Silicon、Windows x64、Linux x64/ARM64。');
  }
  const java = env.JAVA_HOME ? join(env.JAVA_HOME, 'bin', platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const args = ['--enable-native-access=ALL-UNNAMED', '-cp', join(libraries, '*'), 'com.pilot.mobile.bridge.MainKt', '--pilot-port', url.port || '80', '--data-dir', dataDir, '--no-open'];
  if (env.PILOT_MOBILE_MODE && !['auto','direct'].includes(env.PILOT_MOBILE_MODE)) throw new Error('PILOT_MOBILE_MODE 只能配置 auto 或 direct');
  if (env.PILOT_MOBILE_MODE === 'direct' && env.PILOT_MOBILE_RELAY?.trim()) throw new Error('direct 模式不能同时配置中继');
  if (env.PILOT_MOBILE_RELAY) args.push('--relay', env.PILOT_MOBILE_RELAY);
  if (env.PILOT_MOBILE_MODE === 'direct') args.push('--no-relay');
  return { java, args };
}

export function parseMobileAdmin(line) {
  const match = line.match(/^PILOT_MOBILE_ADMIN (\S+)$/m);
  if (!match) return null;
  const url = new URL(match[1]);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port || !/^#[A-Za-z0-9_-]{43}$/.test(url.hash)) throw new Error('移动连接返回了无效管理地址');
  return url.href;
}

/** 延迟启动，复用单一进程。参数使用数组，不经过 shell；不记录配对管理凭证。 */
export class MobileBridge {
  child = null;
  pending = null;
  async start(options) {
    if (this.pending) return this.pending;
    if (!existsSync(options.libraries)) throw new Error('尚未构建移动连接组件。请先在 pilot-mobile 运行 Gradle 的 :bridge:installDist 任务。');
    const command = mobileBridgeOptions(options);
    this.pending = (async () => {
      const java = await resolveMobileJava(options);
      return new Promise((resolve, reject) => {
        const child = spawn(java, command.args, { env: options.env || process.env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
        this.child = child;
        let output = '';
        const timer = setTimeout(() => { child.kill(); reject(new Error('移动连接启动超时，请确认 Java 21 或更新版本可用。')); }, 30000);
        child.stdout.on('data', chunk => {
          output = (output + chunk).slice(-16384);
          try { const url = parseMobileAdmin(output); if (url) { clearTimeout(timer); resolve(url); } }
          catch (error) { clearTimeout(timer); child.kill(); reject(error); }
        });
        child.stderr.on('data', () => {});
        child.once('error', () => { clearTimeout(timer); reject(new Error('无法启动移动连接，请配置 JAVA_HOME 指向 Java 21 或更新版本。')); });
        child.once('exit', () => { clearTimeout(timer); this.child = null; this.pending = null; reject(new Error('移动连接已退出，请检查 Java 版本和中继配置后重试。')); });
      });
    })();
    try { return await this.pending; } catch (error) { this.pending = null; throw error; }
  }
  stop() { this.child?.kill(); this.child = null; this.pending = null; }
}
