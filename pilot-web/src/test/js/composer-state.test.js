import test from 'node:test';
import assert from 'node:assert/strict';

import { resolveComposerAction } from '../../main/resources/static/lib/composer-state.js';

test('运行中且输入为空时主按钮切换为停止', () => {
  assert.deepEqual(resolveComposerAction({ running: true, composerEnabled: true }), {
    mode: 'stop',
    stop: true,
    disabled: false,
    label: '停止当前',
    ariaLabel: '停止当前任务',
    title: '停止当前任务 (Esc)'
  });
});

test('运行中输入文字后立即恢复成排队发送', () => {
  const action = resolveComposerAction({ running: true, hasText: true, composerEnabled: true });
  assert.equal(action.mode, 'queue');
  assert.equal(action.stop, false);
  assert.equal(action.disabled, false);
});

test('运行中只有附件也保持发送动作', () => {
  const action = resolveComposerAction({ running: true, attachmentCount: 1, composerEnabled: true });
  assert.equal(action.mode, 'queue');
  assert.equal(action.disabled, false);
});

test('编辑排队消息时不会误切换为停止', () => {
  const action = resolveComposerAction({ running: true, editing: true, composerEnabled: true });
  assert.equal(action.mode, 'save');
  assert.equal(action.stop, false);
  assert.equal(action.disabled, true);
});

test('空闲且没有草稿时发送按钮禁用', () => {
  const action = resolveComposerAction({ composerEnabled: true });
  assert.equal(action.mode, 'send');
  assert.equal(action.disabled, true);
});

test('停止请求进行中保持停止样式并防止重复点击', () => {
  const action = resolveComposerAction({ running: true, composerEnabled: true, cancelling: true });
  assert.equal(action.mode, 'stop');
  assert.equal(action.disabled, true);
  assert.equal(action.label, '正在停止');
});

test('暂停请求进行中不能同时触发停止', () => {
  const action = resolveComposerAction({ running: true, composerEnabled: true, pausing: true });
  assert.equal(action.mode, 'stop');
  assert.equal(action.disabled, true);
});
