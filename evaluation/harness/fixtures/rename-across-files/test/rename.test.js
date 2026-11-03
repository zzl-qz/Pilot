const test = require('node:test');
const assert = require('node:assert/strict');

const userService = require('../src/userService.js');
const { reportLine } = require('../src/report.js');
const { greet } = require('../src/greeting.js');

const user = { name: '阿七', nickname: '七', active: true };

test('导出名改成 displayNameOf', () => {
  assert.equal(typeof userService.displayNameOf, 'function', '应导出 displayNameOf');
  assert.equal(userService.getUserName, undefined, '旧名字不应再导出');
});

test('改名后行为不变', () => {
  assert.equal(userService.displayNameOf(user), '七（阿七）');
  assert.equal(userService.displayNameOf(null), '未知用户');
});

test('所有引用方都同步更新了', () => {
  assert.equal(reportLine(user), '七（阿七） 本月下单 0 次');
  assert.equal(greet(user), '欢迎回来，七（阿七）');
  assert.equal(greet({ name: '乙', active: false }), '你好，乙（未激活）');
});

test('源码里不再残留旧函数名', () => {
  const { readFileSync, readdirSync } = require('node:fs');
  const { join } = require('node:path');
  const dir = join(__dirname, '..', 'src');
  for (const file of readdirSync(dir)) {
    const text = readFileSync(join(dir, file), 'utf8');
    assert.equal(text.includes('getUserName'), false, `${file} 里仍残留 getUserName`);
  }
});
