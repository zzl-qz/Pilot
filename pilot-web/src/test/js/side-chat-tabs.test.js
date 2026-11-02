import test from 'node:test';
import assert from 'node:assert/strict';
import {
  readSideChats, writeSideChats, quoteMarkdown, appendQuote,
  showSidePieceNav, messagePeek, simplifyTranscript, reduceSideChat, lastEventSequence
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

test('side piece nav hides while the sidebar is open', () => {
  assert.equal(showSidePieceNav(true), false);
  assert.equal(showSidePieceNav(false), true);
});

test('long user text becomes a four-line peek and short text stays open', () => {
  const short = messagePeek('只要一行');
  assert.equal(short.collapsed, false);
  assert.equal(short.preview, '只要一行');

  const long = messagePeek(['第一行', '第二行', '第三行', '第四行', '第五行被收起'].join('\n'));
  assert.equal(long.collapsed, true);
  assert.equal(long.preview.split('\n').length, 4);
  assert.equal(long.preview.includes('第五行'), false);
});

test('side chat keeps only the user line and the reply, once each', () => {
  const messages = simplifyTranscript([
    { type: 'user.message', sequence: 1, payload: { input: '帮我看一下' } },
    { type: 'run.started', sequence: 2, payload: { input: '帮我看一下' } },
    { type: 'tool.started', sequence: 3, payload: { name: 'read_file' } },
    { type: 'assistant.delta', sequence: 4, payload: { text: '看过了' } },
    { type: 'assistant.delta', sequence: 5, payload: { text: '，没有问题。' } },
    { type: 'run.completed', sequence: 6, payload: {} }
  ]);
  assert.deepEqual(messages, [
    { role: 'user', text: '帮我看一下' },
    { role: 'assistant', text: '看过了，没有问题。' }
  ]);
  assert.equal(lastEventSequence(messages) , 0);
  assert.equal(lastEventSequence([{ sequence: 4 }, { sequence: 9 }]), 9);
});

test('a live preview does not get glued onto the saved reply', () => {
  const started = reduceSideChat({ messages: [], live: '' }, {
    type: 'assistant.preview', payload: { text: '还在想' }
  });
  assert.equal(started.live, '还在想');
  assert.deepEqual(started.messages, []);
  const saved = reduceSideChat(started, { type: 'assistant.delta', payload: { text: '想好了' } });
  assert.equal(saved.live, '');
  assert.equal(saved.messages[0].text, '想好了');
});
