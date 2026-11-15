import assert from 'node:assert/strict';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';
import { fileURLToPath } from 'node:url';

// 队列刷新/队首被取走时，编辑必须继续命中最初选中的那条消息，且没保存的文字不能丢。
const root = fileURLToPath(new URL('..', import.meta.url));
const page = `<html><head><link rel="icon" href="data:,"></head><body><div id="root"></div><script type="module">
import React from 'react';
import { createRoot } from 'react-dom/client';
import { MessageQueue } from '/src/components/MessageQueue.jsx';
import { useStore } from '/src/store.js';
import { api } from '/src/api.js';
import '/src/styles.css'; import '/src/shell-vanilla.css'; import '/src/styles-addendum.css'; import '/src/workbench.css'; import '/src/components/conversation.css';
window.store = useStore; window.calls = []; window.notices = [];
window.queue = [{ content: '按图检查', text: '按图检查', attachments: [{ name: 'one.png', url: '/one.png' }], imagePositions: [0] }];
window.revision = 'r1';
api.sessionQueue = async () => ({ entries: window.queue, revision: window.revision });
api.updateQueueItem = async (sessionId, index, body) => { window.calls.push({ sessionId, index, body }); return {}; };
useStore.setState({
  sessionId: 's1',
  queueEntries: window.queue,
  queueRevision: window.revision,
  showNotice: message => window.notices.push(message)
});
createRoot(document.getElementById('root')).render(React.createElement(MessageQueue));
</script></body></html>`;

const server = await createServer({
  configFile: false,
  root,
  publicDir: '../src/main/resources/static',
  server: { host: '127.0.0.1', port: 0, hmr: false, proxy: {} },
  plugins: [{
    name: 'queue-fixture',
    configureServer(dev) {
      dev.middlewares.use('/__queue', async (_request, response) => {
        response.setHeader('Content-Type', 'text/html');
        response.end(await dev.transformIndexHtml('/__queue', page));
      });
    }
  }]
});
await server.listen();
const url = `http://127.0.0.1:${server.httpServer.address().port}/__queue`;
let browser;
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true });
  const tab = await browser.newPage({ viewport: { width: 1000, height: 700 } });
  tab.setDefaultTimeout(8000);
  const errors = [];
  tab.on('pageerror', error => errors.push(error.message));
  await tab.goto(url);

  await tab.locator('.queue-text').click();
  const editor = tab.locator('#queue-edit textarea');
  await editor.fill('改后的正文');

  // 另一端（或自动取走队首）让原消息消失：编辑区要留在页面上，文字不能丢
  await tab.evaluate(() => {
    window.queue = [{ content: '按图检查', text: '按图检查', attachments: [{ name: 'two.png', url: '/two.png' }], imagePositions: [0] }];
    window.revision = 'r2';
    window.store.setState({ queueEntries: window.queue, queueRevision: 'r2' });
  });
  await tab.locator('.queue-orphan').waitFor();
  assert.equal(await tab.locator('#queue-edit textarea').inputValue(), '改后的正文', '刷新后仍保留未保存文字');
  assert.equal(await tab.getByRole('button', { name: '保存' }).isDisabled(), true, '目标消失时不能保存到别的消息');
  assert.match(await tab.locator('.queue-orphan').innerText(), /已被发送、删除或修改|未保存的输入已保留/);

  // 原消息回到队列（例如只是被移动到别处）：重新命中，保存只改这一条
  await tab.evaluate(() => {
    window.queue = [
      { content: '别的消息', text: '别的消息', attachments: [], imagePositions: [] },
      { content: '按图检查', text: '按图检查', attachments: [{ name: 'one.png', url: '/one.png' }], imagePositions: [0] }
    ];
    window.revision = 'r3';
    window.store.setState({ queueEntries: window.queue, queueRevision: 'r3' });
  });
  await tab.locator('.queue-orphan').waitFor({ state: 'detached' });
  await tab.locator('#queue-edit textarea').waitFor();
  assert.equal(await tab.locator('#queue-edit textarea').inputValue(), '改后的正文');
  await tab.getByRole('button', { name: '保存' }).click();
  await tab.waitForFunction(() => window.calls.length === 1);
  const call = await tab.evaluate(() => window.calls[0]);
  assert.equal(call.index, 1, '应修改原消息现在所在的下标');
  assert.equal(call.body.content, '改后的正文');
  assert.equal(call.body.revision, 'r3', '保存使用最新队列版本');
  assert.equal(await tab.locator('#queue-edit').count(), 0, '保存成功后关闭编辑区');
  assert.ok(!(await tab.evaluate(() => window.notices)).some(text => /已发送、删除或修改/.test(text)));
  assert.deepEqual(errors, []);
  console.log('PASS 队列编辑：目标消失时保留输入、不误改；目标移动后按指纹命中并使用最新版本');
} finally {
  await browser?.close();
  await server.close();
}