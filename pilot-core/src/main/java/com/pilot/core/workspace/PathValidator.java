package com.pilot.core.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 工作区路径校验：把模型给出的路径规范化为工作区内的绝对路径。
 *
 * <p>拒绝两类越界：{@code ../} 归一化后落在根目录之外，以及符号链接指向根目录之外。
 * 所有工具都必须经由本类解析路径，不能直接使用模型传入的字符串。
 *
 * <p>根目录在构造时取 real path，避免 Windows 8.3 短路径与长路径混用导致的前缀比较失败。
 */
public final class PathValidator {
    private final Path root;

    public PathValidator(Path root) {
        Objects.requireNonNull(root, "root must not be null");
        Path absolute = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("workspace root is not a directory: " + absolute);
        }
        try {
            this.root = absolute.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot resolve workspace root: " + absolute, e);
        }
    }

    public Path root() {
        return root;
    }

    /**
     * @throws WorkspaceViolationException 路径越界
     * @throws IllegalArgumentException    路径为空或格式非法
     */
    public Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        String value = rawPath.strip();
        Path candidate;
        try {
            Path given = Path.of(value);
            candidate = (given.isAbsolute() ? given : root.resolve(given)).normalize();
        } catch (InvalidPathException e) {
            throw new WorkspaceViolationException("非法路径: " + value);
        }
        if (!candidate.startsWith(root)) {
            throw new WorkspaceViolationException("路径超出工作区: " + value);
        }
        if (Files.exists(candidate)) {
            try {
                Path real = candidate.toRealPath();
                if (!real.startsWith(root)) {
                    throw new WorkspaceViolationException("路径超出工作区（符号链接）: " + value);
                }
                return real;
            } catch (IOException e) {
                throw new UncheckedIOException("cannot resolve path: " + value, e);
            }
        }
        return candidate;
    }

    /** 用于事件的展示形式：工作区内返回相对路径，统一使用 {@code /}。 */
    public String display(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            return absolute.toString();
        }
        String relative = root.relativize(absolute).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
    }
}
