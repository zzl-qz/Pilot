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
    private static final List<String> RULE_FILES = List.of(".pilot/settings.json", "biome.json", "biome.jsonc",
        "eslint.config.js", "eslint.config.mjs", "eslint.config.cjs", "eslint.config.ts", ".eslintrc", ".eslintrc.js",
        ".eslintrc.cjs", ".eslintrc.json", ".eslintrc.yml", "ruff.toml", ".ruff.toml", "pyproject.toml",
        ".golangci.yml", ".golangci.yaml", ".golangci.toml", "phpcs.xml", "phpcs.xml.dist", ".rubocop.yml",
        ".rubocop.yaml", "analysis_options.yaml", "clippy.toml", ".clippy.toml", "deno.json", "deno.jsonc",
        "config/quality/rules.json", "config/java/checkstyle-strict.xml");
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 180;
    private static final int DIGEST_CHARS = 20_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    /** 这次改动是不是在改 lint 自己的配置。模型改这些文件不能当成普通编辑放行。 */
    public static boolean isProtectedConfig(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String norm = path.replace('\\', '/');
        while (norm.startsWith("./")) {
            norm = norm.substring(2);
        }
        String name = norm.substring(norm.lastIndexOf('/') + 1);
        return RULE_FILES.contains(norm) || RULE_FILES.contains(name);
    }

    public static ProjectLint discover(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return new ProjectLint(Status.UNCONFIGURED, "", "无工作区", DEFAULT_TIMEOUT_SECONDS);
        }
        Settings settings = readSettings(root.resolve(".pilot").resolve("settings.json"));
        if (!settings.command().isBlank()) {
            return freeze(root, new ProjectLint(Status.READY, settings.command(),
                ".pilot/settings.json quality.command", settings.timeoutSeconds()));
        }
        ProjectLint fromPackage = fromPackageJson(root, settings.timeoutSeconds());
        if (fromPackage != null) {
            return freeze(root, fromPackage);
        }
        ProjectLint fromMarker = fromMarker(root, settings.timeoutSeconds());
        return fromMarker != null ? fromMarker
            : new ProjectLint(Status.UNCONFIGURED, "", "根目录没有 lint 清单", settings.timeoutSeconds());
    }

    /**
     * 包管理器的 lint 脚本在发现时就展开成脚本正文。
     * 之后模型改 package.json，也跑不到被换成 exit 0 的那一版。
     */
    static ProjectLint freeze(Path root, ProjectLint found) {
        if (found == null || !found.ready() || root == null) {
            return found;
        }
        String script = scriptBody(root, found.source());
        if (script.isBlank()) {
            return found;
        }
        String command = script.contains("{files}") ? script : script + " {files}";
        return new ProjectLint(Status.READY, command, found.source(), found.timeoutSeconds());
    }

    /** 规则文件和脚本正文的快照。运行中这些内容变了，就视为把检查改掉。 */
    public static String digest(Path root, ProjectLint lint) {
        if (root == null || lint == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append(lint.command()).append('\n').append(lint.source()).append('\n');
        out.append("script=").append(scriptBody(root, lint.source())).append('\n');
        for (String name : RULE_FILES) {
            out.append(name).append('=').append(readSmall(root.resolve(name))).append('\n');
        }
        for (String token : lint.command().split("\\s+")) {
            if (token.contains("/") || token.endsWith(".js") || token.endsWith(".mjs") || token.endsWith(".cjs")
                || token.endsWith(".py") || token.endsWith(".sh")) {
                out.append(token).append('=').append(readSmall(root.resolve(token))).append('\n');
            }
        }
        return out.toString();
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
        if (exists(root, "scripts/quality-gate.mjs") && exists(root, "config/quality/rules.json")) {
            return ready("node scripts/quality-gate.mjs {files}", "scripts/quality-gate.mjs", timeout);
        }
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

    private static String scriptBody(Path root, String source) {
        if (source == null || !source.startsWith("package.json scripts.")) {
            return "";
        }
        String name = source.substring("package.json scripts.".length());
        Path file = root.resolve("package.json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try {
            Map<?, ?> json = MAPPER.readValue(Files.readAllBytes(file), Map.class);
            Object scripts = json.get("scripts");
            if (scripts instanceof Map<?, ?> map) {
                return text(map.get(name));
            }
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
        return "";
    }

    private static String readSmall(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try {
            String text = Files.readString(file);
            return text.length() <= DIGEST_CHARS ? text : text.substring(0, DIGEST_CHARS);
        } catch (IOException e) {
            return "";
        }
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
