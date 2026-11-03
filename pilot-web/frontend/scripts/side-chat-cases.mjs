import assert from 'node:assert/strict';
import { join } from 'node:path';

export async function checkSideChat(page, name, temp) {
  await page.evaluate(() => {
    window.sideSaved = { state: store.getState(), api: { ...api }, EventSource };
    window.sideEvents = [];
    window.sideSources = new Map();
    window.EventSource = class extends EventTarget {
      constructor(url) { super(); this.url = url; sideSources.set(new URL(url, location.href).pathname.split('/')[3], this); }
      close() { this.closed = true; }
    };
    api.history = async () => ({ events: [] }); api.activeRun = async () => ({ runId: null });
    api.sessions = async () => [];
    api.createSideChat = async id => { sideEvents.push(['create', id]); return { sessionId: `side-${id}-${sideEvents.length}`, parentSessionId: id }; };
    api.deleteSession = async id => { sideEvents.push(['delete', id]); };
    api.sendMessage = async (id, body) => { sideEvents.push(['send', id, body.content]); return { runId: `run-${id}` }; };
    api.cancelRun = async id => { sideEvents.push(['stop', id]); };
    store.setState({ sessionId: 'parent-a', workspace: '/workspace/a', sideChats: { items: [], activeId: null }, sideDrafts: {}, running: true });
    store.getState().setUi({ inspectorOpen: true, inspectorTab: 'chat', reviewExpanded: false });
  });
  const input = page.getByRole('textbox', { name: '侧边对话输入' });
  const panel = page.locator('#panel-side-chat');
  await input.waitFor();
  await page.evaluate(() => {
    store.setState({ thread: { ...store.getState().thread, rows: [{ key: 'quote', type: 'user', text: '需要引用的主任务原文' }] } });
    store.getState().composerApi.setText('');
  });
  await page.getByText('需要引用的主任务原文', { exact: true }).evaluate(el => {
    const range = document.createRange(); range.selectNodeContents(el);
    const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);
    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  });
  await page.getByRole('button', { name: '加到输入框', exact: true }).click();
  assert.match(await page.evaluate(() => store.getState().composerApi.getText()), /需要引用的主任务原文/);
  await input.fill('中文输入');
  await input.dispatchEvent('keydown', { key: 'Enter', isComposing: true });
  assert.equal(await page.evaluate(() => sideEvents.filter(e => e[0] === 'send').length), 0);
  await input.fill('A 的草稿');
  await panel.getByRole('button', { name: '＋ 新开', exact: true }).click();
  await page.waitForFunction(() => store.getState().sideChats.items.length === 2);
  assert.equal(await input.inputValue(), '', '新侧边会话不能继承另一条输入');
  await input.fill('B 的草稿');
  await panel.getByRole('tab', { name: '侧边对话', exact: true }).click();
  assert.equal(await input.inputValue(), 'A 的草稿');
  // Explicit quote action keeps the selected tab, not always the first side chat.
  await panel.getByRole('tab', { name: '侧边对话 2', exact: true }).click();
  await page.evaluate(() => store.getState().askSideChat('引用原文'));
  assert.match(await input.inputValue(), /B 的草稿[\s\S]*引用原文/);
  await page.evaluate(() => { api.sendMessage = () => new Promise((_, reject) => window.rejectSideSend = reject); });
  await input.press('Enter');
  await panel.getByRole('tab', { name: '侧边对话', exact: true }).click();
  await page.evaluate(() => rejectSideSend(Error('offline')));
  assert.equal(await input.inputValue(), 'A 的草稿', '旧会话失败不得污染新标签');
  await panel.getByRole('tab', { name: '侧边对话 2', exact: true }).click();
  assert.match(await input.inputValue(), /B 的草稿/);
  await page.evaluate(() => { api.deleteSession = async () => { throw Error('offline'); }; });
  await panel.getByRole('button', { name: '关闭并丢弃这条侧边对话', exact: true }).click();
  assert.equal(await page.evaluate(() => store.getState().sideChats.items.length), 2, '删除失败保留标签');
  await page.evaluate(() => { api.deleteSession = async () => {}; });
  await panel.getByRole('button', { name: '关闭并丢弃这条侧边对话', exact: true }).click();
  await page.waitForFunction(() => store.getState().sideChats.items.length === 1);
  // History may already contain run.started; active endpoint restores the stop button.
  await page.evaluate(() => {
    api.activeRun = async () => ({ runId: 'side-running' });
    store.getState().setUi({ inspectorTab: 'changes' });
  });
  await page.evaluate(() => store.getState().setUi({ inspectorTab: 'chat' }));
  await panel.getByRole('button', { name: '停止', exact: true }).click();
  assert.equal(await page.evaluate(() => sideEvents.at(-1)[1]), 'side-running');
  await page.evaluate(() => {
    const source = sideSources.get(store.getState().sideChats.activeId);
    source.onerror(); source.onopen();
    source.dispatchEvent(new MessageEvent('run.completed', { data: JSON.stringify({ type: 'run.completed', sequence: 1, runId: 'side-running', payload: {} }) }));
    api.sendMessage = id => { sideEvents.push(['pending-send', id]); return new Promise(resolve => window.resolveSideSend = resolve); };
  });
  assert.equal(await panel.getByRole('alert').count(), 0, '恢复连接后清理断线提示');
  await panel.getByRole('button', { name: '发送', exact: true }).waitFor();
  await input.fill('马上停止'); await input.press('Enter');
  await page.waitForFunction(() => sideEvents.some(e => e[0] === 'pending-send'));
  await panel.getByRole('button', { name: '停止', exact: true }).click();
  await page.evaluate(() => resolveSideSend({ runId: 'late-started-run' }));
  await page.waitForFunction(() => sideEvents.some(e => e[0] === 'stop' && e[1] === 'late-started-run')).catch(async failure => {
    throw new Error(`${failure.message}: ${JSON.stringify(await page.evaluate(() => ({ events: sideEvents, text: document.querySelector('#panel-side-chat').innerText })))}`);
  });
  // Creation requests are scoped to their parent and duplicate failures are handled.
  await page.evaluate(async () => {
    store.getState().setUi({ inspectorOpen: false });
    window.sideResolvers = {};
    api.createSideChat = id => new Promise((resolve, reject) => sideResolvers[id] = { resolve, reject });
    store.setState({ sessionId: 'slow-a', sessionEpoch: 500 });
    window.slowA = store.getState().createSideChat();
    store.setState({ sessionId: 'slow-b', sessionEpoch: 501 });
    window.slowB = store.getState().createSideChat();
    sideResolvers['slow-b'].resolve({ sessionId: 'child-b', parentSessionId: 'slow-b' }); await slowB;
    sideResolvers['slow-a'].resolve({ sessionId: 'child-a', parentSessionId: 'slow-a' }); await slowA;
  });
  assert.equal(await page.evaluate(() => store.getState().sideChats.activeId), 'child-b');
  await page.evaluate(async () => {
    store.getState().setUi({ inspectorOpen: false });
    store.setState({ sessionId: 'failed-parent', sessionEpoch: 502 });
    const first = store.getState().createSideChat(); const duplicate = store.getState().createSideChat();
    sideResolvers['failed-parent'].reject(Error('offline'));
    window.sideFailureResults = await Promise.allSettled([first, duplicate]);
  });
  assert.equal(await page.evaluate(() => sideFailureResults.every(r => r.status === 'fulfilled' && r.value === null)), true);
  // Switch a mounted panel to a different parent: it should open that parent's side chat.
  await page.evaluate(() => {
    api.createSideChat = async id => ({ sessionId: `child-${id}`, parentSessionId: id });
    api.activeRun = async () => ({ runId: null });
    store.getState().setUi({ inspectorOpen: true, inspectorTab: 'chat' });
  });
  await input.waitFor();
  await page.evaluate(() => store.setState({ sessionId: 'next-parent', workspace: '/workspace/next', sessionEpoch: 503 }));
  await page.waitForFunction(() => store.getState().sideChats.activeId === 'child-next-parent');
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 900 }); await page.waitForTimeout(250);
    assert.equal(await panel.evaluate(el => el.scrollWidth <= el.clientWidth + 1), true);
    await page.screenshot({ path: join(temp, `${name}-side-chat-${width}.png`) });
  }
  await page.evaluate(() => {
    store.getState().setUi({ inspectorOpen: false });
    Object.assign(api, sideSaved.api); window.EventSource = sideSaved.EventSource;
    store.setState({ ...sideSaved.state, sideChats: { items: [], activeId: null }, sideDrafts: {} });
  });
  await page.setViewportSize({ width: 1440, height: 900 });
  console.log(`${name}: PASS side chat IME, tab drafts, request ownership, failed sends/deletes, restore active run and 3 widths`);
}
