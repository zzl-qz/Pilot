/**
 * 多智能体的纯逻辑：谁发起了这条工具调用、一组子智能体怎么概括、耗时怎么显示。
 * 画界面在 app.js，这里只放不碰 DOM 的部分，方便单测。
 */

/** `agent_ab12cd34` → `ab12cd`：界面上只需要能区分，不必看完整 ID。 */
export function shortAgentId(id) {
  return String(id || '').replace(/^agent_/, '').slice(0, 6);
}

/**
 * 判断一条工具 / 审批事件属于哪个子智能体。
 * - 事件自己带了 agentId 且我们见过它：用它（并行的多个子智能体靠这个分清）。
 * - 老数据没有归属：只有「恰好一个子智能体在运行」时才能确定是它在干活（主智能体在等它），
 *   有多个在跑就宁可留在主智能体名下，也不乱归属。
 */
export function pickOwner(agentId, agents) {
  const list = [...(agents || [])];
  if (agentId) {
    return list.find((agent) => agent.id === agentId) || null;
  }
  const running = list.filter((agent) => agent.status === 'running');
  return running.length === 1 ? running[0] : null;
}

/** 一组子智能体的概括：「3 个子智能体 · 2 运行中 · 1 失败」。 */
export function summarizeAgents(agents) {
  const list = [...(agents || [])];
  const count = (status) => list.filter((agent) => agent.status === status).length;
  const running = count('running');
  const failed = count('failed');
  const stopped = count('stopped');
  const done = count('done');
  const bits = [`${list.length} 个子智能体`];
  if (running) bits.push(`${running} 运行中`);
  if (failed) bits.push(`${failed} 失败`);
  if (stopped) bits.push(`${stopped} 已中止`);
  if (list.length && done === list.length) bits.push('全部完成');
  return { total: list.length, running, failed, stopped, done, text: bits.join(' · ') };
}

/** 毫秒 → 「850ms」「12s」「2m 05s」。 */
export function spanText(ms) {
  const value = Number(ms);
  if (!Number.isFinite(value) || value < 0) return '';
  if (value < 1000) return `${Math.round(value)}ms`;
  const seconds = Math.round(value / 1000);
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m ${String(seconds % 60).padStart(2, '0')}s`;
}

/** 卡片右侧的小字：操作数 + 耗时（还在运行就只显示已有的部分）。 */
export function agentMeta({ tools = 0, ms = null, status = 'running' }) {
  const bits = [];
  if (tools > 0) bits.push(`${tools} 项操作`);
  const span = ms == null ? '' : spanText(ms);
  if (span) bits.push(span);
  if (!bits.length) bits.push(status === 'running' ? '启动中' : '无操作');
  return bits.join(' · ');
}

/**
 * 子智能体的回传文本末尾带一段 `<subagent_result>` 清单（改了哪些文件、跑了哪些检查、还剩哪些步骤），
 * 给父模型合并用。界面上把它从正文里摘出来，单独画成一行事实。
 */
export function splitSubagentResult(raw) {
  const text = String(raw || '');
  const match = /\n*<subagent_result>\n([\s\S]*?)\n?<\/subagent_result>\s*$/.exec(text);
  if (!match) {
    return { text: text.trim(), changed: [], checks: [], openSteps: [], hasManifest: false };
  }
  const list = (key) => {
    const line = new RegExp(`^${key}:\\s*\\[(.*)\\]\\s*$`, 'm').exec(match[1]);
    return line && line[1].trim() ? line[1].split(/,\s*(?![^()]*\))/).map((item) => item.trim()).filter(Boolean) : [];
  };
  return {
    text: text.slice(0, match.index).trim(),
    changed: list('changed_files'),
    checks: list('checks'),
    openSteps: list('open_steps'),
    hasManifest: true
  };
}

/** 清单 → 一行小字：「未改动文件 · 校验 1 项 · 未完成步骤 0」。 */
export function manifestFacts({ changed = [], checks = [], openSteps = [], hasManifest = false }) {
  if (!hasManifest) return [];
  const facts = [changed.length ? `改动 ${changed.length} 个文件` : '未改动文件'];
  if (checks.length) facts.push(`校验 ${checks.length} 项`);
  if (openSteps.length) facts.push(`未完成步骤 ${openSteps.length}`);
  return facts;
}

/** 给子智能体起个像样的名字：取任务描述的第一个分句，太长就截断（路径里的点不当句号）。 */
export function agentTitle(task) {
  const text = String(task || '').replace(/\s+/g, ' ').trim();
  if (!text) return '子智能体';
  const body = text.replace(/^请(只读|务必|帮我)?/, '').trim() || text;
  const head = body.split(/[：:。！!？?，,；;]/)[0].trim() || body;
  return head.length > 24 ? `${head.slice(0, 22)}…` : head;
}

/** 面板里每个子智能体下面那行小字：运行中显示正在做什么，结束后显示结论的第一行。 */
export function agentLine({ status, latest = '', conclusion = '' }) {
  if (status === 'running') return latest ? `正在 ${latest}` : '正在思考…';
  if (status === 'stopped') return '运行结束时仍未完成';
  const first = String(conclusion || '').split('\n').map((line) => line.trim()).find(Boolean) || '';
  const text = first.length > 80 ? `${first.slice(0, 78)}…` : first;
  return text || (status === 'failed' ? '失败，没有返回原因' : '完成，没有返回文字结论');
}
