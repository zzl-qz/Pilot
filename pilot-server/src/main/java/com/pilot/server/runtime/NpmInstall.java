package com.pilot.server.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 优先用 Node 执行 npm CLI，让包含空格的路径由进程参数处理。 */
public final class NpmInstall {
    private NpmInstall() {
    }

    public static List<String> command(Path npm, List<String> flags) {
        return command(npm, flags, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    static List<String> command(Path npm, List<String> flags, boolean windows) {
        List<String> arguments = new ArrayList<>(List.of("install", "--no-audit", "--no-fund"));
        arguments.addAll(flags);
        Path directory = npm.toAbsolutePath().getParent();
        Path node = directory.resolve(windows ? "node.exe" : "node");
        for (Path cli : List.of(directory.resolve("node_modules/npm/bin/npm-cli.js"),
            directory.resolve("../lib/node_modules/npm/bin/npm-cli.js").normalize())) {
            if (Files.isRegularFile(node) && Files.isRegularFile(cli)) {
                List<String> command = new ArrayList<>(List.of(node.toString(), cli.toString()));
                command.addAll(arguments);
                return command;
            }
        }
        if (windows && !npm.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            return List.of("cmd.exe", "/d", "/c", "\"" + npm + "\" " + String.join(" ", arguments));
        }
        arguments.addFirst(npm.toString());
        return arguments;
    }
}
