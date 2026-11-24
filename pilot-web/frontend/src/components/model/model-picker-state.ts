import type { ModelProfileView } from '../../types/api.js';

function profileKeyLabel(profile: ModelProfileView) {
  if (!profile.apiKeySet) return '免密钥';
  const masked = profile.apiKeyMasked?.trim();
  return masked ? `已配置 API Key（${masked}）` : '已配置 API Key';
}

export function modelProfileChipLabels(profile: ModelProfileView) {
  const keyLabel = profileKeyLabel(profile);
  return {
    fallback: profile.fallback ? '备用' : '',
    keyless: profile.apiKeySet ? '' : '免密钥',
    title: `${profile.model} · ${profile.endpoint}${profile.fallback ? ' · 故障转移备用' : ''} · ${keyLabel}`
  };
}
