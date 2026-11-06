import { readdir } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

// Node 22 不把显式目录参数当作测试目录；枚举文件也避免依赖 Windows shell 展开通配符。
const root = fileURLToPath(new URL('../', import.meta.url));
const directory = join(root, 'src', 'test', 'js');
const files = (await readdir(directory)).filter(name => name.endsWith('.test.js')).sort().map(name => join(directory, name));
if (!files.length) throw new Error('未找到 Web 测试，不能报告通过');
const loader = new URL('../frontend/scripts/register-typescript.mjs', import.meta.url);
const child = spawn(process.execPath, ['--import', loader.href, '--test', ...files], { cwd: root, stdio: 'inherit' });
child.once('error', error => { console.error(error.message); process.exitCode = 1; });
child.once('exit', code => { process.exitCode = code ?? 1; });
