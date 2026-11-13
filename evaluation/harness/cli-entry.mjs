import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/** 比较真实文件路径，兼容符号链接、Windows 目录联接及保留主模块别名的 Node 参数。 */
export function isCliEntry(moduleUrl) {
  return !!process.argv[1] && realpathSync(fileURLToPath(moduleUrl)) === realpathSync(process.argv[1]);
}
