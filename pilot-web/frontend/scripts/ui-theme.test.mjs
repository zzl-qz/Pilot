import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveTheme, systemPrefersDark, subscribeSystemTheme, supportsAnchoredLayers } from '../src/lib/theme.js';

test('显式主题优先于系统设置，无媒体查询能力时跟随系统回退浅色', () => {
  assert.equal(resolveTheme('dark', false), 'dark');
  assert.equal(resolveTheme('light', true), 'light');
  assert.equal(resolveTheme('system', true), 'dark');
  assert.equal(resolveTheme('system', false), 'light');
  assert.equal(resolveTheme('invalid', true), 'light');
  assert.equal(systemPrefersDark(), false);
});

test('系统主题订阅在卸载时移除同一个监听器', t => {
  const events = [];
  const listener = () => {};
  const previous = globalThis.window;
  t.after(() => { if (previous === undefined) delete globalThis.window; else globalThis.window = previous; });
  globalThis.window = { matchMedia: query => {
    assert.equal(query, '(prefers-color-scheme: dark)');
    return { addEventListener: (...args) => events.push(['add', ...args]), removeEventListener: (...args) => events.push(['remove', ...args]) };
  } };
  subscribeSystemTheme(listener)();
  assert.deepEqual(events, [['add', 'change', listener], ['remove', 'change', listener]]);
  globalThis.window = {};
  assert.doesNotThrow(() => subscribeSystemTheme(listener)());
});

test('缺少 CSS 锚点或 Popover 时选择直接操作入口，避免按操作系统猜测能力', t => {
  for (const key of ['CSS', 'HTMLElement']) { const saved = globalThis[key]; t.after(() => { if (saved === undefined) delete globalThis[key]; else globalThis[key] = saved; }); }
  assert.equal(supportsAnchoredLayers(), false);
  globalThis.HTMLElement = class {};
  globalThis.CSS = { supports: () => true };
  assert.equal(supportsAnchoredLayers(), false);
  globalThis.HTMLElement.prototype.showPopover = () => {};
  assert.equal(supportsAnchoredLayers(), true);
  globalThis.CSS.supports = () => false;
  assert.equal(supportsAnchoredLayers(), false);
});
