package com.pilot.core.hook;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 确定性钩子：与 Claude Code / Codex 一致，把「编辑后自动格式化」「写文件前跑检查」这类
 * 每次都要做、又不该消耗模型注意力的事情从提示词里拿出来，交给配置决定。
 *
 * <p>配置来自工作区 {@code .pilot/settings.json} 与用户级 {@code ~/.pilot/settings.json}（工作区优先），
 * 格式兼容 Claude Code：
 * <pre>{@code
 * {
 *   "hooks": {
 *     "PostToolUse": [
 *       { "matcher": "edit_file|create_file|apply_patch",
 *         "hooks": [ { "type": "command", "command": "mvn -q spotless:apply" } ] }
 *     ],
 *     "PreToolUse": [
 *       { "matcher": "run_command", "command": "echo ok", "blockOnFailure": true }
 *     ]
 *   }
 * }
 * }</pre>
 *
 * <p>钩子命令来自配置文件而不是模型；输出默认只是回注给模型供参考，
 * 只有显式配置 {@code blockOnFailure} 才会阻止工具执行。
 */
public final class Hooks {

    public enum Event {
        PRE_TOOL_USE("PreToolUse"),
        POST_TOOL_USE("PostToolUse");

        private final String wireName;

        Event(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Event parse(String value) {
            if (value == null) return null;
            String normalized = value.strip().toLowerCase(Locale.ROOT).replace("_", "");
            for (Event event : values()) {
                if (event.wireName().toLowerCase(Locale.ROOT).equals(normalized)) return event;
            }
            return null;
        }
    }

    /** @param blockOnFailure PreToolUse 退出码非 0 时阻止工具执行 */
    public record Spec(String matcher, String command, long timeoutSeconds, boolean blockOnFailure) {
        public Spec {
            matcher = matcher == null || matcher.isBlank() ? "*" : matcher.strip();
            command = command == null ? "" : command.strip();
            timeoutSeconds = timeoutSeconds <= 0 ? 60 : Math.min(timeoutSeconds, 300);
        }

        /** {@code *} 全部命中，{@code a|b} 任一命中，末尾 {@code *} 做前缀匹配。 */
        public boolean matches(String toolName) {
            return Arrays.stream(matcher.split("\\|"))
                    .map(String::strip)
                    .filter(part -> !part.isBlank())
                    .anyMatch(part -> part.equals("*")
                            || part.equalsIgnoreCase(toolName)
                            || part.endsWith("*")
                            && toolName.regionMatches(true, 0, part, 0, part.length() - 1));
        }
    }

    /** @param blocked PreToolUse 钩子要求阻止；PostToolUse 恒为 false */
    public record Result(String output, boolean blocked) {
        public static final Result NONE = new Result("", false);
    }

    private static final int MAX_OUTPUT_CHARS = 8_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<Event, List<Spec>> specs;
    /** 钩子命令的工作目录：配置来自哪个工作区，就在哪个工作区跑。 */
    private final Path workspace;

    private Hooks(Map<Event, List<Spec>> specs, Path workspace) {
        this.specs = Map.copyOf(specs);
        this.workspace = workspace;
    }

    public static Hooks none() {
        return new Hooks(Map.of(), null);
    }

    /** 读取用户级与工作区级配置并合并；任何读取或格式问题都退化为「没有钩子」，不阻断运行。 */
    public static Hooks load(Path workspace) {
        Map<Event, List<Spec>> merged = new java.util.LinkedHashMap<>();
        Path userHome = Path.of(System.getProperty("user.home", "."));
        merge(merged, userHome.resolve(".pilot").resolve("settings.json"));
        if (workspace != null) merge(merged, workspace.resolve(".pilot").resolve("settings.json"));
        return merged.isEmpty() ? none() : new Hooks(merged, workspace);
    }

    private static void merge(Map<Event, List<Spec>> target, Path file) {
        if (!Files.isRegularFile(file)) return;
        Map<?, ?> root;
        try {
            root = MAPPER.readValue(Files.readAllBytes(file), Map.class);
        } catch (IOException | RuntimeException e) {
            return;
        }
        Object hooks = root == null ? null : root.get("hooks");
        if (!(hooks instanceof Map<?, ?> groups)) return;
        for (Map.Entry<?, ?> entry : groups.entrySet()) {
            Event event = Event.parse(String.valueOf(entry.getKey()));
            if (event == null) continue;
            List<Spec> parsed = parseSpecs(entry.getValue());
            if (parsed.isEmpty()) continue;
            target.computeIfAbsent(event, key -> new ArrayList<>()).addAll(parsed);
        }
    }

    private static List<Spec> parseSpecs(Object value) {
        List<Spec> specs = new ArrayList<>();
        if (!(value instanceof List<?> items)) return specs;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> group)) continue;
            String matcher = text(group.get("matcher"));
            long timeout = number(group.get("timeoutSeconds"));
            boolean block = Boolean.parseBoolean(text(group.get("blockOnFailure")));
            // 标准格式：hooks 数组里的 command；简化格式：直接给 command
            if (group.get("hooks") instanceof List<?> nested) {
                for (Object hook : nested) {
                    if (hook instanceof Map<?, ?> entry) {
                        String command = text(entry.get("command"));
                        if (!command.isBlank()) specs.add(new Spec(matcher, command, timeout, block));
                    }
                }
            }
            String direct = text(group.get("command"));
            if (!direct.isBlank()) specs.add(new Spec(matcher, direct, timeout, block));
        }
        return specs;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        String raw = text(value);
        if (raw.isBlank()) return 0;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 按顺序执行匹配到的钩子命令。
     *
     * @return 合并后的输出；{@link Result#blocked()} 为 true 时调用方应跳过工具执行
     */
    public Result run(Event event, String toolName) {
        List<Spec> matched = specs.getOrDefault(event, List.of()).stream()
                .filter(spec -> spec.matches(toolName)).toList();
        if (matched.isEmpty()) return Result.NONE;

        StringBuilder out = new StringBuilder();
        for (Spec spec : matched) {
            Exec executed = exec(spec, workspace);
            // 阻止判定不依赖输出：`exit 1` 这类没有输出的钩子同样要拦住工具
            boolean blocking = event == Event.PRE_TOOL_USE && spec.blockOnFailure() && executed.exit() != 0;
            if (executed.output().isBlank()) {
                if (blocking) {
                    return new Result("退出码 " + executed.exit() + "：" + spec.command(), true);
                }
                continue;
            }
            if (out.length() > 0) out.append('\n');
            out.append(executed.output());
            if (blocking) return new Result(clip(out.toString()), true);
        }
        return new Result(clip(out.toString()), false);
    }

    /** 在工作区里跑一条钩子命令；超时、无法执行、被中断都记成失败，不抛给调用方。 */
    private static Exec exec(Spec spec, Path workspace) {
        ProcessBuilder builder = new ProcessBuilder(com.pilot.core.tool.ProcessSupport.shell(spec.command()));
        if (workspace != null) builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        Process process = null;
        try {
            process = builder.start();
            process.getOutputStream().close();
            Process running = process;
            CompletableFuture<String> output = CompletableFuture.supplyAsync(
                    () -> com.pilot.core.tool.ProcessSupport.read(running.getInputStream()));
            if (!process.waitFor(spec.timeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                output.cancel(true);
                return new Exec(-1, "钩子超时（" + spec.timeoutSeconds() + " 秒）：" + spec.command());
            }
            return new Exec(process.exitValue(), output.join());
        } catch (IOException e) {
            return new Exec(-1, "钩子无法执行 " + spec.command() + "：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return new Exec(-1, "钩子被中断：" + spec.command());
        }
    }

    /** 输出过长时截断，避免钩子日志把上下文撑爆。 */
    private static String clip(String value) {
        String trimmed = value.strip();
        return trimmed.length() <= MAX_OUTPUT_CHARS ? trimmed : trimmed.substring(0, MAX_OUTPUT_CHARS) + "\n…";
    }

    private record Exec(int exit, String output) {
    }
}
