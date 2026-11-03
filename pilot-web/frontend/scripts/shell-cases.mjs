import assert from 'node:assert/strict';
import { join } from 'node:path';

// 固定网络响应，只操作独立 fixture，不读取或修改用户的项目。
export async function checkShell(page, name, output) {
  const capture = async file => {
    await page.mouse.move(page.viewportSize().width - 4, 10);
    await page.evaluate(async () => {
      await Promise.all(document.getAnimations().filter(a => a.effect?.getTiming().iterations !== Infinity).map(a => a.finished.catch(() => {})));
      await new Promise(requestAnimationFrame);
    });
    await page.screenshot({ path: join(output, file) });
  };
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.evaluate(() => {
    store.getState().setTheme('dark');
    const workspace = '/workspace/pilot';
    window.api.files = async () => ({ entries: [{ name: 'src', path: 'src', directory: true }, { name: 'README.md', path: 'README.md', directory: false }] });
    window.api.results = async () => ({ items: [] });
    window.api.settings = async () => ({});
    window.api.templates = async () => [];
    store.setState({ sessionId: 'shell-current', sessionTitle: '优化工作区布局与导航', workspace,
      projects: [{ id: 'p', name: 'Pilot', folders: [workspace] }, { id: 'web', name: '客户工作台', folders: ['/workspace/web'] }],
      sessions: [
        { id: 'shell-current', title: '优化工作区布局与导航', projectId: 'p', workspace, status: 'IDLE' },
        { id: 'history', title: '检查附件上传与跨平台输入', projectId: 'p', workspace, status: 'COMPLETED' },
        { id: 'archived', title: '旧版导航方案', projectId: 'p', workspace, archived: true },
        { id: 'client', title: '完善客户资料页', projectId: 'web', workspace: '/workspace/web', status: 'RUNNING' }
      ],
      thread: { ...store.getState().thread, rows: [
        { type: 'user', key: 'shell-user', sequence: 1, text: '参考 Codex 的布局，优化左侧功能栏和顶部侧栏开关，其余细节也一起梳理。' },
        { type: 'turn', key: 'shell-turn', runIds: ['shell-run'], tools: [], reply: { text: '导航分成三层：全局功能、项目与对话、当前工作区。\n\n搜索、任务收件箱和定时任务放在最左侧，收起项目列表后也能直接访问。顶部保留当前工作区与任务标题，设置和主题切换移到功能栏底部。\n\n项目列表与文件树各自滚动。窄屏使用导航抽屉，关闭后回到完整的对话区域。', live: false }, stats: {} }
      ], history: { ...store.getState().thread.history, hasMore: false } }
    });
    store.getState().setUi({ sidebarOpen: true, inspectorOpen: false, resultUnread: 3 });
  });
  await page.locator('[aria-current="page"].session-item').waitFor();
  const rail = page.getByRole('navigation', { name: '全局功能' });
  assert.equal(await rail.getByLabel('3 条未读', { exact: true }).count(), 1);
  const top = await page.locator('.topbar').boundingBox(), side = await page.locator('#sidebar').boundingBox();
  assert.equal(top.y, 0); assert.equal(side.y, top.height);
  await page.getByRole('button', { name: '收起「Pilot」', exact: true }).click();
  assert.equal(await page.locator('.project-archived').isVisible(), false, '归档也跟随项目折叠');
  await page.getByRole('button', { name: '展开「Pilot」', exact: true }).click();
  await page.locator('#session-search').fill('不存在的对话');
  await page.getByText('没有标题或路径包含「不存在的对话」的对话。', { exact: true }).waitFor();
  await page.locator('#session-search').fill('');
  await page.locator('#sidebar-toggle').click();
  assert.equal(await page.locator('#sidebar-toggle').getAttribute('aria-expanded'), 'false');
  assert.equal(await page.locator('#sidebar').isVisible(), false);
  assert.equal(await rail.isVisible(), true, '收起项目侧栏后保留全局入口');
  await rail.getByRole('button', { name: '任务收件箱', exact: true }).click();
  await page.locator('dialog[open]').waitFor(); await page.keyboard.press('Escape');
  await rail.getByRole('button', { name: '搜索消息与书签', exact: true }).click();
  await page.locator('dialog[open]').waitFor(); await page.keyboard.press('Escape');
  await page.evaluate(() => { api.schedules = async () => { throw Error('离线测试'); }; });
  await rail.getByRole('button', { name: '定时任务', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: '读取任务失败：离线测试' }).waitFor();
  await page.keyboard.press('Escape');
  await page.locator('#settings-open').click(); await page.locator('#settings').waitFor();
  await page.keyboard.press('Escape'); assert.equal(await page.evaluate(() => document.activeElement.id), 'settings-open');
  await rail.getByRole('button', { name: '命令面板', exact: true }).click();
  await page.locator('.modal').waitFor(); await page.keyboard.press('Escape');
  // Windows/Linux Ctrl 和 macOS Meta 共用行为；输入法组合键不能触发。
  for (const modifier of ['Control', 'Meta']) {
    await page.keyboard.press(`${modifier}+b`);
    assert.equal(await page.locator('#sidebar-toggle').getAttribute('aria-expanded'), 'true');
    await page.keyboard.press(`${modifier}+b`);
    assert.equal(await page.locator('#sidebar-toggle').getAttribute('aria-expanded'), 'false');
  }
  await page.evaluate(() => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'b', ctrlKey: true, isComposing: true, bubbles: true })));
  assert.equal(await page.locator('#sidebar-toggle').getAttribute('aria-expanded'), 'false');
  await page.locator('#sidebar-toggle').click();
  // 分隔条在新的三列布局中仍然跟随侧栏，宽度能够保存。
  const gutter = await page.locator('#gutter-left').boundingBox();
  await page.mouse.move(gutter.x + 5, 240); await page.mouse.down(); await page.mouse.move(gutter.x + 65, 240); await page.mouse.up();
  assert.ok(await page.locator('#sidebar').evaluate(el => el.getBoundingClientRect().width) > side.width);
  assert.ok(await page.evaluate(() => JSON.parse(localStorage.getItem('pilot.layout')).sidebarW) > side.width);
  await page.mouse.move(gutter.x + 65, 240); await page.mouse.down(); await page.mouse.move(gutter.x + 5, 240); await page.mouse.up();
  await capture(`${name}-shell-desktop.png`);
  await page.locator('#theme-toggle').click(); assert.equal(await page.locator('html').getAttribute('data-theme'), 'light');
  await capture(`${name}-shell-light.png`);
  await page.locator('#theme-toggle').click();
  for (const width of [961, 768, 390]) {
    await page.setViewportSize({ width, height: 820 });
    await page.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth);
    await capture(`${name}-shell-${width}.png`);
    if (width > 960) continue;
    assert.equal(await rail.isVisible(), false);
    await page.locator('#sidebar-toggle').click();
    await page.waitForFunction(() => document.activeElement.id === 'new-session');
    await page.waitForFunction(() => Math.abs(document.querySelector('#sidebar').getBoundingClientRect().x - 52) < 1);
    const drawer = await page.locator('#sidebar').boundingBox();
    assert.ok(drawer.width >= 240 && drawer.x + drawer.width <= width, '抽屉内容完整显示，给右侧遮罩留出关闭区域');
    assert.equal(await page.locator('.main').getAttribute('inert'), '');
    await capture(`${name}-shell-drawer-${width}.png`);
    for (let i = 0; i < 40; i++) await page.keyboard.press('Tab');
    assert.ok(await page.evaluate(() => !!document.activeElement.closest('#sidebar, .navigation-rail, #sidebar-toggle')));
    await page.evaluate(() => store.setState({ running: true }));
    const cancelled = await page.evaluate(() => window.cancelled);
    await page.keyboard.press('Escape');
    assert.equal(await page.evaluate(() => window.cancelled), cancelled, '关抽屉不取消任务');
    assert.equal(await page.evaluate(() => document.activeElement.id), 'sidebar-toggle');
    assert.equal(await page.locator('.main').getAttribute('inert'), null);
    await page.evaluate(() => store.setState({ running: false }));
    await page.locator('#sidebar-toggle').click();
    await page.setViewportSize({ width: 1440, height: 820 });
    await page.waitForFunction(() => !document.querySelector('.main').hasAttribute('inert'));
    await page.locator('#input-flow').click();
    assert.equal(await page.evaluate(() => document.activeElement.id), 'input-flow', '从抽屉切回桌面后主区仍可交互');
    await page.setViewportSize({ width, height: 820 });
  }
  // 内嵌侧边对话不能被全局栏或多余网格列挤压。
  await page.evaluate(() => store.setState({ embeddedSessionId: 'embedded' }));
  await page.waitForFunction(() => document.querySelector('.main').getBoundingClientRect().width >= innerWidth - 1);
  assert.equal(await rail.count(), 0);
  assert.equal(await page.locator('#sidebar-toggle').count(), 0);
  console.log(`${name}: PASS shell navigation, dialogs/errors, project collapse/search, shortcuts/IME, resize, themes, drawer/focus and embedded view`);
}
