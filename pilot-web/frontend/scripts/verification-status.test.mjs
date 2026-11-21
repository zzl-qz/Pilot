import test from 'node:test';
import assert from 'node:assert/strict';
import { verificationNeedsAttention, verificationStatusDetail, verificationStatusLabel, verificationStatusTone, shortFingerprint, statusFromCode, reverifyPrompt, REVERIFY_PROMPT } from '../src/lib/verification/products.js';
import { verificationTone, verificationNeedsRerun } from '../src/lib/verification/run.js';

test('成果验证结论的三种状态各有独立文案与视觉，未知状态不冒充通过', () => {
  assert.equal(verificationStatusLabel('verified'), '已验证');
  assert.equal(verificationStatusLabel('stale'), '证据已过期');
  assert.equal(verificationStatusLabel('unverified'), '未验证');
  assert.equal(verificationStatusLabel('whatever'), '验证状态未知');
  assert.equal(verificationStatusLabel(null), '验证状态未知');

  // 「过期」和「未验证」必须是两种视觉：前者是真的有证据但作废了，更危险。
  assert.deepEqual(
    ['verified', 'stale', 'unverified', 'other'].map(verificationStatusTone),
    ['ok', 'stale', 'muted', 'muted']
  );
});

test('没有原因时给出与状态一致的默认解释，有原因时原样采用后端说明', () => {
  assert.match(verificationStatusDetail({ status: 'verified', reason: '' }), /仍覆盖当前文件内容/);
  assert.match(verificationStatusDetail({ status: 'stale', reason: '' }), /又被改动过/);
  assert.match(verificationStatusDetail({ status: 'unverified', reason: '' }), /还没有可用的校验证据/);
  assert.equal(verificationStatusDetail({ status: 'stale', reason: '证据针对的是旧产物，文件已经变化' }), '证据针对的是旧产物，文件已经变化');
  assert.match(verificationStatusDetail(null), /还没有可用的校验证据/);
});

test('指纹只展示前 8 位，缺失时返回空串而不是占位字符', () => {
  assert.equal(shortFingerprint('abcdef1234567890'), 'abcdef12');
  assert.equal(shortFingerprint('  abcd  '), 'abcd');
  assert.equal(shortFingerprint(''), '');
  assert.equal(shortFingerprint(null), '');
  assert.equal(shortFingerprint(undefined), '');
});

test('只有过期与未验证需要提示，已验证不额外打扰用户', () => {
  assert.equal(verificationNeedsAttention('stale'), true);
  assert.equal(verificationNeedsAttention('unverified'), true);
  assert.equal(verificationNeedsAttention('verified'), false);
  assert.equal(verificationNeedsAttention(null), false);
});

test('原因码决定状态，界面不靠匹配中文句子', () => {
  assert.equal(statusFromCode('ok'), 'verified');
  assert.equal(statusFromCode('stale_candidate'), 'stale');
  assert.equal(statusFromCode('failed'), 'unverified');
  assert.equal(statusFromCode('not_covered'), 'unverified');
  assert.equal(statusFromCode('storage_degraded'), 'unverified');
  // 旧接口只有 covered 布尔值时的降级路径
  assert.equal(statusFromCode(undefined, true), 'verified');
  assert.equal(statusFromCode(undefined, false), 'unverified');
  assert.equal(statusFromCode('未知代号', true), 'verified');
  assert.equal(statusFromCode(undefined, null), 'unverified', '拿不准时按未验证，不能冒充通过');
});

test('任务阶段的视觉档位把过期、失败、未验证分开', () => {
  assert.equal(verificationTone('CHECKS_PASSED'), 'ok');
  assert.equal(verificationTone('CHECKS_STALE'), 'stale');
  assert.equal(verificationTone('CHECKS_FAILED'), 'error');
  assert.equal(verificationTone('UNVERIFIED'), 'muted');
  assert.equal(verificationTone(null), 'muted');
  assert.notEqual(verificationTone('CHECKS_STALE'), verificationTone('UNVERIFIED'));
});

test('重新验证只对需要重跑的阶段开放，并且不冒充重放旧命令', () => {
  assert.equal(verificationNeedsRerun('CHECKS_STALE'), true);
  assert.equal(verificationNeedsRerun('CHECKS_FAILED'), true);
  assert.equal(verificationNeedsRerun('UNVERIFIED'), true);
  assert.equal(verificationNeedsRerun('CHECKS_PASSED'), false);
  assert.equal(verificationNeedsRerun(null), false);

  assert.equal(reverifyPrompt(''), REVERIFY_PROMPT);
  assert.match(reverifyPrompt('证据针对的是旧产物'), /上次结论失效原因：证据针对的是旧产物/);
  // 指令必须要求重新跑命令，而不是复述旧结论
  assert.match(REVERIFY_PROMPT, /重新运行验证/);
  assert.match(REVERIFY_PROMPT, /不要把之前的结论当作这次的结果/);
});
