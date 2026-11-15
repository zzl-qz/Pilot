import type * as S from '../types/server.js';

/**
 * 插件的四档状态：正常 / 会变 / 会失效 / 无法判断。
 *
 * <p>「会失效」和「会变」必须分开：前者是升级后什么都不做而且不报错，
 * 后者只是下次要重新点一次批准。混成一句话，用户就不会去修真正坏掉的东西。
 */
const LABELS: Record<string, string> = {
  ok: '正常',
  changed: '会重新确认',
  broken: '会失效',
  unknown: '无法判断'
};

const TONES: Record<string, string> = {
  ok: 'ok',
  changed: 'warn',
  broken: 'error',
  unknown: 'muted'
};

export function pluginStatusLabel(status: string | null | undefined) {
  return LABELS[status ?? ''] || '状态未知';
}

export function pluginStatusTone(status: string | null | undefined) {
  return TONES[status ?? ''] || 'muted';
}

/** 空原因时按状态给一句默认解释；有原因就用后端那句，它更具体。 */
export function pluginStatusDetail(item: Partial<S.PluginCompatServiceItem> | null | undefined) {
  const reason = (item?.reason ?? '').trim();
  if (reason) return reason;
  if (item?.status === 'ok') return '与当前核心一致，不需要处理。';
  if (item?.status === 'broken') return '升级后不会再执行，而且不会报错。';
  if (item?.status === 'changed') return '下次使用时会要求重新确认。';
  return '当前无法判断，等它下次运行后再看。';
}

export function pluginKindLabel(kind: string | null | undefined) {
  const known: Record<string, string> = { mcp: 'MCP 服务器', skill: '技能', hook: '钩子' };
  return known[kind ?? ''] || (kind || '未知类型');
}

/** 汇总一行：先给「有几项坏了」，再给全部数字。 */
export function pluginSummary(counts: Partial<S.PluginCompatServiceCounts> | null | undefined) {
  const ok = counts?.ok ?? 0;
  const changed = counts?.changed ?? 0;
  const broken = counts?.broken ?? 0;
  const unknown = counts?.unknown ?? 0;
  const total = ok + changed + broken + unknown;
  if (!total) return '还没有接入任何插件。';
  const head = broken ? `${broken} 项会失效` : changed ? `${changed} 项需要重新确认` : '全部正常';
  return `${head} · 共 ${total} 项（正常 ${ok} · 会变 ${changed} · 会失效 ${broken} · 无法判断 ${unknown}）`;
}

/** 只显示需要处理的那部分；全好时返回空数组，页面就不用摆一排绿标。 */
export function pluginAttentionItems(items: readonly S.PluginCompatServiceItem[] | null | undefined) {
  return (items || []).filter((item) => item.status !== 'ok');
}