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
    store.setState({ workspace: '/workspace/demo', sessionId: 'ui-audit', running: false });
    store.getState().setUi({ sidebarOpen: true, inspectorOpen: false, reviewExpanded: false });
  });
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
  await page.locator('#settings-open').click();
  for (const section of ['外观', '模型', '权限', '技能', '用量', '诊断', '关于']) {
    await page.locator('.settings-nav').getByRole('button', { name: section, exact: true }).click();
    assert.equal(await page.locator('.settings-body').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true, section + ' 无横向溢出');
  }
  for (let i = 0; i < 14; i++) await page.keyboard.press('Tab');
  assert.equal(await page.evaluate(() => !!document.activeElement.closest('#settings')), true);
  for (let i = 0; i < 14; i++) await page.keyboard.press('Shift+Tab');
  assert.equal(await page.evaluate(() => !!document.activeElement.closest('#settings')), true);
  await page.locator('.settings-nav').getByRole('button', { name: '外观', exact: true }).click();
  await page.getByRole('button', { name: '浅色', exact: false }).click();
  assert.equal(await page.locator('html').getAttribute('data-theme'), 'light');
  await page.screenshot({ path: join(output, `${name}-settings-light.png`) });
  await page.getByRole('button', { name: '深色', exact: false }).click();
  await page.setViewportSize({ width: 390, height: 700 });
  for (const section of ['模型', '诊断', '关于']) {
    await page.locator('.settings-nav').getByRole('button', { name: section, exact: true }).click();
    assert.equal(await page.locator('.settings-body').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true, '窄屏 ' + section);
    assert.equal(await page.locator('.modal-box').evaluate(el => el.getBoundingClientRect().right <= innerWidth), true);
  }
  await page.screenshot({ path: join(output, `${name}-settings-mobile.png`) });
  await page.setViewportSize({ width: 900, height: 420 });
  assert.equal(await page.locator('.modal-box').evaluate(el => el.getBoundingClientRect().bottom <= innerHeight), true, '短窗口仍能容纳完整弹窗');
  assert.equal(await page.locator('.settings-body').evaluate(el => el.clientHeight > 100 && el.getBoundingClientRect().bottom <= innerHeight), true);
  await page.keyboard.press('Escape');
  assert.equal(await page.evaluate(() => document.activeElement.id), 'settings-open');
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.getByRole('tab', { name: '预览', exact: true }).press('Home');
  assert.equal(await page.getByRole('tab', { name: '对话', exact: true }).getAttribute('aria-selected'), 'true');
  await page.getByRole('tab', { name: '对话', exact: true }).press('End');
  assert.equal(await page.getByRole('tab', { name: '活动', exact: true }).getAttribute('aria-selected'), 'true');
  await page.getByLabel('关闭工作区面板', { exact: true }).click();
  console.log(`${name}: PASS file tree, picker contract/errors, modal focus, settings/theme and panel keyboard navigation`);
}
