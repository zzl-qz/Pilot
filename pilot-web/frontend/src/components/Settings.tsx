import type { ApiResults } from "../types/api.js";
import { useEffect, useRef, useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { TextInput } from "@astryxdesign/core/TextInput";
import { SegmentedControl, SegmentedControlItem } from "@astryxdesign/core/SegmentedControl";
import { errorInfo } from "../lib/errors.js";
import { browserSpeechSupported } from "../lib/voice-input.js";
import { shortcut } from "../lib/interaction.js";
import { permissionLabel } from "../lib/permission.js";
import { useStore } from "../store.js";
import { api } from "../api.js";
import { Icon } from "./Icon.jsx";
import { Modal } from "./Modal.jsx";
import { ModelOptions, useModelDiscovery } from "./model/ModelDiscovery.jsx";
import { ModelSaveFeedback, useModelForm } from "./model/ModelForm.jsx";
import { ModelProfiles } from "./model/ModelProfiles.js";
import { ModelRuntimeSettings } from "./model/ModelRuntimeSettings.jsx";
import { PermissionSettings } from "./settings/PermissionSettings.js";
import { AlertSettings } from "./settings/AlertSettings.js";
import { SkillsSettings } from "./settings/SkillsSettings.js";
import { MemorySettings } from "./settings/MemorySettings.js";
import { McpSettings } from "./settings/McpSettings.js";
import { WebSearchSettings } from "./settings/WebSearchSettings.js";
import { UsageSettings } from "./settings/UsageSettings.js";
import { DiagnosticsSettings } from "./settings/DiagnosticsSettings.js";
import "./settings.css";

const SECTIONS = [
  { id: 'appearance', label: '外观', icon: 'sun', description: '调整工作区主题与强调色，更改即时生效。', Content: AppearanceSettings },
  { id: 'model', label: '模型', icon: 'model', description: '配置聊天模型、语音输入，以及请求计价与缓存。', Content: ModelSettings },
  { id: 'search', label: '搜索', icon: 'search', description: '配置联网搜索提供商、API 密钥、本机 SearXNG 与自动回退。', Content: WebSearchSettings },
  { id: 'permission', label: '权限', icon: 'shield', description: '决定 Pilot 写入文件和执行命令前何时需要批准。', Content: PermissionSettings },
  { id: 'alerts', label: '提醒', icon: 'bell', description: '管理完成提示音、窗口外悬浮提醒、系统通知与任务栏提示。', Content: AlertSettings },
  { id: 'skills', label: '技能', icon: 'skill', description: '管理项目级和用户级技能。', Content: SkillsSettings },
  { id: 'memory', label: '记忆', icon: 'note', description: '审阅候选记忆，管理当前项目与个人全局记忆。', Content: MemorySettings },
  { id: 'mcp', label: 'MCP', icon: 'plug', description: '管理 MCP 服务器与信任关系，接入外部工具。', Content: McpSettings },
  { id: 'usage', label: '用量', icon: 'chart', description: '查看模型请求、Token 和可估算的费用。', Content: UsageSettings },
  { id: 'diagnostics', label: '诊断', icon: 'pulse', description: '检查存储、启动恢复和当前对话的运行记录。', Content: DiagnosticsSettings },
  { id: 'about', label: '关于', icon: 'info', description: '查看本地工作环境和常用快捷键。', Content: AboutSettings }
] as const;

export function SettingsModal() {
  const selected = useStore(state => state.ui.settingsSection);
  const pendingMemoryDrafts = useStore(state => state.pendingMemoryDrafts);
  const section = SECTIONS.find(item => item.id === selected) ?? SECTIONS[0];
  const { Content } = section;
  const navigate = (id: string) => useStore.getState().setUi({ settingsSection: id });
  const navLabel = (item: (typeof SECTIONS)[number]) => {
    if (item.id !== 'memory' || pendingMemoryDrafts <= 0) return item.label;
    return `${item.label} (${pendingMemoryDrafts > 99 ? '99+' : pendingMemoryDrafts})`;
  };
  return (
    <Modal id="settings" title={<><span className="settings-product" aria-hidden="true">Pilot</span>设置</>}
      onClose={() => useStore.getState().setUi({ settingsOpen: false })} wide>
      <div className="settings-layout">
        <nav className="settings-nav" aria-label="设置分类">
          {SECTIONS.map(item => <Button key={item.id} label={navLabel(item)} icon={<Icon name={item.icon} />} variant="ghost"
            className="settings-category" aria-current={section.id === item.id ? 'page' : undefined}
            aria-controls="settings-page" onClick={() => navigate(item.id)} />)}
        </nav>
        <label className="settings-section-select">
          分类
          <select value={section.id} aria-label="设置分类" aria-controls="settings-page" onChange={event => navigate(event.target.value)}>
            {SECTIONS.map(item => <option key={item.id} value={item.id}>{navLabel(item)}</option>)}
          </select>
        </label>
        <section className="settings-body" id="settings-page" key={section.id} aria-labelledby="settings-page-title" tabIndex={0}>
          <header className="settings-page-heading">
            <h2 id="settings-page-title">{section.label}</h2>
            <p>{section.description}</p>
          </header>
          <Content />
        </section>
      </div>
    </Modal>
  );
}

const THEME_MODES = [
  { mode: 'light', label: '浅色' },
  { mode: 'dark', label: '深色' },
  { mode: 'system', label: '跟随系统' }
] as const;

const ACCENTS = [
  { name: 'sky', label: '天蓝' },
  { name: 'blue', label: '靛蓝' },
  { name: 'violet', label: '紫罗兰' },
  { name: 'green', label: '松绿' },
  { name: 'amber', label: '暖橙' },
  { name: 'rose', label: '玫红' }
] as const;

function AppearanceSettings() {
  const themeMode = useStore(state => state.ui.themeMode);
  const accent = useStore(state => state.ui.accent);
  return <>
    <section className="settings-group settings-row">
      <div><h3>主题</h3><p>跟随系统时，Pilot 会随系统切换浅色与深色。</p></div>
      <SegmentedControl id="theme-options" label="主题" value={themeMode} onChange={value => useStore.getState().setTheme(value)}>
        {THEME_MODES.map(item => <SegmentedControlItem key={item.mode} value={item.mode} label={item.label} />)}
      </SegmentedControl>
    </section>
    <section className="settings-group settings-row">
      <div><h3>强调色</h3><p>应用于工作区内的高亮和提示。</p></div>
      <div className="settings-accents" id="accent-options" role="group" aria-label="强调色">
        {ACCENTS.map(item => <Button key={item.name} label={item.label} isIconOnly variant="ghost" className="settings-swatch"
          icon={<span className={`settings-color is-${item.name}`} />} aria-pressed={accent === item.name}
          onClick={() => useStore.getState().setAccent(item.name)} />)}
        <p aria-live="polite">当前为 {ACCENTS.find(item => item.name === accent)?.label || '天蓝'}</p>
      </div>
    </section>
  </>;
}

function isLocalEndpoint(endpoint: string): boolean {
  const raw = endpoint.trim();
  if (!raw) return false;
  try {
    const host = new URL(raw).hostname.toLowerCase();
    return host === 'localhost' || host.endsWith('.localhost') || host === '127.0.0.1'
      || host === '::1' || host === '[::1]' || host === '0.0.0.0' || /^127\.\d+\.\d+\.\d+$/.test(host);
  } catch {
    return false;
  }
}

function ModelSettings() {
  const settings = useStore(state => state.settingsData);
  const { name, endpoint, key, contextTokens, maxOutputTokens, childModel, setName, setEndpoint, setKey, setContextTokens, setMaxOutputTokens, setChildModel, saving, feedback, save, clearKey, resetFromSettings } = useModelForm({ model: settings?.model, endpoint: settings?.modelEndpoint, contextTokens: settings?.contextTokens, maxOutputTokens: settings?.maxOutputTokens, childModel: settings?.childModel });
  const discovery = useModelDiscovery({ endpoint, apiKey: key });
  const probeStatus = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    if (!discovery.probing && !discovery.models.length) probeStatus.current?.scrollIntoView({ block: 'nearest' });
  }, [discovery.hint, discovery.models.length, discovery.probing]);
  return <>
    <section className="settings-group">
      <div className="settings-group-heading">
        <h3>聊天服务</h3>
        <p>{settings?.model ? <>当前使用 <strong>{settings.model}</strong>，保存后应用于后续请求。</> : '填写模型名称和服务端点，保存后应用于后续请求。'}</p>
      </div>
      <form className="settings-form" onSubmit={event => {
        event.preventDefault();
        if (name.trim() && !saving) void save();
      }} onKeyDown={event => {
        if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault();
      }}>
        <div className="settings-field-grid">
          <TextInput id="settings-model-name" label="模型名称" size="lg" width="100%" value={name} onChange={setName}
            isDisabled={saving} hasSpellCheck={false} autoComplete="off" />
          <TextInput id="settings-model-endpoint" label="API 端点" size="lg" width="100%" value={endpoint} onChange={setEndpoint}
            isDisabled={saving} inputMode="url" hasSpellCheck={false} autoComplete="off" />
          <TextInput id="settings-model-context" label="上下文窗口" size="lg" width="100%" value={contextTokens} onChange={setContextTokens}
            isDisabled={saving} inputMode="numeric" placeholder="留空使用 128000" />
          <TextInput id="settings-model-output" label="最大输出" size="lg" width="100%" value={maxOutputTokens} onChange={setMaxOutputTokens}
            isDisabled={saving} inputMode="numeric" placeholder="留空使用 16000" />
          <TextInput id="settings-model-child" label="子代理模型" size="lg" width="100%" value={childModel} onChange={setChildModel}
            isDisabled={saving} hasSpellCheck={false} autoComplete="off" placeholder="留空则子代理使用主模型" />
        </div>
        <TextInput id="settings-model-key" label="API Key" size="lg" width="100%" type="password"
          isDisabled={saving} value={key} onChange={setKey} autoComplete="new-password"
          placeholder={settings?.modelApiKeySet
            ? `已保存 ${settings.modelApiKeyMasked || ''}（不填沿用）`
            : isLocalEndpoint(endpoint) ? '本机服务可留空（免密钥）' : '尚未保存密钥（本地免密钥服务可留空）'} />
        <div className="settings-form-actions">
          <Button id="settings-model-clear-key" label="清除 Key" variant="ghost" className="settings-danger" isDisabled={saving || !settings?.modelApiKeySet}
            onClick={() => useStore.getState().askConfirm({
              title: '清除 API Key',
              message: isLocalEndpoint(endpoint)
                ? '清除后将不携带认证头，本机 Ollama / LM Studio 等免密钥服务仍可正常调用。确定清除吗？'
                : '清除后需要鉴权的云端端点会请求失败；本机或免密钥服务仍可调用。确定清除吗？',
              confirmText: '清除',
              danger: true,
              onConfirm: clearKey
            })} />
          <div>
            <Button id="settings-model-fetch" label={discovery.probing ? '探测中…' : '探测可用模型'} isDisabled={saving || discovery.probing}
              aria-busy={discovery.probing} onClick={discovery.probe} />
            <Button id="settings-model-save" type="submit" label={saving ? '保存中…' : '保存'} variant="primary"
              isDisabled={saving || !name.trim()} aria-busy={saving} />
          </div>
        </div>
        <ModelSaveFeedback id="settings-model-save-feedback" feedback={feedback} />
        {discovery.hint && <p ref={probeStatus} id="settings-model-hint" className="settings-status" role={discovery.error ? 'alert' : 'status'}>{discovery.hint}</p>}
      </form>
      <ModelOptions models={discovery.models} value={name} onSelect={setName} disabled={saving} discovered={discovery.discovered} />
    </section>
    <ModelProfiles
      draft={{ model: name, endpoint, apiKey: key, contextTokens, maxOutputTokens, childModel }}
      onActivated={profile => resetFromSettings(profile)}
    />
    <VoiceSettings />
    <ModelRuntimeSettings model={settings?.model || ''} endpoint={settings?.modelEndpoint || ''} />
  </>;
}

function VoiceSettings() {
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState<ApiResults['voiceSettings'] | null>(null);
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [status, setStatus] = useState<{ message: string; error?: boolean } | null>(null);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    if (!open || value) return;
    let live = true;
    setLoadError('');
    api.voiceSettings().then(data => {
      if (!data || typeof data.model !== 'string') throw new Error('服务端返回空语音配置。');
      if (live) setValue(data);
    }).catch(error => { if (live) setLoadError(errorInfo(error).message || '读取语音设置失败。'); });
    return () => { live = false; };
  }, [open, refresh, value]);

  async function save(clear = false) {
    if (!value || busy) return;
    setBusy(true);
    setStatus(null);
    try {
      const data = await api.saveVoiceSettings(clear
        ? { clearApiKey: true }
        : { endpoint: value.endpoint, model: value.model, apiKey: key, mode: value.mode || 'auto' });
      if (!data || typeof data.model !== 'string') throw new Error('无法确认语音配置，请重试。');
      setValue(data);
      setKey('');
      setStatus({ message: clear ? '已清除独立语音 Key' : '语音设置已保存' });
    } catch (error) {
      setStatus({ message: errorInfo(error).message || '保存语音设置失败。', error: true });
    } finally {
      setBusy(false);
    }
  }

  const speechAvailable = browserSpeechSupported();
  const voiceMode = value?.mode === 'browser' || value?.mode === 'server' ? value.mode : 'auto';

  return <details className="settings-group settings-disclosure voice-settings" onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>语音输入</summary>
    <div className="settings-form">
      <p className="settings-status">支持免密钥的「浏览器原生语音识别」或「本机 Whisper 服务」（whisper.cpp / faster-whisper），也可沿用 OpenAI 兼容转写端点。</p>
      {loadError && <div className="settings-inline-actions">
        <p className="settings-status" role="alert">读取语音设置失败。{loadError}</p>
        <Button label="重新加载语音设置" onClick={() => { setLoadError(''); setRefresh(count => count + 1); }} />
      </div>}
      {!value && !loadError && <p className="settings-status" role="status">正在读取语音设置…</p>}
      {value && <>
        <div className="settings-row">
          <div>
            <strong>识别方式</strong>
            <p className="settings-status">
              {speechAvailable
                ? '当前浏览器支持 Web Speech 原生识别（免密钥）。'
                : '当前浏览器/桌面端未暴露 Web Speech 接口，免密钥使用请填下方本机 Whisper 地址。'}
            </p>
          </div>
          <SegmentedControl id="settings-voice-mode" label="语音识别方式" value={voiceMode}
            onChange={mode => setValue({ ...value, mode })}>
            <SegmentedControlItem value="auto" label="自动" />
            <SegmentedControlItem value="browser" label="浏览器免密钥" />
            <SegmentedControlItem value="server" label="转写接口" />
          </SegmentedControl>
        </div>
        <div className="settings-field-grid">
          <TextInput label="语音模型" size="lg" width="100%" placeholder="填写服务支持的转写模型（如 whisper-1）" value={value.model || ''}
            isDisabled={busy} onChange={model => setValue({ ...value, model })} />
          <TextInput label="独立语音端点（可选）" size="lg" width="100%" inputMode="url" placeholder="留空沿用聊天服务；本地可填 http://127.0.0.1:8080/inference" value={value.endpoint || ''}
            isDisabled={busy} onChange={endpoint => setValue({ ...value, endpoint })} />
        </div>
        <div className="settings-inline-actions">
          <Button size="sm" variant="ghost" label="填入本机 whisper.cpp（免密钥）" isDisabled={busy}
            onClick={() => setValue({ ...value, endpoint: 'http://127.0.0.1:8080/inference', model: value.model || 'whisper-1', mode: 'server' })} />
          <Button size="sm" variant="ghost" label="填入本机 faster-whisper（免密钥）" isDisabled={busy}
            onClick={() => setValue({ ...value, endpoint: 'http://127.0.0.1:8000/v1', model: 'Systran/faster-whisper-small', mode: 'server' })} />
        </div>
        {value.effectiveEndpoint && <p className="settings-status">
          当前请求端点 {value.effectiveEndpoint}{value.localEndpoint ? '（本机回环端点，可免 Key 使用）' : ''}
        </p>}
        <TextInput label="语音 API Key（可选）" size="lg" width="100%" type="password" value={key} autoComplete="new-password"
          isDisabled={busy} placeholder={value.apiKeySet ? '已保存（不填沿用）' : value.localEndpoint ? '本机 Whisper 端点无需填写 Key' : '独立云端服务请填写对应 Key'} onChange={setKey} />
        <div className="settings-form-actions">
          {value.apiKeySet && <Button label="清除语音 Key" variant="ghost" className="settings-danger" isDisabled={busy}
            onClick={() => useStore.getState().askConfirm({
              title: '清除独立语音 Key', message: '清除后，语音服务会尝试使用聊天服务的 Key。', confirmText: '清除', danger: true,
              onConfirm: () => save(true)
            })} />}
          <Button label={busy ? '正在处理…' : '保存语音设置'} isDisabled={busy || !value.model?.trim()} onClick={() => void save()} />
        </div>
      </>}
      {status && <p className="settings-status" role={status.error ? 'alert' : 'status'}>{status.message}</p>}
    </div>
  </details>;
}

function AboutSettings() {
  const workspace = useStore((state) => state.workspace);
  const connection = useStore((state) => state.ui.connection);
  const permissionMode = useStore((state) => state.permissionMode);
  return (
    <div id="settings-about">
      <section className="settings-group">
        <h3>Pilot 本地编码工作区</h3>
        <dl className="diag-grid">
          <div><dt>工作区</dt><dd id="settings-about-workspace">{workspace || '未选择'}</dd></div>
          <div><dt>连接</dt><dd id="settings-about-connection">{connection.text}</dd></div>
          <div><dt>权限</dt><dd id="settings-about-permission">{permissionLabel(permissionMode)}</dd></div>
        </dl>
      </section>
      <section className="settings-group">
        <div className="settings-row">
          <div><h3>常用快捷键</h3><p>完整列表（含审批、选择与模式切换）可在工作台按 {shortcut('/')} 打开。</p></div>
          <Button size="sm" variant="ghost" label="查看全部快捷键"
            onClick={() => useStore.getState().setUi({ settingsOpen: false, shortcutsOpen: true })} />
        </div>
        <dl className="shortcut-list">
          {[
            ['新任务', shortcut('N')],
            ['命令面板', shortcut('K')],
            ['展开侧栏', shortcut('B')],
            ['审阅面板', shortcut('J')],
            ['切换分析/执行模式', 'Shift+Tab'],
            ['快捷键速查表', shortcut('/')]
          ].map(([label, key]) => <div key={label}><dt>{label}</dt><dd><kbd>{key}</kbd></dd></div>)}
        </dl>
      </section>
    </div>
  );
}

