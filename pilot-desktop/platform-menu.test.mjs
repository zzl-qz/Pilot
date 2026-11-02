import test from 'node:test';
import assert from 'node:assert/strict';
import { windowSizeMenuItem } from './platform-menu.mjs';

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
