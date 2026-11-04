package com.pilot.core.tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 查询当前会话的命令，允许有界等待，不重启命令。 */
public final class CommandStatusTool implements Tool, ProducesCheck {
    private final CommandTaskManager tasks;
    private final String sessionId;
    private final CommandChangeTracker changes;

    public CommandStatusTool(CommandTaskManager tasks, String sessionId) {
        this(tasks, sessionId, CommandChangeTracker.none());
    }

    public CommandStatusTool(CommandTaskManager tasks, String sessionId, CommandChangeTracker changes) {
        this.tasks = Objects.requireNonNull(tasks);
        this.sessionId = Objects.requireNonNull(sessionId);
        this.changes = changes == null ? CommandChangeTracker.none() : changes;
    }

    public String name() {
        return "command_status";
    }

    public String description() {
        return promptDescription();
    }

    public boolean readOnly() {
        return true;
    }

    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties",
            Map.of("taskId", Map.of("type", "string", "description", "run_command 返回的任务 ID"), "waitSeconds",
                Map.of("type", "integer", "minimum", 0, "maximum", 5, "description",
                    "最多等待多少秒再返回，默认 0，最大 5；运行中可用 5 避免频繁轮询")),
            "required", List.of("taskId"), "additionalProperties", false);
    }

    public String execute(Map<String, Object> args) {
        return executeResult(args).text();
    }

    @Override
    public ToolResult executeResult(Map<String, Object> args) {
        String id = ToolSupport.stringArg(args, "taskId");
        if (id == null) {
            return ToolResult.text("ERROR: 缺少参数 taskId");
        }
        try {
            long wait = CommandToolSupport.integerArg(args, "waitSeconds", 0, 0, 5);
            CommandTaskManager.Task task = tasks.get(sessionId, id);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(wait);
            while (CommandToolSupport.running(task) && System.nanoTime() < deadline) {
                Thread.sleep(50);
                task = tasks.get(sessionId, id);
            }
            String rendered = CommandToolSupport.render(task);
            if (task == null || CommandToolSupport.running(task) || task.changeTracking() == null
                || !task.changeTracking().active()) {
                return ToolResult.text(rendered);
            }
            CommandChangeTracker.Result result;
            try {
                result = changes.finish(task.changeTracking());
            } catch (RuntimeException e) {
                result = new CommandChangeTracker.Result(List.of(), false, String.valueOf(e.getMessage()));
            }
            if (!result.complete()) {
                rendered = "ERROR: 命令已结束，但无法完整确认其工作区变更；不得据此宣称验证通过：" + result.warning() + "\n" + rendered;
            }
            return new ToolResult(rendered, result.changedPaths(), List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.text("ERROR: 状态等待被中断；命令仍可用原 taskId 查询");
        } catch (IllegalArgumentException e) {
            return ToolResult.text("ERROR: " + e.getMessage());
        }
    }

    public String checkSummary(ToolCall call, String result) {
        return CommandToolSupport.checkSummary(tasks, sessionId, result);
    }
}
