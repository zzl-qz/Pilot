const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const ids = ['m01', 'm02', 'm03', 'm04', 'm05', 'm06', 'm07', 'm08', 'm09', 'm10', 'm11', 'm12', 'm13', 'm14', 'm15', 'm16'];

test('共享实现抽出到 common 且被每个模块复用', () => {
  const common = require('../src/common.js');
  assert.equal(typeof common.normalizeKey, 'function', 'src/common.js 必须导出 normalizeKey');
  for (const id of ids) {
    const source = fs.readFileSync(path.join(root, 'src', id + '.js'), 'utf8');
    assert.match(source, /require\('\.\/common\.js'\)/, id + ' 必须复用 common，不允许各写一份');
  }
});

test('不引入第三方依赖、不新建 src/test 之外的顶层条目', () => {
  assert.ok(!fs.existsSync(path.join(root, 'node_modules')), '不得安装任何依赖');
  for (const name of fs.readdirSync(root)) {
    assert.ok(['src', 'test'].includes(name), '不允许新增顶层目录或文件：' + name);
  }
});
