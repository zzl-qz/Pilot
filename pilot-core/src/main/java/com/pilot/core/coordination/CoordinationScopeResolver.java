package com.pilot.core.coordination;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.pilot.core.project.Project;
import com.pilot.core.project.ProjectRepository;
import com.pilot.core.session.Session;

import lombok.extern.slf4j.Slf4j;

/**
 * 把会话解析成 {@link ProjectCoordinationScope}。
 *
 * <p>这是唯一一处「会话/项目 → 协调范围」的换算，聚合查询、冲突检测与事件订阅都用同一个结果，
 * 避免每个模块各写一套工作区比较。解析只读：项目仓库与会话记录都不会在这里被修改。
 */
@Slf4j
public final class CoordinationScopeResolver {
    private final ProjectRepository projects;
    private final boolean caseInsensitive;

    public CoordinationScopeResolver(ProjectRepository projects) {
        this(projects, ProjectCoordinationScope.windowsFileSystem());
    }

    /** @param caseInsensitive 路径比较是否忽略大小写，测试可固定成 Windows 语义 */
    public CoordinationScopeResolver(ProjectRepository projects, boolean caseInsensitive) {
        this.projects = projects;
        this.caseInsensitive = caseInsensitive;
    }

    /**
     * 会话的协调范围：自己的主目录 + 所属项目绑定的全部文件夹。
     *
     * <p>没有项目标识时只用自己的主目录；项目已被删除时仍保留项目标识，避免和别的项目混在一起。
     */
    public ProjectCoordinationScope resolve(Session session) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        List<Path> folders = new ArrayList<>();
        addPath(folders, session.workspace());
        String projectId = session.projectId();
        if (projectId == null) {
            return ProjectCoordinationScope.of(null, folders, caseInsensitive);
        }
        Project project = projects.find(projectId).orElse(null);
        if (project == null) {
            return ProjectCoordinationScope.of(projectId, folders, caseInsensitive);
        }
        project.folders().forEach(folder -> addPath(folders, folder));
        return ProjectCoordinationScope.of(project.id(), folders, caseInsensitive);
    }

    /**
     * 以某个目录为入口解析协调范围：优先用主工作区落在这个目录上的会话，其次用绑定该目录的项目。
     *
     * <p>看板按目录打开时，这样能把「同一项目里主目录不同、但共享附加目录」的会话一起纳入。
     */
    public ProjectCoordinationScope scopeForWorkspace(String workspace, List<Session> sessions) {
        if (workspace == null || workspace.isBlank()) {
            throw new IllegalArgumentException("workspace must not be blank");
        }
        Path target = toPath(workspace.strip());
        String targetKey =
            ProjectCoordinationScope.directoryKey(ProjectCoordinationScope.canonical(target), caseInsensitive);
        for (Session session : sessions) {
            if (sameDirectory(session.workspace(), targetKey)) {
                return resolve(session);
            }
        }
        for (Project project : projects.list()) {
            if (project.folders().stream().anyMatch(folder -> sameDirectory(folder, targetKey))) {
                List<Path> folders = new ArrayList<>();
                project.folders().forEach(folder -> addPath(folders, folder));
                return ProjectCoordinationScope.of(project.id(), folders, caseInsensitive);
            }
        }
        return ProjectCoordinationScope.of(null, List.of(target), caseInsensitive);
    }

    private boolean sameDirectory(String candidate, String targetKey) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        Path path = toPath(candidate.strip());
        return ProjectCoordinationScope.directoryKey(ProjectCoordinationScope.canonical(path), caseInsensitive)
            .equals(targetKey);
    }

    private void addPath(List<Path> folders, String folder) {
        if (folder == null || folder.isBlank()) {
            return;
        }
        String text = folder.strip();
        try {
            folders.add(toPath(text));
        } catch (InvalidPathException e) {
            log.debug("跳过无法解析的项目目录 {}：{}", text, e.getMessage());
        }
    }

    private Path toPath(String text) {
        return Path.of(text);
    }
}
