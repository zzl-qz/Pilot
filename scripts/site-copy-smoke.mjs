// 官网复制按钮：分别验证英文首页 `/` 与中文页 `/zh-CN/index.html` 的 Clipboard 成功、兼容路径成功、双失败手动复制与连击恢复。
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
const baseUrl = `http://127.0.0.1:${server.address().port}`;

async function verifyLocale(page, route, expect) {
  const errors = [];
  const onError = (error) => errors.push(error.message);
  page.on('pageerror', onError);
  try {
    await page.goto(`${baseUrl}${route}`);
    const button = page.locator('.install .copy');
    await button.waitFor();
    const initialHtml = await button.innerHTML();
    const status = page.locator('.copy-status');
    const fallback = page.locator('.copy-fallback');

    // 1) Clipboard API 成功 + 连续双击不污染原始图标
    await page.evaluate(() => {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => {} } });
      document.execCommand = () => { throw new Error('不应走兼容路径'); };
    });
    await button.click();
    await page.waitForFunction(() => document.querySelector('.install .copy')?.classList.contains('done'));
    await button.click();
    assert.match(await status.innerText(), expect.copied);
    await page.waitForFunction(() => !document.querySelector('.install .copy')?.classList.contains('done'), null, { timeout: 3000 });
    assert.equal(await button.innerHTML(), initialHtml, `${route} 连击后必须恢复原始图标`);

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
    assert.match(await status.innerText(), expect.failed);
    assert.match(await status.innerText(), /Ctrl\+C/);
    await fallback.waitFor();
    assert.equal(await fallback.getAttribute('tabindex'), '0', '手动复制块可键盘聚焦');
    assert.equal(await fallback.getAttribute('aria-label'), expect.fallbackAria);
    assert.equal(await page.evaluate(() => document.activeElement?.className), 'copy-fallback');
    const selection = await page.evaluate(() => window.getSelection()?.toString() || '');
    assert.match(selection, /git clone/);
    assert.equal(await button.getAttribute('aria-label'), expect.failedAria);
    assert.ok((await fallback.innerText()).includes('git clone https://github.com/zzl-qz/Pilot.git'));

    // 再次成功复制后清理失败提示
    await page.evaluate(() => {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async () => {} } });
    });
    await button.click();
    await page.waitForFunction(() => document.querySelector('.install .copy')?.classList.contains('done'));
    assert.equal(await fallback.count(), 0);
    assert.match(await status.innerText(), expect.copied);
    assert.deepEqual(errors, []);
  } finally {
    page.off('pageerror', onError);
  }
}

let browser;
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
  page.setDefaultTimeout(8000);
  await verifyLocale(page, '/', {
    copied: /Copied to clipboard/,
    failed: /Copy failed/,
    failedAria: 'Copy failed; select command manually',
    fallbackAria: 'Install command, select to copy manually'
  });
  await verifyLocale(page, '/zh-CN/index.html', {
    copied: /已复制/,
    failed: /复制失败/,
    failedAria: '复制失败，请手动选择命令复制',
    fallbackAria: '安装命令，可手动复制'
  });
  console.log('PASS 官网复制（EN + zh-CN）：Clipboard 成功、双击恢复、兼容路径成功、双失败状态与手动复制');
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}
