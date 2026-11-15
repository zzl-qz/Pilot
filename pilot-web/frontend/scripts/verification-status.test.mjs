import test from 'node:test';
import assert from 'node:assert/strict';
import { verificationNeedsAttention, verificationStatusDetail, verificationStatusLabel, verificationStatusTone, shortFingerprint } from '../src/lib/verification-status.js';

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