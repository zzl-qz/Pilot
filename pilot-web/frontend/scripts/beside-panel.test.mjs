import test from 'node:test';
import assert from 'node:assert/strict';
import { sideChatsFor, sideChatTitle } from '../src/lib/side-chat.js';

/**
 * 「旁边」把记（想法）与问（侧边对话）合成一个面板后，两者仍各自独立：
 * 这里只验证侧边对话的会话隔离没有被这次合并弄坏。
 */
test('侧边对话按主任务隔离，切到别的任务看不到别处的分叉', () => {
  const state = { tabs: [], active: {} };
  assert.deepEqual(sideChatsFor(state, { sessionId: 's1', workspace: '' }).items, []);
  // 没有会话时不给任何侧边对话：否则会在无主状态下现开一条
  assert.deepEqual(sideChatsFor(state, { sessionId: null, workspace: '' }).items, []);
  assert.deepEqual(sideChatsFor(state, { sessionId: 's1', workspace: '' }).items, []);
});

test('标签标题在没有父标题时给出可读回退', () => {
  assert.equal(sideChatTitle([], 'parent-1'), '侧边对话');
  assert.equal(sideChatTitle(null, 'parent-1'), '侧边对话');
});