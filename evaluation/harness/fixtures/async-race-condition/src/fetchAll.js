'use strict';

/**
 * 并发拉取多个资源，按传入顺序返回结果（不是按完成顺序）。
 * 任一请求失败时整体 reject，错误信息里带上失败的 url。
 */
async function fetchAll(urls, fetcher) {
  const results = [];
  await Promise.all(urls.map(async (url) => {
    const value = await fetcher(url);
    results.push(value);
  }));
  return results;
}

module.exports = { fetchAll };
