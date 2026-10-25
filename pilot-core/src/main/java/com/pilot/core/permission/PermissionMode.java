package com.pilot.core.permission;

/**
 * 权限档位。
 *
 * <ul>
 *   <li>{@link #READ_ONLY}：只读放行，写入与命令一律拒绝</li>
 *   <li>{@link #ASK}：只读放行，写入与命令逐次审批</li>
 *   <li>{@link #AUTO_EDIT}：写入直接生效（事后看 diff、可回滚），命令仍需审批</li>
 *   <li>{@link #AUTO}：全部放行，工作区边界仍由 PathValidator 保证</li>
 * </ul>
 */
public enum PermissionMode {
    READ_ONLY, ASK, AUTO_EDIT, AUTO
}
