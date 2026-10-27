import test from 'node:test';
import assert from 'node:assert/strict';

import { mergeRunReports, sortChangeFiles, statusKind } from '../../main/resources/static/lib/changes.js';

test('mergeRunReports 合并多轮 run 并把增删行数加起来', () => {
  const merged = mergeRunReports([
    { gitRepository: true, files: [{ path: 'src/A.java', added: 3, removed: 1 }] },
    { gitRepository: false, truncated: true, files: [{ path: 'src/B.java', added: 2, removed: 0 }] }
  ]);

  assert.equal(merged.gitRepository, true);
  assert.equal(merged.truncated, true);
  assert.deepEqual(merged.files.map((file) => file.path), ['src/A.java', 'src/B.java']);
  assert.equal(merged.added, 5);
  assert.equal(merged.removed, 1);
});

test('mergeRunReports 同一文件以后出现的报告为准，不重复计数', () => {
  const merged = mergeRunReports([
    { files: [{ path: 'src/A.java', added: 3, removed: 1 }] },
    { files: [{ path: './src/A.java', added: 4, removed: 2 }] }
  ]);

  assert.equal(merged.files.length, 1);
  assert.equal(merged.added, 4);
  assert.equal(merged.removed, 2);
});

test('mergeRunReports 空输入返回空结果而不是抛错', () => {
  assert.deepEqual(mergeRunReports([]), {
    gitRepository: false,
    truncated: false,
    files: [],
    added: 0,
    removed: 0
  });
  assert.equal(mergeRunReports(undefined).files.length, 0);
});

test('sortChangeFiles 本轮改过的排在前面，其余按路径排', () => {
  const sorted = sortChangeFiles(
    [{ path: 'z/Other.java' }, { path: 'a/Second.java' }, { path: 'm/First.java' }],
    ['m/First.java', 'a/Second.java']
  );

  assert.deepEqual(sorted.map((file) => file.path), ['m/First.java', 'a/Second.java', 'z/Other.java']);
});

test('sortChangeFiles 用规范化后的路径匹配（Windows 反斜杠也能对上）', () => {
  const sorted = sortChangeFiles([{ path: 'src\\A.java' }, { path: 'src/B.java' }], ['src/A.java']);

  assert.deepEqual(sorted.map((file) => file.path), ['src\\A.java', 'src/B.java']);
});

test('statusKind 区分新增、删除与修改', () => {
  assert.equal(statusKind('??'), 'added');
  assert.equal(statusKind('A'), 'added');
  assert.equal(statusKind('D'), 'deleted');
  assert.equal(statusKind('M'), 'modified');
  assert.equal(statusKind(undefined), 'modified');
});
