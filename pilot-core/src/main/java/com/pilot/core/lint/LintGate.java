package com.pilot.core.lint;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 收尾时的规范检查。开关关掉就什么都不跑。
 * 打开时发现仓库自己的 lint，只把命中本次改动文件的输出打回给模型。
 */
public final class LintGate {
    private static final int MAX_PROMPT_CHARS = 2_000;

    private final boolean enabled;
    private final LintRunner runner;

    public LintGate(boolean enabled, LintRunner runner) {
        this.enabled = enabled;
        this.runner = runner == null ? ProcessLintRunner.INSTANCE : runner;
    }

    public static LintGate enabled() {
        return new LintGate(true, ProcessLintRunner.INSTANCE);
    }

    public static LintGate disabled() {
        return new LintGate(false, ProcessLintRunner.INSTANCE);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LintAssessment assess(Path workspace, Collection<String> changedFiles) {
        if (!enabled) {
            return LintAssessment.disabled();
        }
        List<String> sources = productionSources(changedFiles);
        if (sources.isEmpty()) {
            return LintAssessment.clean();
        }
        ProjectLint lint = ProjectLint.discover(workspace);
        if (lint.status() == ProjectLint.Status.DISABLED) {
            return LintAssessment.disabled();
        }
        if (!lint.ready()) {
            return LintAssessment.unconfigured();
        }
        String command = lint.render(sources);
        LintRunner.LintResult result = runner.run(workspace, command, lint.timeoutSeconds());
        if (!result.started()) {
            return LintAssessment.failed(command, result.output().isBlank() ? "命令没有启动" : result.output());
        }
        if (result.timedOut()) {
            return LintAssessment.failed(command, "超过 " + lint.timeoutSeconds() + " 秒被终止");
        }
        if (result.exitCode() == 0) {
            return LintAssessment.clean();
        }
        if (toolMissing(result)) {
            return LintAssessment.failed(command, clip(result.output()));
        }
        String relevant = filter(result.output(), sources);
        if (relevant.isBlank()) {
            return LintAssessment.clean();
        }
        return LintAssessment.findings(lint.source(), command, relevant);
    }

    private static List<String> productionSources(Collection<String> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String path : changedFiles) {
            if (SourceFiles.isProductionSource(path)) {
                out.add(path.replace('\\', '/'));
            }
        }
        return List.copyOf(out);
    }

    static String filter(String output, List<String> sources) {
        if (output == null || output.isBlank() || sources.isEmpty()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (String raw : output.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (mentions(line, sources) && kept.length() < MAX_PROMPT_CHARS) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString().strip();
    }

    private static boolean mentions(String line, List<String> sources) {
        String lower = line.toLowerCase(Locale.ROOT).replace('\\', '/');
        for (String source : sources) {
            String norm = source.toLowerCase(Locale.ROOT);
            if (lower.contains(norm)) {
                return true;
            }
        }
        return false;
    }

    private static boolean toolMissing(LintRunner.LintResult result) {
        if (result.exitCode() == 127) {
            return true;
        }
        String lower = result.output().toLowerCase(Locale.ROOT);
        return lower.contains("not found") || lower.contains("no such file") || lower.contains("不是内部或外部命令")
            || lower.contains("无法将") && lower.contains("识别为");
    }

    private static String clip(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.length() <= 500 ? stripped : stripped.substring(0, 500);
    }

    public record LintAssessment(Kind kind, String prompt, String reason) {
        public enum Kind {
            DISABLED, UNCONFIGURED, CLEAN, FINDINGS, FAILED
        }

        public boolean needsFix() {
            return kind == Kind.FINDINGS || kind == Kind.FAILED;
        }

        static LintAssessment disabled() {
            return new LintAssessment(Kind.DISABLED, "", "");
        }

        static LintAssessment unconfigured() {
            return new LintAssessment(Kind.UNCONFIGURED, "", "");
        }

        static LintAssessment clean() {
            return new LintAssessment(Kind.CLEAN, "", "");
        }

        static LintAssessment findings(String source, String command, String output) {
            String prompt = "本轮改动没有通过仓库自己的 lint（" + source + "，命令：" + command + "）。\n"
                + "下面只保留命中本次改动文件的输出，存量问题不要顺手去改：\n" + output + "\n按这个 lint 的规则改本次改动的文件，改完会再跑一次。不要为了让输出变空去关掉规则或删除检查。";
            return new LintAssessment(Kind.FINDINGS, prompt, "仓库 lint 未通过，已把本次改动的问题打回");
        }

        static LintAssessment failed(String command, String detail) {
            String prompt = "准备按仓库配置运行 lint，但命令没有跑成：" + command + "。\n" + detail
                + "\n请改用这个仓库里实际能执行的检查命令，或在 .pilot/settings.json 写入 quality.command。不要把没跑成说成已通过。";
            return new LintAssessment(Kind.FAILED, prompt, "仓库 lint 命令没有跑成");
        }
    }
}
