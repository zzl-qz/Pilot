import test from 'node:test';
import assert from 'node:assert/strict';
import {
  emptySkillsHint,
  mergeCommands,
  resolveSkillCommand,
  skillEntry,
  toSkillCommands
} from '../../main/resources/static/lib/skills.js';
import { QUICK_COMMANDS } from '../../main/resources/static/lib/commands.js';

const SKILLS = [
  {
    name: 'commit-review',
    description: '审阅未提交改动并按风险输出问题清单。',
    command: '/review',
    argumentHint: '[文件路径或关注点]',
    source: 'project'
  },
  {
    name: 'release-notes',
    description: '按提交记录生成发布说明。',
    command: '/release',
    argumentHint: '',
    source: 'user'
  },
  {
    name: 'no-command',
    description: '没有快捷指令的技能不应进命令菜单。',
    command: null,
    source: 'user'
  }
];

test('skill payloads map to command shape with skill markers', () => {
  const commands = toSkillCommands(SKILLS);
  assert.equal(commands.length, 2, '没有 command 的技能不进命令菜单');
  const review = commands.find((command) => command.name === '/review');
  assert.equal(review.skill, 'commit-review');
  assert.equal(review.kind, 'skill');
  assert.equal(review.prompt, null, '技能没有本地展开模板，发送时由后端注入指引');
  assert.match(review.hint, /（技能）/);
  assert.match(review.hint, /参数 \[文件路径或关注点\]/);
});

test('skill commands shadow same-name builtin commands', () => {
  const commands = mergeCommands(QUICK_COMMANDS, toSkillCommands([
    { name: 'custom-review', description: '用户自己装的审阅技能。', command: '/review' }
  ]));
  const review = commands.find((command) => command.name === '/review');
  assert.equal(review.skill, 'custom-review', '同名时技能遮蔽内置：显式安装是更强的意图');
  assert.equal(commands.length, QUICK_COMMANDS.length, '同名合并不增加条目');
  const plain = mergeCommands(QUICK_COMMANDS, toSkillCommands(SKILLS));
  // SKILLS 有两条指令：/review 遮蔽同名内置、/release 新增，净增 1 条
  assert.equal(plain.length, QUICK_COMMANDS.length + 1);
  assert.equal(plain.find((command) => command.name === '/review').skill, 'commit-review');
  assert.equal(plain.find((command) => command.name === '/release').skill, 'release-notes');
});

test('empty skill list keeps builtin commands untouched', () => {
  assert.deepEqual(mergeCommands(QUICK_COMMANDS, []), QUICK_COMMANDS);
  assert.deepEqual(mergeCommands(QUICK_COMMANDS, toSkillCommands([])), QUICK_COMMANDS);
});

test('resolveSkillCommand matches exact slash commands with args', () => {
  const commands = toSkillCommands(SKILLS);
  assert.deepEqual(resolveSkillCommand('/review', commands), { skill: 'commit-review', args: '' });
  assert.deepEqual(resolveSkillCommand('/review src/app.js', commands),
    { skill: 'commit-review', args: 'src/app.js' });
  assert.deepEqual(resolveSkillCommand('/release  \n v1.2 变更 ', commands),
    { skill: 'release-notes', args: 'v1.2 变更' });
});

test('resolveSkillCommand ignores builtin commands, plain text and partial prefixes', () => {
  const commands = toSkillCommands(SKILLS);
  assert.equal(resolveSkillCommand('/test', commands), null, '内置指令不走技能通道');
  assert.equal(resolveSkillCommand('/rev', commands), null, '前缀不精确命中，不误触发');
  assert.equal(resolveSkillCommand('看看代码', commands), null);
  assert.equal(resolveSkillCommand('', commands), null);
  assert.equal(resolveSkillCommand('/review', []), null, '没有技能时一律按普通文本');
});

test('skillEntry renders source and command metadata for the settings page', () => {
  assert.deepEqual(skillEntry({ name: 'demo', description: '做某事。', command: '/review', source: 'project' }),
    { name: 'demo', meta: '项目级 · 快捷命令 /review', description: '做某事。' });
  assert.deepEqual(skillEntry({ name: 'mine', description: '个人技能。', source: 'user' }),
    { name: 'mine', meta: '用户级', description: '个人技能。' });
  assert.deepEqual(skillEntry(null), { name: '', meta: '', description: '' });
});

test('emptySkillsHint points users at the install directory', () => {
  assert.match(emptySkillsHint('D:/repo/.pilot/skills'), /D:\/repo\/\.pilot\/skills 下建一个目录/);
  assert.match(emptySkillsHint('D:/repo/.pilot/skills'), /SKILL\.md/);
  assert.match(emptySkillsHint('-'), /在技能目录下建一个目录/);
  assert.match(emptySkillsHint(undefined), /在技能目录下建一个目录/);
});
