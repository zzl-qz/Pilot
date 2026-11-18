import assert from 'node:assert/strict';
import { build, preview } from 'vite';
import { chromium } from 'playwright-core';
import { browserOptions } from './browser-options.mjs';
import { mkdtemp, writeFile, rm, cp } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { artifactDirectory } from './browser-options.mjs';

// 想法挂在右侧「旁白」面板的「记」页签；Composer 的「想法」按钮只负责打开该面板。
const root = fileURLToPath(new URL('..', import.meta.url));
const output = await mkdtemp(join(artifactDirectory(), 'pilot-notes-'));
const entry = `.notes-${process.pid}.html`, file = join(root, entry);
const fixture = `<html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>会话想法</title><link rel="icon" href="data:,"><style>html,body,#root{height:100%;margin:0}#root{display:flex;flex-direction:row;min-height:100%}#shell-main{flex:1;display:flex;flex-direction:column;justify-content:flex-end;min-width:0}#shell-beside{width:360px;border-left:1px solid #333;overflow:auto}</style></head><body><div id="root"></div><script type="module">
import React from 'react';import {createRoot} from 'react-dom/client';import {Composer} from '/src/components/Composer.jsx';import {BesidePanel} from '/src/components/BesidePanel.jsx';import {UiProvider} from '/src/components/UiProvider.jsx';import {useStore} from '/src/store.js';import {api} from '/src/api.js';
import '/src/styles.css';import '/src/shell-vanilla.css';import '/src/styles-addendum.css';import '/src/workbench.css';
document.documentElement.dataset.theme='dark';window.store=useStore;window.api=api;window.sent=[];window.noteSeq=100;window.failNotes=false;
for(const name of Object.keys(api))if(typeof api[name]==='function')api[name]=async()=>({});
if(new URLSearchParams(location.search).has('fresh'))localStorage.removeItem('notes-fixture');
const seed={'test-a':[{id:'n1',content:'执行中想到的边界情况',status:'inbox',createdAt:'2026-11-14T10:00:00Z',updatedAt:'2026-11-14T10:00:00Z'},{id:'n2',content:'回头补一个测试',status:'inbox',createdAt:'2026-11-14T09:00:00Z',updatedAt:'2026-11-14T09:00:00Z'}],'test-b':[{id:'n3',content:'另一个会话的想法',status:'inbox',createdAt:'2026-11-14T08:00:00Z',updatedAt:'2026-11-14T08:00:00Z'}]};
window.serverNotes=JSON.parse(localStorage.getItem('notes-fixture')||'null')||structuredClone(seed);
const persist=()=>localStorage.setItem('notes-fixture',JSON.stringify(serverNotes));
const clone=value=>JSON.parse(JSON.stringify(value));
api.settings=async()=>({attachmentLimits:{maxFiles:64,maxFileBytes:2097152,maxTotalBytes:26214400,maxRequestBytes:37000000}});
api.sendMessage=async(id,body)=>{sent.push(body);return {sessionId:id,runId:'r'};};
api.notes=async id=>clone(serverNotes[id]||[]);
api.createNote=async(id,content)=>{if(failNotes)throw Error('保存失败，请重试');const note={id:'n'+(++noteSeq),content,status:'inbox',createdAt:new Date().toISOString(),updatedAt:new Date().toISOString()};serverNotes[id]=[note,...(serverNotes[id]||[])];persist();return clone(note);};
api.updateNote=async(id,noteId,content)=>{if(failNotes)throw Error('修改失败，请重试');const list=serverNotes[id]||[];const index=list.findIndex(item=>item.id===noteId);if(index<0)throw Error('想法不存在或已删除');list[index]={...list[index],content,updatedAt:new Date().toISOString()};persist();return clone(list[index]);};
api.deleteNote=async(id,noteId)=>{if(failNotes)throw Error('删除失败，请重试');serverNotes[id]=(serverNotes[id]||[]).filter(item=>item.id!==noteId);persist();return null;};
api.updateNoteStatus=async(id,noteId,status)=>{const list=serverNotes[id]||[];const index=list.findIndex(item=>item.id===noteId);if(index<0)throw Error('想法不存在或已删除');list[index]={...list[index],status,updatedAt:new Date().toISOString()};persist();return clone(list[index]);};
useStore.setState({sessionId:'test-a',settingsData:{attachmentLimits:{maxFiles:64,maxFileBytes:2097152,maxTotalBytes:26214400,maxRequestBytes:37000000}},showNotice:()=>{}});
useStore.getState().loadNotes();
function Shell(){
  const open=useStore(s=>s.ui.inspectorOpen && s.ui.inspectorTab==='chat');
  return React.createElement(React.Fragment,null,
    React.createElement('div',{id:'shell-main'},React.createElement(Composer)),
    open?React.createElement('div',{id:'shell-beside'},React.createElement(BesidePanel)):null
  );
}
createRoot(document.getElementById('root')).render(React.createElement(UiProvider,null,React.createElement(Shell)));
</script></body></html>`;
try {
  await writeFile(file, fixture);
  await build({ root, build: { outDir: output, emptyOutDir: false, rollupOptions: { input: file } } });
} finally { await rm(file, { force: true }); }
await cp(join(root, '../src/main/resources/static/fonts'), join(output, 'fonts'), { recursive: true });
const server = await preview({ root, build: { outDir: output }, preview: { host: '127.0.0.1', port: 0, proxy: {} } });
const url = `http://127.0.0.1:${server.httpServer.address().port}/${entry}`;
let browser;

async function openNotes(page) {
  const chip = page.locator('#notes-chip');
  const panel = page.locator('#notes-menu');
  if (await panel.isVisible().catch(() => false)) return panel;
  await chip.click();
  await panel.waitFor({ state: 'visible' });
  assert.equal(await chip.getAttribute('aria-expanded'), 'true');
  return panel;
}

async function closeNotes(page) {
  await page.evaluate(() => store.getState().setUi({ inspectorOpen: false }));
  await page.locator('#notes-menu').waitFor({ state: 'hidden' });
  assert.equal(await page.locator('#notes-chip').getAttribute('aria-expanded'), 'false');
}

async function run(page) {
  page.setDefaultTimeout(8000);
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(url + '?fresh=1');
  const chip = page.locator('#notes-chip');
  const panel = page.locator('#notes-menu');
  const input = page.locator('#input-flow');
  await chip.waitFor();
  assert.equal(await chip.getAttribute('aria-expanded'), 'false');

  // 1. 列表从服务端恢复；打开面板不影响输入框、不发送
  await openNotes(page);
  await page.waitForFunction(() => document.querySelectorAll('#notes-menu .note-row').length === 2);
  assert.deepEqual(await page.locator('#notes-menu .note-content').allInnerTexts(), ['执行中想到的边界情况', '回头补一个测试']);
  assert.equal(await input.innerText(), '');
  assert.equal(await page.evaluate(() => sent.length), 0, '打开想法不能发送消息');
  await closeNotes(page);

  // 2. 新建想法：保存后列表更新，但输入框和发送队列都不动
  await openNotes(page);
  await page.locator('#notes-input').fill('执行期间记下的一条想法');
  await page.locator('#notes-save').click();
  await page.waitForFunction(() => document.querySelectorAll('#notes-menu .note-row').length === 3);
  assert.equal(await page.locator('#notes-menu .note-content').first().innerText(), '执行期间记下的一条想法');
  assert.equal(await input.innerText(), '', '保存想法不能写入输入框');
  assert.equal(await page.evaluate(() => sent.length), 0, '保存想法不能发送消息');
  assert.match(await chip.innerText(), /想法 3/);

  // 3. 「放到输入框」：空输入框填入原文；面板保持打开（旁白是右侧常驻，不再是浮层）
  await page.locator('#notes-menu .note-row').first().getByRole('button', { name: '放到输入框', exact: true }).click();
  assert.equal(await input.innerText(), '执行期间记下的一条想法');
  assert.equal(await panel.isVisible(), true, '回填后旁白面板应仍打开');

  // 4. 已有草稿时另起一行追加，原草稿保留
  await page.evaluate(() => store.getState().composerApi.setText('已有草稿'));
  await page.locator('#notes-menu .note-row').last().getByRole('button', { name: '放到输入框', exact: true }).click();
  assert.equal(await input.innerText(), '已有草稿\n回头补一个测试');
  assert.equal(await page.evaluate(() => sent.length), 0, '回填想法不能发送消息');

  // 5. 执行中也照常编辑和删除
  await page.evaluate(() => store.setState({ running: true }));
  const row = page.locator('#notes-menu .note-row').first();
  await row.getByRole('button', { name: '编辑想法', exact: true }).click();
  const editor = page.locator('#notes-menu textarea[aria-label="编辑想法"]');
  await editor.waitFor();
  assert.equal(await editor.inputValue(), '执行期间记下的一条想法');
  await editor.fill('执行期间改过的想法');
  await page.getByRole('button', { name: '保存修改', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('#notes-menu .note-content')?.textContent === '执行期间改过的想法');
  const secondRow = page.locator('#notes-menu .note-row').nth(1);
  await secondRow.getByRole('button', { name: '删除想法', exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll('#notes-menu .note-row').length === 2);
  assert.deepEqual(await page.locator('#notes-menu .note-content').allInnerTexts(), ['执行期间改过的想法', '回头补一个测试']);
  await page.screenshot({ path: join(output, 'notes-1440.png'), animations: 'disabled' });
  await page.setViewportSize({ width: 390, height: 900 });
  await page.screenshot({ path: join(output, 'notes-390.png'), animations: 'disabled' });
  await page.setViewportSize({ width: 1440, height: 900 });

  // 6. 失败时不静默：提示保留，列表不变
  await page.evaluate(() => { window.failNotes = true; });
  await page.locator('#notes-input').fill('保存失败的一条');
  await page.locator('#notes-save').click();
  await page.getByRole('alert').filter({ hasText: '保存失败' }).waitFor();
  assert.equal(await page.locator('#notes-menu .note-row').count(), 2);
  await page.evaluate(() => { window.failNotes = false; });

  // 7. 刷新后仍在：编辑结果保留、删掉的不回来；会话之间的想法互不影响
  await page.goto(url);
  await chip.waitFor();
  await page.waitForFunction(() => store.getState().notesState === 'ready');
  assert.deepEqual(await page.evaluate(() => store.getState().notes.map(note => note.content)), ['执行期间改过的想法', '回头补一个测试']);
  await page.evaluate(() => store.setState({ sessionId: 'test-b' }));
  await page.evaluate(() => store.getState().loadNotes());
  await page.waitForFunction(() => store.getState().notes.length === 1 && store.getState().notes[0].content === '另一个会话的想法');
  await openNotes(page);
  assert.deepEqual(await page.locator('#notes-menu .note-content').allInnerTexts(), ['另一个会话的想法']);

  // 8. 还没开始任务也能先记想法：先存本机，切走再回来仍在，发出第一条消息前一直可用
  await page.evaluate(() => store.getState().startNewSession());
  await openNotes(page);
  await page.getByText('还没开始任务', { exact: false }).waitFor();
  assert.equal(await chip.isDisabled(), false, '没有会话时也要能记录想法');
  await page.locator('#notes-input').fill('先规划再动手');
  await page.locator('#notes-save').click();
  await page.waitForFunction(() => document.querySelectorAll('#notes-menu .note-row').length === 1);
  assert.equal(await page.locator('#notes-menu .note-content').innerText(), '先规划再动手');
  assert.equal(await page.evaluate(() => Boolean(localStorage.getItem('pilot.notes.draft'))), true);

  await page.evaluate(() => store.setState({ sessionId: 'test-a' }));
  await page.evaluate(() => store.getState().loadNotes());
  await page.waitForFunction(() => store.getState().notes.length === 2);
  await page.evaluate(() => store.getState().startNewSession());
  await page.waitForFunction(() => store.getState().notes.length === 1 && store.getState().notes[0].content === '先规划再动手');
  await openNotes(page);
  assert.equal(await page.locator('#notes-menu .note-content').innerText(), '先规划再动手');

  const draftRow = page.locator('#notes-menu .note-row').first();
  await draftRow.getByRole('button', { name: '编辑想法', exact: true }).click();
  await page.locator('#notes-menu textarea[aria-label="编辑想法"]').fill('先规划再动手（改）');
  await page.getByRole('button', { name: '保存修改', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('#notes-menu .note-content')?.textContent === '先规划再动手（改）');
  assert.match(await page.evaluate(() => localStorage.getItem('pilot.notes.draft')), /先规划再动手（改）/);
  await draftRow.getByRole('button', { name: '删除想法', exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll('#notes-menu .note-row').length === 0);
  assert.equal(await page.evaluate(() => localStorage.getItem('pilot.notes.draft')), null);

  assert.deepEqual(errors, []);
  console.log('想法：旁白面板新建/回填/追加/编辑/删除/失败提示/刷新恢复/会话隔离/规划期草稿与三种屏宽通过。Screenshots:', output);
}

try {
  browser = await chromium.launch(browserOptions());
  await run(await browser.newPage());
  console.log('PASS');
} finally {
  await browser?.close();
  await new Promise(resolve => server.httpServer.close(resolve));
}
