const test = require('node:test');
const assert = require('node:assert/strict');

const { joinPath } = require('../src/paths.js');

test('Windows 风格输入按 Windows 规则拼接', () => {
  assert.equal(joinPath('C:\\repo', 'src\\main.js'), 'C:\\repo\\src\\main.js');
  assert.equal(joinPath('C:\\repo', 'src'), 'C:\\repo\\src');
});

test('POSIX 风格输入按 POSIX 规则拼接', () => {
  assert.equal(joinPath('/repo', 'src/main.js'), '/repo/src/main.js');
});

test('输出里不能出现混合分隔符', () => {
  const cases = [['C:\\repo', 'src/main.js'], ['/repo', 'src\\main.js'], ['C:\\repo', 'a\\b/c']];
  for (const [root, relative] of cases) {
    const result = joinPath(root, relative);
    assert.equal(
      result.includes('/') && result.includes('\\'),
      false,
      `${root} + ${relative} 得到 ${result}，混合分隔符在部分平台上解析不出预期结果`
    );
  }
});

test('多级相对路径也能拼对', () => {
  assert.equal(joinPath('C:\\repo', 'a\\b\\c.txt'), 'C:\\repo\\a\\b\\c.txt');
  assert.equal(joinPath('/repo', 'a/b/c.txt'), '/repo/a/b/c.txt');
});
