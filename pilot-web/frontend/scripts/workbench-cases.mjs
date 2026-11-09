import assert from 'node:assert/strict';
import { join } from 'node:path';

// 与真实 App 共用组件；网络响应固定，避免回归测试修改个人项目或凭据。
export async function checkWorkbench(page, name, output) {
  await page.evaluate(() => {
    const entry = (name, directory = false, parent = '') => ({ name, directory, path: parent ? `${parent}/${name}` : name });
    api.files = api.workspaceTree = async (_, path) => ({ entries: path === 'src' ? [entry('组件 与空间.jsx', false, 'src')] : path ? [] : [entry('src', true), entry('空目录', true), entry('readme.md')] });
    api.file = api.workspaceFile = async (_, path) => ({ path, content: '文件预览：' + path, binary: false });
    api.workspaceRoots = async () => [{ name: '工作区', path: '/workspace' }];
    api.workspaceBrowse = async path => {
      if (path === '/missing') throw Error('目录不存在');
      return { path: path || '/workspace', parent: path === '/workspace' ? '/' : '/workspace', directories: path === '/workspace' ? [{ name: 'demo', path: '/workspace/demo' }] : [] };
    };
    store.setState({ workspace: '/workspace/demo', sessionId: 'ui-audit', running: false,
      projects: [{ id: 'existing-project', name: '现有项目', folders: ['D:\\项目 空格', '/workspace/demo'] }] });
    store.getState().setUi({ sidebarOpen: true, inspectorOpen: false, reviewExpanded: false });
  });
  await page.evaluate(() => store.getState().setUi({ projectEditor: { id: 'existing-project' } }));
  await page.locator('#project-name').waitFor();
  assert.equal(await page.locator('#project-name').inputValue(), '现有项目');
  assert.equal(await page.locator('#project-folders').inputValue(), 'D:\\项目 空格\n/workspace/demo');
  await page.evaluate(() => { window.savedProjectRename = api.renameProject; api.renameProject = async () => { throw Error('保存项目失败'); }; });
  await page.locator('#project-name').fill('修改后的名称'); await page.locator('#project-save').click();
  assert.equal(await page.locator('#project-name').inputValue(), '修改后的名称');
  await page.evaluate(() => { api.renameProject = window.savedProjectRename; });
  await page.keyboard.press('Escape');
  await page.evaluate(() => store.getState().setUi({ paletteOpen: true }));
  await page.locator('#palette-input').fill('新任务');
  await page.locator('#palette-input').dispatchEvent('keydown', { key: 'Enter', isComposing: true });
  assert.equal(await page.locator('#palette').count(), 1, '输入法确认候选字不能执行命令');
  await page.keyboard.press('Escape');
  await page.evaluate(() => store.setState({ task: { originalGoal: '验证输入法', constraints: [], acceptance: [] } }));
  await page.locator('#task-contract-summary').click();
  await page.locator('#task-add-criterion').fill('新增验收条件');
  await page.locator('#task-add-criterion').dispatchEvent('keydown', { key: 'Enter', isComposing: true });
  assert.equal(await page.locator('#task-acceptance').inputValue(), '', '输入法确认候选字不能提前新增验收项');
  await page.locator('#task-add-criterion').press('Enter');
  assert.match(await page.locator('#task-acceptance').inputValue(), /新增验收条件/);
  await page.evaluate(() => store.setState({ task: null, taskDirty: false }));
  await page.evaluate(() => {
    window.savedOpenWorktree = store.getState().openWorktree; window.branchCalls = [];
    store.setState({ openWorktree: async branch => branchCalls.push(branch) });
  });
  await page.locator('#workspace-name').click();
  const branchInput = page.getByLabel('worktree 分支', { exact: true });
  await branchInput.fill('中文分支');
  await branchInput.dispatchEvent('keydown', { key: 'Enter', isComposing: true });
  assert.deepEqual(await page.evaluate(() => branchCalls), [], '确认输入法候选字不能创建 worktree');
  await branchInput.press('Enter');
  assert.deepEqual(await page.evaluate(() => branchCalls), ['中文分支']);
  await page.evaluate(() => store.setState({ openWorktree: window.savedOpenWorktree }));
  await page.getByRole('button', { name: '工作区文件', exact: true }).click();
  const tree = page.getByRole('tree', { name: '工作区文件' });
  const folder = tree.getByRole('treeitem', { name: 'src', exact: true });
  await folder.click();
  const child = tree.getByRole('treeitem', { name: '组件 与空间.jsx', exact: true });
  await child.waitFor();
  await folder.press('ArrowRight');
  assert.equal(await page.evaluate(() => document.activeElement.dataset.path), 'src/组件 与空间.jsx');
  await child.press('Enter');
  await page.locator('#preview-body').waitFor();
  assert.match(await page.locator('#activity').innerText(), /文件预览：src\/组件 与空间.jsx/);
  assert.equal(await child.getAttribute('aria-selected'), 'true');
  await child.press('ArrowLeft'); await folder.press('ArrowLeft');
  assert.equal(await folder.getAttribute('aria-expanded'), 'false');
  const empty = tree.getByRole('treeitem', { name: '空目录', exact: true });
  await empty.click(); await tree.getByText('空目录', { exact: true }).last().waitFor();
  await empty.press('ArrowRight');
  assert.equal(await page.evaluate(() => document.activeElement.dataset.path), '空目录', '空目录的右箭头不能跳到兄弟文件');
  await tree.getByRole('treeitem', { name: 'readme.md', exact: true }).click();
  await page.evaluate(() => { api.files = async () => ({ entries: [{ name: 'fresh.md', path: 'fresh.md', directory: false }] }); });
  await page.getByLabel('刷新文件树', { exact: true }).click();
  await tree.getByRole('treeitem', { name: 'fresh.md' }).waitFor();
  await page.waitForFunction(() => document.querySelectorAll('[role=treeitem][tabindex="0"]').length === 1);
  assert.equal(await tree.locator('[role=treeitem][tabindex="0"]').count(), 1, '删除焦点文件后仍能 Tab 进入树');
  await page.evaluate(() => { api.files = async () => { throw Error('读取失败'); }; });
  await page.getByLabel('刷新文件树', { exact: true }).click();
  await tree.getByRole('alert').waitFor();
  await page.evaluate(() => { api.files = async () => ({ entries: [] }); });
  await tree.getByRole('button', { name: '重试' }).click();
  await tree.getByText('空目录', { exact: true }).waitFor();

  // 原生根数组 + directories 字段，错误不关闭、无子目录可选、输入未解析不可提交。
  await page.locator('.workspace-chip').click();
  await page.getByText('这个文件夹没有子目录，可以直接选择。', { exact: true }).waitFor();
  await page.locator('#picker-up').click();
  await page.locator('#picker-list').getByRole('button', { name: 'demo', exact: true }).click();
  await page.locator('#picker-path').fill('/missing');
  assert.equal(await page.locator('#picker-confirm').isDisabled(), true);
  await page.locator('#picker-go').click(); await page.locator('#picker-error').waitFor();
  assert.equal(await page.locator('#picker-confirm').isDisabled(), true);
  await page.locator('#picker-path').fill('/workspace/demo'); await page.locator('#picker-go').click();
  await page.waitForFunction(() => !document.querySelector('#picker-confirm').disabled);
  await page.evaluate(() => { api.switchWorkspace = async () => { throw Error('切换失败'); }; });
  await page.locator('#picker-confirm').click(); await page.locator('#picker-error').waitFor();
  assert.match(await page.locator('#picker-error').innerText(), /切换失败/);
  await page.keyboard.press('Escape');
  assert.equal(await page.evaluate(() => document.activeElement.classList.contains('workspace-chip')), true);

  // 每个设置页必须留在视口内；Tab/Shift-Tab 被约束在弹窗，Escape 返回触发按钮。
  await page.evaluate(() => {
    api.validation = async () => ({ report: { current: true, reason: '当前检查已通过' } });
    api.maintenanceStatus = async () => ({ storage: { healthy: false, issues: [{ area: '数据目录', reason: '权限不足，无法写入', at: '2026-11-06T00:00:00Z' }] } });
  });
  await page.getByRole('button', { name: '更多功能', exact: true }).click();
  await page.locator('#settings-open').click();
  for (const section of ['外观', '模型', '权限', '技能', '用量', '诊断', '关于']) {
    await page.locator('.settings-nav').getByRole('button', { name: section, exact: true }).click();
    assert.equal(await page.locator('.settings-body').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true, section + ' 无横向溢出');
  }
  // 数量提示不等于可选择：必须验证实际列表与表单值，防止模型探测只返回一行提示。
  await page.locator('.settings-nav').getByRole('button', { name: '模型', exact: true }).click();
  await page.evaluate(() => { api.probeModels = async () => ({ models: ['probe-first', 'probe-second'] }); });
  await page.locator('#settings-model-fetch').click();
  await page.getByRole('option', { name: 'probe-second', exact: true }).click();
  assert.equal(await page.locator('#settings-model-name').inputValue(), 'probe-second');
  await page.locator('.settings-nav').getByRole('button', { name: '诊断', exact: true }).click();
  await page.getByText('存储告警：权限不足，无法写入', { exact: true }).waitFor();
  await page.evaluate(() => { api.purgeNow = async () => ({ events: 2, snapshotRuns: 1, traces: 3, backups: 4, validationFiles: 5 }); });
  await page.getByRole('button', { name: '清理过期数据', exact: true }).click();
  await page.locator('#confirm-dialog').getByRole('button', { name: '确认清理' }).click();
  await page.getByText('已清理 2 条事件、1 轮快照、3 条调用记录、4 份备份和 5 个校验文件。', { exact: true }).waitFor();
  const validation = page.getByText('校验证据：', { exact: false });
  await validation.waitFor();
  assert.match(await validation.innerText(), /已覆盖当前产物/);
  assert.equal(await validation.evaluate(el => el.classList.contains('is-error')), false);
  await page.evaluate(() => { api.validation = async () => ({ report: { current: false, reason: '产物已变化' } }); });
  await page.locator('.settings-nav').getByRole('button', { name: '关于', exact: true }).click();
  await page.locator('.settings-nav').getByRole('button', { name: '诊断', exact: true }).click();
  await page.getByText('校验证据：未覆盖当前产物（产物已变化）', { exact: true }).waitFor();
  assert.equal(await validation.evaluate(el => el.classList.contains('is-error')), true);
  for (let i = 0; i < 14; i++) await page.keyboard.press('Tab');
  assert.equal(await page.evaluate(() => !!document.activeElement.closest('#settings')), true);
  for (let i = 0; i < 14; i++) await page.keyboard.press('Shift+Tab');
  assert.equal(await page.evaluate(() => !!document.activeElement.closest('#settings')), true);
  await page.locator('.settings-nav').getByRole('button', { name: '外观', exact: true }).click();
  await page.getByRole('radio', { name: '浅色', exact: true }).click();
  assert.equal(await page.locator('html').getAttribute('data-theme'), 'light');
  if (name === 'chromium') await page.screenshot({ path: join(output, `${name}-settings-light.png`), animations: 'disabled' });
  await page.getByRole('radio', { name: '深色', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 700 });
  for (const section of ['模型', '诊断', '关于']) {
    await page.getByRole('combobox', { name: '设置分类' }).selectOption({ label: section });
    assert.equal(await page.locator('.settings-body').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true, '窄屏 ' + section);
    assert.equal(await page.locator('.modal-box').evaluate(el => el.getBoundingClientRect().right <= innerWidth), true);
  }
  if (name === 'chromium') await page.screenshot({ path: join(output, `${name}-settings-mobile.png`), animations: 'disabled' });
  await page.setViewportSize({ width: 900, height: 420 });
  assert.equal(await page.locator('.modal-box').evaluate(el => el.getBoundingClientRect().bottom <= innerHeight), true, '短窗口仍能容纳完整弹窗');
  assert.equal(await page.locator('.settings-body').evaluate(el => el.clientHeight > 100 && el.getBoundingClientRect().bottom <= innerHeight), true);
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => document.activeElement.id === 'sidebar-toggle');
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.getByRole('tab', { name: '预览', exact: true }).press('Home');
  const firstTab = page.getByRole('tab', { name: '变更', exact: true });
  assert.equal(await firstTab.evaluate(tab => document.activeElement === tab), true);
  await firstTab.press('Enter');
  assert.equal(await firstTab.getAttribute('aria-selected'), 'true');
  await firstTab.press('End');
  assert.equal(await page.getByRole('tab', { name: '侧聊', exact: true }).evaluate(tab => document.activeElement === tab), true);
  await page.getByRole('button', { name: '更多工具', exact: true }).click();
  await page.getByRole('menuitem', { name: '活动', exact: true }).click();
  assert.equal(await page.evaluate(() => store.getState().ui.inspectorTab), 'activity');
  await page.getByLabel('关闭工作区面板', { exact: true }).click();
  console.log(`${name}: PASS file tree, picker contract/errors, modal focus, settings/theme and panel keyboard navigation`);
}
