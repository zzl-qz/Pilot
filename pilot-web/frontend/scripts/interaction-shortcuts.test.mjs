import assert from 'node:assert/strict';
import test from 'node:test';
import { isShortcutModifier, isModalBlockingShortcuts, isSideChatShortcut, shouldReorderQueueOnArrow } from '../src/lib/interaction.ts';
import { filterPaletteActions } from '../src/lib/commands.ts';

test('shortcut modifier follows platform conventions and excludes mixed/IME modifiers', () => {
  assert.equal(isShortcutModifier({ metaKey: true, ctrlKey: false }, 'MacIntel'), true);
  assert.equal(isShortcutModifier({ metaKey: false, ctrlKey: true }, 'Linux x86_64'), true);
  assert.equal(isShortcutModifier({ metaKey: false, ctrlKey: true }, 'MacIntel'), false);
  assert.equal(isShortcutModifier({ metaKey: true, ctrlKey: false }, 'Win32'), false);
  assert.equal(isShortcutModifier({ metaKey: true, ctrlKey: true }, 'MacIntel'), false);
  assert.equal(isShortcutModifier({ metaKey: true, ctrlKey: false, isComposing: true }, 'MacIntel'), false);
});

test('side chat shortcut uses physical KeyS regardless of Option-produced key value', () => {
  assert.equal(isSideChatShortcut({ code: 'KeyS', altKey: true, metaKey: true }), true);
  assert.equal(isSideChatShortcut({ code: 'KeyS', altKey: true, ctrlKey: true }), true);
  assert.equal(isSideChatShortcut({ code: 'KeyS', altKey: false, metaKey: true }), false);
  assert.equal(isSideChatShortcut({ code: 'KeyA', altKey: true, metaKey: true }), false);
});

test('modal blocking recognizes supported modal selectors', () => {
  for (const match of ['dialog[open]', '[role="dialog"][aria-modal="true"]', '.modal']) {
    assert.equal(isModalBlockingShortcuts({ querySelector: selector => selector.includes(match) ? {} : null }), true);
  }
  assert.equal(isModalBlockingShortcuts({ querySelector: () => null }), false);
});

test('queue arrow reorder is limited to item buttons or Alt-modified arrows', () => {
  assert.equal(shouldReorderQueueOnArrow({ key: 'ArrowUp', targetTagName: 'TEXTAREA', altKey: false }), false);
  assert.equal(shouldReorderQueueOnArrow({ key: 'ArrowDown', targetTagName: 'TEXTAREA', altKey: true }), true);
  assert.equal(shouldReorderQueueOnArrow({ key: 'ArrowUp', targetTagName: 'BUTTON', altKey: false }), true);
  assert.equal(shouldReorderQueueOnArrow({ key: 'Enter', targetTagName: 'BUTTON', altKey: false }), false);
});

test('command palette context action targets the usage panel', () => {
  const action = filterPaletteActions('context').find(item => item.action === 'open-context');
  assert.ok(action);
  assert.equal(action.action, 'open-context');
});
