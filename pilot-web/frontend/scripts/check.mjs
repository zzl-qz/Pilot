import { build } from 'vite';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import './typecheck.mjs';

// 校验真实的 JSX/样式构建，不替换正在运行的静态产物；兼容各平台临时目录。
const output = await mkdtemp(join(tmpdir(), 'pilot-frontend-check-'));
try {
  await build({ root: fileURLToPath(new URL('..', import.meta.url)), build: { outDir: output } });
} finally {
  await rm(output, { recursive: true, force: true });
}
