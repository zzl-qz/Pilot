package com.pilot.core.permission;

import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolCall;

/** Sprint 2 的只读策略：只读工具放行，其余一律拒绝。 */
public final class ReadOnlyPermissionGate implements PermissionGate {
    @Override
    public PermissionResult check(ToolCall call, Tool tool) {
        if (tool.readOnly()) {
            return PermissionResult.allow();
        }
        return PermissionResult.deny("READ_ONLY 模式禁止执行非只读工具 " + tool.name());
    }
}
