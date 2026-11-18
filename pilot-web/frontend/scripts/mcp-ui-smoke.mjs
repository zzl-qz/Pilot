import assert from 'node:assert/strict';
import { build, preview } from 'vite';
import { chromium } from 'playwright-core';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { artifactDirectory } from './browser-options.mjs';

// 审批卡的 MCP 信任按钮：必须带上审批时看到的 configHash/toolHash，且先批准再写信任。
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(artifactDirectory(), 'pilot-mcp-ui-'));
const entry = `.mcp-ui-${process.pid}.html`, file = join(root, entry);
const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';import {createRoot} from 'react-dom/client';import {Composer} from '/src/components/Composer.jsx';import {UiProvider} from '/src/components/UiProvider.jsx';import {useStore} from '/src/store.js';import {api} from '/src/api.js';
import '/src/styles.css';import '/src/shell-vanilla.css';import '/src/styles-addendum.css';import '/src/workbench.css';
document.documentElement.dataset.theme='dark';window.store=useStore;window.api=api;window.calls=[];
for(const name of Object.keys(api))if(typeof api[name]==='function')api[name]=async()=>({});
api.settings=async()=>({attachmentLimits:{maxFiles:64,maxFileBytes:2097152,maxTotalBytes:26214400,maxRequestBytes:37000000}});
api.approval=async(callId,approved)=>{calls.push(['approval',callId,approved]);};
api.mcpTrust=async(id,body)=>{calls.push(['trust',id,body]);return {action:body.action,server:body.server,state:'TRUSTED'};};
api.capabilities=async()=>({mcpServers:[]});
useStore.setState({sessionId:'s1',settingsData:{attachmentLimits:{maxFiles:64,maxFileBytes:2097152,maxTotalBytes:26214400,maxRequestBytes:37000000}},showNotice:()=>{},pendingApprovals:{
  a:{callId:'a',agent:null,name:'mcp_tools',args:'启动 fake',structuredArgs:{server:'fake',configHash:'sha256:aa'}},
  b:{callId:'b',agent:null,name:'mcp_call',args:'调用 echo',structuredArgs:{server:'fake',tool:'echo',configHash:'sha256:aa',toolHash:'sha256:bb'}},
  old:{callId:'old',agent:null,name:'mcp_call',args:'旧事件',structuredArgs:{server:'fake',tool:'echo'}}
}});
createRoot(document.getElementById('root')).render(React.createElement(UiProvider,null,React.createElement(Composer)));
</script></body></html>`;
try {
  await writeFile(file, fixture);
  await build({ root, build: { outDir: output, emptyOutDir: false, rollupOptions: { input: file } } });
} finally { await rm(file, { force: true }); }
await cp(join(root, '../src/main/resources/static/fonts'), join(output, 'fonts'), { recursive: true });
const server = await preview({ root, build: { outDir: output }, preview: { host: '127.0.0.1', port: 0, proxy: {} } });
const url = `http://127.0.0.1:${server.httpServer.address().port}/${entry}`;
let browser;
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  page.setDefaultTimeout(8000);
  await page.goto(url);
  const dock = page.locator('#approval-dock');
  await dock.waitFor();
  assert.equal(await dock.getByRole('button', { name: /记住 fake 当前这条启动命令/ }).count(), 2, 'mcp_tools 与 mcp_call 都应能信任服务器');
  assert.equal(await dock.getByRole('button', { name: /记住 fake\.echo/ }).count(), 1);
  // 旧事件没有绑定哈希：只保留批准/拒绝，不显示信任按钮
  const oldCard = dock.locator('.approval-card').nth(2);
  assert.equal(await oldCard.getByRole('button', { name: /记住 fake 当前这条启动命令/ }).count(), 0);

  // 界面展示中文名；用 data-tool-name 区分 mcp_tools / mcp_call，避免再依赖协议原文。
  const first = dock.locator('.approval-card[data-tool-name="mcp_tools"]');
  assert.match(await first.locator('.approval-title').innerText(), /MCP 工具/);
  await first.getByRole('button', { name: /记住 fake 当前这条启动命令/ }).click();
  await page.waitForFunction(() => window.calls.length === 2);
  assert.deepEqual(await page.evaluate(() => calls.map(call => call[0])), ['approval', 'trust'], '必须先批准再写信任');
  assert.equal((await page.evaluate(() => calls[1][2])).configHash, 'sha256:aa');

  const second = dock.locator('.approval-card[data-tool-name="mcp_call"]').first();
  assert.match(await second.locator('.approval-title').innerText(), /MCP 调用/);
  await second.getByRole('button', { name: /记住 fake\.echo/ }).click();
  await page.waitForFunction(() => window.calls.length === 4);
  const calls = await page.evaluate(() => window.calls);
  assert.deepEqual(calls.slice(2).map(call => call[0]), ['approval', 'trust']);
  assert.equal(calls[3][2].configHash, 'sha256:aa');
  assert.equal(calls[3][2].toolHash, 'sha256:bb');
  assert.equal(calls[3][2].tool, 'echo');
  await page.screenshot({ path: join(output, 'approval-trust.png'), animations: 'disabled' });
  console.log('MCP 审批信任按钮：绑定哈希、先批准后信任、旧事件降级通过。Screenshots:', output);
} finally {
  await browser?.close();
  await new Promise(resolve => server.httpServer.close(resolve));
}
