package com.pilot.core.tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 查询当前会话的命令，允许有界等待，不重启命令。 */
public final class CommandStatusTool implements Tool, ProducesCheck {
    private final CommandTaskManager tasks;
    private final String sessionId;

    public CommandStatusTool(CommandTaskManager tasks, String sessionId) {
        this.tasks = Objects.requireNonNull(tasks);
        this.sessionId = Objects.requireNonNull(sessionId);
    }

    public String name() { return "command_status"; }
    public String description() { return promptDescription(); }
    public boolean readOnly() { return true; }
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(
                "taskId", Map.of("type", "string", "description", "run_command 返回的任务 ID"),
                "waitSeconds", Map.of("type", "integer", "minimum", 0, "maximum", 5,
                        "description", "最多等待多少秒再返回，默认 0，最大 5；运行中可用 5 避免频繁轮询")),
                "required", List.of("taskId"), "additionalProperties", false);
    }

    public String execute(Map<String, Object> args) {
        String id = ToolSupport.stringArg(args, "taskId");
        if (id == null) return "ERROR: 缺少参数 taskId";
        try {
            long wait = CommandToolSupport.integerArg(args, "waitSeconds", 0, 0, 5);
            var task = tasks.get(sessionId, id);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(wait);
            while (CommandToolSupport.running(task) && System.nanoTime() < deadline) {
                Thread.sleep(50);
                task = tasks.get(sessionId, id);
            }
            return CommandToolSupport.render(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: 状态等待被中断；命令仍可用原 taskId 查询";
        } catch (IllegalArgumentException e) { return "ERROR: " + e.getMessage(); }
    }

    public String checkSummary(ToolCall call, String result) {
        return CommandToolSupport.checkSummary(tasks, sessionId, result);
    }
}
