package com.pilot.core.coordination;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 协调范围：一个会话在跨会话协调里「看得见哪些目录」。
 *
 * <p>范围由会话自己的主工作区与所属项目绑定的全部文件夹组成。目录统一换成真实路径比较，
 * 因此符号链接、Windows 8.3 短路径与长路径混用、父子目录重叠都不会把同一个位置判成两处；
 * Windows 上路径大小写不敏感，其他平台保持大小写敏感。
 *
 * <p>本类只描述范围，不读会话、不查项目、也不决定谁该退让——解析成范围由
 * {@link CoordinationScopeResolver} 负责，使用范围的是各查询与冲突检测。
 *
 * <p>不同项目之间即使绑定了同一目录也互不可见：{@link #sharesCoordinationWith} 在双方都有
 * 项目标识且不一致时直接返回 {@code false}。没有项目标识的会话只按自己的主目录参与协调。
 */
public final class ProjectCoordinationScope {
    private static final String SEPARATOR = "/";

    private final String projectId;
    private final List<Directory> directories;
    private final boolean caseInsensitive;

    private ProjectCoordinationScope(String projectId, List<Directory> directories, boolean caseInsensitive) {
        this.projectId = projectId;
        this.directories = List.copyOf(directories);
        this.caseInsensitive = caseInsensitive;
    }

    /** 用平台默认的大小写规则构造范围；{@code folders} 为空时范围为空。 */
    public static ProjectCoordinationScope of(String projectId, List<Path> folders) {
        return of(projectId, folders, windowsFileSystem());
    }

    /**
     * @param projectId       所属项目；{@code null} 表示没有项目的个人会话
     * @param folders         主目录与项目附加目录，可重复、嵌套或不存在
     * @param caseInsensitive 路径比较是否忽略大小写（Windows 为 {@code true}）
     */
    public static ProjectCoordinationScope of(String projectId, List<Path> folders, boolean caseInsensitive) {
        return new ProjectCoordinationScope(projectId, normalize(folders, caseInsensitive), caseInsensitive);
    }

    /** 只有一个目录的范围：没有项目时，会话只能用自己的主目录协调。 */
    public static ProjectCoordinationScope ofDirectory(Path directory) {
        return of(null, List.of(directory));
    }

    public static ProjectCoordinationScope ofDirectory(Path directory, boolean caseInsensitive) {
        return of(null, List.of(directory), caseInsensitive);
    }

    /** 当前平台是否按 Windows 语义比较路径大小写。 */
    public static boolean windowsFileSystem() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    /** 目录的比较键：真实路径、统一 {@code /}、去掉末尾分隔符，必要时折叠大小写。 */
    public static String directoryKey(Path directory, boolean caseInsensitive) {
        String text = directory.toString().replace('\\', '/');
        while (text.length() > 1 && text.endsWith(SEPARATOR)) {
            text = text.substring(0, text.length() - 1);
        }
        if (SEPARATOR.equals(text)) {
            return "";
        }
        return caseInsensitive ? text.toLowerCase(Locale.ROOT) : text;
    }

    /**
     * 换真实路径：从最近存在的祖先取 real path，再拼回尚未创建的后缀。
     *
     * <p>目录不存在时同样这样算，既能还原 Windows 8.3 短路径，也不会让暂未创建的目录
     * 与已存在的父目录被当成两处；祖先都取不到时退回规范化绝对路径。
     */
    public static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
        } catch (IOException e) {
            return absolute;
        }
    }

    public String projectId() {
        return projectId;
    }

    /** 规范化后的目录，外层目录在前；被别的目录完全覆盖的嵌套目录已去掉。 */
    public List<Path> directories() {
        return directories.stream().map(Directory::path).toList();
    }

    public boolean caseInsensitive() {
        return caseInsensitive;
    }

    public boolean isEmpty() {
        return directories.isEmpty();
    }

    /** 这个路径（文件或目录）是否落在范围内。 */
    public boolean includes(Path path) {
        if (path == null) {
            return false;
        }
        String key = directoryKey(canonical(path), caseInsensitive);
        return directories.stream().anyMatch(directory -> covers(directory.key(), key));
    }

    /** 两个范围是否覆盖到同一处目录（含父子目录关系）。 */
    public boolean intersects(ProjectCoordinationScope other) {
        if (other == null) {
            return false;
        }
        for (Directory mine : directories) {
            for (Directory theirs : other.directories) {
                String theirKey = caseInsensitive == other.caseInsensitive ? theirs.key()
                    : directoryKey(theirs.path(), caseInsensitive);
                if (covers(mine.key(), theirKey) || covers(theirKey, mine.key())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 是否与另一个范围互相可见：目录要重叠，且不能分属两个不同的项目。
     *
     * <p>没有项目标识的会话仍按自己的目录参与协调；两个不同项目即使绑定同一目录也互不可见。
     */
    public boolean sharesCoordinationWith(ProjectCoordinationScope other) {
        if (other == null || directories.isEmpty() || other.directories.isEmpty() || !intersects(other)) {
            return false;
        }
        return projectId == null || other.projectId == null || projectId.equals(other.projectId);
    }

    @Override
    public String toString() {
        return "ProjectCoordinationScope[project=" + projectId + ", directories="
            + directories.stream().map(Directory::key).toList() + "]";
    }

    /** 一个纳入范围的目录：真实路径与它的比较键。 */
    public record Directory(Path path, String key) {
        public Directory {
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(key, "key must not be null");
        }
    }

    private static List<Directory> normalize(List<Path> folders, boolean caseInsensitive) {
        if (folders == null || folders.isEmpty()) {
            return List.of();
        }
        Map<String, Directory> unique = new LinkedHashMap<>();
        for (Path folder : folders) {
            if (folder == null) {
                continue;
            }
            Path canonical = canonical(folder);
            String key = directoryKey(canonical, caseInsensitive);
            unique.putIfAbsent(key, new Directory(canonical, key));
        }
        List<Directory> result = new ArrayList<>();
        for (Directory candidate : unique.values()) {
            boolean covered = unique.values().stream()
                .anyMatch(other -> !other.key().equals(candidate.key()) && covers(other.key(), candidate.key()));
            if (!covered) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** 父键是否覆盖子键：相等或子键位于父键之下。根目录（空键）覆盖全部。 */
    private static boolean covers(String parentKey, String childKey) {
        if (parentKey.isEmpty()) {
            return true;
        }
        return childKey.equals(parentKey) || childKey.startsWith(parentKey + SEPARATOR);
    }
}
