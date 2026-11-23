import test from 'node:test';
import assert from 'node:assert/strict';

const {
  ALERT_PREFS_KEY,
  DEFAULT_ALERT_PREFS,
  isDndActive,
  normalizeAlertPreferences,
  playAlertSound,
  readAlertPreferences,
  resetSoundCooldown,
  shouldPlaySound,
  writeAlertPreferences
} = await import('../src/lib/sound.ts');

function createMemoryStorage(initial = {}) {
  const map = new Map(Object.entries(initial));
  return {
    getItem(key) { return map.has(key) ? map.get(key) : null; },
    setItem(key, value) { map.set(key, String(value)); }
  };
}

function createFakeAudioContext(state = 'running') {
  const calls = { resumed: false, oscillators: [], gains: [] };
  class FakeAudioContext {
    state = state;
    currentTime = 1.0;
    destination = { kind: 'dest' };
    async resume() { calls.resumed = true; this.state = 'running'; }
    createOscillator() {
      const osc = {
        type: 'sine',
        freqValues: [],
        frequency: { setValueAtTime: (val, at) => osc.freqValues.push([val, at]) },
        connect() {},
        start: (t) => { osc.startedAt = t; },
        stop: (t) => { osc.stoppedAt = t; }
      };
      calls.oscillators.push(osc);
      return osc;
    }
    createGain() {
      const g = {
        ramps: [],
        gain: {
          setValueAtTime: (v, t) => g.ramps.push(['set', v, t]),
          exponentialRampToValueAtTime: (v, t) => g.ramps.push(['ramp', v, t])
        },
        connect() {}
      };
      calls.gains.push(g);
      return g;
    }
  }
  return { AudioContextCtor: FakeAudioContext, calls };
}

test('提醒偏好持久化与默认值规范化', () => {
  const storage = createMemoryStorage();
  assert.deepEqual(readAlertPreferences(storage), DEFAULT_ALERT_PREFS);
  const custom = {
    ...DEFAULT_ALERT_PREFS,
    soundVolume: 'crisp',
    floatingMode: 'always',
    soundOnDone: false
  };
  assert.equal(writeAlertPreferences(custom, storage), true);
  assert.deepEqual(readAlertPreferences(storage), custom);

  const malformed = createMemoryStorage({ [ALERT_PREFS_KEY]: '{broken-json' });
  assert.deepEqual(readAlertPreferences(malformed), DEFAULT_ALERT_PREFS);
  assert.equal(normalizeAlertPreferences({ soundVolume: 'loud', floatingMode: 'bad' }).soundVolume, 'standard');
  assert.equal(normalizeAlertPreferences({ floatingMode: 'bad' }).floatingMode, 'background');
});

test('场景与前后台开关正确控制是否发声', () => {
  assert.equal(shouldPlaySound('done', DEFAULT_ALERT_PREFS, false), true);
  assert.equal(shouldPlaySound('done', { ...DEFAULT_ALERT_PREFS, soundBackgroundOnly: true }, false), false);
  assert.equal(shouldPlaySound('done', { ...DEFAULT_ALERT_PREFS, soundBackgroundOnly: true }, true), true);
  assert.equal(shouldPlaySound('approval', { ...DEFAULT_ALERT_PREFS, soundOnApproval: false }, true), false);
  assert.equal(shouldPlaySound('question', { ...DEFAULT_ALERT_PREFS, soundEnabled: false }, true), false);
});

test('Web Audio 提示音按场景合成并遵守 1200ms 冷却防抖，試听 force 无视冷却', async () => {
  resetSoundCooldown();
  const fake = createFakeAudioContext('suspended');
  const first = await playAlertSound('done', {
    prefs: DEFAULT_ALERT_PREFS,
    backgrounded: true,
    now: 5000,
    AudioContextCtor: fake.AudioContextCtor
  });
  assert.equal(first, true);
  assert.equal(fake.calls.resumed, true);
  assert.equal(fake.calls.oscillators.length, 3);

  const debounced = await playAlertSound('done', {
    prefs: DEFAULT_ALERT_PREFS,
    backgrounded: true,
    now: 5500,
    AudioContextCtor: fake.AudioContextCtor
  });
  assert.equal(debounced, false);

  const forced = await playAlertSound('approval', {
    prefs: { ...DEFAULT_ALERT_PREFS, soundEnabled: false },
    backgrounded: false,
    force: true,
    now: 5600,
    AudioContextCtor: fake.AudioContextCtor
  });
  assert.equal(forced, true);
  assert.equal(fake.calls.oscillators.length, 6);
});

test('勿扰模式、发送快捷键与桌面常驻字段规范化与拦截逻辑正确', () => {
  const normalized = normalizeAlertPreferences({
    dndUntil: -1,
    keepAwake: true,
    minimizeToTray: true,
    globalShortcut: '  CommandOrControl+Shift+P  ',
    sendKey: 'mod-enter'
  });
  assert.equal(normalized.sendKey, 'mod-enter');
  assert.equal(normalized.globalShortcut, 'CommandOrControl+Shift+P');
  assert.equal(isDndActive(normalized, 10_000), true);
  assert.equal(shouldPlaySound('done', normalized, true, 10_000), false);

  const timedDnd = { ...DEFAULT_ALERT_PREFS, dndUntil: 5000 };
  assert.equal(isDndActive(timedDnd, 4999), true);
  assert.equal(isDndActive(timedDnd, 5001), false);
  assert.equal(shouldPlaySound('done', timedDnd, true, 5001), true);
});
