import { existsSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, posix, resolve, win32 } from 'node:path';

/**
 * 各平台常见的 Google Chrome 安装位置。
 *
 * 用绝对路径而不是 channel，是因为命令子进程的环境经过白名单裁剪：缺了 PROGRAMFILES 一类变量时，
 * Playwright 会把路径拼成 `undefined\Program Files\...` 并误报「浏览器未安装」。
 * Windows 除了系统级安装，还要看用户级安装（LOCALAPPDATA）；系统盘不一定在 C:，按 SYSTEMDRIVE 拼。
 * macOS 兼顾 arm64 与 x86 的 Chrome 变体；Linux 覆盖发行版包、deb 包、snap 和 chromium。
 *
 * Windows 分支统一用 win32 拼接，这样同一份断言在 macOS/Linux 上跑也能得到一致的反斜杠路径。
 *
 * platform/env 可注入，好让三个系统的候选清单都能在任何一个系统上被测试。
 */
export function chromeCandidates({ platform = process.platform, env = process.env } = {}) {
  if (platform === 'darwin') {
    const app = (name) => `/Applications/${name}.app/Contents/MacOS/${name}`;
    return [app('Google Chrome'), app('Google Chrome Canary'),
      posix.join(homedir(), 'Applications', 'Google Chrome.app', 'Contents', 'MacOS', 'Google Chrome')];
  }
  if (platform === 'linux') {
    return ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/opt/google/chrome/chrome',
      '/snap/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser'];
  }
  const drive = env.SYSTEMDRIVE || env.SystemDrive || 'C:';
  const programDirs = [env.PROGRAMFILES, env['PROGRAMFILES(X86)'], win32.join(drive, 'Program Files'),
    win32.join(drive, 'Program Files (x86)')].filter(Boolean);
  const localAppData = env.LOCALAPPDATA || win32.join(homedir(), 'AppData', 'Local');
  return [
    ...programDirs.map((dir) => win32.join(dir, 'Google', 'Chrome', 'Application', 'chrome.exe')),
    win32.join(localAppData, 'Google', 'Chrome', 'Application', 'chrome.exe'),
  ];
}

/** 找到本机已安装的 Chrome 就返回其路径，找不到返回 undefined。 */
export function installedChrome() {
  return chromeCandidates().find((candidate) => existsSync(candidate));
}

/**
 * 默认优先用本机已安装的 Google Chrome，没有才退回 Playwright 自带的 chromium；
 * PILOT_TEST_CHROMIUM_EXECUTABLE 指定路径，PILOT_TEST_BROWSER_CHANNEL=chrome 强制走渠道检测。
 */
export function browserOptions({ headless = true, env = process.env } = {}) {
  const executablePath = env.PILOT_TEST_CHROMIUM_EXECUTABLE?.trim();
  if (executablePath) return { headless, executablePath };
  const channel = env.PILOT_TEST_BROWSER_CHANNEL?.trim();
  if (channel) return { headless, channel };
  const chrome = installedChrome();
  return chrome ? { headless, executablePath: chrome } : { headless };
}

/**
 * 浏览器启动失败时把「为什么」和「怎么改」一起说出来。
 * 三个系统各自的安装位置不同，笼统喊「装个浏览器」会让人在 macOS/Linux 上照着 Windows 路径去找。
 */
export function browserLaunchHint(error, platform = process.platform) {
  const detail = error instanceof Error ? error.message : String(error);
  const where = {
    win32: 'Windows：安装 Google Chrome（默认在 C:\\Program Files\\Google\\Chrome\\Application）',
    darwin: 'macOS：安装 Google Chrome 到 /Applications',
    linux: 'Linux：安装 google-chrome 或 chromium（/usr/bin）',
  }[platform] || '确认当前系统的 Chrome 安装位置';
  return `${detail}\n浏览器不可用。${where}，或用 PILOT_TEST_CHROMIUM_EXECUTABLE 指定可执行文件路径。`;
}
/** Electron 的主进程入口及用户目录需要绝对路径，支持调用方传入相对的产物目录。 */
export function artifactDirectory() {
  return resolve(process.env.PILOT_TEST_ARTIFACTS_DIR || tmpdir());
}
