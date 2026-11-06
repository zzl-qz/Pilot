import test from 'node:test';
import assert from 'node:assert/strict';
import { verificationLabel, verificationHint } from '../../../frontend/src/lib/verification.js';

test('执行结束、记录检查通过与目标核验通过保持不同含义', () => {
  assert.equal(verificationLabel('COMPLETED'), '执行已结束');
  assert.equal(verificationLabel('CHECKS_PASSED'), '记录的检查通过');
  assert.equal(verificationLabel('VERIFIED'), '目标核验通过');
  assert.match(verificationHint('CHECKS_PASSED'), /仍需/);
  assert.match(verificationHint('COMPLETED'), /没有足够/);
  assert.equal(verificationHint('VERIFIED'), '');
});

test('未知、缺失、过期和不可用的验证状态不能误报通过', () => {
  for (const value of [null, undefined, '', 'FUTURE_PHASE']) {
    assert.equal(verificationLabel(value), '验证状态未知');
    assert.match(verificationHint(value), /没有足够/);
  }
  assert.match(verificationHint('CHECKS_STALE'), /不能用于确认/);
  for (const value of ['VERIFICATION_FAILED', 'REVIEW_UNAVAILABLE']) assert.match(verificationHint(value), /仍需验证/);
});
