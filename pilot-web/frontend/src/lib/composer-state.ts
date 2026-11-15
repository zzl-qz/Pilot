export interface ComposerActionInput {
  queuePaused?: boolean; running?: boolean; editing?: boolean; hasText?: boolean; attachmentCount?: number; composerEnabled?: boolean; steering?: boolean; pausing?: boolean; cancelling?: boolean; modelSaving?: boolean }
/** 空草稿运行中显示停止；有草稿只排队，引导在对应队列行操作。 */
export function resolveComposerAction({ running = false, editing = false, hasText = false,
  attachmentCount = 0, composerEnabled = false, steering = false, pausing = false, cancelling = false, modelSaving = false }: ComposerActionInput = {}) {
  const hasDraft = Boolean(hasText) || Number(attachmentCount) > 0;
  const mode = editing ? 'save' : running ? (hasDraft ? 'queue' : 'stop') : 'send';
  const label = { save: '重新发送', queue: '加入队列', stop: cancelling ? '停止中…' : '停止当前任务', send: '发送任务' }[mode];
  return { mode, stop: mode === 'stop', label, ariaLabel: label,
    title: mode === 'stop' ? '停止当前任务 (Esc)' : modelSaving ? '模型配置保存中，请稍候再发送' : `${label} (Enter)`,
    disabled: cancelling || pausing || steering || (!running && !composerEnabled) || (mode !== 'stop' && (!hasDraft || modelSaving)) };
}

/**
 * 「纠正本轮」入口：只有运行中、有文字草稿且不是编辑历史消息时出现。
 * 带附件时仍走队列（服务端纠正接口只收纯文本）。
 */
export function resolveSteerEntry({ running = false, editing = false, hasText = false,
  attachmentCount = 0, steering = false, pending = false }: {
  running?: boolean; editing?: boolean; hasText?: boolean; attachmentCount?: number;
  steering?: boolean; pending?: boolean } = {}) {
  // 提交后输入框已清空，但仍要显示「等待应用」直到对应事件到达
  const visible = Boolean(pending) || (Boolean(running) && Boolean(hasText) && !editing && Number(attachmentCount) === 0);
  const disabled = Boolean(steering) || Boolean(pending);
  return {
    visible,
    disabled,
    label: pending ? '纠正等待应用…' : steering ? '正在提交纠正…' : '纠正本轮',
    title: '把这段文字作为本轮的方向纠正立即提交，不进入排队；仍可点「加入队列」留到下一轮'
  };
}
