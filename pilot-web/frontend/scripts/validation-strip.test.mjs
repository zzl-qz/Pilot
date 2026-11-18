import test from 'node:test';
import assert from 'node:assert/strict';
import { stripView } from '../src/lib/validation-strip.js';

const evidence = (over = {}) => ({
  id: 'val_1', sessionId: 's1', runId: 'r1', candidateHash: 'abc123def456',
  files: ['src/a.js', 'src/b.js'], checks: ['node --test → 通过'],
  status: 'passed', environmentFingerprint: 'win|amd64', source: 'run',
  recordedAt: '2026-11-16T10:00:00Z', ...over
});

const view = (report, recent = []) => ({ report, recent });

test('没跑过或还没加载时不显示，避免把空状态说成没有证据', () => {
  assert.equal(stripView(null, false).visible, false);
  assert.equal(stripView(undefined, true).visible, false, '未加载 ≠ 未验证');
  assert.equal(stripView(null, true).visible, false);
});

test('这一轮还在跑时不显示验证结论', () => {
  const sv = stripView(view({ current: false, code: 'no_evidence', reason: '还没有任何校验证据', latest: null }), true, true);
  assert.equal(sv.visible, false);
});

test('通过且有当前证据时显示已验证，不要求重新验证', () => {
  const sv = stripView(view({ current: true, code: 'ok', reason: '', latest: evidence() }), true);
  assert.equal(sv.visible, true);
  assert.equal(sv.label, '已验证');
  assert.equal(sv.tone, 'ok');
  assert.equal(sv.needsRerun, false);
  assert.match(sv.facts.join(' '), /node --test/);
});

test('证据过期要单独一档，并提示可以重新验证', () => {
  const sv = stripView(view({
    current: false, code: 'stale_candidate', reason: '最近一次校验针对的是旧产物，代码已变化', latest: evidence()
  }), true);
  assert.equal(sv.label, '证据已过期');
  assert.equal(sv.tone, 'stale');
  assert.equal(sv.needsRerun, true);
  assert.match(sv.detail, /旧产物/);
  // 与「未验证」必须是两种不同呈现
  const unverified = stripView(view({ current: false, code: 'no_evidence', reason: '', latest: null }), true);
  assert.notEqual(sv.tone, unverified.tone);
  assert.notEqual(sv.label, unverified.label);
});

test('校验被跳过时不说成没通过', () => {
  const skipped = stripView(view({ current: false, code: 'skipped', reason: '这一轮没有跑完校验', latest: evidence({ status: 'skipped', checks: [] }) }), true);
  assert.equal(skipped.label, '还没校验完');
  assert.equal(skipped.tone, 'muted');
  assert.equal(skipped.needsRerun, true);
  assert.doesNotMatch(skipped.detail, /没有通过/);

  // 旧账本偶发 failed + (skipped) 文案：按证据状态纠正，并清掉英文码
  const legacy = stripView(view({
    current: false, code: 'failed', reason: '最近一次校验没有通过 (skipped)',
    latest: evidence({ status: 'skipped', checks: [] })
  }), true);
  assert.equal(legacy.label, '还没校验完');
  assert.equal(legacy.tone, 'muted');
  assert.doesNotMatch(legacy.detail, /skipped/i);
});

test('校验失败与账本异常各有自己的说法', () => {
  const failed = stripView(view({ current: false, code: 'failed', reason: '', latest: evidence({ status: 'failed' }) }), true);
  assert.equal(failed.label, '校验未通过');
  assert.equal(failed.tone, 'error');

  const degraded = stripView(view({ current: false, code: 'storage_degraded', reason: '账本写入失败', latest: null }), true);
  assert.equal(degraded.label, '证据不可核对');
  assert.equal(degraded.needsRerun, false, '账本坏了，重跑也拿不到可信结论');
  assert.match(degraded.detail, /账本写入失败/);
});

test('没有证据时如实说未验证，并给出依据为空', () => {
  const sv = stripView(view({ current: false, code: 'no_evidence', reason: '还没有任何校验证据', latest: null }), true);
  assert.equal(sv.label, '未验证');
  assert.equal(sv.tone, 'muted');
  assert.equal(sv.needsRerun, true);
  assert.deepEqual(sv.facts, []);
});

test('依据里放可核对的东西：检查命令、覆盖文件数、记录时间', () => {
  const sv = stripView(view({ current: true, code: 'ok', reason: '', latest: evidence() }), true);
  const joined = sv.facts.join(' ');
  assert.match(joined, /node --test → 通过/);
  assert.match(joined, /覆盖文件 2 个/);
  assert.match(joined, /记录时间/);

  // 没有记录检查命令时要说出来，不能留空让人以为是「不需要检查」
  const noChecks = stripView(view({ current: true, code: 'ok', reason: '', latest: evidence({ checks: [] }) }), true);
  assert.match(noChecks.facts.join(' '), /没有记录任何检查命令/);

  // 文件很多时不铺满，截断并标省略
  const many = stripView(view({
    current: true, code: 'ok', reason: '',
    latest: evidence({ files: ['a', 'b', 'c', 'd', 'e', 'f', 'g'] })
  }), true);
  assert.match(many.facts.join(' '), /覆盖文件 7 个/);
  assert.match(many.facts.join(' '), /…/);
});