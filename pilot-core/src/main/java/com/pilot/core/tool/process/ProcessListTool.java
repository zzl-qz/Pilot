package com.pilot.core.tool.process;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.tool.ToolSupport;

/**
 * 结构化进程视图：把本机正在运行的进程列成稳定字段，供模型观察端口占用、确认残留进程。
 *
 * <p>只读。看不到的字段留空，不猜；结束或接管别人的进程不在这个工具范围内，
 * 那需要操作系统权限，也应当由用户明确授权。
 */
public final class ProcessListTool implements Tool.ParallelReadOnly {
    public static final String NAME = "process_list";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int COMMAND_MAX_CHARS = 160;
    /** 命令行里常见的密码类参数，值一律遮盖，避免把凭据带进上下文。 */
    private static final Pattern SENSITIVE = Pattern.compile(
        "(?i)(--?(?:password|passwd|token|secret|api[-_]?key|access[-_]?key)[=\\s]+)(\"[^\"]*\"|'[^']*'|\\S+)");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(Map.of(
            "filter", ToolSchemas.string("可选：按命令行或 PID 的子串过滤，不区分大小写"),
            "limit", Map.of("type", "integer", "minimum", 1, "maximum", MAX_LIMIT,
                "description", "最多列出多少条，默认 " + DEFAULT_LIMIT + "，最大 " + MAX_LIMIT)), List.of());
    }

    @Override
    public String execute(Map<String, Object> args) {
        int limit = limit(args);
        String filter = filter(args);
        List<String[]> rows = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(handle -> {
            String command = redact(commandLine(handle));
            String pid = String.valueOf(handle.pid());
            if (!filter.isEmpty() && !pid.contains(filter) && !command.toLowerCase(Locale.ROOT).contains(filter)) {
                return;
            }
            rows.add(new String[] { pid, parentOf(handle), startOf(handle), userOf(handle), truncate(command) });
        });
        rows.sort(Comparator.comparingLong(row -> parsePid(row[0])));
        StringBuilder result = new StringBuilder();
        result.append("进程视图：可见 ").append(rows.size()).append(" 个");
        if (rows.size() > limit) {
            result.append("，按 PID 升序只列前 ").append(limit).append(" 个（调大 limit 或加 filter 继续看）");
        } else if (!filter.isEmpty()) {
            result.append("（filter=").append(filter).append("）");
        }
        result.append('\n');
        result.append("PID\t父PID\t启动时间\t用户\t命令\n");
        rows.stream().limit(limit).forEach(row -> result.append(String.join("\t", row)).append('\n'));
        if (rows.isEmpty()) {
            result.append("没有匹配的进程。\n");
        }
        return result.toString();
    }

    private static int limit(Map<String, Object> args) {
        Object value = args == null ? null : args.get("limit");
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            long parsed = value instanceof Number number ? number.longValue() : Long.parseLong(value.toString().strip());
            return (int) Math.min(MAX_LIMIT, Math.max(1, parsed));
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    private static String filter(Map<String, Object> args) {
        String value = ToolSupport.stringArg(args, "filter");
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    /** 命令行拿不到（权限不足或系统进程）时留空标记，不冒充空字符串。 */
    private static String commandLine(ProcessHandle handle) {
        var info = handle.info();
        String command = info.command().orElse("");
        if (command.isEmpty()) {
            return "<命令不可见>";
        }
        String arguments = info.arguments().map(parts -> String.join(" ", parts)).orElse("");
        return arguments.isEmpty() ? command : command + " " + arguments;
    }

    private static String parentOf(ProcessHandle handle) {
        return handle.parent().map(parent -> String.valueOf(parent.pid())).orElse("");
    }

    private static String startOf(ProcessHandle handle) {
        return handle.info().startInstant().map(Instant::toString).orElse("");
    }

    private static String userOf(ProcessHandle handle) {
        return handle.info().user().orElse("");
    }

    static String redact(String command) {
        return SENSITIVE.matcher(command).replaceAll(match -> match.group(1) + "***");
    }

    private static String truncate(String command) {
        return command.length() <= COMMAND_MAX_CHARS ? command : command.substring(0, COMMAND_MAX_CHARS) + "…";
    }

    private static long parsePid(String pid) {
        try {
            return Long.parseLong(pid);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}