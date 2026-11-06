/**
 * 权限档位：以后端 PermissionMode 枚举为准（READ_ONLY / ASK / AUTO_EDIT / AUTO）。
 *
 * <p>早先界面自己造了 default / auto-edit / auto 三个值：保存时后端按枚举解析直接 400，
 * 顶栏 chip 还只能原样显示 AUTO_EDIT。这里统一成一份表，菜单、设置页、chip 都读它。
 */
export type PermissionMode = 'AUTO' | 'AUTO_EDIT' | 'ASK' | 'READ_ONLY';
export interface PermissionOption { mode: PermissionMode; label: string; code: PermissionMode; hint: string }
export const PERMISSION_MODES: PermissionOption[] = [
  {
    mode: 'AUTO',
    label: '完全放行',
    code: 'AUTO',
    hint: '全部放行：写入与命令都不再打断，工作区边界仍由路径校验保证'
  },
  {
    mode: 'AUTO_EDIT',
    label: '自动改文件',
    code: 'AUTO_EDIT',
    hint: '改文件直接生效（可看 diff 回滚），命令仍需批准'
  },
  {
    mode: 'ASK',
    label: '逐次批准',
    code: 'ASK',
    hint: '每次写入和命令都要确认'
  },
  {
    mode: 'READ_ONLY',
    label: '只读',
    code: 'READ_ONLY',
    hint: '写入与命令一律拒绝，相当于 plan 模式'
  }
];

export const DEFAULT_PERMISSION = 'AUTO';

/** 后端返回的可能是任意大小写；认不出来的档位按默认档处理，别让界面显示空值。 */
export function normalizePermission(mode: unknown): PermissionMode {
  const value = String(mode || '').trim().toUpperCase();
  return PERMISSION_MODES.some((item) => item.mode === value) ? value as PermissionMode : DEFAULT_PERMISSION;
}

export function permissionOf(mode: unknown) {
  const value = normalizePermission(mode);
  return PERMISSION_MODES.find((item) => item.mode === value) || PERMISSION_MODES[0];
}

export function permissionLabel(mode: unknown) {
  return permissionOf(mode).label;
}

export function permissionHint(mode: unknown) {
  return permissionOf(mode).hint;
}
