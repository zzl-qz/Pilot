import { useEffect, useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { TextInput } from '@astryxdesign/core/TextInput';
import type { ModelProfileView, ModelProfilesView } from '../../types/api.js';
import { api } from '../../api.js';
import { errorInfo } from '../../lib/errors.js';
import { useStore } from '../../store.js';

interface CurrentDraft {
  model: string;
  endpoint: string;
  apiKey: string;
  contextTokens: string;
  maxOutputTokens: string;
  childModel: string;
}

interface EditDraft {
  id: string;
  name: string;
  endpoint: string;
  model: string;
  apiKey: string;
  clearApiKey: boolean;
  contextTokens: string;
  maxOutputTokens: string;
  childModel: string;
}

function parsePositiveInt(value: string): number | null {
  const n = Number(value.trim());
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : null;
}

export function ModelProfiles({ draft, onActivated, refreshKey = 0 }: {
  draft: CurrentDraft;
  onActivated: (profile: ModelProfileView) => void;
  refreshKey?: number;
}) {
  const [data, setData] = useState<ModelProfilesView | null>(null);
  const [profileName, setProfileName] = useState('');
  const [editing, setEditing] = useState<EditDraft | null>(null);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<{ message: string; error?: boolean } | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    api.modelProfiles(controller.signal).then(res => {
      if (!controller.signal.aborted) setData(res);
    }).catch(error => {
      if (!controller.signal.aborted) setStatus({ message: errorInfo(error).message || '读取模型预设失败', error: true });
    });
    return () => controller.abort();
  }, [refreshKey]);

  async function saveCurrentAsProfile() {
    const trimmedName = profileName.trim();
    if (!trimmedName || !draft.model.trim() || !draft.endpoint.trim() || busy) return;
    setBusy(true);
    setStatus(null);
    try {
      const updated = await api.createModelProfile({
        name: trimmedName,
        endpoint: draft.endpoint.trim(),
        model: draft.model.trim(),
        apiKey: draft.apiKey.trim() || undefined,
        inheritCurrentKey: !draft.apiKey.trim(),
        contextTokens: parsePositiveInt(draft.contextTokens),
        maxOutputTokens: parsePositiveInt(draft.maxOutputTokens),
        childModel: draft.childModel.trim()
      });
      setData(updated);
      setProfileName('');
      setStatus({ message: `已保存预设「${trimmedName}」` });
    } catch (error) {
      setStatus({ message: errorInfo(error).message || '保存预设失败', error: true });
    } finally {
      setBusy(false);
    }
  }

  function startEdit(profile: ModelProfileView) {
    setEditing({
      id: profile.id,
      name: profile.name,
      endpoint: profile.endpoint,
      model: profile.model,
      apiKey: '',
      clearApiKey: false,
      contextTokens: profile.contextTokens ? String(profile.contextTokens) : '',
      maxOutputTokens: profile.maxOutputTokens ? String(profile.maxOutputTokens) : '',
      childModel: profile.childModel || ''
    });
    setStatus(null);
  }

  async function saveEdit() {
    if (!editing || !editing.name.trim() || !editing.endpoint.trim() || !editing.model.trim() || busy) return;
    setBusy(true);
    setStatus(null);
    try {
      const updated = await api.updateModelProfile(editing.id, {
        name: editing.name.trim(),
        endpoint: editing.endpoint.trim(),
        model: editing.model.trim(),
        apiKey: editing.clearApiKey ? undefined : (editing.apiKey.trim() || undefined),
        clearApiKey: editing.clearApiKey || undefined,
        contextTokens: editing.contextTokens.trim() ? parsePositiveInt(editing.contextTokens) : 0,
        maxOutputTokens: editing.maxOutputTokens.trim() ? parsePositiveInt(editing.maxOutputTokens) : 0,
        childModel: editing.childModel.trim()
      });
      setData(updated);
      const savedName = editing.name.trim();
      setEditing(null);
      setStatus({ message: `已更新预设「${savedName}」` });
    } catch (error) {
      setStatus({ message: errorInfo(error).message || '更新预设失败', error: true });
    } finally {
      setBusy(false);
    }
  }

  async function activateProfile(profile: ModelProfileView) {
    if (busy) return;
    setBusy(true);
    setStatus(null);
    try {
      const updated = await api.activateModelProfile(profile.id);
      setData(updated);
      await useStore.getState().loadSettings();
      onActivated(profile);
      setStatus({ message: `已切换到预设「${profile.name}」` });
      useStore.getState().showNotice(`已切换模型预设：${profile.name}（${profile.model}）`);
    } catch (error) {
      setStatus({ message: errorInfo(error).message || '切换预设失败', error: true });
    } finally {
      setBusy(false);
    }
  }

  async function toggleFallback(profile: ModelProfileView) {
    if (busy) return;
    setBusy(true);
    setStatus(null);
    const clearing = Boolean(profile.fallback);
    try {
      const updated = await api.setFallbackModelProfile(clearing ? '' : profile.id);
      setData(updated);
      setStatus({
        message: clearing
          ? `已取消「${profile.name}」的故障转移备用身份`
          : `已将「${profile.name}」设为故障转移备用：主模型限流、额度耗尽或断连时本次运行将自动切换`
      });
    } catch (error) {
      setStatus({ message: errorInfo(error).message || '设置备用预设失败', error: true });
    } finally {
      setBusy(false);
    }
  }

  async function deleteProfile(profile: ModelProfileView) {
    if (busy) return;
    setBusy(true);
    setStatus(null);
    try {
      const updated = await api.deleteModelProfile(profile.id);
      setData(updated);
      if (editing?.id === profile.id) setEditing(null);
      setStatus({ message: `已删除预设「${profile.name}」` });
    } catch (error) {
      setStatus({ message: errorInfo(error).message || '删除预设失败', error: true });
    } finally {
      setBusy(false);
    }
  }

  const profiles = data?.profiles || [];
  const canSave = Boolean(profileName.trim() && draft.model.trim() && draft.endpoint.trim() && !busy);
  const canSaveEdit = Boolean(editing && editing.name.trim() && editing.model.trim() && editing.endpoint.trim() && !busy);

  return (
    <section className="settings-group" id="settings-model-profiles">
      <div className="settings-group-heading">
        <h3>模型预设</h3>
        <p>将常用的本地 Ollama、免费兼容接口或主力云端配置存为预设，可随时一键切换、编辑或指定为自动故障转移备用。</p>
      </div>
      <div className="settings-source-row model-profile-create">
        <TextInput
          id="settings-model-profile-name"
          label="将当前表单另存为预设"
          size="lg"
          width="100%"
          placeholder="例如：本地 Ollama / 免费路由 / DeepSeek 主力"
          value={profileName}
          isDisabled={busy}
          onChange={setProfileName}
        />
        <Button
          id="settings-model-profile-save"
          label={busy ? '保存中…' : '存为预设'}
          isDisabled={!canSave}
          onClick={() => void saveCurrentAsProfile()}
        />
      </div>
      {profiles.length > 0 ? (
        <ul className="model-profile-list" aria-label="已保存的模型预设">
          {profiles.map(profile => {
            const isEditing = editing?.id === profile.id;
            return (
              <li key={profile.id} className={`model-profile-item${profile.active ? ' is-active' : ''}${isEditing ? ' is-editing' : ''}`}>
                {isEditing && editing ? (
                  <form className="settings-form model-profile-edit-form" onSubmit={event => {
                    event.preventDefault();
                    if (canSaveEdit) void saveEdit();
                  }} onKeyDown={event => {
                    if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault();
                  }}>
                    <div className="settings-field-grid">
                      <TextInput label="预设名称" value={editing.name} width="100%" isDisabled={busy}
                        onChange={name => setEditing(cur => cur ? { ...cur, name } : null)} />
                      <TextInput label="模型名称" value={editing.model} width="100%" isDisabled={busy} hasSpellCheck={false}
                        onChange={model => setEditing(cur => cur ? { ...cur, model } : null)} />
                      <TextInput label="API 端点" value={editing.endpoint} width="100%" isDisabled={busy} inputMode="url" hasSpellCheck={false}
                        onChange={endpoint => setEditing(cur => cur ? { ...cur, endpoint } : null)} />
                      <TextInput label="子代理模型（可选）" value={editing.childModel} width="100%" isDisabled={busy} hasSpellCheck={false}
                        onChange={childModel => setEditing(cur => cur ? { ...cur, childModel } : null)} />
                      <TextInput label="上下文窗口（可选）" value={editing.contextTokens} width="100%" isDisabled={busy} inputMode="numeric"
                        placeholder="留空用默认值" onChange={contextTokens => setEditing(cur => cur ? { ...cur, contextTokens } : null)} />
                      <TextInput label="最大输出（可选）" value={editing.maxOutputTokens} width="100%" isDisabled={busy} inputMode="numeric"
                        placeholder="留空用默认值" onChange={maxOutputTokens => setEditing(cur => cur ? { ...cur, maxOutputTokens } : null)} />
                    </div>
                    <TextInput label="API Key（留空保留原值）" type="password" width="100%"
                      value={editing.apiKey} isDisabled={busy || editing.clearApiKey} autoComplete="new-password"
                      placeholder={editing.clearApiKey ? '保存时将清除此预设密钥' : (profile.apiKeySet ? `已保存 ${profile.apiKeyMasked}（留空保留）` : '免密钥可留空')}
                      onChange={apiKey => setEditing(cur => cur ? { ...cur, apiKey } : null)} />
                    <div className="settings-inline-actions">
                      {profile.apiKeySet && (
                        <Button type="button" size="sm" variant="ghost"
                          label={editing.clearApiKey ? '保留原密钥' : '设为免密钥'}
                          isDisabled={busy}
                          onClick={() => setEditing(cur => cur ? { ...cur, clearApiKey: !cur.clearApiKey, apiKey: '' } : null)} />
                      )}
                      <Button type="submit" size="sm" variant="primary" label={busy ? '保存中…' : '保存修改'} isDisabled={!canSaveEdit} />
                      <Button type="button" size="sm" variant="ghost" label="取消" isDisabled={busy} onClick={() => setEditing(null)} />
                    </div>
                  </form>
                ) : (
                  <>
                    <div className="model-profile-meta">
                      <div className="model-profile-title">
                        <strong>{profile.name}</strong>
                        {profile.active && <span className="model-profile-badge">当前</span>}
                        {profile.fallback && <span className="model-profile-badge is-fallback">备用</span>}
                        <span className="model-profile-key">{profile.apiKeySet ? `Key ${profile.apiKeyMasked}` : '免密钥'}</span>
                      </div>
                      <p className="model-profile-detail">{profile.model} · {profile.endpoint}</p>
                    </div>
                    <div className="model-profile-actions">
                      <Button
                        label={profile.active ? '已启用' : '切换'}
                        variant={profile.active ? 'ghost' : 'primary'}
                        isDisabled={busy || profile.active}
                        onClick={() => void activateProfile(profile)}
                      />
                      <Button
                        label={profile.fallback ? '取消备用' : '设为备用'}
                        variant="ghost"
                        isDisabled={busy}
                        onClick={() => void toggleFallback(profile)}
                      />
                      <Button
                        label="编辑"
                        variant="ghost"
                        isDisabled={busy}
                        onClick={() => startEdit(profile)}
                      />
                      <Button
                        label="删除"
                        variant="ghost"
                        className="settings-danger"
                        isDisabled={busy}
                        onClick={() => useStore.getState().askConfirm({
                          title: '删除模型预设',
                          message: `确定删除预设「${profile.name}」吗？不会影响当前已生效的模型配置。`,
                          confirmText: '删除',
                          danger: true,
                          onConfirm: () => void deleteProfile(profile)
                        })}
                      />
                    </div>
                  </>
                )}
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="settings-status">尚未保存预设；在上方填好模型与端点后输入名称即可保存。</p>
      )}
      {status && <p className="settings-status" role={status.error ? 'alert' : 'status'}>{status.message}</p>}
    </section>
  );
}
