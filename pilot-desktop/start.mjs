import { spawn } from 'node:child_process';
import { desktopRoot, prepareDevelopmentHost } from './brand-host.mjs';

try {
  const env = { ...process.env };
  delete env.ELECTRON_RUN_AS_NODE;
  const child = spawn(prepareDevelopmentHost(), [desktopRoot, ...process.argv.slice(2)], {
    cwd: desktopRoot, env, stdio: 'inherit'
  });
  child.on('error', error => { console.error(error.message); process.exitCode = 1; });
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
  child.on('exit', code => { process.exitCode = code ?? 1; });
} catch (error) {
  console.error('启动 Pilot 失败：' + error.message);
  process.exitCode = 1;
}
