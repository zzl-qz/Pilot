export const VOICE_MAX_BYTES = 10 * 1024 * 1024;
export const VOICE_MAX_MS = 120000;
const FORMATS = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus'];
export function voiceFormat(Recorder) {
  return FORMATS.find(type => Recorder?.isTypeSupported?.(type)) || '';
}
export function voiceFilename(type) {
  return `recording.${type?.includes('mp4') ? 'mp4' : type?.includes('ogg') ? 'ogg' : 'webm'}`;
}
export function voiceError(error) {
  return ({ NotAllowedError: '麦克风权限被拒绝。请在浏览器或系统设置中允许 Pilot 使用麦克风。',
    NotFoundError: '没有找到麦克风，请连接麦克风后重试。',
    NotReadableError: '麦克风无法使用，可能正被其他应用占用。',
    AbortError: '录音已中断，请重试。' })[error?.name] || error?.message || '语音输入失败，请重试。';
}

/** 录音与转写共用代次：取消、切换会话后迟到的权限/转写结果不会回填。 */
export function createVoiceInput({ environment = globalThis, transcribe, onState, onText }) {
  let generation = 0, phase = 'idle', stream, recorder, timer, permissionTimer, controller, recording;
  let chunks = [], bytes = 0, started = 0;
  const emit = (next, extra = {}) => { phase = next; onState({ phase, ...extra }); };
  const release = () => {
    clearInterval(timer); clearTimeout(permissionTimer);
    stream?.getTracks().forEach(track => { track.onended = null; track.stop(); }); stream = null;
  };
  function cancel() {
    generation++; controller?.abort(); controller = null;
    if (recorder && recorder.state !== 'inactive') recorder.stop();
    recorder = null; release(); chunks = []; recording = null; emit('idle');
  }
  function fail(error) {
    generation++;
    if (recorder && recorder.state !== 'inactive') recorder.stop();
    release(); chunks = []; emit('error', { error: voiceError(error), retry: Boolean(recording) });
  }
  async function upload(token) {
    controller = new AbortController(); emit('transcribing');
    try {
      const result = await transcribe(recording, controller.signal);
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
      recorder = new Recorder(media, { mimeType, audioBitsPerSecond: 64000 });
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
        recording = new Blob(chunks, { type: recorder.mimeType || mimeType }); chunks = [];
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
    emit('finishing'); clearInterval(timer); recorder.stop(); release();
  }
  return { start, stop, cancel, retry() { if (phase === 'error' && recording) upload(++generation); } };
}
