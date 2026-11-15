// 文档相对链接检查：主要 Markdown 里指向仓库内文件的链接必须存在。
// 用法：node scripts/check-doc-links.mjs
import { readFileSync, existsSync, statSync } from 'node:fs';
import { readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const roots = ['README.md', 'AGENTS.md', 'CONTRIBUTING.md', 'PROJECT_TECHNICAL_GUIDE.md'];
const dirs = ['docs-public'];
const files = [...roots];
for (const dir of dirs) {
  const walk = (path) => {
    for (const entry of readdirSync(path, { withFileTypes: true })) {
      const full = join(path, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.md')) files.push(relative(root, full).replaceAll('\\', '/'));
    }
  };
  walk(join(root, dir));
}

const link = /\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g;
const problems = [];
let checked = 0;
for (const file of files) {
  const full = join(root, file);
  if (!existsSync(full)) {
    problems.push(`${file}: 文档文件不存在`);
    continue;
  }
  const text = readFileSync(full, 'utf8');
  for (const match of text.matchAll(link)) {
    const target = match[1];
    if (/^(?:[a-z]+:)?\/\//i.test(target) || target.startsWith('mailto:') || target.startsWith('#')) continue;
    const path = target.split('#')[0].split('?')[0];
    if (!path) continue;
    checked += 1;
    const absolute = resolve(dirname(full), path);
    if (!existsSync(absolute)) {
      problems.push(`${file}: 链接目标不存在 -> ${target}`);
      continue;
    }
    const inside = relative(root, absolute);
    if (inside.startsWith('..')) problems.push(`${file}: 链接指向仓库外 -> ${target}`);
    else if (statSync(absolute).isDirectory() && !target.endsWith('/')) continue;
  }
}

if (problems.length) {
  console.error(problems.join('\n'));
  console.error(`检查 ${checked} 个相对链接，发现 ${problems.length} 个问题`);
  process.exit(1);
}
console.log(`PASS 检查 ${checked} 个相对链接，全部指向存在的文件`);