package com.pilot.core.tool.command;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 命令层的自保护：不允许从工具里杀掉正在服务本次运行的进程（以及它的父进程）。
 *
 * <p>这不是权限系统，而是“不要把自己弄死”的常识护栏。真正停止后台任务应使用
 * {@code stop_command}，它只停本会话启动并记录了 taskId 的进程。
 */
final class SelfProtection {
    private SelfProtection() {
    }

    /** 返回错误文本；允许执行时返回 {@code null}。 */
    static String check(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        String executable = bare(tokens.getFirst());
        List<String> args =
            tokens.subList(1, tokens.size()).stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
        if (Set.of("kill", "taskkill", "pkill", "killall", "stop-process").contains(executable)) {
            return "不要从命令里杀进程；停止后台任务请用 stop_command（只停本会话启动的 taskId）";
        }
        if ("node".equals(executable) && args.stream().anyMatch(arg -> arg.contains("process.kill")
            || arg.contains("taskkill") || arg.contains("stop-process") || arg.contains("child_process"))) {
            return "不要在 node -e 脚本里杀进程；停止后台任务请用 stop_command，避免误杀 Pilot 自身";
        }
        return null;
    }

    private static String bare(String executable) {
        String value = executable == null ? "" : executable.replace('\\', '/').toLowerCase(Locale.ROOT);
        int slash = value.lastIndexOf('/');
        value = slash >= 0 ? value.substring(slash + 1) : value;
        return value.replaceFirst("\\.(exe|cmd|bat)$", "");
    }
}
