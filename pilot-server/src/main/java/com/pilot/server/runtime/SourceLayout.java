package com.pilot.server.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

/** 源码启动所用的仓库布局。各入口按实际所需模块查找，安装包继续使用自己的回退目录。 */
public final class SourceLayout {
    private static final int SOURCE_SEARCH_LEVELS = 6;
    private static final int STATIC_SEARCH_LEVELS = 5;
    private static final Path FRONTEND = Path.of("pilot-web", "frontend");
    private static final Path STATIC_FILES = Path.of("pilot-web", "src", "main", "resources", "static");

    private SourceLayout() {
    }

    public static Path workingDirectory() {
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    public static Path serverRoot(Path start) {
        return find(start, SOURCE_SEARCH_LEVELS, SourceLayout::hasServer);
    }

    public static Path desktopRoot(Path start) {
        return find(start, SOURCE_SEARCH_LEVELS,
            path -> hasServer(path) && Files.isDirectory(path.resolve("pilot-desktop")));
    }

    public static Path frontendRoot(Path start) {
        return find(start, SOURCE_SEARCH_LEVELS, path -> Files.isRegularFile(path.resolve("pom.xml"))
            && Files.isRegularFile(frontendDirectory(path).resolve("package.json")));
    }

    public static Path frontendDirectory(Path root) {
        return root.resolve(FRONTEND);
    }

    public static Path staticSource(Path start) {
        Path root = find(start, STATIC_SEARCH_LEVELS, path -> Files.isDirectory(path.resolve(STATIC_FILES)));
        return root == null ? null : root.resolve(STATIC_FILES);
    }

    public static Path localData(Path root) {
        return root.resolve(".pilot");
    }

    public static Path bundledRuntime(Path root) {
        return localData(root).resolve("runtime");
    }

    private static boolean hasServer(Path root) {
        return Files.isRegularFile(root.resolve("pom.xml"))
            && Files.isRegularFile(root.resolve("pilot-server/pom.xml"));
    }

    private static Path find(Path start, int levels, Predicate<Path> matches) {
        Path current = start.toAbsolutePath().normalize();
        for (int i = 0; i < levels && current != null; i++, current = current.getParent()) {
            if (matches.test(current)) {
                return current;
            }
        }
        return null;
    }
}
