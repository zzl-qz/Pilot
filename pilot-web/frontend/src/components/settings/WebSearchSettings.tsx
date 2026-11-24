import type { WebSearchSettingsView, WebSearchTestResponse } from "../../types/api.js";
import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { TextInput } from "@astryxdesign/core/TextInput";
import { SegmentedControl, SegmentedControlItem } from "@astryxdesign/core/SegmentedControl";
import { errorInfo } from "../../lib/errors.js";
import { api } from "../../api.js";
import { Icon } from "../Icon.jsx";

export function WebSearchSettings() {
  const [view, setView] = useState<WebSearchSettingsView | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [restarting, setRestarting] = useState(false);
  const [testing, setTesting] = useState(false);
  const [error, setError] = useState('');
  const [feedback, setFeedback] = useState<{ text: string; isError?: boolean } | null>(null);

  const [provider, setProvider] = useState('auto');
  const [apiKey, setApiKey] = useState('');
  const [apiEndpoint, setApiEndpoint] = useState('');
  const [searxngEndpoint, setSearxngEndpoint] = useState('');
  const [fallbackEnabled, setFallbackEnabled] = useState(true);
  const [proxy, setProxy] = useState('');

  const [testQuery, setTestQuery] = useState('Spring Boot 3');
  const [testCategory, setTestCategory] = useState('general');
  const [testResult, setTestResult] = useState<WebSearchTestResponse | null>(null);
  const [testError, setTestError] = useState('');
  const sequence = useRef(0);

  const applyView = useCallback((data: WebSearchSettingsView) => {
    setView(data);
    setProvider(data.provider || 'auto');
    setApiEndpoint(data.apiEndpoint || '');
    setSearxngEndpoint(data.searxngEndpoint || '');
    setFallbackEnabled(Boolean(data.fallbackEnabled));
    setProxy(data.proxy || '');
    setApiKey('');
  }, []);

  const load = useCallback(async () => {
    const req = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await api.webSearchSettings();
      if (req !== sequence.current) return;
      if (data) applyView(data);
    } catch (failure) {
      if (req === sequence.current) setError(errorInfo(failure).message || '无法读取联网搜索设置。');
    } finally {
      if (req === sequence.current) setLoading(false);
    }
  }, [applyView]);

  useEffect(() => {
    void load();
    return () => { sequence.current++; };
  }, [load]);

  const providers = view?.providers ?? [];
  const selectedProvider = providers.find(item => item.id === provider);
  const needsKey = Boolean(selectedProvider?.requiresApiKey);
  const selectedKeySet = selectedProvider ? selectedProvider.apiKeySet : Boolean(view?.apiKeySet);
  const selectedKeyMasked = selectedProvider ? selectedProvider.apiKeyMasked : (view?.apiKeyMasked || '');
  const anyApiKeySet = providers.some(item => item.requiresApiKey && item.apiKeySet);
  const localBadge = view?.localPortOpen
    ? (view.localSettingsUpToDate === false ? '配置可升级' : (view.localRunning ? '运行中' : '外部进程'))
    : '未运行';

  async function save() {
    if (saving) return;
    setSaving(true);
    setFeedback(null);
    try {
      const saved = await api.saveWebSearchSettings({
        provider,
        apiKey: apiKey.trim() || undefined,
        apiEndpoint,
        searxngEndpoint,
        fallbackEnabled,
        proxy
      });
      if (saved) applyView(saved);
      setFeedback({ text: '联网搜索设置已保存，下一次搜索立即生效。' });
    } catch (failure) {
      setFeedback({ text: errorInfo(failure).message || '保存失败，请检查地址与参数格式。', isError: true });
    } finally {
      setSaving(false);
    }
  }

  async function clearKey() {
    if (saving) return;
    setSaving(true);
    setFeedback(null);
    try {
      const saved = await api.clearWebSearchApiKey(needsKey ? provider : undefined);
      if (saved) applyView(saved);
      setFeedback({ text: '已清除当前提供商的搜索 API Key。' });
    } catch (failure) {
      setFeedback({ text: errorInfo(failure).message || '清除密钥失败。', isError: true });
    } finally {
      setSaving(false);
    }
  }

  async function restartLocal() {
    if (restarting) return;
    setRestarting(true);
    setFeedback(null);
    try {
      const next = await api.restartLocalWebSearch();
      if (next) applyView(next);
      setFeedback({ text: next?.localMessage || '已触发本机 SearXNG 重启。' });
    } catch (failure) {
      setFeedback({ text: errorInfo(failure).message || '重启本机 SearXNG 失败。', isError: true });
    } finally {
      setRestarting(false);
    }
  }

  async function runTest() {
    if (testing) return;
    setTesting(true);
    setTestError('');
    setTestResult(null);
    try {
      const result = await api.testWebSearch({
        query: testQuery.trim() || 'Spring Boot 3',
        count: 5,
        category: testCategory,
        provider,
        apiKey: apiKey.trim() || undefined,
        apiEndpoint,
        searxngEndpoint,
        fallbackEnabled,
        proxy
      });
      setTestResult(result);
    } catch (failure) {
      setTestError(errorInfo(failure).message || '测试搜索失败。');
    } finally {
      setTesting(false);
    }
  }

  if (loading && !view) {
    return <p className="settings-status" role="status">正在读取联网搜索设置…</p>;
  }
  if (error && !view) {
    return (
      <section className="settings-group">
        <p className="settings-status" role="alert">{error}</p>
        <div className="settings-inline-actions">
          <Button label="重试" onClick={() => void load()} />
        </div>
      </section>
    );
  }

  return <>
    <section className="settings-group">
      <div className="settings-group-heading">
        <h3>搜索提供商</h3>
        <p>
          当前策略：<strong>{selectedProvider?.label || provider}</strong>
          {view?.effectiveProvider && provider === 'auto' ? `（当前优先命中：${view.effectiveProvider}）` : ''}。
          推荐在「设置 → 搜索」配置博查（国内直连）或 Tavily / Brave / Serper / Exa 密钥，可大幅提高检索命中率。
        </p>
      </div>
      <div className="settings-choices" id="web-search-providers" role="group" aria-label="搜索提供商">
        {providers.map(item => (
          <button key={item.id} type="button" aria-pressed={provider === item.id} disabled={saving}
            className="settings-choice" onClick={() => { setProvider(item.id); setApiKey(''); }}>
            <span>
              <strong>
                {item.label}
                {item.requiresApiKey && item.apiKeySet && <span className="web-search-badge">已配密钥</span>}
              </strong>
              <span>{item.description}</span>
            </span>
            {provider === item.id && <Icon name="check" />}
          </button>
        ))}
      </div>
      {provider === 'auto' && !anyApiKeySet && (
        <p className="settings-status">
          当前未配置商用搜索 API 密钥，自动模式将依次使用本机 SearXNG 与免密钥公开只读源；如需更高命中率，可在上方选择博查 / Tavily / Brave / Serper / Exa 并填写密钥。
        </p>
      )}
    </section>

    <section className="settings-group">
      <div className="settings-group-heading">
        <h3>密钥、端点与回退策略</h3>
        <p>密钥只写不读，留空提交会保留已保存密钥；所有设置保存后立即对后续搜索生效。</p>
      </div>
      <form className="settings-form" onSubmit={event => { event.preventDefault(); void save(); }}>
        {needsKey && (
          <div className="settings-field-grid">
            <TextInput id="web-search-api-key" label={`${selectedProvider?.label || provider} API Key`}
              type="password" size="lg" width="100%" value={apiKey} onChange={setApiKey}
              isDisabled={saving} hasSpellCheck={false} autoComplete="off"
              placeholder={selectedKeySet ? `已保存（${selectedKeyMasked}），留空保持不变` : '粘贴 API Key'} />
            <TextInput id="web-search-api-endpoint" label="自定义 API 端点（可选）"
              size="lg" width="100%" value={apiEndpoint} onChange={setApiEndpoint}
              isDisabled={saving} inputMode="url" hasSpellCheck={false} autoComplete="off"
              placeholder={selectedProvider?.defaultEndpoint || '留空使用官方默认地址'} />
          </div>
        )}
        <div className="settings-field-grid">
          <TextInput id="web-search-searxng-endpoint" label="SearXNG 地址"
            size="lg" width="100%" value={searxngEndpoint} onChange={setSearxngEndpoint}
            isDisabled={saving} inputMode="url" hasSpellCheck={false} autoComplete="off"
            placeholder="http://127.0.0.1:8888" />
          <TextInput id="web-search-proxy" label="出网代理（可选）"
            size="lg" width="100%" value={proxy} onChange={setProxy}
            isDisabled={saving} hasSpellCheck={false} autoComplete="off"
            placeholder="例如 http://127.0.0.1:7890" />
        </div>
        <div className="settings-row">
          <div>
            <h3>自动回退兜底</h3>
            <p>主搜索源失败或返回 0 条结果时，自动依次回退到本机 SearXNG 与免密钥官方公开只读源（Wikipedia / GitHub / StackOverflow / arXiv）。</p>
          </div>
          <SegmentedControl id="web-search-fallback" label="自动回退兜底"
            value={fallbackEnabled ? 'on' : 'off'}
            onChange={value => setFallbackEnabled(value === 'on')}>
            <SegmentedControlItem value="on" label="开启" />
            <SegmentedControlItem value="off" label="关闭" />
          </SegmentedControl>
        </div>
        <div className="settings-form-actions">
          {needsKey && selectedKeySet && (
            <Button label="清除密钥" variant="ghost" className="settings-danger"
              isDisabled={saving} onClick={() => void clearKey()} />
          )}
          <Button type="submit" variant="primary" label={saving ? '保存中…' : '保存设置'} isDisabled={saving} />
        </div>
        {feedback && (
          <p className="settings-status" role={feedback.isError ? 'alert' : 'status'}>{feedback.text}</p>
        )}
      </form>
    </section>

    <section className="settings-group">
      <div className="settings-group-heading">
        <h3>
          本机 SearXNG 运行状态
          <span className={`web-search-badge${view?.localPortOpen && view.localSettingsUpToDate !== false ? ' is-ok' : ''}`}>
            {localBadge}
          </span>
        </h3>
        <p>{view?.localMessage || '未获取状态'}</p>
      </div>
      <div className="web-search-meta">
        {view?.localSettingsPath && <p className="settings-status">配置文件：{view.localSettingsPath}</p>}
        {view?.localLogPath && <p className="settings-status">运行日志：{view.localLogPath}</p>}
      </div>
      <div className="settings-inline-actions">
        <Button label={restarting ? '正在重启…' : '同步配置并重启本机 SearXNG'}
          isDisabled={restarting || !view?.localManaged} onClick={() => void restartLocal()} />
        <Button label="刷新状态" isDisabled={loading || restarting} onClick={() => void load()} />
      </div>
    </section>

    <section className="settings-group">
      <div className="settings-group-heading">
        <h3>实时搜索测试</h3>
        <p>使用当前表单中的设置发起一次只读测试查询（不会自动保存），可用于核验密钥、代理与回退链路。</p>
      </div>
      <form className="settings-source-row" onSubmit={event => { event.preventDefault(); void runTest(); }}>
        <TextInput id="web-search-test-query" label="测试关键词" size="lg" width="100%"
          value={testQuery} onChange={setTestQuery} isDisabled={testing}
          placeholder="输入关键词，例如 Spring Boot 3" />
        <label className="settings-native-field">
          分类
          <select value={testCategory} disabled={testing} onChange={event => setTestCategory(event.target.value)}>
            <option value="general">综合</option>
            <option value="news">新闻</option>
            <option value="it">技术 / 代码</option>
            <option value="science">学术</option>
          </select>
        </label>
        <Button type="submit" variant="primary" label={testing ? '测试中…' : '测试搜索'}
          isDisabled={testing} />
      </form>
      {testError && <p className="settings-status" role="alert">{testError}</p>}
      {testResult && (
        <div className="web-search-test-result" role="status">
          <p className="web-search-test-summary">
            <strong>{testResult.ok ? '测试成功' : '未获取到结果'}</strong>
            {testResult.sourceLabel ? ` · 命中来源：${testResult.sourceLabel}` : ''}
            {` · 耗时 ${testResult.elapsedMs} ms · 命中 ${testResult.hits.length} 条`}
          </p>
          {testResult.triedSources.length > 0 && (
            <p className="settings-status">尝试顺序：{testResult.triedSources.join(' → ')}</p>
          )}
          {testResult.error && <p className="settings-status" role="alert">{testResult.error}</p>}
          {testResult.warnings.length > 0 && (
            <p className="settings-status">诊断提示：{testResult.warnings.join('；')}</p>
          )}
          {testResult.answers.length > 0 && (
            <p className="web-search-test-answer">直接回答：{testResult.answers[0]}</p>
          )}
          {testResult.hits.length > 0 && (
            <ul className="web-search-hits">
              {testResult.hits.map((hit, idx) => (
                <li key={`${hit.url}-${idx}`}>
                  <a href={hit.url} target="_blank" rel="noreferrer">{hit.title || hit.url}</a>
                  {(hit.publishedDate || hit.engines.length > 0) && (
                    <span className="web-search-hit-meta">
                      {[hit.publishedDate, hit.engines.join(', ')].filter(Boolean).join(' · ')}
                    </span>
                  )}
                  {hit.snippet && <p>{hit.snippet}</p>}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </section>
  </>;
}
