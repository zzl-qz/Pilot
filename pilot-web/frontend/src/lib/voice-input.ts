import type { SpeechRecognitionCtor, SpeechRecognitionLike, VoiceEnvironment, VoiceMode, VoiceOptions, VoicePhase, VoiceState } from '../types/input.js';
import type { VoiceLimitsBudget } from '../types/server.js';
import { formatBytes } from './attachments/attachments.js';
import { errorInfo } from './errors.js';
const FORMATS = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus'];
export function voiceFormat(Recorder: Pick<typeof MediaRecorder, 'isTypeSupported'> | null | undefined) {
  return FORMATS.find(type => Recorder?.isTypeSupported?.(type)) || '';
}
export function voiceFilename(type: string | null | undefined) {
  if (type?.includes('wav')) return 'recording.wav';
  return `recording.${type?.includes('mp4') ? 'mp4' : type?.includes('ogg') ? 'ogg' : 'webm'}`;
}

export function browserSpeechCtor(environment: VoiceEnvironment = globalThis as unknown as VoiceEnvironment): SpeechRecognitionCtor | null {
  const ctor = environment.SpeechRecognition || environment.webkitSpeechRecognition;
  return typeof ctor === 'function' ? ctor : null;
}

export function browserSpeechSupported(environment: VoiceEnvironment = globalThis as unknown as VoiceEnvironment): boolean {
  return browserSpeechCtor(environment) !== null;
}

/** 转写统一用 16k 单声道：多数服务只认 mp3/wav，webm/opus 会被判「不支持当前文件格式」。 */
export const VOICE_SAMPLE_RATE = 16000;
const DEFAULT_BROWSER_MAX_SECONDS = 120;

function wavBytes(samples: Float32Array, rate: number) {
  const bytes = new Uint8Array(44 + samples.length * 2);
  const view = new DataView(bytes.buffer);
  const ascii = (offset: number, text: string) => { for (let i = 0; i < text.length; i++) bytes[offset + i] = text.charCodeAt(i); };
  ascii(0, 'RIFF'); view.setUint32(4, 36 + samples.length * 2, true); ascii(8, 'WAVE');
  ascii(12, 'fmt '); view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
  view.setUint32(24, rate, true); view.setUint32(28, rate * 2, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true);
  ascii(36, 'data'); view.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) {
    const sample = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(44 + i * 2, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true);
  }
  return bytes;
}

/**
 * 把 MediaRecorder 的 webm/mp4 解成 16k 单声道 WAV。
 * 解码失败（老浏览器、罕见编码）时原样返回，让服务端给出明确报错，而不是静默丢录音。
 */
export async function toWav(blob: Blob, environment: VoiceEnvironment, maxBytes: number) {
  const Ctx = environment.AudioContext || environment.webkitAudioContext;
  const Offline = environment.OfflineAudioContext || environment.webkitOfflineAudioContext;
  if (!Ctx || !Offline || typeof blob.arrayBuffer !== 'function') return blob;
  const context = new Ctx();
  try {
    const decoded = await context.decodeAudioData(await blob.arrayBuffer());
    if (!decoded?.length) return blob;
    const frames = Math.max(1, Math.ceil(decoded.duration * VOICE_SAMPLE_RATE));
    if (44 + frames * 2 > maxBytes) return blob;
    const offline = new Offline(1, frames, VOICE_SAMPLE_RATE);
    const source = offline.createBufferSource();
    source.buffer = decoded;
    source.connect(offline.destination);
    source.start();
    const rendered = await offline.startRendering();
    return new Blob([wavBytes(rendered.getChannelData(0), VOICE_SAMPLE_RATE)], { type: 'audio/wav' });
  } finally {
    context.close?.();
  }
}
export function voiceError(value: unknown) {
  const error = errorInfo(value);
  return ({ NotAllowedError: '麦克风权限被拒绝。请在浏览器或系统设置中允许 Pilot 使用麦克风。',
    NotFoundError: '没有找到麦克风，请连接麦克风后重试。',
    NotReadableError: '麦克风无法使用，可能正被其他应用占用。',
    AbortError: '录音已中断，请重试。' } as Record<string, string>)[error.name ?? ''] || error?.message || '语音输入失败，请重试。';
}

function browserSpeechError(code: string | undefined): string {
  switch (code) {
    case 'not-allowed':
    case 'service-not-allowed':
      return '麦克风或浏览器语音识别权限被拒绝。请在浏览器设置中允许麦克风，或继续键盘输入。';
    case 'audio-capture':
      return '没有检测到可用麦克风，请连接麦克风后重试。';
    case 'no-speech':
      return '没有识别到语音，请靠近麦克风重试。';
    case 'network':
      return '浏览器语音识别服务连接失败。请检查网络，或在「设置 → 模型 → 语音输入」中配置本机 Whisper 端点（免密钥）。';
    case 'aborted':
      return '语音识别已中断，请重试。';
    default:
      return '浏览器语音识别失败，请重试或在设置中改用本机 Whisper 接口。';
  }
}

/** 录音与转写共用代次：取消、切换会话后迟到的权限/转写结果不会回填。 */
export function createVoiceInput({ environment = globalThis as unknown as VoiceEnvironment, loadLimits, loadMode, transcribe, onState, onText }: VoiceOptions) {
  let generation = 0, phase: VoicePhase = 'idle';
  let stream: MediaStream | null | undefined, recorder: MediaRecorder | null | undefined;
  let recognition: SpeechRecognitionLike | null = null;
  let timer: ReturnType<typeof setInterval> | undefined, permissionTimer: ReturnType<typeof setTimeout> | undefined;
  let controller: AbortController | null | undefined, recording: Blob | null | undefined;
  let limits: VoiceLimitsBudget;
  let chunks: Blob[] = [], bytes = 0, started = 0;
  let speechFinal = '', speechInterim = '', speechStoppedByUser = false;
  let activeMode: VoiceMode = 'auto';

  const emit = (next: VoicePhase, extra: Partial<VoiceState> = {}) => { phase = next; onState({ phase, ...extra }); };
  const releaseTimers = () => {
    if (timer !== undefined) clearInterval(timer);
    if (permissionTimer !== undefined) clearTimeout(permissionTimer);
    timer = permissionTimer = undefined;
  };
  const release = () => {
    releaseTimers();
    stream?.getTracks().forEach(track => { track.onended = null; track.stop(); }); stream = null;
  };
  const releaseRecognition = (abort: boolean) => {
    const active = recognition;
    recognition = null;
    if (!active) return;
    active.onstart = active.onresult = active.onerror = active.onend = null;
    try {
      if (abort) active.abort();
      else active.stop();
    } catch { /* 忽略已停止的识别实例 */ }
  };

  function cancel() {
    generation++; controller?.abort(); controller = null;
    if (recorder && recorder.state !== 'inactive') recorder.stop();
    recorder = null;
    releaseRecognition(true);
    release(); chunks = []; recording = null; speechFinal = ''; speechInterim = ''; speechStoppedByUser = false;
    emit('idle');
  }

  function fail(error: unknown, extra: Partial<VoiceState> = {}) {
    generation++;
    if (recorder && recorder.state !== 'inactive') recorder.stop();
    releaseRecognition(true);
    release(); chunks = [];
    emit('error', { error: voiceError(error), retry: Boolean(recording), ...extra });
  }

  async function upload(token: number) {
    controller = new AbortController(); emit('transcribing', { engine: 'server' });
    try {
      let audio = recording!;
      try { audio = await toWav(recording!, environment, limits.maxBytes); }
      catch { /* 解码不了就按原格式上传，服务端会给出明确原因 */ }
      if (token !== generation) return;
      const result = await transcribe(audio, controller.signal);
      if (token !== generation) return;
      const text = result?.text?.trim();
      if (!text) throw new Error('没有识别到文字，可以重试或重新录音。');
      recording = null; onText(text); emit('idle');
    } catch (error) {
      if (token === generation) {
        const canFallback = activeMode !== 'server' && browserSpeechSupported(environment);
        emit('error', { error: voiceError(error), retry: true, engine: 'server', browserFallback: canFallback });
      }
    }
  }

  function startBrowserSpeech(token: number, maxSeconds = DEFAULT_BROWSER_MAX_SECONDS) {
    if (environment.isSecureContext === false) {
      return fail(new Error('此页面无法访问麦克风。请使用 HTTPS、localhost 或桌面端，也可继续键盘输入。'));
    }
    const Ctor = browserSpeechCtor(environment);
    if (!Ctor) {
      return fail(new Error('当前浏览器或桌面端不支持原生 Web Speech 语音识别。请改用 Chrome / Edge，或在「设置 → 模型 → 语音输入」中配置免密钥的本机 Whisper 服务，也可继续键盘输入。'));
    }
    speechFinal = '';
    speechInterim = '';
    speechStoppedByUser = false;
    emit('requesting', { engine: 'browser' });
    const rec = new Ctor();
    recognition = rec;
    rec.continuous = true;
    rec.interimResults = true;
    rec.lang = environment.navigator?.language || 'zh-CN';
    let hadError = false;

    rec.onstart = () => {
      if (token !== generation) return;
      started = Date.now();
      emit('recording', { elapsed: 0, maxSeconds, engine: 'browser', interim: '' });
      timer = setInterval(() => {
        if (token !== generation) return;
        const elapsed = Date.now() - started;
        if (elapsed >= maxSeconds * 1000) stop();
        else emit('recording', { elapsed, maxSeconds, engine: 'browser', interim: (speechFinal + speechInterim).trim() });
      }, 1000);
    };
    rec.onresult = event => {
      if (token !== generation) return;
      let committed = '';
      let interim = '';
      for (let i = 0; i < event.results.length; i++) {
        const res = event.results[i];
        const piece = res?.[0]?.transcript || '';
        if (res?.isFinal) committed += piece;
        else interim += piece;
      }
      speechFinal = committed;
      speechInterim = interim;
      emit('recording', { elapsed: started ? Date.now() - started : 0, maxSeconds, engine: 'browser', interim: (speechFinal + speechInterim).trim() });
    };
    rec.onerror = event => {
      if (token !== generation) return;
      if (event?.error === 'aborted' && speechStoppedByUser) return;
      hadError = true;
      releaseTimers();
      recognition = null;
      emit('error', { error: browserSpeechError(event?.error), retry: false, engine: 'browser' });
    };
    rec.onend = () => {
      if (token !== generation || hadError) return;
      releaseTimers();
      recognition = null;
      const text = (speechFinal + speechInterim).trim();
      speechFinal = '';
      speechInterim = '';
      if (!text) {
        emit('error', { error: '没有识别到文字，请靠近麦克风重试。', retry: false, engine: 'browser' });
        return;
      }
      onText(text);
      emit('idle');
    };
    try {
      rec.start();
    } catch (error) {
      if (token === generation) fail(error, { engine: 'browser' });
    }
  }

  async function start(forceEngine?: 'browser' | 'server') {
    if (!['idle', 'error'].includes(phase)) return;
    cancel(); const token = generation;
    let mode: VoiceMode = 'auto';
    if (!forceEngine && loadMode) {
      controller = new AbortController();
      try {
        const loaded = await loadMode(controller.signal);
        if (token !== generation) return;
        if (loaded === 'browser' || loaded === 'server' || loaded === 'auto') mode = loaded;
      } catch {
        if (token !== generation) return;
      }
      controller = null;
    }
    activeMode = mode;
    if (forceEngine === 'browser' || (!forceEngine && mode === 'browser')) {
      startBrowserSpeech(token);
      return;
    }

    const Recorder = environment.MediaRecorder;
    if (!environment.navigator?.mediaDevices?.getUserMedia || environment.isSecureContext === false) {
      return fail(new Error('此页面无法访问麦克风。请使用 HTTPS、localhost 或桌面端，也可继续键盘输入。'));
    }
    const mimeType = voiceFormat(Recorder);
    if (!mimeType) return fail(new Error('此浏览器不支持录音格式。请使用新版浏览器或桌面端。'));
    emit('loading', { engine: 'server' });
    controller = new AbortController();
    try {
      const budget = await loadLimits(controller.signal);
      if (token !== generation) return;
      if (!budget || ![budget.maxBytes, budget.maxSeconds].every(value => Number.isSafeInteger(value) && value > 0)) {
        throw new Error('服务端未提供录音限额，请刷新重试或更新电脑端。');
      }
      limits = budget;
    } catch (error) {
      if (token === generation) fail(error);
      return;
    }
    controller = null;
    emit('requesting', { engine: 'server' });
    permissionTimer = setTimeout(() => { if (token === generation) fail(new Error('等待麦克风权限超时，请允许权限后重试。')); }, 30000);
    try {
      const media = await environment.navigator.mediaDevices.getUserMedia({ audio: true });
      if (token !== generation) { media.getTracks().forEach(track => track.stop()); return; }
      clearTimeout(permissionTimer); stream = media;
      recorder = new Recorder!(media, { mimeType, audioBitsPerSecond: 64000 });
      chunks = []; bytes = 0; started = Date.now();
      recorder.ondataavailable = event => {
        if (token !== generation || !event.data.size) return;
        bytes += event.data.size;
        if (bytes > limits.maxBytes) { fail(new Error(`录音超过 ${formatBytes(limits.maxBytes)}，请缩短后重新录制。`)); return; }
        chunks.push(event.data);
      };
      recorder.onerror = event => { if (token === generation) fail(event.error || new Error('录音失败，请重试。')); };
      recorder.onstop = () => {
        if (token !== generation) return;
        release();
        recording = new Blob(chunks, { type: recorder!.mimeType || mimeType }); chunks = [];
        if (!recording.size) { recording = null; fail(new Error('没有录到声音，请重新录音。')); return; }
        upload(token);
      };
      media.getTracks().forEach(track => { track.onended = () => { if (token === generation) fail(new Error('麦克风已断开，请重新录音。')); }; });
      recorder.start(250); emit('recording', { elapsed: 0, maxSeconds: limits.maxSeconds, engine: 'server' });
      timer = setInterval(() => {
        if (token !== generation) return;
        const elapsed = Date.now() - started;
        if (elapsed >= limits.maxSeconds * 1000) stop();
        else emit('recording', { elapsed, maxSeconds: limits.maxSeconds, engine: 'server' });
      }, 1000);
    } catch (error) { if (token === generation) fail(error); }
  }

  function stop() {
    if (phase !== 'recording') return;
    if (recognition) {
      speechStoppedByUser = true;
      emit('finishing', { engine: 'browser' });
      releaseTimers();
      try { recognition.stop(); } catch { /* 忽略已停止 */ }
      return;
    }
    emit('finishing', { engine: 'server' }); clearInterval(timer); recorder!.stop(); release();
  }

  return {
    start,
    startBrowserFallback() { void start('browser'); },
    stop,
    cancel,
    retry() { if (phase === 'error' && recording) upload(++generation); }
  };
}
