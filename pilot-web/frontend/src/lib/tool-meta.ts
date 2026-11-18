/** 工具图标 / 文案 / 状态说明：给用户看的短中文，不把协议字段原样甩出去。 */

export function parseTimestamp(value: string | number | null | undefined): number | undefined {
  const parsed = typeof value === 'number' ? value : value ? Date.parse(value) : NaN;
  return Number.isFinite(parsed) ? parsed : undefined;
}

function eventTimestamp(event: { timestamp?: string | number } | null | undefined) {
  return parseTimestamp(event?.timestamp) ?? Date.now();
}

const TOOL_LABELS: Record<string, string> = {
  read_file: '读取',
  list_dir: '列目录',
  search_text: '搜索',
  edit_file: '编辑',
  create_file: '新建',
  glob_files: '找文件',
  apply_patch: '补丁',
  run_command: '命令',
  command_status: '查命令',
  command_logs: '读日志',
  stop_command: '停命令',
  browser: '浏览器',
  web_search: '联网搜索',
  web_fetch: '拉取网页',
  mcp_tools: 'MCP 工具',
  mcp_call: 'MCP 调用',
  update_plan: '计划',
  use_skill: '技能',
  read_tool_output: '读输出',
  spawn_agent: '子代理',
  dispatch_agents: '派发',
  promote_goal: '提升目标',
  record_research: '记录调研',
  wait_until: '等待'
};

const BROWSER_ACTIONS: Record<string, string> = {
  navigate: '打开',
  click: '点击',
  type: '输入',
  fill: '填写',
  press: '按键',
  hover: '悬停',
  select: '选择',
  scroll: '滚动',
  screenshot: '截图',
  inspect: '检查',
  snapshot: '快照',
  wait: '等待',
  upload: '上传',
  download: '下载',
  evaluate: '脚本',
  tabs: '标签',
  back: '后退',
  forward: '前进',
  reload: '刷新',
  close: '关闭',
  content: '读内容'
};

function toolLabel(name: string) {
  return TOOL_LABELS[name] || name || '操作';
}

function shortId(value: string) {
  const text = String(value || '').trim();
  if (!text) return '';
  return text.length <= 12 ? text : `${text.slice(0, 8)}…`;
}

function toolArgsText(name: string, args: unknown) {
  const raw = String(args || '').trim();
  if (!raw) return '';
  const field = (key: string) => {
    const match = raw.match(new RegExp(`(?:^|[{,\\s])${key}=([^,}\\s]+)`));
    return match ? match[1].trim().replace(/^["']|["']$/g, '') : '';
  };
  if (name === 'run_command') return field('command') || raw;
  if (name === 'command_status') {
    const id = shortId(field('taskId'));
    const wait = field('waitSeconds');
    if (id && wait) return `${id} · 最多等 ${wait} 秒`;
    if (id) return id;
    return '查询后台命令';
  }
  if (name === 'command_logs') {
    const id = shortId(field('taskId'));
    return id ? `${id} · 增量日志` : '读取增量日志';
  }
  if (name === 'stop_command') {
    const id = shortId(field('taskId'));
    return id ? `停止 ${id}` : '停止后台命令';
  }
  if (name === 'browser') {
    const action = field('action') || raw.split(/[\s,]/)[0] || '';
    const label = BROWSER_ACTIONS[action] || action;
    const url = field('url');
    if ((action === 'navigate' || label === '打开') && url) {
      return `${label} ${url.replace(/^https?:\/\//, '')}`;
    }
    const target = field('ref') || field('selector') || field('text') || '';
    if (label && target) return `${label} ${target.length > 28 ? `${target.slice(0, 28)}…` : target}`;
    // summary 常常就是 action 名（click / inspect），直接译成中文
    if (BROWSER_ACTIONS[raw]) return BROWSER_ACTIONS[raw];
    return label || raw.replace(/^\{|\}$/g, '');
  }
  if (name === 'web_search') return field('query') || raw;
  if (name === 'web_fetch') return (field('url') || raw).replace(/^https?:\/\//, '');
  if (name === 'read_file' || name === 'edit_file' || name === 'create_file' || name === 'list_dir' || name === 'search_text' || name === 'glob_files') {
    return field('path') || field('query') || raw;
  }
  return raw.replace(/^\{|\}$/g, '');
}

/** 1 毫秒这种完成时间不占一行；再长的收成 61ms 或 1.2s。 */
function formatLatency(meta: string) {
  const match = String(meta || '').trim().match(/^(\d+(?:\.\d+)?)\s*ms$/i);
  if (!match) return meta;
  const ms = Number(match[1]);
  if (!Number.isFinite(ms) || ms < 10) return '';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const seconds = ms / 1000;
  return `${seconds.toFixed(seconds < 10 ? 1 : 0)}s`;
}

/**
 * 运行条：这一轮每一步操作画成一格，按发生顺序排开。
 * 灰=完成，红=失败，琥珀=等待审批，天蓝（脉动）=正在执行，一眼看出跑到哪、哪步出过错。
 */
const TOOL_STATUS_TITLES: Record<string, string> = {
  run: '运行中', ok: '已完成', error: '失败', wait: '等待批准',
  interrupted: '已中断：这一轮结束时没有收到结果'
};


/** 工具类型 → 单字符图标，让一屏内能快速扫出「读 / 写 / 命令」。 */
const TOOL_ICONS: Record<string, string> = {
  read_file: 'R',
  list_dir: 'D',
  search_text: 'S',
  edit_file: 'E',
  create_file: '+',
  glob_files: 'G',
  apply_patch: 'P',
  run_command: '$',
  command_status: '?',
  command_logs: 'L',
  stop_command: 'X',
  browser: 'B',
  mcp_tools: 'M',
  mcp_call: 'M'
};


export { eventTimestamp, toolLabel, toolArgsText, formatLatency, TOOL_STATUS_TITLES, TOOL_ICONS };
