package com.pilot.core.project;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 项目：对话的上级容器，绑定一个或多个本地文件夹。
 *
 * <p>一个项目下可以有很多对话；对话本身只使用其中一个文件夹作为工作区
 * （{@link #primaryFolder()} 是新建对话时的默认值）。绑定多个文件夹是为了
 * 同一个产品跨前端/后端/文档多个目录时还能共用一套对话，而不是让工具越过单根边界。
 *
 * @param folders 绑定的绝对路径，按用户给的顺序；第一个是默认工作区
 * @param baseCommit 建项目时记录的仓库基线；沙箱与变更审阅都应以它为起点
 */
public record Project(String id, String name, List<String> folders, String baseCommit,
                      Instant createdAt, Instant updatedAt) {
    /** 兼容调用：不记录基线。 */
    public Project(String id, String name, List<String> folders, Instant createdAt, Instant updatedAt) {
        this(id, name, folders, null, createdAt, updatedAt);
    }

    public Project {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        name = name.strip();
        folders = normalize(folders);
    }

    /** 去空、去重、保持顺序：同一路径绑两次没有意义。 */
    private static List<String> normalize(List<String> folders) {
        if (folders == null || folders.isEmpty()) {
            return List.of();
        }
        var unique = new LinkedHashSet<String>();
        for (String folder : folders) {
            if (folder == null) {
                continue;
            }
            String text = folder.strip();
            if (!text.isEmpty()) {
                unique.add(text);
            }
        }
        return List.copyOf(new ArrayList<>(unique));
    }

    /** 新建对话时的默认工作区；没有绑定任何文件夹时为 {@code null}。 */
    public String primaryFolder() {
        return folders.isEmpty() ? null : folders.get(0);
    }

    public Project rename(String newName, Instant now) {
        return new Project(id, newName, folders, baseCommit, createdAt, now);
    }

    public Project withFolders(List<String> newFolders, Instant now) {
        return new Project(id, name, newFolders, baseCommit, createdAt, now);
    }

    public Project withBaseCommit(String commit, Instant now) {
        return new Project(id, name, folders, commit, createdAt, now);
    }
}
