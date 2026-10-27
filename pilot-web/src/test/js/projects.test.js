import test from 'node:test';
import assert from 'node:assert/strict';

import {
  groupSessionsByProject,
  partitionSessions,
  resolveFolders,
  samePath
} from '../../main/resources/static/lib/projects.js';

test('samePath 忽略分隔符、大小写与末尾斜杠', () => {
  assert.equal(samePath('E:\\Pilot\\web', 'e:/pilot/web'), true);
  assert.equal(samePath('E:\\Pilot\\web\\', 'E:\\Pilot\\web'), true);
  assert.equal(samePath('E:\\Pilot\\web', 'E:\\Pilot\\core'), false);
  // 空路径不能和任何东西相等，否则「没选目录」会误判成命中
  assert.equal(samePath('', ''), false);
  assert.equal(samePath(null, ''), false);
});

test('resolveFolders 保留项目顺序，并把当前目录补到最前', () => {
  const project = { folders: ['a', 'b'] };
  assert.deepEqual(resolveFolders(project, 'b'), ['a', 'b']);
  // 当前目录不在项目里（被移除过）：仍要出现在列表最前面
  assert.deepEqual(resolveFolders(project, 'c'), ['c', 'a', 'b']);
});

test('resolveFolders 没有项目时退化成当前目录', () => {
  assert.deepEqual(resolveFolders(null, 'x'), ['x']);
  assert.deepEqual(resolveFolders(null, null), []);
});

test('partitionSessions 把空白、活跃、归档分开', () => {
  const sessions = [
    { id: 'blank', isBlank: true },
    { id: 'a1' },
    { id: 'archived1', archived: true },
    { id: 'a2' }
  ];

  const { blank, active, archived } = partitionSessions(sessions);

  assert.equal(blank.id, 'blank');
  assert.deepEqual(active.map((item) => item.id), ['a1', 'a2']);
  assert.deepEqual(archived.map((item) => item.id), ['archived1']);
});

test('partitionSessions 不会把归档的空白对话当成当前空白位', () => {
  const { blank, archived } = partitionSessions([
    { id: 'old-blank', isBlank: true, archived: true },
    { id: 'a1' }
  ]);

  assert.equal(blank, null, '归档的空白对话不该占着空白位');
  assert.equal(archived.length, 1);
});

test('groupSessionsByProject 按项目分组，历史会话进未分组', () => {
  const projects = [{ id: 'p1', name: 'one' }, { id: 'p2', name: 'two' }];
  const sessions = [
    { id: 's1', projectId: 'p2' },
    { id: 's2', projectId: 'p1' },
    { id: 's3', projectId: null }
  ];

  const groups = groupSessionsByProject(projects, sessions);

  assert.equal(groups.length, 3);
  assert.deepEqual(groups[0].active.map((item) => item.id), ['s2']);
  assert.deepEqual(groups[1].active.map((item) => item.id), ['s1']);
  assert.equal(groups[2].project.id, null);
  assert.equal(groups[2].project.name, '未分组');
  assert.deepEqual(groups[2].active.map((item) => item.id), ['s3']);
});

test('groupSessionsByProject 每个组单独算空白对话', () => {
  const groups = groupSessionsByProject(
    [{ id: 'p1' }, { id: 'p2' }],
    [
      { id: 'b1', projectId: 'p1', isBlank: true },
      { id: 'b2', projectId: 'p2', isBlank: true }
    ]
  );

  assert.equal(groups[0].blank.id, 'b1');
  assert.equal(groups[1].blank.id, 'b2');
});

test('groupSessionsByProject 空输入返回空数组', () => {
  assert.deepEqual(groupSessionsByProject([], []), []);
  assert.deepEqual(groupSessionsByProject(undefined, undefined), []);
});
