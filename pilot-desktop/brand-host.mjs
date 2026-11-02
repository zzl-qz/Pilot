/**
 * 开发时直接跑的是 Electron.app，Dock 悬停和程序名读的是这个包的 Info.plist。
 * 装完依赖后把显示名改成 Pilot，并重新签名，否则 macOS 仍显示 Electron。
 */
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

if (process.platform !== 'darwin') process.exit(0);

const root = dirname(fileURLToPath(import.meta.url));
const app = join(root, 'node_modules', 'electron', 'dist', 'Electron.app');
const plist = join(app, 'Contents', 'Info.plist');
if (!existsSync(plist)) process.exit(0);

const buddy = '/usr/libexec/PlistBuddy';
for (const key of ['CFBundleName', 'CFBundleDisplayName']) {
  try {
    execFileSync(buddy, ['-c', `Set :${key} Pilot`, plist], { stdio: 'ignore' });
  } catch {
    execFileSync(buddy, ['-c', `Add :${key} string Pilot`, plist], { stdio: 'ignore' });
  }
}
execFileSync('codesign', ['--force', '--deep', '--sign', '-', app], { stdio: 'ignore' });
