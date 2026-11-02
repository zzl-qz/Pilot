import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveComposerAction } from '../../../frontend/src/lib/composer-state.js';

test('主按钮只发送，运行中空草稿禁用，停止是独立动作', () => {
  const action = resolveComposerAction({ running: true, composerEnabled: true });
  assert.equal(action.mode, 'queue'); assert.equal(action.stop, false); assert.equal(action.disabled, true);
});
test('文字或附件均可排队；显式选择纠正', () => {
  for (const draft of [{hasText:true}, {attachmentCount:1}]) {
    assert.equal(resolveComposerAction({running:true, ...draft}).disabled, false);
  }
  assert.equal(resolveComposerAction({running:true, hasText:true, followUp:'steer'}).mode, 'steer');
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
