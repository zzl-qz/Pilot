import type { VoiceEnvironment, VoiceOptions, VoicePhase, VoiceState } from '../types/input.js';
import { errorInfo } from './errors.js';
export const VOICE_MAX_BYTES = 10 * 1024 * 1024;
export const VOICE_MAX_MS = 120000;
const FORMATS = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus'];
export function voiceFormat(Recorder: Pick<typeof MediaRecorder, 'isTypeSupported'> | null | undefined) {
  return FORMATS.find(type => Recorder?.isTypeSupported?.(type)) || '';
}
export function voiceFilename(type: string | null | undefined) {
  if (type?.includes('wav')) return 'recording.wav';
  return `recording.${type?.includes('mp4') ? 'mp4' : type?.includes('ogg') ? 'ogg' : 'webm'}`;
}

/** 转写统一用 16k 单声道：多数服务只认 mp3/wav，webm/opus 会被判「不支持当前文件格式」。 */
export const VOICE_SAMPLE_RATE = 16000;

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
export async function toWav(blob: Blob, environment: VoiceEnvironment = globalThis) {
  const Ctx = environment.AudioContext || environment.webkitAudioContext;
  const Offline = environment.OfflineAudioContext || environment.webkitOfflineAudioContext;
  if (!Ctx || !Offline || typeof blob.arrayBuffer !== 'function') return blob;
  const context = new Ctx();
  try {
    const decoded = await context.decodeAudioData(await blob.arrayBuffer());
    if (!decoded?.length) return blob;
    const frames = Math.max(1, Math.ceil(decoded.duration * VOICE_SAMPLE_RATE));
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

/** 录音与转写共用代次：取消、切换会话后迟到的权限/转写结果不会回填。 */
export function createVoiceInput({ environment = globalThis, transcribe, onState, onText }: VoiceOptions) {
  let generation = 0, phase: VoicePhase = 'idle';
  let stream: MediaStream | null | undefined, recorder: MediaRecorder | null | undefined;
  let timer: ReturnType<typeof setInterval> | undefined, permissionTimer: ReturnType<typeof setTimeout> | undefined;
  let controller: AbortController | null | undefined, recording: Blob | null | undefined;
  let chunks: Blob[] = [], bytes = 0, started = 0;
  const emit = (next: VoicePhase, extra: Partial<VoiceState> = {}) => { phase = next; onState({ phase, ...extra }); };
  const release = () => {
    // 只有真正创建过定时器才清理：Node 20 的 mock timers 对 undefined 句柄会抛错，
    // 浏览器里 clearTimeout(undefined) 虽是无操作，但显式判断更稳。
    if (timer !== undefined) clearInterval(timer);
    if (permissionTimer !== undefined) clearTimeout(permissionTimer);
    timer = permissionTimer = undefined;
    stream?.getTracks().forEach(track => { track.onended = null; track.stop(); }); stream = null;
  };
  function cancel() {
    generation++; controller?.abort(); controller = null;
    if (recorder && recorder.state !== 'inactive') recorder.stop();
    recorder = null; release(); chunks = []; recording = null; emit('idle');
  }
  function fail(error: unknown) {
    generation++;
    if (recorder && recorder.state !== 'inactive') recorder.stop();
    release(); chunks = []; emit('error', { error: voiceError(error), retry: Boolean(recording) });
  }
  async function upload(token: number) {
    controller = new AbortController(); emit('transcribing');
    try {
      let audio = recording!;
      try { audio = await toWav(recording!, environment); }
      catch { /* 解码不了就按原格式上传，服务端会给出明确原因 */ }
      if (token !== generation) return;
      const result = await transcribe(audio, controller.signal);
      if (token !== generation) return;
      const text = result?.text?.trim();
      if (!text) throw new Error('没有识别到文字，可以重试或重新录音。');
      recording = null; onText(text); emit('idle');
    } catch (error) { if (token === generation) emit('error', { error: voiceError(error), retry: true }); }
  }
  async function start() {
    if (!['idle', 'error'].includes(phase)) return;
    cancel(); const token = generation;
    const Recorder = environment.MediaRecorder;
    if (!environment.navigator?.mediaDevices?.getUserMedia || environment.isSecureContext === false) {
      return fail(new Error('此页面无法访问麦克风。请使用 HTTPS、localhost 或桌面端，也可继续键盘输入。'));
    }
    const mimeType = voiceFormat(Recorder);
    if (!mimeType) return fail(new Error('此浏览器不支持录音格式。请使用新版浏览器或桌面端。'));
    emit('requesting');
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
        if (bytes > VOICE_MAX_BYTES) { fail(new Error('录音超过 10 MB，请缩短后重新录制。')); return; }
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
      recorder.start(250); emit('recording', { elapsed: 0 });
      timer = setInterval(() => {
        if (token !== generation) return;
        const elapsed = Date.now() - started;
        if (elapsed >= VOICE_MAX_MS) stop();
        else emit('recording', { elapsed });
      }, 1000);
    } catch (error) { if (token === generation) fail(error); }
  }
  function stop() {
    if (phase !== 'recording') return;
    emit('finishing'); clearInterval(timer); recorder!.stop(); release();
  }
  return { start, stop, cancel, retry() { if (phase === 'error' && recording) upload(++generation); } };
}
