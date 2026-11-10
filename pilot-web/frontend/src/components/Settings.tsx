import type * as S from '../types/server.js';
import type { ApiResults } from '../types/api.js';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { TextInput } from '@astryxdesign/core/TextInput';
import { CheckboxInput } from '@astryxdesign/core/CheckboxInput';
import { SegmentedControl, SegmentedControlItem } from '@astryxdesign/core/SegmentedControl';
import { errorInfo } from '../lib/errors.js';
import { formatCache, totalCost } from '../lib/request-usage.js';
import { shortcut } from '../lib/interaction.js';
import { PERMISSION_MODES, permissionLabel } from '../lib/permission.js';
import { emptySkillsHint, parseInstallSource, summarizeInstallResults, gitUrlProblem } from '../lib/skills.js';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { Icon } from './Icon.jsx';
import { Modal } from './Modal.jsx';
import { ModelOptions, useModelDiscovery } from './ModelDiscovery.jsx';
import { ModelSaveFeedback, useModelForm } from './ModelForm.jsx';
import { ModelRuntimeSettings } from './ModelRuntimeSettings.jsx';
import './settings.css';

const SECTIONS = [
  { id: 'appearance', label: '外观', icon: 'sun', description: '调整工作区主题与强调色，更改即时生效。', Content: AppearanceSettings },
  { id: 'model', label: '模型', icon: 'model', description: '配置聊天模型、语音输入，以及请求计价与缓存。', Content: ModelSettings },
  { id: 'permission', label: '权限', icon: 'shield', description: '决定 Pilot 写入文件和执行命令前何时需要批准。', Content: PermissionSettings },
  { id: 'skills', label: '技能', icon: 'skill', description: '管理项目级和用户级技能。', Content: SkillsSettings },
  { id: 'usage', label: '用量', icon: 'chart', description: '查看模型请求、Token 和可估算的费用。', Content: UsageSettings },
  { id: 'diagnostics', label: '诊断', icon: 'pulse', description: '检查存储、启动恢复和当前对话的运行记录。', Content: DiagnosticsSettings },
  { id: 'about', label: '关于', icon: 'info', description: '查看本地工作环境和常用快捷键。', Content: AboutSettings }
] as const;

export function SettingsModal() {
  const selected = useStore(state => state.ui.settingsSection);
  const section = SECTIONS.find(item => item.id === selected) ?? SECTIONS[0];
  const { Content } = section;
  const navigate = (id: string) => useStore.getState().setUi({ settingsSection: id });
  return (
    <Modal id="settings" title={<><span className="settings-product" aria-hidden="true">Pilot</span>设置</>}
      onClose={() => useStore.getState().setUi({ settingsOpen: false })} wide>
      <div className="settings-layout">
        <nav className="settings-nav" aria-label="设置分类">
          {SECTIONS.map(item => <Button key={item.id} label={item.label} icon={<Icon name={item.icon} />} variant="ghost"
            className="settings-category" aria-current={section.id === item.id ? 'page' : undefined}
            aria-controls="settings-page" onClick={() => navigate(item.id)} />)}
        </nav>
        <label className="settings-section-select">
          分类
          <select value={section.id} aria-label="设置分类" aria-controls="settings-page" onChange={event => navigate(event.target.value)}>
            {SECTIONS.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}
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

function ModelSettings() {
  const settings = useStore(state => state.settingsData);
  const { name, endpoint, key, setName, setEndpoint, setKey, saving, feedback, save, clearKey } = useModelForm({ model: settings?.model, endpoint: settings?.modelEndpoint });
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
        </div>
        <TextInput id="settings-model-key" label="API Key" size="lg" width="100%" type="password"
          isDisabled={saving} value={key} onChange={setKey} autoComplete="new-password"
          placeholder={settings?.modelApiKeySet ? `已保存 ${settings.modelApiKeyMasked || ''}（不填沿用）` : '尚未保存密钥'} />
        <div className="settings-form-actions">
          <Button id="settings-model-clear-key" label="清除 Key" variant="ghost" className="settings-danger" isDisabled={saving || !settings?.modelApiKeySet}
            onClick={() => useStore.getState().askConfirm({
              title: '清除 API Key',
              message: '清除后模型请求会失败，直到重新填写。确定清除吗？',
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
      const data = await api.saveVoiceSettings(clear ? { clearApiKey: true } : { endpoint: value.endpoint, model: value.model, apiKey: key });
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

  return <details className="settings-group settings-disclosure voice-settings" onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>语音输入</summary>
    <div className="settings-form">
      <p className="settings-status">录音会发送到语音服务，转写文字加入草稿。默认沿用聊天服务的端点和 Key，也可以填写支持音频转写的独立端点。</p>
      {loadError && <div className="settings-inline-actions">
        <p className="settings-status" role="alert">读取语音设置失败。{loadError}</p>
        <Button label="重新加载语音设置" onClick={() => { setLoadError(''); setRefresh(count => count + 1); }} />
      </div>}
      {!value && !loadError && <p className="settings-status" role="status">正在读取语音设置…</p>}
      {value && <>
        <div className="settings-field-grid">
          <TextInput label="语音模型" size="lg" width="100%" placeholder="gpt-4o-mini-transcribe" value={value.model || ''}
            isDisabled={busy} onChange={model => setValue({ ...value, model })} />
          <TextInput label="独立语音端点（可选）" size="lg" width="100%" inputMode="url" placeholder="留空沿用聊天服务" value={value.endpoint || ''}
            isDisabled={busy} onChange={endpoint => setValue({ ...value, endpoint })} />
        </div>
        {value.effectiveEndpoint && <p className="settings-status">当前请求端点 {value.effectiveEndpoint}</p>}
        <TextInput label="语音 API Key（可选）" size="lg" width="100%" type="password" value={key} autoComplete="new-password"
          isDisabled={busy} placeholder={value.apiKeySet ? '已保存（不填沿用）' : '独立服务请填写对应 Key'} onChange={setKey} />
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

function PermissionSettings() {
  const mode = useStore(state => state.permissionMode);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  async function choose(next: string) {
    if (next === mode || busy) return;
    setBusy(true);
    setNotice('');
    try {
      await useStore.getState().savePermissionMode(next);
      setNotice(useStore.getState().ui.notice?.text || '');
    } finally { setBusy(false); }
  }
  return (
    <section className="settings-group">
      <div className="settings-group-heading">
        <h3>权限模式</h3>
        <p>当前为 {permissionLabel(mode)}。切换后即时保存。</p>
      </div>
      <div className="settings-choices" id="permission-options" role="group" aria-label="权限模式" aria-busy={busy}>
        {PERMISSION_MODES.map(item => <button key={item.mode} type="button" aria-pressed={mode === item.mode}
          disabled={busy} className="settings-choice" onClick={() => void choose(item.mode)}>
          <span><strong>{item.label}</strong><span>{item.hint}</span></span>
          {mode === item.mode && <Icon name="check" />}
        </button>)}
      </div>
      <p id="settings-permission-hint" className="settings-status">请仅在可信工作区使用「完全放行」。</p>
      {notice && <p className="settings-status" role={notice.startsWith('保存权限模式失败') ? 'alert' : 'status'}>{notice}</p>}
    </section>
  );
}

function SkillsSettings() {
  const skills = useStore(state => state.skills);
  const meta = useStore(state => state.skillsMeta);
  const [installing, setInstalling] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    let live = true;
    setLoading(true);
    void useStore.getState().loadSkills().finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [refresh]);

  return <section className="settings-group">
    <div className="settings-group-heading settings-inline-actions">
      <h3>已安装技能</h3>
      <Button id="skills-install-open" label={installing ? '收起安装' : '安装技能…'} aria-expanded={installing}
        aria-controls={installing ? 'skills-install' : undefined} onClick={() => setInstalling(open => !open)} />
    </div>
    {loading && <p className="settings-status" role="status">正在读取技能…</p>}
    {!loading && !meta && <div className="settings-inline-actions">
      <p className="settings-status" role="alert">暂时无法读取技能清单。</p>
      <Button label="重新加载技能" onClick={() => setRefresh(count => count + 1)} />
    </div>}
    <ul className="skills-list" id="settings-skills-list">
      {skills.map(skill => <li key={skill.name || skill.id}>
        <b>/{skill.command || skill.name}</b>
        <span className="skill-source">{skill.source === 'project' ? '项目级' : '用户级'}</span>
        <span className="skill-desc">{skill.description || ''}</span>
      </li>)}
      {meta && !loading && !skills.length && <li className="panel-empty">{emptySkillsHint(meta.projectRoot)}也可以从 git 仓库或本机目录安装。</li>}
    </ul>
    {!!meta?.invalid?.length && <p className="settings-status">有 {meta.invalid.length} 个目录缺少 SKILL.md 或名称不合法，已跳过。</p>}
    {meta?.projectRoot && <p className="settings-status">项目级目录 {meta.projectRoot}</p>}
    {installing && <SkillsInstall onDone={() => { void useStore.getState().loadSkills(); }} />}
  </section>;
}

/** 预览生成的令牌只用于当时的来源；编辑地址后须重新预览。 */
function SkillsInstall({ onDone }: { onDone: () => void }) {
  const sessionId = useStore(state => state.sessionId);
  const meta = useStore(state => state.skillsMeta);
  const [source, setSource] = useState('');
  const [token, setToken] = useState('');
  const [candidates, setCandidates] = useState<S.SkillsControllerCandidatePayload[]>([]);
  const [picked, setPicked] = useState(() => new Set<string>());
  const [target, setTarget] = useState('project');
  const [busy, setBusy] = useState('');
  const [hint, setHint] = useState('填写 git 地址或本机目录，先查看内容。');
  const [hasError, setHasError] = useState(false);
  const [conflicts, setConflicts] = useState<string[]>([]);
  const candidateList = useRef<HTMLDivElement>(null);
  useEffect(() => { candidateList.current?.firstElementChild?.scrollIntoView({ block: 'nearest' }); }, [candidates]);

  function resetPreview() {
    setToken('');
    setCandidates([]);
    setPicked(new Set());
    setConflicts([]);
  }
  function changeSource(value: string) {
    setSource(value);
    resetPreview();
    setHasError(false);
    setHint('选择来源后，先查看内容。');
  }
  async function preview() {
    if (busy) return;
    resetPreview();
    const parsed = parseInstallSource(source);
    const problem = parsed?.type === 'git' ? gitUrlProblem(parsed.value) : '';
    if (!parsed || problem) {
      setHasError(true);
      setHint(problem || '请输入 http(s):// 开头的 git 地址，或本机技能目录的绝对路径（如 D:\\skills\\review）。');
      return;
    }
    setBusy('preview');
    setHasError(false);
    setHint(parsed.type === 'git' ? '正在拉取仓库（浅克隆）…' : '正在读取目录…');
    try {
      const data = await api.skillsPreview({ source: parsed.value, sessionId: sessionId || undefined });
      const list = Array.isArray(data?.candidates) ? data.candidates : [];
      if (list.some(item => item.valid) && !data?.token) throw new Error('预览凭证缺失，请重试。');
      setToken(data?.token || '');
      setCandidates(list);
      setPicked(new Set(list.filter(item => item.valid).map(item => item.name)));
      setHint(list.length ? `找到 ${list.length} 个技能，勾选后安装。` : (data?.message || '来源里没有找到可安装的技能。'));
    } catch (error) {
      setHasError(true);
      setHint('查看内容失败。' + errorInfo(error).message);
    } finally {
      setBusy('');
    }
  }

  async function install(names: string[], overwrite = false) {
    if (!token || !names.length || busy) return;
    setBusy(overwrite ? 'overwrite' : 'install');
    setHasError(false);
    setHint(overwrite ? '正在覆盖安装…' : '正在安装…');
    try {
      const data = await api.skillsInstall({ token, skills: names, target, sessionId: sessionId || undefined, ...(overwrite ? { overwrite: true } : {}) });
      const { installed, conflicts: conflicted, errors: failed } = summarizeInstallResults(data?.results || []);
      setConflicts(conflicted);
      const parts = [];
      if (installed.length) {
        parts.push(`已安装 ${installed.join('、')}`);
        setPicked(current => new Set([...current].filter(name => !installed.includes(name))));
        onDone();
      }
      if (conflicted.length) parts.push(`发现同名技能 ${conflicted.join('、')}`);
      if (failed.length) parts.push(`安装失败 ${failed.map(item => `${item.name}（${item.reason}）`).join('、')}`);
      setHasError(Boolean(failed.length));
      setHint(parts.length ? parts.join('。') + '。' : '没有执行任何安装。');
    } catch (error) {
      setHasError(true);
      setHint('安装失败。' + errorInfo(error).message);
    } finally {
      setBusy('');
    }
  }

  return <div className="skills-install settings-form" id="skills-install">
    <div className="settings-source-row">
      <TextInput id="skills-install-source" label="来源（git 地址或本机目录）" size="lg" width="100%" value={source}
        placeholder={'https://github.com/… 或 D:\\skills\\review'} isDisabled={Boolean(busy)} hasSpellCheck={false}
        onChange={changeSource} onEnter={() => void preview()} />
      <Button label="浏览…" isDisabled={Boolean(busy)} onClick={() => useStore.getState().setUi({
        picker: { title: '选择技能目录', onPick: changeSource }
      })} />
      <Button id="skills-install-preview" label={busy === 'preview' ? '读取中…' : '查看内容'} isDisabled={Boolean(busy)} onClick={preview} />
    </div>
    {!!candidates.length && <div ref={candidateList} className="skills-install-candidates" id="skills-install-candidates">
      {candidates.map(item => <CheckboxInput key={item.name} label={item.command ? `${item.name} · /${item.command}` : item.name}
        description={item.valid ? item.description || undefined : `无效（${item.reason || '校验未通过'}）`}
        isDisabled={!item.valid || Boolean(busy)} value={picked.has(item.name)}
        onChange={checked => setPicked(current => {
          const next = new Set(current);
          if (checked) next.add(item.name);
          else next.delete(item.name);
          return next;
        })} />)}
    </div>}
    <fieldset className="skills-install-target" id="skills-install-target" disabled={Boolean(busy)}>
      <legend>安装位置</legend>
      {[['project', '装到项目', meta?.projectRoot], ['user', '装到用户目录', meta?.userRoot]].map(([value, label, root]) => (
        <label key={value} title={root || ''}>
          <input type="radio" name="skills-target" checked={target === value} onChange={() => { setTarget(value); setConflicts([]); }} />
          {label}
        </label>
      ))}
    </fieldset>
    <div className="skills-install-actions">
      <Button id="skills-install-confirm" variant="primary" label={`安装选中的 ${picked.size} 个`}
        isDisabled={!token || !picked.size || Boolean(busy)} onClick={() => install([...picked])} />
      {!!conflicts.length && <Button id="skills-install-overwrite" variant="ghost" className="settings-danger" label="覆盖安装冲突项"
        isDisabled={Boolean(busy)} onClick={() => useStore.getState().askConfirm({
          title: '覆盖安装冲突技能', message: `覆盖会替换目标目录里的 ${conflicts.join('、')}。`, confirmText: '覆盖安装', danger: true,
          onConfirm: () => install(conflicts, true)
        })} />}
    </div>
    <p id="skills-install-hint" className="settings-status" role={hasError ? 'alert' : 'status'}>{hint}</p>
  </div>;
}

/** token 数字口径：与旧版一致（万级以上转 k/M/B）。 */
function formatCount(value: unknown) {
  const number = Number(value) || 0;
  if (number >= 1_000_000_000) return `${(number / 1_000_000_000).toFixed(1)}B`;
  if (number >= 1_000_000) return `${(number / 1_000_000).toFixed(1)}M`;
  if (number >= 10_000) return `${(number / 1_000).toFixed(0)}k`;
  return number.toLocaleString('zh-CN');
}

function UsageSettings() {
  const usage = useStore(state => state.usageData);
  const days = useStore(state => state.ui.usageDays);
  const [requestedDays, setRequestedDays] = useState(days);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const sequence = useRef(0);

  const load = useCallback(async (target: number) => {
    const request = ++sequence.current;
    setRequestedDays(target);
    setLoading(true);
    setError('');
    try {
      const data = await api.usage(target);
      if (!data || !Array.isArray(data.daily)) throw new Error('服务端返回空用量信息。');
      if (request !== sequence.current) return;
      useStore.setState(draft => { draft.usageData = data; draft.ui.usageDays = target; });
    } catch (failure) {
      if (request === sequence.current) setError(errorInfo(failure).message || '无法读取用量。');
    } finally {
      if (request === sequence.current) setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load(useStore.getState().ui.usageDays);
    return () => { sequence.current++; };
  }, [load]);

  const totals = usage && [
    ['模型请求', formatCount(usage.totalRequests)],
    ['输入 Token', formatCount(usage.totalInputTokens)],
    ['输出 Token', formatCount(usage.totalOutputTokens)],
    ['缓存命中', formatCache(usage.details)],
    ['费用估算', totalCost(usage.details)]
  ];

  return <div className="settings-usage">
    <div className="settings-inline-actions">
      <SegmentedControl id="usage-range" label="用量范围" value={String(requestedDays)} onChange={value => void load(Number(value))}>
        {[7, 14, 30].map(value => <SegmentedControlItem key={value} value={String(value)} label={`${value} 天`} />)}
      </SegmentedControl>
      {loading && <p className="settings-status" role="status">正在读取近 {requestedDays} 天的用量…</p>}
    </div>
    {error && <div className="settings-inline-actions">
      <p className="settings-status" role="alert">读取近 {requestedDays} 天的用量失败。{error}</p>
      <Button label="重新加载用量" onClick={() => void load(requestedDays)} />
    </div>}
    {usage && totals && <>
      <section className="settings-group" aria-labelledby="usage-summary-heading">
        <h3 id="usage-summary-heading">近 {days} 天用量</h3>
        <dl id="usage-summary" className="usage-summary">
          {totals.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}
        </dl>
        {usage.details?.requests > 0 && <p className="settings-status">缓存数据覆盖 {usage.details.observedCacheRequests}/{usage.details.requests} 次请求，可计价 {usage.details.pricedRequests}/{usage.details.requests} 次。缺失数据不按零计入，各币种分别汇总。</p>}
      </section>
      <section className="settings-group" id="usage-daily">
        <h3>每日 Token</h3>
        {usage.daily.length ? <UsageTrend daily={usage.daily} /> : <p className="settings-status">这个窗口内还没有用量记录。</p>}
      </section>
      <section className="settings-group">
        <h3>用量最多的对话</h3>
        <ul id="usage-sessions" className="usage-sessions">
          {(usage.sessions || []).map(session => <li key={session.sessionId}>
            <span className="usage-session-name" title={`${session.title}（${session.sessionId}）`}>{session.title}</span>
            <span className="usage-session-meta">{formatCount((session.inputTokens || 0) + (session.outputTokens || 0))} Token · {session.requests} 请求</span>
          </li>)}
          {!usage.sessions?.length && <li className="panel-empty">还没有可统计的对话。</li>}
        </ul>
      </section>
    </>}
  </div>;
}

function UsageTrend({ daily }: { daily: S.UsageServiceDayTotal[] }) {
  const maxTokens = Math.max(...daily.map(day => (day.inputTokens || 0) + (day.outputTokens || 0)));
  const ticks = new Set([0, Math.floor((daily.length - 1) / 2), daily.length - 1]);
  return <>
    <figure className="usage-trend" aria-hidden="true">
      <div className="usage-axis"><span>{maxTokens > 0 ? formatCount(maxTokens) : ''}</span><span>0</span></div>
      <div className="usage-series" data-dense={daily.length > 8 || undefined}
        style={{ '--usage-day-count': daily.length } as React.CSSProperties}>
        <div className="usage-series-bars">
          {daily.map(day => <div key={day.date} className="usage-series-day"
            title={`${day.date} · 输入 ${formatCount(day.inputTokens)} · 输出 ${formatCount(day.outputTokens)} · ${day.requests} 请求`}>
            <span style={{ height: `${(day.outputTokens || 0) / (maxTokens || 1) * 100}%` }} />
            <span style={{ height: `${(day.inputTokens || 0) / (maxTokens || 1) * 100}%` }} />
          </div>)}
        </div>
        <div className="usage-series-dates">{[...ticks].map(index => <span key={daily[index].date}
          style={{ gridColumn: index + 1 }}>{daily[index].date.slice(5)}</span>)}</div>
      </div>
      <figcaption className="usage-series-legend"><span>输入</span><span>输出</span></figcaption>
    </figure>
    <details className="settings-disclosure">
      <summary>查看每日明细</summary>
      <table className="usage-table">
        <thead><tr><th scope="col">日期</th><th scope="col">请求</th><th scope="col">输入</th><th scope="col">输出</th></tr></thead>
        <tbody>{daily.map(day => <tr key={day.date}>
          <th scope="row">{day.date}</th><td>{day.requests?.toLocaleString('zh-CN') ?? '—'}</td>
          <td>{day.inputTokens?.toLocaleString('zh-CN') ?? '—'}</td><td>{day.outputTokens?.toLocaleString('zh-CN') ?? '—'}</td>
        </tr>)}</tbody>
      </table>
    </details>
  </>;
}

type DiagnosticsSnapshot = {
  maintenance: ApiResults['maintenanceStatus'] | null;
  metrics: ApiResults['metrics'] | null;
  recovery: ApiResults['recovery'] | null;
  diagnostics: ApiResults['diagnostics'] | null;
  validation: ApiResults['validation'] | null;
};

function DiagnosticsSettings() {
  const sessionId = useStore((state) => state.sessionId);
  const [data, setData] = useState<DiagnosticsSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [notice, setNotice] = useState<{ message: string; error?: boolean } | null>(null);
  const [error, setError] = useState('');
  const sequence = useRef(0);

  const load = useCallback(async () => {
    const request = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const [maintenance, metrics, recovery, diagnostics, validation] = await Promise.all([
        api.maintenanceStatus(),
        api.metrics(),
        api.recovery(),
        sessionId ? api.diagnostics(sessionId) : Promise.resolve(null),
        sessionId ? api.validation(sessionId) : Promise.resolve(null)
      ]);
      if (request === sequence.current) setData({ maintenance, metrics, recovery, diagnostics, validation });
    } catch (err) {
      if (request === sequence.current) setError(errorInfo(err).message || '无法读取诊断信息。');
    } finally {
      if (request === sequence.current) setLoading(false);
    }
  }, [sessionId]);

  useEffect(() => {
    setData(null);
    void load();
    return () => { sequence.current++; };
  }, [load]);

  const run = async (kind: 'backup' | 'verify' | 'purge') => {
    if (busy) return;
    setBusy(kind);
    setNotice(null);
    try {
      if (kind === 'backup') {
        const report = await api.backupNow();
        setNotice(report?.ok
          ? { message: `已备份 ${report.files} 个文件到 ${report.directory}。${report.message}` }
          : { message: report?.message || '备份未完成。', error: true });
      } else if (kind === 'verify') {
        const report = await api.verifyBackup();
        setNotice(report?.valid
          ? { message: `${report.message}（${report.checkedFiles} 个文件）` }
          : { message: `${report?.message || '备份校验失败'}：${report?.failures?.join('；') || '没有可校验的备份'}`, error: true });
      } else {
        const report = await api.purgeNow();
        if (!report) throw new Error('服务端未确认清理结果，请刷新诊断信息。');
        setNotice({ message: `已清理 ${report.events ?? 0} 条事件、${report.snapshotRuns ?? 0} 轮快照、${report.traces ?? 0} 条调用记录、${report.backups ?? 0} 份备份和 ${report.validationFiles ?? 0} 个校验文件。` });
      }
      if (useStore.getState().sessionId === sessionId) await load();
    } catch (err) {
      setNotice({ message: '操作失败。' + errorInfo(err).message, error: true });
    } finally {
      setBusy('');
    }
  };

  const maintenance = data?.maintenance;
  const metrics = data?.metrics;
  const recovery = data?.recovery;
  const diagnostics = data?.diagnostics;
  const validation = data?.validation;
  const counters = Object.entries(metrics?.metrics?.counters || {}).sort((a, b) => b[1] - a[1]).slice(0, 8);

  return (
    <div id="settings-diagnostics" className="settings-diagnostics">
      <div className="settings-inline-actions">
        {loading && <p className="settings-status" role="status">正在读取诊断信息…</p>}
        {error && <p className="settings-status" role="alert">读取诊断失败。{error}{data && ' 当前显示上次读取的信息。'}</p>}
        <Button label={error ? '重新加载诊断' : '刷新诊断'} isDisabled={loading || Boolean(busy)} onClick={() => void load()} />
      </div>
      {data && <>
        <section className="settings-group">
          <h3>存储与备份</h3>
          {maintenance ? (
            <>
              <dl className="diag-grid">
                <div><dt>数据目录</dt><dd className="diag-path" title={maintenance.backup?.dataDir}>{maintenance.backup?.dataDir || '—'}</dd></div>
                <div><dt>数据库</dt><dd>{formatBytes(maintenance.backup?.databaseBytes)}</dd></div>
                <div><dt>备份</dt><dd>{maintenance.backup?.backups ?? 0} 份</dd></div>
                <div><dt>上次备份</dt><dd>{maintenance.backup?.lastBackupAt || '从未'}</dd></div>
                <div><dt>保留策略</dt><dd>{maintenance.retentionDays > 0 ? `${maintenance.retentionDays} 天` : '不自动清理'}</dd></div>
                <div><dt>执行隔离</dt><dd>{maintenance.isolated ? '已启用' : (maintenance.degraded ? '降级运行' : '未启用')}</dd></div>
              </dl>
              {!maintenance.storage?.healthy && (
                <p className="chip-hint is-error">
                  存储告警：{(maintenance.storage?.issues || []).map((issue) => issue.reason || issue.area || String(issue)).join('；') || '未知问题'}
                </p>
              )}
              {maintenance.degraded && maintenance.degradedReason && (
                <p className="chip-hint">隔离降级原因：{maintenance.degradedReason}</p>
              )}
              <div className="settings-inline-actions">
                <Button label={busy === 'backup' ? '备份中…' : '立即备份'} isDisabled={Boolean(busy)} onClick={() => void run('backup')} />
                <Button label={busy === 'verify' ? '校验中…' : '校验最近备份'} variant="ghost" isDisabled={Boolean(busy)} onClick={() => void run('verify')} />
                <Button label={busy === 'purge' ? '清理中…' : '清理过期数据'} variant="ghost" className="settings-danger"
                  isDisabled={Boolean(busy)} onClick={() => useStore.getState().askConfirm({
                    title: '清理过期数据',
                    message: '按保留策略删除过期事件、快照、调用记录、备份和校验文件。',
                    confirmText: '确认清理',
                    danger: true,
                    onConfirm: () => run('purge')
                  })} />
              </div>
              {notice && <p className="settings-status" role={notice.error ? 'alert' : 'status'}>{notice.message}</p>}
            </>
          ) : <p className="chip-hint">没有维护信息。</p>}
        </section>

        <section className="settings-group">
          <h3>运行指标</h3>
          {metrics?.metrics ? (
            <>
              <dl className="diag-grid">
                <div><dt>运行轮数</dt><dd>{metrics.metrics.runs}</dd></div>
                <div><dt>平均时长</dt><dd>{formatDuration(metrics.metrics.averageRunMs)}</dd></div>
                <div><dt>统计窗口</dt><dd>{metrics.retentionDays > 0 ? `${metrics.retentionDays} 天` : '全部'}</dd></div>
              </dl>
              {counters.length > 0 && (
                <ul className="diag-counters">
                  {counters.map(([name, value]) => (
                    <li key={name}><span>{name}</span><b>{formatCount(value)}</b></li>
                  ))}
                </ul>
              )}
            </>
          ) : <p className="chip-hint">还没有运行指标。</p>}
        </section>

        <section className="settings-group">
          <h3>恢复快照</h3>
          {recovery ? (
            <p className="chip-hint">
              {recovery.recoveredAt ? `上次启动：${recovery.recoveredAt}` : '本进程还没有执行过启动恢复'} ·
              {' '}接管 {recovery.entries?.length ?? 0} 条 · {recovery.instanceId || '—'}
              {recovery.note ? ` · ${recovery.note}` : ''}
            </p>
          ) : <p className="chip-hint">没有恢复记录。</p>}
        </section>

        <section className="settings-group">
          <h3>当前对话诊断</h3>
          {!sessionId && <p className="chip-hint">打开一个对话后可以看到它的卡点、重试与校验状态。</p>}
          {sessionId && !diagnostics && <p className="settings-status">这段对话还没有诊断记录。</p>}
          {diagnostics && (
            <>
              <dl className="diag-grid">
                <div><dt>状态</dt><dd>{diagnostics.sessionStatus} · {diagnostics.phase || '—'} 第 {diagnostics.step} 步</dd></div>
                <div><dt>上次成功</dt><dd>{diagnostics.lastSuccessfulStep || '—'}</dd></div>
                <div><dt>下一步</dt><dd>{diagnostics.nextAction || '—'}</dd></div>
                <div><dt>模型请求</dt><dd>{diagnostics.modelRequests} 次</dd></div>
                <div><dt>Token</dt><dd>{formatCount(diagnostics.inputTokens)} 输入 / {formatCount(diagnostics.outputTokens)} 输出{diagnostics.usageExact ? '' : '（估算）'}</dd></div>
                <div><dt>最近工具</dt><dd>{diagnostics.lastToolSummary || '—'}</dd></div>
              </dl>
              {diagnostics.retryAttempt != null && (
                <p className="chip-hint">
                  正在重试第 {diagnostics.retryAttempt} 次{diagnostics.retryDelayMs ? `，${Math.round(diagnostics.retryDelayMs / 1000)}s 后` : ''}
                  {diagnostics.retryReason ? `：${diagnostics.retryReason}` : ''}
                </p>
              )}
              {!!(diagnostics.commands || []).length && (
                <ul className="diag-commands">
                  {diagnostics.commands.map((task) => (
                    <li key={task.taskId}>
                      <code>{task.command}</code>
                      <span>{task.status}{task.exitCode != null ? ` · exit ${task.exitCode}` : ''}</span>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
          {validation?.report && (
            <p className={`chip-hint${validation.report.current ? '' : ' is-error'}`}>
              校验证据：{validation.report.current ? '已覆盖当前产物' : '未覆盖当前产物'}
              {validation.report.reason ? `（${validation.report.reason}）` : ''}
            </p>
          )}
        </section>
      </>}
    </div>
  );
}

function formatBytes(value: unknown) {
  if (value == null) return '—';
  const size = Number(value) || 0;
  if (size <= 0) return '0 B';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function formatDuration(ms: unknown) {
  if (ms == null) return '—';
  const value = Number(ms) || 0;
  if (value < 1000) return `${Math.round(value)} ms`;
  if (value < 60000) return `${(value / 1000).toFixed(1)} s`;
  return `${Math.floor(value / 60000)} 分 ${Math.round((value % 60000) / 1000)} 秒`;
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
        <h3>常用快捷键</h3>
        <dl className="shortcut-list">{[['新任务','N'],['命令面板','K'],['展开侧栏','B'],['审阅面板','J'],['设置',',']].map(([label,key]) => <div key={key}><dt>{label}</dt><dd><kbd>{shortcut(key)}</kbd></dd></div>)}</dl>
      </section>
    </div>
  );
}
