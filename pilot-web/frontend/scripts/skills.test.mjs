import assert from 'node:assert/strict';
import test from 'node:test';
import {
  gitUrlProblem,
  parseInstallSource,
  summarizeInstallResults
} from '../src/lib/skills.js';

test('gitUrlProblem 拦下 GitHub 网页地址并给出仓库根写法', () => {
  const blob = gitUrlProblem('https://github.com/anthropics/skills/blob/main/skills/doc-coauthoring/SKILL.md');
  assert.match(blob, /网页地址/);
  assert.match(blob, /仓库根/);
  assert.match(blob, /anthropics\/skills/);
  assert.match(gitUrlProblem('https://github.com/u/repo/tree/main'), /网页地址/);
  assert.match(gitUrlProblem('https://github.com/u/repo/raw/main/x.md'), /网页地址/);
  assert.match(gitUrlProblem('https://raw.githubusercontent.com/u/repo/main/skills/x/SKILL.md'), /文件/);
  // 尾斜杠的 md 地址同样拦
  assert.match(gitUrlProblem('https://example.com/some/SKILL.md/'), /文件/);
});

test('gitUrlProblem 对仓库地址与非 git 输入不误报', () => {
  assert.equal(gitUrlProblem('https://github.com/anthropics/skills'), null);
  assert.equal(gitUrlProblem('https://github.com/anthropics/skills.git'), null);
  assert.equal(gitUrlProblem('http://gitea.local/team/skills'), null);
  assert.equal(gitUrlProblem('https://example.com/repo'), null);
  // 非以 http(s) 开头的输入不归这个函数管（parseInstallSource 已分类）
  assert.equal(gitUrlProblem('D:\\skills\\review'), null);
  assert.equal(gitUrlProblem(''), null);
});

test('parseInstallSource 严格分类 git 与本机目录', () => {
  assert.deepEqual(parseInstallSource('https://github.com/u/review.git'), { type: 'git', value: 'https://github.com/u/review.git' });
  assert.deepEqual(parseInstallSource(' D:\\skills\\review '), { type: 'local', value: 'D:\\skills\\review' });
  assert.equal(parseInstallSource('github.com/u/repo'), null, '裸域名不自动补协议');
  assert.equal(parseInstallSource('skills/review'), null);
  assert.equal(parseInstallSource(null), null);
});

test('summarizeInstallResults 三组化安装结果', () => {
  const summary = summarizeInstallResults([
    { name: 'a', status: 'installed' },
    { name: 'b', status: 'conflict' },
    { name: 'c', status: 'error', reason: '超过上限' }
  ]);
  assert.deepEqual(summary.installed, ['a']);
  assert.deepEqual(summary.conflicts, ['b']);
  assert.deepEqual(summary.errors, [{ name: 'c', reason: '超过上限' }]);
  assert.deepEqual(summarizeInstallResults(null), { installed: [], conflicts: [], errors: [] });
});
