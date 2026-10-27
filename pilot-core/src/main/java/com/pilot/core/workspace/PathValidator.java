package com.pilot.core.workspace;

import com.pilot.core.multi.AgentScope;

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
 *
 * <p>可选绑定 {@link AgentScope}：子 Agent 使用带白名单的校验器，越界路径在解析阶段就被拒绝，
 * 而不是「能看见但不能读」——隔离必须由后端硬校验保证，提示词只是辅助。
 */
public final class PathValidator {
    private final Path root;
    private final AgentScope scope;

    public PathValidator(Path root) {
        this(root, null);
    }

    /** @param scope 子 Agent 的文件白名单；{@code null} 表示不额外限制（仍受工作区根目录约束） */
    public PathValidator(Path root, AgentScope scope) {
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
        this.scope = scope;
    }

    public Path root() {
        return root;
    }

    public AgentScope scope() {
        return scope;
    }

    /** 路径是否在当前视图内，供目录列举、搜索这类批量读取工具过滤结果。 */
    public boolean allows(Path path) {
        if (scope == null) {
            return true;
        }
        return scope.allowsPath(display(path));
    }

    /** 目录是否可见；白名单里有它的子路径时仍然允许列出，具体文件再逐条过滤。 */
    public boolean allowsDirectory(Path path) {
        if (scope == null) {
            return true;
        }
        return scope.allowsDirectory(display(path));
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
        // 校验从候选路径向上最近的现存祖先的真实路径，避免通过“工作区内符号链接/新建子路径”越界。
        // 例如 workspace/link 指向外部目录时，workspace/link/new.txt 本身尚不存在，仍须拒绝。
        Path existing = candidate;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new WorkspaceViolationException("无法验证工作区路径: " + value);
        }
        try {
            Path realExisting = existing.toRealPath();
            if (!realExisting.startsWith(root)) {
                throw new WorkspaceViolationException("路径超出工作区（符号链接）: " + value);
            }
            Path suffix = existing.relativize(candidate);
            Path resolved = realExisting.resolve(suffix).normalize();
            if (!resolved.startsWith(root)) {
                throw new WorkspaceViolationException("路径超出工作区: " + value);
            }
            // 工作区根目录本身始终可解析（列目录时再按白名单过滤子项），其余路径按子 Agent 视图校验
            String relative = display(resolved);
            if (scope != null && !".".equals(relative) && !scope.allowsPath(relative)) {
                throw new WorkspaceViolationException(scope.denialHint(relative));
            }
            return resolved;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot resolve path: " + value, e);
        }
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
