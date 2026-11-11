import { once } from 'node:events';
import { electronBinary } from '../../../pilot-desktop/brand-host.mjs';
import { createServer } from 'vite';
import { chromium, _electron } from 'playwright-core';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const root = fileURLToPath(new URL('..', import.meta.url));
const temp = await mkdtemp(join(tmpdir(), 'pilot-composer-'));
const server = await createServer({ configFile: false, root, publicDir: '../src/main/resources/static', server: { host: '127.0.0.1', port: 0, hmr: false, proxy: {} }, plugins: [{ name: 'composer-fixture', configureServer(server) {
  server.middlewares.use('/__composer', async (_req, res) => {
    res.setHeader('Content-Type', 'text/html');
    res.end(await server.transformIndexHtml('/__composer', `<html><head><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
      import React from 'react';
      import {createRoot} from 'react-dom/client';
      import {Composer} from '/src/components/Composer.jsx';
      import {UiProvider} from '/src/components/UiProvider.js';
      import {useStore} from '/src/store.js';
      import {api} from '/src/api.js';
      import '/src/styles.css'; import '/src/shell-vanilla.css'; import '/src/styles-addendum.css'; import '/src/workbench.css';
      document.documentElement.dataset.theme='dark'; window.store=useStore; window.api=api; window.sent=[];
      for(const name of Object.keys(api)) if(typeof api[name]==='function') api[name]=async()=>({});
      const attachmentLimits={maxFiles:64,maxFileBytes:2097152,maxTotalBytes:26214400,maxRequestBytes:37000000};
      api.settings=async()=>({attachmentLimits});
      api.sendMessage=async (id, body)=>{window.sent.push({id, body}); if(window.failSend) throw Error('test failure');};
      useStore.setState({sessionId:'test-a',settingsData:{attachmentLimits}});
      useStore.getState().setUi({themeMode:'dark'});
      createRoot(document.getElementById('root')).render(React.createElement(UiProvider, null, React.createElement(Composer)));
    </script></body></html>`));
  });
} }] });
let browser, electron;
async function checkToolbarLayout(page, name) {
  const saved = await page.evaluate(() => {
    const { settingsData, contextStatus, runUsage } = store.getState();
    return { settingsData, contextStatus, runUsage };
  });
  const sizing = await page.addStyleTag({ content: '.composer-wrap { padding: 0 !important; zoom: var(--test-composer-zoom); } .composer { width: var(--test-composer-width); max-width: 100%; }' });
  try {
    for (const viewport of [1440, 768, 390]) {
      await page.setViewportSize({ width: viewport, height: 900 });
      for (const width of [280, 320, 480, 760].filter(width => width <= viewport)) {
        for (const { longName, zoom } of [{ longName: false, zoom: 1 }, { longName: true, zoom: 1 }, { longName: true, zoom: 1.5 }]) {
          await page.evaluate(({ width, longName, zoom }) => {
            document.documentElement.style.setProperty('--test-composer-width', `${width}px`);
            document.documentElement.style.setProperty('--test-composer-zoom', zoom);
            store.setState({
              settingsData: { ...store.getState().settingsData, model: longName ? 'provider/very-long-model-name-for-narrow-chat-panels-2026' : 'gpt-5.6-sol' },
              contextStatus: { usedTokens: 100000, limitTokens: 100000 },
              runUsage: { details: { observedCacheRequests: 1, observedCacheInputTokens: 100000, cacheReadTokens: 99900 } }
            });
          }, { width, longName, zoom });
          await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
          const problems = await page.locator('.composer-bar').evaluate(bar => {
            const bounds = bar.getBoundingClientRect();
            const controls = [...bar.querySelectorAll('button')].map(el => ({ id: el.id || el.className, rect: el.getBoundingClientRect() }));
            const issues = controls.filter(({ rect }) => rect.left < bounds.left - 1 || rect.right > bounds.right + 1 || rect.bottom > bounds.bottom + 1).map(({ id }) => `溢出: ${id}`);
            controls.forEach((a, i) => controls.slice(i + 1).forEach(b => {
              if (Math.min(a.rect.right, b.rect.right) - Math.max(a.rect.left, b.rect.left) > 1 && Math.min(a.rect.bottom, b.rect.bottom) - Math.max(a.rect.top, b.rect.top) > 1) issues.push(`重叠: ${a.id}/${b.id}`);
            }));
            return issues;
          });
          assert.deepEqual(problems, [], `${name}: viewport=${viewport}, composer=${width}, longName=${longName}, zoom=${zoom}`);
          if (viewport === 1440 && width === 320 && longName) await page.locator('.composer').screenshot({ path: join(temp, `${name}-narrow-toolbar-${zoom}.png`) });
        }
      }
    }
  } finally {
    await sizing.evaluate(el => el.remove());
    await page.evaluate(saved => {
      store.setState(saved);
      document.documentElement.style.removeProperty('--test-composer-width');
      document.documentElement.style.removeProperty('--test-composer-zoom');
    }, saved);
  }
  console.log(`${name}: PASS toolbar bounds and non-overlap (desktop narrow pane/tablet/mobile/long model/cache usage/150% CSS zoom)`);
}
async function run(page, name) {
  const errors=[]; page.on('pageerror', e=>{errors.push(e.message); console.error(e.message);});
  page.on('console',m=>{if(m.type()==='error') console.error(m.text());}); await page.goto(url);
  const input=page.locator('#input-flow'); await input.waitFor();
  const chips=page.locator('[data-chip="attachment"]');
  async function transfer(kind, fileName, content='hello', type='text/plain') {
    await input.evaluate((el, {kind,fileName,content,type})=>{
      const data=new DataTransfer(); data.items.add(new File([content],fileName,{type}));
      el.dispatchEvent(kind==='paste' ? new ClipboardEvent('paste',{clipboardData:data,bubbles:true,cancelable:true}) : new DragEvent('drop',{dataTransfer:data,bubbles:true,cancelable:true}));
    },{kind,fileName,content,type});
    await page.waitForFunction(()=>document.querySelector('#input-flow').getAttribute('aria-busy')==='false');
  }
  await input.fill('请检查附件');
  const beforeUriPaste = await page.evaluate(() => store.getState().composerApi.getText());
  // URI-only 剪贴板（如部分 Linux 文件管理器）不伪装成附件，也不污染原输入。
  await input.evaluate(el => {
    const data = new DataTransfer(); data.setData('text/uri-list', 'file:///home/demo/local.txt'); data.setData('text/plain', 'file:///home/demo/local.txt');
    el.dispatchEvent(new ClipboardEvent('paste', {clipboardData:data,bubbles:true,cancelable:true}));
  });
  assert.equal(await page.evaluate(() => store.getState().composerApi.getText()), beforeUriPaste); assert.equal(await chips.count(), 0);
  assert.match(await page.evaluate(() => store.getState().ui.notice.text), /未提供文件内容/);
  // 普通 HTTP / 受限 WebView 不一定提供 randomUUID，附件读取仍应完成。
  await page.evaluate(() => Object.defineProperty(crypto, 'randomUUID', { configurable:true, value:undefined }));
  await transfer('paste','paste.txt'); assert.equal(await chips.count(),1);
  assert.equal(await input.innerText().then(t=>t.includes('请检查附件')),true);
  assert.equal(await page.locator('#send').isEnabled(),true);
  await transfer('paste','paste.txt'); assert.equal(await chips.count(),1,'去重');
  await transfer('drop','drop.txt'); assert.equal(await chips.count(),2);
  await page.locator('input[type=file]').setInputFiles({name:'picker.txt',mimeType:'text/plain',buffer:Buffer.from('picker')});
  await page.waitForFunction(()=>document.querySelectorAll('[data-chip="attachment"]').length===3);
  await transfer('paste','empty.txt',''); assert.equal(await chips.count(),3);
  await transfer('paste','large.txt','x'.repeat(2*1024*1024+1)); assert.equal(await chips.count(),3);
  await page.evaluate(()=>store.setState({sessionId:'test-b'})); await page.waitForTimeout(50); assert.equal(await chips.count(),0);
  await page.evaluate(()=>store.setState({sessionId:'test-a'})); await page.waitForTimeout(50); assert.equal(await chips.count(),3,'切换会话保留附件');
  await page.evaluate(()=>window.failSend=true);
  await page.locator('#send').click(); await page.waitForFunction(()=>!document.querySelector('#send').disabled);
  assert.equal(await chips.count(),3,'失败保留附件');
  await page.evaluate(()=>window.failSend=false);
  await page.locator('#send').click(); await page.waitForFunction(()=>document.querySelectorAll('[data-chip="attachment"]').length===0);
  const body=await page.evaluate(()=>sent.at(-1).body); assert.equal(body.attachments.length,3); assert.match(body.attachments[0].dataBase64,/^data:text\/plain;base64,/);
  assert.equal(await page.locator('#send').isDisabled(),true);
  await transfer('paste','only.txt'); await page.locator('#send').click();
  await page.waitForFunction(()=>document.querySelectorAll('[data-chip="attachment"]').length===0);
  assert.equal(await page.evaluate(()=>sent.at(-1).body.attachments[0].name),'only.txt');
  await transfer('paste','screen.png','image','image/png');
  assert.equal(await page.locator('.chip-thumb').count(),1);
  await page.locator('.chip-remove').click(); assert.equal(await chips.count(),0);
  const originalLimits = await page.evaluate(() => store.getState().settingsData.attachmentLimits);
  await page.evaluate(() => store.setState({settingsData:{attachmentLimits:{maxFiles:1,maxFileBytes:3,maxTotalBytes:3,maxRequestBytes:300}}}));
  await transfer('paste','rejected.txt','four'); assert.equal(await chips.count(),0,'下发单文件限制在读取前生效');
  await transfer('drop','accepted.txt','abc'); assert.equal(await chips.count(),1,'精确边界允许添加');
  await transfer('paste','second.txt','a'); assert.equal(await chips.count(),1,'下发数量限制生效');
  const beforeBudgetRejection = await page.evaluate(() => sent.length);
  await page.evaluate(() => store.setState({settingsData:{attachmentLimits:{maxFiles:1,maxFileBytes:2,maxTotalBytes:2,maxRequestBytes:300}}}));
  await page.locator('#send').click();
  await page.waitForFunction(()=>!document.querySelector('#send').disabled);
  assert.equal(await page.evaluate(() => sent.length), beforeBudgetRejection,'预算变小后拒绝发送旧草稿');
  assert.equal(await chips.count(),1,'拒绝发送保留草稿');
  await page.locator('.chip-remove').click();
  await page.evaluate(attachmentLimits => store.setState({settingsData:{attachmentLimits}}), originalLimits);
  await input.fill('中文'); const before=await page.evaluate(()=>sent.length);
  await input.evaluate(el=>el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',isComposing:true,bubbles:true,cancelable:true})));
  assert.equal(await page.evaluate(()=>sent.length),before,'输入法不发送');
  await input.press('Shift+Enter'); await input.press('a'); assert.equal(await page.evaluate(()=>sent.length),before);
  await checkToolbarLayout(page, name);
  await page.setViewportSize({width:390,height:844}); await page.screenshot({path:join(temp,`${name}-mobile.png`)});
  await page.setViewportSize({width:1440,height:900}); await page.screenshot({path:join(temp,`${name}-desktop.png`)});
  assert.deepEqual(errors,[]); console.log(`${name}: PASS (paste/drop/picker/limits/dedup/drafts/failure/attachment-only/thumbnail/IME/newline)`);
}
let url;
try {
  const listening = once(server.httpServer, 'listening');
  server.httpServer.listen(0, '127.0.0.1');
  await listening; url=`http://127.0.0.1:${server.httpServer.address().port}/__composer`;
  browser=await chromium.launch({channel:'chrome',headless:true}); await run(await browser.newPage(),'chromium'); await browser.close(); browser=null;
  const main=join(temp,'main.cjs');
  await writeFile(main,`const {app,BrowserWindow}=require('electron');app.whenReady().then(()=>{let w=new BrowserWindow({show:false,webPreferences:{contextIsolation:true,nodeIntegration:false}});w.loadURL(${JSON.stringify(url)});});`);
  const env={...process.env}; delete env.ELECTRON_RUN_AS_NODE;
  electron=await _electron.launch({executablePath:electronBinary(),args:[main],env});
  await run(await electron.firstWindow(),'electron');
  // Electron 将 text/uri-list 映射到三平台原生文件格式；保存并恢复所有原有格式。
  {
    const pasteShortcut = process.platform === 'darwin' ? 'Meta+v' : 'Control+v';
    const file = join(temp, 'native-clipboard.txt'); await writeFile(file, 'native clipboard test');
    await electron.evaluate(async ({clipboard, ClipboardItem}) => {
      globalThis.savedClipboard = await Promise.all((await clipboard.read()).map(async item =>
        new ClipboardItem(Object.fromEntries(await Promise.all(item.types.map(async type => [type, await item.getType(type)]))))));
    });
    try {
      await electron.evaluate(async ({clipboard, ClipboardItem}, path) => {
        await clipboard.write([new ClipboardItem({
          'text/uri-list': new Blob([path + '\r\n'], {type:'text/uri-list'})
        })]);
      }, pathToFileURL(file).href);
      const page = await electron.firstWindow();
      await page.locator('#input-flow').focus(); await page.keyboard.press(pasteShortcut);
      await page.waitForFunction(()=>document.querySelector('[data-chip="attachment"]')?.textContent.includes('native-clipboard.txt'));
      console.log(`electron: PASS native ${process.platform} file clipboard + ${pasteShortcut}`);
      browser=await chromium.launch({channel:'chrome',headless:false});
      const web=await browser.newPage(); await web.goto(url); await web.locator('#input-flow').focus(); await web.keyboard.press(pasteShortcut);
      await web.waitForFunction(()=>document.querySelector('[data-chip="attachment"]')?.textContent.includes('native-clipboard.txt'));
      console.log(`chromium: PASS native ${process.platform} file clipboard + ${pasteShortcut}`);
    } finally {
      await electron.evaluate(async ({clipboard}) => {
        if (globalThis.savedClipboard.length) await clipboard.write(globalThis.savedClipboard);
        else clipboard.clear();
      });
    }
  }
  console.log(`Screenshots: ${temp}`);
} finally { await browser?.close(); await electron?.close(); await server.close(); }
