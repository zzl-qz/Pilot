import test from 'node:test';
import assert from 'node:assert/strict';
import {
  readSideChats, writeSideChats, sideChatFrameSrc, quoteMarkdown, appendQuote
} from '../../../frontend/src/lib/side-chat.js';

/** 最小的 localStorage 桩：侧边对话的标签页持久化只依赖这四个方法。 */
function stubStorage() {
  const map = new Map();
  return {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => map.set(key, String(value)),
    removeItem: (key) => map.delete(key),
    raw: map
  };
}

test('side chat tabs survive a reload and keep the active tab', () => {
  const storage = stubStorage();
  writeSideChats({
    activeId: 'side-2',
    items: [
      { id: 'side-1', title: 'Side chat', workspace: 'repo-a', projectId: null },
      { id: 'side-2', title: 'Side chat 2', workspace: 'repo-b', projectId: 'p1' }
    ]
  }, storage);

  const restored = readSideChats(storage);
  assert.equal(restored.activeId, 'side-2');
  assert.equal(restored.items.length, 2);
  assert.equal(restored.items[1].workspace, 'repo-b');
});

test('a missing active tab falls back to the first one instead of leaving the pane empty', () => {
  const storage = stubStorage();
  writeSideChats({
    activeId: 'gone',
    items: [{ id: 'side-1', title: 'Side chat', workspace: 'repo-a' }]
  }, storage);

  assert.equal(readSideChats(storage).activeId, 'side-1');
});

test('broken or partial payloads degrade to an empty list', () => {
  const storage = stubStorage();
  storage.setItem('pilot.side-chats', '{not json');
  assert.deepEqual(readSideChats(storage), { items: [], activeId: null });

  storage.setItem('pilot.side-chats', JSON.stringify({ items: [{ id: 'x' }, null], activeId: 'x' }));
  assert.deepEqual(readSideChats(storage), { items: [], activeId: null });
});

test('iframe src carries the session id and stays on the app root', () => {
  assert.equal(sideChatFrameSrc('ses_1'), '/?sideChat=1&session=ses_1');
});

test('quoted selection becomes a markdown block and appends after existing text', () => {
  assert.equal(quoteMarkdown('a\nb'), '> a\n> b');

  let text = '已经写了一半';
  appendQuote(() => text, (next) => { text += next; }, '选中的两行\n第二行');
  assert.equal(text, '已经写了一半\n\n> 选中的两行\n> 第二行\n\n');

  let empty = '';
  appendQuote(() => empty, (next) => { empty += next; }, 'x');
  assert.equal(empty, '> x\n\n');
});

test('blank selections are ignored', () => {
  let text = 'keep';
  assert.equal(appendQuote(() => text, (next) => { text = next; }, '   '), false);
  assert.equal(text, 'keep');
});
