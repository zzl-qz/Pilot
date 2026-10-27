import test from 'node:test';
import assert from 'node:assert/strict';

import { applyRule, classifyRunFailure, planProgressLabel } from '../../main/resources/static/lib/events.js';

test('applyRule 推进游标并应用新事件', () => {
  assert.deepEqual(applyRule({ type: 'tool.started', sequence: 5 }, 4), { apply: true, cursor: 5 });
});

test('applyRule 丢掉旧连接重复推送的事件', () => {
  assert.deepEqual(applyRule({ type: 'tool.started', sequence: 3 }, 5), { apply: false, cursor: 5 });
  assert.deepEqual(applyRule({ type: 'tool.started', sequence: 5 }, 5), { apply: false, cursor: 5 });
});

test('applyRule 不推进 assistant.preview 的游标', () => {
  // 预览沿用上一条落库事件的序号：如果推进，后面的正式事件会被当成重复丢掉
  assert.deepEqual(applyRule({ type: 'assistant.preview', sequence: 9 }, 3), { apply: false, cursor: 3 });
});

test('applyRule 允许没有序号的事件通过且不改游标', () => {
  assert.deepEqual(applyRule({ type: 'local.echo' }, 7), { apply: true, cursor: 7 });
});

test('applyRule 容忍非数字游标与空事件', () => {
  assert.deepEqual(applyRule({ type: 'x', sequence: 1 }, undefined), { apply: true, cursor: 1 });
  assert.deepEqual(applyRule(null, 2), { apply: false, cursor: 2 });
});

test('classifyRunFailure 区分取消、暂停、中断与真失败', () => {
  assert.deepEqual(classifyRunFailure('MAX_STEPS'), {
    cancelled: false, paused: true, interrupted: false, resumable: true
  });
  assert.deepEqual(classifyRunFailure('INTERRUPTED'), {
    cancelled: false, paused: false, interrupted: true, resumable: true
  });
  assert.equal(classifyRunFailure('CANCELLED').cancelled, true);
  assert.equal(classifyRunFailure('CANCELLED').resumable, false);
  assert.equal(classifyRunFailure('MODEL_ERROR').resumable, false);
  assert.equal(classifyRunFailure(undefined).resumable, false);
});

test('planProgressLabel 没有计划时不显示进度', () => {
  assert.equal(planProgressLabel(0, 0), '');
  assert.equal(planProgressLabel(1, 3), ' · 计划 1/3');
  assert.equal(planProgressLabel(undefined, 2), ' · 计划 0/2');
});
