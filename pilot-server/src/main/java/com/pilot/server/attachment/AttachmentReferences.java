package com.pilot.server.attachment;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.pilot.core.event.RunStarted;
import com.pilot.core.message.Attachment;
import com.pilot.core.message.Message;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 同一份上传附件可能被会话分支和对话检查点共同引用。 */
@Component
public class AttachmentReferences {
    public interface Checkpoints {
        RunCheckpoint load(String runId);
    }

    public record RunCheckpoint(List<Message> messages) {
    }

    public record Reference(String owner, String filename) {
    }

    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final Pattern FILENAME = Pattern.compile("[a-fA-F0-9-]{36}-[A-Za-z0-9._-]{1,60}");
    private static final Pattern SAVED_NOTE = Pattern.compile("(?<=已保存到 )[^\\r\\n]+?(?=，可用 read_attachment 读取)");

    private final SqliteStore sqlite;
    private final ObjectMapper mapper;
    private final SessionService sessions;
    private final ConversationRepository conversations;
    private final Checkpoints checkpoints;
    private final AttachmentService attachments;

    public AttachmentReferences(ObjectProvider<SqliteStore> store, ObjectMapper mapper, SessionService sessions,
        ConversationRepository conversations, Checkpoints checkpoints, AttachmentService attachments) {
        this.sqlite = store.getIfAvailable();
        this.mapper = mapper;
        this.sessions = sessions;
        this.conversations = conversations;
        this.checkpoints = checkpoints;
        this.attachments = attachments;
    }

    public static Set<Reference> read(Connection connection, String attachmentRoot, ObjectMapper mapper, String session)
        throws SQLException {
        Set<Reference> result = new HashSet<>();
        try (var statement = connection.prepareStatement("""
            SELECT json_extract(payload, '$.attachments')
              FROM events WHERE type = 'run.started' AND json_valid(payload) = 1 AND (? IS NULL OR session_id = ?)
            UNION ALL
            SELECT attachments
              FROM conversation_messages WHERE (? IS NULL OR session_id = ?)
            """)) {
            for (int parameter = 1; parameter <= 4; parameter++) {
                statement.setString(parameter, session);
            }
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String json = rows.getString(1);
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
            var checkpoint = checkpoints.load(run);
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

    /** 供会话分支复制历史时提取原会话合法拥有的非图片落盘附件，转为结构化引用；绝不接受未归属原会话的自由文本路径。 */
    public List<Attachment> ownedTextReferences(String sourceSessionId, String text) {
        if (sourceSessionId == null || sourceSessionId.isBlank() || text == null || text.isBlank()) {
            return List.of();
        }
        String ownDir = AttachmentService.storageDirectory(sourceSessionId);
        Set<Reference> inherited = null;
        Map<String, Attachment> found = new LinkedHashMap<>();
        var matcher = SAVED_NOTE.matcher(text);
        while (matcher.find()) {
            Reference ref = reference(matcher.group(), root()).orElse(null);
            if (ref == null) {
                continue;
            }
            if (!ref.owner().equals(ownDir)) {
                if (inherited == null) {
                    inherited = retained(sourceSessionId);
                }
                if (!inherited.contains(ref)) {
                    continue;
                }
            }
            try {
                Path file = attachments.resolveAttachment(ref.owner(), ref.filename());
                String normalized = file.toAbsolutePath().normalize().toString();
                found.putIfAbsent(normalized, new Attachment(ref.filename(), "application/octet-stream", normalized));
            } catch (IllegalArgumentException ignored) {
                // 仅继承真实存在的合法附件
            }
        }
        return found.isEmpty() ? List.of() : new ArrayList<>(found.values());
    }

    /** 工具传入的是完整路径：必须匹配本会话上传或历史中仍保留的同一个文件。 */
    public Path resolvePath(String session, String path) {
        Reference reference = reference(path, root()).orElseThrow(() -> new IllegalArgumentException("无效的附件路径"));
        if (!reference.owner().equals(AttachmentService.storageDirectory(session))
            && !retained(session).contains(reference)) {
            throw new IllegalArgumentException("附件不属于当前会话或已不再被引用");
        }
        return attachments.resolveAttachment(reference.owner(), reference.filename());
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
            message.attachments().forEach(attachment -> add(result, attachment.path(), root));
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
