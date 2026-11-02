import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { prepareDevelopmentHost } from './brand-host.mjs';

const root = fileURLToPath(new URL('.', import.meta.url));
const { _electron } = createRequire(new URL('../pilot-web/frontend/package.json', import.meta.url))('playwright-core');
const temp = await mkdtemp(join(tmpdir(), 'pilot-shell-ui-'));
const server = createServer((_, res) => { res.writeHead(200, { 'content-type': 'text/html;charset=utf-8' }); res.end('<!doctype html><h1>Pilot 服务已连接</h1>'); });
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const port = server.address().port;
await new Promise(resolve => server.close(resolve));
const env = { ...process.env, PILOT_URL: `http://127.0.0.1:${port}`, PILOT_BROWSER_HOME: temp };
delete env.ELECTRON_RUN_AS_NODE; delete env.PILOT_DESKTOP_SMOKE;
let desktop;
try {
  desktop = await _electron.launch({ executablePath: prepareDevelopmentHost(), args: [root, `--user-data-dir=${join(temp, 'profile')}`], env });
  const page = await desktop.firstWindow();
  await page.getByRole('heading', { name: '暂时无法连接服务' }).waitFor();
  assert.match(await page.locator('code').innerText(), new RegExp(':' + port));
  const menuRoles = await desktop.evaluate(({ Menu }) => Menu.getApplicationMenu().items.flatMap(item => item.submenu?.items.map(child => child.role) || []));
  for (const role of ['zoomin', 'zoomout', 'resetzoom', 'paste']) assert.ok(menuRoles.map(value => value?.toLowerCase()).includes(role), role);
  await page.screenshot({ path: join(temp, 'desktop-offline.png') });
  await page.getByRole('link', { name: '重新连接' }).click();
  await page.getByRole('heading', { name: '暂时无法连接服务' }).waitFor();
  await new Promise(resolve => server.listen(port, '127.0.0.1', resolve));
  await page.getByRole('link', { name: '重新连接' }).click();
  await page.getByRole('heading', { name: 'Pilot 服务已连接' }).waitFor();
  console.log('PASS: branded desktop, offline retry failure → successful reconnection, native zoom/edit menus');
  console.log('Screenshot:', join(temp, 'desktop-offline.png'));
} finally {
  await desktop?.close();
  if (server.listening) await new Promise(resolve => server.close(resolve));
  await rm(join(temp, 'profile'), { recursive: true, force: true });
}
