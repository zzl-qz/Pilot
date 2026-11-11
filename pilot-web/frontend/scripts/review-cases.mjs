import assert from 'node:assert/strict';
import { join } from 'node:path';

async function checkDiffTruncation(page) {
  await page.evaluate(() => {
    const patch = path => [
      `diff --git a/${path} b/${path}`, `--- a/${path}`, `+++ b/${path}`,
      '@@ -1 +1 @@', '-old', '+new',
    ].join('\n');
    const complete = { path: 'complete.txt', status: 'M', added: 1, removed: 1, diff: patch('complete.txt') };
    const partial = { path: 'partial.txt', status: 'M', added: 215, removed: 68, diff: patch('partial.txt') };
    const omitted = { path: 'omitted.txt', status: 'M', added: 1, removed: 1, diff: '… diff 已截断 …' };
    const unavailable = { path: 'unavailable.txt', status: 'M', added: 1, removed: 1 };
    const report = {
      gitRepository: true, truncated: true,
      files: [complete, partial, omitted, unavailable].map(({ diff, ...file }) => file),
      diff: [complete.diff, partial.diff, '… diff 已截断 …'].join('\n'),
    };
    window.diffTruncationSaved = { diff: api.diff, fileDiff: api.fileDiff, runChanges: api.runChanges };
    api.diff = async () => report;
    api.fileDiff = async (_id, path) => {
      if (path === omitted.path) return { diff: patch(path), truncated: true };
      throw Error('读取失败');
    };
    api.runChanges = async () => ({ gitRepository: true, truncated: true, files: [complete, omitted] });
    store.setState({ changeReport: report });
    store.getState().setUi({ changeView: complete.path });
  });

  const diff = page.locator('#change-diff');
  const select = path => page.evaluate(path => store.getState().setUi({ changeView: path }), path);
  await diff.locator('.diff-line.is-add').waitFor();
  assert.equal(await diff.getByRole('status').count(), 0);
  await select('partial.txt');
  await diff.getByRole('status').filter({ hasText: '当前文件可能未显示完整' }).waitFor();
  assert.equal(await diff.locator('.diff-line.is-ctx').count(), 0);
  assert.equal(await diff.locator('button.n').count(), 2);
  assert.equal(await page.locator('.change-view-stat .add').innerText(), '+215');

  await select('omitted.txt');
  await diff.getByRole('status').filter({ hasText: '只显示了前一部分' }).waitFor();
  await diff.locator('.diff-line.is-add').waitFor();
  await select('unavailable.txt');
  await diff.getByText('读取差异失败，点右上角的刷新再试一次。').waitFor();

  await page.getByLabel('审阅范围').selectOption('run');
  await page.locator('#change-files').getByText('complete.txt', { exact: true }).waitFor();
  await select('complete.txt');
  await diff.locator('.diff-line.is-add').waitFor();
  assert.equal(await diff.getByRole('status').count(), 0);
  await select('omitted.txt');
  await diff.getByRole('status').filter({ hasText: '暂无可预览的片段' }).waitFor();
  assert.equal(await diff.locator('button.n').count(), 0);

  await page.getByLabel('审阅范围').selectOption('workspace');
  await page.evaluate(() => {
    Object.assign(api, window.diffTruncationSaved);
    store.setState({ changeReport: window.reviewReport });
  });
}

async function checkSnapshotReview(page) {
  await page.evaluate(async () => {
    const state = store.getState();
    window.snapshotReviewSaved = {
      diff: api.diff, runChanges: api.runChanges, changeReport: state.changeReport, thread: state.thread,
      running: state.running, runId: state.runId, lastRunId: state.lastRunId,
    };
    const file = (path, revised) => ({
      path, status: 'M', added: 1, removed: 1,
      diff: [`diff --git a/${path} b/${path}`, `--- a/${path}`, `+++ b/${path}`,
        '@@ -1 +1 @@', '-旧行', `+${revised}`].join('\n'),
    });
    const previous = file('上一轮.txt', '上一轮的文件版本');
    const latest = file('本轮.txt', '本轮的文件版本');
    api.diff = async () => ({ gitRepository: false, files: [], diff: '' });
    api.runChanges = async id => ({
      gitRepository: false, snapshot: true, files: id === 'previous-run' ? [previous] : [latest, {
        path: '图片.bin', status: 'A', added: 0, removed: 0, diff: '', note: '二进制文件无法显示逐行差异。',
      }],
    });
    store.setState({
      changeReport: { gitRepository: false, files: [], diff: '' }, running: true, runId: 'latest-run', lastRunId: null,
      thread: { ...state.thread, rows: [...state.thread.rows, {
        key: 'result:previous-run', type: 'result', title: '上一轮结束', tone: 'text', detail: null,
        changedFiles: [previous], runIds: ['previous-run'], start: 900, end: 900,
      }] },
    });
    await store.getState().loadChanges();
  });
  const scope = page.getByLabel('审阅范围');
  const diff = page.locator('#change-diff');
  await scope.locator('option[value="workspace"][disabled]').waitFor({ state: 'attached' });
  assert.equal(await scope.inputValue(), 'run');
  await page.getByText('本轮结束后显示逐行差异', { exact: true }).waitFor();

  await page.evaluate(() => store.setState({ running: false }));
  await page.locator('#change-files').getByText('本轮.txt', { exact: true }).click();
  await diff.locator('.is-add > code').filter({ hasText: '本轮的文件版本' }).waitFor();
  await diff.getByRole('button', { name: '选择现文件第 1 行' }).click();
  await page.getByRole('textbox', { name: '审阅意见' }).waitFor();
  await page.locator('#change-files').getByText('图片.bin', { exact: true }).click();
  await diff.getByText('二进制文件无法显示逐行差异。', { exact: true }).waitFor();

  await page.locator('.turn-card-files').getByText('上一轮.txt', { exact: true }).click();
  assert.equal(await scope.inputValue(), 'selected');
  await diff.locator('.is-add > code').filter({ hasText: '上一轮的文件版本' }).waitFor();
  await scope.selectOption('run');
  await page.locator('#change-files').getByText('本轮.txt', { exact: true }).waitFor();

  await page.evaluate(() => {
    const { diff, runChanges, ...state } = window.snapshotReviewSaved;
    Object.assign(api, { diff, runChanges });
    store.setState(state);
    store.getState().setUi({ reviewRunId: null, changeView: null });
  });
  await scope.selectOption('workspace');
}

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
  const menu = page.getByRole('menu', { name: '变更操作' });
  const undoItem = menu.getByRole('menuitem', { name: '撤销全部改动…' });
  const dialog = page.getByRole('dialog', { name: '创建并切换分支', exact: true });
  const openBranch = async () => { await more.click(); await menu.getByRole('menuitem', { name: '创建并切换分支…' }).click(); };
  const openUndo = async () => { await more.click(); await menu.getByRole('menuitem', { name: '撤销全部改动…' }).click(); };
  assert.match(await page.locator('#changes-totals').innerText(), /2 个文件/);
  assert.match(await page.locator('#changes-totals').innerText(), /\+215/);
  await checkDiffTruncation(page);
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
  assert.equal(await page.locator('#review-action-dialog').isVisible(), true, '请求中 Esc 不关闭或重复创建');
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
  await page.keyboard.press('Escape'); assert.equal(await page.locator('#review-action-dialog').isVisible(), true);
  assert.deepEqual(await page.evaluate(() => ({ ...reviewCalls.at(-1).body, paths: reviewCalls.at(-1).body.paths.toSorted() })), { paths: ['src/App.jsx', 'src/新 文件.jsx'], all: false });
  await page.evaluate(() => finishRestore({ restored: ['src/App.jsx'], deleted: [], failed: [{ path: 'src/新 文件.jsx', reason: 'locked' }] }));
  await undo.getByRole('alert').waitFor();
  assert.match(await undo.getByRole('alert').innerText(), /1 个文件未能撤销/);
  assert.equal(await page.locator('#review-undo-confirm').isDisabled(), true);
  await undo.getByRole('button', { name: '关闭', exact: true }).click();
  await page.evaluate(() => { api.restoreChanges = async () => ({ restored: ['src/App.jsx'], deleted: ['src/新 文件.jsx'], failed: [] }); });
  await openUndo(); await page.locator('#review-undo-confirm').click(); await undo.waitFor({ state: 'hidden' });
  // Scope, repository and loading errors never expose destructive actions for stale data.
  await page.getByLabel('审阅范围').selectOption('run'); await more.click(); await menu.waitFor();
  assert.equal(await menu.getByRole('menuitem', { name: '撤销全部改动…' }).count(), 0); await page.keyboard.press('Escape');
  await page.getByLabel('审阅范围').selectOption('workspace');
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
  await page.setViewportSize({ width: 1440, height: 900 });
  await checkSnapshotReview(page);
  await page.evaluate(() => {
    api.diff = reviewSaved.diff; api.branch = reviewSaved.branch; api.restoreChanges = reviewSaved.restore;
    store.setState({ changeReport: reviewSaved.state.changeReport, changesState: '', running: reviewSaved.state.running });
    store.getState().setUi({ reviewExpanded: false });
  });
  await page.setViewportSize({ width: 1440, height: 900 });
  console.log(`${name}: PASS run snapshot review, diff truncation, toolbar, keyboard/IME, errors/retry, confirmation snapshot, stale responses and 3 widths`);
}
