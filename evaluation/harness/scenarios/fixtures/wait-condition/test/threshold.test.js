const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const { THRESHOLD } = require('../src/threshold.js');

const root = path.join(__dirname, '..');
const digest = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex');

test('scripts/ 与 data/ 不允许被修改（等待行为本身是被测对象）', () => {
  assert.equal(digest(path.join(root, 'scripts', 'slow-check.js')), '26e5c1e7ef3e9a21994bf17e0e423eb577e808e2eee8c93c54f55c7ec713c293');
  assert.equal(digest(path.join(root, 'data', 'seed.txt')), 'c11eae0cc2e648efe410effd75e64dfca8f6b6532b51f6cfa29e5c03503be0d7');
});

test('必须实际等待过后台检查：src/state.json 由检查生成且经历过完整等待', () => {
  const statePath = path.join(root, 'src', 'state.json');
  assert.ok(fs.existsSync(statePath), 'src/state.json 不存在：后台检查没有真正运行完成，等待场景未覆盖');
  const state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
  assert.ok(Number.isFinite(state.waitedMs) && state.waitedMs >= 7000,
    'state.json 显示后台检查未经历完整等待（waitedMs=' + state.waitedMs + '）；不允许用快进环境替代真实等待');
});

test('THRESHOLD 必须等于后台检查得到的值', () => {
  fs.rmSync(path.join(root, 'src', 'state.json'), { force: true });
  const run = spawnSync(process.execPath, [path.join(root, 'scripts', 'slow-check.js')], { timeout: 15000 });
  assert.equal(run.status, 0, 'slow-check 必须完整运行并正常退出');
  const fresh = JSON.parse(fs.readFileSync(path.join(root, 'src', 'state.json'), 'utf8'));
  assert.equal(THRESHOLD, fresh.threshold, 'THRESHOLD 应采用后台检查给出的 ' + fresh.threshold);
});
