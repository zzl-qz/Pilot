const test = require('node:test');
const assert = require('node:assert/strict');
const { existsSync } = require('node:fs');
const { join } = require('node:path');

const root = join(__dirname, '..', 'src');

test('helpers 已经挪到 src/shared 下', () => {
  assert.equal(existsSync(join(root, 'shared', 'helpers.js')), true, 'src/shared/helpers.js 应存在');
  assert.equal(existsSync(join(root, 'utils', 'helpers.js')), false, 'src/utils/helpers.js 应被移走');
});

test('挪动后调用方仍然可用', () => {
  const { apiPath } = require('../src/api.js');
  const { cliLine } = require('../src/cli.js');
  assert.equal(apiPath('Hello World'), '/posts/hello-world');
  assert.equal(cliLine('Hello World'), 'Hello World  hello-world');
});

test('调用方引用的是新路径', () => {
  const { readFileSync } = require('node:fs');
  for (const file of ['api.js', 'cli.js']) {
    const text = readFileSync(join(root, file), 'utf8');
    assert.ok(!text.includes('utils/helpers'), `${file} 仍引用旧路径`);
  }
});
