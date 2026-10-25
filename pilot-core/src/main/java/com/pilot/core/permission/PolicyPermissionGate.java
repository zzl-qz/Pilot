package com.pilot.core.permission;

import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.WritesFiles;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 权限策略：
 * <ul>
 *   <li>READ_ONLY：只读工具放行，写入/命令一律拒绝</li>
 *   <li>ASK：只读放行，写入/命令挂起等待用户审批</li>
 *   <li>AUTO_EDIT：写入直接生效（事后按 diff 审阅、必要时回滚），命令仍挂起审批</li>
 *   <li>AUTO：在已注册工具范围内放行（工作区边界仍由 PathValidator 保证）</li>
 * </ul>
 *
 * <p>档位可以在运行时切换：传入 {@link Supplier} 时每次检查都读取最新值，
 * 因此界面上改完立即对后续工具调用生效，不需要重启服务。
 */
public final class PolicyPermissionGate implements PermissionGate {
    private final Supplier<PermissionMode> mode;

    public PolicyPermissionGate(PermissionMode mode) {
        PermissionMode fixed = mode == null ? PermissionMode.READ_ONLY : mode;
        this.mode = () -> fixed;
    }

    public PolicyPermissionGate(Supplier<PermissionMode> mode) {
        this.mode = Objects.requireNonNull(mode, "mode supplier must not be null");
    }

    @Override
    public PermissionResult check(ToolCall call, Tool tool) {
        if (tool.readOnly()) {
            return PermissionResult.allow();
        }
        return switch (mode()) {
            case READ_ONLY -> PermissionResult.deny("READ_ONLY 模式禁止执行 " + tool.name());
            case ASK -> PermissionResult.ask("需要用户批准 " + tool.name());
            // 文件写入像 Codex 那样直接改，命令仍逐次确认：命令的副作用不能被快照回滚
            case AUTO_EDIT -> tool instanceof WritesFiles
                    ? PermissionResult.allow()
                    : PermissionResult.ask("需要用户批准 " + tool.name());
            case AUTO -> PermissionResult.allow();
        };
    }

    public PermissionMode mode() {
        PermissionMode current = mode.get();
        return current == null ? PermissionMode.READ_ONLY : current;
    }
}
