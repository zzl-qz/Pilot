package com.pilot.core.tool.command;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;

/**
 * 列出本机所有会话的命令任务，运行中的排在前面。
 *
 * <p>命令任务按会话隔离，但会话关掉后进程仍可能在跑；换一个会话时模型只有这张表能知道
 * 「那是谁的、还活着没有」，然后带着 {@code ownerSessionId} 去查 command_status、command_logs
 * 或 stop_command。只读，不会停止任何进程。
 */
public final class CommandTasksTool implements Tool.ParallelReadOnly {
    public static final String NAME = "command_tasks";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 128;
    private static final int COMMAND_MAX_CHARS = 120;

    private final CommandTaskManager tasks;

    public CommandTasksTool(CommandTaskManager tasks) {
        this.tasks = Objects.requireNonNull(tasks);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(
            Map.of("includeFinished", ToolSchemas.bool("是否连已结束的任务一起列出，默认 true"), "limit", Map.of("type", "integer",
                "minimum", 1, "maximum", MAX_LIMIT, "description", "最多列出多少条，默认 " + DEFAULT_LIMIT + "，最大 " + MAX_LIMIT)),
            List.of());
    }

    @Override
    public String execute(Map<String, Object> args) {
        boolean includeFinished = ToolSupport.boolArg(args, "includeFinished", true);
        int limit = ToolSupport.clampedIntArg(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        List<CommandTaskManager.Task> shown = tasks.listAll().stream()
            .filter(task -> includeFinished || CommandTaskStatus.active(task.status())).limit(limit).toList();
        if (shown.isEmpty()) {
            return "本机没有" + (includeFinished ? "" : "运行中的") + "命令任务。";
        }
        long running = shown.stream().filter(task -> CommandTaskStatus.active(task.status())).count();
        StringBuilder result = new StringBuilder();
        result.append("共 ").append(shown.size()).append(" 条命令任务，其中运行中 ").append(running).append(" 条");
        if (shown.size() >= limit) {
            result.append("（已截断，调大 limit 继续看）");
        }
        result.append('\n');
        result.append("任务ID\t会话\t状态\t后台\tPID\t工作目录\t命令\n");
        for (CommandTaskManager.Task task : shown) {
            result.append(task.taskId()).append('\t').append(task.sessionId()).append('\t').append(task.status())
                .append('\t').append(task.background()).append('\t').append(task.pid() == null ? "" : task.pid())
                .append('\t').append(task.workdir()).append('\t').append(command(task.command())).append('\n');
        }
        result.append("\n不属于当前会话的任务：查状态、读日志、停止时把该行的会话作为 ownerSessionId 传进去。")
            .append("服务重启后仍在运行的任务一律标为 INTERRUPTED，那是旧进程所有权已失效，不要按旧 PID 操作。");
        return result.toString();
    }

    private static String command(String command) {
        return command.length() <= COMMAND_MAX_CHARS ? command : command.substring(0, COMMAND_MAX_CHARS) + "…";
    }
}
