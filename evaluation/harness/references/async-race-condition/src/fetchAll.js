'use strict';
async function fetchAll(urls, fetcher) {
  return Promise.all(urls.map(async url => {
    try { return await fetcher(url); }
    catch (cause) { throw new Error(`拉取 ${url} 失败：${cause?.message ?? cause}`, { cause }); }
  }));
}
module.exports = { fetchAll };
