import assert from 'node:assert/strict';
import { join } from 'node:path';

export async function checkQueue(page, name, temp) {
  await page.evaluate(() => {
    window.queueOriginalApi = { ...api };
    store.setState(s => { s.running = true; s.runId = 'queue-run'; s.queueBusy = false; s.ui.inspectorOpen = false; s.ui.sidebarOpen = false; });
    window.pendingGuidanceCalls = 0;
    api.steerQueueItem = () => { pendingGuidanceCalls++; return new Promise(resolve => { window.resolveGuidance = resolve; }); };
    store.setState({ queueEntries: [{ content: '立即引导' }, { content: '之后测试' }], queueRevision: 'one' });
    window.guidanceOne = store.getState().steerQueueItem(0);
    window.guidanceTwo = store.getState().steerQueueItem(0);
  });
  assert.equal(await page.evaluate(() => pendingGuidanceCalls), 1, '并发点击只提交一次');
  assert.equal(await page.getByRole('button', { name: '引导这条排队消息' }).first().isEnabled(), false);
  await page.evaluate(async () => { api.queue = async () => ({ entries: [{ content: '之后测试' }], revision: 'two' }); resolveGuidance({}); await Promise.all([guidanceOne, guidanceTwo]); });
  // 旧的读取不能覆盖较新的队列，更不能写入切换后的会话。
  await page.evaluate(() => {
    api.queue = () => new Promise(resolve => { window.oldQueueResolve = resolve; });
    window.oldQueueRead = store.getState().loadQueue();
  });
  await page.evaluate(async () => {
    api.queue = async () => ({ entries: [{ content: '最新队列' }], revision: 'latest' }); await store.getState().loadQueue();
    oldQueueResolve({ entries: [{ content: '过期队列' }], revision: 'old' }); await oldQueueRead;
  });
  assert.equal(await page.locator('.queue-text').innerText(), '最新队列');
  await page.evaluate(() => {
    api.queue = () => new Promise(resolve => { window.switchedQueueResolve = resolve; }); window.switchedQueueRead = store.getState().loadQueue();
    store.setState(s => { s.sessionId = 'queue-new'; s.sessionEpoch++; s.queueEntries = []; });
  });
  await page.evaluate(async () => { switchedQueueResolve({ entries: [{ content: '别的会话' }] }); await switchedQueueRead; });
  assert.equal(await page.locator('.queue-item').count(), 0);
  await page.evaluate(() => {
    store.setState({ running: true, runId: 'queue-run', queueEntries: [
      { content: '保留附件与草稿，逐项检查输入行为和三平台兼容性' }, { content: '完成后运行所有相关边界测试' }
    ] });
  });
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 900 });
    if (width <= 960) await page.waitForFunction(() => document.querySelector('#sidebar').getBoundingClientRect().right <= 1);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true);
    assert.equal(await page.locator('.queue-actions').first().evaluate(el => getComputedStyle(el).opacity), '1', '引导始终可见');
    await page.getByRole('button', { name: '更多排队操作' }).first().click();
    assert.equal(await page.getByRole('button', { name: '上移', exact: true }).isEnabled(), false);
    const box = await page.locator('.queue-more-menu').boundingBox();
    assert.ok(box.x >= 0 && box.x + box.width <= width + 1 && box.y >= 0);
    await page.keyboard.press('Escape'); assert.equal(await page.locator('.queue-more-menu').count(), 0);
    await page.screenshot({ path: join(temp, `${name}-queue-${width}.png`) });
  }
  // 编辑文件消息只改正文；Esc 先关闭编辑器，不停止正在运行的任务。
  await page.evaluate(() => {
    window.queuedFileBlock = '[附件 notes.txt (text/plain)]\n保留原始文件内容\n[附件结束 notes.txt]';
    store.setState({queueEntries:[{content:'检查附件\n\n'+queuedFileBlock}],queueRevision:'file'});
    api.updateQueueItem=async(_id,_index,body)=>{window.savedQueueBody=body;};
    api.queue=async()=>({entries:[{content:window.savedQueueBody?.content || '检查附件\n\n'+queuedFileBlock}],revision:'file2'});
  });
  const cancelled = await page.evaluate(()=>calls.filter(c=>c.name==='cancelRun').length);
  await page.getByRole('button',{name:'编辑这条排队消息'}).click();
  assert.equal(await page.getByLabel('编辑排队消息').inputValue(),'检查附件');
  await page.getByLabel('编辑排队消息').press('Escape');
  assert.equal(await page.locator('#queue-edit').count(),0);
  assert.equal(await page.evaluate(()=>calls.filter(c=>c.name==='cancelRun').length),cancelled);
  await page.getByRole('button',{name:'编辑这条排队消息'}).click();
  await page.getByLabel('编辑排队消息').fill('新的引导内容');
  await page.locator('#queue-edit').getByRole('button',{name:'保存',exact:true}).click();
  await page.waitForFunction(()=>!store.getState().queueBusy);
  assert.match(await page.evaluate(()=>savedQueueBody.content),/新的引导内容/);
  assert.match(await page.evaluate(()=>savedQueueBody.content),/保留原始文件内容/);
  // 引导事件回放里的图片要真正渲染；不能出现不适用的编辑/分支按钮。
  await page.evaluate(() => {
    store.setState(s => { s.thread.rows = [{ key: 'guidance', type: 'user', sequence: 2, steered: true, text: '按图修改', imagePositions: [1], attachments: [{ name: '参考.png', mimeType: 'image/png', dataBase64: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aBv8AAAAASUVORK5CYII=' }] }]; });
  });
  assert.equal(await page.locator('.bubble-image img').count(), 1);
  assert.equal(await page.getByRole('button', { name: '编辑并重新发送', exact: true }).count(), 0);
  assert.equal(await page.getByRole('button', { name: '回退或另开分支', exact: true }).count(), 0);
  await page.evaluate(() => { Object.assign(api, queueOriginalApi); store.setState({ running: false, queueEntries: [], queueBusy: false }); });
  await page.setViewportSize({ width: 1440, height: 900 });
}
