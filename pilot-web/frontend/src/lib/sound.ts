import type { AlertPreferences, ComposerSendKey, SoundVolumeLevel } from '../types/store.js';
export type AlertSoundKind = 'done' | 'approval' | 'question' | 'error';

import { safeLocalStorage, safeSetItem } from './safe-storage.js';


export const ALERT_PREFS_KEY = 'pilot.alerts.v1';

export const DEFAULT_ALERT_PREFS: AlertPreferences = {
  soundEnabled: true,
  soundVolume: 'standard',
  soundBackgroundOnly: false,
  soundOnDone: true,
  soundOnApproval: true,
  soundOnQuestion: true,
  soundOnError: true,
  floatingMode: 'background',
  taskbarAttention: true,
  desktopNotifyOnBackground: true,
  dndUntil: null,
  keepAwake: false,
  minimizeToTray: false,
  globalShortcut: '',
  sendKey: 'enter'
};

const VOLUME_GAINS: Record<SoundVolumeLevel, number> = {
  gentle: 0.055,
  standard: 0.11,
  crisp: 0.18
};

const COOLDOWN_MS = 1200;
const lastPlayedAt: Partial<Record<AlertSoundKind, number>> = {};
let sharedContext: AudioContext | null = null;
let unlocked = false;

export function normalizeAlertPreferences(raw: unknown): AlertPreferences {
  if (!raw || typeof raw !== 'object') return { ...DEFAULT_ALERT_PREFS };
  const value = raw as Partial<AlertPreferences>;
  const soundVolume: SoundVolumeLevel = value.soundVolume === 'gentle' || value.soundVolume === 'crisp'
    ? value.soundVolume : 'standard';
  const floatingMode = value.floatingMode === 'always' || value.floatingMode === 'off'
    ? value.floatingMode : 'background';
  const sendKey: ComposerSendKey = value.sendKey === 'mod-enter' ? 'mod-enter' : 'enter';
  const dndUntil = typeof value.dndUntil === 'number' && Number.isFinite(value.dndUntil)
    ? value.dndUntil : null;
  const bool = (candidate: unknown, fallback: boolean) => (typeof candidate === 'boolean' ? candidate : fallback);
  return {
    soundEnabled: bool(value.soundEnabled, DEFAULT_ALERT_PREFS.soundEnabled),
    soundVolume,
    soundBackgroundOnly: bool(value.soundBackgroundOnly, DEFAULT_ALERT_PREFS.soundBackgroundOnly),
    soundOnDone: bool(value.soundOnDone, DEFAULT_ALERT_PREFS.soundOnDone),
    soundOnApproval: bool(value.soundOnApproval, DEFAULT_ALERT_PREFS.soundOnApproval),
    soundOnQuestion: bool(value.soundOnQuestion, DEFAULT_ALERT_PREFS.soundOnQuestion),
    soundOnError: bool(value.soundOnError, DEFAULT_ALERT_PREFS.soundOnError),
    floatingMode,
    taskbarAttention: bool(value.taskbarAttention, DEFAULT_ALERT_PREFS.taskbarAttention),
    desktopNotifyOnBackground: bool(value.desktopNotifyOnBackground, DEFAULT_ALERT_PREFS.desktopNotifyOnBackground),
    dndUntil,
    keepAwake: bool(value.keepAwake, DEFAULT_ALERT_PREFS.keepAwake),
    minimizeToTray: bool(value.minimizeToTray, DEFAULT_ALERT_PREFS.minimizeToTray),
    globalShortcut: typeof value.globalShortcut === 'string' ? value.globalShortcut.trim().slice(0, 64) : '',
    sendKey
  };
}

export function isDndActive(prefs: Pick<AlertPreferences, 'dndUntil'> | null | undefined, now = Date.now()): boolean {
  const until = prefs?.dndUntil;
  return until === -1 || (typeof until === 'number' && until > now);
}

export function readAlertPreferences(storage: Pick<Storage, 'getItem'> = safeLocalStorage()): AlertPreferences {
  try {
    const raw = storage.getItem(ALERT_PREFS_KEY);
    return raw ? normalizeAlertPreferences(JSON.parse(raw)) : { ...DEFAULT_ALERT_PREFS };
  } catch (error) {
    console.warn('读取提醒偏好失败，使用默认值', error);
    return { ...DEFAULT_ALERT_PREFS };
  }
}

export function writeAlertPreferences(
  prefs: AlertPreferences,
  storage: Pick<Storage, 'setItem'> = safeLocalStorage()
): boolean {
  if (!storage) return false;
  return safeSetItem(storage, ALERT_PREFS_KEY, JSON.stringify(normalizeAlertPreferences(prefs)));
}

export function shouldPlaySound(kind: AlertSoundKind, prefs: AlertPreferences, backgrounded: boolean, now = Date.now()): boolean {
  if (!prefs.soundEnabled || isDndActive(prefs, now)) return false;
  if (prefs.soundBackgroundOnly && !backgrounded) return false;
  switch (kind) {
    case 'done': return prefs.soundOnDone;
    case 'approval': return prefs.soundOnApproval;
    case 'question': return prefs.soundOnQuestion;
    case 'error': return prefs.soundOnError;
    default: return false;
  }
}

interface ToneStep {
  freq: number;
  offset: number;
  duration: number;
  type: OscillatorType;
  weight?: number;
}

const SOUND_PATTERNS: Record<AlertSoundKind, ToneStep[]> = {
  // Codex 风格完成音：温润的 C5 -> G5 双音谐波上升提示
  done: [
    { freq: 523.25, offset: 0, duration: 0.14, type: 'sine', weight: 0.9 },
    { freq: 783.99, offset: 0.10, duration: 0.24, type: 'sine', weight: 1.0 },
    { freq: 1567.98, offset: 0.10, duration: 0.16, type: 'sine', weight: 0.2 }
  ],
  // 授权等待：清晰双音铃声
  approval: [
    { freq: 880, offset: 0, duration: 0.11, type: 'triangle', weight: 0.85 },
    { freq: 659.25, offset: 0.11, duration: 0.12, type: 'sine', weight: 0.75 },
    { freq: 880, offset: 0.22, duration: 0.19, type: 'sine', weight: 0.95 }
  ],
  // 选择提问：轻柔问询上升音
  question: [
    { freq: 659.25, offset: 0, duration: 0.10, type: 'sine', weight: 0.85 },
    { freq: 987.77, offset: 0.09, duration: 0.17, type: 'sine', weight: 0.9 }
  ],
  // 异常或阻塞：平稳低沉双音
  error: [
    { freq: 293.66, offset: 0, duration: 0.13, type: 'triangle', weight: 0.85 },
    { freq: 220, offset: 0.13, duration: 0.22, type: 'sine', weight: 0.95 }
  ]
};

function resolveAudioContextConstructor(custom?: typeof AudioContext): typeof AudioContext | null {
  if (custom) return custom;
  const g = globalThis as { AudioContext?: typeof AudioContext; webkitAudioContext?: typeof AudioContext };
  return g.AudioContext || g.webkitAudioContext || null;
}

function getAudioContext(customCtor?: typeof AudioContext): AudioContext | null {
  if (sharedContext && sharedContext.state !== 'closed' && !customCtor) {
    return sharedContext;
  }
  const Ctor = resolveAudioContextConstructor(customCtor);
  if (!Ctor) return null;
  try {
    const ctx = new Ctor();
    if (!customCtor) sharedContext = ctx;
    return ctx;
  } catch {
    return null;
  }
}

export function resetSoundCooldown() {
  for (const key of Object.keys(lastPlayedAt) as AlertSoundKind[]) {
    delete lastPlayedAt[key];
  }
  sharedContext = null;
  unlocked = false;
}

export async function playAlertSound(
  kind: AlertSoundKind,
  options: {
    prefs?: AlertPreferences;
    backgrounded?: boolean;
    force?: boolean;
    now?: number;
    cooldownMs?: number;
    AudioContextCtor?: typeof AudioContext;
  } = {}
): Promise<boolean> {
  const prefs = options.prefs || DEFAULT_ALERT_PREFS;
  const force = Boolean(options.force);
  if (!force && !shouldPlaySound(kind, prefs, Boolean(options.backgrounded), options.now)) {
    return false;
  }
  const nowMs = options.now ?? Date.now();
  const cooldown = options.cooldownMs ?? COOLDOWN_MS;
  const prev = lastPlayedAt[kind] ?? 0;
  if (!force && nowMs - prev < cooldown) {
    return false;
  }
  const ctx = getAudioContext(options.AudioContextCtor);
  if (!ctx) return false;
  try {
    if (ctx.state === 'suspended' && typeof ctx.resume === 'function') {
      await ctx.resume();
    }
    const baseGain = VOLUME_GAINS[prefs.soundVolume] ?? VOLUME_GAINS.standard;
    const baseTime = (ctx.currentTime ?? 0) + 0.01;
    const steps = SOUND_PATTERNS[kind] || SOUND_PATTERNS.done;
    for (const step of steps) {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      const start = baseTime + step.offset;
      const peak = baseGain * (step.weight ?? 1);
      osc.type = step.type;
      osc.frequency.setValueAtTime(step.freq, start);
      gain.gain.setValueAtTime(0.0001, start);
      gain.gain.exponentialRampToValueAtTime(Math.max(0.001, peak), start + 0.015);
      gain.gain.exponentialRampToValueAtTime(0.0001, start + step.duration);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(start);
      osc.stop(start + step.duration + 0.02);
    }
    lastPlayedAt[kind] = nowMs;
    return true;
  } catch {
    return false;
  }
}

/** 浏览器需要一次用户交互后才能播放音频；挂载后在首次点击或按键时静默解锁。 */
export function installSoundUnlock(target: Pick<Window, 'addEventListener' | 'removeEventListener'> | undefined = globalThis.window): () => void {
  if (!target || typeof target.addEventListener !== 'function' || unlocked) {
    return () => {};
  }
  const unlock = () => {
    const ctx = getAudioContext();
    if (!ctx) {
      cleanup();
      return;
    }
    if (ctx.state === 'suspended' && typeof ctx.resume === 'function') {
      void ctx.resume().then(() => {
        unlocked = true;
        cleanup();
      }).catch(() => {});
    } else {
      unlocked = true;
      cleanup();
    }
  };
  const cleanup = () => {
    target.removeEventListener('pointerdown', unlock, true);
    target.removeEventListener('keydown', unlock, true);
  };
  target.addEventListener('pointerdown', unlock, true);
  target.addEventListener('keydown', unlock, true);
  return cleanup;
}
