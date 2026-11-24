import assert from 'node:assert/strict';
import test from 'node:test';
import { modelProfileChipLabels } from '../src/components/model/model-picker-state.js';

const profile = {
  id: 'local', name: '本机', endpoint: 'http://127.0.0.1:11434/v1', model: 'local-model',
  apiKeySet: false, apiKeyMasked: '', contextTokens: null, maxOutputTokens: null,
  childModel: '', active: false, fallback: true
};

test('备用免密钥预设同时展示两个状态，并在提示中说明端点和状态', () => {
  const labels = modelProfileChipLabels(profile);
  assert.equal(labels.fallback, '备用');
  assert.equal(labels.keyless, '免密钥');
  assert.equal(labels.title, 'local-model · http://127.0.0.1:11434/v1 · 故障转移备用 · 免密钥');
});

test('已配置密钥且非备用的预设不显示误导性状态标签', () => {
  const labels = modelProfileChipLabels({ ...profile, apiKeySet: true, fallback: false });
  assert.equal(labels.fallback, '');
  assert.equal(labels.keyless, '');
  assert.match(labels.title, /已配置 API Key$/);
});

test('已配置脱敏密钥时在提示中展示掩码', () => {
  const labels = modelProfileChipLabels({ ...profile, apiKeySet: true, apiKeyMasked: 'sk-***99', fallback: true });
  assert.equal(labels.fallback, '备用');
  assert.equal(labels.keyless, '');
  assert.equal(labels.title, 'local-model · http://127.0.0.1:11434/v1 · 故障转移备用 · 已配置 API Key（sk-***99）');
});
