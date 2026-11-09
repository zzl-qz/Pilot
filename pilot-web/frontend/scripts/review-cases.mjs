import assert from 'node:assert/strict';
import { join } from 'node:path';

export async function checkReview(page, name, temp) {
  await page.evaluate(() => {
    window.reviewSaved = { diff: api.diff, branch: api.branch, restore: api.restoreChanges, state: store.getState() };
    window.reviewCalls = [];
    window.reviewReport = { gitRepository: true, files: [
      { path: 'src/App.jsx', status: 'M', added: 215, removed: 68 },
      { path: 'src/新 文件.jsx', status: '??', added: 100, removed: 0 },
    ], diff: window.diff };
    api.diff = async () => window.reviewReport;
    store.setState({ running: false, changesState: '', changeReport: window.reviewReport });
    store.getState().setUi({ inspectorOpen: true, inspectorTab: 'changes', reviewExpanded: false, changeView: null });
  });
  const more = page.getByRole('button', { name: '更多变更操作', exact: true });
  const menu = page.getByRole('menu', { name: '更多变更操作' });
  const undoItem = menu.getByRole('menuitem', { name: '撤销全部改动…' });
  const dialog = page.getByRole('dialog', { name: '创建并切换分支', exact: true });
  const openBranch = async () => { await more.click(); await menu.getByRole('menuitem', { name: '创建并切换分支…' }).click(); };
  const openUndo = async () => { await more.click(); await menu.getByRole('menuitem', { name: '撤销全部改动…' }).click(); };
  assert.match(await page.locator('#changes-totals').innerText(), /2 个文件/);
  assert.match(await page.locator('#changes-totals').innerText(), /\+215/);
  assert.equal(await page.getByLabel('新 Git 分支名').count(), 0);
  assert.equal(await undoItem.count(), 0);
  await more.focus();
  await more.press('Enter');
  await menu.waitFor();
  await page.waitForFunction(() => document.activeElement?.getAttribute('role') === 'menuitem');
  await page.keyboard.press('End');
  assert.equal(await undoItem.evaluate(el => document.activeElement === el), true);
  await page.keyboard.press('Escape');
  await menu.waitFor({ state: 'hidden' });
  await page.waitForFunction(() => document.activeElement?.getAttribute('aria-label') === '更多变更操作');
  await openBranch();
  assert.equal(await page.locator('#branch-create').isDisabled(), true);
  await page.getByLabel('新 Git 分支名').fill('   ');
  assert.equal(await page.locator('#branch-create').isDisabled(), true);
  await page.evaluate(() => { api.branch = async (_id, branch) => { reviewCalls.push({ type: 'branch', branch }); throw Error('分支已存在'); }; });
  await page.getByLabel('新 Git 分支名').fill('feature/中文');
  // IME confirmation must not submit the dialog.
  await page.getByLabel('新 Git 分支名').dispatchEvent('keydown', { key: 'Enter', isComposing: true });
  assert.equal(await page.evaluate(() => reviewCalls.length), 0);
  await page.locator('#branch-create').click();
  await dialog.getByRole('alert').waitFor();
  assert.match(await dialog.getByRole('alert').innerText(), /分支已存在/);
  assert.equal(await page.getByLabel('新 Git 分支名').inputValue(), 'feature/中文');
  await page.keyboard.press('Escape'); await dialog.waitFor({ state: 'hidden' });
  await openBranch(); assert.equal(await page.getByLabel('新 Git 分支名').inputValue(), 'feature/中文');
  await page.evaluate(() => { api.branch = (_id, branch) => { reviewCalls.push({ type: 'branch', branch }); return new Promise(resolve => window.finishBranch = resolve); }; });
  await page.locator('#branch-create').click();
  await page.keyboard.press('Enter'); await page.keyboard.press('Escape');
  assert.equal(await dialog.count(), 1, '请求中 Esc 不关闭或重复创建');
  assert.equal(await page.evaluate(() => reviewCalls.length), 2);
  await page.evaluate(() => finishBranch({ name: 'feature/中文' }));
  await dialog.waitFor({ state: 'hidden' });
  await openUndo();
  const undo = page.getByRole('dialog', { name: '撤销全部改动？' });
  assert.match(await undo.innerText(), /1 个未跟踪文件会被删除/);
  await undo.getByRole('button', { name: '取消', exact: true }).click();
  assert.equal(await page.evaluate(() => reviewCalls.filter(c => c.type === 'restore').length), 0);
  await page.evaluate(() => { api.restoreChanges = async (id, body) => { reviewCalls.push({ type: 'restore', id, body }); throw Error('offline'); }; });
  await openUndo(); await page.locator('#review-undo-confirm').click();
  await undo.getByRole('alert').waitFor(); assert.match(await undo.getByRole('alert').innerText(), /撤销失败/);
  // Only the confirmed paths are sent; a file created after confirmation is excluded.
  await page.evaluate(() => {
    store.setState({ changeReport: { ...reviewReport, files: [...reviewReport.files, { path: 'later.txt', status: '??' }] } });
    api.restoreChanges = (id, body) => { reviewCalls.push({ type: 'restore', id, body }); return new Promise(resolve => window.finishRestore = resolve); };
  });
  await page.locator('#review-undo-confirm').click();
  assert.equal(await page.locator('#review-undo-confirm').isDisabled(), true);
  await page.keyboard.press('Escape'); assert.equal(await undo.count(), 1);
  assert.deepEqual(await page.evaluate(() => ({ ...reviewCalls.at(-1).body, paths: reviewCalls.at(-1).body.paths.toSorted() })), { paths: ['src/App.jsx', 'src/新 文件.jsx'], all: false });
  await page.evaluate(() => finishRestore({ restored: ['src/App.jsx'], deleted: [], failed: [{ path: 'src/新 文件.jsx', reason: 'locked' }] }));
  await undo.getByRole('alert').waitFor();
  assert.match(await undo.getByRole('alert').innerText(), /1 个文件未能撤销/);
  assert.equal(await page.locator('#review-undo-confirm').isDisabled(), true);
  await undo.getByRole('button', { name: '关闭', exact: true }).click();
  await page.evaluate(() => { api.restoreChanges = async () => ({ restored: ['src/App.jsx'], deleted: ['src/新 文件.jsx'], failed: [] }); });
  await openUndo(); await page.locator('#review-undo-confirm').click(); await undo.waitFor({ state: 'hidden' });
  // Scope, repository and loading errors never expose destructive actions for stale data.
  await page.getByRole('radio', { name: '最近任务', exact: true }).click(); await more.click(); await menu.waitFor();
  assert.equal(await menu.getByRole('menuitem', { name: '撤销全部改动…' }).count(), 0); await page.keyboard.press('Escape');
  await page.getByRole('radio', { name: '工作区', exact: true }).click();
  await page.evaluate(() => store.setState({ running: true }));
  await more.click(); assert.equal(await menu.getByRole('menuitem', { name: '创建并切换分支…' }).isDisabled(), true);
  const cancels = await page.evaluate(() => calls.filter(c => c.name === 'cancelRun').length);
  await page.keyboard.press('Escape');
  await menu.waitFor({ state: 'hidden' });
  assert.equal(await page.evaluate(() => calls.filter(c => c.name === 'cancelRun').length), cancels, '菜单 Esc 不停止任务');
  await page.evaluate(() => store.setState({ running: false, changesState: 'error' }));
  await more.click(); await menu.waitFor(); assert.equal(await undoItem.count(), 0); await page.keyboard.press('Escape');
  await page.evaluate(() => store.setState({ changeReport: { gitRepository: false, files: [] }, changesState: '' }));
  assert.equal(await more.isDisabled(), true);
  await page.evaluate(() => store.setState({ changeReport: { gitRepository: true, files: [] } }));
  await more.click(); await menu.waitFor(); assert.equal(await undoItem.count(), 0); await page.keyboard.press('Escape');
  await page.evaluate(() => store.setState({ changeReport: { gitRepository: true, files: [{ path: 'only.txt', status: '??', added: 999 }] } }));
  assert.equal(await page.locator('.changes-line-counts').count(), 0, '只有未跟踪文件时不误报增删行');
  // Stale refreshes and mutations must not update another session (including A → B → A).
  await page.evaluate(async () => {
    window.diffResolvers = [];
    api.diff = () => new Promise(resolve => diffResolvers.push(resolve));
    window.loadOne = store.getState().loadChanges(); window.loadTwo = store.getState().loadChanges();
    diffResolvers[1]({ gitRepository: true, files: [{ path: 'new.txt', status: 'M' }] }); await loadTwo;
    diffResolvers[0](reviewReport); await loadOne;
  });
  assert.equal(await page.evaluate(() => store.getState().changeReport.files[0].path), 'new.txt');
  await page.evaluate(async () => {
    const prior = store.getState();
    api.restoreChanges = () => new Promise(resolve => window.staleRestore = resolve);
    window.restoringOld = store.getState().restoreChanges(['new.txt']);
    store.getState().startNewSession();
    if (store.getState().changesState !== '') throw Error('新会话仍被旧撤销状态锁住');
    store.setState({ sessionId: prior.sessionId, sessionTitle: prior.sessionTitle, thread: prior.thread, lastRunId: prior.lastRunId, changesState: 'loading' });
    staleRestore({ restored: ['new.txt'] }); await restoringOld;
  });
  assert.equal(await page.evaluate(() => store.getState().changesState), 'loading');
  await page.evaluate(() => { api.diff = reviewSaved.diff; store.setState({ changesState: '', changeReport: reviewReport }); });
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await page.waitForTimeout(250);
    const bounds = await page.locator('.changes-bar').evaluate(el => ({ width: el.clientWidth, scroll: el.scrollWidth }));
    assert.ok(bounds.scroll <= bounds.width + 1, `${width}px 工具栏无横向溢出`);
    await more.click();
    const box = await menu.boundingBox(); assert.ok(box.x >= 0 && box.x + box.width <= width, `${width}px 菜单保持可见`);
    if (name === 'chromium') await page.screenshot({ path: join(temp, `${name}-review-menu-${width}.png`), animations: 'disabled' });
    await page.keyboard.press('Escape');
    if (name === 'chromium') await page.screenshot({ path: join(temp, `${name}-review-toolbar-${width}.png`), animations: 'disabled' });
    await openBranch();
    const form = await dialog.boundingBox(); assert.ok(form.x >= 0 && form.x + form.width <= width);
    await page.keyboard.press('Escape');
    await dialog.waitFor({ state: 'hidden' });
  }
  await page.evaluate(() => {
    api.diff = reviewSaved.diff; api.branch = reviewSaved.branch; api.restoreChanges = reviewSaved.restore;
    store.setState({ changeReport: reviewSaved.state.changeReport, changesState: '', running: reviewSaved.state.running });
    store.getState().setUi({ reviewExpanded: false });
  });
  await page.setViewportSize({ width: 1440, height: 900 });
  console.log(`${name}: PASS review toolbar, keyboard/IME, errors/retry, confirmation snapshot, stale responses and 3 widths`);
}
