package com.pilot.server.maintenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

import com.pilot.server.session.AttachmentReferences;

import tools.jackson.databind.ObjectMapper;

final class BackupDatabase {
    private BackupDatabase() {
    }

    static Connection readOnly(Path database) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().toUri() + "?mode=ro&immutable=1");
    }

    static Set<String> workspaces(Path database, String defaultWorkspace) throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        try (var connection = readOnly(database); var statement = connection.createStatement();
            var rows = statement.executeQuery("""
                SELECT DISTINCT workspace FROM sessions
                UNION SELECT path FROM project_folders WHERE path <> ''
                """)) {
            while (rows.next()) {
                String path = rows.getString(1);
                paths.add(path == null || path.isBlank() ? defaultWorkspace : path);
            }
        } catch (SQLException e) {
            throw new IOException("无法读取备份中的工作区：" + e.getMessage(), e);
        }
        return paths;
    }

    static void check(Path database, Path attachments, String attachmentSource, ObjectMapper mapper)
        throws IOException {
        try (var connection = readOnly(database); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA quick_check")) {
                if (!rows.next() || !"ok".equals(rows.getString(1))) {
                    throw new IOException("备份数据库未通过 SQLite 完整性检查");
                }
            }
            for (var reference : AttachmentReferences.read(connection, attachmentSource, mapper, null)) {
                Path relative = BackupManifest.relative(reference.owner() + "/" + reference.filename());
                if (!Files.isDirectory(attachments) || !BackupManifest.regularFile(attachments, relative)) {
                    throw new IOException("备份缺少消息引用的附件: " + relative);
                }
            }
        } catch (SQLException e) {
            throw new IOException("备份数据库不可读取：" + e.getMessage(), e);
        }
    }
}
