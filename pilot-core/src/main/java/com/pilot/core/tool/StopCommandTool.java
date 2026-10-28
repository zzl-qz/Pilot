package com.pilot.core.tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 停止属于当前会话的命令进程树。 */
public final class StopCommandTool implements Tool {
    private final CommandTaskManager tasks;
    private final String sessionId;

    public StopCommandTool(CommandTaskManager tasks, String sessionId) {
        this.tasks = Objects.requireNonNull(tasks);
        this.sessionId = Objects.requireNonNull(sessionId);
    }

    public String name() { return "stop_command"; }
    public String description() { return promptDescription(); }
    public boolean readOnly() { return false; }
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(
                "taskId", Map.of("type", "string", "description", "当前会话需要停止的命令任务 ID")),
                "required", List.of("taskId"), "additionalProperties", false);
    }

    public String execute(Map<String, Object> args) {
        String id = ToolSupport.stringArg(args, "taskId");
        if (id == null) return "ERROR: 缺少参数 taskId";
        try { return CommandToolSupport.render(tasks.stop(sessionId, id), false); }
        catch (IllegalArgumentException e) { return "ERROR: " + e.getMessage(); }
    }
}
