package com.pilot.core.permission;

import com.pilot.core.tool.ToolCall;

public interface PermissionGate {
    PermissionDecision check(ToolCall call);
}
