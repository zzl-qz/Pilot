import { realpathSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** 跨平台解析 java 可执行文件路径；未设 JAVA_HOME 时交由 PATH 解析。 */
export function resolveJava(javaHome = process.env.JAVA_HOME, platform = process.platform) {
  if (!javaHome) return 'java';
  return path.join(javaHome, 'bin', platform === 'win32' ? 'java.exe' : 'java');
}

/** 从 `java -version` 输出校验 JDK 主版本号，不满足时抛出可执行提示。 */
export function checkJdkMajor(output, minMajor = 25, context = '当前操作') {
  const text = String(output || '');
  const major = Number(text.match(/version\s+"(\d+)/)?.[1]);
  if (!Number.isFinite(major)) {
    throw new Error(`无法识别 Java 版本，请把 JAVA_HOME 指向 JDK ${minMajor}+（${context}）`);
  }
  if (major < minMajor) {
    const firstLine = text.split(/\r?\n/)[0] || String(major);
    throw new Error(`${context}需要 JDK ${minMajor}+，当前检测到：${firstLine}`);
  }
  return major;
}

/** 判断当前 ESM 模块是否由命令行直接执行，兼容符号链接与 Windows 大小写。 */
export function isDirectRun(metaUrl, argv1 = process.argv[1], platform = process.platform) {
  if (!argv1) return false;
  try {
    const normalize = (file) => {
      const abs = path.resolve(file);
      try { return realpathSync(abs); } catch { return abs; }
    };
    const invoked = normalize(argv1);
    const modulePath = normalize(fileURLToPath(metaUrl));
    return platform === 'win32'
      ? invoked.toLowerCase() === modulePath.toLowerCase()
      : invoked === modulePath;
  } catch {
    return false;
  }
}

/** 轮询条件直至返回真值或超时。 */
export async function waitFor(predicate, { timeoutMs = 8000, intervalMs = 30, message = '等待状态收敛超时' } = {}) {
  const deadline = Date.now() + timeoutMs;
  while (true) {
    const value = await predicate();
    if (value) return value;
    if (Date.now() >= deadline) throw new Error(message);
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}

/** 遮挡日志与报告里的移动端配对码。 */
export function redactPairingCodes(text) {
  return String(text ?? '').replace(/pilot:\/\/pair\/[A-Za-z0-9_-]+/g, '[配对码已隐藏]');
}
