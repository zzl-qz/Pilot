package com.pilot.core.lint;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.pilot.core.util.FileNames;

/**
 * 收尾时的规范检查。开关关掉就什么都不跑。
 * 打开时发现仓库自己的 lint，只把命中本次改动文件的输出打回给模型。
 */
public final class LintGate {
    public enum FailurePolicy {
        /** 规范没过也交付，把结果写进最终答复。 */
        PASS,
        /** 先要求改。同一问题到次数上限，并且测试或构建已通过，才记下并放行。 */
        RETRY,
        /** 规范没过就停止，不标成完成。 */
        STOP
    }

    private static final int MAX_PROMPT_CHARS = 2_000;
    /** 同一条诊断重复这么多次，就停止打补丁。 */
    public static final int SAME_FAILURE_LIMIT = 3;
    /** 改动后的 lint 累计未通过这么多次，也停止。 */
    public static final int TOTAL_FAILURE_LIMIT = 5;

    private final boolean enabled;
    private final LintRunner runner;
    private final FailurePolicy policy;
    private String releaseNote = "";
    private int findingRounds;
    private int sameCount;
    private String fingerprint = "";
    private boolean locked;
    private ProjectLint frozen;
    private String definition = "";
    private Function<String, String> originalText = path -> null;

    public void setOriginalText(Function<String, String> reader) {
        this.originalText = reader == null ? path -> null : reader;
    }

    public void lock(Path workspace) {
        if (!enabled || locked) {
            return;
        }
        locked = true;
        frozen = ProjectLint.discover(workspace);
        definition = ProjectLint.digest(workspace, frozen);
    }

    public LintGate(boolean enabled, LintRunner runner) {
        this(enabled, runner, FailurePolicy.PASS);
    }

    public LintGate(boolean enabled, LintRunner runner, FailurePolicy policy) {
        this.enabled = enabled;
        this.runner = runner == null ? ProcessLintRunner.INSTANCE : runner;
        this.policy = policy == null ? FailurePolicy.PASS : policy;
    }

    public static LintGate enabled() {
        return new LintGate(true, ProcessLintRunner.INSTANCE);
    }

    public static LintGate disabled() {
        return new LintGate(false, ProcessLintRunner.INSTANCE);
    }

    /** 规范没过时是否直接终止。stop 才终止。 */
    public boolean stopsOnFailure() {
        return policy == FailurePolicy.STOP;
    }

    /** 默认策略：先改，次数用尽且功能验证通过才放行。 */
    public boolean retriesOnFailure() {
        return policy == FailurePolicy.RETRY;
    }

    /** 最近一次没通过的规范检查。通过或没配 lint 时为空。 */
    public String releaseNote() {
        return releaseNote;
    }

    public LintAssessment assess(Path workspace, Collection<String> changedFiles) {
        return assess(workspace, changedFiles, true);
    }

    /**
     * @param countStyleRound 为 false 时只看结果，不占用“同一问题能改几次”的次数。功能还没验证通过时用这个。
     */
    public LintAssessment assess(Path workspace, Collection<String> changedFiles, boolean countStyleRound) {
        if (!enabled) {
            return LintAssessment.disabled();
        }
        LintAssessment guard = guard(workspace, changedFiles);
        if (guard != null) {
            return retain(guard);
        }
        List<String> sources = productionSources(changedFiles);
        if (sources.isEmpty()) {
            return retain(LintAssessment.clean());
        }
        LintAssessment merged;
        if (frozen == null || !frozen.ready()) {
            merged = withReuse(workspace, sources, builtin(workspace, sources));
        } else {
            merged = withReuse(workspace, sources, run(workspace, frozen, sources));
        }
        return retain(countStyleRound ? account(merged) : merged);
    }

    /**
     * 写文件刚结束时调用。只跑能限定到本次文件的命令（命令里有 {files}）。
     * 整仓命令留到收尾，避免每改一个文件就扫全库。
     */
    public String feedbackAfterEdit(Path workspace, Collection<String> changedFiles) {
        if (!enabled) {
            return "";
        }
        LintAssessment guard = guard(workspace, changedFiles);
        if (guard != null) {
            return guard.prompt();
        }
        List<String> sources = productionSources(changedFiles);
        if (sources.isEmpty()) {
            return "";
        }
        if (frozen == null || !frozen.ready()) {
            LintAssessment builtin = withReuse(workspace, sources, builtin(workspace, sources));
            return builtin.needsFix() ? builtin.prompt() : "";
        }
        if (!frozen.command().contains("{files}")) {
            LintAssessment local = account(withReuse(workspace, sources, LintAssessment.clean()));
            return local.needsFix() ? local.prompt() : "";
        }
        LintAssessment assessment = account(withReuse(workspace, sources, run(workspace, frozen, sources)));
        return assessment.needsFix() ? assessment.prompt() : "";
    }

    /**
     * 配置被改、或本次新加了抑制注释时，直接拦住。
     * 没有这类问题时返回 null，交给原来的 lint。
     */
    private LintAssessment guard(Path workspace, Collection<String> changedFiles) {
        lock(workspace);
        if (frozen != null && !definition.equals(ProjectLint.digest(workspace, frozen))) {
            return LintAssessment.tampered();
        }
        if (changedFiles != null) {
            for (String path : changedFiles) {
                if (ProjectLint.isProtectedConfig(path)) {
                    return LintAssessment.tampered();
                }
            }
        }
        String added = addedSuppressions(workspace, productionSources(changedFiles));
        if (!added.isBlank()) {
            return LintAssessment.suppressed(added);
        }
        return null;
    }

    private String addedSuppressions(Path workspace, List<String> sources) {
        StringBuilder out = new StringBuilder();
        for (String path : sources) {
            String before;
            try {
                before = originalText.apply(path);
            } catch (IllegalStateException noBaseline) {
                // 续跑时，上一轮写过的文件不在这一轮的写前快照里；没有基线就无从判断是否新增抑制，跳过这一条。
                continue;
            }
            String after = readCurrent(workspace, path);
            for (String line : SuppressionScan.added(before, after)) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(path).append(": ").append(line);
            }
        }
        return out.toString();
    }

    private static String readCurrent(Path workspace, String relative) {
        if (workspace == null || relative == null || relative.isBlank()) {
            return "";
        }
        Path file = workspace.resolve(relative).normalize();
        if (!file.startsWith(workspace.normalize())) {
            return "";
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try {
            String text = Files.readString(file);
            return text.length() <= 200_000 ? text : text.substring(0, 200_000);
        } catch (Exception e) {
            return "";
        }
    }

    private LintAssessment retain(LintAssessment assessment) {
        if (assessment.kind() == LintAssessment.Kind.CLEAN || assessment.kind() == LintAssessment.Kind.DISABLED
            || assessment.kind() == LintAssessment.Kind.UNCONFIGURED) {
            releaseNote = "";
            return assessment;
        }
        if (assessment.kind() == LintAssessment.Kind.FINDINGS || assessment.kind() == LintAssessment.Kind.FAILED
            || assessment.kind() == LintAssessment.Kind.BLOCKED) {
            releaseNote = assessment.prompt();
        }
        return assessment;
    }

    private LintAssessment builtin(Path workspace, List<String> sources) {
        String extra = builtinLines(workspace, sources);
        if (extra.isBlank()) {
            return LintAssessment.clean();
        }
        return LintAssessment.builtin(extra);
    }

    /**
     * 读改动前原文。续跑时上一轮写过的文件不在这一轮的写前快照里，没有基线按「新文件」处理，
     * 不让收尾检查因为缺一条快照整轮崩掉。
     */
    private String originalOrNull(String path) {
        try {
            return originalText.apply(path);
        } catch (IllegalStateException noBaseline) {
            return null;
        }
    }

    private String builtinLines(Path workspace, List<String> sources) {
        StringBuilder out = new StringBuilder();
        for (String path : sources) {
            for (String item : BuiltinLint.scan(path, originalOrNull(path), readCurrent(workspace, path))) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(item);
            }
        }
        return out.toString();
    }

    private LintAssessment withReuse(Path workspace, List<String> sources, LintAssessment primary) {
        String reuse = reuseText(workspace, sources);
        if (reuse.isBlank()) {
            return primary;
        }
        if (primary.kind() == LintAssessment.Kind.CLEAN || primary.kind() == LintAssessment.Kind.UNCONFIGURED) {
            return LintAssessment.reuse(reuse);
        }
        String prompt = primary.prompt().isBlank() ? reuse : primary.prompt() + "\n" + reuse;
        return new LintAssessment(primary.kind(), prompt, primary.reason(), primary.fingerprint() + "\n" + reuse);
    }

    private String reuseText(Path workspace, List<String> sources) {
        StringBuilder out = new StringBuilder();
        for (String path : sources) {
            for (String item : ReuseScan.scan(path, originalOrNull(path), readCurrent(workspace, path),
                siblings(workspace, path))) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(item);
            }
        }
        return out.toString();
    }

    private static List<String> siblings(Path workspace, String relative) {
        if (workspace == null || relative == null) {
            return List.of();
        }
        Path file = workspace.resolve(relative).normalize();
        if (!file.startsWith(workspace.normalize()) || file.getParent() == null) {
            return List.of();
        }
        String ext = FileNames.extension(relative);
        List<String> texts = new ArrayList<>();
        try (Stream<Path> list = Files.list(file.getParent())) {
            for (Path candidate : list.limit(24).toList()) {
                if (texts.size() >= 12 || candidate.equals(file)
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (candidate.toFile().length() > 48 * 1024) {
                    continue;
                }
                String name = candidate.getFileName().toString();
                if (!ext.equals(FileNames.extension(name)) || SourceFiles.isTestSource(name)) {
                    continue;
                }
                String text =
                    readCurrent(workspace, workspace.normalize().relativize(candidate.normalize()).toString());
                if (!text.isEmpty()) {
                    texts.add(text);
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return texts;
    }

    private LintAssessment run(Path workspace, ProjectLint lint, List<String> sources) {
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
        String relevant = filterToNewLines(result.output(), workspace, sources);
        if (relevant.isBlank()) {
            return LintAssessment.clean();
        }
        return LintAssessment.findings(lint.source(), command, relevant);
    }

    private LintAssessment account(LintAssessment raw) {
        if (raw.kind() == LintAssessment.Kind.CLEAN) {
            sameCount = 0;
            return raw;
        }
        if (raw.kind() != LintAssessment.Kind.FINDINGS) {
            return raw;
        }
        findingRounds++;
        if (raw.fingerprint().equals(fingerprint)) {
            sameCount++;
        } else {
            fingerprint = raw.fingerprint();
            sameCount = 1;
        }
        if (sameCount >= SAME_FAILURE_LIMIT || findingRounds >= TOTAL_FAILURE_LIMIT) {
            if (policy == FailurePolicy.RETRY) {
                String prompt = raw.prompt() + "\n同一类问题已经到上限（相同 " + sameCount + " 次，累计 " + findingRounds
                    + " 次）。测试或构建通过就记下并放行；没通过就不能当完成。不要改 lint 规则，也不要加抑制注释。";
                return new LintAssessment(LintAssessment.Kind.FINDINGS, prompt, "规范问题已到次数上限", raw.fingerprint(), true);
            }
            return LintAssessment.blocked(sameCount, findingRounds, raw.fingerprint());
        }
        return raw;
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

    /**
     * 仓库 lint 会把同一文件里的旧问题也打出来。
     * 知道改动前原文时，只留指向本次新行的诊断。
     */
    private String filterToNewLines(String output, Path workspace, List<String> sources) {
        Map<String, Set<Integer>> addedLines = new HashMap<>();
        Map<String, String> addedBody = new HashMap<>();
        boolean scoped = false;
        for (String path : sources) {
            String before = originalOrNull(path);
            if (before == null) {
                continue;
            }
            scoped = true;
            String after = readCurrent(workspace, path);
            addedLines.put(path, BuiltinLint.addedLineNumbers(before, after));
            addedBody.put(path, String.join("\n", BuiltinLint.addedLines(before, after)));
        }
        if (!scoped) {
            return filter(output, sources);
        }
        if (output == null || output.isBlank()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (String raw : output.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || !mentions(line, sources) || kept.length() >= MAX_PROMPT_CHARS) {
                continue;
            }
            if (aboutNewLine(line, sources, addedLines, addedBody)) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString().strip();
    }

    private static boolean aboutNewLine(String line, List<String> sources, Map<String, Set<Integer>> addedLines,
        Map<String, String> addedBody) {
        String normalized = line.replace('\\', '/');
        boolean sawNumber = false;
        for (String source : sources) {
            Set<Integer> fresh = addedLines.get(source);
            if (fresh == null) {
                return true;
            }
            for (String name : names(source)) {
                Matcher matcher = Pattern.compile(Pattern.quote(name) + ":(\\d+)").matcher(normalized);
                while (matcher.find()) {
                    sawNumber = true;
                    if (fresh.contains(Integer.parseInt(matcher.group(1)))) {
                        return true;
                    }
                }
            }
            String body = addedBody.getOrDefault(source, "");
            if (!sawNumber && quotesNewText(normalized, body)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> names(String source) {
        int slash = source.lastIndexOf('/');
        if (slash < 0 || slash == source.length() - 1) {
            return List.of(source);
        }
        return List.of(source, source.substring(slash + 1));
    }

    private static boolean quotesNewText(String line, String body) {
        if (body.isBlank()) {
            return false;
        }
        Matcher numbers = Pattern.compile("(?<![\\w.])\\d{2,}(?![\\w.])").matcher(body);
        while (numbers.find()) {
            if (line.contains(numbers.group())) {
                return true;
            }
        }
        return false;
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

    public record LintAssessment(Kind kind, String prompt, String reason, String fingerprint, boolean exhausted) {
        public enum Kind {
            DISABLED, UNCONFIGURED, CLEAN, FINDINGS, FAILED, BLOCKED
        }

        public LintAssessment(Kind kind, String prompt, String reason, String fingerprint) {
            this(kind, prompt, reason, fingerprint, false);
        }

        public LintAssessment(Kind kind, String prompt, String reason) {
            this(kind, prompt, reason, "", false);
        }

        public boolean needsFix() {
            return kind == Kind.FINDINGS || kind == Kind.FAILED || kind == Kind.BLOCKED;
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

        static LintAssessment reuse(String output) {
            String prompt = "[lint 修正指令] 这次新增代码和已有实现重复，属于复制造轮子。\n" + output
                + "\n先复用已有方法。只有职责相同且合并后更清楚时才抽公共方法，不要为两三行再包一层。到次数上限且测试通过才放行。";
            return new LintAssessment(Kind.FINDINGS, prompt, "新增代码复制了已有实现", output);
        }

        static LintAssessment builtin(String output) {
            String prompt = "[lint 修正指令] 这个仓库没有自己的 lint，已用内置通用检查。它只看裸数字和重复字符串，不理解业务。\n" + output
                + "\n建议改的可以改；确认是一次性的值可以保留。到次数上限且测试通过才放行。";
            return new LintAssessment(Kind.FINDINGS, prompt, "仓库未配置 lint，内置通用检查未通过", output);
        }

        static LintAssessment findings(String source, String command, String output) {
            String prompt = "[lint 修正指令] 这次写入没有通过仓库 lint（" + source + "，命令：" + command + "）。\n"
                + "下一步只改下面点名的位置。不要改 lint 配置，不要处理没有出现在下面的旧文件。\n" + output + "\n改完会立刻再跑同一条命令。";
            return new LintAssessment(Kind.FINDINGS, prompt, "仓库 lint 未通过，已把修正指令附在写入结果上", output);
        }

        static LintAssessment failed(String command, String detail) {
            String prompt = "准备按仓库配置运行 lint，但命令没有跑成：" + command + "。\n" + detail
                + "\n请改用这个仓库里实际能执行的检查命令，或在 .pilot/settings.json 写入 quality.command。不要把没跑成说成已通过。";
            return new LintAssessment(Kind.FAILED, prompt, "仓库 lint 命令没有跑成");
        }

        static LintAssessment suppressed(String lines) {
            String prompt = "[lint 停止] 这次改动新增了抑制注释，用来让 lint 对这段代码闭嘴：\n" + lines
                + "\n删掉这些注释，按 lint 的诊断改代码。不能靠 eslint-disable、@ts-ignore、noqa、nolint 这类注释通过检查。这次不标成完成。";
            return new LintAssessment(Kind.BLOCKED, prompt, "新增抑制注释，已停止并标记为未完成");
        }

        static LintAssessment tampered() {
            String prompt = "[lint 停止] 仓库的 lint 命令或规则文件在这次运行中被改过。" + "不能靠关掉检查、把命令换成 exit 0、或改规则文件来通过。这次不标成完成。";
            return new LintAssessment(Kind.BLOCKED, prompt, "lint 配置被改过，已停止并标记为未完成");
        }

        static LintAssessment blocked(int sameCount, int findingRounds, String output) {
            String prompt = "[lint 停止] 同一类 lint 问题已经重复 " + sameCount + " 次，累计未通过 " + findingRounds
                + " 次。停止在这个结果上继续打补丁。最后一次诊断：\n" + output + "\n这次不标成完成，需要人看。不要宣称 lint 已通过，也不要删掉规则来换通过。";
            return new LintAssessment(Kind.BLOCKED, prompt, "同一 lint 问题重复出现，已停止并标记为未完成", output);
        }
    }
}
