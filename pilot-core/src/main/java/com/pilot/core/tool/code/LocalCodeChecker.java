package com.pilot.core.tool.code;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.pilot.core.exec.ProcessSupport;
import com.pilot.core.runtime.ExecutablePath;
import com.pilot.core.util.HostOs;

/** 本机已安装的跨语言代码检查器（Maven/Gradle/TypeScript/Go/Python/Rust）。 */
public final class LocalCodeChecker {
    private static final boolean WINDOWS = HostOs.isWindows();
    private static final Pattern STANDARD_LINE = Pattern.compile("^(.+?):(\\d+)(?::(\\d+))?:\\s*(.+)$");
    private static final Pattern TSC_PAREN_LINE = Pattern.compile("^(.+?)\\((\\d+),(\\d+)\\):\\s*(.+)$");
    private static final Pattern MAVEN_ERROR_LINE = Pattern.compile("^\\[ERROR]\\s+(.+?):\\[(\\d+),(\\d+)]\\s*(.+)$");
    private static final Pattern PYRIGHT_LINE =
        Pattern.compile("^(.+?):(\\d+):(\\d+)\\s+-\\s+(error|warning|information):\\s*(.+)$");
    private static final Pattern OUTLINE = Pattern.compile(
        "^\\s*(?:public\\s+|private\\s+|protected\\s+)?(?:class|interface|enum|func|function|def|type)\\s+(\\w+)");
    private static final int MAX_LINES = 50;
    private static final int OTHER_FILE_PREVIEW = 10;
    private static final int MAX_CAPTURE_BYTES = 512 * 1024;
    private static final long FAST_TIMEOUT_SECONDS = 25;
    private static final long BUILD_TIMEOUT_SECONDS = 60;
    private static final String TIMEOUT_SENTINEL = "__PILOT_CHECKER_TIMEOUT__";
    private static final Set<String> SKIPPED_DIRS =
        Set.of(".git", "node_modules", "target", "build", "dist", ".idea", ".gradle", ".venv", "venv");

    private final Path workspace;
    private final Predicate<String> installed;
    private final BiFunction<Path, List<String>, String> runner;
    private final boolean customRunner;

    public LocalCodeChecker(Path workspace, Predicate<String> installed,
        BiFunction<Path, List<String>, String> runner) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.installed = installed == null ? name -> false : installed;
        this.customRunner = runner != null;
        this.runner = runner == null ? (dir, cmd) -> runProcess(dir, cmd, FAST_TIMEOUT_SECONDS) : runner;
    }

    public static LocalCodeChecker installed(Path workspace) {
        return new LocalCodeChecker(workspace, LocalCodeChecker::onPath, null);
    }

    public boolean available() {
        return resolvePlan(null) != null;
    }

    public String diagnose(String relative) {
        Path target = null;
        if (relative != null && !relative.isBlank()) {
            target = resolveWithinWorkspace(relative);
            if (target == null) {
                return "ERROR: 路径超出工作区范围: " + relative;
            }
        }
        CheckPlan plan = resolvePlan(target);
        if (plan == null) {
            return "ERROR: 没有可用的代码检查器";
        }
        ProcessOutcome outcome = executePlan(plan);
        if (outcome.timedOut()) {
            return "ERROR: 代码检查超时（" + plan.label + " 已中止，可传入更具体的子模块或文件路径）";
        }
        String output = outcome.output();
        if (output == null || output.isBlank()) {
            return outcome.exitCode() == 0 ? "" : "ERROR: " + plan.label + " 检查失败（退出码 " + outcome.exitCode() + "）";
        }
        List<DiagnosticEntry> parsed = parseDiagnostics(output, plan.workingDir);
        if (parsed.isEmpty()) {
            if (plan.kind == Kind.MAVEN && output.contains("[ERROR]")) {
                String fallback = extractMavenSummaryError(output);
                if (!fallback.isEmpty()) {
                    return fallback;
                }
            }
            if (outcome.exitCode() != 0) {
                String summary = summarizeFailureOutput(output);
                if (!summary.isEmpty()) {
                    return "ERROR: " + plan.label + " 检查失败：\n" + summary;
                }
                return "ERROR: " + plan.label + " 检查失败（退出码 " + outcome.exitCode() + "）";
            }
            return "";
        }
        if (target != null && Files.isRegularFile(target)) {
            String targetRel = toRelativeSlash(target);
            List<DiagnosticEntry> matched =
                parsed.stream().filter(entry -> samePath(entry.path, targetRel)).limit(MAX_LINES).toList();
            if (!matched.isEmpty()) {
                return formatEntries(matched);
            }
            List<DiagnosticEntry> preview = parsed.stream().limit(OTHER_FILE_PREVIEW).toList();
            return "当前文件无诊断错误（同项目其他文件存在 " + parsed.size() + " 处诊断）：\n" + formatEntries(preview);
        }
        return formatEntries(parsed.stream().limit(MAX_LINES).toList());
    }

    public String outline(String relative) {
        if (!available() || relative == null || relative.isBlank()) {
            return "";
        }
        Path file = resolveWithinWorkspace(relative);
        if (file == null || !Files.isRegularFile(file)) {
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

    /** 暴露给单测和排障：按给定相对路径解析将要执行的检查计划。 */
    CheckPlan resolvePlanForTest(String relative) {
        Path target = relative == null || relative.isBlank() ? null : resolveWithinWorkspace(relative);
        return resolvePlan(target);
    }

    private ProcessOutcome executePlan(CheckPlan plan) {
        if (customRunner) {
            String output = runner.apply(plan.workingDir, plan.command);
            if (TIMEOUT_SENTINEL.equals(output)) {
                return new ProcessOutcome(-1, "", true);
            }
            return new ProcessOutcome(0, output == null ? "" : output, false);
        }
        return runProcessOutcome(plan.workingDir, plan.command, plan.timeoutSeconds, plan.kind);
    }

    private CheckPlan resolvePlan(Path target) {
        if (target != null) {
            Path startDir = Files.isDirectory(target) ? target : target.getParent();
            if (startDir == null || !startDir.startsWith(workspace)) {
                startDir = workspace;
            }
            CheckPlan byExtension = resolveByFileHint(target, startDir);
            if (byExtension != null) {
                return byExtension;
            }
            CheckPlan upward = findUpwardPlan(startDir, target);
            if (upward != null) {
                return upward;
            }
        }
        CheckPlan rootPlan = detectInDirectory(workspace, target);
        if (rootPlan != null) {
            return rootPlan;
        }
        return findNestedSubprojectPlan();
    }

    private CheckPlan resolveByFileHint(Path target, Path startDir) {
        if (!Files.isRegularFile(target)) {
            return null;
        }
        String lower = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".mts") || lower.endsWith(".cts")) {
            Path tsDir = findAncestorWith(startDir, "tsconfig.json");
            if (tsDir != null) {
                return buildTypeScriptPlan(tsDir);
            }
        }
        if (lower.endsWith(".java")) {
            CheckPlan javaPlan = findUpwardJavaPlan(startDir);
            if (javaPlan != null) {
                return javaPlan;
            }
        }
        if (lower.endsWith(".go")) {
            Path goDir = findAncestorWith(startDir, "go.mod");
            if (goDir != null) {
                return buildGoPlan(goDir, target);
            }
        }
        if (lower.endsWith(".py")) {
            Path pyDir = findAncestorWithAny(startDir, List.of("pyproject.toml", "requirements.txt", "setup.py"));
            return buildPythonPlan(pyDir == null ? workspace : pyDir, target);
        }
        if (lower.endsWith(".rs")) {
            Path cargoDir = findAncestorWith(startDir, "Cargo.toml");
            if (cargoDir != null) {
                return buildRustPlan(cargoDir);
            }
        }
        return null;
    }

    private CheckPlan findUpwardPlan(Path startDir, Path target) {
        Path cursor = startDir;
        while (cursor != null && cursor.startsWith(workspace)) {
            CheckPlan plan = detectInDirectory(cursor, target);
            if (plan != null) {
                return plan;
            }
            if (cursor.equals(workspace)) {
                break;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private CheckPlan findUpwardJavaPlan(Path startDir) {
        Path pomDir = findAncestorWith(startDir, "pom.xml");
        if (pomDir != null) {
            return buildMavenPlan(pomDir);
        }
        Path gradleDir = findAncestorWithAny(startDir, List.of("build.gradle", "build.gradle.kts"));
        if (gradleDir != null) {
            return buildGradlePlan(gradleDir);
        }
        return null;
    }

    private CheckPlan detectInDirectory(Path dir, Path target) {
        if (Files.exists(dir.resolve("go.mod"))) {
            CheckPlan goPlan = buildGoPlan(dir, target);
            if (goPlan != null) {
                return goPlan;
            }
        }
        if (Files.exists(dir.resolve("tsconfig.json"))) {
            CheckPlan tsPlan = buildTypeScriptPlan(dir);
            if (tsPlan != null) {
                return tsPlan;
            }
        }
        if (Files.exists(dir.resolve("pyproject.toml")) || Files.exists(dir.resolve("requirements.txt"))) {
            CheckPlan pyPlan = buildPythonPlan(dir, target);
            if (pyPlan != null) {
                return pyPlan;
            }
        }
        if (Files.exists(dir.resolve("Cargo.toml"))) {
            CheckPlan rustPlan = buildRustPlan(dir);
            if (rustPlan != null) {
                return rustPlan;
            }
        }
        if (Files.exists(dir.resolve("pom.xml"))) {
            CheckPlan mavenPlan = buildMavenPlan(dir);
            if (mavenPlan != null) {
                return mavenPlan;
            }
        }
        if (Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("build.gradle.kts"))) {
            CheckPlan gradlePlan = buildGradlePlan(dir);
            if (gradlePlan != null) {
                return gradlePlan;
            }
        }
        return null;
    }

    /** 根目录无配置时，向浅层子目录（如 monorepo 的 frontend/packages）扫描一次。 */
    private CheckPlan findNestedSubprojectPlan() {
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> level1 = Files.list(workspace)) {
            for (Path child : level1.filter(Files::isDirectory).sorted().toList()) {
                String name = child.getFileName().toString();
                if (SKIPPED_DIRS.contains(name) || name.startsWith(".")) {
                    continue;
                }
                candidates.add(child);
                try (Stream<Path> level2 = Files.list(child)) {
                    for (Path grand : level2.filter(Files::isDirectory).sorted().toList()) {
                        String gName = grand.getFileName().toString();
                        if (!SKIPPED_DIRS.contains(gName) && !gName.startsWith(".")) {
                            candidates.add(grand);
                        }
                    }
                } catch (IOException ignored) {
                    // 忽略无法读取的子目录
                }
            }
        } catch (IOException e) {
            return null;
        }
        for (Path dir : candidates) {
            CheckPlan plan = detectInDirectory(dir, null);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    private CheckPlan buildTypeScriptPlan(Path projectDir) {
        Path localBin = resolveLocalNodeBin(projectDir, "tsc");
        if (localBin != null) {
            List<String> cmd = buildExecutableCommand(localBin, List.of("--noEmit", "--pretty", "false"));
            return new CheckPlan(Kind.TYPESCRIPT, "tsc", projectDir, cmd, FAST_TIMEOUT_SECONDS);
        }
        if (installed.test("tsc")) {
            List<String> cmd = buildPathCommand("tsc", List.of("--noEmit", "--pretty", "false"));
            return new CheckPlan(Kind.TYPESCRIPT, "tsc", projectDir, cmd, FAST_TIMEOUT_SECONDS);
        }
        return null;
    }

    private CheckPlan buildGoPlan(Path moduleDir, Path target) {
        if (!installed.test("go")) {
            return null;
        }
        String pkg = "./...";
        if (target != null && target.startsWith(moduleDir)) {
            Path rel =
                Files.isRegularFile(target) ? moduleDir.relativize(target.getParent()) : moduleDir.relativize(target);
            String slash = rel.toString().replace('\\', '/');
            pkg = slash.isEmpty() ? "." : "./" + slash;
        }
        List<String> cmd = buildPathCommand("go", List.of("vet", pkg));
        return new CheckPlan(Kind.GO, "go vet", moduleDir, cmd, FAST_TIMEOUT_SECONDS);
    }

    private CheckPlan buildPythonPlan(Path projectDir, Path target) {
        String targetArg = null;
        if (target != null && Files.isRegularFile(target) && target.startsWith(projectDir)) {
            targetArg = projectDir.relativize(target).toString().replace('\\', '/');
        }
        if (installed.test("pyright")) {
            List<String> args = targetArg == null ? List.of() : List.of(targetArg);
            return new CheckPlan(Kind.PYTHON, "pyright", projectDir, buildPathCommand("pyright", args),
                FAST_TIMEOUT_SECONDS);
        }
        if (installed.test("ruff")) {
            List<String> args = new ArrayList<>(List.of("check", "--output-format", "concise"));
            if (targetArg != null) {
                args.add(targetArg);
            }
            return new CheckPlan(Kind.PYTHON, "ruff", projectDir, buildPathCommand("ruff", args), FAST_TIMEOUT_SECONDS);
        }
        return null;
    }

    private CheckPlan buildRustPlan(Path cargoDir) {
        if (!installed.test("cargo")) {
            return null;
        }
        List<String> cmd = buildPathCommand("cargo", List.of("check", "--message-format=short"));
        return new CheckPlan(Kind.RUST, "cargo check", cargoDir, cmd, BUILD_TIMEOUT_SECONDS);
    }

    private CheckPlan buildMavenPlan(Path moduleDir) {
        Path rootPomDir = Files.exists(workspace.resolve("pom.xml")) ? workspace : moduleDir;
        Path wrapper = findWrapper(rootPomDir, WINDOWS ? "mvnw.cmd" : "mvnw");
        if (wrapper == null && !installed.test("mvn")) {
            return null;
        }
        List<String> args = new ArrayList<>();
        args.add("test-compile");
        args.add("-B");
        if (!moduleDir.equals(rootPomDir) && moduleDir.startsWith(rootPomDir)) {
            String moduleRel = rootPomDir.relativize(moduleDir).toString().replace('\\', '/');
            args.add("-pl");
            args.add(moduleRel);
            args.add("-am");
        }
        List<String> cmd = wrapper != null ? buildWrapperCommand(wrapper, args) : buildPathCommand("mvn", args);
        return new CheckPlan(Kind.MAVEN, "maven", rootPomDir, cmd, BUILD_TIMEOUT_SECONDS);
    }

    private CheckPlan buildGradlePlan(Path moduleDir) {
        Path rootDir = (Files.exists(workspace.resolve("settings.gradle"))
            || Files.exists(workspace.resolve("settings.gradle.kts"))) ? workspace : moduleDir;
        Path wrapper = findWrapper(rootDir, WINDOWS ? "gradlew.bat" : "gradlew");
        if (wrapper == null && !installed.test("gradle")) {
            return null;
        }
        List<String> args = List.of("compileTestJava", "--console=plain");
        List<String> cmd = wrapper != null ? buildWrapperCommand(wrapper, args) : buildPathCommand("gradle", args);
        return new CheckPlan(Kind.GRADLE, "gradle", rootDir, cmd, BUILD_TIMEOUT_SECONDS);
    }

    private Path resolveLocalNodeBin(Path projectDir, String binary) {
        Path binDir = projectDir.resolve("node_modules").resolve(".bin");
        if (!Files.isDirectory(binDir)) {
            return null;
        }
        if (WINDOWS) {
            for (String suffix : List.of(".cmd", ".exe", ".bat")) {
                Path candidate = binDir.resolve(binary + suffix);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
        Path direct = binDir.resolve(binary);
        return Files.isRegularFile(direct) ? direct : null;
    }

    private Path findWrapper(Path rootDir, String fileName) {
        Path candidate = rootDir.resolve(fileName);
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private List<String> buildWrapperCommand(Path wrapper, List<String> args) {
        if (WINDOWS) {
            List<String> cmd = new ArrayList<>();
            cmd.add("cmd");
            cmd.add("/c");
            cmd.add(wrapper.toAbsolutePath().toString());
            cmd.addAll(args);
            return List.copyOf(cmd);
        }
        List<String> cmd = new ArrayList<>();
        if (Files.isExecutable(wrapper)) {
            cmd.add(wrapper.toAbsolutePath().toString());
        } else {
            cmd.add("sh");
            cmd.add(wrapper.toAbsolutePath().toString());
        }
        cmd.addAll(args);
        return List.copyOf(cmd);
    }

    private List<String> buildExecutableCommand(Path executable, List<String> args) {
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        if (WINDOWS && (name.endsWith(".cmd") || name.endsWith(".bat"))) {
            List<String> cmd = new ArrayList<>();
            cmd.add("cmd");
            cmd.add("/c");
            cmd.add(executable.toAbsolutePath().toString());
            cmd.addAll(args);
            return List.copyOf(cmd);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(executable.toAbsolutePath().toString());
        cmd.addAll(args);
        return List.copyOf(cmd);
    }

    private List<String> buildPathCommand(String binary, List<String> args) {
        List<String> cmd = new ArrayList<>();
        if (WINDOWS && !customRunner) {
            Path resolved = resolveOnPath(binary);
            if (resolved != null) {
                return buildExecutableCommand(resolved, args);
            }
        }
        cmd.add(binary);
        cmd.addAll(args);
        return List.copyOf(cmd);
    }

    private Path findAncestorWith(Path startDir, String fileName) {
        return findAncestorWithAny(startDir, List.of(fileName));
    }

    private Path findAncestorWithAny(Path startDir, List<String> fileNames) {
        Path cursor = startDir;
        while (cursor != null && cursor.startsWith(workspace)) {
            for (String fileName : fileNames) {
                if (Files.exists(cursor.resolve(fileName))) {
                    return cursor;
                }
            }
            if (cursor.equals(workspace)) {
                break;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private Path resolveWithinWorkspace(String relative) {
        try {
            Path resolved = workspace.resolve(relative).normalize();
            return resolved.startsWith(workspace) ? resolved : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<DiagnosticEntry> parseDiagnostics(String output, Path workingDir) {
        List<DiagnosticEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> rawLines = output.lines().toList();
        for (int i = 0; i < rawLines.size(); i++) {
            String stripped = rawLines.get(i).strip();
            if (stripped.isEmpty()) {
                continue;
            }
            DiagnosticEntry entry = parseSingleLine(stripped, workingDir);
            if (entry == null) {
                continue;
            }
            // Maven javac 常在下一行紧跟 symbol: / location: 说明，合并进来更便于定位
            if (i + 1 < rawLines.size()) {
                String next = rawLines.get(i + 1).strip();
                if (next.startsWith("symbol:") || next.startsWith("符号:")) {
                    entry = new DiagnosticEntry(entry.path, entry.line, entry.message + " (" + next + ")");
                }
            }
            String key = entry.format();
            if (seen.add(key)) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private DiagnosticEntry parseSingleLine(String line, Path workingDir) {
        Matcher maven = MAVEN_ERROR_LINE.matcher(line);
        if (maven.matches()) {
            String path = normalizeDiagnosticPath(maven.group(1), workingDir);
            return new DiagnosticEntry(path, maven.group(2), maven.group(4).strip());
        }
        Matcher tsc = TSC_PAREN_LINE.matcher(line);
        if (tsc.matches()) {
            String path = normalizeDiagnosticPath(tsc.group(1), workingDir);
            return new DiagnosticEntry(path, tsc.group(2), tsc.group(4).strip());
        }
        Matcher pyright = PYRIGHT_LINE.matcher(line);
        if (pyright.matches()) {
            String path = normalizeDiagnosticPath(pyright.group(1), workingDir);
            return new DiagnosticEntry(path, pyright.group(2), pyright.group(4) + ": " + pyright.group(5).strip());
        }
        Matcher std = STANDARD_LINE.matcher(line);
        if (std.matches()) {
            String rawPath = std.group(1).strip();
            if (rawPath.startsWith("http://") || rawPath.startsWith("https://") || rawPath.startsWith("[")) {
                return null;
            }
            String path = normalizeDiagnosticPath(rawPath, workingDir);
            return new DiagnosticEntry(path, std.group(2), std.group(4).strip());
        }
        return null;
    }

    private String normalizeDiagnosticPath(String rawPath, Path workingDir) {
        String cleaned = rawPath.strip();
        // Windows Maven 偶尔输出 /C:/path/Foo.java
        if (cleaned.length() >= 3 && cleaned.charAt(0) == '/' && cleaned.charAt(2) == ':') {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.startsWith("./") || cleaned.startsWith(".\\")) {
            cleaned = cleaned.substring(2);
        }
        try {
            Path parsed = Path.of(cleaned);
            Path resolved = parsed.isAbsolute() ? parsed.normalize() : workingDir.resolve(parsed).normalize();
            if (resolved.startsWith(workspace)) {
                return toRelativeSlash(resolved);
            }
        } catch (RuntimeException ignored) {
            // 保持原始规范化文本
        }
        return cleaned.replace('\\', '/');
    }

    private String extractMavenSummaryError(String output) {
        List<String> errors = output.lines().map(String::strip).filter(line -> line.startsWith("[ERROR]"))
            .map(line -> line.substring("[ERROR]".length()).strip())
            .filter(line -> !line.isEmpty() && !line.startsWith("->") && !line.startsWith("To see")
                && !line.startsWith("Re-run") && !line.startsWith("For more") && !line.startsWith("[Help"))
            .limit(8).toList();
        if (errors.isEmpty()) {
            return "";
        }
        return "ERROR: Maven 检查失败：\n" + String.join("\n", errors);
    }

    private String summarizeFailureOutput(String output) {
        List<String> lines = output.lines().map(String::strip).filter(line -> !line.isEmpty()).limit(8).toList();
        return String.join("\n", lines);
    }

    private String toRelativeSlash(Path path) {
        return workspace.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static boolean samePath(String a, String b) {
        String normA = a.replace('\\', '/');
        String normB = b.replace('\\', '/');
        return WINDOWS ? normA.equalsIgnoreCase(normB) : normA.equals(normB);
    }

    private static String formatEntries(List<DiagnosticEntry> entries) {
        return String.join("\n", entries.stream().map(DiagnosticEntry::format).toList());
    }

    private static boolean onPath(String name) {
        return resolveOnPath(name) != null;
    }

    private static Path resolveOnPath(String name) {
        String path = ExecutablePath.inherited(System.getenv());
        if (path == null || path.isBlank()) {
            return null;
        }
        return ExecutablePath.executableCandidates(ExecutablePath.directories(path), name, WINDOWS)
            .filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private static String runProcess(Path directory, List<String> command, long timeoutSeconds) {
        ProcessOutcome outcome = runProcessOutcome(directory, command, timeoutSeconds, null);
        return outcome.timedOut() ? TIMEOUT_SENTINEL : outcome.output();
    }

    private static ProcessOutcome runProcessOutcome(Path directory, List<String> command, long timeoutSeconds,
        Kind kind) {
        Process process = null;
        try {
            ProcessBuilder builder =
                new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
            if (kind == Kind.MAVEN || kind == Kind.GRADLE) {
                String currentJavaHome = System.getProperty("java.home");
                String envJavaHome = builder.environment().get("JAVA_HOME");
                if ((envJavaHome == null || envJavaHome.isBlank()) && currentJavaHome != null
                    && !currentJavaHome.isBlank()) {
                    builder.environment().put("JAVA_HOME", currentJavaHome);
                }
            }
            process = builder.start();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            InputStream stream = process.getInputStream();
            Thread drainer = Thread.ofVirtual().start(() -> drainBounded(stream, buffer));
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                destroyTree(process);
                drainer.join(1000);
                return new ProcessOutcome(-1, "", true);
            }
            drainer.join(5000);
            return new ProcessOutcome(process.exitValue(), ProcessSupport.decode(buffer.toByteArray()), false);
        } catch (IOException e) {
            return new ProcessOutcome(-1, "ERROR: 无法启动检查器进程: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            if (process != null) {
                destroyTree(process);
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException cleanupInterrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            Thread.currentThread().interrupt();
            return new ProcessOutcome(-1, "", false);
        }
    }

    private static void drainBounded(InputStream stream, ByteArrayOutputStream buffer) {
        byte[] chunk = new byte[4096];
        int remaining = MAX_CAPTURE_BYTES;
        try {
            int read;
            while ((read = stream.read(chunk)) != -1) {
                if (remaining > 0) {
                    int toWrite = Math.min(read, remaining);
                    buffer.write(chunk, 0, toWrite);
                    remaining -= toWrite;
                }
            }
        } catch (IOException ignored) {
            // 进程被终止时流会关闭，忽略即可
        }
    }

    private static void destroyTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
            // 忽略不支持 descendants 的环境
        }
        process.destroyForcibly();
    }

    enum Kind {
        TYPESCRIPT, MAVEN, GRADLE, GO, PYTHON, RUST
    }

    record CheckPlan(Kind kind, String label, Path workingDir, List<String> command, long timeoutSeconds) {
    }

    private record ProcessOutcome(int exitCode, String output, boolean timedOut) {
    }

    private record DiagnosticEntry(String path, String line, String message) {
        String format() {
            return path + ":" + line + ": " + message;
        }
    }
}
