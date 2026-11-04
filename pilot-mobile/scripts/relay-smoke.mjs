import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const exec = promisify(execFile);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const libraries = path.join(root, 'bridge/build/install/bridge/lib');
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const server = spawn(java, ['--enable-native-access=ALL-UNNAMED', '-cp', path.join(libraries, '*'), 'com.pilot.mobile.bridge.MainKt', '--probe-relay-server'], { stdio: ['ignore', 'pipe', 'pipe'] });
try {
  const ticket = await new Promise((resolve, reject) => {
    let output = '', errors = '';
    const timer = setTimeout(() => reject(new Error('公开中继不可用或连接超时')), 30000);
    server.stderr.on('data', chunk => { errors += chunk; });
    server.once('exit', code => { clearTimeout(timer); reject(new Error(`探针退出 ${code}: ${errors}`)); });
    server.stdout.on('data', chunk => { output += chunk; const match = output.match(/PROBE_TICKET (\S+)/); if (match) { clearTimeout(timer); resolve(match[1]); } });
  });
  // 固定探针数据；不挂载用户项目、令牌或 Docker socket，不开放容器端口。
  const result = await exec('docker', ['run', '--rm', '--read-only', '--tmpfs', '/tmp:rw,exec,nosuid,size=512m', '--network', 'bridge', '--entrypoint', 'java', '-v', `${libraries}:/probe:ro`, process.env.PILOT_PROBE_JAVA_IMAGE || 'maven:3.9-eclipse-temurin-25', '--enable-native-access=ALL-UNNAMED', '-cp', '/probe/*', 'com.pilot.mobile.bridge.MainKt', '--probe-relay-client', ticket], { timeout: 60000, maxBuffer: 1024 * 1024 });
  console.log(result.stdout.trim());
} finally { server.kill(); }
