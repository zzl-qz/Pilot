import test from 'node:test';
import assert from 'node:assert/strict';
import { faviconHref, shouldNotify, tabTitle } from '../../../frontend/src/lib/attention.js';

test('tab title carries the run state', () => {
  assert.equal(tabTitle('修复登录', 'idle'), '修复登录');
  assert.equal(tabTitle('修复登录', 'running'), '● 修复登录');
  assert.equal(tabTitle('修复登录', 'attention'), '! 修复登录');
  assert.equal(tabTitle('', 'done'), '✓ Pilot');
});

test('favicon gets a status badge only when there is a state', () => {
  assert.ok(!faviconHref('idle').includes('circle cx=\'25\''));
  assert.ok(faviconHref('running').includes('circle cx=\'25\''));
  assert.notEqual(faviconHref('done'), faviconHref('error'));
});

test('notifications need a hidden page, opt-in, permission and a meaningful event', () => {
  const base = { hidden: true, enabled: true, permission: 'granted' };
  assert.equal(shouldNotify({ ...base, kind: 'done' }), true);
  assert.equal(shouldNotify({ ...base, kind: 'attention' }), true);
  assert.equal(shouldNotify({ ...base, kind: 'running' }), false);
  assert.equal(shouldNotify({ ...base, kind: 'done', hidden: false }), false);
  assert.equal(shouldNotify({ ...base, kind: 'done', enabled: false }), false);
  assert.equal(shouldNotify({ ...base, kind: 'done', permission: 'denied' }), false);
});
