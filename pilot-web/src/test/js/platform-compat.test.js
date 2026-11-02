import test from 'node:test';
import assert from 'node:assert/strict';
import { platformModifier, isShortcutModifier } from '../../../frontend/src/lib/interaction.js';
import { attachmentId } from '../../../frontend/src/lib/attachment-input.js';

test('快捷键标识覆盖三平台及优先使用的 Client Hints', () => {
  for (const platform of ['MacIntel', 'Macintosh']) assert.equal(platformModifier({ platform }), '⌘');
  for (const platform of ['Win32', 'Win64', 'Linux x86_64', '']) assert.equal(platformModifier({ platform }), 'Ctrl');
  assert.equal(platformModifier({ platform: 'Win32', userAgentData: { platform: 'macOS' } }), '⌘');
  assert.equal(platformModifier({}), 'Ctrl');
});

test('Ctrl/Cmd 可以触发动作，AltGr 和中文输入候选键不能触发', () => {
  assert.equal(isShortcutModifier({ ctrlKey: true }), true);
  assert.equal(isShortcutModifier({ metaKey: true }), true);
  assert.equal(isShortcutModifier({ ctrlKey: true, altKey: true, getModifierState: key => key === 'AltGraph' }), false);
  assert.equal(isShortcutModifier({ metaKey: true, isComposing: true }), false);
  assert.equal(isShortcutModifier({ ctrlKey: true, keyCode: 229 }), false);
  assert.equal(isShortcutModifier({ altKey: true }), false);
});

test('附件编号兼容缺少 randomUUID、安全 API 被禁用及无 crypto 的 WebView', () => {
  assert.equal(attachmentId({ randomUUID: () => 'uuid' }), 'uuid');
  assert.equal(attachmentId({ getRandomValues: bytes => bytes.fill(15) }), 'attachment-' + '0f'.repeat(16));
  const restricted = { randomUUID() { throw Error('denied'); } };
  const ids = [attachmentId(restricted), attachmentId(null), attachmentId(null)];
  assert.equal(new Set(ids).size, ids.length);
  assert.ok(ids.every(value => value.startsWith('attachment-')));
});
