import assert from 'node:assert/strict';
import { homedir } from 'node:os';
import { posix } from 'node:path';
import { browserLaunchHint, browserOptions, chromeCandidates } from './browser-options.mjs';

// 三个系统的候选清单必须在任意一台机器上都能核对，不依赖本机装了什么。
const win = chromeCandidates({
  platform: 'win32',
  env: { PROGRAMFILES: 'C:\\Program Files', LOCALAPPDATA: 'C:\\Users\\me\\AppData\\Local' },
});
assert.ok(win.includes('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'), win.join('\n'));
assert.ok(win.includes('C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe'), win.join('\n'));
assert.ok(win.includes('C:\\Users\\me\\AppData\\Local\\Google\\Chrome\\Application\\chrome.exe'), win.join('\n'));

const mac = chromeCandidates({ platform: 'darwin', env: {} });
assert.equal(mac[0], '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome');
assert.equal(mac[1], '/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary');
// 用户级安装目录：macOS 上是 /Users/<name>/Applications；跨平台跑测试时 homedir() 跟随本机，所以按拼出的值比。
assert.equal(mac[2], posix.join(homedir(), 'Applications', 'Google Chrome.app', 'Contents', 'MacOS', 'Google Chrome'));
assert.equal(mac.length, 3, mac.join('\n'));

const linux = chromeCandidates({ platform: 'linux', env: {} });
assert.ok(linux.includes('/usr/bin/google-chrome'), linux.join('\n'));
assert.ok(linux.includes('/usr/bin/google-chrome-stable'), linux.join('\n'));
assert.ok(linux.includes('/opt/google/chrome/chrome'), linux.join('\n'));
assert.ok(linux.includes('/usr/bin/chromium'), linux.join('\n'));
assert.ok(linux.every((path) => path.startsWith('/')), linux.join('\n'));

// 环境被裁到只剩默认值时，Windows 仍要给出确定的绝对路径，不能拼出 undefined。
const bare = chromeCandidates({ platform: 'win32', env: {} });
assert.ok(bare.every((path) => path.includes('Program Files') || path.includes('AppData')), bare.join('\n'));
assert.ok(!bare.some((path) => path.includes('undefined')), bare.join('\n'));

// 系统盘不是 C: 时（在 D: 装系统的机器）要跟着 SYSTEMDRIVE 走，不能永远拼成 C:\Program Files。
// LOCALAPPDATA 回落用的是用户目录，可以不在系统盘上，所以只核对 Program Files 那两条。
const onD = chromeCandidates({ platform: 'win32', env: { SYSTEMDRIVE: 'D:' } });
assert.ok(onD.includes('D:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'), onD.join('\n'));
assert.ok(onD.filter((path) => path.includes('Program Files')).every((path) => path.startsWith('D:')),
  onD.join('\n'));

// 三个平台的路径必须用各自的写法：Windows 是反斜杠，macOS/Linux 是正斜杠。
// macOS 的用户目录那一条由 homedir() 拼出，跳平台跑测试时它就是本机写法，不参与这项检查。
assert.ok(win.every((path) => path.includes('\\')), win.join('\n'));
assert.ok([...mac.slice(0, 2), ...linux].every((path) => !path.includes('\\')),
  mac.slice(0, 2).concat(linux).join('\n'));

// 显式指定的路径优先，其次是渠道，最后才回落到本机探测。
assert.deepEqual(
  browserOptions({ env: { PILOT_TEST_CHROMIUM_EXECUTABLE: '/custom/chrome', PILOT_TEST_BROWSER_CHANNEL: 'chrome' } }),
  { headless: true, executablePath: '/custom/chrome' });
assert.deepEqual(browserOptions({ env: { PILOT_TEST_BROWSER_CHANNEL: 'chrome' } }), { headless: true, channel: 'chrome' });
assert.equal(browserOptions({ headless: false, env: { PILOT_TEST_BROWSER_CHANNEL: 'chrome' } }).headless, false);

// 启动失败的提示要按平台给对应安装位置，不能一律照 Windows 路径说。
const error = new Error('Cannot find Chrome');
assert.ok(browserLaunchHint(error, 'win32').includes('C:\\Program Files'), browserLaunchHint(error, 'win32'));
assert.ok(browserLaunchHint(error, 'darwin').includes('/Applications'), browserLaunchHint(error, 'darwin'));
assert.ok(browserLaunchHint(error, 'linux').includes('/usr/bin'), browserLaunchHint(error, 'linux'));
for (const platform of ['win32', 'darwin', 'linux']) {
  assert.ok(browserLaunchHint(error, platform).includes('Cannot find Chrome'));
  assert.ok(browserLaunchHint(error, platform).includes('PILOT_TEST_CHROMIUM_EXECUTABLE'));
}
assert.ok(browserLaunchHint('launch failed', 'freebsd').includes('PILOT_TEST_CHROMIUM_EXECUTABLE'));

console.log('PASS browser-options.test.mjs');