import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { analyze, classifyWirePrefixTransitions, isTailEphemeralItem } from './trace-audit.mjs';

function makeTempDebugDir(requestsByRun) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pilot-trace-audit-test-'));
  for (const [runId, reqs] of Object.entries(requestsByRun)) {
    const dir = path.join(root, runId);
    fs.mkdirSync(dir, { recursive: true });
    reqs.forEach((req, idx) => {
      fs.writeFileSync(
        path.join(dir, `req-${idx + 1}.json`),
        JSON.stringify({ sequence: idx + 1, runId, purpose: req.purpose || 'main', body: req.body }),
        'utf8'
      );
    });
  }
  return root;
}

function modelEvent(sequence, runId, change, inputTokens = 20000, cachedInputTokens = 19000) {
  return {
    sequence,
    runId,
    type: 'model.request',
    payload: {
      report: {
        purpose: 'main',
        model: 'deepseek-v4.1-flash-1017',
        status: 'completed',
        usage: { inputTokens, cachedInputTokens, cacheReadObserved: sequence > 1 },
        context: { totalTokens: inputTokens },
        prefix: { change },
      },
    },
  };
}

test('isTailEphemeralItem 仅识别一次性尾部运行时提示', () => {
  assert.equal(isTailEphemeralItem({ role: 'user', content: '<candidate-targets status="unverified-hypothesis">...' }), true);
  assert.equal(isTailEphemeralItem({ role: 'system', content: '[Runtime guidance; applies only to this request]\n...' }), true);
  assert.equal(isTailEphemeralItem({ role: 'user', content: '[运行时检查点] 你已经连续做了 14 次只读查询...' }), true);
  assert.equal(isTailEphemeralItem({ role: 'user', content: '[调查收敛提醒] ...' }), true);
  assert.equal(isTailEphemeralItem({ role: 'system', content: '...\n[收尾前自查] 你准备结束这次任务' }), true);
  assert.equal(isTailEphemeralItem({ role: 'user', content: '普通用户需求描述' }), false);
  assert.equal(isTailEphemeralItem({ type: 'function_call_output', output: '[旧工具输出已清理]' }), false);
});

test('仅移除尾部一次性提示且 [0..n-2] 与 tools 一致时不误报 P1 前缀缓存告警', () => {
  const tools = [{ name: 'read_file' }, { name: 'edit_file' }];
  const base = [
    { role: 'system', content: 'sys' },
    { role: 'user', content: 'fix bug' },
  ];
  const debugDir = makeTempDebugDir({
    run_1: [
      { body: { tools, input: [...base, { role: 'user', content: '<candidate-targets status="unverified-hypothesis">\n- A.java' }] } },
      { body: { tools, input: [...base, { role: 'assistant', content: '先读 A.java' }, { role: 'system', content: '[Runtime guidance; applies only to this request]\nupdate_plan' }] } },
      { body: { tools, input: [...base, { role: 'assistant', content: '先读 A.java' }, { type: 'function_call', name: 'read_file' }, { role: 'user', content: '[运行时检查点] 开始修改' }] } },
      { body: { tools, input: [...base, { role: 'assistant', content: '先读 A.java' }, { type: 'function_call', name: 'read_file' }, { type: 'function_call', name: 'edit_file' }] } },
    ],
  });
  try {
    const drops = classifyWirePrefixTransitions(debugDir);
    assert.deepEqual([...drops], ['run_1#2', 'run_1#3', 'run_1#4']);

    const report = analyze({
      session: { id: 'ses_1', title: 'test', status: 'COMPLETED', workspace: '' },
      runs: [{ id: 'run_1' }],
      events: [
        modelEvent(1, 'run_1', 'initial', 20000, 0),
        modelEvent(2, 'run_1', 'history_changed', 21000, 20000),
        modelEvent(3, 'run_1', 'history_changed', 22000, 21000),
        modelEvent(4, 'run_1', 'history_changed', 23000, 22000),
      ],
      messages: [],
      trace: { exists: false, entries: [] },
      diagnostics: null,
      gitInfo: null,
      debugRequestsDir: debugDir,
    });
    assert.equal(report.signals.H_cacheStability.nonAppendOnlyMainCount, 0);
    assert.equal(report.signals.H_cacheStability.tailEphemeralDropCount, 3);
    assert.equal(report.signals.H_cacheStability.prefixChangeCounts.tail_ephemeral_drop, 3);
    assert.equal(report.alerts.filter((a) => a.category === 'H').length, 0);
  } finally {
    fs.rmSync(debugDir, { recursive: true, force: true });
  }
});

test('真正的中段前缀改写、tools 变更或非临时尾消息变化仍触发 P1 前缀缓存告警', () => {
  const toolsA = [{ name: 'read_file' }];
  const toolsB = [{ name: 'read_file' }, { name: 'edit_file' }];
  const debugDir = makeTempDebugDir({
    run_2: [
      { body: { tools: toolsA, input: [{ role: 'system', content: 'sys' }, { role: 'user', content: 'task' }, { role: 'user', content: 'raw tail' }] } },
      // 1) 中段前缀被改写（idx=1 变了）
      { body: { tools: toolsA, input: [{ role: 'system', content: 'sys' }, { role: 'user', content: '[旧工具输出已清理]' }, { role: 'user', content: 'raw tail' }, { role: 'assistant', content: 'ok' }] } },
      // 2) tools 发生变更，即便末尾曾是临时提示也绝不算 tail drop
      { body: { tools: toolsB, input: [{ role: 'system', content: 'sys' }, { role: 'user', content: '[旧工具输出已清理]' }, { role: 'user', content: 'raw tail' }, { role: 'assistant', content: 'ok' }, { role: 'user', content: 'next' }] } },
    ],
  });
  try {
    const drops = classifyWirePrefixTransitions(debugDir);
    assert.equal(drops.size, 0);

    const report = analyze({
      session: { id: 'ses_2', title: 'test2', status: 'COMPLETED', workspace: '' },
      runs: [{ id: 'run_2' }],
      events: [
        modelEvent(1, 'run_2', 'initial', 20000, 0),
        modelEvent(2, 'run_2', 'history_changed', 21000, 19000),
        modelEvent(3, 'run_2', 'tools_changed', 22000, 19000),
      ],
      messages: [],
      trace: { exists: false, entries: [] },
      diagnostics: null,
      gitInfo: null,
      debugRequestsDir: debugDir,
    });
    assert.equal(report.signals.H_cacheStability.nonAppendOnlyMainCount, 2);
    assert.equal(report.signals.H_cacheStability.tailEphemeralDropCount, 0);
    assert.ok(report.alerts.some((a) => a.category === 'H' && a.severity === 'P1'));
  } finally {
    fs.rmSync(debugDir, { recursive: true, force: true });
  }
});

test('缺少 debugRequestsDir 时保守保留原始 history_changed 告警，不弱化检测', () => {
  const report = analyze({
    session: { id: 'ses_3', title: 'test3', status: 'COMPLETED', workspace: '' },
    runs: [{ id: 'run_3' }],
    events: [
      modelEvent(1, 'run_3', 'initial', 20000, 0),
      modelEvent(2, 'run_3', 'history_changed', 21000, 19000),
      modelEvent(3, 'run_3', 'history_changed', 22000, 20000),
    ],
    messages: [],
    trace: { exists: false, entries: [] },
    diagnostics: null,
    gitInfo: null,
    debugRequestsDir: null,
  });
  assert.equal(report.signals.H_cacheStability.nonAppendOnlyMainCount, 2);
  assert.equal(report.signals.H_cacheStability.tailEphemeralDropCount, 0);
  assert.ok(report.alerts.some((a) => a.category === 'H' && a.severity === 'P1'));
});
