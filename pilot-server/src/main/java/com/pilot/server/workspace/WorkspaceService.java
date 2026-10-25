package com.pilot.server.workspace;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 本地目录浏览与工作区校验。
 *
 * <p>只暴露目录，不读取文件内容；用于前端在新建会话时选择工作区。
 * 服务默认只绑定 loopback，因此这里不做用户认证，但仍会校验路径类型与可读性。
 */
@Component
public class WorkspaceService {
    private static final int MAX_DIRECTORIES = 300;
    private static final Set<String> NOISY_DIRECTORIES = Set.of("node_modules", ".git", ".gradle", "target", "dist", "out");

    public record RootEntry(String path, String label) {
    }

    public record DirectoryEntry(String name, String path) {
    }

    /**
     * @param parent      上级目录，已在根目录时为 {@code null}
     * @param directories 子目录（仅目录，按名称排序，最多 300 项）
     */
    public record BrowseResult(String path, String name, String parent, boolean gitRepository,
                               List<DirectoryEntry> directories, boolean truncated) {
    }

    /** 快捷入口：盘符或根、用户主目录。 */
    public List<RootEntry> roots() {
        Map<String, RootEntry> entries = new LinkedHashMap<>();
        Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        entries.put(key(home), new RootEntry(home.toString(), "主目录"));
        for (java.io.File root : java.io.File.listRoots()) {
            Path path = root.toPath().toAbsolutePath().normalize();
            entries.putIfAbsent(key(path), new RootEntry(path.toString(), path.toString()));
        }
        Path startup = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        entries.putIfAbsent(key(startup), new RootEntry(startup.toString(), "启动目录"));
        return List.copyOf(entries.values());
    }

    /** 浏览目录；{@code rawPath} 为空时返回用户主目录。 */
    public BrowseResult browse(String rawPath) {
        Path directory = rawPath == null || rawPath.isBlank() ? home() : requireDirectory(rawPath);
        List<DirectoryEntry> children = new ArrayList<>();
        boolean truncated = false;
        try (var stream = Files.list(directory)) {
            List<Path> dirs = stream
                    .filter(Files::isDirectory)
                    .filter(path -> !isNoisy(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
            for (Path child : dirs) {
                if (children.size() >= MAX_DIRECTORIES) {
                    truncated = true;
                    break;
                }
                children.add(new DirectoryEntry(child.getFileName().toString(), child.toAbsolutePath().toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取目录: " + directory, e);
        }

        Path parent = directory.getParent();
        return new BrowseResult(
                directory.toString(),
                displayName(directory),
                parent == null ? null : parent.toAbsolutePath().toString(),
                Files.isDirectory(directory.resolve(".git")),
                List.copyOf(children),
                truncated);
    }

    /**
     * 校验并规范化一个将要作为工作区的目录。
     *
     * @throws IllegalArgumentException 路径非法、不存在或不是目录
     */
    public Path requireDirectory(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("工作区路径不能为空");
        }
        Path path;
        try {
            Path given = Path.of(rawPath.strip());
            path = (given.isAbsolute() ? given : Path.of("").toAbsolutePath().resolve(given)).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("非法路径: " + rawPath);
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("目录不存在: " + path);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("不是目录: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException("目录不可读: " + path);
        }
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
    }

    private static boolean isNoisy(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return NOISY_DIRECTORIES.contains(name);
    }

    private static String displayName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    private static String key(Path path) {
        return path.toString().toLowerCase(Locale.ROOT);
    }
}
