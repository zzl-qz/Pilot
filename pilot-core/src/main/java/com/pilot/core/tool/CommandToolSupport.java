package com.pilot.core.tool;

import java.util.Map;
import java.util.regex.Pattern;

/** 命令工具共用的状态表示；日志内容不参与判断验证是否通过。 */
final class CommandToolSupport {
    private static final Pattern TASK_ID = Pattern.compile("(?m)^任务ID: ([A-Za-z0-9_-]+)$");
    private static final Pattern STATUS = Pattern.compile("(?m)^状态: ([A-Z_]+)$");

    private CommandToolSupport() { }

    static boolean running(CommandTaskManager.Task task) {
        return task != null && ("RUNNING".equals(task.status()) || "STOPPING".equals(task.status()));
    }

    static String render(CommandTaskManager.Task task) {
        return render(task, true);
    }

    static String render(CommandTaskManager.Task task, boolean reportFailure) {
        if (task == null) return "ERROR: 当前会话没有该命令任务";
        StringBuilder result = new StringBuilder();
        if (reportFailure && !running(task) && !"COMPLETED".equals(task.status())) {
            result.append("ERROR: 命令未成功完成（").append(task.status()).append("）\n");
        }
        result.append("任务ID: ").append(task.taskId()).append('\n')
                .append("状态: ").append(task.status()).append('\n')
                .append("命令: ").append(task.command()).append('\n')
                .append("工作目录: ").append(task.workdir()).append('\n')
                .append("后台运行: ").append(task.background()).append('\n')
                .append("超时秒数: ").append(task.timeoutSeconds()).append('\n');
        if (task.pid() != null) result.append("PID: ").append(task.pid()).append('\n');
        if (!running(task) && task.exitCode() != null) result.append("退出码: ").append(task.exitCode()).append('\n');
        if (task.reason() != null && !task.reason().isBlank()) result.append("原因: ").append(task.reason()).append('\n');
        result.append("日志: command_logs(taskId=\"").append(task.taskId()).append("\", offset=0)");
        return result.toString();
    }

    static String checkSummary(CommandTaskManager manager, String sessionId, String result) {
        if (result == null) return null;
        var id = TASK_ID.matcher(result);
        var status = STATUS.matcher(result);
        if (!id.find() || !status.find()) return null;
        // 查询与渲染之间即使进程刚好结束，返回的 RUNNING 也不算验证已完成。
        if ("RUNNING".equals(status.group(1)) || "STOPPING".equals(status.group(1))) return null;
        var task = manager.get(sessionId, id.group(1));
        if (task == null || running(task) || task.exitCode() == null || !task.status().equals(status.group(1))) return null;
        String command = ToolSupport.oneLine(task.command(), 90);
        if ("COMPLETED".equals(task.status()) || "FAILED".equals(task.status())) {
            return command + " → exit " + task.exitCode();
        }
        // 超时或取消不是成功验证，即使恰好捕获到退出码 0，也不能满足收尾门。
        return command + " → " + task.status() + "（进程退出码 " + task.exitCode() + "）";
    }

    static long integerArg(Map<String, Object> args, String name, long fallback, long min, long max) {
        Object value = args == null ? null : args.get(name);
        if (value == null) return fallback;
        try {
            long parsed;
            if (value instanceof Number number) {
                double numeric = number.doubleValue();
                if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)
                        || numeric < min || numeric > max) throw new NumberFormatException();
                parsed = number.longValue();
            } else parsed = Long.parseLong(value.toString().strip());
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 必须是 " + min + " 到 " + max + " 之间的整数");
        }
    }
}
