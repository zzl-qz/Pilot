import test from 'node:test';
import assert from 'node:assert/strict';
import { browserSpeechSupported, createVoiceInput } from '../src/lib/voice-input.js';

function createFakeRecognitionClass() {
  const instances = [];
  class FakeSpeechRecognition {
    continuous = false;
    interimResults = false;
    lang = '';
    onstart = null;
    onresult = null;
    onerror = null;
    onend = null;
    started = false;
    stopped = false;
    aborted = false;
    constructor() { instances.push(this); }
    start() { this.started = true; this.onstart?.(); }
    stop() { this.stopped = true; this.onend?.(); }
    abort() { this.aborted = true; }
  }
  return { FakeSpeechRecognition, instances };
}

test('browserSpeechSupported 检测原生与 webkit 前缀语音识别能力', () => {
  const { FakeSpeechRecognition } = createFakeRecognitionClass();
  assert.equal(browserSpeechSupported({}), false);
  assert.equal(browserSpeechSupported({ SpeechRecognition: FakeSpeechRecognition }), true);
  assert.equal(browserSpeechSupported({ webkitSpeechRecognition: FakeSpeechRecognition }), true);
});

test('浏览器免密钥模式实时更新草稿并在停止时提交文字，取消后不回填迟到结果', async () => {
  const { FakeSpeechRecognition, instances } = createFakeRecognitionClass();
  const states = [];
  const texts = [];
  let serverCalled = 0;

  const voice = createVoiceInput({
    environment: { SpeechRecognition: FakeSpeechRecognition, navigator: { language: 'zh-CN' }, isSecureContext: true },
    loadLimits: async () => ({ maxBytes: 1024, maxSeconds: 60 }),
    loadMode: async () => 'browser',
    transcribe: async () => { serverCalled++; return { text: 'should-not-run' }; },
    onState: s => states.push(s),
    onText: t => texts.push(t)
  });

  await voice.start();
  const rec = instances[0];
  assert.equal(rec.continuous, true);
  assert.equal(rec.interimResults, true);
  assert.equal(rec.lang, 'zh-CN');

  rec.onresult?.({ results: [{ isFinal: false, 0: { transcript: '测试一下' } }] });
  assert.equal(states.at(-1).interim, '测试一下');

  rec.onresult?.({ results: [{ isFinal: true, 0: { transcript: '测试一下免密钥语音' } }] });
  voice.stop();

  assert.deepEqual(texts, ['测试一下免密钥语音']);
  assert.equal(serverCalled, 0);
  assert.equal(states.at(-1).phase, 'idle');

  // 取消后迟到结果不得回填
  await voice.start();
  const second = instances[1];
  voice.cancel();
  assert.equal(second.aborted, true);
  second.onresult?.({ results: [{ isFinal: true, 0: { transcript: '迟到文本' } }] });
  second.onend?.();
  assert.deepEqual(texts, ['测试一下免密钥语音']);
});

test('不支持 Web Speech API 的环境给出明确提示与替代方案', async () => {
  const states = [];
  const voice = createVoiceInput({
    environment: { isSecureContext: true },
    loadLimits: async () => ({ maxBytes: 1024, maxSeconds: 60 }),
    loadMode: async () => 'browser',
    transcribe: async () => ({ text: '' }),
    onState: s => states.push(s),
    onText: () => {}
  });

  await voice.start();
  const last = states.at(-1);
  assert.equal(last.phase, 'error');
  assert.match(last.error, /不支持原生 Web Speech/);
  assert.match(last.error, /Whisper/);
});

test('自动模式下服务端转写失败且浏览器支持 Web Speech 时暴露一键切换回退', async () => {
  const { FakeSpeechRecognition } = createFakeRecognitionClass();
  const states = [];
  class FakeMediaRecorder {
    static isTypeSupported(t) { return t === 'audio/webm'; }
    state = 'inactive';
    mimeType = 'audio/webm';
    ondataavailable = null;
    onerror = null;
    onstop = null;
    start() { this.state = 'recording'; }
    stop() {
      this.state = 'inactive';
      this.ondataavailable?.({ data: new Blob([new Uint8Array([1, 2, 3])], { type: 'audio/webm' }) });
      this.onstop?.();
    }
  }
  const voice = createVoiceInput({
    environment: {
      MediaRecorder: FakeMediaRecorder,
      webkitSpeechRecognition: FakeSpeechRecognition,
      navigator: { mediaDevices: { getUserMedia: async () => ({ getTracks: () => [{ stop() {}, onended: null }] }) } },
      isSecureContext: true
    },
    loadLimits: async () => ({ maxBytes: 1024 * 1024, maxSeconds: 60 }),
    loadMode: async () => 'auto',
    transcribe: async () => { throw new Error('语音服务鉴权失败'); },
    onState: s => states.push(s),
    onText: () => {}
  });

  await voice.start();
  voice.stop();
  await new Promise(resolve => setTimeout(resolve, 20));
  const last = states.at(-1);
  assert.equal(last.phase, 'error');
  assert.equal(last.retry, true);
  assert.equal(last.browserFallback, true);
});
