// 统一构建/启动入口。直接执行 Java wrapper，Windows 不需要 shell 拼接批处理命令。
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'pilot-mobile');
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const operation = process.argv[2] || 'build';
let args;
if (operation === 'bridge') {
  const libraries = path.join(root, 'bridge/build/install/bridge/lib');
  if (!existsSync(libraries)) throw new Error('先运行 node scripts/mobile.mjs build 构建连接组件');
  args = ['--enable-native-access=ALL-UNNAMED', '-cp', path.join(libraries, '*'), 'com.pilot.mobile.bridge.MainKt', ...process.argv.slice(3)];
} else {
  if (operation === 'release' && ['PILOT_ANDROID_KEYSTORE','PILOT_ANDROID_STORE_PASSWORD','PILOT_ANDROID_KEY_ALIAS','PILOT_ANDROID_KEY_PASSWORD'].some(key => !process.env[key])) throw new Error('正式签名需要配置 PILOT_ANDROID_KEYSTORE、PILOT_ANDROID_STORE_PASSWORD、PILOT_ANDROID_KEY_ALIAS、PILOT_ANDROID_KEY_PASSWORD');
  const tasks = {
    release: [':app:assembleRelease', ':bridge:installDist'],
    build: [':app:assembleDebug', ':bridge:installDist'],
    'build-bridge': ['-Ppilot.desktopOnly=true', ':bridge:installDist'],
    'test-bridge': ['-Ppilot.desktopOnly=true', ':transport:test', ':bridge:test', ':bridge:installDist'],
    test: [':transport:test', ':bridge:test', ':app:testDebugUnitTest', ':app:lintDebug'],
    qa: [':app:assembleQa', ':app:assembleQaAndroidTest', ':bridge:installDist'],
  }[operation];
  if (!tasks) throw new Error('用法：node scripts/mobile.mjs build|build-bridge|test-bridge|test|qa|release|bridge');
  args = ['-classpath', path.join(root, 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain', ...tasks];
}
const child = spawn(java, args, { cwd: root, env: process.env, stdio: 'inherit', windowsHide: true });
child.on('error', error => { console.error('启动失败，请检查 JAVA_HOME 与 Java 21：' + error.message); process.exitCode = 1; });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
child.on('exit', code => { process.exitCode = code ?? 1; });
