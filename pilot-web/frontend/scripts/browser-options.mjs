import { tmpdir } from 'node:os';
import { resolve } from 'node:path';

/** 默认验证 Chrome；ARM Linux 等环境可显式选择已安装的 Chromium，不依赖 shell 别名。 */
export function browserOptions({ headless = true } = {}) {
  const executablePath = process.env.PILOT_TEST_CHROMIUM_EXECUTABLE?.trim();
  return { headless, ...(executablePath ? { executablePath } : { channel: 'chrome' }) };
}
/** Electron 的主进程入口及用户目录需要绝对路径，支持调用方传入相对的产物目录。 */
export function artifactDirectory() {
  return resolve(process.env.PILOT_TEST_ARTIFACTS_DIR || tmpdir());
}
