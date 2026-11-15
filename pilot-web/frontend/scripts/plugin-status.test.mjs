import test from 'node:test';
import assert from 'node:assert/strict';
import { pluginAttentionItems, pluginKindLabel, pluginStatusDetail, pluginStatusLabel, pluginStatusTone, pluginSummary } from '../src/lib/plugin-status.js';

test('插件四档状态各有独立文案与视觉，未知不冒充正常', () => {
  assert.equal(pluginStatusLabel('ok'), '正常');
  assert.equal(pluginStatusLabel('changed'), '会重新确认');
  assert.equal(pluginStatusLabel('broken'), '会失效');
  assert.equal(pluginStatusLabel('unknown'), '无法判断');
  assert.equal(pluginStatusLabel('whatever'), '状态未知');
  assert.equal(pluginStatusLabel(null), '状态未知');

  assert.deepEqual(
    ['ok', 'changed', 'broken', 'unknown', 'other'].map(pluginStatusTone),
    ['ok', 'warn', 'error', 'muted', 'muted']
  );
  // 「会失效」和「会变」不能同色：一个什么都不做，一个只是要点一次批准。
  assert.notEqual(pluginStatusTone('broken'), pluginStatusTone('changed'));
});

test('原因优先用后端那句，没有原因时按状态给默认解释', () => {
  assert.equal(pluginStatusDetail({ status: 'broken', reason: 'matcher 指向的工具不存在' }), 'matcher 指向的工具不存在');
  assert.match(pluginStatusDetail({ status: 'broken', reason: '' }), /不会报错/);
  assert.match(pluginStatusDetail({ status: 'ok', reason: '' }), /不需要处理/);
  assert.match(pluginStatusDetail({ status: 'changed', reason: '' }), /重新确认/);
  assert.match(pluginStatusDetail(null), /无法判断/);
});

test('汇总先报坏掉的，再给全部数字', () => {
  assert.equal(pluginSummary({ ok: 0, changed: 0, broken: 0, unknown: 0 }), '还没有接入任何插件。');
  assert.equal(pluginSummary(null), '还没有接入任何插件。');
  assert.match(pluginSummary({ ok: 3, changed: 0, broken: 0, unknown: 0 }), /^全部正常/);
  assert.match(pluginSummary({ ok: 1, changed: 2, broken: 0, unknown: 0 }), /^2 项需要重新确认/);
  const mixed = pluginSummary({ ok: 1, changed: 1, broken: 2, unknown: 1 });
  assert.match(mixed, /^2 项会失效/);
  assert.match(mixed, /共 5 项/);
});

test('只把需要处理的项挑出来，全好时不打扰用户', () => {
  const items = [
    { kind: 'mcp', name: 'a', source: '', status: 'ok', reason: '', details: [] },
    { kind: 'skill', name: 'b', source: '', status: 'changed', reason: '', details: [] },
    { kind: 'hook', name: 'c', source: '', status: 'broken', reason: '', details: [] }
  ];
  assert.deepEqual(pluginAttentionItems(items).map((item) => item.name), ['b', 'c']);
  assert.deepEqual(pluginAttentionItems([items[0]]), []);
  assert.deepEqual(pluginAttentionItems(null), []);
});

test('插件类型名可读，未知类型原样显示', () => {
  assert.equal(pluginKindLabel('mcp'), 'MCP 服务器');
  assert.equal(pluginKindLabel('hook'), '钩子');
  assert.equal(pluginKindLabel('custom'), 'custom');
  assert.equal(pluginKindLabel(null), '未知类型');
});