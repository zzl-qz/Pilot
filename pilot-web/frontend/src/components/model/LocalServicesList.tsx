import { useState } from 'react';
import { Button } from '@astryxdesign/core/Button';
import type { LocalModelService } from '../../types/api.js';
import { api } from '../../api.js';
import { errorInfo } from '../../lib/errors.js';

export function buildLocalProfileName(serviceName: string, model: string): string {
  const base = `${serviceName.trim()} · ${model.trim()}`;
  return base.length > 64 ? base.slice(0, 64).trim() : base;
}

export function LocalServicesList({ services, disabled, onFill, onProfilesChanged, onHint }: {
  services: LocalModelService[] | null;
  disabled: boolean;
  onFill: (service: LocalModelService, model: string) => void;
  onProfilesChanged: () => void;
  onHint: (text: string, error?: boolean) => void;
}) {
  const [busyKey, setBusyKey] = useState('');
  if (!services || !services.length) return null;

  async function saveLocalProfile(service: LocalModelService, model: string, asFallback: boolean) {
    const actionKey = `${service.endpoint}:${model}:${asFallback ? 'fallback' : 'save'}`;
    if (disabled || busyKey) return;
    setBusyKey(actionKey);
    try {
      const profileName = buildLocalProfileName(service.name, model);
      let targetId = '';
      if (asFallback) {
        const existing = await api.modelProfiles();
        const matched = existing?.profiles?.find(item => item.endpoint === service.endpoint && item.model === model);
        if (matched) targetId = matched.id;
      }
      if (!targetId) {
        const updated = await api.createModelProfile({
          name: profileName,
          endpoint: service.endpoint,
          model,
          inheritCurrentKey: false
        });
        const created = updated?.profiles?.find(item => item.name === profileName)
          || updated?.profiles?.find(item => item.endpoint === service.endpoint && item.model === model);
        targetId = created?.id || '';
      }
      if (asFallback) {
        if (!targetId) throw new Error('未能定位新建预设，请重试。');
        await api.setFallbackModelProfile(targetId);
        onHint(`已将 ${service.name} 的 ${model} 设为免密钥故障转移备用。`);
      } else {
        onHint(`已保存为免密钥预设「${profileName}」。`);
      }
      onProfilesChanged();
    } catch (error) {
      onHint(`${asFallback ? '设置备用' : '保存预设'}失败：${errorInfo(error).message || '请稍后重试'}`, true);
    } finally {
      setBusyKey('');
    }
  }

  return <div className="local-scan-list" aria-label="本机在线模型服务">
    {services.map(service => <section className="local-scan-card" key={service.endpoint} aria-label={`${service.name}本机服务`}>
      <div className="local-scan-head">
        <strong>{service.name}</strong>
        <code>{service.endpoint}</code>
        <span className="local-scan-count">{service.models.length} 个模型</span>
      </div>
      {service.models.length ? (
        <ul className="local-scan-models" aria-label={`${service.name}已安装模型`}>
          {service.models.map(model => {
            const busy = disabled || Boolean(busyKey);
            return <li key={model} className="local-scan-model-row">
              <span className="local-scan-model-name" title={model}>{model}</span>
              <div className="local-scan-actions">
                <Button size="sm" variant="ghost" label="填入表单" isDisabled={busy}
                  onClick={() => onFill(service, model)} />
                <Button size="sm" variant="ghost" label="存为预设" isDisabled={busy}
                  onClick={() => void saveLocalProfile(service, model, false)} />
                <Button size="sm" variant="ghost" label="设为备用" isDisabled={busy}
                  onClick={() => void saveLocalProfile(service, model, true)} />
              </div>
            </li>;
          })}
        </ul>
      ) : (
        <div className="local-scan-empty">
          <span>该服务在线，但尚未加载模型。</span>
          <Button size="sm" variant="ghost" label="填入端点" isDisabled={disabled || Boolean(busyKey)}
            onClick={() => onFill(service, '')} />
        </div>
      )}
    </section>)}
  </div>;
}
