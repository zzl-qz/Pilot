package com.pilot.core.tool.command;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.pilot.core.message.ToolNames;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;

/**
 * 向以 {@code interactive=true} 启动的命令写标准输入。
 *
 * <p>交互式程序（npm init、REPL、等密码提示）在管道 stdin 下会一直等，模型只有这里能推进它。
 * 写的是纯文本加管道，不是终端：程序若依赖 isatty（行编辑、彩色提示、进度条）仍会走非交互分支，
 * 需要真终端时得另配 PTY。
 */
public final class CommandInputTool implements Tool {
    private final CommandTaskManager tasks;
    private final String sessionId;

    public CommandInputTool(CommandTaskManager tasks, String sessionId) {
        this.tasks = Objects.requireNonNull(tasks);
        this.sessionId = Objects.requireNonNull(sessionId);
    }

    @Override
    public String name() {
        return ToolNames.COMMAND_INPUT;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(Map.of("taskId", ToolSchemas.string("run_command 返回的任务 ID"),
            "text", ToolSchemas.string("要写入标准输入的内容；不自动补换行，该回车就自己写 \\n"),
            "endInput", ToolSchemas.bool("写完关闭标准输入（发送 EOF），之后不能再写；默认 false"),
            "ownerSessionId", CommandToolSupport.OWNER_SESSION_PARAMETER), List.of("taskId"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        Object rawText = args == null ? null : args.get("text");
        if (rawText != null && !(rawText instanceof String)) {
            return "ERROR: text 必须是字符串";
        }
        Object rawEnd = args == null ? null : args.get("endInput");
        if (rawEnd != null && !(rawEnd instanceof Boolean)) {
            return "ERROR: endInput 必须是布尔值";
        }
        String text = rawText == null ? "" : (String)rawText;
        boolean endInput = Boolean.TRUE.equals(rawEnd);
        if (text.isEmpty() && !endInput) {
            return "ERROR: text 和 endInput 至少给一个，否则这次调用什么也没做";
        }
        return CommandToolSupport.withTaskId(args, id -> write(id, text, endInput, args));
    }

    private String write(String id, String text, boolean endInput, Map<String, Object> args) {
        CommandTaskManager.Task task =
            tasks.input(sessionId, id, text, endInput, CommandToolSupport.ownerSession(args));
        if (task == null) {
            return "ERROR: 当前会话没有该命令任务";
        }
        return "已写入 " + text.length() + " 字符" + (endInput ? "，并关闭标准输入" : "") + "\n"
            + CommandToolSupport.render(task, false) + "\n输入不会回显到日志；用 command_logs 看程序响应。";
    }
}