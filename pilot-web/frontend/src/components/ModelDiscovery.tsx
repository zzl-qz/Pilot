import { errorInfo } from '../lib/errors.js';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { buildModelProbe, describeModelsFetched, normalizeModelNames } from '../lib/model-settings.js';

const EMPTY: string[] = [];
interface DiscoveryResult { endpoint: string; apiKey: string; models: string[]; probing?: boolean; hint: string; error?: boolean }

/** 探测只属于当前表单和连接参数，不把未保存端点的结果写入全局模型配置。 */
export function useModelDiscovery({ endpoint = '', apiKey = '', initialModels = EMPTY, initialEndpoint = '' }: { endpoint?: string; apiKey?: string; initialModels?: string[]; initialEndpoint?: string }) {
  const [result, setResult] = useState<DiscoveryResult | null>(null);
  const sequence = useRef(0);
  const target = buildModelProbe({ endpoint, apiKey });
  const current = useRef(target);
  current.current = target;
  useEffect(() => {
    sequence.current++;
    setResult(null);
    return () => { sequence.current++; };
  }, [target.endpoint, target.apiKey]);
  const preset = useMemo(() => normalizeModelNames(initialModels), [initialModels]);
  const visible = result?.endpoint === target.endpoint && result?.apiKey === target.apiKey ? result : null;
  const models = visible ? visible.models : target.endpoint === initialEndpoint.trim() && !target.apiKey ? preset : EMPTY;
  return {
    models,
    probing: Boolean(visible?.probing),
    hint: visible?.hint || '',
    error: Boolean(visible?.error),
    discovered: Boolean(visible && !visible.probing && !visible.error),
    async probe() {
      const request = ++sequence.current;
      const matches = () => sequence.current === request && current.current.endpoint === target.endpoint && current.current.apiKey === target.apiKey;
      setResult({ ...target, models: EMPTY, probing: true, hint: '正在获取端点可用模型…' });
      try {
        const response = await useStore.getState().probeModels(target);
        if (!matches()) return;
        if (!Array.isArray(response?.models)) throw new Error('模型列表格式无效，请重试或手动填写模型名称');
        const models = normalizeModelNames(response.models);
        setResult({ ...target, models, hint: describeModelsFetched({ models }, target) });
      } catch (error) {
        if (matches()) setResult({ ...target, models: EMPTY, error: true, hint: '探测失败：' + (errorInfo(error).message || '无法读取模型列表') });
      }
    }
  };
}

/** 设置页和输入区共用完整列表；长列表可搜索，支持方向键和 Enter 选择。 */
export function ModelOptions({ models, value, onSelect, disabled = false, discovered = false }: { models: string[]; value: string; onSelect: (value: string) => void; disabled?: boolean; discovered?: boolean }) {
  const [query, setQuery] = useState('');
  const firstOption = useRef<HTMLButtonElement>(null);
  useEffect(() => { setQuery(''); }, [models]);
  useEffect(() => {
    if (discovered) firstOption.current?.scrollIntoView({ block: 'nearest' });
  }, [models, discovered]);
  if (!models.length) return null;
  const shown = models.filter(model => model.toLowerCase().includes(query.trim().toLowerCase()));
  const focused = shown.includes(value) ? value : shown[0];
  return <section className="model-discovery" aria-label="可选模型">
    <p className="model-options-head">{discovered ? '端点可用模型' : '候选模型'}<span>{query ? `${shown.length} / ${models.length}` : models.length}</span></p>
    {models.length > 8 && <input type="search" aria-label="筛选模型" placeholder="搜索模型名称" value={query} onChange={event => setQuery(event.target.value)} />}
    <div className="model-options" role="listbox" aria-label={discovered ? '探测到的模型' : '候选模型'} onKeyDown={event => {
      if (disabled) return;
      const options = [...event.currentTarget.querySelectorAll<HTMLElement>('[role="option"]')];
      const target = event.target instanceof Element ? event.target.closest('[role="option"]') : null;
      const index = options.findIndex(option => option === target);
      if (index < 0 || !['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length;
      options[next]?.focus();
      onSelect(shown[next]);
    }}>
      {shown.map((model, index) => <button ref={index === 0 ? firstOption : undefined} key={model} type="button" role="option" disabled={disabled} aria-selected={model === value} tabIndex={model === focused ? 0 : -1}
        className={`model-option${model === value ? ' is-on' : ''}`} title={model} onClick={() => onSelect(model)}>
        <span className="model-option-name">{model}</span>
        {model === value && <span className="model-option-check" aria-hidden="true">✓</span>}
      </button>)}
    </div>
    {!shown.length && <p className="chip-hint" role="status">没有匹配的模型，请更换搜索内容；也可以直接填写模型名称。</p>}
    <p className="model-options-help">选择后填入模型名称，点击“保存”后生效。</p>
  </section>;
}
