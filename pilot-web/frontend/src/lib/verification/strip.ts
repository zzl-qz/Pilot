import type * as S from '../../types/server.js';

/**
 * 输入框上方那条验证结论的展示数据。
 *
 * <p>这条带子是产品主张唯一常驻可见的地方：以前「完成是否可信」只藏在折叠的任务契约里，
 * 用户在主界面看不到，也就感受不到这个产品在做什么。
 */
export type StripTone = 'ok' | 'stale' | 'muted' | 'error';

export interface StripView {
  /** 是否有必要显示；没有会话或还没跑过任何一轮时不显示。 */
  visible: boolean;
  label: string;
  tone: StripTone;
  /** 一句话说明：为什么是这个结论。 */
  detail: string;
  /** 是否需要提供「重新验证」。 */
  needsRerun: boolean;
  /** 可展开的具体依据；为空表示没有可看的细节。 */
  facts: string[];
  /** 未通过的检查命令原文：有它就提供「重跑失败的检查」，不靠模型自己猜跑什么。 */
  failedCommands?: string[];
}

const HIDDEN: StripView = { visible: false, label: '', tone: 'muted', detail: '', needsRerun: false, facts: [] };

/**
 * 把接口返回的验证视图压成一条带子。
 *
 * @param view    `/api/sessions/{id}/validation` 的返回；未加载时为 null
 * @param hasRun  当前会话是否已经跑过至少一轮（没跑过就别摆一条「未验证」吓人）
 * @param running 这一轮还在跑。跑完之前没有验证结论，不能把「还没验」说成「未验证」。
 */
export function stripView(view: S.DiagnosticsControllerValidationView | null | undefined, hasRun: boolean, running = false): StripView {
  if (running || !hasRun) return HIDDEN;
  if (!view?.report) {
    // 还没拿到结论时不要显示「未验证」：那会把「正在加载」说成「没有证据」。
    return HIDDEN;
  }
  const report = view.report;
  const latest = report.latest ?? null;
  // 旧账本偶尔把 skipped 记成 failed 文案；以证据状态为准，别让用户看见英文码。
  const rawCode = report.code ?? '';
  const code = latest?.status === 'skipped' && rawCode === 'failed' ? 'skipped' : rawCode;
  const reason = cleanReason(report.reason ?? '');

  if (report.current) {
    return {
      visible: true,
      label: '已验证',
      tone: 'ok',
      detail: '最近的校验证据仍覆盖当前文件内容。',
      needsRerun: false,
      facts: factsOf(latest)
    };
  }

  if (code === 'stale_candidate') {
    return {
      visible: true,
      label: '证据已过期',
      tone: 'stale',
      detail: reason || '文件在这次校验之后又被改动过，旧证据不能再支撑完成声明。',
      needsRerun: true,
      facts: factsOf(latest)
    };
  }

  if (code === 'skipped') {
    return {
      visible: true,
      label: '还没校验完',
      tone: 'muted',
      detail: reason || '这一轮没有跑完校验，不能当成通过。',
      needsRerun: true,
      facts: factsOf(latest)
    };
  }

  if (code === 'failed') {
    return {
      visible: true,
      label: '校验未通过',
      tone: 'error',
      detail: reason || '最近一次校验没有通过，不能声称已完成。',
      needsRerun: true,
      facts: factsOf(latest),
      failedCommands: failedCommandsOf(latest)
    };
  }

  if (code === 'storage_degraded') {
    return {
      visible: true,
      label: '证据不可核对',
      tone: 'error',
      detail: reason || '校验账本读写异常，当前结论不可信。',
      needsRerun: false,
      facts: []
    };
  }

  // no_evidence / no_changes / not_covered / 其它：都没有可支撑完成的证据
  return {
    visible: true,
    label: '未验证',
    tone: 'muted',
    detail: reason || '还没有可用的校验证据，这份结果不能视为已验证。',
    needsRerun: true,
    facts: factsOf(latest)
  };
}

/** 去掉括号里的 passed/failed/skipped 英文状态码，界面只留人话。 */
function cleanReason(value: string) {
  return String(value || '')
    .replace(/[（(]\s*(passed|failed|skipped|missing|unknown|ok)\s*[）)]/gi, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

/** 依据里放什么：跑过哪些检查、覆盖了哪些文件、什么时候记的。只给可核对的东西。 */
function factsOf(evidence: S.ValidationEvidence | null): string[] {
  if (!evidence) return [];
  const facts: string[] = [];
  if (evidence.checks?.length) {
    facts.push(`执行的检查：${evidence.checks.join('；')}`);
  } else {
    facts.push('这一轮没有记录任何检查命令');
  }
  if (evidence.files?.length) {
    facts.push(`覆盖文件 ${evidence.files.length} 个：${evidence.files.slice(0, 5).join('、')}${evidence.files.length > 5 ? '…' : ''}`);
  }
  if (evidence.recordedAt) {
    const at = new Date(evidence.recordedAt);
    if (!Number.isNaN(at.getTime())) facts.push(`记录时间：${at.toLocaleString()}`);
  }
  return facts;
}

/** 「重新验证」发出的话：要求真跑一次，而不是复述上一次的结论。 */
export const REVERIFY_TEXT = '重新运行验证：针对当前文件内容执行相关测试或构建，说明实际执行的命令、退出码与覆盖范围；不要把之前的结论当作这次的结果。';

/** 从「命令 → exit N」里取非零退出的命令；exit 0 和无法解析的行不算失败。 */
const FAILED_CHECK = /^(.*?)\s*(?:→|->)\s*exit\s+(?!0\b)\d+/;

function failedCommandsOf(evidence: S.ValidationEvidence | null): string[] {
  return (evidence?.checks ?? [])
    .map(check => FAILED_CHECK.exec(String(check ?? '')))
    .filter((matched): matched is RegExpExecArray => matched !== null)
    .map(matched => matched[1].trim())
    .filter(command => command.length > 0);
}

/** 重跑失败的检查时把命令原文带上，让模型原样执行，别改写参数。 */
export function rerunFailedText(commands: string[]): string {
  return `原样重跑这些未通过的检查命令，不要合并或修改参数，说明实际退出码与覆盖范围：${commands.map(command => `\`${command}\``).join('、')}`;
}
