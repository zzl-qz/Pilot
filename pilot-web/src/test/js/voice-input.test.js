import test from 'node:test';
import assert from 'node:assert/strict';
import { createVoiceInput, voiceFormat, voiceFilename, VOICE_MAX_BYTES, VOICE_MAX_MS } from '../../../frontend/src/lib/voice-input.js';
const tick = () => new Promise(resolve => setImmediate(resolve));
function fixture({ getMedia, transcribe, format = 'audio/webm' } = {}) {
  const states = [], texts = [], uploads = [];
  const track = { stopped: false, stop() { this.stopped = true; } };
  const stream = { getTracks: () => [track] };
  let instance;
  class Recorder {
    static isTypeSupported(type) { return type === format; }
    constructor(media, options) { this.mimeType = options.mimeType; this.state = 'inactive'; instance = this; }
    start() { this.state = 'recording'; }
    stop() { this.state = 'inactive'; queueMicrotask(() => { this.ondataavailable?.({ data: new Blob(['sound'], { type: format }) }); this.onstop?.(); }); }
  }
  const voice = createVoiceInput({ environment: { isSecureContext: true, MediaRecorder: Recorder,
    navigator: { mediaDevices: { getUserMedia: getMedia || (async () => stream) } } },
    transcribe: async (blob, signal) => { uploads.push(blob); return transcribe ? transcribe(blob, signal) : { text: '检查按钮和附件' }; },
    onState: state => states.push(state), onText: text => texts.push(text) });
  return { voice, track, stream, states, texts, uploads, get recorder() { return instance; }, get state() { return states.at(-1); } };
}
test('格式按浏览器能力选择，文件扩展名匹配', () => {
  for (const type of ['audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus']) {
    assert.equal(voiceFormat({ isTypeSupported: t => t === type }), type);
    assert.match(voiceFilename(type), new RegExp(type.includes('mp4') ? 'mp4$' : type.includes('ogg') ? 'ogg$' : 'webm$'));
  }
  assert.equal(voiceFormat(null), '');
});
test('完成录音后只回填文字，释放所有音轨；连续点击不会重复上传', async () => {
  const f = fixture(); await f.voice.start(); await f.voice.start();
  assert.equal(f.state.phase, 'recording'); f.voice.stop(); f.voice.stop(); await tick();
  assert.equal(f.state.phase, 'idle'); assert.deepEqual(f.texts, ['检查按钮和附件']); assert.equal(f.uploads.length, 1); assert.ok(f.track.stopped);
});
test('取消权限请求后，迟到的 stream 立即释放且不录音', async () => {
  let grant; const f = fixture({ getMedia: () => new Promise(resolve => { grant = resolve; }) });
  const pending = f.voice.start(); f.voice.cancel(); grant(f.stream); await pending;
  assert.ok(f.track.stopped); assert.equal(f.recorder, undefined); assert.equal(f.uploads.length, 0);
});
test('取消录音、转写时切换会话，不回填迟到结果', async () => {
  const f = fixture(); await f.voice.start(); f.voice.cancel(); await tick(); assert.equal(f.uploads.length, 0);
  let resolve, signal;
  const g = fixture({ transcribe: (_, s) => { signal = s; return new Promise(r => { resolve = r; }); } });
  await g.voice.start(); g.voice.stop(); await tick(); g.voice.cancel(); resolve({ text: '迟到文字' }); await tick();
  assert.ok(signal.aborted); assert.deepEqual(g.texts, []); assert.equal(g.state.phase, 'idle');
});
test('权限拒绝、无设备、设备忙有可操作反馈', async () => {
  for (const name of ['NotAllowedError', 'NotFoundError', 'NotReadableError']) {
    const f = fixture({ getMedia: async () => { throw Object.assign(new Error(), { name }); } });
    await f.voice.start(); assert.equal(f.state.phase, 'error'); assert.match(f.state.error, /麦克风/); f.voice.cancel();
  }
});
test('转写失败保留音频重试，成功只回填一次', async () => {
  let calls = 0; const f = fixture({ transcribe: async () => { if (!calls++) throw Error('offline'); return { text: '重试成功' }; } });
  await f.voice.start(); f.voice.stop(); await tick(); assert.equal(f.state.retry, true);
  f.voice.retry(); f.voice.retry(); await tick(); assert.deepEqual(f.texts, ['重试成功']); assert.equal(f.uploads.length, 2);
});
test('空转写不能把草稿清空', async () => {
  const f = fixture({ transcribe: async () => ({ text: ' ' }) }); await f.voice.start(); f.voice.stop(); await tick();
  assert.equal(f.state.phase, 'error'); assert.deepEqual(f.texts, []); f.voice.cancel();
});
test('录音过大或设备断开，停止音轨，不上传残缺音频', async () => {
  const f = fixture(); await f.voice.start(); f.recorder.ondataavailable({ data: new Blob([new Uint8Array(VOICE_MAX_BYTES + 1)]) }); await tick();
  assert.equal(f.state.phase, 'error'); assert.equal(f.uploads.length, 0); assert.ok(f.track.stopped);
  const g = fixture(); await g.voice.start(); g.track.onended(); await tick(); assert.equal(g.state.phase, 'error'); assert.ok(g.track.stopped);
});
test('非安全页面和不支持录音的环境明确降级', async () => {
  let state;
  const v = createVoiceInput({ environment: { isSecureContext: false }, transcribe() {}, onText() {}, onState: s => { state = s; } });
  await v.start(); assert.match(state.error, /HTTPS/);
  const f = fixture({ format: 'unsupported' }); await f.voice.start(); assert.match(f.state.error, /不支持录音/);
});
test('最长两分钟自动结束；权限等待超时后可重试', async t => {
  t.mock.timers.enable({ apis: ['setTimeout', 'setInterval', 'Date'] });
  const f = fixture(); await f.voice.start(); t.mock.timers.tick(VOICE_MAX_MS); await tick();
  assert.equal(f.uploads.length, 1); assert.ok(f.track.stopped);
  const g = fixture({ getMedia: () => new Promise(() => {}) }); g.voice.start(); t.mock.timers.tick(30000);
  assert.equal(g.state.phase, 'error'); assert.match(g.state.error, /超时/); g.voice.cancel();
});
