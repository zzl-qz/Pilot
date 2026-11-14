package com.pilot.core.runtime;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 按调用方指定的优先级查找程序。调用方负责判断候选文件是否可用。 */
public final class ExecutablePath {
    private static final Pattern SEPARATOR = Pattern.compile(Pattern.quote(File.pathSeparator));

    private ExecutablePath() {
    }

    public static Stream<Path> directories(String pathList) {
        if (pathList == null || pathList.isBlank()) {
            return Stream.empty();
        }
        return SEPARATOR.splitAsStream(pathList).filter(entry -> !entry.isBlank()).flatMap(entry -> {
            try {
                return Stream.of(Path.of(entry));
            } catch (InvalidPathException ignored) {
                return Stream.empty();
            }
        });
    }

    public static Stream<Path> candidates(Stream<Path> directories, String... names) {
        List<String> orderedNames = Arrays.asList(names);
        return directories.flatMap(directory -> orderedNames.stream().map(directory::resolve));
    }
}
