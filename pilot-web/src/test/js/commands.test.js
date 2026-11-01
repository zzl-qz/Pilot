import test from 'node:test';
import assert from 'node:assert/strict';
import {
  expandQuickCommand,
  filterPaletteActions,
  filterQuickCommands,
  QUICK_COMMANDS
} from '../../../frontend/src/lib/commands.js';

test('quick commands can be found by name, alias, label, or hint', () => {
  assert.equal(filterQuickCommands('review')[0].name, '/review');
  assert.equal(filterQuickCommands('tests')[0].name, '/test');
  assert.equal(filterQuickCommands('解释代码')[0].name, '/explain');
  assert.equal(filterQuickCommands('不存在').length, 0);
});

test('quick commands expand optional arguments into task prompts', () => {
  const run = QUICK_COMMANDS.find((command) => command.name === '/run');
  assert.match(expandQuickCommand(run, 'pilot-core test'), /pilot-core test/);
  assert.match(expandQuickCommand(run), /运行当前项目测试/);
});

test('/review 直发命令原文，让服务端进入独立审查模式而不是普通对话', () => {
  const review = QUICK_COMMANDS.find((command) => command.name === '/review');
  // 展开成自然语言会让服务端识别不到命令，协议与只读裁剪都会失效——必须原样保留
  assert.equal(expandQuickCommand(review, '重点看登录模块'), '/review 重点看登录模块');
  assert.equal(expandQuickCommand(review), '/review');
  assert.equal(expandQuickCommand(review, '  '), '/review');
});

test('command palette actions search keywords and shortcuts', () => {
  assert.equal(filterPaletteActions('workspace')[0].action, 'new-session');
  assert.equal(filterPaletteActions('Ctrl B')[0].action, 'toggle-activity');
});
