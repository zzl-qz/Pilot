import test from 'node:test';
import assert from 'node:assert/strict';
import {
  changeHint, emptyPdfForm, formatBytes, formatLabel, parsePageList, pdfOperations
} from '../../../frontend/src/lib/file-workbench.js';

test('页码解析：逗号、中文逗号、区间与去重排序', () => {
  assert.deepEqual(parsePageList('1,3,5-8，7'), [1, 3, 5, 6, 7, 8]);
  assert.deepEqual(parsePageList('  2 ~ 4 '), [2, 3, 4]);
  assert.deepEqual(parsePageList('abc,0,-1,9-2'), []);
  assert.deepEqual(parsePageList('1-300'), []);
});

test('空表单不产生任何页面操作', () => {
  assert.equal(pdfOperations(emptyPdfForm()), null);
  assert.equal(pdfOperations({ ...emptyPdfForm(), keep: 'bad' }), null);
});

test('表单转成 DocumentRenderer.editPdf 的操作结构', () => {
  const operations = pdfOperations({
    ...emptyPdfForm(), keep: '1,3-5', drop: '2', rotatePages: '1,4',
    watermark: 'DRAFT', pageNumbers: true, merge: 'a.pdf, b.pdf'
  });
  assert.deepEqual(operations.keep_pages, [1, 3, 4, 5]);
  assert.deepEqual(operations.drop_pages, [2]);
  assert.deepEqual(operations.rotate, { degrees: 90, pages: [1, 4] });
  assert.equal(operations.watermark, 'DRAFT');
  assert.equal(operations.page_numbers, true);
  assert.deepEqual(operations.merge, ['a.pdf', 'b.pdf']);
});

test('水印与旋转角度给出明确校验，不静默丢字符', () => {
  assert.throws(() => pdfOperations({ ...emptyPdfForm(), watermark: '草稿' }), /ASCII/);
  assert.throws(() => pdfOperations({ ...emptyPdfForm(), rotatePages: '1', rotateDegrees: '45' }), /90/);
});

test('展示格式化：字节、格式名与外部改动提示', () => {
  assert.equal(formatBytes(512), '512 B');
  assert.equal(formatBytes(2048), '2.0 KB');
  assert.equal(formatLabel('docx'), 'Word 文档');
  assert.equal(formatLabel('pdf'), 'PDF');
  assert.match(changeHint(true), /外部/);
  assert.match(changeHint(false), /唯一源/);
});
