import type { SettingsControllerSettingsResponse } from '../types/server.js';
/**
 * 模型设置的前端纯逻辑：请求体拼装、保存结果文案、错误归类。
 *
 * <p>与 DOM 解耦，便于 node:test 覆盖「留空保持 / 明确清除 / 401 提示」等交互语义。
 */

/**
 * 拼装 PUT /api/settings/model 的请求体。
 *
 * <p>勾选「清除已保存密钥」时必须把 apiKey 置空：否则后端会先看到显式 key
 * 再看到 clear 标记，语义容易含糊。clearApiKey 单独为 true 才是唯一信号。
 */
export function buildModelUpdate({ model, endpoint, apiKey, clearKey }: { model?: string; endpoint?: string; apiKey?: string; clearKey?: boolean } = {}) {
  const clearApiKey = Boolean(clearKey);
  return {
    model: String(model ?? '').trim(),
    endpoint: String(endpoint ?? '').trim(),
    apiKey: clearApiKey ? '' : String(apiKey ?? '').trim(),
    clearApiKey
  };
}

/** 保存成功后的提示文案；clearKey 优先于「密钥已更新」。 */
export function describeModelSaved(data: Pick<SettingsControllerSettingsResponse, 'model'> | null | undefined, { clearKey = false, keyChanged = false }: { clearKey?: boolean; keyChanged?: boolean } = {}) {
  const model = (data && data.model) || '未设置';
  if (clearKey) {
    return `已清除保存的 API Key：模型「${model}」保留，填好新密钥前模型调用会失败。`;
  }
  if (keyChanged) {
    return `已保存：模型「${model}」，API Key 已更新，下一次请求即生效。`;
  }
  return `已保存：模型「${model}」，下一次请求即生效。`;
}

/**
 * 拼装 POST /api/settings/model/models 的请求体。
 *
 * <p>两个字段留空都表示「用已保存的值」，由服务端回退；密钥只在本次探测中使用，
 * 不落盘。
 */
export function buildModelProbe({ endpoint, apiKey }: { endpoint?: string; apiKey?: string } = {}) {
  return {
    endpoint: String(endpoint ?? '').trim(),
    apiKey: String(apiKey ?? '').trim()
  };
}

/** 模型 ID 区分大小写；忽略空值与重复值，保证数量和可选行一致。 */
export function normalizeModelNames(models: unknown): string[] {
  return [...new Set((Array.isArray(models) ? models : [])
    .filter(model => typeof model === 'string').map(model => model.trim()).filter(Boolean))];
}

/** 「获取可用模型」成功后的状态行文案：有结果引导点击选择，无结果提示手填。 */
export function describeModelsFetched(data: { models?: unknown } | null | undefined, { endpoint = '' }: { endpoint?: string } = {}) {
  const models = normalizeModelNames(data?.models);
  const from = endpoint ? `，来自 ${endpoint}` : '';
  if (!models.length) {
    return `端点已连通，但没有返回任何模型${from}：请手动填写模型名。`;
  }
  return `已获取 ${models.length} 个可用模型${from}，点击列表中的模型即可选择。`;
}

/**
 * 从错误响应里取最有用的说明文字。
 *
 * <p>兼容 Spring 的 {@code message}、RFC 7807 的 {@code detail}，以及框架默认的 {@code error}。
 */
export function errorText(response: { status: number } | null | undefined, body: unknown) {
  if (body && typeof body === 'object') {
    for (const key of ['message', 'detail', 'error']) {
      const value = (body as Record<string, unknown>)[key];
      if (typeof value === 'string' && value.trim()) {
        return value.trim();
      }
    }
  }
  const status = response && response.status;
  if (status === 404) {
    return '没有找到对应的资源';
  }
  return 'HTTP ' + (status ?? '?');
}

/**
 * 把「模型调用失败」的原始报错翻译成可行动的提示。
 *
 * @returns {{kind: string, hint: string, action: string|null}}
 */
export function classifyModelError(message: unknown) {
  const text = String(message ?? '');
  if (/\b(401|403)\b/.test(text) || /unauthori[sz]ed|invalid[^;]*key|api[ _-]?key/i.test(text)) {
    return {
      kind: 'auth',
      hint: 'API Key 未配置或已失效：打开下方「模型」填入密钥后重试。',
      action: 'model-settings'
    };
  }
  if (/\b404\b/.test(text)) {
    return {
      kind: 'endpoint',
      hint: '模型接口返回 404：多半是端点路径或模型名不对，检查「端点」与「模型」。',
      action: 'model-settings'
    };
  }
  if (/\b429\b/.test(text)) {
    return {
      kind: 'quota',
      hint: '模型接口限流或额度不足（HTTP 429）：稍后重试，或更换密钥/模型。',
      action: null
    };
  }
  if (/timeout|timed out|超时/i.test(text)) {
    return {
      kind: 'timeout',
      hint: '模型请求超时：可以点「继续执行」重试，或换更快的模型。',
      action: null
    };
  }
  return { kind: 'unknown', hint: '', action: null };
}
