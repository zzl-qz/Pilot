import assert from 'node:assert/strict';
import test from 'node:test';
import {
  agentSourceLabel,
  createAgentDraft,
  editAgentDraft,
  overrideAgentDraft,
  summarizeAgentBoundaries,
  validateAgentProfileDraft
} from '../src/components/settings/agent-profiles-state.js';

const sampleProfile = {
  name: 'reviewer',
  description: '代码审查员',
  tools: ['read_file', 'git_diff'],
  files: ['src/**'],
  denyFiles: ['secrets/**'],
  commands: ['git status'],
  denyCommands: ['rm -rf *'],
  writable: false,
  source: 'builtin',
  content: '---\ndescription: 代码审查员\nwritable: false\n---\n只读审查。\n'
};

test('新建草稿允许持续输入名称，编辑与覆写草稿才锁定名称', () => {
  const created = createAgentDraft();
  assert.equal(created.mode, 'create');
  assert.equal(created.nameLocked, false);
  assert.equal(created.name, '');

  const edited = editAgentDraft({ ...sampleProfile, source: 'user' });
  assert.equal(edited.mode, 'edit');
  assert.equal(edited.nameLocked, true);
  assert.equal(edited.name, 'reviewer');

  const overridden = overrideAgentDraft(sampleProfile);
  assert.equal(overridden.mode, 'override');
  assert.equal(overridden.nameLocked, true);
  assert.match(overridden.content, /只读审查/);
});

test('角色草稿校验拦截非法名称、缺失 frontmatter、空正文和超长提示词', () => {
  assert.equal(validateAgentProfileDraft('qa-bot', sampleProfile.content), '');
  assert.match(validateAgentProfileDraft('', sampleProfile.content), /角色名称/);
  assert.match(validateAgentProfileDraft('Bad_Name', sampleProfile.content), /小写字母/);
  assert.match(validateAgentProfileDraft('qa-bot', '没有 frontmatter'), /---/);
  assert.match(validateAgentProfileDraft('qa-bot', '---\nwritable: false\n---\n正文'), /description/);
  assert.match(validateAgentProfileDraft('qa-bot', '---\ndescription: 空正文\n---\n   \n'), /正文不能为空/);
  assert.match(validateAgentProfileDraft('qa-bot', '---\ndescription: 超长\n---\n' + 'line\n'.repeat(305)), /300 行/);
});

test('来源文案与五类权限边界摘要完整映射', () => {
  assert.equal(agentSourceLabel('project'), '项目级');
  assert.equal(agentSourceLabel('user'), '用户级');
  assert.equal(agentSourceLabel('builtin'), '内置');
  assert.deepEqual(summarizeAgentBoundaries(sampleProfile), [
    { label: '工具', value: 'read_file、git_diff' },
    { label: '文件白名单', value: 'src/**' },
    { label: '文件黑名单', value: 'secrets/**' },
    { label: '命令白名单', value: 'git status' },
    { label: '命令黑名单', value: 'rm -rf *' }
  ]);
});
