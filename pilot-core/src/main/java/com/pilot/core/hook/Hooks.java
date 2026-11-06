package com.pilot.core.hook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

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
 * <p>配置不是执行授权：工作区钩子必须逐条确认，所有钩子遵守当前命令权限和执行后端。
 * PreToolUse 被拒绝或跳过时阻止原工具；已执行命令退出失败时按 {@code blockOnFailure} 处理。
 */
public final class Hooks {

    public enum Event {
        PRE_TOOL_USE("PreToolUse"), POST_TOOL_USE("PostToolUse");

        private final String wireName;

        Event(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Event parse(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.strip().toLowerCase(Locale.ROOT).replace("_", "");
            for (Event event : values()) {
                if (event.wireName().toLowerCase(Locale.ROOT).equals(normalized)) {
                    return event;
                }
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
            return Arrays.stream(matcher.split("\\|")).map(String::strip).filter(part -> !part.isBlank())
                .anyMatch(part -> part.equals("*") || part.equalsIgnoreCase(toolName)
                    || part.endsWith("*") && toolName.regionMatches(true, 0, part, 0, part.length() - 1));
        }
    }

    /** @param blocked PreToolUse 钩子要求阻止；PostToolUse 恒为 false */
    public record Result(String output, boolean blocked) {
        public static final Result NONE = new Result("", false);
    }

    private static final int MAX_OUTPUT_CHARS = 8_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<Event, List<Command>> specs;

    private Hooks(Map<Event, List<Command>> specs) {
        this.specs = Map.copyOf(specs);
    }

    public static Hooks none() {
        return new Hooks(Map.of());
    }

    /** 读取用户级与工作区级配置并合并；任何读取或格式问题都退化为「没有钩子」，不阻断运行。 */
    public static Hooks load(Path workspace) {
        return load(workspace, Path.of(System.getProperty("user.home", ".")));
    }

    public static Hooks load(Path workspace, Path userHome) {
        Map<Event, List<Command>> merged = new LinkedHashMap<>();
        merge(merged, userHome.resolve(".pilot").resolve("settings.json"), workspace, false);
        if (workspace != null) {
            merge(merged, workspace.resolve(".pilot").resolve("settings.json"), workspace, true);
        }
        return merged.isEmpty() ? none() : new Hooks(merged);
    }

    private static void merge(Map<Event, List<Command>> target, Path file, Path workspace, boolean workspaceConfig) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<?, ?> root;
        try {
            root = MAPPER.readValue(Files.readAllBytes(file), Map.class);
        } catch (IOException | RuntimeException e) {
            return;
        }
        Object hooks = root == null ? null : root.get("hooks");
        if (!(hooks instanceof Map<?, ?> groups)) {
            return;
        }
        for (Map.Entry<?, ?> entry : groups.entrySet()) {
            Event event = Event.parse(String.valueOf(entry.getKey()));
            if (event == null) {
                continue;
            }
            List<Spec> parsed = parseSpecs(entry.getValue());
            if (parsed.isEmpty()) {
                continue;
            }
            Path source;
            try {
                source = file.toRealPath();
            } catch (IOException invalidSource) {
                continue;
            }
            boolean needsTrust =
                workspaceConfig || workspace != null && source.startsWith(workspace.toAbsolutePath().normalize());
            Path directory = workspace == null ? Path.of(System.getProperty("user.dir", ".")).toAbsolutePath()
                : workspace.toAbsolutePath().normalize();
            for (Spec spec : parsed) {
                target.computeIfAbsent(event, key -> new ArrayList<>())
                    .add(new Command(spec, directory, source, needsTrust));
            }
        }
    }

    private static List<Spec> parseSpecs(Object value) {
        List<Spec> specs = new ArrayList<>();
        if (!(value instanceof List<?> items)) {
            return specs;
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> group)) {
                continue;
            }
            String matcher = text(group.get("matcher"));
            long timeout = number(group.get("timeoutSeconds"));
            boolean block = Boolean.parseBoolean(text(group.get("blockOnFailure")));
            // 标准格式：hooks 数组里的 command；简化格式：直接给 command
            if (group.get("hooks") instanceof List<?> nested) {
                for (Object hook : nested) {
                    if (hook instanceof Map<?, ?> entry) {
                        String command = text(entry.get("command"));
                        if (!command.isBlank()) {
                            specs.add(new Spec(matcher, command, timeout, block));
                        }
                    }
                }
            }
            String direct = text(group.get("command"));
            if (!direct.isBlank()) {
                specs.add(new Spec(matcher, direct, timeout, block));
            }
        }
        return specs;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String raw = text(value);
        if (raw.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 配置只描述命令；执行必须交给当前工具执行器进行权限、审批和隔离检查。 */
    public record Command(Spec spec, Path workspace, Path source, boolean requiresTrust) {
    }

    public record CommandResult(String output, boolean ok, boolean executed) {
    }

    @FunctionalInterface
    public interface Runner {
        CommandResult run(Command command);
    }

    /** 没有执行上下文时拒绝执行，避免新增调用方绕过权限。 */
    public Result run(Event event, String toolName) {
        return run(event, toolName, command -> new CommandResult("ERROR: 钩子缺少受控执行上下文，未执行", false, false));
    }

    public Result run(Event event, String toolName, Runner runner) {
        List<Command> matched =
            specs.getOrDefault(event, List.of()).stream().filter(command -> command.spec().matches(toolName)).toList();
        if (matched.isEmpty()) {
            return Result.NONE;
        }
        StringBuilder out = new StringBuilder();
        for (Command command : matched) {
            CommandResult result = runner.run(command);
            boolean blocking =
                event == Event.PRE_TOOL_USE && (!result.executed() || command.spec().blockOnFailure() && !result.ok());
            if (!result.output().isBlank()) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(result.output());
            }
            if (blocking) {
                return new Result(clip(out.toString()), true);
            }
        }
        return new Result(clip(out.toString()), false);
    }

    /** 输出过长时截断，避免钩子日志把上下文撑爆。 */
    private static String clip(String value) {
        String trimmed = value.strip();
        return trimmed.length() <= MAX_OUTPUT_CHARS ? trimmed : trimmed.substring(0, MAX_OUTPUT_CHARS) + "\n…";
    }

}
