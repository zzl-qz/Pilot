/**
 * 事件流的纯逻辑：游标去重与失败码归类。
 *
 * <p>这两件事原来嵌在 app.js 的 handleEvent 里，是重连、回放、翻页三条路径共用的判定，
 * 出错的表现又都很隐蔽（重复渲染、丢掉一段历史、把「暂停」显示成「失败」），
 * 所以抽出来单测。
 */

/**
 * 判断一条事件是否应该被应用，以及应用后游标停在哪里。
 *
 * <p>三条规则：
 * <ul>
 *   <li>{@code assistant.preview} 沿用上一条已落库事件的序号，不能推进游标，
 *       否则后面的正式事件会因为「序号更小」被当成重复丢掉；</li>
 *   <li>序号不大于当前游标的是旧连接重复推送的，跳过；</li>
 *   <li>没有序号的事件（本地回显）照常应用，但不动游标。</li>
 * </ul>
 *
 * @returns {{apply: boolean, cursor: number}}
 */
export function applyRule(event, cursor) {
  const current = Number.isFinite(cursor) ? cursor : 0;
  if (!event || event.type === 'assistant.preview') {
    return { apply: false, cursor: current };
  }
  if (typeof event.sequence !== 'number') {
    return { apply: true, cursor: current };
  }
  if (event.sequence <= current) {
    return { apply: false, cursor: current };
  }
  return { apply: true, cursor: event.sequence };
}

/**
 * run.failed 的语义分档：取消、步数触顶（可继续）、服务重启中断（可继续）、真失败。
 *
 * <p>「步数触顶」和「中断」都不是任务失败——历史还在，继续跑就行，
 * 界面上要显示成暂停而不是红色错误。
 */
export function classifyRunFailure(code) {
  const value = String(code || '');
  const cancelled = value === 'CANCELLED';
  const paused = value === 'MAX_STEPS';
  const interrupted = value === 'INTERRUPTED';
  return {
    cancelled,
    paused,
    interrupted,
    resumable: paused || interrupted
  };
}

/** 计划进度文案：没有计划时返回空串，避免界面上出现「· 计划 0/0」。 */
export function planProgressLabel(done, total) {
  const totalCount = Number(total || 0);
  if (!totalCount) {
    return '';
  }
  return ` · 计划 ${Number(done || 0)}/${totalCount}`;
}
