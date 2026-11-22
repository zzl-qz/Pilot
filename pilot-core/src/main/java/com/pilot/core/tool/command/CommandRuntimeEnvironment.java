package com.pilot.core.tool.command;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.pilot.core.exec.EnvironmentSanitizer;
import com.pilot.core.runtime.ExecutablePath;
import com.pilot.core.runtime.bootstrap.RuntimeEnvironment;

/** Prepares the environment and executable paths used to launch commands. */
final class CommandRuntimeEnvironment {
    private CommandRuntimeEnvironment() {
    }

    static void prepareEnvironment(Map<String, String> environment) {
        EnvironmentSanitizer sanitizer = EnvironmentSanitizer.defaults();
        environment.keySet().removeIf(key -> !sanitizer.allowed(key));

        String pathKey = ExecutablePath.key(environment);
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            String bin = Path.of(javaHome, "bin").toString();
            environment.put("JAVA_HOME", javaHome);
            String current = environment.getOrDefault(pathKey, "");
            String prefix = bin + File.pathSeparator;
            current =
                current.startsWith(prefix) ? current.substring(prefix.length()) : current.equals(bin) ? "" : current;
            environment.put(pathKey, current.isBlank() ? bin : bin + File.pathSeparator + current);
        }

        String bundledBin = RuntimeEnvironment.bundledBinPath().strip();
        if (!bundledBin.isBlank()) {
            String current = environment.getOrDefault(pathKey, "");
            String prefix = bundledBin + File.pathSeparator;
            current = current.startsWith(prefix) ? current.substring(prefix.length())
                : current.equals(bundledBin) ? "" : current;
            environment.put(pathKey, current.isBlank() ? bundledBin : bundledBin + File.pathSeparator + current);
        }

        environment.putIfAbsent("CI", "true");
        environment.put("PAGER", "cat");
        environment.put("GIT_PAGER", "cat");
        environment.put("GIT_TERMINAL_PROMPT", "0");
    }

    static Path resolveOnPath(String name, String path, boolean windows) {
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", path == null ? "" : path);
        prepareEnvironment(environment);

        var directories = ExecutablePath.directories(ExecutablePath.inherited(environment), String::trim);
        String[] names = windows ? new String[] {name + ".exe", name + ".cmd", name + ".bat"} : new String[] {name};
        return ExecutablePath.candidates(directories, names).filter(Files::isRegularFile).findFirst().orElse(null);
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
}
