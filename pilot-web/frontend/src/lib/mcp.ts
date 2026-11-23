/** MCP 信任状态的统一文案：设置页、能力面板和审批卡共用，避免各写一份。 */
export const MCP_TRUST_LABELS: Record<string, string> = {
  UNTRUSTED: '未信任',
  TRUSTED_UNPINNED: '已信任',
  TRUSTED: '已信任 · 已钉住工具',
  STALE: '信任失效：配置已变化'
};

/** 生效来源文案：工作区同名会覆盖用户级，所以生效来源只标 workspace/user。 */
export function mcpSourceLabel(source: unknown): string {
  return source === 'workspace' ? '工作区' : '用户级';
}
