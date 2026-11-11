package com.pilot.server.session;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.pilot.core.event.RunStarted;
import com.pilot.core.loop.Message;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.store.SqliteStore;
import com.pilot.server.workspace.RunSnapshotService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 同一份上传附件可能被会话分支和对话检查点共同引用。 */
@Component
public class AttachmentReferences {
    public record Reference(String owner, String filename) {
    }

    private static final Pattern NOTE = Pattern.compile("(?<=已保存到 )[^\\r\\n]+?(?=，可用 read_attachment 读取)");
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final Pattern FILENAME = Pattern.compile("[a-fA-F0-9-]{36}-[A-Za-z0-9._-]{1,60}");

    private final SqliteStore sqlite;
    private final ObjectMapper mapper;
    private final SessionService sessions;
    private final ConversationRepository conversations;
    private final RunSnapshotService snapshots;
    private final AttachmentService attachments;

    public AttachmentReferences(ObjectProvider<SqliteStore> store, ObjectMapper mapper, SessionService sessions,
        ConversationRepository conversations, RunSnapshotService snapshots, AttachmentService attachments) {
        this.sqlite = store.getIfAvailable();
        this.mapper = mapper;
        this.sessions = sessions;
        this.conversations = conversations;
        this.snapshots = snapshots;
        this.attachments = attachments;
    }

    public static Set<Reference> read(Connection connection, String attachmentRoot, ObjectMapper mapper, String session)
        throws SQLException {
        Set<Reference> result = new HashSet<>();
        try (var statement = connection.prepareStatement("""
            SELECT json_extract(payload, '$.input'), json_extract(payload, '$.attachments')
              FROM events WHERE type = 'run.started' AND (? IS NULL OR session_id = ?)
            UNION ALL
            SELECT CASE WHEN role = 'user' THEN content ELSE NULL END, attachments
              FROM conversation_messages WHERE (? IS NULL OR session_id = ?)
            """)) {
            for (int parameter = 1; parameter <= 4; parameter++) {
                statement.setString(parameter, session);
            }
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    collectNote(result, rows.getString(1), attachmentRoot);
                    String json = rows.getString(2);
                    if (json != null) {
                        for (JsonNode item : mapper.readTree(json)) {
                            add(result, item.path("path").asText(""), attachmentRoot);
                        }
                    }
                }
            }
        }
        return result;
    }

    public Set<String> retainedOwners() {
        Set<Reference> references = retained(null);
        Set<String> runs = new HashSet<>();
        if (sqlite == null) {
            for (Session session : sessions.list()) {
                sessions.events(session.id()).forEach(event -> runs.add(event.runId()));
            }
        } else {
            runs.addAll(sqlite.retainedEventReferences().runs());
            runs.addAll(sqlite.activeRunIds());
        }
        for (String run : runs) {
            var checkpoint = snapshots.conversation(run);
            if (checkpoint != null) {
                collectMessages(references, checkpoint.messages(), root());
            }
        }
        Set<String> owners = new HashSet<>();
        references.forEach(reference -> owners.add(reference.owner()));
        return owners;
    }

    public Path resolve(String session, String filename) {
        try {
            return attachments.resolveAttachment(session, filename);
        } catch (IllegalArgumentException e) {
            if (filename == null || !FILENAME.matcher(filename).matches()) {
                throw e;
            }
            return retained(session).stream().filter(reference -> filename.equals(reference.filename())).findFirst()
                .map(reference -> attachments.resolveAttachment(reference.owner(), filename)).orElseThrow(() -> e);
        }
    }

    private Set<Reference> retained(String sessionId) {
        if (sqlite != null) {
            try (Connection connection = sqlite.open()) {
                return read(connection, root(), mapper, sessionId);
            } catch (SQLException e) {
                throw new IllegalStateException("读取附件引用失败", e);
            }
        }
        Set<Reference> result = new HashSet<>();
        List<String> ids = sessionId == null ? sessions.list().stream().map(Session::id).toList() : List.of(sessionId);
        for (String id : ids) {
            sessions.events(id).forEach(event -> {
                if (event.payload() instanceof RunStarted started) {
                    collectNote(result, started.input(), root());
                    started.attachments().forEach(attachment -> add(result, attachment.path(), root()));
                }
            });
            collectMessages(result, conversations.load(id), root());
        }
        return result;
    }

    private String root() {
        return attachments.root().toAbsolutePath().normalize().toString();
    }

    private static void collectMessages(Set<Reference> result, List<Message> messages, String root) {
        for (Message message : messages) {
            if ("user".equals(message.role())) {
                collectNote(result, message.content(), root);
            }
            message.attachments().forEach(attachment -> add(result, attachment.path(), root));
        }
    }

    private static void collectNote(Set<Reference> result, String text, String root) {
        if (text != null) {
            var matches = NOTE.matcher(text);
            while (matches.find()) {
                add(result, matches.group(), root);
            }
        }
    }

    private static void add(Set<Reference> references, String path, String root) {
        reference(path, root).ifPresent(references::add);
    }

    static Optional<Reference> reference(String path, String root) {
        if (path == null || root.isBlank()) {
            return Optional.empty();
        }
        String value = path.replace('\\', '/');
        String prefix = root.replace('\\', '/') + "/";
        if (!value.startsWith(prefix)) {
            return Optional.empty();
        }
        String[] parts = value.substring(prefix.length()).split("/", -1);
        if (parts.length == 2 && OWNER.matcher(parts[0]).matches() && !Set.of(".", "..").contains(parts[0])
            && FILENAME.matcher(parts[1]).matches()) {
            return Optional.of(new Reference(parts[0], parts[1]));
        }
        return Optional.empty();
    }
}
