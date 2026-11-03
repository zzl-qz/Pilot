// 运行结束、记录的检查通过、目标核验通过是不同的事实。
const PHASES = {
  ASSESSING: '准备执行', EXPLORING: '探查中', EXECUTING: '执行中',
  VERIFYING: '目标核验中', VERIFIED: '目标核验通过',
  CHECKS_PASSED: '记录的检查通过', CHECKS_FAILED: '检查未通过',
  CHECKS_STALE: '验证依据已变化，需重新验证', UNVERIFIED: '尚未验证',
  WAITING: '等待外部事件', WAIT: '等待外部事件', NEED_INPUT: '需要输入',
  BLOCKED: '遇到阻塞', IMPOSSIBLE: '当前条件无法完成', NO_PROGRESS: '暂无有效进展',
  REVIEW_UNAVAILABLE: '核验暂不可用', VERIFICATION_FAILED: '核验未能完成',
  BUDGET_LIMITED: '已达预算', PAUSED: '已暂停', CANCELLED: '已取消',
  COMPLETE: '执行已结束', COMPLETED: '执行已结束', UNKNOWN: '未保存验证记录'
};

export function verificationLabel(phase) {
  return PHASES[phase] || '验证状态未知';
}

export function verificationHint(phase) {
  if (phase === 'CHECKS_PASSED') return '仅表示当前代码对应的已记录检查通过，仍需对照任务要求确认覆盖范围。';
  if (phase === 'CHECKS_FAILED') return '存在未通过的检查；执行结束不代表任务已验收。';
  if (phase === 'CHECKS_STALE') return '现有检查对应较早的代码版本或任务要求，不能用于确认当前结果。';
  if (phase === 'VERIFICATION_FAILED' || phase === 'REVIEW_UNAVAILABLE') return '核验未能完成，当前结果仍需验证。';
  if (phase === 'UNVERIFIED' || phase === 'UNKNOWN' || phase === 'COMPLETE' || phase === 'COMPLETED' || !PHASES[phase]) return '没有足够的验证记录，不能据此认定结果已通过检查。';
  return '';
}
