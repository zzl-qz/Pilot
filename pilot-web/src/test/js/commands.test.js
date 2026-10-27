import test from 'node:test';
import assert from 'node:assert/strict';
import {
  expandQuickCommand,
  filterPaletteActions,
  filterQuickCommands,
  QUICK_COMMANDS
} from '../../main/resources/static/lib/commands.js';

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

test('command palette actions search keywords and shortcuts', () => {
  assert.equal(filterPaletteActions('workspace')[0].action, 'new-session');
  assert.equal(filterPaletteActions('Ctrl B')[0].action, 'toggle-activity');
});
