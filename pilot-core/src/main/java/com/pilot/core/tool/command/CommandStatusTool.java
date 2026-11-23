package com.pilot.core.tool.command;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.pilot.core.message.ToolCall;
import com.pilot.core.message.ToolNames;
import com.pilot.core.tool.ProducesCheck;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolResult;
import com.pilot.core.tool.ToolResults;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;

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
        return ToolNames.COMMAND_STATUS;
    }

    public boolean readOnly() {
        return true;
    }

    public Map<String, Object> parameters() {
        return ToolSchemas.object(Map.of("taskId", ToolSchemas.string("run_command 返回的任务 ID"), "waitSeconds",
            Map.of("type", "integer", "minimum", 0, "maximum", 60, "description",
                "最多等待多少秒再返回，默认 0，最大 60。长测试请一次等 20 到 30 秒，命令提前结束会立刻返回"),
            "ownerSessionId", CommandToolSupport.OWNER_SESSION_PARAMETER), List.of("taskId"));
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
            // 等待和变更核对分开计时。以前共用 5 秒，长测试每 5 秒就要把模型叫醒一次，核对时间也被等光。
            long wait = CommandToolSupport.integerArg(args, "waitSeconds", 0, 0, 60);
            String owner = CommandToolSupport.ownerSession(args);
            CommandTaskManager.Task task = tasks.get(sessionId, id, owner);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(wait);
            while (CommandToolSupport.running(task) && System.nanoTime() < deadline) {
                Thread.sleep(100);
                task = tasks.get(sessionId, id, owner);
            }
            String rendered = CommandToolSupport.render(task);
            String sandboxHint = CommandToolSupport.nestedSandboxHint(tasks, task);
            if (!sandboxHint.isEmpty()) {
                rendered = sandboxHint + "\n" + rendered;
            }
            if (task == null || CommandToolSupport.running(task) || task.changeTracking() == null
                || !task.changeTracking().active()) {
                return ToolResult.text(rendered);
            }
            long leftNanos = TimeUnit.SECONDS.toNanos(5);
            CommandChangeTracker.Result result;
            CommandTaskManager.Task finished = task;
            try {
                result = CompletableFuture.supplyAsync(() -> changes.finish(finished.changeTracking())).get(leftNanos,
                    TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                return ToolResult.text(rendered + "\n变更核对超过 5 秒还没结束，这次不把结果算作验证。");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                result = new CommandChangeTracker.Result(List.of(), false, String.valueOf(cause.getMessage()));
            }
            if (!result.complete()) {
                rendered = ToolResults.CHANGE_TRACKING_INCOMPLETE + "；不得据此宣称验证通过：" + result.warning() + "\n" + rendered;
            } else if (!result.warning().isBlank()) {
                rendered = rendered + "\n\n" + ToolResults.CHANGE_TRACKING_PARTIAL + "（" + result.warning() + "）";
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
