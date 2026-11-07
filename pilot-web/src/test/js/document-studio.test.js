import test from 'node:test';
import assert from 'node:assert/strict';
import {
  defaultSpec, diffLines, formatLabel, normalizeBlocks, parsePdfOperations, parseSpec,
  parseTable, serializeSpec, tableText
} from '../../../frontend/src/lib/document-model.js';

test('规格解析：合法对象、非法 JSON、非对象都给出可读结果', () => {
  const parsed = parseSpec('{"title":"季度报告","blocks":[{"type":"paragraph","text":"结论"}]}');
  assert.equal(parsed.title, '季度报告');
  assert.equal(parsed.blocks.length, 1);
  assert.equal(parsed.error, '');
  assert.equal(parseSpec('not json').error.startsWith('JSON 解析失败'), true);
  assert.equal(parseSpec('[]').error, '规格必须是 JSON 对象');
  assert.equal(parseSpec('').error, '规格为空');
});

test('块规范化：过滤未知类型，保留标题级别与表格行', () => {
  const blocks = normalizeBlocks([
    { type: 'heading', text: '标题', level: 2 },
    { type: 'unknown', text: '丢弃' },
    { type: 'table', rows: [['A', 'B'], ['1', '2']] },
    { type: 'page_break' },
    null
  ]);
  assert.equal(blocks.length, 3);
  assert.equal(blocks[0].level, 2);
  assert.equal(blocks[1].rows.length, 2);
  assert.equal(blocks[2].type, 'page_break');
});

test('规格序列化往返不丢失内容', () => {
  const blocks = [
    { type: 'heading', text: '背景', level: 1 },
    { type: 'paragraph', text: '正文' },
    { type: 'table', rows: [['列 A', '列 B'], ['值 1', '值 2']] }
  ];
  const raw = serializeSpec('报告', blocks);
  const parsed = parseSpec(raw);
  assert.equal(parsed.title, '报告');
  assert.equal(parsed.blocks.length, 3);
  assert.deepEqual(parsed.blocks[2].rows, [['列 A', '列 B'], ['值 1', '值 2']]);
  assert.equal(parseSpec(defaultSpec()).blocks.length, 2);
});

test('表格文本与行数组互相转换', () => {
  assert.equal(tableText([['A', 'B'], ['1', '2']]), 'A | B\n1 | 2');
  assert.deepEqual(parseTable('A | B\n1 | 2'), [['A', 'B'], ['1', '2']]);
  assert.deepEqual(parseTable(''), []);
});

test('版本差异标出新增与删除行', () => {
  const diff = diffLines('标题\n旧段落', '标题\n新段落');
  assert.ok(diff.some(line => line.kind === 'same' && line.text === '标题'));
  assert.ok(diff.some(line => line.kind === 'add' && line.text === '新段落'));
  assert.ok(diff.some(line => line.kind === 'remove' && line.text === '旧段落'));
  assert.deepEqual(diffLines('same', 'same').map(line => line.kind), ['same']);
});

test('PDF 页面操作校验：空串、合法对象、未知键与坏 JSON', () => {
  assert.deepEqual(parsePdfOperations(''), { value: null, error: '' });
  const ok = parsePdfOperations('{"keep_pages":[1,2],"page_numbers":true}');
  assert.equal(ok.error, '');
  assert.equal(ok.value.keep_pages.length, 2);
  assert.ok(parsePdfOperations('{"evil":1}').error.includes('不支持'));
  assert.ok(parsePdfOperations('[1]').error.includes('JSON 对象'));
  assert.ok(parsePdfOperations('{').error.includes('解析失败'));
});

test('格式标签映射', () => {
  assert.equal(formatLabel('docx'), 'Word');
  assert.equal(formatLabel('pdf'), 'PDF');
  assert.equal(formatLabel('xlsx'), 'Excel');
  assert.equal(formatLabel('pptx'), 'PPT');
  assert.equal(formatLabel('other'), 'OTHER');
});
