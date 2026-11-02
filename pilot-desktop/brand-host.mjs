/**
 * 准备开发用 Pilot.app，业务代码仍从源码目录加载。
 * 不修改 node_modules 里的 Electron；按运行时、图标和脚本版本缓存，升级时生成新壳。
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(import.meta.url);
export const desktopRoot = dirname(script);
const appName = 'Pilot';
const bundleId = 'com.pilot.desktop.dev';
const buddy = '/usr/libexec/PlistBuddy';

export function electronBinary(root = desktopRoot, platform = process.platform) {
  const dist = join(root, 'node_modules', 'electron', 'dist');
  if (platform === 'darwin') return join(dist, 'Electron.app', 'Contents', 'MacOS', 'Electron');
  return join(dist, platform === 'win32' ? 'electron.exe' : 'electron');
}

function setPlist(plist, key, value) {
  try {
    execFileSync(buddy, ['-c', `Set :${key} ${value}`, plist], { stdio: 'pipe' });
  } catch {
    execFileSync(buddy, ['-c', `Add :${key} string ${value}`, plist], { stdio: 'pipe' });
  }
}

function brandBundle(bundle, oldName, newName, identifier) {
  const contents = join(bundle, 'Contents');
  const plist = join(contents, 'Info.plist');
  for (const [key, value] of Object.entries({
    CFBundleName: newName, CFBundleDisplayName: newName,
    CFBundleIdentifier: identifier, CFBundleExecutable: newName
  })) setPlist(plist, key, value);
  renameSync(join(contents, 'MacOS', oldName), join(contents, 'MacOS', newName));
}

function buildIcon(source, contents, staging) {
  const iconset = join(staging, 'Pilot.iconset');
  mkdirSync(iconset);
  for (const size of [16, 32, 128, 256, 512]) {
    for (const scale of [1, 2]) {
      const target = join(iconset, `icon_${size}x${size}${scale === 2 ? '@2x' : ''}.png`);
      execFileSync('/usr/bin/sips', ['-z', String(size * scale), String(size * scale), source, '--out', target], { stdio: 'pipe' });
    }
  }
  execFileSync('/usr/bin/iconutil', ['-c', 'icns', iconset, '-o', join(contents, 'Resources', 'Pilot.icns')], { stdio: 'pipe' });
  setPlist(join(contents, 'Info.plist'), 'CFBundleIconFile', 'Pilot.icns');
  rmSync(iconset, { recursive: true });
}

export function prepareDevelopmentHost(root = desktopRoot) {
  const sourceBinary = electronBinary(root);
  if (!existsSync(sourceBinary)) throw new Error('还没有安装桌面壳，请执行 npm --prefix pilot-desktop install。');
  if (process.platform !== 'darwin') return sourceBinary;

  const sourceApp = resolve(sourceBinary, '../../..');
  const icon = join(root, 'assets', 'icon.png');
  const fingerprint = createHash('sha256')
    .update(readFileSync(script))
    .update(readFileSync(join(root, 'node_modules', 'electron', 'package.json')))
    .update(readFileSync(join(sourceApp, 'Contents', 'Info.plist')))
    .update(readFileSync(icon))
    .update(String(statSync(sourceBinary).mtimeMs))
    .digest('hex').slice(0, 20);
  const cache = join(root, 'out', 'dev-host');
  const destination = join(cache, fingerprint);
  const binary = join(destination, 'Pilot.app', 'Contents', 'MacOS', 'Pilot');
  if (existsSync(binary) && existsSync(join(destination, 'ready.json'))) return binary;

  mkdirSync(cache, { recursive: true });
  const staging = mkdtempSync(join(cache, '.prepare-'));
  try {
    const bundle = join(staging, 'Pilot.app');
    execFileSync('/usr/bin/ditto', [sourceApp, bundle], { stdio: 'pipe' });
    brandBundle(bundle, 'Electron', appName, bundleId);
    const contents = join(bundle, 'Contents');
    const frameworks = join(contents, 'Frameworks');
    for (const entry of readdirSync(frameworks).filter(name => /^Electron Helper.*\.app$/.test(name))) {
      const oldName = entry.slice(0, -4);
      const newName = oldName.replace('Electron', appName);
      const suffix = oldName.match(/\((.+)\)/)?.[1]?.toLowerCase();
      const helper = join(frameworks, entry);
      brandBundle(helper, oldName, newName, `${bundleId}.helper${suffix ? '.' + suffix : ''}`);
      renameSync(helper, join(frameworks, newName + '.app'));
    }
    setPlist(join(contents, 'Info.plist'), 'NSMicrophoneUsageDescription', 'Pilot 使用麦克风将你的语音转为消息草稿。');
    buildIcon(icon, contents, staging);
    execFileSync('/usr/bin/codesign', ['--force', '--deep', '--sign', '-', bundle], { stdio: 'pipe' });
    execFileSync('/usr/bin/codesign', ['--verify', '--deep', '--strict', bundle], { stdio: 'pipe' });
    writeFileSync(join(staging, 'ready.json'), JSON.stringify({ fingerprint, appName, bundleId }));
    // 同时从 IDEA / npm 启动时，先完成的产物获胜，不覆盖任何正在运行的壳。
    try {
      renameSync(staging, destination);
    } catch (error) {
      if (!existsSync(binary) || !existsSync(join(destination, 'ready.json'))) throw error;
    }
    return binary;
  } finally {
    rmSync(staging, { recursive: true, force: true });
  }
}

if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(script)) {
  try {
    console.log(prepareDevelopmentHost());
  } catch (error) {
    console.error('准备 Pilot 开发壳失败：' + error.message);
    process.exitCode = 1;
  }
}
