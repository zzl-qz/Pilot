import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildModelUpdate,
  classifyModelError,
  describeModelSaved,
  errorText
} from '../../main/resources/static/lib/model-settings.js';

test('默认不清除密钥，字段两端空格被去掉', () => {
  assert.deepEqual(
    buildModelUpdate({ model: ' gpt-4o ', endpoint: ' https://api.example/v1 ', apiKey: ' sk-1 ' }),
    { model: 'gpt-4o', endpoint: 'https://api.example/v1', apiKey: 'sk-1', clearApiKey: false }
  );
});

test('勾选清除时 apiKey 一律置空，避免「填了又清」的歧义', () => {
  assert.deepEqual(
    buildModelUpdate({ model: 'gpt-4o', endpoint: 'https://api.example/v1', apiKey: 'sk-new', clearKey: true }),
    { model: 'gpt-4o', endpoint: 'https://api.example/v1', apiKey: '', clearApiKey: true }
  );
});

test('保存成功的文案区分「清除」「换密钥」「只换模型」', () => {
  const data = { model: 'gpt-4o' };

  assert.match(describeModelSaved(data, { clearKey: true }), /已清除保存的 API Key/);
  assert.match(describeModelSaved(data, { keyChanged: true }), /API Key 已更新/);
  assert.match(describeModelSaved(data, {}), /已保存：模型「gpt-4o」/);
  assert.match(describeModelSaved({}, {}), /未设置/);
});

test('错误文案优先 message，其次 detail / error，最后退回状态码', () => {
  assert.equal(errorText({ status: 400 }, { message: '模型端点必须是 http/https 地址' }),
    '模型端点必须是 http/https 地址');
  assert.equal(errorText({ status: 400 }, { detail: '端点不合法' }), '端点不合法');
  assert.equal(errorText({ status: 502 }, { error: 'Bad Gateway' }), 'Bad Gateway');
  assert.equal(errorText({ status: 404 }, null), '没有找到对应的资源');
  assert.equal(errorText({ status: 500 }, 'plain text'), 'HTTP 500');
  assert.equal(errorText({}, { message: '   ' }), 'HTTP ?');
});

test('401/403 提示去填密钥，并给出可点击的动作', () => {
  const auth = classifyModelError('模型调用失败：模型接口返回 HTTP 401（120 ms, model=gpt）：{"error":"invalid api key"}');
  assert.equal(auth.kind, 'auth');
  assert.equal(auth.action, 'model-settings');
  assert.match(auth.hint, /API Key/);

  assert.equal(classifyModelError('HTTP 403 forbidden').kind, 'auth');
  assert.equal(classifyModelError('Unauthorized').kind, 'auth');
});

test('404 指向端点/模型名，429 与超时只提示重试', () => {
  const endpoint = classifyModelError('模型接口返回 HTTP 404（12 ms, model=gpt）：not found');
  assert.equal(endpoint.kind, 'endpoint');
  assert.equal(endpoint.action, 'model-settings');

  const quota = classifyModelError('模型接口返回 HTTP 429：rate limited');
  assert.equal(quota.kind, 'quota');
  assert.equal(quota.action, null);

  assert.equal(classifyModelError('模型请求超时（90000 ms）').kind, 'timeout');
  assert.equal(classifyModelError('请求被中断').kind, 'unknown');
  assert.equal(classifyModelError(null).hint, '');
});
