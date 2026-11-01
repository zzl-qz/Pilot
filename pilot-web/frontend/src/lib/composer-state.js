/**
 * 解析输入区主按钮的唯一动作。
 *
 * 运行中没有草稿时，按钮用于停止当前任务；一旦用户输入文字或加入附件，
 * 它立即恢复为发送按钮，把内容排到当前任务之后。队列编辑拥有更高优先级，
 * 避免编辑到一半误触停止。
 */
export function resolveComposerAction({
  running = false,
  editing = false,
  hasText = false,
  attachmentCount = 0,
  composerEnabled = false,
  steering = false,
  pausing = false,
  cancelling = false
} = {}) {
  const hasDraft = Boolean(hasText) || Number(attachmentCount) > 0;
  const mode = editing ? 'save' : running && !hasDraft ? 'stop' : running ? 'queue' : 'send';

  if (mode === 'stop') {
    return {
      mode,
      stop: true,
      disabled: cancelling || pausing || steering,
      label: cancelling ? '正在停止' : '停止当前',
      ariaLabel: cancelling ? '正在停止当前任务' : '停止当前任务',
      title: cancelling ? '正在停止当前任务' : '停止当前任务 (Esc)'
    };
  }

  const labels = {
    save: ['保存排队消息', '保存排队消息', '保存排队消息 (Enter)'],
    queue: ['排队下一件事', '排队下一件事', '排队下一件事 (Enter)'],
    send: ['发送任务', '发送任务', '发送任务 (Enter)']
  };
  const [label, ariaLabel, title] = labels[mode];
  return {
    mode,
    stop: false,
    disabled: steering || (!running && !composerEnabled) || !hasDraft,
    label,
    ariaLabel,
    title
  };
}
