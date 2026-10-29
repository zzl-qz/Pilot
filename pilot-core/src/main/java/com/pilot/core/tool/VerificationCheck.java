package com.pilot.core.tool;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies command summaries that can actually verify a code change.
 *
 * <p>A successful process is not automatically a successful verification: commands such as
 * {@code git status}, {@code node --version}, and {@code cargo metadata} only inspect the
 * environment. Keeping the classification here gives every completion policy the same typed,
 * conservative definition instead of relying on an {@code exit 0} suffix alone.
 */
public final class VerificationCheck {
    public enum Kind {
        TEST,
        BUILD,
        STATIC_ANALYSIS,
        FORMAT_CHECK,
        OTHER;

        public boolean qualifies() {
            return this != OTHER;
        }
    }

    private static final Pattern SUMMARY = Pattern.compile("^(.*) → exit (-?\\d+)\\z", Pattern.DOTALL);
    private static final Set<String> TEST_RUNNERS = Set.of(
            "jest", "vitest", "playwright", "cypress", "pytest", "unittest");
    private static final Set<String> ANALYZERS = Set.of(
            "tsc", "eslint", "mypy", "pyright", "ruff", "checkstyle", "detekt");
    private static final Set<String> SCRIPT_TESTS = Set.of(
            "test", "e2e", "integration", "smoke", "behavior", "behaviour");
    private static final Set<String> SCRIPT_BUILDS = Set.of("build", "compile", "package", "assemble");
    private static final Set<String> SCRIPT_CHECKS = Set.of("lint", "typecheck", "check", "verify");
    private static final Set<String> INSPECTION_FLAGS = Set.of(
            "-v", "--version", "-version", "version", "-h", "--help", "help");
    private static final Set<String> SHELL_WRAPPERS = Set.of("bash", "sh", "zsh");
    private static final Set<String> BUILD_WRAPPER_SCRIPTS = Set.of("mvnw", "gradlew");

    private VerificationCheck() {
    }

    /** Returns the command kind only when the summary has a final successful exit code. */
    public static Kind passingKind(String summary) {
        if (summary == null || summary.isBlank()) return Kind.OTHER;
        Matcher matcher = SUMMARY.matcher(summary.strip());
        if (!matcher.matches() || !"0".equals(matcher.group(2))) return Kind.OTHER;
        return classifyCommand(matcher.group(1));
    }

    /** Classifies the command portion of a check summary, without considering its exit code. */
    public static Kind classifyCommand(String command) {
        if (command == null || command.isBlank()) return Kind.OTHER;
        String normalized = command.strip().toLowerCase(Locale.ROOT);
        if (normalized.contains("&&") || normalized.contains("||") || normalized.contains(";")
                || normalized.contains("|") || normalized.contains("\n") || normalized.contains("\r")) {
            return Kind.OTHER;
        }
        List<String> tokens = Arrays.stream(normalized.split("\\s+"))
                .filter(token -> !token.isBlank()).toList();
        return classifyTokens(tokens);
    }

    private static Kind classifyTokens(List<String> tokens) {
        if (tokens.isEmpty()) return Kind.OTHER;
        String executable = bare(tokens.getFirst());
        List<String> args = tokens.subList(1, tokens.size());

        // A successful version/help probe only proves that a binary exists. Reject it before
        // dispatch so combinations such as `mvn --version test` cannot smuggle in a test token.
        if (args.stream().anyMatch(INSPECTION_FLAGS::contains)) return Kind.OTHER;
        if (SHELL_WRAPPERS.contains(executable)) return classifyShellWrapper(args);

        if (Set.of("mvn", "mvnw").contains(executable)) return classifyMaven(args);
        if (Set.of("gradle", "gradlew").contains(executable)) return classifyGradle(args);
        if (Set.of("npm", "pnpm", "yarn", "bun").contains(executable)) return classifyPackageManager(args);
        if ("npx".equals(executable)) return classifyRunner(args);
        if ("node".equals(executable)) return classifyNode(args);
        if ("pytest".equals(executable)) return Kind.TEST;
        if ("ctest".equals(executable)) return Kind.TEST;
        if (Set.of("python", "python3").contains(executable)) return classifyPython(args);
        if ("javac".equals(executable) || "rustc".equals(executable) || "tsc".equals(executable)) {
            return args.isEmpty() ? Kind.OTHER : Kind.BUILD;
        }
        if ("java".equals(executable)) {
            return args.stream().anyMatch(token -> token.contains("junit")) ? Kind.TEST : Kind.OTHER;
        }
        if ("go".equals(executable)) return classifyGo(args);
        if ("cargo".equals(executable)) return classifyCargo(args);
        if ("dotnet".equals(executable)) return classifyDotnet(args);
        if ("make".equals(executable)) return classifyMake(args);
        if ("git".equals(executable) && args.size() >= 2
                && "diff".equals(args.getFirst()) && args.contains("--check")) return Kind.FORMAT_CHECK;
        return Kind.OTHER;
    }

    /**
     * Accept only a direct shell invocation of a known repository wrapper. Shell command strings
     * ({@code -c}/{@code -lc}), arbitrary scripts and nested operators remain untrusted/OTHER.
     */
    private static Kind classifyShellWrapper(List<String> args) {
        if (args.isEmpty() || args.getFirst().startsWith("-")) return Kind.OTHER;
        String script = bare(args.getFirst());
        if (!BUILD_WRAPPER_SCRIPTS.contains(script)) return Kind.OTHER;
        return classifyTokens(args);
    }

    private static Kind classifyMaven(List<String> args) {
        for (String arg : args) {
            if (arg.startsWith("-")) continue;
            String goal = lastSegment(arg);
            if (Set.of("test", "verify").contains(goal)) return Kind.TEST;
            if (Set.of("compile", "package", "install").contains(goal)) return Kind.BUILD;
            if (goal.equals("check") || goal.endsWith("check")) return Kind.STATIC_ANALYSIS;
        }
        return Kind.OTHER;
    }

    private static Kind classifyGradle(List<String> args) {
        for (String arg : args) {
            if (arg.startsWith("-")) continue;
            String task = lastSegment(arg).replace("-", "").replace("_", "");
            if (isGradleTestTask(task)) return Kind.TEST;
            if (task.equals("build") || task.equals("assemble") || task.startsWith("compile")) return Kind.BUILD;
            if (task.equals("check") || task.contains("lint") || task.contains("check")
                    || task.contains("detekt")) return Kind.STATIC_ANALYSIS;
        }
        return Kind.OTHER;
    }

    private static Kind classifyPackageManager(List<String> args) {
        if (args.isEmpty()) return Kind.OTHER;
        int scriptIndex = "run".equals(args.getFirst()) ? 1 : 0;
        if (Set.of("exec", "dlx").contains(args.getFirst())) {
            return classifyRunner(args.subList(1, args.size()));
        }
        if (scriptIndex >= args.size()) return Kind.OTHER;
        String script = args.get(scriptIndex).split(":", 2)[0];
        if (SCRIPT_TESTS.contains(script)) return Kind.TEST;
        if (SCRIPT_BUILDS.contains(script)) return Kind.BUILD;
        if (SCRIPT_CHECKS.contains(script)) return Kind.STATIC_ANALYSIS;
        return Kind.OTHER;
    }

    private static Kind classifyRunner(List<String> args) {
        if (args.isEmpty()) return Kind.OTHER;
        String runner = bare(args.getFirst());
        List<String> runnerArgs = args.subList(1, args.size());
        if ("playwright".equals(runner)) {
            return !runnerArgs.isEmpty() && "test".equals(runnerArgs.getFirst()) ? Kind.TEST : Kind.OTHER;
        }
        if ("cypress".equals(runner)) {
            return !runnerArgs.isEmpty() && "run".equals(runnerArgs.getFirst()) ? Kind.TEST : Kind.OTHER;
        }
        if (TEST_RUNNERS.contains(runner)) return Kind.TEST;
        if ("prettier".equals(runner) && args.contains("--check")) return Kind.FORMAT_CHECK;
        if (ANALYZERS.contains(runner)) return Kind.STATIC_ANALYSIS;
        return Kind.OTHER;
    }

    private static Kind classifyNode(List<String> args) {
        if (args.contains("--test") || args.stream().anyMatch(token -> token.startsWith("--test="))) {
            return Kind.TEST;
        }
        return args.contains("--check") ? Kind.STATIC_ANALYSIS : Kind.OTHER;
    }

    private static Kind classifyPython(List<String> args) {
        if (args.size() >= 2 && "-m".equals(args.getFirst())) {
            String module = args.get(1);
            if (Set.of("pytest", "unittest").contains(module)) return Kind.TEST;
            if ("compileall".equals(module)) return Kind.BUILD;
            if (Set.of("mypy", "pyright", "ruff").contains(module)) return Kind.STATIC_ANALYSIS;
        }
        return Kind.OTHER;
    }

    private static Kind classifyGo(List<String> args) {
        if (args.isEmpty()) return Kind.OTHER;
        return switch (args.getFirst()) {
            case "test" -> Kind.TEST;
            case "build" -> Kind.BUILD;
            case "vet" -> Kind.STATIC_ANALYSIS;
            default -> Kind.OTHER;
        };
    }

    private static Kind classifyCargo(List<String> args) {
        if (args.isEmpty()) return Kind.OTHER;
        return switch (args.getFirst()) {
            case "test" -> Kind.TEST;
            case "build" -> Kind.BUILD;
            case "check", "clippy" -> Kind.STATIC_ANALYSIS;
            case "fmt" -> args.contains("--check") ? Kind.FORMAT_CHECK : Kind.OTHER;
            default -> Kind.OTHER;
        };
    }

    private static Kind classifyDotnet(List<String> args) {
        if (args.isEmpty()) return Kind.OTHER;
        return switch (args.getFirst()) {
            case "test" -> Kind.TEST;
            case "build" -> Kind.BUILD;
            case "format" -> args.contains("--verify-no-changes") ? Kind.FORMAT_CHECK : Kind.OTHER;
            default -> Kind.OTHER;
        };
    }

    private static Kind classifyMake(List<String> args) {
        for (String arg : args) {
            if (arg.startsWith("-")) continue;
            String target = arg.split(":", 2)[0];
            if (SCRIPT_TESTS.contains(target)) return Kind.TEST;
            if (SCRIPT_BUILDS.contains(target)) return Kind.BUILD;
            if (SCRIPT_CHECKS.contains(target)) return Kind.STATIC_ANALYSIS;
        }
        return Kind.OTHER;
    }

    private static boolean isGradleTestTask(String task) {
        if ("test".equals(task)) return true;
        if (List.of("unit", "integration", "functional", "e2e", "smoke")
                .stream().anyMatch(prefix -> task.equals(prefix + "test"))) return true;
        // Android/variant tasks commonly look like testDebugUnitTest. Require a known test
        // suffix too, so tasks such as testClasses/testResources/testimony do not qualify.
        return task.startsWith("test") && List.of("unittest", "instrumentationtest", "connectedtest")
                .stream().anyMatch(task::endsWith);
    }

    private static String bare(String executable) {
        String value = executable.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static String lastSegment(String task) {
        int colon = task.lastIndexOf(':');
        return colon >= 0 ? task.substring(colon + 1) : task;
    }
}
