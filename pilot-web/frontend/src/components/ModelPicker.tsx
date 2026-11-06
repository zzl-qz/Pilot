import React, { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { buildModelUpdate, normalizeModelNames } from '../lib/model-settings.js';
import { useModelDiscovery } from './ModelDiscovery.jsx';

/** 输入区只负责快速切换；连接配置和手动填写统一进入模型设置。 */
export function ModelPicker({ onClose, models, initialModel = '', initialEndpoint = '' }: { onClose: () => void; models: string[]; initialModel?: string; initialEndpoint?: string }) {
  const feedback = useStore(state => state.modelSave);
  const discovery = useModelDiscovery({ endpoint: initialEndpoint, initialModels: models, initialEndpoint });
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(initialModel);
  const [pending, setPending] = useState('');
  const [position, setPosition] = useState<React.CSSProperties | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const mounted = useRef(false);
  const cached = useRef<string[] | null>(null);
  const probe = useRef(discovery.probe);
  probe.current = discovery.probe;
  const listId = useId();

  useEffect(() => {
    mounted.current = true;
    useStore.getState().resetModelFeedback();
    void probe.current();
    return () => { mounted.current = false; };
  }, []);

  // 定位前弹层不可见，此时 focus 会被浏览器忽略；等首次定位完成再交给搜索框。
  const positioned = position !== null;
  useEffect(() => {
    if (positioned) input.current?.focus({ preventScroll: true });
  }, [positioned]);

  useEffect(() => {
    if (discovery.discovered) cached.current = discovery.models;
  }, [discovery.discovered, discovery.models]);

  // 固定在视口内：窄屏、短窗口、滚动及移动端软键盘都使用同一套坐标。
  useLayoutEffect(() => {
    const trigger = document.getElementById('model-chip');
    if (!trigger) return;
    const place = () => {
      const rect = trigger.getBoundingClientRect();
      const viewport = window.visualViewport;
      const x = viewport?.offsetLeft || 0, y = viewport?.offsetTop || 0;
      const width = viewport?.width || window.innerWidth, height = viewport?.height || window.innerHeight;
      const above = rect.top - y - 20, below = y + height - rect.bottom - 20;
      const upward = above >= 280 || above >= below;
      setPosition({
        left: Math.max(x + 12, Math.min(rect.left, x + width - Math.min(360, width - 24) - 12)),
        width: Math.min(360, width - 24),
        maxHeight: Math.max(0, upward ? above : below),
        ...(upward ? { bottom: window.innerHeight - rect.top + 8 } : { top: rect.bottom + 8 })
      });
    };
    place();
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(place) : null;
    observer?.observe(trigger);
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    window.visualViewport?.addEventListener('resize', place);
    window.visualViewport?.addEventListener('scroll', place);
    return () => {
      observer?.disconnect();
      window.removeEventListener('resize', place);
      window.removeEventListener('scroll', place, true);
      window.visualViewport?.removeEventListener('resize', place);
      window.visualViewport?.removeEventListener('scroll', place);
    };
  }, []);

  const candidates = discovery.discovered ? discovery.models : cached.current ?? models;
  const available = normalizeModelNames([initialModel, ...candidates]);
  const terms = query.trim().toLowerCase().split(/\s+/);
  const shown = available.filter(model => terms.every(term => model.toLowerCase().includes(term)));
  const focused = shown.includes(active) ? active : shown[0];
  const activeIndex = shown.indexOf(focused);
  useEffect(() => {
    menu.current?.querySelector('[data-active="true"]')?.scrollIntoView({ block: 'nearest' });
  }, [focused, query]);

  async function select(model: string) {
    if (!model || useStore.getState().modelSave.busy) return;
    if (model === initialModel) { onClose(); return; }
    setPending(model);
    // 留空沿用已保存的连接参数，不把打开菜单时的旧端点写回。
    const success = await useStore.getState().saveModel(buildModelUpdate({ model }));
    if (!mounted.current) return;
    setPending('');
    if (success) onClose();
    else input.current?.focus({ preventScroll: true });
  }

  function navigate(event: React.KeyboardEvent<HTMLElement>) {
    if (event.nativeEvent?.isComposing || feedback.busy) return;
    if (event.key === 'Enter') {
      event.preventDefault();
      void select(focused);
    } else if (['ArrowDown', 'ArrowUp'].includes(event.key) && shown.length) {
      event.preventDefault();
      const next = (activeIndex + (event.key === 'ArrowDown' ? 1 : -1) + shown.length) % shown.length;
      setActive(shown[next]);
      input.current?.focus({ preventScroll: true });
    }
  }

  return <div ref={menu} id="model-menu" className="model-picker-menu" role="dialog" aria-label="切换模型"
    style={position || { visibility: 'hidden' }}>
    <div className="model-picker-head">
      <strong>切换模型</strong>
      <button type="button" className="icon-btn" aria-label="刷新模型列表" title="刷新模型列表" disabled={feedback.busy || discovery.probing} onClick={() => probe.current()}>
        <svg className="ui-icon" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><path d="M16 8a6 6 0 1 0-.5 5M16 3v5h-5" strokeLinecap="round" strokeLinejoin="round" /></svg>
      </button>
    </div>
    <div className="model-picker-search">
      <svg className="ui-icon" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true"><circle cx="8.5" cy="8.5" r="5.5" /><path d="m13 13 4 4" strokeLinecap="round" /></svg>
      <input ref={input} role="combobox" aria-label="搜索模型" aria-autocomplete="list" aria-expanded="true" aria-controls={listId}
        aria-activedescendant={activeIndex >= 0 ? `${listId}-${activeIndex}` : undefined} autoComplete="off" spellCheck="false"
        placeholder="搜索模型…" value={query} readOnly={feedback.busy} onChange={event => setQuery(event.target.value)} onKeyDown={navigate} />
    </div>
    <div className="model-picker-caption" role="status">
      <span>{discovery.probing ? '正在获取可用模型…' : discovery.discovered ? '可用模型' : cached.current ? '上次获取的模型' : '已配置模型'}</span>
      <span>{query.trim() ? `${shown.length} / ${available.length}` : available.length}</span>
    </div>
    <div className="model-picker-list" id={listId} role="listbox" aria-label="模型列表" aria-busy={discovery.probing}>
      {shown.map((model, index) => <button key={model} id={`${listId}-${index}`} type="button" role="option" aria-label={model}
        aria-selected={model === initialModel} disabled={feedback.busy} tabIndex={-1} data-active={model === focused}
        className="model-picker-option" title={model} onClick={() => select(model)} onKeyDown={navigate}>
        <span className="model-picker-name">{model}</span>
        {pending === model ? <span className="model-picker-current">切换中…</span> : model === initialModel && <span className="model-picker-current">当前 <svg viewBox="0 0 16 16" aria-hidden="true"><path d="m3 8 3 3 7-7" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg></span>}
      </button>)}
      {!shown.length && <p className="model-picker-empty">{query.trim() ? '没有匹配的模型，试试其他关键词。' : discovery.probing ? '正在加载模型列表…' : '暂无可选模型，可在模型设置中手动填写。'}</p>}
    </div>
    {discovery.error && <p className="model-picker-message" role="alert">{discovery.hint}。可重试刷新，或打开模型设置。</p>}
    {discovery.discovered && !discovery.models.length && initialModel && <p className="model-picker-message" role="status">服务未返回模型列表，仍保留当前配置。可在模型设置中手动填写。</p>}
    {feedback.message && (feedback.busy || feedback.error) && <p className="model-picker-message" role={feedback.error ? 'alert' : 'status'}>{feedback.message}</p>}
    <div className="model-picker-footer">
      <p>对所有会话生效 · 下次请求使用</p>
      <button type="button" disabled={feedback.busy} onClick={() => { onClose(); useStore.getState().setUi({ settingsOpen: true, settingsSection: 'model' }); }}>模型设置 <span aria-hidden="true">›</span></button>
    </div>
  </div>;
}
