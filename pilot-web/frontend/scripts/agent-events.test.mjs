import test from 'node:test';
import assert from 'node:assert/strict';
import { produce } from 'immer';
import { applyEvent, initialThread } from '../src/thread-reducer.js';
import { agentStatusLabel, summarizeAgents } from '../src/lib/agents.js';

// 使用真实 Immer 更新，普通可变对象会掩盖 thread.agents 与根 agents 的别名失效。
function session(live = true) {
  let state = { thread: initialThread(), ui: {}, timeline: [], agents: {}, pendingInputs: [], queue: [], pendingApprovals: {}, pendingArbitrations: {}, contextStatus: null };
  let sequence = 0;
  return {
    get state() { return state; },
    send(type, payload = {}, runId = 'run-a') {
      state = produce(state, draft => applyEvent(draft, { type, payload, runId, sequence: ++sequence, timestamp: 1700000000000 + sequence * 1000 }, { live }));
      return state;
    },
    agent(id) { return state.thread.rows.find(row => row.agents?.[id])?.agents[id]; }
  };
}

for (const live of [true, false]) {
  for (const ok of [true, false]) test(`${live ? 'SSE' : '历史回放'}：${ok ? '完成' : '失败'}同步线程与全局状态`, () => {
    const s = session(live);
    s.send('run.started', { input: '检查附件入口' });
    s.send('subagent.started', { agentId: 'agent-a', task: '检查附件' });
    for (let i = 0; i < 6; i++) s.send('model.request', { report: { id: `r${i}`, purpose: 'subagent', status: 'completed' } });
    s.send('subagent.finished', { agentId: 'agent-a', result: '检查完毕', ok });
    assert.equal(s.agent('agent-a').status, ok ? 'done' : 'failed');
    assert.equal(s.state.agents['agent-a'].status, s.agent('agent-a').status);
    assert.equal(s.state.agents['agent-a'].result, '检查完毕');
    assert.equal(Object.keys(s.state.agents).length, 1);
    assert.equal(summarizeAgents(Object.values(s.state.agents)).running, 0);
    assert.equal(s.state.requestReports.length, 6);
  });

  for (const type of ['run.completed', 'run.failed']) test(`${live ? 'SSE' : '历史回放'}：${type} 中止未结束子任务但保留已完成状态`, () => {
    const s = session(live);
    s.send('run.started', { input: '检查附件入口' });
    s.send('subagent.started', { agentId: 'done', task: '检查文件' });
    s.send('subagent.finished', { agentId: 'done', ok: true });
    s.send('subagent.started', { agentId: 'pending', task: '检查桌面端' });
    s.send(type, { code: 'CANCELLED' });
    assert.equal(s.agent('pending').status, 'stopped');
    assert.equal(s.state.agents.pending.status, 'stopped');
    assert.equal(s.state.agents.done.status, 'done');
    assert.equal(summarizeAgents(Object.values(s.state.agents)).running, 0);
  });
}

test('重复开始事件不新增智能体，迟到的完成事件归属原回合', () => {
  const s = session();
  s.send('run.started', { input: '第一轮' });
  s.send('subagent.started', { agentId: 'old', task: '检查旧任务' });
  s.send('subagent.started', { agentId: 'old', task: '检查旧任务' });
  s.send('run.failed', { code: 'CANCELLED' });
  s.send('user.message', { input: '第二轮' }, 'run-b');
  s.send('run.started', { input: '第二轮' }, 'run-b');
  s.send('subagent.started', { agentId: 'new', task: '新任务' }, 'run-b');
  s.send('subagent.finished', { agentId: 'old', result: '旧任务结论', ok: true });
  assert.equal(s.agent('old').status, 'done');
  assert.equal(s.state.agents.old.status, 'done');
  assert.equal(s.state.agents.new.status, 'running');
  assert.equal(Object.keys(s.state.agents).length, 2);
});

test('旧的 interrupted 状态与停止状态均有明确文案', () => {
  assert.equal(agentStatusLabel('interrupted'), '已中止');
  assert.equal(summarizeAgents([{ status: 'interrupted' }, { status: 'stopped' }]).stopped, 2);
});
