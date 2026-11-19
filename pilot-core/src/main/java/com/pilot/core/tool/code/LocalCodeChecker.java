package com.pilot.core.tool.code;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** 本机已安装的检查器。没有对应命令时诊断和符号查询都不可用。 */
public final class LocalCodeChecker {
    private static final Pattern LINE = Pattern.compile("^.+:\\d+:.+$");
    private static final Pattern OUTLINE = Pattern.compile(
        "^\\s*(?:public\\s+|private\\s+|protected\\s+)?(?:class|interface|enum|func|function|def|type)\\s+(\\w+)");
    private static final int MAX_LINES = 50;

    private final Path workspace;
    private final Predicate<String> installed;
    private final BiFunction<Path, List<String>, String> runner;

    public LocalCodeChecker(Path workspace, Predicate<String> installed,
        BiFunction<Path, List<String>, String> runner) {
        this.workspace = workspace;
        this.installed = installed == null ? name -> false : installed;
        this.runner = runner == null ? LocalCodeChecker::runProcess : runner;
    }

    public static LocalCodeChecker installed(Path workspace) {
        return new LocalCodeChecker(workspace, LocalCodeChecker::onPath, LocalCodeChecker::runProcess);
    }

    public boolean available() {
        return command() != null;
    }

    public String diagnose(String relative) {
        List<String> command = command();
        if (command == null) {
            return "";
        }
        String output = runner.apply(workspace, command);
        if (output == null || output.isBlank()) {
            return "";
        }
        List<String> lines =
            output.lines().map(String::strip).filter(line -> LINE.matcher(line).matches()).limit(MAX_LINES).toList();
        return String.join("\n", lines);
    }

    public String outline(String relative) {
        if (!available() || relative == null || relative.isBlank()) {
            return "";
        }
        Path file = workspace.resolve(relative).normalize();
        if (!file.startsWith(workspace.normalize()) || !Files.isRegularFile(file)) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        try {
            List<String> source = Files.readAllLines(file);
            for (int index = 0; index < source.size() && lines.size() < MAX_LINES; index++) {
                var match = OUTLINE.matcher(source.get(index));
                if (match.find()) {
                    lines.add(relative + ":" + (index + 1) + ": " + match.group(1));
                }
            }
        } catch (IOException e) {
            return "";
        }
        return String.join("\n", lines);
    }

    private List<String> command() {
        if (Files.exists(workspace.resolve("go.mod")) && installed.test("go")) {
            return List.of("go", "vet", "./...");
        }
        if (Files.exists(workspace.resolve("tsconfig.json")) && installed.test("tsc")) {
            return List.of("tsc", "--noEmit", "--pretty", "false");
        }
        if (installed.test("pyright") && (Files.exists(workspace.resolve("pyproject.toml"))
            || Files.exists(workspace.resolve("requirements.txt")))) {
            return List.of("pyright");
        }
        if (installed.test("ruff") && Files.exists(workspace.resolve("pyproject.toml"))) {
            return List.of("ruff", "check", "--output-format", "concise");
        }
        if (installed.test("javac") && Files.exists(workspace.resolve("pom.xml"))) {
            return List.of("javac", "-version");
        }
        return null;
    }

    private static boolean onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (Files.isRegularFile(Path.of(dir, name)) || Files.isRegularFile(Path.of(dir, name + ".exe"))
                || Files.isRegularFile(Path.of(dir, name + ".cmd"))) {
                return true;
            }
        }
        return false;
    }

    private static String runProcess(Path directory, List<String> command) {
        try {
            Process process =
                new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }
}
