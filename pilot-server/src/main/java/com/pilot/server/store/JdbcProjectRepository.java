package com.pilot.server.store;

import com.pilot.core.project.Project;
import com.pilot.core.project.ProjectRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SQLite 版项目存储：项目头一张表，绑定文件夹一张表。 */
public final class JdbcProjectRepository implements ProjectRepository {
    private static final String INSERT_FOLDER =
            "INSERT INTO project_folders (project_id, position, path) VALUES (?, ?, ?)";

    private final SqliteStore store;

    public JdbcProjectRepository(SqliteStore store) {
        this.store = store;
    }

    @Override
    public Project create(String id, String name, List<String> folders, String baseCommit, Instant now) {
        Project project = new Project(id, name, folders, baseCommit, now, now);
        JdbcSupport.inTransaction(store, connection -> {
            JdbcSupport.exec(connection,
                    "INSERT INTO projects (id, name, created_at, updated_at, base_commit) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    statement -> {
                        statement.setString(1, id);
                        statement.setString(2, project.name());
                        statement.setString(3, now.toString());
                        statement.setString(4, now.toString());
                        statement.setString(5, project.baseCommit());
                    });
            JdbcSupport.batch(connection, INSERT_FOLDER, folderBinders(id, project.folders()));
        }, "创建项目失败: " + id);
        return project;
    }

    @Override
    public Optional<Project> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return JdbcSupport.queryOne(store,
                "SELECT id, name, created_at, updated_at, base_commit FROM projects WHERE id = ?",
                statement -> statement.setString(1, id),
                JdbcProjectRepository::mapHead,
                "读取项目失败: " + id)
                .map(project -> new Project(project.id(), project.name(), foldersOf(id),
                        project.baseCommit(), project.createdAt(), project.updatedAt()));
    }

    @Override
    public List<Project> list() {
        List<Project> heads = JdbcSupport.query(store,
                "SELECT id, name, created_at, updated_at, base_commit FROM projects ORDER BY updated_at DESC",
                statement -> { },
                JdbcProjectRepository::mapHead,
                "读取项目列表失败");
        if (heads.isEmpty()) {
            return List.of();
        }
        // 一次读完全部文件夹再分组，避免每个项目各查一次（N+1）
        Map<String, List<String>> folders = new HashMap<>();
        for (var row : JdbcSupport.query(store,
                "SELECT project_id, path FROM project_folders ORDER BY project_id, position",
                statement -> { },
                rows -> Map.entry(rows.getString("project_id"), rows.getString("path")),
                "读取项目文件夹失败")) {
            folders.computeIfAbsent(row.getKey(), key -> new ArrayList<>()).add(row.getValue());
        }
        return heads.stream()
                .map(project -> new Project(project.id(), project.name(),
                        folders.getOrDefault(project.id(), List.of()),
                        project.baseCommit(), project.createdAt(), project.updatedAt()))
                .toList();
    }

    @Override
    public boolean rename(String id, String name, Instant now) {
        return JdbcSupport.update(store, "UPDATE projects SET name = ?, updated_at = ? WHERE id = ?",
                statement -> {
                    statement.setString(1, name);
                    statement.setString(2, now.toString());
                    statement.setString(3, id);
                },
                "重命名项目失败: " + id) > 0;
    }

    @Override
    public boolean updateFolders(String id, List<String> folders, Instant now) {
        Project project = new Project(id, "placeholder", folders, now, now);
        boolean[] updated = {false};
        JdbcSupport.inTransaction(store, connection -> {
            JdbcSupport.exec(connection, "DELETE FROM project_folders WHERE project_id = ?",
                    statement -> statement.setString(1, id));
            JdbcSupport.batch(connection, INSERT_FOLDER, folderBinders(id, project.folders()));
            updated[0] = JdbcSupport.exec(connection, "UPDATE projects SET updated_at = ? WHERE id = ?",
                    statement -> {
                        statement.setString(1, now.toString());
                        statement.setString(2, id);
                    }) > 0;
        }, "更新项目文件夹失败: " + id);
        return updated[0];
    }

    @Override
    public boolean delete(String id) {
        boolean[] removed = {false};
        JdbcSupport.inTransaction(store, connection -> {
            JdbcSupport.exec(connection, "DELETE FROM project_folders WHERE project_id = ?",
                    statement -> statement.setString(1, id));
            removed[0] = JdbcSupport.exec(connection, "DELETE FROM projects WHERE id = ?",
                    statement -> statement.setString(1, id)) > 0;
        }, "删除项目失败: " + id);
        return removed[0];
    }

    /** 先建一个不带文件夹的项目头，再把文件夹一次填进去，避免每行查一次。 */
    private static Project mapHead(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new Project(
                rows.getString("id"),
                rows.getString("name"),
                List.of(),
                rows.getString("base_commit"),
                Instant.parse(rows.getString("created_at")),
                Instant.parse(rows.getString("updated_at")));
    }

    private List<String> foldersOf(String projectId) {
        return JdbcSupport.query(store,
                "SELECT path FROM project_folders WHERE project_id = ? ORDER BY position",
                statement -> statement.setString(1, projectId),
                rows -> rows.getString(1),
                "读取项目文件夹失败: " + projectId);
    }

    private static List<JdbcSupport.Binder> folderBinders(String projectId, List<String> folders) {
        List<JdbcSupport.Binder> binders = new ArrayList<>();
        for (int position = 0; position < folders.size(); position++) {
            String path = folders.get(position);
            int index = position;
            binders.add(statement -> {
                statement.setString(1, projectId);
                statement.setInt(2, index);
                statement.setString(3, path);
            });
        }
        return binders;
    }
}
