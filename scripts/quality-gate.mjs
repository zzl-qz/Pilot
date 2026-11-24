#!/usr/bin/env node
// 代码质量门禁：只对「本次改动引入」的坏味道报错，存量债务不阻塞提交。
// 判定方式不是维护一份基线快照，而是把改动文件在基线版本上的检查结果一起算出来做差集：
// 快照会过期、会随行号漂移，而「这个文件在基线版本里长什么样」永远是准的。
// 输出行固定为 `path:line [规则] 说明`，因为这条输出会被回灌到模型上下文，格式稳定比信息丰富更重要。
import { spawnSync } from 'node:child_process';
import { closeSync, existsSync, mkdirSync, mkdtempSync, openSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { createRequire } from 'node:module';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)));
const JAVA_DIRS = ['pilot-core/src/main/java', 'pilot-core/src/test/java', 'pilot-server/src/main/java', 'pilot-server/src/test/java'];
const TS_DIRS = ['pilot-web/frontend/src'];
const JAVA_EXT = /\.java$/;
const TS_EXT = /\.(ts|tsx)$/;

// 规则的 id、阈值、提示文案统一来自 config/quality/rules.json：这里只放实现，不放口径。
const CATALOG = JSON.parse(readFileSync(join(ROOT, 'config', 'quality', 'rules.json'), 'utf8'));
const HINTS = Object.fromEntries(CATALOG.rules.map((rule) => [rule.id, rule.hint]));
// Checkstyle 的模块名和规则 id 是两套命名，靠目录里的 engines 声明对齐，输出统一用规则 id。
const CHECKSTYLE_RULE_IDS = new Map();
for (const rule of CATALOG.rules) {
  for (const engine of rule.engines ?? []) {
    if (engine.engine === 'checkstyle') CHECKSTYLE_RULE_IDS.set(engine.module, rule.id);
  }
}

// 本文件实现的门禁侧规则（对应 rules.json 的 engines[].impl）。同步脚本会校验两边一致：
// 目录里有实现没写、或写了目录里没有，都会直接失败。
export const GATE_IMPLS = new Set([
  'ts-conditional',
  'ts-any',
  'ts-catch',
  'import-direction',
  'suppression-scan',
  'encoding-probe',
  'report-coverage',
  'ts-parse'
]);

// 只拦「把整片检查关掉」的写法，不拦正常的单规则抑制（例如 @SuppressWarnings("unchecked")）。
const BROAD_SUPPRESSION = /@SuppressWarnings\s*\(\s*(?:"all"|"ALL")\s*\)|NOSONAR|\/\/\s*noinspection|eslint-disable(?!-next-line\s+[\w@/-]+)/;

// 模块依赖方向：以前只写在文档里，模型复制一个 import 就能破掉。这里按 package 声明做机器检查。
const LAYER_RULES = [
  { from: 'com.pilot.core', to: 'com.pilot.server', message: 'pilot-core 是底层执行内核，不能反向依赖 pilot-server' }
];

function usage(message) {
  if (message) console.error(`[quality-gate] ${message}`);
  console.error([
    '用法：node scripts/quality-gate.mjs [选项]',
    '  --changed            只检查本次改动引入的问题（默认）',
    '  --all                全量检查全部坏味道（诊断用，存量也会报）',
    '  --base <ref>         指定对比基线（默认 HEAD；CI 里可传 origin/master）',
    '  --java-only / --ts-only',
    '  --max-findings <n>   最多打印多少条（默认 40）',
    '  [文件...]            只检查列出的仓库相对路径；不写则检查 git 里的全部改动'
  ].join('\n'));
  process.exit(message ? 2 : 0);
}

function parseArgs(argv) {
  const options = { mode: 'changed', base: 'HEAD', java: true, ts: true, maxFindings: 40, files: [] };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--changed') options.mode = 'changed';
    else if (arg === '--all') options.mode = 'all';
    else if (arg === '--java-only') options.ts = false;
    else if (arg === '--ts-only') options.java = false;
    else if (arg === '--base') options.base = argv[++i] ?? 'HEAD';
    else if (arg === '--max-findings') options.maxFindings = Number(argv[++i] ?? 40);
    else if (arg === '--help' || arg === '-h') usage();
    else if (arg.startsWith('-')) usage(`未知参数：${arg}`);
    else if (arg !== '{files}') options.files.push(arg.replace(/\\/g, '/').replace(/^\.\//, ''));
  }
  return options;
}

// 子进程输出落盘而不是走管道：受限沙箱会拒绝命名管道，管道形式的 spawn 会直接 EPERM。
function runCapture(command, args, cwd = ROOT) {
  const dir = mkdtempSync(join(tmpdir(), 'pilot-qg-'));
  const outFile = join(dir, 'stdout.txt');
  const errFile = join(dir, 'stderr.txt');
  const outFd = openSync(outFile, 'w');
  const errFd = openSync(errFile, 'w');
  try {
    const result = spawnSync(command, args, { cwd, stdio: ['ignore', outFd, errFd] });
    const stdout = readFileSync(outFile, 'utf8');
    const stderr = readFileSync(errFile, 'utf8');
    if (result.error) throw result.error;
    return { status: result.status ?? 1, stdout, stderr };
  } finally {
    closeSync(outFd);
    closeSync(errFd);
    rmSync(dir, { recursive: true, force: true });
  }
}

function git(args) {
  return runCapture('git', ['-c', 'core.quotepath=false', ...args]);
}

function toRepoPath(path) {
  const rel = path.startsWith(ROOT) ? relative(ROOT, path) : path;
  return rel.split(sep).join('/');
}

function resolveBase(ref) {
  if (ref === 'HEAD') return 'HEAD';
  const mergeBase = git(['merge-base', ref, 'HEAD']);
  if (mergeBase.status === 0 && mergeBase.stdout.trim()) return mergeBase.stdout.trim();
  return ref;
}

// Map<仓库相对路径, Set<改动后的行号>>；未跟踪文件整体视为改动。
// only 有值时只看这些路径，避免 agent 每次写入都把仓库里其他脏文件一起查一遍。
function changedLines(base, only = []) {
  const args = ['diff', '--unified=0', '--no-color', base];
  if (only.length) args.push('--', ...only);
  const patch = git(args);
  if (patch.status !== 0) throw new Error(patch.stderr.trim() || `git diff ${base} 失败`);
  const map = new Map();
  let current = null;
  for (const line of patch.stdout.split('\n')) {
    if (line.startsWith('+++ ')) {
      const target = line.slice(4).trim();
      current = target === '/dev/null' ? null : target.replace(/^b\//, '');
      if (current && !map.has(current)) map.set(current, new Set());
      continue;
    }
    const hunk = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/.exec(line);
    if (!hunk || !current) continue;
    const start = Number(hunk[1]);
    const count = hunk[2] === undefined ? 1 : Number(hunk[2]);
    const set = map.get(current);
    for (let i = 0; i < count; i++) set.add(start + i);
  }
  const allow = only.length ? new Set(only) : null;
  const untracked = git(['ls-files', '--others', '--exclude-standard', ...(only.length ? ['--', ...only] : [])]);
  if (untracked.status === 0) {
    for (const path of untracked.stdout.split('\n').filter(Boolean)) {
      if (allow && !allow.has(path)) continue;
      const text = safeRead(join(ROOT, path));
      if (text !== null) map.set(path, new Set(text.split('\n').map((_, index) => index + 1)));
    }
  }
  return map;
}

function safeRead(path) {
  try {
    return readFileSync(path, 'utf8');
  } catch {
    return null;
  }
}

function listFiles(dirs, matcher) {
  const out = [];
  const walk = (dir) => {
    let entries = [];
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      if (entry.name === 'node_modules' || entry.name === 'target' || entry.name.startsWith('.')) continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.isFile() && matcher.test(entry.name)) out.push(toRepoPath(full));
    }
  };
  for (const dir of dirs) walk(join(ROOT, dir));
  return out.sort();
}

function classpathJars() {
  const repo = process.env.MAVEN_REPO || join(process.env.USERPROFILE || homedir(), '.m2', 'repository');
  const artifacts = [
    ['com/puppycrawl/tools/checkstyle', 'checkstyle'],
    ['commons-logging/commons-logging', 'commons-logging'],
    ['info/picocli/picocli', 'picocli'],
    ['org/antlr/antlr4-runtime', 'antlr4-runtime'],
    ['commons-beanutils/commons-beanutils', 'commons-beanutils'],
    ['commons-collections/commons-collections', 'commons-collections'],
    ['com/google/guava/guava', 'guava'],
    ['org/reflections/reflections', 'reflections'],
    ['org/slf4j/slf4j-api', 'slf4j-api'],
    ['org/slf4j/slf4j-simple', 'slf4j-simple'],
    ['org/javassist/javassist', 'javassist'],
    ['net/sf/saxon/Saxon-HE', 'Saxon-HE'],
    ['com/google/guava/failureaccess', 'failureaccess']
  ];
  const version = (name) => {
    const match = /^(\d+(?:\.\d+)*)/.exec(name.replace(/^.*?-/, '').replace(/\.jar$/, ''));
    return match ? match[1].split('.').map(Number) : [0];
  };
  const compare = (left, right) => {
    const a = version(left);
    const b = version(right);
    for (let i = 0; i < Math.max(a.length, b.length); i++) {
      const diff = (a[i] ?? 0) - (b[i] ?? 0);
      if (diff !== 0) return diff;
    }
    return 0;
  };
  const jars = [];
  for (const [dir, artifact] of artifacts) {
    const base = join(repo, ...dir.split('/'));
    if (!existsSync(base)) return null;
    const stack = [base];
    const candidates = [];
    while (stack.length) {
      const current = stack.pop();
      for (const entry of readdirSync(current, { withFileTypes: true })) {
        if (entry.isDirectory()) stack.push(join(current, entry.name));
        else if (entry.name.startsWith(`${artifact}-`) && entry.name.endsWith('.jar')
          && !/-(sources|javadoc|tests)\.jar$/.test(entry.name)) candidates.push(join(current, entry.name));
      }
    }
    if (!candidates.length) return null;
    candidates.sort((a, b) => compare(a.split(sep).pop(), b.split(sep).pop()));
    jars.push(candidates.at(-1));
  }
  return jars;
}

function decodeXml(text) {
  return text
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'").replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)))
    .replace(/&amp;/g, '&');
}

// Checkstyle 14 需要 JDK 21+，直接拿 PATH 上的旧 JDK 会抛 LinkageError；这里先探测版本给出可执行的提示。
function javaBinary() {
  const candidate = process.env.JAVA_HOME
    ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const probe = runCapture(candidate, ['-version']);
  const reported = /version "(\d+)/.exec(`${probe.stdout}${probe.stderr}`)?.[1];
  if (!reported) throw new Error(`无法运行 java（${candidate}）：请把 JAVA_HOME 指向 JDK 21 或 25`);
  if (Number(reported) < 21) {
    throw new Error(`运行 Checkstyle 需要 JDK 21+，当前是 ${reported}：请把 JAVA_HOME 指向 JDK 21 或 25`);
  }
  return candidate;
}

// entries: [{ abs, path }]，abs 是磁盘上的真实路径，path 是仓库相对路径（用于输出）。
function lintJava(entries) {
  if (!entries.length) return [];
  const jars = classpathJars();
  if (!jars) {
    throw new Error('本地 Maven 仓库缺少 Checkstyle 依赖：先执行一次 ./mvnw verify 填充缓存，或用 MAVEN_REPO 指定仓库位置');
  }
  const javaBin = javaBinary();
  const classpath = jars.join(process.platform === 'win32' ? ';' : ':');
  const configDir = join(ROOT, 'config', 'java');
  const configs = ['checkstyle-strict.xml', 'checkstyle.xml'].filter((name) => existsSync(join(configDir, name)));
  const display = new Map(entries.map((entry) => [resolve(entry.abs), entry.path]));
  const findings = [];
  const emitted = new Set();
  // 命令行有长度上限（Windows 尤其明显），分批调用再合并报告，避免一次传几百个路径。
  const batchSize = process.platform === 'win32' ? 40 : 200;
  for (let offset = 0; offset < entries.length; offset += batchSize) {
    const batch = entries.slice(offset, offset + batchSize).map((entry) => entry.abs);
    const workDir = mkdtempSync(join(tmpdir(), 'pilot-checkstyle-'));
    const seen = new Set();
    for (const [index, configName] of configs.entries()) {
      const output = join(workDir, `report-${index}.xml`);
      const configPath = join(workDir, configName);
      writeFileSync(configPath, readFileSync(join(configDir, configName), 'utf8'), 'utf8');
      const args = ['-cp', classpath, 'com.puppycrawl.tools.checkstyle.Main', '-c', configPath,
        '-f', 'xml', '-o', output, ...batch];
      const result = runCapture(javaBin, args);
      if (!existsSync(output)) {
        throw new Error(`Checkstyle 执行失败（${configName}）：${result.stderr.trim() || `退出码 ${result.status}`}`);
      }
      for (const finding of parseCheckstyleReport(safeRead(output) ?? '', display, index === 0 ? seen : null)) {
        const key = `${finding.path}:${finding.line}:${finding.rule}`;
        if (emitted.has(key)) continue;
        emitted.add(key);
        findings.push(finding);
      }
    }
    // 检查器没返回结果的文件等于没检查过（例如带 BOM 的 UTF-8 会被静默跳过），必须显式报出来。
    for (const abs of batch) {
      if (!seen.has(resolve(abs))) {
        findings.push({ path: display.get(resolve(abs)) ?? toRepoPath(abs), line: 1, rule: 'unparsed-file', detail: HINTS['unparsed-file'] });
      }
    }
    rmSync(workDir, { recursive: true, force: true });
  }
  return findings;
}

function parseCheckstyleReport(xml, display, seen) {
  const findings = [];
  const fileBlocks = /<file name="([^"]+)">([\s\S]*?)<\/file>/g;
  let block;
  while ((block = fileBlocks.exec(xml)) !== null) {
    const abs = resolve(decodeXml(block[1]));
    seen?.add(abs);
    const path = display.get(abs) ?? toRepoPath(abs);
    const errorTags = /<error\b([^>]*)\/>/g;
    let tag;
    while ((tag = errorTags.exec(block[2])) !== null) {
      const attrs = tag[1];
      const line = Number(/\bline="(\d+)"/.exec(attrs)?.[1] ?? 0);
      const message = decodeXml(/\bmessage="([^"]*)"/.exec(attrs)?.[1] ?? '');
      const source = /\bsource="([^"]*)"/.exec(attrs)?.[1] ?? '';
      const module = source.split('.').pop().replace(/Check$/, '');
      const rule = CHECKSTYLE_RULE_IDS.get(module) ?? module;
      if (line > 0) findings.push({ path, line, rule, detail: HINTS[rule] ? `${HINTS[rule]}（检测到：${message}）` : message });
    }
  }
  return findings;
}

function loadTypeScript() {
  const candidates = [
    join(ROOT, 'pilot-web', 'frontend', 'node_modules', 'typescript', 'lib', 'typescript.js'),
    join(ROOT, 'pilot-web', 'node_modules', 'typescript', 'lib', 'typescript.js')
  ];
  const found = candidates.find((path) => existsSync(path));
  if (!found) throw new Error('未找到前端 node_modules/typescript：先执行 npm --prefix pilot-web/frontend ci');
  return createRequire(import.meta.url)(found);
}

// entries: [{ path, text }]。这三条规则都是编译器类型检查覆盖不到的「写法」问题。
function lintTs(entries) {
  if (!entries.length) return [];
  const ts = loadTypeScript();
  const findings = [];
  for (const entry of entries) {
    const source = ts.createSourceFile(entry.path, entry.text, ts.ScriptTarget.Latest, true,
      entry.path.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
    // 语法错误会让 AST 缺节点、后面的规则全部失效，必须显式报出来而不是安静通过。
    for (const diagnostic of source.parseDiagnostics ?? []) {
      const start = source.getLineAndCharacterOfPosition(diagnostic.start ?? 0);
      findings.push({ path: entry.path, line: start.line + 1, rule: 'parse-error',
        detail: `${HINTS['parse-error']}（${ts.flattenDiagnosticMessageText(diagnostic.messageText, ' ')}）` });
    }
    const add = (node, rule) => {
      const { line } = source.getLineAndCharacterOfPosition(node.getStart(source));
      findings.push({ path: entry.path, line: line + 1, rule, detail: HINTS[rule] });
    };
    const visit = (node) => {
      if (ts.isConditionalExpression(node)) {
        let parent = node.parent;
        while (parent && (ts.isParenthesizedExpression(parent) || ts.isAsExpression(parent))) parent = parent.parent;
        if (parent && ts.isConditionalExpression(parent)) add(node, 'nested-ternary');
      }
      if (node.kind === ts.SyntaxKind.AnyKeyword) add(node, 'explicit-any');
      if (ts.isCatchClause(node) && node.block.statements.length === 0) add(node, 'empty-catch');
      ts.forEachChild(node, visit);
    };
    visit(source);
  }
  return findings;
}

function layerFindings(entries) {
  const findings = [];
  for (const entry of entries) {
    if (!JAVA_EXT.test(entry.path)) continue;
    const pkg = /^\s*package\s+([\w.]+)\s*;/m.exec(entry.text)?.[1] ?? '';
    if (!pkg) continue;
    const rules = LAYER_RULES.filter((rule) => pkg === rule.from || pkg.startsWith(`${rule.from}.`));
    if (!rules.length) continue;
    entry.text.split('\n').forEach((line, index) => {
      const imported = /^\s*import\s+(?:static\s+)?([\w.]+)\s*;/.exec(line)?.[1];
      if (!imported) return;
      for (const rule of rules) {
        if (imported === rule.to || imported.startsWith(`${rule.to}.`)) {
          findings.push({ path: entry.path, line: index + 1, rule: 'layer-direction', detail: rule.message });
        }
      }
    });
  }
  return findings;
}

function suppressionFindings(entries) {
  const findings = [];
  for (const entry of entries) {
    entry.text.split('\n').forEach((line, index) => {
      if (BROAD_SUPPRESSION.test(line)) {
        findings.push({ path: entry.path, line: index + 1, rule: 'broad-suppression', detail: HINTS['broad-suppression'] });
      }
    });
  }
  return findings;
}

// Checkstyle 遇到带 BOM 的 UTF-8 会把文件解析成空结果，XML 里仍然有 <file> 节点但是没有 error，
// 表现就是「检查通过但什么都没查」。这类静默失效只能靠读字节识别，不能靠报告。
function bomFindings(entries) {
  return entries
    .filter((entry) => entry.text.charCodeAt(0) === 0xFEFF)
    .map((entry) => ({ path: entry.path, line: 1, rule: 'bom-encoding', detail: HINTS['bom-encoding'] }));
}

// 用「规则 + 归一化后的整行文本」做指纹：行号会随无关改动漂移，文本不会。
function keyOf(finding, text) {
  const normalized = (text.split('\n')[finding.line - 1] ?? '').replace(/\s+/g, ' ').trim();
  return `${finding.rule}|${normalized}`;
}

function lintAll(entries, options) {
  const javaEntries = options.java ? entries.filter((entry) => JAVA_EXT.test(entry.path)) : [];
  const tsEntries = options.ts ? entries.filter((entry) => TS_EXT.test(entry.path)) : [];
  return [
    ...lintJava(javaEntries.map((entry) => ({ abs: join(ROOT, entry.path), path: entry.path }))),
    ...lintTs(tsEntries),
    ...layerFindings([...javaEntries, ...tsEntries]),
    ...suppressionFindings([...javaEntries, ...tsEntries]),
    ...bomFindings([...javaEntries, ...tsEntries])
  ];
}

function render(findings, limit) {
  for (const finding of findings.slice(0, limit)) console.log(`${finding.path}:${finding.line} [${finding.rule}] ${finding.detail}`);
  if (findings.length > limit) console.log(`… 还有 ${findings.length - limit} 处未显示，用 --max-findings 调整`);
}

function scanAll(options) {
  const paths = [];
  if (options.java) paths.push(...listFiles(JAVA_DIRS, JAVA_EXT));
  if (options.ts) paths.push(...listFiles(TS_DIRS, TS_EXT));
  const entries = paths.map((path) => ({ path, text: safeRead(join(ROOT, path)) ?? '' })).filter((entry) => entry.text);
  return { entries, findings: lintAll(entries, options) };
}

function scanChanged(options) {
  const base = resolveBase(options.base);
  const lines = changedLines(base, options.files);
  const allow = options.files.length ? new Set(options.files) : null;
  const paths = [...lines.keys()].filter((path) => (!allow || allow.has(path))
    && ((options.java && JAVA_EXT.test(path)) || (options.ts && TS_EXT.test(path))));
  const entries = paths.map((path) => ({ path, text: safeRead(join(ROOT, path)) })).filter((entry) => entry.text !== null);

  // 基线版本的内容写到临时目录里检查，用同一套规则算出「本来就有的问题」。
  const baseRoot = join(mkdtempSync(join(tmpdir(), 'pilot-base-')), 'tree');
  const baseEntries = [];
  for (const entry of entries) {
    const stored = git(['show', `${base}:${entry.path}`]);
    if (stored.status !== 0) continue;
    const target = join(baseRoot, ...entry.path.split('/'));
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, stored.stdout, 'utf8');
    baseEntries.push({ path: entry.path, text: stored.stdout, abs: target });
  }
  const baseText = new Map(baseEntries.map((entry) => [entry.path, entry.text]));
  const known = new Set();
  const baseFindings = [
    ...lintJava(baseEntries.map((entry) => ({ abs: entry.abs, path: entry.path }))),
    ...lintTs(baseEntries),
    ...layerFindings(baseEntries),
    ...suppressionFindings(baseEntries),
    ...bomFindings(baseEntries)
  ];
  for (const finding of baseFindings) known.add(`${finding.path}|${keyOf(finding, baseText.get(finding.path) ?? '')}`);

  const current = lintAll(entries, options);
  const currentText = new Map(entries.map((entry) => [entry.path, entry.text]));
  const introduced = current.filter((finding) => lines.get(finding.path)?.has(finding.line)
    && !known.has(`${finding.path}|${keyOf(finding, currentText.get(finding.path) ?? '')}`));
  const preexisting = current.length - introduced.length;
  return { base, entries, introduced, preexisting };
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.mode === 'all') {
    const { entries, findings } = scanAll(options);
    const javaCount = entries.filter((entry) => JAVA_EXT.test(entry.path)).length;
    console.log(`[quality-gate] 全量：Java ${javaCount} / TS ${entries.length - javaCount} 个文件，违例 ${findings.length} 处`);
    render(findings, options.maxFindings);
    process.exitCode = findings.length ? 1 : 0;
    return;
  }
  const result = scanChanged(options);
  const javaCount = result.entries.filter((entry) => JAVA_EXT.test(entry.path)).length;
  console.log(`[quality-gate] 改动 ${result.entries.length} 个文件（Java ${javaCount} / TS ${result.entries.length - javaCount}），基线 ${result.base}`);
  if (result.introduced.length) render(result.introduced, options.maxFindings);
  console.log(result.introduced.length
    ? `[quality-gate] 新增违例 ${result.introduced.length} 处，必须先修掉（基线版本已有 ${result.preexisting} 处不计）`
    : `[quality-gate] 通过：新增违例 0 处（基线版本已有 ${result.preexisting} 处不计）`);
  process.exitCode = result.introduced.length ? 1 : 0;
}

// 只有直接执行时才跑；被质量同步脚本 import 时只导出规则声明，不产生副作用。
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (error) {
    // 门禁自身出错必须和「有违例」区分开：退出码 2 表示工具跑不起来，不是代码有问题。
    console.error(`[quality-gate] ${error.message}`);
    process.exitCode = 2;
  }
}