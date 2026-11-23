package com.pilot.core.tool.command;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pilot.core.message.ToolNames;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;

/** 停止属于当前会话的命令进程树。 */
public final class StopCommandTool implements Tool {
    private final CommandTaskManager tasks;
    private final String sessionId;

    public StopCommandTool(CommandTaskManager tasks, String sessionId) {
        this.tasks = Objects.requireNonNull(tasks);
        this.sessionId = Objects.requireNonNull(sessionId);
    }

    public String name() {
        return ToolNames.STOP_COMMAND;
    }

    public boolean readOnly() {
        return false;
    }

    public Map<String, Object> parameters() {
        return ToolSchemas.object(Map.of("taskId", ToolSchemas.string("需要停止的命令任务 ID"), "ownerSessionId",
            CommandToolSupport.OWNER_SESSION_PARAMETER), List.of("taskId"));
    }

    public String execute(Map<String, Object> args) {
        // 参数取好了再进动作，动作里只做一次 stop，不再判空和补 ERROR 文本。
        return CommandToolSupport.withTaskId(args, id -> stop(id, CommandToolSupport.ownerSession(args)));
    }

    private String stop(String id, String owner) {
        return CommandToolSupport.render(tasks.stop(sessionId, id, owner), false);
    }
}
