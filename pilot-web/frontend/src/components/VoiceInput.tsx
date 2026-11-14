import type { VoiceState } from '../types/input.js';
import { useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { Button } from '@astryxdesign/core/Button';
import { IconButton } from '@astryxdesign/core/IconButton';
import { Spinner } from '@astryxdesign/core/Spinner';
import { StatusDot } from '@astryxdesign/core/StatusDot';
import { api } from '../api.js';
import { createVoiceInput } from '../lib/voice-input.js';
import { Icon } from './Icon.jsx';

const clock = (ms: number) => Math.floor(ms / 60000) + ':' + String(Math.floor(ms / 1000) % 60).padStart(2, '0');

interface VoiceInputProps {
  ownerKey: string;
  disabled?: boolean;
  feedbackRef: RefObject<HTMLDivElement | null>;
  onBusy: (busy: boolean) => void;
  onText: (text: string) => void;
}

export function VoiceInput({ ownerKey, disabled, feedbackRef, onBusy, onText }: VoiceInputProps) {
  const [state, setState] = useState<VoiceState>({ phase: 'idle' });
  const callbacks = useRef({ onBusy, onText });
  callbacks.current = { onBusy, onText };
  const voice = useRef<ReturnType<typeof createVoiceInput> | null>(null);
  const trigger = useRef<HTMLButtonElement>(null);

  // 在会话切换的提交阶段取消录音，旧权限请求或转写结果就不会进入新草稿。
  useLayoutEffect(() => {
    const input = createVoiceInput({
      loadLimits: async signal => (await api.settings(signal))?.voiceLimits,
      transcribe: (...args) => api.transcribeVoice(...args),
      onState: next => {
        setState(next);
        callbacks.current.onBusy(next.phase !== 'idle' && next.phase !== 'error');
      },
      onText: text => callbacks.current.onText(text)
    });
    voice.current = input;
    return () => { input.cancel(); voice.current = null; };
  }, [ownerKey]);

  const active = state.phase !== 'idle';
  const recording = state.phase === 'recording';
  const failed = state.phase === 'error';
  const waiting = active && !recording && !failed;

  function dismiss() {
    const restoreFocus = feedbackRef.current?.contains(document.activeElement);
    voice.current?.cancel();
    if (restoreFocus) trigger.current?.focus({ preventScroll: true });
  }

  useLayoutEffect(() => {
    if (!active) return;
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.isComposing || event.keyCode === 229 || event.defaultPrevented) return;
      if (document.querySelector('#composer [aria-expanded="true"], .composer-suggestions')) return;
      const dialog = document.querySelector('dialog[open], [aria-modal="true"]');
      if (dialog?.contains(document.activeElement)) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      dismiss();
    };
    document.addEventListener('keydown', escape, true);
    return () => document.removeEventListener('keydown', escape, true);
  }, [active, feedbackRef]);

  const elapsed = state.elapsed || 0;
  const status = {
    idle: '',
    loading: '正在读取录音限额…',
    requesting: '正在请求麦克风权限…',
    recording: '正在录音',
    finishing: '正在收尾…',
    transcribing: '正在转为文字…',
    error: state.error || '语音输入失败，请重试。'
  }[state.phase];
  const label = recording ? '完成录音' : '语音输入';

  return <>
    <span title={recording ? '完成录音并转为文字' : '录音将发送到已配置的语音服务，转为草稿'}>
      <IconButton ref={trigger} className="composer-icon voice-button" variant="ghost" label={label}
        icon={<Icon name={recording ? 'check' : 'mic'} />} isDisabled={disabled}
        aria-disabled={disabled || waiting || undefined} aria-controls={active ? 'composer-voice-panel' : undefined}
        onClick={() => {
          if (recording) voice.current?.stop();
          else if (!waiting) void voice.current?.start();
        }} />
    </span>
    {active && feedbackRef.current && createPortal(
      <section id="composer-voice-panel" className={'voice-panel composer-voice-panel is-' + state.phase} aria-label="语音输入状态">
        <div className="composer-voice-body">
          <div className="voice-head">
            {waiting && <Spinner size="sm" shade="subtle" aria-hidden="true" />}
            {recording && <StatusDot variant="error" label="正在录音" aria-hidden="true" />}
            {failed && <Icon name="info" />}
            <span className="voice-status" role={failed ? 'alert' : 'status'}>{status}</span>
            {recording && <span className="voice-timer" aria-hidden="true">{clock(elapsed)}</span>}
          </div>
          {recording && <p className="voice-hint">最长 {clock((state.maxSeconds ?? 0) * 1000)} · 转写后可编辑再发送</p>}
        </div>
        <div className="voice-panel-actions">
          {recording && <Button size="sm" label="完成并转写" onClick={() => {
            trigger.current?.focus({ preventScroll: true });
            voice.current?.stop();
          }} />}
          {state.retry && <Button size="sm" label="重试转写" onClick={() => {
            trigger.current?.focus({ preventScroll: true });
            voice.current?.retry();
          }} />}
          <Button size="sm" variant="ghost" label={failed ? '关闭' : '取消'} onClick={dismiss} />
        </div>
      </section>,
      feedbackRef.current
    )}
  </>;
}
