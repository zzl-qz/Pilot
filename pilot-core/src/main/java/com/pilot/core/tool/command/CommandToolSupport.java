package com.pilot.core.tool.command;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pilot.core.tool.ToolResults;
import com.pilot.core.util.WireEnum;

/** 命令工具共用的状态表示；日志内容不参与判断验证是否通过。 */
final class CommandToolSupport {
    private static final Pattern TASK_ID = Pattern.compile("(?m)^任务ID: ([A-Za-z0-9_-]+)$");
    private static final Pattern STATUS = Pattern.compile("(?m)^状态: ([A-Z_]+)$");
    /** 命令摘要保留上限；超长时保留首尾 token，防止把末尾 test/verify 等验证目标截掉导致收尾门误判。 */
    private static final int CHECK_COMMAND_MAX_CHARS = 240;
    private static final int CHECK_COMMAND_HEAD_CHARS = 150;
    private static final int CHECK_COMMAND_TAIL_CHARS = 70;
    private static final int SANDBOX_LOG_TAIL_CHARS = 8_000;
    private static final int MAC_SEATBELT_EX_OSERR = 71;
    static final String NESTED_SANDBOX_HINT =
        "[环境提示] 检测到嵌套 OS 沙箱失败（当前命令已在系统沙箱内运行，macOS Seatbelt / Linux bwrap 禁止在沙箱内再次创建子沙箱），"
            + "这属于外层沙箱环境限制而非业务代码缺陷。请优先运行与本次改动直接相关的定向单测并明确区分环境限制，不要反复排查无关的沙箱隔离测试。";

    private CommandToolSupport() {
    }

    static boolean running(CommandTaskManager.Task task) {
        return task != null && CommandTaskStatus.active(task.status());
    }

    static String render(CommandTaskManager.Task task) {
        return render(task, true);
    }

    static String render(CommandTaskManager.Task task, boolean reportFailure) {
        if (task == null) {
            return "ERROR: 当前会话没有该命令任务";
        }
        StringBuilder result = new StringBuilder();
        if (reportFailure && !running(task) && !CommandTaskStatus.COMPLETED.is(task.status())) {
            result.append("ERROR: 命令未成功完成（").append(task.status()).append("）\n");
        }
        result.append("任务ID: ").append(task.taskId()).append('\n').append("状态: ").append(task.status()).append('\n')
            .append("命令: ").append(task.command()).append('\n').append("工作目录: ").append(task.workdir()).append('\n')
            .append("后台运行: ").append(task.background()).append('\n').append("超时秒数: ").append(task.timeoutSeconds())
            .append('\n');
        if (task.pid() != null) {
            result.append("PID: ").append(task.pid()).append('\n');
        }
        if (!running(task) && task.exitCode() != null) {
            result.append("退出码: ").append(task.exitCode()).append('\n');
        }
        if (task.reason() != null && !task.reason().isBlank()) {
            result.append("原因: ").append(task.reason()).append('\n');
        }
        result.append("日志: command_logs(taskId=\"").append(task.taskId()).append("\", offset=0)");
        return result.toString();
    }

    static String checkSummary(CommandTaskManager manager, String sessionId, String result) {
        if (result == null) {
            return null;
        }
        Matcher id = TASK_ID.matcher(result);
        Matcher status = STATUS.matcher(result);
        if (!id.find() || !status.find()) {
            return null;
        }
        // 查询与渲染之间即使进程刚好结束，返回的 RUNNING 也不算验证已完成。
        if (CommandTaskStatus.active(status.group(1))) {
            return null;
        }
        CommandTaskManager.Task task = manager.get(sessionId, id.group(1));
        if (task == null || running(task) || task.exitCode() == null || !task.status().equals(status.group(1))) {
            return null;
        }
        String command = summarizeCommand(task.command());
        if (ToolResults.isChangeTrackingIncomplete(result)) {
            return command + " → CHANGE_TRACKING_INCOMPLETE";
        }
        if (WireEnum.in(task.status(), CommandTaskStatus.COMPLETED, CommandTaskStatus.FAILED)) {
            return command + " → exit " + task.exitCode();
        }
        // 超时或取消不是成功验证，即使恰好捕获到退出码 0，也不能满足收尾门。
        return command + " → " + task.status() + "（进程退出码 " + task.exitCode() + "）";
    }

    /** 保留完整可执行名与尾部目标（如 `mvnw -pl ... -Dtest=... test`），避免 90 字符硬截断裁掉末尾验证目标。 */
    static String summarizeCommand(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String single = raw.replaceAll("\\s+", " ").strip();
        if (single.length() <= CHECK_COMMAND_MAX_CHARS) {
            return single;
        }
        String head = single.substring(0, CHECK_COMMAND_HEAD_CHARS).stripTrailing();
        int tailStart = Math.max(CHECK_COMMAND_HEAD_CHARS, single.length() - CHECK_COMMAND_TAIL_CHARS);
        int boundary = single.indexOf(' ', tailStart);
        String tail = (boundary > 0 && boundary + 1 < single.length() ? single.substring(boundary + 1)
            : single.substring(tailStart)).stripLeading();
        return head + " … " + tail;
    }

    /** 确定性识别 macOS Seatbelt / Linux bwrap 嵌套沙箱失败，避免模型误将外层沙箱限制当成业务代码缺陷反复排错。 */
    static String nestedSandboxHint(String output, Integer exitCode) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String lower = output.toLowerCase(Locale.ROOT);
        boolean macSeatbelt = lower.contains("sandbox_apply: operation not permitted") || (lower
            .contains("sandbox-exec")
            && (lower.contains("operation not permitted") || Integer.valueOf(MAC_SEATBELT_EX_OSERR).equals(exitCode)));
        boolean linuxBwrap = lower.contains("bwrap:") && (lower.contains("no permissions to creating new namespace")
            || lower.contains("operation not permitted") || lower.contains("setting up uid map"));
        return macSeatbelt || linuxBwrap ? NESTED_SANDBOX_HINT : "";
    }

    static String nestedSandboxHint(CommandTaskManager tasks, String sessionId, CommandTaskManager.Task task) {
        if (tasks == null || task == null || running(task) || CommandTaskStatus.COMPLETED.is(task.status())) {
            return "";
        }
        long total = tasks.logLength(sessionId, task.taskId());
        if (total <= 0L) {
            return "";
        }
        long offset = Math.max(0L, total - SANDBOX_LOG_TAIL_CHARS);
        CommandTaskManager.LogChunk tail = tasks.logs(sessionId, task.taskId(), offset, SANDBOX_LOG_TAIL_CHARS + 200);
        if (tail == null) {
            return "";
        }
        return nestedSandboxHint(tail.text(), task.exitCode());
    }

    static long integerArg(Map<String, Object> args, String name, long fallback, long min, long max) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            long parsed;
            if (value instanceof Number number) {
                double numeric = number.doubleValue();
                if (!Double.isFinite(numeric) || numeric != Math.rint(numeric) || numeric < min || numeric > max) {
                    throw new NumberFormatException();
                }
                parsed = number.longValue();
            } else {
                parsed = Long.parseLong(value.toString().strip());
            }
            if (parsed < min || parsed > max) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 必须是 " + min + " 到 " + max + " 之间的整数");
        }
    }
}
