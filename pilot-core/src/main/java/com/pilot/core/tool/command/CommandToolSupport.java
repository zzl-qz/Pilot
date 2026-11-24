package com.pilot.core.tool.command;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.pilot.core.tool.ToolResults;
import com.pilot.core.tool.ToolSupport;
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
    private static final int FAILURE_LOG_TAIL_CHARS = 2_000;
    private static final int MAC_SEATBELT_EX_OSERR = 71;
    private static final int MAX_REPORT_FILES = 24;
    private static final int MAX_REPORT_READ_BYTES = 256 * 1024;
    private static final long REPORT_MTIME_SKEW_MILLIS = 2_000L;
    private static final Pattern TEST_REPORT_FAIL_ATTR = Pattern.compile("(?:failures|errors)=\"[1-9][0-9]*\"");
    private static final Pattern CONSOLE_TEST_FAILURE = Pattern.compile(
        "(?i)(?:surefire-reports|failsafe-reports|\\[ERROR\\]\\s+(?:Failures|Errors):|Failures:\\s*[1-9]|Errors:\\s*[1-9])");
    /** command_status / command_logs / stop_command 共用的跨会话归属参数。 */
    static final Map<String, Object> OWNER_SESSION_PARAMETER =
        Map.of("type", "string", "description", "可选：该任务所属会话 ID（command_tasks 输出里的会话列）。只在操作不属于当前会话的任务时传；不传就只找当前会话。");
    static final String NESTED_SANDBOX_HINT =
        ToolResults.NESTED_SANDBOX_HINT_PREFIX + "（当前命令已在系统沙箱内运行，macOS Seatbelt / Linux bwrap 禁止在沙箱内再次创建子沙箱），"
            + "这属于外层沙箱环境限制而非业务代码缺陷。请优先运行与本次改动直接相关的定向单测并明确区分环境限制，不要反复排查无关的沙箱隔离测试。";

    private CommandToolSupport() {
    }

    static boolean running(CommandTaskManager.Task task) {
        return task != null && CommandTaskStatus.active(task.status());
    }

    /**
     * command_logs、stop_command、command_input 共用的入口：取 taskId，交给具体动作，
     * 把管理器抛出的参数错误与状态错误统一成一行 ERROR 文本。
     * 动作返回 null 表示当前会话没有该任务，由调用方决定提示什么。
     */
    static String withTaskId(Map<String, Object> args, Function<String, String> action) {
        String id = ToolSupport.stringArg(args, "taskId");
        if (id == null) {
            return "ERROR: 缺少参数 taskId";
        }
        try {
            return action.apply(id);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** 读取跨会话归属参数；空串视为未传。 */
    static String ownerSession(Map<String, Object> args) {
        Object value = args == null ? null : args.get("ownerSessionId");
        String owner = value == null ? "" : value.toString().strip();
        return owner.isEmpty() ? null : owner;
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

    /** 确定性识别 macOS Seatbelt / Linux bwrap / Chromium 子进程嵌套沙箱失败，避免模型误将外层沙箱限制当成业务代码缺陷反复排错。 */
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
        boolean chromiumSandbox = lower.contains("sandbox initialization failed: operation not permitted")
            || (lower.contains("failed to initialize sandbox") && lower.contains("operation not permitted"))
            || (lower.contains("failed to move to new namespace") && lower.contains("operation not permitted"));
        return macSeatbelt || linuxBwrap || chromiumSandbox ? NESTED_SANDBOX_HINT : "";
    }

    static String nestedSandboxHint(CommandTaskManager tasks, CommandTaskManager.Task task) {
        return nestedSandboxHint(tasks, task, null);
    }

    /** ownerSessionId 非空时表示任务属于另一个会话，日志也要按那个会话去读。 */
    static String nestedSandboxHint(CommandTaskManager tasks, CommandTaskManager.Task task, String ownerSessionId) {
        if (tasks == null || task == null || running(task) || CommandTaskStatus.COMPLETED.is(task.status())) {
            return "";
        }
        String session = ownerSessionId == null ? task.sessionId() : ownerSessionId;
        long total = tasks.logLength(session, task.taskId());
        if (total <= 0L) {
            return "";
        }
        long offset = Math.max(0L, total - SANDBOX_LOG_TAIL_CHARS);
        CommandTaskManager.LogChunk tail = tasks.logs(session, task.taskId(), offset, SANDBOX_LOG_TAIL_CHARS + 200);
        if (tail == null) {
            return "";
        }
        if (task.workdir() != null && !task.workdir().isBlank() && CONSOLE_TEST_FAILURE.matcher(tail.text()).find()) {
            Boolean allReportsSandboxBlocked = inspectFailingTestReports(Path.of(task.workdir()), task.startedAt());
            if (allReportsSandboxBlocked != null) {
                return allReportsSandboxBlocked ? NESTED_SANDBOX_HINT : "";
            }
        }
        return nestedSandboxHint(tail.text(), task.exitCode());
    }

    /** 后台命令非正常结束时附带尾部输出，让模型在 command_status 中直接看到失败原因，不必再额外翻一次 command_logs。 */
    static String failureLogTail(CommandTaskManager tasks, CommandTaskManager.Task task, String ownerSessionId) {
        if (tasks == null || task == null || running(task) || CommandTaskStatus.COMPLETED.is(task.status())) {
            return "";
        }
        String session = ownerSessionId == null ? task.sessionId() : ownerSessionId;
        long total = tasks.logLength(session, task.taskId());
        if (total <= 0L) {
            return "";
        }
        long offset = Math.max(0L, total - FAILURE_LOG_TAIL_CHARS);
        CommandTaskManager.LogChunk chunk = tasks.logs(session, task.taskId(), offset, FAILURE_LOG_TAIL_CHARS + 200);
        if (chunk == null) {
            return "";
        }
        String text = chunk.text().replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.isEmpty()) {
            return "";
        }
        String prefix = offset > 0L ? "…（已省略前 " + offset + " 字符，完整日志见上方 command_logs）\n" : "";
        return "\n输出:\n" + prefix + text;
    }

    /**
     * Maven/Gradle 测试 runner 常把子进程 stderr 收进 XML 报告而不在主控制台输出。
     * 仅当本次命令期间生成的全部失败测试报告都命中嵌套沙箱特征时才提示环境限制；
     * 只要有任意一份失败报告属于普通业务失败，立即返回空串，绝不掩盖真实缺陷。
     */
    static String nestedSandboxHintFromTestReports(Path workdir, Instant startedAt) {
        return Boolean.TRUE.equals(inspectFailingTestReports(workdir, startedAt)) ? NESTED_SANDBOX_HINT : "";
    }

    /**
     * @return {@code Boolean.TRUE} 表示所有新失败报告都因嵌套沙箱失败；{@code Boolean.FALSE} 表示存在非沙箱真实失败；
     *         {@code null} 表示未发现新失败 XML 报告，由调用方回退到控制台输出检测。
     */
    private static Boolean inspectFailingTestReports(Path workdir, Instant startedAt) {
        if (workdir == null || !Files.isDirectory(workdir)) {
            return null;
        }
        long minMtime = startedAt == null ? 0L : Math.max(0L, startedAt.toEpochMilli() - REPORT_MTIME_SKEW_MILLIS);
        List<Path> reportDirs = findTestReportDirs(workdir);
        int failingReports = 0;
        for (Path dir : reportDirs) {
            List<Path> xmls = recentXmlReports(dir, minMtime);
            for (Path xml : xmls) {
                String text = readBounded(xml);
                if (!TEST_REPORT_FAIL_ATTR.matcher(text).find()) {
                    continue;
                }
                failingReports++;
                if (nestedSandboxHint(text, null).isEmpty()) {
                    return Boolean.FALSE;
                }
            }
        }
        return failingReports > 0 ? Boolean.TRUE : null;
    }

    private static List<Path> findTestReportDirs(Path workdir) {
        List<Path> found = new ArrayList<>();
        collectTestReportDirs(workdir, found);
        try (Stream<Path> children = Files.list(workdir)) {
            children.filter(Files::isDirectory).filter(p -> !isIgnoredSubdir(p.getFileName()))
                .forEach(child -> collectTestReportDirs(child, found));
        } catch (IOException | RuntimeException ignored) {
            return found;
        }
        return found;
    }

    private static void collectTestReportDirs(Path moduleRoot, List<Path> found) {
        for (Path candidate : List.of(moduleRoot.resolve("target").resolve("surefire-reports"),
            moduleRoot.resolve("target").resolve("failsafe-reports"),
            moduleRoot.resolve("build").resolve("test-results"))) {
            if (Files.isDirectory(candidate)) {
                found.add(candidate);
            }
        }
    }

    private static boolean isIgnoredSubdir(Path name) {
        if (name == null) {
            return true;
        }
        String n = name.toString();
        return n.startsWith(".") || "node_modules".equals(n) || "target".equals(n) || "build".equals(n);
    }

    private static List<Path> recentXmlReports(Path dir, long minMtime) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir, 2)) {
            walk.filter(Files::isRegularFile).filter(p -> {
                Path fn = p.getFileName();
                return fn != null && fn.toString().startsWith("TEST-") && fn.toString().endsWith(".xml");
            }).filter(p -> modifiedAtLeast(p, minMtime)).limit(MAX_REPORT_FILES).forEach(files::add);
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
        return files;
    }

    private static boolean modifiedAtLeast(Path file, long minMtime) {
        if (minMtime <= 0L) {
            return true;
        }
        try {
            return Files.getLastModifiedTime(file).toMillis() >= minMtime;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String readBounded(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(MAX_REPORT_READ_BYTES), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
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
