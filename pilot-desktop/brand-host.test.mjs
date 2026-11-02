import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { desktopRoot, electronBinary, prepareDevelopmentHost } from './brand-host.mjs';

test('保留各平台原始 Electron 入口，供开发壳准备及非 macOS 启动使用', () => {
  const root = resolve('test-root');
  assert.equal(electronBinary(root, 'darwin'), join(root, 'node_modules/electron/dist/Electron.app/Contents/MacOS/Electron'));
  assert.equal(electronBinary(root, 'win32'), join(root, 'node_modules/electron/dist/electron.exe'));
  assert.equal(electronBinary(root, 'linux'), join(root, 'node_modules/electron/dist/electron'));
});

test('macOS 开发壳有独立身份和有效签名，重复准备复用产物且不修改依赖', { skip: process.platform !== 'darwin' }, () => {
  const source = electronBinary();
  const sourcePlist = resolve(source, '../../Info.plist');
  const original = readFileSync(sourcePlist);
  const nodePath = readFileSync(join(desktopRoot, 'node_modules/electron/path.txt'));
  const binary = prepareDevelopmentHost();
  assert.equal(binary.endsWith('/Pilot.app/Contents/MacOS/Pilot'), true);
  assert.notEqual(binary, source);
  const app = resolve(binary, '../../..');
  const plist = join(app, 'Contents/Info.plist');
  const value = (file, key) => execFileSync('/usr/libexec/PlistBuddy', ['-c', `Print :${key}`, file], { encoding: 'utf8' }).trim();
  assert.equal(value(plist, 'CFBundleName'), 'Pilot');
  assert.equal(value(plist, 'CFBundleDisplayName'), 'Pilot');
  assert.equal(value(plist, 'CFBundleIdentifier'), 'com.pilot.desktop.dev');
  assert.equal(value(plist, 'CFBundleExecutable'), 'Pilot');
  assert.equal(value(plist, 'CFBundleIconFile'), 'Pilot.icns');
  assert.match(value(plist, 'NSMicrophoneUsageDescription'), /麦克风/);
  assert.ok(statSync(join(app, 'Contents/Resources/Pilot.icns')).size > 0);
  for (const suffix of ['', ' (GPU)', ' (Plugin)', ' (Renderer)']) {
    const helper = join(app, `Contents/Frameworks/Pilot Helper${suffix}.app/Contents`);
    assert.equal(value(join(helper, 'Info.plist'), 'CFBundleName'), `Pilot Helper${suffix}`);
    assert.ok(statSync(join(helper, `MacOS/Pilot Helper${suffix}`)).isFile());
  }
  execFileSync('/usr/bin/codesign', ['--verify', '--deep', '--strict', app]);
  const modified = statSync(plist).mtimeMs;
  assert.equal(prepareDevelopmentHost(), binary);
  assert.equal(statSync(plist).mtimeMs, modified, '第二次准备不重写或重新签名');
  assert.deepEqual(readFileSync(sourcePlist), original);
  assert.deepEqual(readFileSync(join(desktopRoot, 'node_modules/electron/path.txt')), nodePath);
});
