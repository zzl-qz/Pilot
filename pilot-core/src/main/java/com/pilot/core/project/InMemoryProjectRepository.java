package com.pilot.core.project;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 进程内项目存储：无数据库模式与测试使用。 */
public final class InMemoryProjectRepository implements ProjectRepository {
    private final ConcurrentMap<String, Project> projects = new ConcurrentHashMap<>();

    @Override
    public Project create(String id, String name, List<String> folders, String baseCommit, Instant now) {
        Project project = new Project(id, name, folders, baseCommit, now, now);
        projects.put(id, project);
        return project;
    }

    @Override
    public Optional<Project> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(projects.get(id));
    }

    @Override
    public List<Project> list() {
        return projects.values().stream()
                .sorted(Comparator.comparing(Project::updatedAt).reversed())
                .toList();
    }

    @Override
    public boolean rename(String id, String name, Instant now) {
        Project existing = projects.get(id);
        if (existing == null) {
            return false;
        }
        projects.put(id, existing.rename(name, now));
        return true;
    }

    @Override
    public boolean updateFolders(String id, List<String> folders, Instant now) {
        Project existing = projects.get(id);
        if (existing == null) {
            return false;
        }
        projects.put(id, existing.withFolders(folders, now));
        return true;
    }

    @Override
    public boolean delete(String id) {
        return projects.remove(id) != null;
    }
}
