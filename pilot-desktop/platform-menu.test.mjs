import test from 'node:test';
import assert from 'node:assert/strict';
import { windowSizeMenuItem, workbenchMenuItems, workbenchShortcut } from './platform-menu.mjs';

test('macOS 使用系统窗口 Zoom 动作', () => {
  assert.equal(windowSizeMenuItem('darwin').role, 'zoom');
});

for (const platform of ['win32', 'linux']) {
  test(`${platform} 最大化/还原及无窗口边界`, () => {
    const item = windowSizeMenuItem(platform);
    let maximized = false;
    const window = { isDestroyed: () => false, isMaximized: () => maximized,
      maximize: () => { maximized = true; }, unmaximize: () => { maximized = false; } };
    item.click(null, window); assert.equal(maximized, true);
    item.click(null, window); assert.equal(maximized, false);
    item.click(null, undefined);
    item.click(null, { isDestroyed: () => true });
  });
}

for (const platform of ['darwin', 'win32', 'linux']) {
  test(`${platform} 工作台刷新与重新载入快捷键`, () => {
    const input = { type: 'keyDown', key: 'F5' };
    assert.equal(workbenchShortcut(input, platform), 'refresh');
    assert.equal(workbenchShortcut({ ...input, shift: true }, platform), 'reload');
    const mod = platform === 'darwin' ? { meta: true } : { control: true };
    assert.equal(workbenchShortcut({ type: 'keyDown', key: 'R', ...mod }, platform), 'reload');
    for (const extra of [{ alt: true }, { isComposing: true }, { isAutoRepeat: true }, { type: 'keyUp' }]) {
      assert.equal(workbenchShortcut({ ...input, ...extra }, platform), null);
    }
    assert.equal(workbenchShortcut({ type: 'keyDown', key: 'r', control: true, alt: true }, platform), null);
    assert.equal(workbenchShortcut(undefined, platform), null);
  });
}

test('原生菜单使用显式工作台回调，不依赖当前获得焦点的网页', () => {
  const calls = [], items = workbenchMenuItems(value => calls.push(value));
  assert.equal(items[0].accelerator, 'F5'); assert.equal(items[1].accelerator, 'CommandOrControl+R');
  for (const item of items) { assert.equal(item.role, undefined); item.click(); }
  assert.deepEqual(calls, ['refresh', 'reload']);
});
