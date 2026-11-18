package com.pilot.core.lint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/**
 * 从仓库根目录的清单发现 lint 命令。只读根上的固定文件，不遍历目录。
 * 工作区 {@code .pilot/settings.json} 的 quality.command 优先于自动发现。
 */
public record ProjectLint(Status status, String command, String source, int timeoutSeconds) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 180;

    public enum Status {
        READY, UNCONFIGURED, DISABLED
    }

    public ProjectLint {
        command = command == null ? "" : command.strip();
        source = source == null ? "" : source;
        timeoutSeconds = timeoutSeconds < 1 ? DEFAULT_TIMEOUT_SECONDS : Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
    }

    public boolean ready() {
        return status == Status.READY && !command.isBlank();
    }

    public static ProjectLint discover(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return new ProjectLint(Status.UNCONFIGURED, "", "无工作区", DEFAULT_TIMEOUT_SECONDS);
        }
        Settings settings = readSettings(root.resolve(".pilot").resolve("settings.json"));
        if (settings.disabled()) {
            return new ProjectLint(Status.DISABLED, "", ".pilot/settings.json", settings.timeoutSeconds());
        }
        if (!settings.command().isBlank()) {
            return new ProjectLint(Status.READY, settings.command(), ".pilot/settings.json quality.command",
                settings.timeoutSeconds());
        }
        ProjectLint fromPackage = fromPackageJson(root, settings.timeoutSeconds());
        if (fromPackage != null) {
            return fromPackage;
        }
        ProjectLint fromMarker = fromMarker(root, settings.timeoutSeconds());
        return fromMarker != null ? fromMarker
            : new ProjectLint(Status.UNCONFIGURED, "", "根目录没有 lint 清单", settings.timeoutSeconds());
    }

    /**
     * 命令里写了 {files} 才替换成本次改动的相对路径。
     * 没写占位符就原样执行，再靠输出过滤丢掉未改文件。路径里有空格或引号时不拼进去。
     */
    public String render(List<String> relativeFiles) {
        if (!command.contains("{files}")) {
            return command;
        }
        return command.replace("{files}", joinSafe(relativeFiles)).replaceAll("\\s+", " ").strip();
    }

    private static String joinSafe(List<String> relativeFiles) {
        if (relativeFiles == null || relativeFiles.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String file : relativeFiles) {
            if (file == null || !file.matches("[A-Za-z0-9_./\\\\-]+")) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(file.replace('\\', '/'));
        }
        return out.toString();
    }

    private static ProjectLint fromPackageJson(Path root, int timeout) {
        Path file = root.resolve("package.json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            Map<?, ?> json = MAPPER.readValue(Files.readAllBytes(file), Map.class);
            Object scripts = json.get("scripts");
            if (!(scripts instanceof Map<?, ?> map)) {
                return null;
            }
            String lint = text(map.get("lint"));
            if (!lint.isBlank()) {
                return new ProjectLint(Status.READY, runner(root) + " {files}", "package.json scripts.lint", timeout);
            }
            String check = text(map.get("check"));
            if (looksLikeLint(check)) {
                return new ProjectLint(Status.READY, runner(root, "check") + " {files}", "package.json scripts.check",
                    timeout);
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static boolean looksLikeLint(String script) {
        String lower = script.toLowerCase(Locale.ROOT);
        return lower.contains("eslint") || lower.contains("biome") || lower.contains("ruff")
            || lower.contains("stylelint") || lower.contains("prettier") || lower.contains("phpcs")
            || lower.contains("rubocop") || lower.contains("golangci") || lower.contains("ktlint")
            || lower.contains("checkstyle") || lower.contains("detekt") || lower.contains("clippy");
    }

    private static String runner(Path root) {
        return runner(root, "lint");
    }

    private static String runner(Path root, String script) {
        if (Files.isRegularFile(root.resolve("pnpm-lock.yaml"), LinkOption.NOFOLLOW_LINKS)) {
            return "pnpm run " + script + " --";
        }
        if (Files.isRegularFile(root.resolve("yarn.lock"), LinkOption.NOFOLLOW_LINKS)) {
            return "yarn " + script;
        }
        if (Files.isRegularFile(root.resolve("bun.lock"), LinkOption.NOFOLLOW_LINKS)
            || Files.isRegularFile(root.resolve("bun.lockb"), LinkOption.NOFOLLOW_LINKS)) {
            return "bun run " + script + " --";
        }
        return "npm run " + script + " --";
    }

    private static ProjectLint fromMarker(Path root, int timeout) {
        if (exists(root, "biome.json") || exists(root, "biome.jsonc")) {
            return ready("biome check {files}", "biome.json", timeout);
        }
        if (exists(root, "eslint.config.js") || exists(root, "eslint.config.mjs") || exists(root, "eslint.config.cjs")
            || exists(root, "eslint.config.ts") || exists(root, ".eslintrc") || exists(root, ".eslintrc.js")
            || exists(root, ".eslintrc.cjs") || exists(root, ".eslintrc.json") || exists(root, ".eslintrc.yml")) {
            return ready("eslint {files}", "eslint 配置", timeout);
        }
        if (exists(root, "ruff.toml") || exists(root, ".ruff.toml") || pyprojectHas(root, "[tool.ruff]")) {
            return ready("ruff check {files}", "ruff 配置", timeout);
        }
        if (exists(root, ".golangci.yml") || exists(root, ".golangci.yaml") || exists(root, ".golangci.toml")) {
            return ready("golangci-lint run {files}", "golangci-lint 配置", timeout);
        }
        if (exists(root, "go.mod")) {
            return ready("go vet ./...", "go.mod", timeout);
        }
        if (exists(root, "phpcs.xml") || exists(root, "phpcs.xml.dist")) {
            return ready("phpcs {files}", "phpcs 配置", timeout);
        }
        if (exists(root, ".rubocop.yml") || exists(root, ".rubocop.yaml")) {
            return ready("rubocop {files}", "rubocop 配置", timeout);
        }
        if (exists(root, "analysis_options.yaml")) {
            return ready("dart analyze {files}", "analysis_options.yaml", timeout);
        }
        if (exists(root, "clippy.toml") || exists(root, ".clippy.toml")) {
            return ready("cargo clippy -- -D warnings", "clippy.toml", timeout);
        }
        if (exists(root, "deno.json") || exists(root, "deno.jsonc")) {
            return ready("deno lint {files}", "deno.json", timeout);
        }
        return null;
    }

    private static ProjectLint ready(String command, String source, int timeout) {
        return new ProjectLint(Status.READY, command, source, timeout);
    }

    private static boolean pyprojectHas(Path root, String marker) {
        Path file = root.resolve("pyproject.toml");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return Files.readString(file).contains(marker);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean exists(Path root, String name) {
        return Files.isRegularFile(root.resolve(name), LinkOption.NOFOLLOW_LINKS);
    }

    private static Settings readSettings(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Settings.ABSENT;
        }
        try {
            Map<?, ?> root = MAPPER.readValue(Files.readAllBytes(file), Map.class);
            Object quality = root == null ? null : root.get("quality");
            if (!(quality instanceof Map<?, ?> map)) {
                return Settings.ABSENT;
            }
            boolean disabled = "false".equalsIgnoreCase(text(map.get("enabled")));
            int timeout = (int)number(map.get("timeoutSeconds"));
            return new Settings(disabled, text(map.get("command")), timeout < 1 ? DEFAULT_TIMEOUT_SECONDS : timeout);
        } catch (IOException | RuntimeException ignored) {
            return Settings.ABSENT;
        }
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

    private record Settings(boolean disabled, String command, int timeoutSeconds) {
        static final Settings ABSENT = new Settings(false, "", DEFAULT_TIMEOUT_SECONDS);
    }
}
