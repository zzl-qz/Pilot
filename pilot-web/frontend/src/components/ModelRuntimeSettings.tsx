import type * as S from '../types/server.js';
import type { Update } from '../types/api.js';
type RateKey = 'input' | 'cachedInput' | 'cacheWrite' | 'output';
type PricingForm = Omit<S.ModelPricing, RateKey> & Record<RateKey, number | string | null>;
type RuntimeOptions = Omit<S.ModelRuntimeSettingsOptions, 'pricing'> & { pricing: PricingForm };
type RuntimeView = Omit<S.ModelRuntimeSettingsView, 'options'> & { options: RuntimeOptions };
import { errorInfo } from '../lib/errors.js';
import React, { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { Button } from '@astryxdesign/core/Button';
import './request-usage.css';

function withPricing(data: S.ModelRuntimeSettingsView): RuntimeView {
  const pricing = data.options.pricing || { currency: 'USD', source: '', input: null, cachedInput: null, cacheWrite: null, output: null };
  return { ...data, options: { ...data.options, pricing } };
}

const RATE_FIELDS = [['input', '普通输入'], ['cachedInput', '缓存读取'], ['cacheWrite', '缓存写入'], ['output', '输出']] as const;
export function ModelRuntimeSettings({ model, endpoint }: { model: string; endpoint: string }) {
  const [value, setValue] = useState<RuntimeView | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const generation = useRef(0);
  const feedback = useRef<HTMLParagraphElement>(null);
  useEffect(() => { feedback.current?.scrollIntoView({ block: 'nearest' }); }, [error, notice]);
  useEffect(() => {
    generation.current++; let live = true; setValue(null); setError(''); setNotice(''); setBusy(false);
    api.modelRuntime().then(data => {
      if (!data?.options || !data.model || !data.endpoint) throw new Error('端点未提供完整的缓存与计价配置，请刷新或升级服务端。');
      if (live) setValue(withPricing(data));
    }).catch(e => { if (live) setError(errorInfo(e).message || '操作失败'); });
    return () => { live = false; generation.current++; };
  }, [model, endpoint, refresh]);
  useEffect(() => {
    const report = value?.capabilityDiscovery;
    if (!report || !value?.model) return;
    const revision = generation.current;
    let live = true;
    const delay = report.state === 'checking' ? 1000 : Math.max(1000, Math.min(60000, report.expiresAt - Date.now()));
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function poll() {
      let updated = false;
      try {
        const next = await api.modelRuntime();
        if (!live || !next || !value || revision !== generation.current || next.model !== value.model || next.endpoint !== value.endpoint) return;
        // 后台状态刷新只合并检测结果，不覆盖用户正在编辑的报价和兼容选项。
        setValue(current => current && ({ ...current, nativeCapabilities: next.nativeCapabilities,
          webSearchSource: next.webSearchSource, capabilityDiscovery: next.capabilityDiscovery }));
        updated = true;
      } catch { /* 保留当前表单并继续自动重试。 */ }
      finally { if (live && !updated) timer = setTimeout(poll, 3000); }
    }
    timer = setTimeout(poll, delay);
    return () => { live = false; clearTimeout(timer); };
  }, [value?.capabilityDiscovery, value?.model, value?.endpoint]);
  const update = <K extends keyof RuntimeOptions,>(key: K, next: RuntimeOptions[K]) => { setNotice(''); setValue(current => current && ({ ...current, options: { ...current.options, [key]: next } })); };
  const price = (key: keyof PricingForm, next: string) => { if (value) update('pricing', { ...value.options.pricing, [key]: next }); };
  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (!value || busy) return;
    const revision = generation.current;
    setBusy(true); setError(''); setNotice('');
    try {
      const pricing: Update<S.ModelPricing> = { currency: value.options.pricing.currency, source: value.options.pricing.source };
      for (const [key] of RATE_FIELDS) {
        const raw = value.options.pricing[key];
        pricing[key] = raw == null || String(raw).trim() === '' ? null : Number(raw);
        if (pricing[key] != null && (!Number.isFinite(pricing[key]) || pricing[key] < 0)) throw new Error('单价必须是非负数字，未知时请留空。');
      }
      const saved = await api.saveModelRuntime({ model: value.model, endpoint: value.endpoint, options: { ...value.options, pricing } });
      if (!saved?.options || !saved.model || !saved.endpoint) throw new Error('服务端返回空模型配置，请重新加载后重试');
      if (revision === generation.current) { setValue(withPricing(saved)); setNotice('已保存，仅对该端点与模型的后续请求生效。'); }
    } catch (e) { if (revision === generation.current) setError(errorInfo(e).message || '操作失败'); }
    finally { if (revision === generation.current) setBusy(false); }
  }
  return <details className="request-runtime-settings settings-group settings-disclosure" open>
    <summary>请求计价与缓存</summary>
    {!value && !error && <p className="settings-status" role="status">正在读取模型配置…</p>}
    {!value && error && <div className="settings-inline-actions">
      <p className="settings-status" role="alert">{error}</p>
      <Button label="重试" onClick={() => setRefresh(count => count + 1)} />
    </div>}
    {value && <form onSubmit={save} onKeyDown={event => {
      if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault();
    }}>
      <fieldset className="settings-runtime-form settings-form" disabled={busy} aria-busy={busy}>
        <p className="settings-status">为已保存的 <strong>{value.model}</strong> 配置。单价单位为每百万 Token，留空表示未知，0 表示免费。</p>
        <div className="settings-field-grid">
          <label className="settings-native-field">币种<select value={value.options.pricing.currency || 'USD'} onChange={e => price('currency', e.target.value)}>
            {['USD', 'CNY', 'EUR', 'GBP'].map(currency => <option key={currency}>{currency}</option>)}
          </select></label>
          <label className="settings-native-field">报价来源<input maxLength={300} value={value.options.pricing.source || ''} placeholder="例如：网关报价，2026-11" onChange={e => price('source', e.target.value)} /></label>
          {RATE_FIELDS.map(([key, label]) => <label key={key} className="settings-native-field">{label}<input type="number" min="0" max="1000000" step="any"
            aria-label={`${label}每百万 token 单价`} value={value.options.pricing[key] ?? ''} placeholder="未知" onChange={e => price(key, e.target.value)} /></label>)}
        </div>
        <p className="settings-status">缓存写入无额外计费时，填写与普通输入相同的单价。估算不包含税费、订阅优惠或服务商额外收费。</p>
        <div className="settings-field-grid">
          <label className="settings-native-field">端点能力<select value={value.options.provider || 'auto'} onChange={e => update('provider', e.target.value)}>
            <option value="auto">自动识别端点协议</option><option value="generic">通用兼容端点</option>
            <option value="openai">已确认的 OpenAI 能力</option><option value="deepseek">已确认的 DeepSeek 能力</option>
            <option value="anthropic">Anthropic Messages API</option>
            <option value="anthropic-compatible">支持 Anthropic 缓存标记的兼容网关</option>
          </select></label>
          <label className="settings-native-field">网页搜索<select value={value.options.webSearchMode || 'auto'} onChange={e => update('webSearchMode', e.target.value)}>
            <option value="auto">自动检测并优先使用服务端能力</option>
            <option value="local">始终使用 Pilot 本地搜索</option>
            <option value="openai-responses">使用 OpenAI Responses 原生搜索</option>
            <option value="anthropic-messages">使用 Anthropic Messages 原生搜索</option>
          </select></label>
        </div>
        <p className="settings-status">当前使用 {value.webSearchSource || 'Pilot 本地搜索'}。原生能力 {value.nativeCapabilities?.join('、') || '无'}。自动验证在后台进行，未确认时继续使用 Pilot 工具。</p>
        {value.capabilityDiscovery && <p className="settings-status" role="status">
          {value.capabilityDiscovery.state === 'checking' ? '正在自动验证，无需手动配置。' : `本轮能力验证请求：${value.capabilityDiscovery.probeRequests || 0} 次。`}
          自动验证可能产生少量服务商调用费用，不发送会话内容或附件。
        </p>}
        <div className="settings-field-grid">
          <label className="settings-native-field">缓存策略<select value={value.options.cacheMode || 'auto'} onChange={e => update('cacheMode', e.target.value)}>
            <option value="auto">按端点能力自动选择</option><option value="explicit">显式缓存标记</option><option value="none">不附加缓存参数</option>
          </select></label>
          <label className="settings-native-field">显式缓存有效期<select value={value.options.cacheTtl || ''} onChange={e => update('cacheTtl', e.target.value)}>
            <option value="">端点默认</option><option value="5m">5 分钟</option><option value="1h">1 小时</option>
          </select></label>
        </div>
        <details className="settings-disclosure">
          <summary>兼容与压缩选项</summary>
          <div className="settings-field-grid">
            {([['instructionMessages', '支持对话中的 system 指令更新'], ['streamUsage', '流式请求允许 include_usage']] as const).map(([key, label]) =>
              <label className="settings-native-field" key={key}>{label}<select value={value.options[key] == null ? 'auto' : String(value.options[key])}
                onChange={e => update(key, e.target.value === 'auto' ? null : e.target.value === 'true')}>
                <option value="auto">按端点能力</option><option value="true">已确认支持</option><option value="false">不支持</option>
              </select></label>)}
            <label className="settings-native-field">压缩请求<select value={value.options.compactionMode || 'short'} onChange={e => update('compactionMode', e.target.value)}>
              <option value="short">优先使用较短的摘要输入</option><option value="prefix">满足成本和容量条件时尝试缓存复用</option>
            </select></label>
          </div>
        </details>
        <p className="settings-status">端点的自动缓存不受“不附加缓存参数”选项控制。自定义能力需与网关协议一致。</p>
        <div className="settings-form-actions">
          <Button label={busy ? '正在保存…' : '保存计价与缓存设置'} type="submit" isDisabled={busy} />
        </div>
      </fieldset>
      {(error || notice) && <p ref={feedback} className="settings-status runtime-feedback" role={error ? 'alert' : 'status'}>{error || notice}</p>}
    </form>}
  </details>;
}
