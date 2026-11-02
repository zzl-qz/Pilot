/** 主按钮只负责发送；停止始终是独立按钮，草稿和附件不会改变停止入口。 */
export function resolveComposerAction({
  running = false,
  editing = false,
  followUp = 'queue',
  hasText = false,
  attachmentCount = 0,
  composerEnabled = false,
  steering = false,
  pausing = false,
  cancelling = false
} = {}) {
  const hasDraft = Boolean(hasText) || Number(attachmentCount) > 0;
  const mode = editing ? 'save' : running ? (followUp === 'steer' ? 'steer' : 'queue') : 'send';

  const labels = {
    save: ['重新发送', '重新发送', '重新发送 (Enter)'],
    steer: ['立即纠正', '立即纠正当前任务', '立即纠正当前任务 (Enter)'],
    queue: ['排队下一件事', '排队下一件事', '排队下一件事 (Enter)'],
    send: ['发送任务', '发送任务', '发送任务 (Enter)']
  };
  const [label, ariaLabel, title] = labels[mode];
  return {
    mode,
    stop: false,
    disabled: cancelling || pausing || steering || (!running && !composerEnabled) || !hasDraft,
    label,
    ariaLabel,
    title
  };
}
