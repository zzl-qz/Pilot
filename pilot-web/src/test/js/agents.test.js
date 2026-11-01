import test from 'node:test';
import assert from 'node:assert/strict';
import {
  agentMeta, pickOwner, shortAgentId, spanText, summarizeAgents
} from '../../../frontend/src/lib/agents.js';

const a = (id, status) => ({ id, status });

test('short id drops the prefix and keeps six characters', () => {
  assert.equal(shortAgentId('agent_ab12cd34'), 'ab12cd');
  assert.equal(shortAgentId(''), '');
  assert.equal(shortAgentId(null), '');
});

test('an explicit agentId wins, even when several agents run in parallel', () => {
  const agents = [a('agent_1', 'running'), a('agent_2', 'running')];
  assert.equal(pickOwner('agent_2', agents).id, 'agent_2');
});

test('an unknown agentId is never guessed', () => {
  assert.equal(pickOwner('agent_x', [a('agent_1', 'running')]), null);
});

test('old events without ownership only attach to a single running agent', () => {
  assert.equal(pickOwner(undefined, [a('agent_1', 'running')]).id, 'agent_1');
  assert.equal(pickOwner(undefined, [a('agent_1', 'running'), a('agent_2', 'running')]), null);
  assert.equal(pickOwner(undefined, [a('agent_1', 'done')]), null);
  assert.equal(pickOwner(undefined, []), null);
});

test('summary counts running and failed agents', () => {
  assert.equal(summarizeAgents([a('1', 'running'), a('2', 'running'), a('3', 'done')]).text,
    '3 个子智能体 · 2 运行中');
  assert.equal(summarizeAgents([a('1', 'done'), a('2', 'failed')]).text, '2 个子智能体 · 1 失败');
  assert.equal(summarizeAgents([a('1', 'done'), a('2', 'done')]).text, '2 个子智能体 · 全部完成');
  assert.equal(summarizeAgents([a('1', 'done'), a('2', 'stopped')]).text, '2 个子智能体 · 1 已中止');
  assert.equal(summarizeAgents([]).total, 0);
});

test('duration text', () => {
  assert.equal(spanText(850), '850ms');
  assert.equal(spanText(12_400), '12s');
  assert.equal(spanText(125_000), '2m 05s');
  assert.equal(spanText(-1), '');
  assert.equal(spanText('x'), '');
});

test('card meta combines tool count and duration', () => {
  assert.equal(agentMeta({ tools: 3, ms: 12_000, status: 'done' }), '3 项操作 · 12s');
  assert.equal(agentMeta({ tools: 0, ms: null, status: 'running' }), '启动中');
  assert.equal(agentMeta({ tools: 0, ms: 4000, status: 'done' }), '4s');
  assert.equal(agentMeta({ tools: 0, ms: null, status: 'done' }), '无操作');
});

test('manifest is split off the conclusion text', async () => {
  const { splitSubagentResult, manifestFacts } = await import('../../../frontend/src/lib/agents.js');
  const raw = '129 个文件。\n\n<subagent_result>\nchanged_files: [a/B.java, c/D.java]\nchecks: [rg --files x | wc -l -> exit 0]\nopen_steps: []\n</subagent_result>';
  const parsed = splitSubagentResult(raw);
  assert.equal(parsed.text, '129 个文件。');
  assert.deepEqual(parsed.changed, ['a/B.java', 'c/D.java']);
  assert.deepEqual(parsed.checks, ['rg --files x | wc -l -> exit 0']);
  assert.deepEqual(parsed.openSteps, []);
  assert.deepEqual(manifestFacts(parsed), ['改动 2 个文件', '校验 1 项']);
});

test('text without a manifest is left alone', async () => {
  const { splitSubagentResult, manifestFacts } = await import('../../../frontend/src/lib/agents.js');
  const parsed = splitSubagentResult('只有结论');
  assert.equal(parsed.text, '只有结论');
  assert.equal(parsed.hasManifest, false);
  assert.deepEqual(manifestFacts(parsed), []);
  assert.equal(manifestFacts(splitSubagentResult('x\n<subagent_result>\nchanged_files: []\nchecks: []\nopen_steps: [T1]\n</subagent_result>')).join('|'), '未改动文件|未完成步骤 1');
});

test('agent title is the first clause, shortened', async () => {
  const { agentTitle } = await import('../../../frontend/src/lib/agents.js');
  assert.equal(agentTitle('T1 登录接口：改造校验逻辑'), 'T1 登录接口');
  assert.equal(agentTitle(''), '子智能体');
  assert.equal(agentTitle('请只读统计工作区内 pilot-core/src/main 目录及其子目录下的 .java 文件数量，使用 rg。'),
    '统计工作区内 pilot-core/src/…');
  assert.equal(agentTitle('统计 Java 文件，只读'), '统计 Java 文件');
  assert.equal(agentTitle('读 a.b.java 看看').includes('a.b.java'), true);
});

test('panel line shows progress while running and the conclusion once done', async () => {
  const { agentLine } = await import('../../../frontend/src/lib/agents.js');
  assert.equal(agentLine({ status: 'running', latest: '读取 A.java' }), '正在 读取 A.java');
  assert.equal(agentLine({ status: 'running' }), '正在思考…');
  assert.equal(agentLine({ status: 'done', conclusion: '\n 129 个文件\n第二行' }), '129 个文件');
  assert.equal(agentLine({ status: 'failed' }), '失败，没有返回原因');
  assert.equal(agentLine({ status: 'stopped' }), '运行结束时仍未完成');
});
