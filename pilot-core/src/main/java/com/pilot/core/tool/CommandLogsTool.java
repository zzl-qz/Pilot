package com.pilot.core.tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 日志游标由管理器产生，每次只读取有界文本。 */
public final class CommandLogsTool implements Tool {
    private final CommandTaskManager tasks;
    private final String sessionId;

    public CommandLogsTool(CommandTaskManager tasks, String sessionId) {
        this.tasks = Objects.requireNonNull(tasks);
        this.sessionId = Objects.requireNonNull(sessionId);
    }

    public String name() { return "command_logs"; }
    public String description() { return promptDescription(); }
    public boolean readOnly() { return true; }
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(
                "taskId", Map.of("type", "string", "description", "run_command 返回的任务 ID"),
                "offset", Map.of("type", "integer", "minimum", 0, "description", "首次传 0，之后原样传上次 nextOffset"),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 32000,
                        "description", "最多读取字符数，默认 12000，最大 32000")),
                "required", List.of("taskId"), "additionalProperties", false);
    }

    public String execute(Map<String, Object> args) {
        String id = ToolSupport.stringArg(args, "taskId");
        if (id == null) return "ERROR: 缺少参数 taskId";
        try {
            long offset = CommandToolSupport.integerArg(args, "offset", 0, 0, Long.MAX_VALUE);
            int limit = (int) CommandToolSupport.integerArg(args, "limit", 12000, 1, 32000);
            var chunk = tasks.logs(sessionId, id, offset, limit);
            if (chunk == null) return "ERROR: 当前会话没有该命令任务";
            return "任务ID: " + chunk.taskId() + "\noffset: " + chunk.offset()
                    + "\nnextOffset: " + chunk.nextOffset() + "\neof: " + chunk.eof()
                    + "\ntruncated: " + chunk.truncated() + "\n输出:\n" + chunk.text();
        } catch (IllegalArgumentException e) { return "ERROR: " + e.getMessage(); }
    }
}
