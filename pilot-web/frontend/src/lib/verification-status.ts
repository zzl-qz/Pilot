import type * as S from '../types/server.js';

/**
 * 成果的验证结论：不是「有没有跑过测试」，而是「那条证据现在还作不作数」。
 *
 * <p>三种状态必须能被肉眼区分，否则「证据已过期」会被读成「没验证过」，
 * 而前者恰恰更危险——用户以为自己有证据。
 */
const LABELS: Record<string, string> = {
  verified: '已验证',
  stale: '证据已过期',
  unverified: '未验证'
};

const TONES: Record<string, string> = {
  verified: 'ok',
  stale: 'stale',
  unverified: 'muted'
};

export function verificationStatusLabel(status: string | null | undefined) {
  return LABELS[status ?? ''] || '验证状态未知';
}

/** ok / stale / muted，供样式选择；未知状态按 muted 处理，不冒充通过。 */
export function verificationStatusTone(status: string | null | undefined) {
  return TONES[status ?? ''] || 'muted';
}

/** 一句话说明当前结论的依据，空原因时给出对应的默认解释。 */
export function verificationStatusDetail(verification: Partial<S.WorkProductServiceValidationSnapshot> | null | undefined) {
  const status = verification?.status ?? '';
  const reason = (verification?.reason ?? '').trim();
  if (reason) return reason;
  if (status === 'verified') return '最近一次校验证据仍覆盖当前文件内容。';
  if (status === 'stale') return '文件在这次校验之后又被改动过，旧证据不能再支撑完成声明。';
  return '还没有可用的校验证据，这份成果尚不能视为已验证。';
}

/** 指纹太长，界面上只给前 8 位；三端对比时看的就是这几位。 */
export function shortFingerprint(value: string | null | undefined) {
  const text = (value ?? '').trim();
  return text ? text.slice(0, 8) : '';
}

/** 是否需要把结论显示成警示态（过期或未验证但用户可能误以为已完成）。 */
export function verificationNeedsAttention(status: string | null | undefined) {
  return status === 'stale' || status === 'unverified';
}

/**
 * 后端原因码 → 展示状态。
 *
 * <p>界面靠代号判断，不去匹配中文句子：文案一改，匹配就会静默失效。
 * 兼容只有 covered 布尔值的旧接口：true 视为已验证，false 视为未验证。
 */
const CODE_TO_STATUS: Record<string, string> = {
  ok: 'verified',
  stale_candidate: 'stale',
  no_evidence: 'unverified',
  no_changes: 'unverified',
  failed: 'unverified',
  not_covered: 'unverified',
  no_workspace: 'unverified',
  storage_degraded: 'unverified'
};

export function statusFromCode(code: string | null | undefined, covered?: boolean | null) {
  const mapped = CODE_TO_STATUS[code ?? ''];
  if (mapped) return mapped;
  if (covered === true) return 'verified';
  return 'unverified';
}

/**
 * 「重新验证」发出的话。
 *
 * <p>不伪造一个「重跑上次命令」的假动作：历史证据里只有结果摘要，没有可重放的命令参数，
 * 直接重放等于凭空造证据。这里改为让会话真正再验一次，产出新的证据。
 */
export const REVERIFY_PROMPT = '重新运行验证：针对当前文件内容执行相关的测试或构建，说明实际执行的命令、退出码和覆盖范围；不要把之前的结论当作这次的结果。';

export function reverifyPrompt(reason: string | null | undefined) {
  const detail = (reason ?? '').trim();
  return detail ? `${REVERIFY_PROMPT}（上次结论失效原因：${detail}）` : REVERIFY_PROMPT;
}