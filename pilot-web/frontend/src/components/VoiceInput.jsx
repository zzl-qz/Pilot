import React, { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { createVoiceInput } from '../lib/voice-input.js';
import { Icon } from './Icon.jsx';

export function VoiceInput({ ownerKey, disabled, onBusy, onText }) {
  const [state, setState] = useState({ phase: 'idle' });
  const callbacks = useRef({ onBusy, onText }); callbacks.current = { onBusy, onText };
  const voice = useRef(null);
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
    const escape = event => {
      if (event.key !== 'Escape' || event.isComposing) return;
      event.preventDefault(); event.stopPropagation(); voice.current?.cancel();
    };
    document.addEventListener('keydown', escape, true);
    return () => document.removeEventListener('keydown', escape, true);
  }, [active]);
  const recording = state.phase === 'recording';
  const seconds = Math.floor((state.elapsed || 0) / 1000);
  return <div className="voice-input">
    <button type="button" className={`voice-button${recording ? ' is-recording' : ''}`}
      disabled={disabled || (!['idle', 'error', 'recording'].includes(state.phase))}
      aria-label={recording ? '完成录音' : '语音输入'} aria-pressed={recording}
      title={recording ? '完成录音并转为文字' : '语音输入 · 录音将发送到已配置的语音服务，转为草稿'}
      onClick={() => recording ? voice.current?.stop() : voice.current?.start()}><Icon name={recording ? "check" : "mic"} /></button>
    {active && <div className="voice-panel">
      <div className="voice-status" role={state.phase === 'error' ? 'alert' : 'status'}>
        {recording ? `正在录音 ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}` :
          state.phase === 'requesting' ? '正在请求麦克风权限…' : state.phase === 'error' ? state.error : '正在转为文字…'}
      </div>
      {recording && <p>最长 2 分钟 · 完成后转为草稿，确认后发送</p>}
      <div className="voice-panel-actions">
        {state.retry && <button type="button" className="btn" onClick={() => voice.current?.retry()}>重试转写</button>}
        <button type="button" className="btn" onClick={() => voice.current?.cancel()}>{state.phase === 'error' ? '关闭' : '取消'}</button>
      </div>
    </div>}
  </div>;
}
