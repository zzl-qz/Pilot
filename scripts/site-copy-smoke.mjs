// 官网复制按钮：Clipboard API 成功、兼容路径成功、两条都失败时的手动复制提示。
// 用法：node scripts/site-copy-smoke.mjs
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '../pilot-web/frontend/node_modules/playwright-core/index.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'site');
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml' };
const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://x');
  const file = join(root, url.pathname === '/' ? 'index.html' : url.pathname.slice(1));
  try {
    const body = await readFile(file);
    response.setHeader('Content-Type', types[extname(file)] || 'application/octet-stream');
    response.end(body);
  } catch {
    response.statusCode = 404;
    response.end('not found');
  }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const url = `http://127.0.0.1:${server.address().port}/`;

let browser;
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
  page.setDefaultTimeout(8000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(url);
  const button = page.locator('.install .copy');
  await button.waitFor();
  const status = page.locator('.copy-status');
  const fallback = page.locator('.copy-fallback');

  // 1) Clipboard API 成功
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => {} } });
    document.execCommand = () => { throw new Error('不应走兼容路径'); };
  });
  await button.click();
  await page.waitForFunction(() => document.querySelector('.install .copy')?.classList.contains('done'));
  assert.match(await status.innerText(), /已复制/);

  // 2) Clipboard 拒绝但兼容路径成功
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => { throw new Error('denied'); } } });
    document.execCommand = command => command === 'copy';
  });
  await button.click();
  await page.waitForFunction(() => document.querySelector('.install .copy')?.classList.contains('done'));
  assert.equal(await fallback.count(), 0);

  // 3) 两条都失败：明确状态 + 可键盘操作的手动复制块
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => { throw new Error('denied'); } } });
    document.execCommand = () => false;
  });
  await button.click();
  await status.waitFor();
  assert.match(await status.innerText(), /复制失败/);
  assert.match(await status.innerText(), /Ctrl\+C/);
  await fallback.waitFor();
  assert.equal(await fallback.getAttribute('tabindex'), '0', '手动复制块可键盘聚焦');
  assert.equal(await page.evaluate(() => document.activeElement?.className), 'copy-fallback');
  const selection = await page.evaluate(() => window.getSelection()?.toString() || '');
  assert.match(selection, /git clone/);
  assert.equal(await button.getAttribute('aria-label'), '复制失败，请手动选择命令复制');
  assert.ok((await fallback.innerText()).includes('git clone https://github.com/zzl-qz/Pilot.git'));

  // 再次成功复制后清理失败提示
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => {} } });
  });
  await button.click();
  await page.waitForFunction(() => document.querySelector('.install .copy')?.classList.contains('done'));
  assert.equal(await fallback.count(), 0);
  assert.match(await status.innerText(), /已复制/);
  assert.deepEqual(errors, []);
  console.log('PASS 官网复制：Clipboard 成功、兼容路径成功、双失败时的状态与手动复制');
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}