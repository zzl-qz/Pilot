import test from 'node:test';
import assert from 'node:assert/strict';

test('安全存储在访问器抛 SecurityError 和方法抛 QuotaExceededError 时降级', async () => {
  const originalLocal = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  const originalSession = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');
  try {
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, get() { throw new DOMException('blocked', 'SecurityError'); } });
    Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, get() { return {
      get length() { throw new DOMException('blocked', 'SecurityError'); },
      getItem() { throw new DOMException('blocked', 'SecurityError'); },
      setItem() { throw new DOMException('full', 'QuotaExceededError'); },
      removeItem() { throw new DOMException('blocked', 'SecurityError'); }, key() { return null; }
    }; } });
    const { safeLocalStorage, safeSessionStorage, safeSetItem } = await import('../src/lib/safe-storage.js');
    const { saveWorkbenchView } = await import('../src/lib/workbench-sync.js');
    const { DRAFT_STORAGE, writeDraftSnapshot } = await import('../src/lib/drafts.js');
    const { writeAlertPreferences, DEFAULT_ALERT_PREFS } = await import('../src/lib/sound.js');
    const local = safeLocalStorage(); const session = safeSessionStorage();
    assert.equal(local.available(), false);
    assert.equal(local.getItem('x'), null);
    assert.doesNotThrow(() => local.setItem('x', 'y'));
    assert.equal(safeSetItem(local, 'x', 'y'), false);
    assert.equal(writeAlertPreferences(DEFAULT_ALERT_PREFS, local), false);
    assert.equal(writeDraftSnapshot(DRAFT_STORAGE, 's1', {
      text: 'hi', files: [{ id: '1', name: 'a.txt', mimeType: 'text/plain', dataBase64: 'YWJj', size: 3 }], imagePositions: []
    }).ok, false);
    assert.equal(session.length, 0);
    assert.equal(session.getItem('x'), null);
    assert.doesNotThrow(() => session.setItem('x', 'y'));
    assert.equal(safeSetItem(session, 'x', 'y'), false);
    assert.equal(saveWorkbenchView(session, { sessionId: 's1' }), false);
    assert.doesNotThrow(() => session.removeItem('x'));
  } finally {
    if (originalLocal) Object.defineProperty(globalThis, 'localStorage', originalLocal); else delete globalThis.localStorage;
    if (originalSession) Object.defineProperty(globalThis, 'sessionStorage', originalSession); else delete globalThis.sessionStorage;
  }
});
