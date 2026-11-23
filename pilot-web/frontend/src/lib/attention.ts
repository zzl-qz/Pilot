/**
 * 标签页上的「注意力信号」：运行中、需要你处理、完成、失败。
 * 切到别的窗口干活时，只看标签页标题和图标就知道该不该回来。
 */
const PREFIX: Record<string, string> = { idle: '', running: '● ', attention: '! ', done: '✓ ', error: '✕ ' };
const BADGE: Record<string, string> = { running: '#4fa3ea', attention: '#f2b544', done: '#3ecf8e', error: '#ff6f6f' };

export function tabTitle(base: unknown, kind: string) {
  const name = String(base || 'Pilot').trim() || 'Pilot';
  return (PREFIX[kind] ?? '') + name;
}

export function tabTitleWithCount(base: unknown, kind: string, urgentCount = 0) {
  const name = String(base || 'Pilot').trim() || 'Pilot';
  if (urgentCount > 0) {
    return `! ${urgentCount > 1 ? `(${urgentCount}) ` : ''}${name}`;
  }
  return tabTitle(name, kind);
}

/** 圆环 + 箭头的品牌图标；有状态时右下角加一枚状态色圆点。 */
export function faviconHref(kind: string) {
  const badge = BADGE[kind]
    ? `%3Ccircle cx='25' cy='25' r='6.5' fill='${encodeURIComponent(BADGE[kind])}' stroke='%23171717' stroke-width='2'/%3E`
    : '';
  return "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E"
    + "%3Crect width='32' height='32' rx='9' fill='%23171717'/%3E"
    + "%3Ccircle cx='16' cy='16' r='9.5' fill='none' stroke='%238c8c8c' stroke-width='1.8'/%3E"
    + "%3Cpath d='M16 8.6l4.6 13.6L16 19.4l-4.6 2.8z' fill='%236cb4f0'/%3E"
    + badge
    + '%3C/svg%3E';
}

/** 只有页面在后台、用户开了通知、浏览器也授权了、且事件值得打扰时才弹系统通知。 */
export function shouldNotify({ kind, hidden, enabled, permission }: { kind: string; hidden: boolean; enabled: boolean; permission: string }) {
  return Boolean(hidden && enabled && permission === 'granted'
    && (kind === 'attention' || kind === 'done' || kind === 'error'));
}

export function isWindowBackgrounded(
  doc: { hidden?: boolean; hasFocus?: () => boolean } | undefined = globalThis.document,
  desktopFocused: boolean | null = null
): boolean {
  if (doc?.hidden) return true;
  if (desktopFocused === false) return true;
  if (typeof doc?.hasFocus === 'function') {
    try { return !doc.hasFocus(); } catch { return false; }
  }
  return false;
}

const HIGH_RISK_PATTERNS = [
  /\brm\s+-[^\s]*r/i,
  /\bdel\s+\/[sq]/i,
  /\brmdir\s+\/s/i,
  /\bremove-item\b[^\n]*-recurse/i,
  /\b(mkfs|format|fdisk)\b/i,
  /\bdd\b\s+if=/i,
  /git\s+reset\s+--hard/i,
  /git\s+push\b[^\n]*(--force|-f\b)/i,
  /git\s+clean\b[^\n]*-[^\s]*f/i,
  /\bsudo\b/i,
  /chmod\s+777\b/i,
  /\b(npm|yarn|pnpm)\s+publish\b/i,
  /\bmvn\s+deploy\b/i,
  /\b(npm|pnpm)\s+(install|add|i)\s+-g\b/i,
  /\bpip3?\s+install\b/i,
  /\b(drop\s+(table|database)|truncate\s+table)\b/i,
  /\bcurl\b[^\n]*\|\s*(sh|bash|zsh|pwsh|powershell)\b/i
];

export function classifyApprovalRisk(
  toolName: string,
  argsSummary: unknown,
  structuredArgs?: Record<string, unknown> | null
): { category: string; highRisk: boolean; summary: string } {
  const name = String(toolName || '').trim();
  const command = typeof structuredArgs?.command === 'string' ? structuredArgs.command : '';
  const summary = command || (typeof argsSummary === 'string' ? argsSummary.trim() : '');
  let category = '工具操作';
  if (name === 'run_command' || name === 'command_input' || name === 'stop_command') category = '执行命令';
  else if (name === 'edit_file' || name === 'create_file' || name === 'apply_patch' || name === 'office') category = '修改文件';
  else if (name === 'mcp_tools' || name === 'mcp_call') category = '外部工具';
  else if (name === 'browser') category = '浏览器操作';
  else if (name === 'hook_command') category = '工作区钩子';
  const textToInspect = `${command}\n${String(argsSummary || '')}`;
  const highRisk = Boolean(structuredArgs?.highRisk) || HIGH_RISK_PATTERNS.some((pattern) => pattern.test(textToInspect));
  return { category, highRisk, summary };
}

export function formatCountdown(
  expiresAt: string | number | null | undefined,
  now = Date.now()
): { label: string; remainingSeconds: number; urgent: boolean; expired: boolean } | null {
  if (expiresAt == null || expiresAt === '') return null;
  const target = typeof expiresAt === 'number' ? expiresAt : Date.parse(String(expiresAt));
  if (!Number.isFinite(target) || target <= 0) return null;
  const remainingSeconds = Math.max(0, Math.floor((target - now) / 1000));
  const minutes = Math.floor(remainingSeconds / 60);
  const seconds = remainingSeconds % 60;
  return {
    remainingSeconds,
    urgent: remainingSeconds <= 30,
    expired: remainingSeconds === 0,
    label: remainingSeconds > 0 ? `剩余 ${minutes}:${String(seconds).padStart(2, '0')}` : '即将超时'
  };
}

const COMPOUND_SHELL_RE = /(?:&&|\|\||[;|`<>()]|\$\(|\r|\n)/;
const MULTI_TOKEN_CMD_HEADS = new Set(['npm', 'pnpm', 'yarn', 'bun', 'mvn', 'gradle', 'git', 'cargo', 'go', 'dotnet', 'docker', 'kubectl', 'pytest', 'python', 'python3', 'node', 'npx', 'uv']);

/** 为非高风险审批生成安全的一键放行规则建议；复合 Shell 语句和高风险操作一律不提供。 */
export function suggestAllowRule(
  toolName: string,
  argsSummary: unknown,
  structuredArgs?: Record<string, unknown> | null
): { rule: string; label: string } | null {
  const name = String(toolName || '').trim();
  const risk = classifyApprovalRisk(name, argsSummary, structuredArgs);
  if (risk.highRisk) return null;

  if (name === 'run_command') {
    const cmd = typeof structuredArgs?.command === 'string'
      ? structuredArgs.command.trim()
      : typeof argsSummary === 'string' ? argsSummary.trim() : '';
    if (!cmd || COMPOUND_SHELL_RE.test(cmd)) return null;
    const tokens = cmd.split(/\s+/).filter(Boolean);
    if (!tokens.length || tokens[0].startsWith('-')) return null;
    const base = tokens[0].replace(/^.*[\\/]/, '');
    if (!base) return null;
    const second = tokens[1] && !tokens[1].startsWith('-') && !/[\\/:*?"<>|]/.test(tokens[1]) ? tokens[1] : '';
    const prefix = MULTI_TOKEN_CMD_HEADS.has(base.toLowerCase()) && second
      ? `${tokens[0]} ${second}`
      : tokens[0];
    return {
      rule: `run_command(${prefix}:*)`,
      label: `记住 ${prefix} 命令`
    };
  }

  if (name === 'edit_file' || name === 'create_file' || name === 'apply_patch') {
    const rawPath = typeof structuredArgs?.path === 'string' ? structuredArgs.path.trim().replace(/\\/g, '/') : '';
    const firstDir = rawPath && rawPath.includes('/') ? rawPath.replace(/^\.\//, '').split('/')[0] : '';
    if (firstDir && firstDir !== '.' && firstDir !== '..' && !/[:*()[\]]/.test(firstDir)) {
      return {
        rule: `${name}(${firstDir}/**)`,
        label: `允许修改 ${firstDir}/`
      };
    }
    return {
      rule: name,
      label: name === 'create_file' ? '记住新建文件' : '记住文件修改'
    };
  }

  return null;
}
