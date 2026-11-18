// 规则目录一致性测试：rules.json、生成的 Checkstyle 配置、门禁实现三边必须互相咬合。
// 这些断言的意义是防止「文档说 80、XML 写 120、门禁实际不查」这种静默漂移。
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadCatalog, validate, renderCheckstyle } from './quality-sync.mjs';
import { GATE_IMPLS } from './quality-gate.mjs';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const catalog = loadCatalog();
const activeRules = catalog.rules.filter((rule) => (rule.status ?? 'active') === 'active');

test('规则目录自身没有结构问题', () => {
  assert.deepEqual(validate(catalog), []);
});

test('提交的 Checkstyle 配置与 rules.json 一致', () => {
  const committed = readFileSync(join(root, 'config', 'java', 'checkstyle-strict.xml'), 'utf8').replace(/\r\n/g, '\n');
  assert.equal(committed, renderCheckstyle(catalog),
    '生成文件与目录不一致：改过 rules.json 后要执行 node scripts/quality-sync.mjs --write');
});

test('每条 active 的 Java 规则都在生成的配置里出现恰好一次', () => {
  const xml = renderCheckstyle(catalog);
  const modules = [...xml.matchAll(/<module name="([A-Za-z]+)"\/?>| {10}<module name="([A-Za-z]+)">/g)]
    .map((match) => match[1] ?? match[2]);
  for (const rule of activeRules) {
    for (const engine of rule.engines ?? []) {
      if (engine.engine !== 'checkstyle') continue;
      const hits = modules.filter((name) => name === engine.module).length;
      assert.equal(hits, 1, `${rule.id} 的 ${engine.module} 应出现一次，实际 ${hits} 次`);
    }
  }
});

test('生成配置里的阈值与目录声明一致', () => {
  const xml = renderCheckstyle(catalog);
  for (const rule of activeRules) {
    if (rule.limit === undefined) continue;
    for (const engine of rule.engines ?? []) {
      if (engine.engine !== 'checkstyle' || !engine.property) continue;
      const block = new RegExp(`<module name="${engine.module}">([\\s\\S]*?)</module>`).exec(xml);
      assert.ok(block, `${engine.module} 不在生成的配置里`);
      assert.match(block[1], new RegExp(`<property name="${engine.property}" value="${rule.limit}"/>`),
        `${rule.id} 的阈值 ${rule.limit} 没有写进 ${engine.module}.${engine.property}`);
    }
  }
});

test('未接入的规则必须标明卡在哪，且不能声明引擎', () => {
  for (const rule of catalog.rules.filter((item) => item.status === 'planned')) {
    assert.deepEqual(rule.engines ?? [], [], `${rule.id} 标了 planned 却声明了引擎`);
    assert.ok(rule.blockedBy, `${rule.id} 缺少 blockedBy`);
  }
});

test('门禁声明的实现都被目录使用，目录要求的实现也都存在', () => {
  const declared = new Set();
  for (const rule of activeRules) {
    for (const engine of rule.engines ?? []) {
      if (engine.engine === 'quality-gate') declared.add(engine.impl);
    }
  }
  for (const impl of declared) assert.ok(GATE_IMPLS.has(impl), `目录要求 ${impl}，但门禁没有实现`);
  for (const impl of GATE_IMPLS) assert.ok(declared.has(impl), `门禁实现了 ${impl}，但目录里没有规则用它`);
});

test('规则 id 全仓唯一且是 kebab-case', () => {
  const ids = catalog.rules.map((rule) => rule.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const id of ids) assert.match(id, /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/);
});