import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { MobileBridge } from '../../pilot-desktop/mobile-bridge.mjs';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(new URL('../../pilot-web/frontend/package.json', import.meta.url));
const { chromium } = require('playwright-core');
const bridge = new MobileBridge();
const directory = await mkdtemp(path.join(tmpdir(), 'pilot-admin-ui-'));
const output = path.join(root, 'build/admin-qa');
let browser;
try {
  const options = { pilotUrl: 'http://127.0.0.1:9999', libraries: path.join(root, 'bridge/build/install/bridge/lib'), dataDir: directory };
  const url = await bridge.start(options);
  assert.equal(await bridge.start(options), url, '重复打开必须复用同一个桥接进程');
  browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(25000);
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.goto(url);
  await page.getByText('还没有配对的手机').waitFor();
  assert.equal(new URL(page.url()).hash, '', '管理凭证读取后应移出地址栏');
  await page.getByRole('button', { name: '生成配对码', exact: true }).click();
  await page.getByAltText('Pilot 手机配对二维码').waitFor({ state: 'visible' });
  assert.equal(await page.getByAltText('Pilot 手机配对二维码').evaluate(img => img.naturalWidth), 768);
  await mkdir(output, { recursive: true });
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 900 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    assert.ok((await page.getByRole('button', { name: '重新生成', exact: true }).boundingBox()).height >= 44);
    await page.screenshot({ path: path.join(output, `pairing-${width}.png`), fullPage: true });
  }
  const first = await page.getByAltText('Pilot 手机配对二维码').getAttribute('src');
  await page.getByRole('button', { name: '重新生成', exact: true }).click();
  await page.waitForFunction(old => document.querySelector('#qr').src !== old, first);
  // 无能力凭证的同源页面也不能生成新配对码。
  assert.equal(await page.evaluate(async () => (await fetch('/api/pair', { method: 'POST' })).status), 403);
  assert.deepEqual(errors, []);
  console.log('通过：桌面启动器复用、管理页鉴权、生成/轮换二维码、1440/768/390 布局。');
} finally { await browser?.close(); bridge.stop(); await rm(directory, { recursive: true, force: true }); }
