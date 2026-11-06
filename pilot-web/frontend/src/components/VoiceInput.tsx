import type { VoiceState } from '../types/input.js';
import React, { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { createVoiceInput, VOICE_MAX_MS } from '../lib/voice-input.js';
import { Icon } from './Icon.jsx';

const clock = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;

export function VoiceInput({ ownerKey, disabled, onBusy, onText }: { ownerKey: string; disabled?: boolean; onBusy: (busy: boolean) => void; onText: (text: string) => void }) {
  const [state, setState] = useState<VoiceState>({ phase: 'idle' });
  const callbacks = useRef({ onBusy, onText }); callbacks.current = { onBusy, onText };
  const voice = useRef<ReturnType<typeof createVoiceInput> | null>(null);
  useEffect(() => {
    const input = createVoiceInput({ transcribe: (...args) => api.transcribeVoice(...args),
      onState: next => { setState(next); callbacks.current.onBusy(!['idle', 'error'].includes(next.phase)); },
      onText: text => callbacks.current.onText(text) });
    voice.current = input;
    return () => { input.cancel(); voice.current = null; };
  }, [ownerKey]);
  const active = state.phase !== 'idle';
  useEffect(() => {
    if (!active) return;
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.isComposing) return;
      event.preventDefault(); event.stopPropagation(); voice.current?.cancel();
    };
    document.addEventListener('keydown', escape, true);
    return () => document.removeEventListener('keydown', escape, true);
  }, [active]);
  const recording = state.phase === 'recording';
  const failed = state.phase === 'error';
  const elapsed = state.elapsed || 0;
  const progress = Math.min(100, Math.round(elapsed / VOICE_MAX_MS * 100));
  return <div className="voice-input">
    <button type="button" className={`voice-button${recording ? ' is-recording' : ''}`}
      disabled={disabled || (!['idle', 'error', 'recording'].includes(state.phase))}
      aria-label={recording ? '完成录音' : '语音输入'} aria-pressed={recording}
      title={recording ? '完成录音并转为文字' : '语音输入 · 录音将发送到已配置的语音服务，转为草稿'}
      onClick={() => recording ? voice.current?.stop() : voice.current?.start()}><Icon name={recording ? "check" : "mic"} /></button>
    {active && <div className={`voice-panel is-${state.phase}`}>
      <div className="voice-head">
        {recording && <span className="voice-dot" aria-hidden="true" />}
        <span className="voice-status" role={failed ? 'alert' : 'status'}>
          {recording ? '正在录音' :
            state.phase === 'requesting' ? '正在请求麦克风权限…' :
              failed ? state.error :
                state.phase === 'finishing' ? '正在收尾…' : '正在转为文字…'}
        </span>
        {recording && <span className="voice-timer" aria-hidden="true">{clock(elapsed)}</span>}
      </div>
      {recording && <>
        <div className="voice-meter" role="presentation"><span style={{ width: `${progress}%` }} /></div>
        <p className="voice-hint">最长 2 分钟 · 完成后转为草稿，确认后发送</p>
      </>}
      {!recording && !failed && <div className="voice-meter is-busy" role="presentation"><span /></div>}
      <div className="voice-panel-actions">
        {recording && <button type="button" className="btn btn-primary" onClick={() => voice.current?.stop()}>
          <Icon name="check" />完成并转写
        </button>}
        {state.retry && <button type="button" className="btn btn-primary" onClick={() => voice.current?.retry()}>重试转写</button>}
        <button type="button" className="btn" onClick={() => voice.current?.cancel()}>{failed ? '关闭' : '取消'}</button>
      </div>
    </div>}
  </div>;
}
