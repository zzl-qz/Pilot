package com.pilot.core.tool.command;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.pilot.core.exec.EnvironmentSanitizer;
import com.pilot.core.runtime.ExecutablePath;
import com.pilot.core.runtime.bootstrap.RuntimeEnvironment;
import com.pilot.core.util.HostOs;

/** Prepares the environment and executable paths used to launch commands. */
final class CommandRuntimeEnvironment {
    private static final int WRAPPER_INSPECT_BYTES = 16_384;

    private CommandRuntimeEnvironment() {
    }

    static void prepareEnvironment(Map<String, String> environment) {
        prepareEnvironment(environment, System.getProperty("java.home"), HostOs.isWindows());
    }

    static void prepareEnvironment(Map<String, String> environment, String pilotJavaHome, boolean windows) {
        EnvironmentSanitizer sanitizer = EnvironmentSanitizer.defaults();
        environment.keySet().removeIf(key -> !sanitizer.allowed(key));

        String pathKey = ExecutablePath.key(environment);
        String javaHomeKey = findKey(environment, "JAVA_HOME");
        String existingJavaHome = environment.get(javaHomeKey);
        // 已有有效 JAVA_HOME（如用户项目配置的 JDK 8/11/17）必须保留，避免被 Pilot 自身的 JDK 25 或 jlink 最小 JRE 劫持。
        if (!isValidJavaHome(existingJavaHome, false, windows) && isValidJavaHome(pilotJavaHome, true, windows)) {
            String bin = Path.of(pilotJavaHome, "bin").toString();
            environment.put(javaHomeKey, pilotJavaHome);
            prependPath(environment, pathKey, bin);
        }

        String bundledBin = RuntimeEnvironment.bundledBinPath().strip();
        if (!bundledBin.isBlank()) {
            prependPath(environment, pathKey, bundledBin);
        }

        environment.putIfAbsent("CI", "true");
        environment.put("PAGER", "cat");
        environment.put("GIT_PAGER", "cat");
        environment.put("GIT_TERMINAL_PROMPT", "0");
    }

    private static void prependPath(Map<String, String> environment, String pathKey, String bin) {
        String current = environment.getOrDefault(pathKey, "");
        String prefix = bin + File.pathSeparator;
        current = current.startsWith(prefix) ? current.substring(prefix.length()) : current.equals(bin) ? "" : current;
        environment.put(pathKey, current.isBlank() ? bin : bin + File.pathSeparator + current);
    }

    private static String findKey(Map<String, String> environment, String fallback) {
        for (String key : environment.keySet()) {
            if (fallback.equalsIgnoreCase(key)) {
                return key;
            }
        }
        return fallback;
    }

    static boolean isValidJavaHome(String dir, boolean requireCompiler, boolean windows) {
        if (dir == null || dir.isBlank()) {
            return false;
        }
        try {
            Path bin = Path.of(dir.strip(), "bin");
            String javaBin = windows ? "java.exe" : "java";
            if (!Files.isRegularFile(bin.resolve(javaBin))) {
                return false;
            }
            if (!requireCompiler) {
                return true;
            }
            String javacBin = windows ? "javac.exe" : "javac";
            return Files.isRegularFile(bin.resolve(javacBin));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    static Path resolveOnPath(String name, String path, boolean windows) {
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", path == null ? "" : path);
        prepareEnvironment(environment);

        var directories = ExecutablePath.directories(ExecutablePath.inherited(environment), String::trim);
        return ExecutablePath.executableCandidates(directories, name, windows).filter(Files::isRegularFile).findFirst()
            .orElse(null);
    }

    static Path findWrapper(Path workspace, String baseName, boolean windows) {
        List<String> names = windows ? List.of(baseName + ".cmd", baseName + ".bat") : List.of(baseName);
        for (String name : names) {
            Path candidate = workspace.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 检查 JAR 模式的 mvnw/gradlew 是否缺失配套 wrapper jar（例如仓库 .gitignore 忽略了 maven-wrapper.jar）。
     * 自定义脚本或 Maven Wrapper 3.2+ only-script 不引用 wrapper jar，不受此判断影响。
     */
    static boolean isWrapperMissingJar(Path workspace, Path wrapper, String baseName) {
        if (workspace == null || wrapper == null || !Files.isRegularFile(wrapper)) {
            return false;
        }
        String text = readPrefix(wrapper);
        if ("mvnw".equals(baseName) && (text.contains("maven-wrapper.jar") || text.contains("MavenWrapperMain"))) {
            return !Files.isRegularFile(workspace.resolve(".mvn").resolve("wrapper").resolve("maven-wrapper.jar"));
        }
        if ("gradlew".equals(baseName) && (text.contains("gradle-wrapper.jar") || text.contains("GradleWrapperMain"))) {
            return !Files.isRegularFile(workspace.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.jar"));
        }
        return false;
    }

    private static String readPrefix(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            int len = Math.min(bytes.length, WRAPPER_INSPECT_BYTES);
            return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            return "";
        }
    }
}
