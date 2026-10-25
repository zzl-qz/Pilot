package com.pilot.core.permission;

import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolCall;

/**
 * 三档权限策略：
 * <ul>
 *   <li>READ_ONLY：只读工具放行，写入/命令一律拒绝</li>
 *   <li>ASK：只读放行，写入/命令挂起等待用户审批</li>
 *   <li>AUTO：在已注册工具范围内放行（工作区边界仍由 PathValidator 保证）</li>
 * </ul>
 */
public final class PolicyPermissionGate implements PermissionGate {
    private final PermissionMode mode;

    public PolicyPermissionGate(PermissionMode mode) {
        this.mode = mode == null ? PermissionMode.READ_ONLY : mode;
    }

    @Override
    public PermissionResult check(ToolCall call, Tool tool) {
        if (tool.readOnly()) {
            return PermissionResult.allow();
        }
        return switch (mode) {
            case READ_ONLY -> PermissionResult.deny("READ_ONLY 模式禁止执行 " + tool.name());
            case ASK -> PermissionResult.ask("需要用户批准 " + tool.name());
            case AUTO -> PermissionResult.allow();
        };
    }

    public PermissionMode mode() {
        return mode;
    }
}
