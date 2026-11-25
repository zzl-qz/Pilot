package com.pilot.core.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
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
    private final List<Path> roots;
    private final AgentScope scope;

    public PathValidator(Path root) {
        this(root, List.of(), null);
    }

    /** @param extraRoots 同一项目关联的其他目录：绝对路径可读写，相对路径仍以默认根为起点 */
    public PathValidator(Path root, List<Path> extraRoots) {
        this(root, extraRoots, null);
    }

    /** @param scope 子 Agent 的文件白名单；{@code null} 表示不额外限制（仍受工作区根目录约束） */
    public PathValidator(Path root, AgentScope scope) {
        this(root, List.of(), scope);
    }

    public PathValidator(Path root, List<Path> extraRoots, AgentScope scope) {
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
        this.roots = RealPaths.visible(this.root, extraRoots, true);
    }

    public Path root() {
        return root;
    }

    /** 本次运行可见可写的全部根：默认工作区在前，其余项目文件夹在后。 */
    public List<Path> roots() {
        return roots;
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
            Path given = root.getFileSystem().getPath(value);
            candidate = (given.isAbsolute() ? given : root.resolve(given)).normalize();
        } catch (InvalidPathException e) {
            throw new WorkspaceViolationException("非法路径: " + value);
        }
        Path resolved = canonical(candidate, value);
        if (!resolved.startsWith(boundFor(resolved))) {
            throw new WorkspaceViolationException("路径超出工作区: " + value);
        }
        // 工作区根目录本身始终可解析（列目录时再按白名单过滤子项），其余路径按子 Agent 视图校验。
        // 额外目录的展示形式是绝对路径，子 Agent 白名单里没有它，等于继续对子 Agent 关闭。
        String relative = display(resolved);
        if (scope != null && !".".equals(relative) && !scope.allowsPath(relative)) {
            throw new WorkspaceViolationException(scope.denialHint(relative));
        }
        return resolved;
    }

    /** 写入路径不能指向 Pilot 的仓库元数据或本地数据目录。 */
    public Path resolveWritable(String rawPath) {
        Path resolved = resolve(rawPath);
        if (resolved.startsWith(root)) {
            Path relative = root.relativize(resolved);
            if (relative.getNameCount() > 0) {
                String first = relative.getName(0).toString();
                if (first.equalsIgnoreCase(".git") || first.equalsIgnoreCase(".pilot")) {
                    throw new WorkspaceViolationException("禁止写入工作区的 .git 或 .pilot 受保护目录");
                }
            }
        }
        return resolved;
    }

    /** 候选路径落在哪个可见根上：命中多个时取更具体的那个，都不命中时交给默认根统一报越界。 */
    private Path boundFor(Path candidate) {
        Path match = root;
        int best = -1;
        for (Path bound : roots) {
            if (candidate.startsWith(bound) && bound.getNameCount() > best) {
                match = bound;
                best = bound.getNameCount();
            }
        }
        return match;
    }

    /**
     * 把候选路径换成真实路径：取最近现存祖先的 real path，再拼回尚未创建的后缀。
     *
     * <p>这一步必须发生在比较边界之前：Windows 临时目录等路径可能以 8.3 短名出现，
     * 而可见根都是 real path，直接比较前缀会把合法路径误判成越界。
     * 从现存祖先开始解析还避免通过“工作区内符号链接/新建子路径”越界：
     * workspace/link 指向外部目录时，workspace/link/new.txt 本身尚不存在，仍须拒绝。
     *
     * @throws WorkspaceViolationException 无法解析路径（含悬空链接）
     */
    private Path canonical(Path candidate, String value) {
        Path existing = candidate;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new WorkspaceViolationException("无法验证工作区路径: " + value);
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(candidate)).normalize();
        } catch (IOException e) {
            // 悬空链接也属于现存节点；无法解析时必须拒绝，不能退回父目录后继续写入。
            throw new WorkspaceViolationException("无法验证工作区路径: " + value + "（" + e.getMessage() + "）");
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
