/** 工具图标 / 文案 / 状态说明：从旧版原样搬运。 */

function eventTimestamp(event) {
  const parsed = Date.parse(event?.timestamp || '');
  return Number.isFinite(parsed) ? parsed : Date.now();
}

const TOOL_LABELS = {
  read_file: '读取',
  list_dir: '列目录',
  search_text: '搜索',
  edit_file: '编辑',
  create_file: '新建',
  glob_files: '找文件',
  apply_patch: '补丁',
  run_command: '命令',
  browser: '浏览器',
  mcp_tools: 'MCP',
  mcp_call: 'MCP',
  update_plan: '计划'
};

function toolLabel(name) {
  return TOOL_LABELS[name] || name || '操作';
}

/** 行里只留人能读的那一段：命令显示命令本身，文件类显示路径。 */
function toolArgsText(name, args) {
  const raw = String(args || '').trim();
  if (!raw) return '';
  const field = (key) => {
    const match = raw.match(new RegExp(`(?:^|[{,\\s])${key}=([^,}]+)`));
    return match ? match[1].trim() : '';
  };
  if (name === 'run_command') return field('command') || raw;
  if (name === 'read_file' || name === 'edit_file' || name === 'create_file' || name === 'list_dir' || name === 'search_text' || name === 'glob_files') {
    return field('path') || field('query') || raw;
  }
  return raw.replace(/^\{|\}$/g, '');
}

function refreshToolGroup(turn) {
  if (!turn || turn.total <= 0) {
    if (turn) {
      turn.tools.hidden = true;
    }
    return;
  }
  turn.tools.hidden = false;
  const bits = [];
  for (const [name, n] of turn.counts) {
    bits.push(`${toolLabel(name)} ${n}`);
  }
  turn.label.textContent = turn.running > 0 ? `正在执行 ${turn.total} 项` : `${turn.total} 项操作`;
  turn.count.textContent = bits.join(' · ');
  renderRunStrip(turn);
}

/**
 * 运行条：这一轮每一步操作画成一格，按发生顺序排开。
 * 灰=完成，红=失败，琥珀=等待审批，天蓝（脉动）=正在执行，一眼看出跑到哪、哪步出过错。
 */
const TOOL_STATUS_TITLES = {
  run: '运行中', ok: '已完成', error: '失败', wait: '等待批准',
  interrupted: '已中断：这一轮结束时没有收到结果'
};


/** 工具类型 → 单字符图标，让一屏内能快速扫出「读 / 写 / 命令」。 */
const TOOL_ICONS = {
  read_file: 'R',
  list_dir: 'D',
  search_text: 'S',
  edit_file: 'E',
  create_file: '+',
  glob_files: 'G',
  apply_patch: 'P',
  run_command: '$',
  browser: 'B',
  mcp_tools: 'M',
  mcp_call: 'M'
};


export { eventTimestamp, toolLabel, toolArgsText, TOOL_STATUS_TITLES, TOOL_ICONS };
