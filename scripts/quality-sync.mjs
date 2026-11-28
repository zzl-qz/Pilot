#!/usr/bin/env node
// 质量规则同步：config/quality/rules.json 是唯一事实源，本脚本把它校验一遍并生成各引擎配置。
// 加规则、改阈值只改 rules.json，然后 `--write` 生成；CI 用默认（--check）确认生成的配置没有漂移。
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { GATE_IMPLS } from './quality-gate.mjs';
import { isDirectRun } from './smoke-env.mjs';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const CATALOG_PATH = join(ROOT, 'config', 'quality', 'rules.json');
const CHECKSTYLE_PATH = join(ROOT, 'config', 'java', 'checkstyle-strict.xml');
const KINDS = ['structure', 'boundary', 'redundancy', 'architecture', 'hygiene'];
const KIND_LABELS = {
  structure: '结构臃肿',
  boundary: '边界与资源',
  redundancy: '冗余写法',
  architecture: '依赖与架构',
  hygiene: '工具链自检'
};
const ENGINES = ['checkstyle', 'quality-gate', 'semgrep', 'jscpd'];
// 声明了但还没接入实现的引擎：规则可以用它，但不能是 active，否则就是「说查但没人查」。
const UNWIRED_ENGINES = new Set(['semgrep', 'jscpd']);

function fail(message) {
  console.error(`[quality-sync] ${message}`);
  process.exit(1);
}

export function loadCatalog() {
  try {
    return JSON.parse(readFileSync(CATALOG_PATH, 'utf8'));
  } catch (error) {
    fail(`无法读取 ${CATALOG_PATH}：${error.message}`);
  }
  return null;
}

export function validate(catalog) {
  const problems = [];
  const ids = new Set();
  const checkstyleModules = new Map();
  const usedImpls = new Set();
  for (const [index, rule] of (catalog.rules ?? []).entries()) {
    const at = `rules[${index}]（${rule.id ?? '缺少 id'}）`;
    if (!rule.id || !/^[a-z][a-z0-9]*(-[a-z0-9]+)*$/.test(rule.id)) problems.push(`${at}：id 必须是 kebab-case`);
    else if (ids.has(rule.id)) problems.push(`${at}：id 重复`);
    ids.add(rule.id);
    for (const field of ['title', 'hint', 'kind', 'languages']) {
      if (rule[field] === undefined) problems.push(`${at}：缺少 ${field}`);
    }
    if (rule.kind && !KINDS.includes(rule.kind)) problems.push(`${at}：kind 只能是 ${KINDS.join(' / ')}`);
    if (rule.limit !== undefined && (!Number.isInteger(rule.limit) || rule.limit <= 0)) problems.push(`${at}：limit 必须是正整数`);
    const status = rule.status ?? 'active';
    const engines = rule.engines ?? [];
    if (status === 'active' && engines.length === 0) problems.push(`${at}：active 规则必须声明至少一个引擎`);
    if (status === 'planned') {
      if (engines.length) problems.push(`${at}：planned 规则不能声明引擎（否则等于假称已被检查）`);
      if (!rule.blockedBy) problems.push(`${at}：planned 规则必须写 blockedBy，说明卡在哪`);
    }
    if (status !== 'active' && status !== 'planned') problems.push(`${at}：status 只能是 active / planned`);
    for (const engine of engines) {
      if (!ENGINES.includes(engine.engine)) problems.push(`${at}：未知引擎 ${engine.engine}`);
      if (engine.engine === 'checkstyle') {
        if (!engine.module) problems.push(`${at}：checkstyle 引擎缺少 module`);
        const previous = checkstyleModules.get(engine.module);
        if (previous) problems.push(`${at}：checkstyle 模块 ${engine.module} 已被 ${previous} 使用`);
        checkstyleModules.set(engine.module, rule.id);
        if (rule.limit !== undefined && !engine.property) problems.push(`${at}：有 limit 却没说写进哪个 Checkstyle 属性`);
      }
      if (engine.engine === 'quality-gate') {
        if (!engine.impl) problems.push(`${at}：quality-gate 引擎缺少 impl`);
        else if (!GATE_IMPLS.has(engine.impl)) problems.push(`${at}：门禁里没有实现 ${engine.impl}`);
        else usedImpls.add(engine.impl);
      }
      if (UNWIRED_ENGINES.has(engine.engine) && status === 'active') {
        problems.push(`${at}：${engine.engine} 尚未接入，不能标成 active`);
      }
    }
  }
  for (const impl of GATE_IMPLS) {
    if (!usedImpls.has(impl)) problems.push(`门禁实现了 ${impl}，但 rules.json 里没有规则使用它（死代码）`);
  }
  return problems;
}

function escapeXml(value) {
  return String(value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function xmlComment(text) {
  // XML 注释里不允许出现连续两个连字符，含命令行参数时必须先消掉，否则整份配置解析失败。
  return String(text).replace(/-{2,}/g, '-');
}

export function renderCheckstyle(catalog) {
  const rules = catalog.rules
    .filter((rule) => (rule.status ?? 'active') === 'active')
    .filter((rule) => (rule.engines ?? []).some((engine) => engine.engine === 'checkstyle'));
  const lines = [
    '<?xml version="1.0"?>',
    '<!DOCTYPE module PUBLIC "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN" "https://checkstyle.org/dtds/configuration_1_3.dtd">',
    '<!--',
    '  本文件由 config/quality/rules.json 生成，请勿手改。',
    '  改规则或阈值：编辑 rules.json，然后执行 node scripts/quality-sync.mjs 加 write 参数重新生成。',
    '  与 checkstyle.xml 的分工：checkstyle.xml 是形式规范，绑定 mvn verify、必须全绿；',
    '  本文件是语义坏味道规则，由 scripts/quality-gate.mjs 执行，配合基线只卡新增代码。',
    '-->',
    '<module name="Checker">',
    '    <property name="charset" value="UTF-8"/>',
    '    <module name="FileTabCharacter"/>',
    '    <module name="TreeWalker">'
  ];
  for (const kind of KINDS) {
    const group = rules.filter((rule) => rule.kind === kind);
    if (!group.length) continue;
    lines.push(`        <!-- ${kind}：${KIND_LABELS[kind]} -->`);
    for (const rule of group) {
      if (rule.note) lines.push(`        <!-- ${escapeXml(xmlComment(rule.note))} -->`);
      for (const engine of rule.engines) {
        if (engine.engine !== 'checkstyle') continue;
        const properties = { ...(engine.properties ?? {}) };
        if (rule.limit !== undefined && engine.property) properties[engine.property] = rule.limit;
        if (!Object.keys(properties).length) {
          lines.push(`        <module name="${engine.module}"/>`);
          continue;
        }
        lines.push(`        <module name="${engine.module}">`);
        for (const [name, value] of Object.entries(properties)) {
          lines.push(`            <property name="${name}" value="${escapeXml(value)}"/>`);
        }
        lines.push('        </module>');
      }
    }
  }
  lines.push('    </module>', '</module>', '');
  return lines.join('\n');
}

function listRules(catalog) {
  console.log(`共 ${catalog.rules.length} 条规则（语言：${catalog.languages.join(' / ')}）`);
  for (const kind of KINDS) {
    const group = catalog.rules.filter((rule) => rule.kind === kind);
    if (!group.length) continue;
    console.log(`\n${kind}：${KIND_LABELS[kind]}`);
    for (const rule of group) {
      const status = rule.status ?? 'active';
      const limit = rule.limit === undefined ? '' : ` ≤ ${rule.limit}${rule.unit ?? ''}`;
      const engines = (rule.engines ?? []).map((engine) => engine.engine).join('+') || '（未接入）';
      console.log(`  ${rule.id.padEnd(30)} ${rule.languages.join(',').padEnd(12)} ${status.padEnd(8)} ${engines.padEnd(14)} ${rule.title}${limit}`);
    }
  }
}

function main() {
  const args = process.argv.slice(2);
  const write = args.includes('--write');
  const list = args.includes('--list');
  const catalog = loadCatalog();
  const problems = validate(catalog);
  if (problems.length) {
    for (const problem of problems) console.error(`[quality-sync] ${problem}`);
    process.exit(1);
  }
  if (list) {
    listRules(catalog);
    return;
  }
  const generated = [
    { path: CHECKSTYLE_PATH, content: renderCheckstyle(catalog), label: 'Checkstyle 配置' }
  ];
  if (write) {
    for (const artifact of generated) {
      mkdirSync(dirname(artifact.path), { recursive: true });
      writeFileSync(artifact.path, artifact.content, 'utf8');
    }
    const count = catalog.rules.filter((rule) => (rule.engines ?? []).some((engine) => engine.engine === 'checkstyle')).length;
    console.log(`[quality-sync] 已生成 ${generated.length} 个文件（其中 Checkstyle 规则 ${count} 条）`);
    for (const artifact of generated) console.log(`  - ${artifact.path.replace(ROOT, '').replace(/\\/g, '/')}`);
    return;
  }
  for (const artifact of generated) {
    let current = '';
    try {
      current = readFileSync(artifact.path, 'utf8');
    } catch {
      fail(`${artifact.path} 不存在，先执行 node scripts/quality-sync.mjs --write`);
    }
    if (current.replace(/\r\n/g, '\n') !== artifact.content) {
      fail(`${artifact.label}与仓库里的文件不一致：改过 rules.json 却忘了 --write，或有人手改了生成文件`);
    }
  }
  console.log('[quality-sync] 规则目录与生成的配置一致');
}

// 只有直接执行时才跑；被测试 import 时只导出校验和生成函数，不产生副作用。
if (isDirectRun(import.meta.url)) {
  main();
}