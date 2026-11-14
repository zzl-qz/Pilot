package com.pilot.core.permission;

import com.pilot.core.message.ToolCall;
import com.pilot.core.tool.Tool;

/** 工具执行前的权限检查。接入层在 Sprint 3 用它实现 ASK 模式的挂起与恢复。 */
public interface PermissionGate {
    PermissionResult check(ToolCall call, Tool tool);
}
