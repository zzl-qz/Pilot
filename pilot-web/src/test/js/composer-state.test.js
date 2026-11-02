import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveComposerAction } from '../../../frontend/src/lib/composer-state.js';

test('运行中空草稿主按钮为停止', () => {
  const action = resolveComposerAction({ running: true, composerEnabled: true });
  assert.equal(action.mode, 'stop'); assert.equal(action.stop, true); assert.equal(action.disabled, false);
});
test('文字或附件均可排队；旧发送方式不改变默认行为', () => {
  for (const draft of [{hasText:true}, {attachmentCount:1}]) {
    assert.equal(resolveComposerAction({running:true, ...draft}).disabled, false);
  }
  assert.equal(resolveComposerAction({running:true, hasText:true, followUp:'steer'}).mode, 'queue');
});
test('请求进行中禁止重复发送', () => {
  for (const key of ['steering', 'pausing', 'cancelling']) {
    assert.equal(resolveComposerAction({running:true, hasText:true, [key]:true}).disabled, true);
  }
});
test('历史编辑优先；禁用输入和空草稿不能发送', () => {
  assert.equal(resolveComposerAction({running:true, editing:true, hasText:true}).mode, 'save');
  assert.equal(resolveComposerAction({composerEnabled:true}).disabled,true);
  assert.equal(resolveComposerAction({composerEnabled:false, hasText:true}).disabled,true);
});
