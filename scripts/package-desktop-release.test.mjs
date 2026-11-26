import test from 'node:test';
import assert from 'node:assert/strict';
import {
  PILOT_JLINK_MODULES,
  buildJlinkArgs,
  executableName,
  linuxAppRunScript,
  linuxDesktopEntry,
  macInfoPlist,
  macLauncherScript,
  parseArgs,
  windowsLauncherCmd,
  windowsNsisScript
} from './package-desktop-release.mjs';

test('buildJlinkArgs uses minimal curated module list and compression', () => {
  const args = buildJlinkArgs('/tmp/runtime', ['jdk.charsets', 'java.base']);
  const moduleIndex = args.indexOf('--add-modules');
  assert.ok(moduleIndex >= 0);
  const modules = args[moduleIndex + 1].split(',');
  assert.ok(modules.includes('java.base'));
  assert.ok(modules.includes('java.sql'));
  assert.ok(modules.includes('jdk.crypto.ec'));
  assert.ok(modules.includes('jdk.charsets'));
  assert.equal(modules.filter((m) => m === 'java.base').length, 1);
  assert.ok(!modules.includes('jdk.jlink'));
  assert.ok(args.includes('--strip-debug'));
  assert.ok(args.includes('--no-header-files'));
  assert.ok(args.includes('--no-man-pages'));
  assert.ok(args.includes('--compress=zip-6'));
  assert.ok(PILOT_JLINK_MODULES.length <= 25);
});

test('executableName appends .exe only on win32', () => {
  assert.equal(executableName('java', 'win32'), 'java.exe');
  assert.equal(executableName('jlink', 'darwin'), 'jlink');
  assert.equal(executableName('java', 'linux'), 'java');
});

test('platform launchers bind localhost and enable native access cleanly', () => {
  const mac = macLauncherScript();
  assert.ok(mac.includes('runtime/bin/java'));
  assert.ok(mac.includes('--enable-native-access=ALL-UNNAMED'));
  assert.ok(mac.includes('--server.address=127.0.0.1'));
  assert.ok(macInfoPlist('1.2.3').includes('<string>1.2.3</string>'));

  const win = windowsLauncherCmd();
  assert.ok(win.includes('\r\n'));
  assert.ok(win.includes('runtime\\bin\\java.exe'));
  assert.ok(win.includes('--server.address=127.0.0.1'));
  assert.ok(windowsNsisScript('x64', '1.2.3').includes('Pilot-Windows-x64-Setup.exe'));

  const linux = linuxAppRunScript();
  assert.ok(linux.includes('usr/lib/pilot/runtime/bin/java'));
  assert.ok(linux.includes('--server.address=127.0.0.1'));
  assert.ok(linuxDesktopEntry().includes('Exec=AppRun'));
  assert.ok(linuxDesktopEntry().includes('Icon=pilot'));
  assert.ok(linuxDesktopEntry().includes('StartupWMClass=Pilot'));
});

test('parseArgs parses flags and rejects unknown flags', () => {
  const parsed = parseArgs(['--platform', 'linux', '--arch', 'x64', '--skip-dmg', '--verify']);
  assert.equal(parsed.platform, 'linux');
  assert.equal(parsed.arch, 'x64');
  assert.equal(parsed.skipDmg, true);
  assert.equal(parsed.verify, true);
  assert.throws(() => parseArgs(['--bogus']), /未知参数/);
});

test('verify uses the desktop launch property', async () => {
  const { readFile } = await import('node:fs/promises');
  const script = await readFile(new URL('./package-desktop-release.mjs', import.meta.url), 'utf8');
  assert.match(script, /--pilot\.desktop\.launch=false/);
  assert.doesNotMatch(script, /--pilot\.desktop\.open-browser=false/);
});
