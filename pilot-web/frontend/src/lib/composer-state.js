/** 空草稿运行中显示停止；有草稿只排队，引导在对应队列行操作。 */
export function resolveComposerAction({ running = false, editing = false, hasText = false,
  attachmentCount = 0, composerEnabled = false, steering = false, pausing = false, cancelling = false } = {}) {
  const hasDraft = Boolean(hasText) || Number(attachmentCount) > 0;
  const mode = editing ? 'save' : running ? (hasDraft ? 'queue' : 'stop') : 'send';
  const label = { save: '重新发送', queue: '加入队列', stop: cancelling ? '停止中…' : '停止当前任务', send: '发送任务' }[mode];
  return { mode, stop: mode === 'stop', label, ariaLabel: label,
    title: mode === 'stop' ? '停止当前任务 (Esc)' : `${label} (Enter)`,
    disabled: cancelling || pausing || steering || (!running && !composerEnabled) || (mode !== 'stop' && !hasDraft) };
}
