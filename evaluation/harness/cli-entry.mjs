import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/** 比较真实文件路径，兼容符号链接、Windows 目录联接及保留主模块别名的 Node 参数。 */
export function isCliEntry(moduleUrl) {
  if (!process.argv[1]) return false;
  try {
    return realpathSync(fileURLToPath(moduleUrl)) === realpathSync(process.argv[1]);
  } catch {
    // node --eval 后的第一个参数可以是普通数据，不一定对应文件。
    return false;
  }
}
