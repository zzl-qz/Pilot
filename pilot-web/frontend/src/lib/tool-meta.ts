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
  wait_until: '等待',
  ask_user: '提问',
  code_diagnostics: '诊断',
  code_symbol: '符号'
};

export function parseAsk(args: string) {
  const text = String(args || '');
  if (!text.startsWith('ASK\n')) return null;
  const body = text.slice(4);
  const split = body.indexOf('\n');
  const question = split < 0 ? body : body.slice(0, split);
  const options = (split < 0 ? '' : body.slice(split + 1)).split('\u001f').filter(Boolean);
  return options.length >= 2 ? { question, options } : null;
}

export function questionChoice(option: string, custom: string) {
  const typed = String(custom || '').trim();
  return typed || String(option || '').trim();
}

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
  const obj = args && typeof args === 'object' && !Array.isArray(args) ? (args as Record<string, unknown>) : null;
  const raw = obj
    ? (() => {
        for (const key of ['command', 'path', 'file', 'pattern', 'query', 'url', 'question', 'task']) {
          if (obj[key] != null && String(obj[key]).trim()) return String(obj[key]).trim();
        }
        try { return JSON.stringify(obj); } catch { return ''; }
      })()
    : String(args || '').trim();
  if (!raw) return '';
  const field = (key: string) => {
    if (obj && obj[key] != null) return String(obj[key]).trim();
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

export type ToolIconKind =
  | 'java' | 'markdown' | 'typescript' | 'javascript' | 'style' | 'json'
  | 'markup' | 'python' | 'shell' | 'code' | 'file'
  | 'terminal' | 'search' | 'folder' | 'browser' | 'plan' | 'agent' | 'tool';

export interface ToolDiff {
  added: number;
  removed: number;
}

export interface ToolDisplay {
  kind: ToolIconKind;
  primary: string;
  fullTarget: string;
  lineRange: string;
  startLine?: number;
  diff: ToolDiff | null;
  exitCode: number | null;
  filePath: string;
}

const FILE_TOOLS = new Set([
  'read_file', 'edit_file', 'create_file', 'code_symbol', 'code_diagnostics'
]);

const EXT_KINDS: Record<string, ToolIconKind> = {
  java: 'java', kt: 'java', kts: 'java', gradle: 'java',
  md: 'markdown', mdx: 'markdown', markdown: 'markdown',
  ts: 'typescript', tsx: 'typescript', mts: 'typescript', cts: 'typescript',
  js: 'javascript', jsx: 'javascript', mjs: 'javascript', cjs: 'javascript',
  css: 'style', scss: 'style', sass: 'style', less: 'style',
  json: 'json', jsonc: 'json', yaml: 'json', yml: 'json', toml: 'json', xml: 'json', properties: 'json',
  html: 'markup', htm: 'markup', svg: 'markup', vue: 'markup', svelte: 'markup',
  py: 'python', pyi: 'python',
  sh: 'shell', bash: 'shell', zsh: 'shell', ps1: 'shell', bat: 'shell', cmd: 'shell',
  go: 'code', rs: 'code', c: 'code', cc: 'code', cpp: 'code', h: 'code', hpp: 'code',
  cs: 'code', rb: 'code', php: 'code', swift: 'code', sql: 'code', graphql: 'code'
};

/** 把秒数收成易扫读的时长：「45s」「2m 15s」「1h 05m」。 */
export function formatProcessDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0s';
  const whole = Math.floor(seconds);
  if (whole < 60) return `${whole}s`;
  const hours = Math.floor(whole / 3600);
  const minutes = Math.floor((whole % 3600) / 60);
  const secs = whole % 60;
  if (hours > 0) return `${hours}h ${String(minutes).padStart(2, '0')}m`;
  return `${minutes}m ${String(secs).padStart(2, '0')}s`;
}

/** 计算一组工具从最早开始到最晚结束（或当前时刻）的总秒数。 */
export function computeToolSpanSeconds(
  tools: readonly { startedAt?: number; finishedAt?: number }[],
  live: boolean,
  now = Date.now()
): number | null {
  let start = Infinity;
  let end = -Infinity;
  for (const tool of tools) {
    if (tool.startedAt != null && Number.isFinite(tool.startedAt)) start = Math.min(start, tool.startedAt);
    if (tool.finishedAt != null && Number.isFinite(tool.finishedAt)) end = Math.max(end, tool.finishedAt);
  }
  if (!Number.isFinite(start)) return null;
  const finish = live ? now : end;
  if (!Number.isFinite(finish)) return null;
  return Math.max(0, Math.floor((finish - start) / 1000));
}

/** 跨平台拆分文件路径（同时支持 POSIX `/` 与 Windows `\`），识别文件名与图标类别。 */
export function extractFileMeta(rawPath: string) {
  const cleaned = String(rawPath || '').trim().replace(/^["']|["']$/g, '').replace(/[\\/]+$/, '');
  const parts = cleaned.split(/[\\/]+/).filter(Boolean);
  const fileName = parts[parts.length - 1] || cleaned;
  const dirName = parts.length > 1 ? parts.slice(0, -1).join('/') : '';
  const dot = fileName.lastIndexOf('.');
  const ext = dot > 0 && dot < fileName.length - 1 ? fileName.slice(dot + 1).toLowerCase() : '';
  const kind: ToolIconKind = EXT_KINDS[ext] || 'file';
  return { cleaned, fileName, dirName, ext, kind };
}

function parseArgsField(raw: string, key: string): string {
  const match = raw.match(new RegExp(`(?:^|[{,\\s])${key}=([^,}\\s]+)`));
  return match ? match[1].trim().replace(/^["']|["']$/g, '') : '';
}

function parseLineRange(args: string, body: string): { lineRange: string; startLine?: number } {
  const startRaw = Number(parseArgsField(args, 'start_line'));
  const countRaw = Number(parseArgsField(args, 'line_count'));
  if (Number.isInteger(startRaw) && startRaw > 0) {
    if (Number.isInteger(countRaw) && countRaw > 0) {
      const end = startRaw + countRaw - 1;
      return { lineRange: end > startRaw ? `#L${startRaw}-${end}` : `#L${startRaw}`, startLine: startRaw };
    }
    return { lineRange: `#L${startRaw}`, startLine: startRaw };
  }
  const zhRange = body.match(/显示第\s*(\d+)\s*到第\s*(\d+)\s*行/)
    || body.match(/第\s*(\d+)\s*[–-]\s*(\d+)\s*行/)
    || body.match(/#?L(\d+)\s*[–-]\s*(\d+)/);
  if (zhRange) {
    const start = Number(zhRange[1]);
    const end = Number(zhRange[2]);
    if (Number.isFinite(start) && Number.isFinite(end) && start > 0) {
      return { lineRange: end > start ? `#L${start}-${end}` : `#L${start}`, startLine: start };
    }
  }
  const singleZh = body.match(/请求第\s*(\d+)\s*行/);
  if (singleZh) {
    const line = Number(singleZh[1]);
    if (Number.isFinite(line) && line > 0) return { lineRange: `#L${line}`, startLine: line };
  }
  return { lineRange: '' };
}

function parseToolDiff(name: string, body: string): ToolDiff | null {
  if (!body) return null;
  const editMatch = body.match(/[（(](\d+)\s*行\s*(?:→|->)\s*(\d+)\s*行[）)]/);
  if (editMatch) {
    return { removed: Number(editMatch[1]), added: Number(editMatch[2]) };
  }
  if (name === 'create_file') {
    const createMatch = body.match(/[（(](?:[^）)]*[，,])?\s*(\d+)\s*行[）)]/);
    if (createMatch) return { added: Number(createMatch[1]), removed: 0 };
  }
  const plusMinus = body.match(/\+(\d+)\s+-(\d+)/);
  if (plusMinus && (name === 'edit_file' || name === 'apply_patch' || name === 'create_file')) {
    return { added: Number(plusMinus[1]), removed: Number(plusMinus[2]) };
  }
  return null;
}

function parseExitCode(body: string): number | null {
  if (!body) return null;
  const match = body.match(/退出码[:：]\s*(-?\d+)/)
    || body.match(/exit(?:ed with)?\s+code\s+(-?\d+)/i)
    || body.match(/→\s*exit\s+(-?\d+)/i);
  if (!match) return null;
  const code = Number(match[1]);
  return Number.isFinite(code) && code !== 0 ? code : null;
}

function iconKindForTool(name: string): ToolIconKind {
  if (name === 'run_command' || name === 'command_status' || name === 'command_logs' || name === 'stop_command') return 'terminal';
  if (name === 'search_text' || name === 'glob_files') return 'search';
  if (name === 'list_dir') return 'folder';
  if (name === 'browser' || name === 'web_search' || name === 'web_fetch') return 'browser';
  if (name === 'update_plan' || name === 'promote_goal') return 'plan';
  if (name === 'spawn_agent' || name === 'dispatch_agents') return 'agent';
  return 'tool';
}

function extractSimpleArg(raw: string, key: string): string {
  const direct = new RegExp(`^${key}=(.+)$`).exec(raw);
  if (direct) return direct[1].trim().replace(/^["']|["']$/g, '');
  const braced = new RegExp(`(?:^|[{,]\\s*)${key}=([^,}]+)`).exec(raw);
  return braced ? braced[1].trim().replace(/^["']|["']$/g, '') : '';
}

/** 把工具原始参数与返回摘要整理成紧凑、易扫读的单行展示信息。 */
export function parseToolDisplay(tool: { name: string; args: string; body?: string; status?: string }): ToolDisplay {
  const rawArgs = String(tool.args || '').trim();
  const body = String(tool.body || '').trim();
  const fullTarget = toolArgsText(tool.name, rawArgs);

  // 提问的问题正文交给卡片展示，行头只留一句状态，否则同一句话会出现两遍还被截断。
  if (tool.name === 'ask_user') {
    const ask = parseAsk(rawArgs);
    return {
      kind: 'tool',
      primary: ask ? `等待你选择 ${ask.options.length} 项` : '等待回答',
      fullTarget: '',
      lineRange: '',
      diff: null,
      exitCode: null,
      filePath: ''
    };
  }

  if (FILE_TOOLS.has(tool.name)) {
    const simplePath = extractSimpleArg(rawArgs, 'path');
    const pathFromBody = !simplePath && !fullTarget
      ? (body.match(/(?:已修改|已创建|已提交到沙箱)\s+([^：:（(\s]+)/)?.[1] || '')
      : '';
    const rawPath = simplePath || fullTarget || pathFromBody;
    const meta = extractFileMeta(rawPath);
    const range = tool.name === 'read_file' || tool.name === 'code_symbol'
      ? parseLineRange(rawArgs, body)
      : { lineRange: '' };
    return {
      kind: rawPath ? meta.kind : 'file',
      primary: meta.fileName || fullTarget,
      fullTarget: meta.cleaned || fullTarget,
      lineRange: range.lineRange,
      startLine: range.startLine,
      diff: parseToolDiff(tool.name, body),
      exitCode: null,
      filePath: meta.cleaned
    };
  }

  let primary = fullTarget;
  if (tool.name === 'run_command') {
    primary = extractSimpleArg(rawArgs, 'command') || fullTarget;
  } else if (tool.name === 'search_text' || tool.name === 'glob_files') {
    primary = extractSimpleArg(rawArgs, 'pattern') || extractSimpleArg(rawArgs, 'query') || fullTarget;
  } else if (tool.name === 'list_dir') {
    primary = extractSimpleArg(rawArgs, 'path') || fullTarget || '.';
  }

  const kind = tool.name === 'apply_patch' ? 'code' : iconKindForTool(tool.name);
  const exitCode = kind === 'terminal' ? parseExitCode(body) : null;
  return {
    kind,
    primary,
    fullTarget: primary || fullTarget,
    lineRange: '',
    diff: tool.name === 'apply_patch' ? parseToolDiff(tool.name, body) : null,
    exitCode,
    filePath: ''
  };
}

/** 收起态运行中摘要：提取最近一条执行中工具的短文案。 */
export function summarizeActiveTool(tools: readonly { name: string; args: string; status: string; body?: string }[]): string {
  for (let i = tools.length - 1; i >= 0; i -= 1) {
    const item = tools[i];
    if (item.status !== 'run') continue;
    const display = parseToolDisplay(item);
    const parts = [toolLabel(item.name), display.primary].filter(Boolean);
    return parts.join(' ');
  }
  return '';
}


export { eventTimestamp, toolLabel, toolArgsText, formatLatency, TOOL_STATUS_TITLES, TOOL_ICONS };
