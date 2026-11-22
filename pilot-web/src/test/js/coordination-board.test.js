import test from 'node:test';
import assert from 'node:assert/strict';
import {
  claimsOwnedBy, connectionLabel, handoffStatusLabel, openFor, otherSessions, readBoard, requestStatusLabel,
  sessionStatusLabel
} from '../../../frontend/src/lib/coordination/board.js';

const claim = (id, sessionId, path) => ({
  id, projectId: 'p1', sessionId, path, kind: 'FILE',
  claimedAt: '2026-11-23T00:00:00Z', expiresAt: '2026-11-23T00:05:00Z'
});

const boardPayload = () => ({
  snapshot: {
    projectId: 'p1',
    sessions: [
      { sessionId: 's1', title: '主会话', status: 'RUNNING', workspace: '/repo', online: true, archived: false },
      { sessionId: 's2', title: '协作会话', status: 'WAITING_APPROVAL', workspace: '/repo/shared', online: false, archived: false }
    ],
    claims: [claim('c1', 's1', '/repo/a.ts'), claim('c2', 's2', '/repo/shared/b.ts')],
    requests: [
      { id: 'r1', fromSessionId: 's1', toSessionId: 's2', reason: '一起改接口', taskId: null, status: 'PENDING', createdAt: '', updatedAt: '' },
      { id: 'r2', fromSessionId: 's2', toSessionId: 's1', reason: '帮我看下日志', taskId: null, status: 'PENDING', createdAt: '', updatedAt: '' },
      { id: 'r3', fromSessionId: 's1', toSessionId: 's2', reason: '已处理', taskId: null, status: 'ACCEPTED', createdAt: '', updatedAt: '' }
    ],
    handoffs: [
      { id: 'h1', fromSessionId: 's2', toSessionId: 's1', taskId: 't1', summary: '接手构建', status: 'PENDING', createdAt: '', updatedAt: '', expiresAt: '' },
      { id: 'h2', fromSessionId: 's1', toSessionId: 's2', taskId: 't2', summary: '接手文档', status: 'PENDING', createdAt: '', updatedAt: '', expiresAt: '' }
    ]
  },
  overview: { workspace: '/repo', sessions: [], fileOwners: [], generatedAt: '2026-11-23T00:00:00Z' }
});

test('读协调看板：快照与概览同时保留', () => {
  const board = readBoard(boardPayload());
  assert.equal(board.snapshot.sessions.length, 2);
  assert.equal(board.snapshot.claims[0].id, 'c1');
  assert.equal(board.overview.workspace, '/repo');
});

test('旧服务端只返回概览时仍能显示会话，协调区块退化为空', () => {
  const legacy = {
    workspace: '/repo',
    sessions: [{ sessionId: 's1', title: '旧会话', running: true, pendingFiles: ['a.ts'], runningCommands: [], arbitrations: [], tasks: [] }],
    fileOwners: [{ path: 'a.ts', sessions: ['s1', 's2'] }],
    generatedAt: '2026-11-23T00:00:00Z'
  };
  const board = readBoard(legacy);
  assert.deepEqual(board.snapshot.sessions, []);
  assert.deepEqual(board.snapshot.claims, []);
  assert.equal(board.overview.fileOwners.length, 1);
  assert.equal(board.overview.sessions[0].title, '旧会话');
  assert.equal(readBoard(null), null);
  assert.equal(readBoard({ nope: true }), null);
});

test('待处理请求按方向筛选，只收 PENDING', () => {
  const board = readBoard(boardPayload());
  assert.deepEqual(openFor(board.snapshot.requests, 's1', 'incoming').map((item) => item.id), ['r2']);
  assert.deepEqual(openFor(board.snapshot.requests, 's1', 'outgoing').map((item) => item.id), ['r1']);
  assert.deepEqual(openFor(board.snapshot.handoffs, 's1', 'incoming').map((item) => item.id), ['h1']);
  assert.deepEqual(openFor(board.snapshot.handoffs, 's1', 'outgoing').map((item) => item.id), ['h2']);
});

test('占用只认自己声明的，其他人的占用只能查看', () => {
  const board = readBoard(boardPayload());
  assert.deepEqual(claimsOwnedBy(board.snapshot.claims, 's1').map((item) => item.id), ['c1']);
  assert.deepEqual(otherSessions(board.snapshot, 's1').map((item) => item.sessionId), ['s2']);
});

test('状态与连接文案覆盖各阶段与未知值', () => {
  assert.equal(sessionStatusLabel('WAITING_APPROVAL'), '等待批准');
  assert.equal(sessionStatusLabel('SOMETHING_NEW'), 'SOMETHING_NEW');
  assert.equal(sessionStatusLabel(undefined), '未知');
  assert.equal(requestStatusLabel('WITHDRAWN'), '已撤销');
  assert.equal(handoffStatusLabel('EXPIRED'), '已过期');
  assert.match(connectionLabel('live', 12, 0), /实时 · 游标 12/);
  assert.match(connectionLabel('reconnecting', 8, 3), /第 3 次/);
  assert.match(connectionLabel('stopped', 8, 0), /已停止/);
  assert.match(connectionLabel('idle', 0, 0), /尚未连接/);
});
