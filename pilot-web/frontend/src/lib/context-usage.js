/**
 * 上下文占用的展示口径。
 *
 * <p>后端的 {@code ContextStatus} 给的是保守估算：{@code usedTokens / limitTokens}、
 * {@code percent}，以及按角色的记忆构成 {@code segments[{role,messages,chars}]}。
 * 自动收缩的四个水位（55% 换占位符、70% 截断、80% 折叠摘要、97% 硬裁剪）与
 * {@code ContextBudget} 一一对应，界面照着说人话就行。
 */

/** 与 ContextBudget 的 PRE_COMPACT_RATIO 对齐：到这里会折叠成交接摘要。 */
export const COMPACT_RATIO = 0.8;
/** 与 ContextBudget 的 DROP_RATIO 对齐：到这里开始硬裁剪最旧的组。 */
export const DROP_RATIO = 0.97;

const ROLE_LABELS = {
  system: '系统提示',
  user: '你的输入',
  assistant: '助手回复',
  tool: '工具输出'
};

/** 0-100 的整数占用比；没有预算时返回 0。 */
export function usagePercent(status) {
  const limit = Number(status?.limitTokens);
  if (Number.isFinite(Number(status?.percent))) {
    return Math.max(0, Math.min(100, Math.round(Number(status.percent))));
  }
  if (!Number.isFinite(limit) || limit <= 0) {
    return 0;
  }
  const used = Number(status?.usedTokens) || 0;
  return Math.max(0, Math.min(100, Math.round((used / limit) * 100)));
}

/** 占用档位：normal 正常、warn 已进入自动折叠区、danger 快要硬裁剪。 */
export function usageLevel(percent) {
  const value = Number(percent) || 0;
  if (value >= DROP_RATIO * 100) {
    return 'danger';
  }
  return value >= COMPACT_RATIO * 100 ? 'warn' : 'normal';
}

/** 1.2k / 128k / 1.1M；未知返回 —。 */
export function formatTokens(value) {
  // Number(null) === 0：先挡掉空值，否则「没有数据」会被显示成 0
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  const tokens = Number(value);
  if (!Number.isFinite(tokens) || tokens <= 0) {
    return tokens === 0 ? '0' : '—';
  }
  if (tokens >= 1_000_000) {
    return `${trim(tokens / 1_000_000)}M`;
  }
  if (tokens >= 1000) {
    return `${trim(tokens / 1000)}k`;
  }
  return String(Math.round(tokens));
}

function trim(size) {
  return size >= 100 ? String(Math.round(size)) : size.toFixed(1).replace(/\.0$/, '');
}

/** 剩余可用 token（不小于 0）；预算未知返回 null。 */
export function remainingTokens(status) {
  const limit = Number(status?.limitTokens);
  if (!Number.isFinite(limit) || limit <= 0) {
    return null;
  }
  return Math.max(0, limit - (Number(status?.usedTokens) || 0));
}

/** 记忆构成：按字符数降序、丢掉空项，字符占比用于画分段条。 */
export function usageSegments(status) {
  const segments = Array.isArray(status?.segments) ? status.segments : [];
  const totalChars = segments.reduce((sum, item) => sum + Math.max(0, Number(item?.chars) || 0), 0);
  return segments
    .map((item) => {
      const role = String(item?.role || '').toLowerCase();
      const chars = Math.max(0, Number(item?.chars) || 0);
      return {
        role,
        label: ROLE_LABELS[role] || role || '其他',
        messages: Math.max(0, Number(item?.messages) || 0),
        chars,
        share: totalChars > 0 ? chars / totalChars : 0
      };
    })
    .filter((item) => item.chars > 0)
    .sort((left, right) => right.chars - left.chars);
}

/** 一句话说清现在处在哪一档，界面上直接显示，不用记水位。 */
export function usageNote(status, percent) {
  const limit = Number(status?.limitTokens);
  if (!Number.isFinite(limit) || limit <= 0) {
    return '还没有可统计的模型记忆：发一条消息后这里会显示占用。';
  }
  const level = usageLevel(percent);
  if (level === 'danger') {
    return '快要超出窗口：最旧的对话正在被硬裁剪，建议先压缩或开新会话。';
  }
  if (level === 'warn') {
    return '已进入自动折叠区：下一步会把手头的历史折叠成交接摘要。';
  }
  // 说的是「到折叠水位还有多少」，不是「总量还剩多少」，两个数别混着显示
  const compactAt = Math.floor(limit * COMPACT_RATIO);
  const toCompact = Math.max(0, compactAt - (Number(status?.usedTokens) || 0));
  return `离自动折叠（${formatTokens(compactAt)}）还有 ${formatTokens(toCompact)} tokens。`;
}
