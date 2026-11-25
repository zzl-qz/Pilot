#!/usr/bin/env node
/**
 * 把筛选结果（A/B 校验记录）转成任务集 tasks.json。
 *
 * 用法：
 *   node evaluation/replay/build-tasks.mjs --screen <screen.jsonl> --candidates <candidates.json> [--out tasks.json]
 *
 * 规则：
 *   - 只收 verdict=VALID 的候选；
 *   - 每个档位至少 2 个，超出部分按新增行数从小到大取（小任务优先，便宜）；
 *   - 断言失败（A 项在断言层面红）比编译失败更优，排前面。
 */
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('.', import.meta.url)).replace(/[\\/]$/, '');

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index > 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const screenPath = resolve(arg('screen'));
const candidatesPath = resolve(arg('candidates'));
const outPath = resolve(arg('out', join(HERE, 'tasks.json')));
const perTier = Number(arg('per-tier', '6'));

const screen = (await readFile(screenPath, 'utf8')).replace(/^\uFEFF/, '').split('\n').filter(Boolean).map(line => JSON.parse(line));
const candidates = JSON.parse((await readFile(candidatesPath, 'utf8')).replace(/^\uFEFF/, ''));
const bySha = new Map();
for (const item of candidates) bySha.set(item.sha, item);

const valid = new Map();
for (const record of screen) {
  if (record.verdict !== 'VALID') continue;
  const previous = valid.get(record.sha);
  // 同一提交出现多次时保留信息更全的一条
  if (!previous || (record.A && previous.A && record.A.tests >= previous.A.tests)) valid.set(record.sha, record);
}

const tiers = { S: [], M: [], L: [] };
for (const record of valid.values()) {
  const candidate = bySha.get(record.sha);
  if (!candidate) continue;
  const tier = record.tier;
  if (!tiers[tier]) continue;
  tiers[tier].push({
    record,
    candidate,
    // A 项在断言层面失败更可信；编译失败说明测试只能验证符号存在
    weak: Boolean(record.A?.compileError)
  });
}

const tasks = [];
for (const [tier, items] of Object.entries(tiers)) {
  items.sort((a, b) => (a.weak - b.weak) || (a.record.srcAdded - b.record.srcAdded));
  const chosen = items.slice(0, perTier);
  chosen.forEach((item, index) => {
    const { record, candidate, weak } = item;
    tasks.push({
      id: `${tier}-${String(index + 1).padStart(2, '0')}`,
      tier,
      commit: record.sha,
      parent: candidate.parent,
      subject: record.subject,
      module: record.module,
      mainFiles: record.mainFiles,
      srcAdded: record.srcAdded,
      testPaths: candidate.testPaths.split(';'),
      testClasses: record.classes.split(','),
      discriminatesBy: weak ? 'compile' : 'assertion',
      verified: {
        A: record.A?.compileError ? 'compile-fail' : 'assertion-fail',
        B: 'pass',
        testsA: record.A?.tests ?? null,
        failuresA: record.A?.failures ?? null,
        testsB: record.B?.tests ?? null,
        secondsA: record.A?.seconds ?? null,
        secondsB: record.B?.seconds ?? null
      }
    });
  });
}

const doc = {
  version: 1,
  generatedAt: new Date().toISOString(),
  source: {
    repo: 'https://github.com/zzl-qz/Pilot',
    screen: 'A = 父提交 + 隐藏用例必须失败；B = 父提交 + 参考解必须通过',
    candidates: candidates.length,
    valid: valid.size
  },
  tasks
};

await writeFile(outPath, JSON.stringify(doc, null, 2) + '\n', 'utf8');
const counts = tasks.reduce((acc, task) => ({ ...acc, [task.tier]: (acc[task.tier] ?? 0) + 1 }), {});
console.log(`写入 ${outPath}`);
console.log(`任务 ${tasks.length} 个：${JSON.stringify(counts)}（候选 ${candidates.length}，合格 ${valid.size}）`);
