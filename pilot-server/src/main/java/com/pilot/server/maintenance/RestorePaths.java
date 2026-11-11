package com.pilot.server.maintenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pilot.core.multi.DiffSandbox;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 将恢复副本中的持久化路径换为新位置。原备份始终只读。 */
final class RestorePaths {
    private static final Set<String> PATH_FIELDS = Set.of("path", "workspace", "base", "workdir", "sourceWorkspace",
        "workspacePath", "absolutePath", "storedPath", "relativePath", "basePath");
    private static final Pattern ATTACHMENT_NOTE = Pattern.compile("(?<=已保存到 )[^\r\n]+?(?=，可用 read_attachment 读取)");

    private final List<Map.Entry<String, Path>> mappings;
    private final ObjectMapper mapper;

    RestorePaths(Map<String, Path> mappings, ObjectMapper mapper) {
        this.mappings = mappings.entrySet().stream().filter(entry -> !entry.getKey().isBlank())
            .sorted(Comparator.comparingInt((Map.Entry<String, Path> entry) -> entry.getKey().length()).reversed())
            .toList();
        this.mapper = mapper;
    }

    static String suffix(String source, String root) {
        if (root.isBlank() || source.isBlank()) {
            return null;
        }
        String normalized = source.replace('\\', '/');
        String prefix = root.replace('\\', '/');
        String child = prefix.endsWith("/") ? prefix : prefix + "/";
        return normalized.equals(prefix) ? ""
            : normalized.startsWith(child) ? normalized.substring(child.length()) : null;
    }

    String relocate(String value) throws IOException {
        for (var entry : mappings) {
            String relative = suffix(value, entry.getKey());
            if (relative != null) {
                return (relative.isEmpty() ? entry.getValue()
                    : entry.getValue().resolve(BackupManifest.relative(relative))).toString();
            }
        }
        return value;
    }

    private String rewriteNote(String value) throws IOException {
        var match = ATTACHMENT_NOTE.matcher(value);
        StringBuilder updated = new StringBuilder();
        while (match.find()) {
            match.appendReplacement(updated, Matcher.quoteReplacement(relocate(match.group())));
        }
        return match.appendTail(updated).toString();
    }

    private boolean rewriteJson(JsonNode node) throws IOException {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            for (String field : object.propertyNames()) {
                JsonNode child = object.get(field);
                if (child.isTextual()) {
                    String value = child.asText();
                    String replacement = PATH_FIELDS.contains(field) ? relocate(value)
                        : field.equals("content") || field.equals("input") ? rewriteNote(value) : value;
                    if (!value.equals(replacement)) {
                        object.put(field, replacement);
                        changed = true;
                    }
                } else {
                    changed |= rewriteJson(child);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                changed |= rewriteJson(child);
            }
        }
        return changed;
    }

    private String json(String text) throws IOException {
        JsonNode node = mapper.readTree(text);
        return node != null && rewriteJson(node) ? mapper.writeValueAsString(node) : text;
    }

    void database(Connection connection, String defaultWorkspace) throws IOException, SQLException {
        String relocated = relocate(defaultWorkspace);
        if (!defaultWorkspace.equals(relocated)) {
            try (var update = connection
                .prepareStatement("UPDATE sessions SET workspace = ? WHERE workspace IS NULL OR workspace = ''")) {
                update.setString(1, relocated);
                update.executeUpdate();
            }
        }
        for (String table : List.of("sessions", "run_checkpoints", "dispatches")) {
            rewriteColumn(connection, table, "workspace", this::relocate);
        }
        rewriteColumn(connection, "project_folders", "path", this::relocate);
        rewriteColumn(connection, "events", "payload", this::json);
        rewriteColumn(connection, "conversation_messages", "attachments", this::json);
        rewriteColumn(connection, "conversation_messages", "content", this::rewriteNote);
        rewriteColumn(connection, "scheduled_tasks", "payload", this::json);
        rewriteColumn(connection, "automation_documents", "payload", this::json);
    }

    @FunctionalInterface
    private interface Rewrite {
        String apply(String value) throws IOException;
    }

    private static void rewriteColumn(Connection connection, String table, String column, Rewrite rewrite)
        throws IOException, SQLException {
        try (var statement =
            connection.prepareStatement("SELECT 1 FROM sqlite_schema WHERE name = ? AND type = 'table'")) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return;
                }
            }
        }
        try (var select = connection.createStatement();
            var rows = select.executeQuery(
                "SELECT rowid, " + column + " FROM " + table + " WHERE " + column + " IS NOT NULL ORDER BY rowid");
            var update = connection.prepareStatement("UPDATE " + table + " SET " + column + " = ? WHERE rowid = ?")) {
            while (rows.next()) {
                String value = rows.getString(2);
                String replacement = rewrite.apply(value);
                if (!value.equals(replacement)) {
                    update.setString(1, replacement);
                    update.setLong(2, rows.getLong(1));
                    update.executeUpdate();
                }
            }
        }
    }

    void files(Path root) throws IOException {
        for (String directory : List.of("snapshots", "multi", "commands", "tasks", "apply-journal", "work-products",
            "file-versions")) {
            Path folder = root.resolve(directory);
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (var walk = Files.walk(folder, 2)) {
                for (Path file : walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                    String text = Files.readString(file);
                    String replacement = json(text);
                    if (!text.equals(replacement)) {
                        Files.writeString(file, replacement);
                    }
                    if (directory.equals("apply-journal") && file.getFileName().toString().equals("pending.json")) {
                        String workspace = mapper.readTree(replacement).path("workspace").asText("");
                        if (!workspace.isBlank()) {
                            Path target = folder.resolve(DiffSandbox.fingerprint(workspace));
                            if (!file.getParent().equals(target)) {
                                RestoreSwap.move(file.getParent(), target);
                            }
                        }
                    }
                }
            }
        }
    }
}
