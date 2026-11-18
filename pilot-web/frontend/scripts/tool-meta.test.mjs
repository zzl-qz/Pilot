import test from 'node:test';
import assert from 'node:assert/strict';
import { toolArgsText, toolLabel } from '../src/lib/tool-meta.js';

test('常见工具显示中文名，不直接甩协议名', () => {
  assert.equal(toolLabel('command_status'), '查命令');
  assert.equal(toolLabel('command_logs'), '读日志');
  assert.equal(toolLabel('browser'), '浏览器');
  assert.equal(toolLabel('run_command'), '命令');
  assert.equal(toolLabel('mcp_tools'), 'MCP 工具');
  assert.equal(toolLabel('mcp_call'), 'MCP 调用');
});

test('command_status 参数收成人话，不露 waitSeconds 原文', () => {
  const text = toolArgsText('command_status', 'taskId=cmd_16162ef8abcd waitSeconds=5');
  assert.match(text, /cmd_1616/);
  assert.match(text, /最多等 5 秒/);
  assert.doesNotMatch(text, /waitSeconds/);
  assert.doesNotMatch(text, /taskId=/);
});

test('浏览器 summary 里的 action 译成中文', () => {
  assert.equal(toolArgsText('browser', 'click'), '点击');
  assert.equal(toolArgsText('browser', 'inspect'), '检查');
  assert.match(toolArgsText('browser', 'action=navigate,url=https://example.com/path'), /打开 example.com/);
});
