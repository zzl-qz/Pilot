const test = require('node:test');
const assert = require('node:assert/strict');

const { fetchAll } = require('../src/fetchAll.js');

// 故意让「慢」的请求先完成，检验结果是否仍按传入顺序排列
function delayedFetcher(seconds) {
  return (url) => new Promise((resolve) => setTimeout(() => resolve(`${url}:${seconds[url]}`), seconds[url]));
}

test('结果按传入顺序返回，不是按完成顺序', async () => {
  const seconds = { b: 5, a: 60, c: 20 };
  const result = await fetchAll(['a', 'b', 'c'], delayedFetcher(seconds));
  assert.deepEqual(result, ['a:60', 'b:5', 'c:20']);
});

test('失败时错误信息带上失败的 url', async () => {
  const fetcher = async (url) => {
    if (url === 'bad') throw new Error('boom');
    return url;
  };
  await assert.rejects(
    () => fetchAll(['ok', 'bad'], fetcher),
    (error) => {
      assert.match(String(error && error.message), /bad/);
      return true;
    }
  );
});

test('全部成功时返回全部结果', async () => {
  const result = await fetchAll(['x', 'y'], async (url) => url);
  assert.deepEqual(result, ['x', 'y']);
});
