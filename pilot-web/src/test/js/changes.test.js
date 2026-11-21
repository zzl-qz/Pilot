import test from 'node:test';
import assert from 'node:assert/strict';

import {
  changeFileTitle, editSummary, mergeRunReports, sortChangeFiles, splitPath, statusKind, sumChangeLines
} from '../../../frontend/src/lib/changes.js';

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
    removed: 0,
    known: true
  });
  assert.equal(mergeRunReports(undefined).files.length, 0);
});

test('sumChangeLines 正常情况把增删行加起来', () => {
  assert.deepEqual(
    sumChangeLines([{ path: 'a', added: 3, removed: 1 }, { path: 'b', added: 2 }]),
    { added: 5, removed: 1, known: true, note: null }
  );
});

test('sumChangeLines 有一条行数未知时合计标为未知，不再报 +0 -0', () => {
  const sum = sumChangeLines([
    { path: 'binary.png', added: 0, removed: 0, countsKnown: false, note: '文件是二进制' },
    { path: 'a.java', added: 4, removed: 2, countsKnown: true }
  ]);

  assert.equal(sum.known, false);
  assert.equal(sum.note, '文件是二进制');
});

test('sumChangeLines 容忍旧记录没有 countsKnown 字段', () => {
  assert.equal(sumChangeLines([{ path: 'old.js', added: 1, removed: 0 }]).known, true);
  assert.equal(sumChangeLines([]).known, true);
  assert.equal(sumChangeLines(null).added, 0);
});

test('editSummary 与 changeFileTitle 对未知行数给出说明而不是 0', () => {
  const unknown = { path: 'big.bin', status: 'M', countsKnown: false, note: '文件超过预览上限' };
  const summary = editSummary([unknown, { path: 'a.java', added: 2, removed: 1 }]);

  assert.equal(summary.known, false);
  assert.equal(changeFileTitle(unknown), 'M · 文件超过预览上限');
  assert.equal(changeFileTitle({ path: 'a.java', status: 'A', added: 2, removed: 1 }), 'A · +2 -1');
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

test('editSummary 默认只露三条，其余收进「展开」', () => {
  const summary = editSummary([
    { path: 'a/A.java', added: 3, removed: 1 },
    { path: 'b/B.java', added: 2, removed: 0 },
    { path: 'c/C.java', added: 0, removed: 4 },
    { path: 'd/D.java', added: 3, removed: 3 }
  ]);

  assert.equal(summary.label, '已编辑 4 个文件');
  assert.equal(summary.total, 4);
  assert.equal(summary.added, 8);
  assert.equal(summary.removed, 8);
  assert.deepEqual(summary.visible.map((file) => file.path), ['a/A.java', 'b/B.java', 'c/C.java']);
  assert.deepEqual(summary.hidden.map((file) => file.path), ['d/D.java']);
});

test('editSummary 文件不超过上限时没有折叠项', () => {
  const summary = editSummary([{ path: 'a/A.java', added: 1, removed: 0 }]);

  assert.equal(summary.hidden.length, 0);
  assert.equal(summary.visible.length, 1);
});

test('editSummary 空列表给出明确文案而不是「已编辑 0 个文件」', () => {
  assert.equal(editSummary([]).label, '本轮没有改动文件');
  assert.equal(editSummary(undefined).total, 0);
});

test('editSummary 归一化 Windows 路径并容忍缺失的增删行', () => {
  const summary = editSummary([{ path: 'src\\A.java' }]);

  assert.equal(summary.visible[0].path, 'src/A.java');
  assert.equal(summary.added, 0);
  assert.equal(summary.removed, 0);
});

test('splitPath 把目录与文件名分开，长路径省略时仍能看到文件名', () => {
  assert.deepEqual(splitPath('pilot-core/src/main/A.java'),
    { dir: 'pilot-core/src/main/', name: 'A.java' });
  assert.deepEqual(splitPath('A.java'), { dir: '', name: 'A.java' });
  assert.deepEqual(splitPath('src\\A.java'), { dir: 'src/', name: 'A.java' });
});

test('statusKind 区分新增、删除与修改', () => {
  assert.equal(statusKind('??'), 'added');
  assert.equal(statusKind('A'), 'added');
  assert.equal(statusKind('D'), 'deleted');
  assert.equal(statusKind('M'), 'modified');
  assert.equal(statusKind(undefined), 'modified');
});
