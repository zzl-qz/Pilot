import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveSteerEntry } from '../src/lib/composer-state.js';

test('只有运行中且有文字草稿时出现「纠正本轮」', () => {
  assert.equal(resolveSteerEntry({ running: true, hasText: true }).visible, true);
  assert.equal(resolveSteerEntry({ running: false, hasText: true }).visible, false);
  assert.equal(resolveSteerEntry({ running: true, hasText: false }).visible, false);
  assert.equal(resolveSteerEntry({ running: true, hasText: true, editing: true }).visible, false);
  assert.equal(resolveSteerEntry({ running: true, hasText: true, attachmentCount: 1 }).visible, false,
    '带附件仍走队列，纠正接口只收纯文本');
});

test('提交中与等待应用时禁用，文案对应状态', () => {
  assert.equal(resolveSteerEntry({ running: true, hasText: true, steering: true }).disabled, true);
  assert.match(resolveSteerEntry({ running: true, hasText: true, steering: true }).label, /正在提交/);
  assert.equal(resolveSteerEntry({ running: true, hasText: true, pending: true }).disabled, true);
  assert.match(resolveSteerEntry({ running: true, hasText: true, pending: true }).label, /等待应用/);
  assert.match(resolveSteerEntry({ running: true, hasText: true }).title, /不进入排队/);
});