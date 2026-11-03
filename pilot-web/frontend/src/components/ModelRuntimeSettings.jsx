import React, { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import './request-usage.css';

const RATE_FIELDS = [['input', '普通输入'], ['cachedInput', '缓存读取'], ['cacheWrite', '缓存写入'], ['output', '输出']];
export function ModelRuntimeSettings({ model, endpoint }) {
  const [value, setValue] = useState(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const generation = useRef(0);
  useEffect(() => {
    generation.current++; let live = true; setValue(null); setError(''); setNotice(''); setBusy(false);
    api.modelRuntime().then(data => {
      if (!data?.options || !data.model || !data.endpoint) throw new Error('端点未提供完整的缓存与计价配置，请刷新或升级服务端。');
      if (live) setValue(data);
    }).catch(e => { if (live) setError(e.message); });
    return () => { live = false; generation.current++; };
  }, [model, endpoint, refresh]);
  const update = (key, next) => { setNotice(''); setValue(current => ({ ...current, options: { ...current.options, [key]: next } })); };
  const price = (key, next) => update('pricing', { ...(value.options.pricing || {}), [key]: next });
  async function save(event) {
    const revision = generation.current;
    event.preventDefault(); setBusy(true); setError(''); setNotice('');
    try {
      const pricing = { ...(value.options.pricing || {}) };
      for (const [key] of RATE_FIELDS) {
        const raw = pricing[key];
        pricing[key] = raw == null || String(raw).trim() === '' ? null : Number(raw);
        if (pricing[key] != null && (!Number.isFinite(pricing[key]) || pricing[key] < 0)) throw new Error('单价必须是非负数字，未知时请留空。');
      }
      const saved = await api.saveModelRuntime({ model: value.model, endpoint: value.endpoint, options: { ...value.options, pricing } });
      if (revision === generation.current) { setValue(saved); setNotice('已保存，仅对该端点与模型的后续请求生效。'); }
    } catch (e) { if (revision === generation.current) setError(e.message); }
    finally { if (revision === generation.current) setBusy(false); }
  }
  return <details className="request-runtime-settings" open>
    <summary>请求计价与缓存</summary>
    {!value && !error && <p className="request-setting-status">正在读取模型配置…</p>}
    {error && <p className="request-setting-status" role="alert">{error} {!value && <button type="button" className="request-text-action" onClick={() => setRefresh(n => n + 1)}>重试</button>}</p>}
    {value && <form onSubmit={save}>
      <p className="request-setting-status">为已保存的 {value.model} 配置。单价单位为每百万 tokens；留空表示未知，0 表示免费。</p>
      <div className="request-prices">
        <label className="task-field">币种<select value={value.options.pricing?.currency || 'USD'} onChange={e => price('currency', e.target.value)}>
          {['USD', 'CNY', 'EUR', 'GBP'].map(currency => <option key={currency}>{currency}</option>)}
        </select></label>
        <label className="task-field">报价来源<input maxLength={300} value={value.options.pricing?.source || ''} placeholder="例如：网关报价，2026-11" onChange={e => price('source', e.target.value)} /></label>
        {RATE_FIELDS.map(([key, label]) => <label key={key} className="task-field">{label}<input type="number" min="0" max="1000000" step="any"
          aria-label={`${label}每百万 token 单价`} value={value.options.pricing?.[key] ?? ''} placeholder="未知" onChange={e => price(key, e.target.value)} /></label>)}
      </div>
      <p className="request-setting-status">缓存写入无额外计费时，填写与普通输入相同的单价。估算不包含税费、订阅优惠或服务商额外收费。</p>
      <label className="task-field">端点能力<select value={value.options.provider || 'auto'} onChange={e => { update('provider', e.target.value); }}>
        <option value="auto">自动识别官方端点</option><option value="generic">通用兼容端点</option>
        <option value="openai">已确认的 OpenAI 能力</option><option value="deepseek">已确认的 DeepSeek 能力</option>
        <option value="anthropic-compatible">支持 Anthropic 缓存标记的兼容网关</option>
      </select></label>
      <label className="task-field">缓存策略<select value={value.options.cacheMode || 'auto'} onChange={e => update('cacheMode', e.target.value)}>
        <option value="auto">按端点能力自动选择</option><option value="explicit">显式缓存标记</option><option value="none">不附加缓存参数</option>
      </select></label>
      <label className="task-field">显式缓存有效期<select value={value.options.cacheTtl || ''} onChange={e => update('cacheTtl', e.target.value)}>
        <option value="">端点默认</option><option value="5m">5 分钟</option><option value="1h">1 小时</option>
      </select></label>
      <details><summary>兼容与压缩选项</summary>
        {[['instructionMessages', '支持对话中的 system 指令更新'], ['streamUsage', '流式请求允许 include_usage']].map(([key, label]) =>
          <label className="task-field" key={key}>{label}<select value={value.options[key] == null ? 'auto' : String(value.options[key])}
            onChange={e => update(key, e.target.value === 'auto' ? null : e.target.value === 'true')}>
            <option value="auto">按端点能力</option><option value="true">已确认支持</option><option value="false">不支持</option>
          </select></label>)}
        <label className="task-field">压缩请求<select value={value.options.compactionMode || 'short'} onChange={e => update('compactionMode', e.target.value)}>
          <option value="short">优先使用较短的摘要输入</option><option value="prefix">满足成本和容量条件时尝试缓存复用</option>
        </select></label>
      </details>
      <p className="request-setting-status">端点的自动缓存不受“不附加缓存参数”选项控制。自定义能力需与网关协议一致。</p>
      <button className="btn" type="submit" disabled={busy}>{busy ? '正在保存…' : '保存计价与缓存设置'}</button>
      {notice && <p className="request-setting-status" role="status">{notice}</p>}
    </form>}
  </details>;
}
