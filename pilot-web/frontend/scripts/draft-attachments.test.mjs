import assert from 'node:assert/strict';
import test from 'node:test';
import {
  draftFilesKey, dropDraftSnapshot, readDraftSnapshot, writeDraftSnapshot
} from '../src/lib/drafts.js';

function storage() {
  const data = new Map();
  return {
    getItem: key => (data.has(key) ? data.get(key) : null),
    setItem: (key, value) => { if (value.length > 10_000) throw new Error('QuotaExceeded'); data.set(key, String(value)); },
    removeItem: key => data.delete(key),
    dump: () => data
  };
}
const file = (id, name, content = 'hello') => ({
  id, name, mimeType: 'text/plain', size: content.length, dataBase64: `data:text/plain;base64,${Buffer.from(content).toString('base64')}`
});

test('附件草稿保存正文、文件与图片位置，读回一致', () => {
  const store = storage();
  const files = [file('a', 'a.txt'), file('b', 'b.png')];
  assert.deepEqual(writeDraftSnapshot(store, 's1', { text: '看图\n再检查', files, imagePositions: [2] }), { ok: true });
  const { snapshot, error } = readDraftSnapshot(store, 's1');
  assert.equal(error, undefined);
  assert.equal(snapshot.text, '看图\n再检查');
  assert.deepEqual(snapshot.files.map(item => item.name), ['a.txt', 'b.png']);
  assert.deepEqual(snapshot.imagePositions, [2]);
});

test('不同会话的附件草稿互相隔离，新会话用独立槽位', () => {
  const store = storage();
  writeDraftSnapshot(store, 's1', { text: '一', files: [file('a', 'a.txt')], imagePositions: [] });
  writeDraftSnapshot(store, null, { text: '二', files: [file('b', 'b.txt')], imagePositions: [] });
  assert.equal(readDraftSnapshot(store, 's1').snapshot.files[0].name, 'a.txt');
  assert.equal(readDraftSnapshot(store, 's2').snapshot, null);
  assert.equal(readDraftSnapshot(store, null).snapshot.files[0].name, 'b.txt');
});

test('清空附件或发送成功后删除副本，兼容只有文字的旧草稿', () => {
  const store = storage();
  writeDraftSnapshot(store, 's1', { text: '一', files: [file('a', 'a.txt')], imagePositions: [] });
  writeDraftSnapshot(store, 's1', { text: '一', files: [], imagePositions: [] });
  assert.equal(store.dump().has(draftFilesKey('s1')), false);
  // 只有文字时不会误建附件副本
  assert.equal(readDraftSnapshot(store, 's1').snapshot, null);
  writeDraftSnapshot(store, 's1', { text: '二', files: [file('b', 'b.txt')], imagePositions: [] });
  dropDraftSnapshot(store, 's1');
  assert.equal(readDraftSnapshot(store, 's1').snapshot, null);
});

test('损坏的副本会被清除并报告，交给界面提示重新添加', () => {
  const store = storage();
  store.setItem(draftFilesKey('s1'), '{not json');
  const first = readDraftSnapshot(store, 's1');
  assert.equal(first.snapshot, null);
  assert.equal(first.error, 'corrupt');
  assert.equal(store.dump().has(draftFilesKey('s1')), false, '损坏副本不能留到下次刷新');
});

test('配额不足或超上限时不留下半截副本，并给出原因', () => {
  const store = storage();
  const huge = { ...file('big', 'big.bin'), dataBase64: `data:application/octet-stream;base64,${'A'.repeat(20_000)}` };
  const rejected = writeDraftSnapshot(store, 's1', { text: '', files: [huge], imagePositions: [] });
  assert.equal(rejected.ok, false);
  assert.match(rejected.reason, /无法|超过/);
  assert.equal(readDraftSnapshot(store, 's1').snapshot, null);
  assert.equal(store.dump().has(draftFilesKey('s1')), false);
});

test('存储完全不可用时返回失败而不是抛错', () => {
  const broken = {
    getItem: () => { throw new Error('denied'); },
    setItem: () => { throw new Error('denied'); },
    removeItem: () => { throw new Error('denied'); }
  };
  assert.equal(readDraftSnapshot(broken, 's1').snapshot, null);
  const result = writeDraftSnapshot(broken, 's1', { text: '', files: [file('a', 'a.txt')], imagePositions: [] });
  assert.equal(result.ok, false);
  assert.match(result.reason, /无法写入/);
  assert.doesNotThrow(() => dropDraftSnapshot(broken, 's1'));
});