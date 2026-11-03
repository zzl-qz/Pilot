const test = require('node:test');
const assert = require('node:assert/strict');

const { isSamePath } = require('../src/dedupe.js');

test('Windows 上大小写不同视为同一个文件', () => {
  assert.equal(isSamePath('C:\\repo\\A.txt', 'C:\\repo\\a.txt', 'win32'), true);
});

test('Linux 上大小写不同是两个文件', () => {
  assert.equal(isSamePath('/repo/A.txt', '/repo/a.txt', 'linux'), false);
});

test('macOS 上大小写不同视为同一个文件', () => {
  assert.equal(isSamePath('/Users/a/File.txt', '/Users/a/file.txt', 'darwin'), true);
});

test('相同路径在任何平台都相同', () => {
  assert.equal(isSamePath('/repo/a.txt', '/repo/a.txt', 'linux'), true);
  assert.equal(isSamePath('C:\\a.txt', 'C:\\a.txt', 'win32'), true);
});

test('尾部斜杠不影响判断', () => {
  assert.equal(isSamePath('/repo/a', '/repo/a/', 'linux'), true);
  assert.equal(isSamePath('C:\\repo\\a', 'C:\\repo\\a\\', 'win32'), true);
});
