import test from 'node:test';
import assert from 'node:assert/strict';

import { PERMISSION_MODES, normalizePermission, permissionLabel, permissionHint } from '../../../frontend/src/lib/permission.js';

test('accepts every backend permission mode, case-insensitively', () => {
  for (const item of PERMISSION_MODES) {
    assert.equal(normalizePermission(item.mode), item.mode);
    assert.equal(normalizePermission(item.mode.toLowerCase()), item.mode);
  }
});

test('unknown or empty values fall back to the default mode', () => {
  // 旧界面用的 default / auto-edit 不是后端枚举值，不能再让它们传下去
  assert.equal(normalizePermission('default'), 'AUTO');
  assert.equal(normalizePermission('auto-edit'), 'AUTO');
  assert.equal(normalizePermission(''), 'AUTO');
  assert.equal(normalizePermission(null), 'AUTO');
});

test('labels are Chinese and hints are non-empty', () => {
  assert.equal(permissionLabel('READ_ONLY'), '只读');
  assert.equal(permissionLabel('auto_edit'), '自动改文件');
  for (const item of PERMISSION_MODES) {
    assert.ok(permissionLabel(item.mode).length > 0);
    assert.ok(permissionHint(item.mode).length > 10);
  }
});
