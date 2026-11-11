package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.pilot.core.event.RunStarted;
import com.pilot.core.loop.Message;
import com.pilot.core.session.SessionService;
import com.pilot.server.store.EventCodec;
import com.pilot.server.store.JdbcConversationRepository;
import com.pilot.server.store.JdbcSessionRepository;
import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.json.JsonMapper;

class AttachmentReferencesTest {
    @TempDir
    Path root;

    @Test
    void sqliteReferencesSurviveReopenAndOnlyAuthorizeTheExactInheritedFile() throws Exception {
        Path database = root.resolve("references.db");
        var store = new SqliteStore(database);
        var mapper = JsonMapper.builder().build();
        var sessions = new SessionService(Clock.systemUTC(), new JdbcSessionRepository(store, new EventCodec(mapper)));
        var memory = new JdbcConversationRepository(store, mapper);
        var attachments = new AttachmentService(root.resolve("中文 空格附件"));
        String source = sessions.createSession("来源").id();
        String fork = sessions.createSession("分支").id();
        String unrelated = sessions.createSession("无关").id();
        var prepared =
            attachments.prepare(source, "", List.of(new AttachmentService.Inbound("notes.txt", "text/plain", "YWJj")));
        Path saved;
        try (var files = Files.list(attachments.sessionDir(source))) {
            saved = files.findFirst().orElseThrow();
        }
        Path sibling =
            Files.writeString(saved.getParent().resolve("00000000-0000-0000-0000-000000000001-other.txt"), "private");
        sessions.publish(fork, "inherited", new RunStarted(prepared.content()));
        memory.save(fork, List.of(Message.user(prepared.content())));
        sessions.delete(source);

        var reopened = new SqliteStore(database);
        var beans = new StaticListableBeanFactory();
        beans.addBean("sqlite", reopened);
        var refs = new AttachmentReferences(beans.getBeanProvider(SqliteStore.class), mapper, sessions, memory, null,
            attachments);
        var tool = new ReadAttachmentTool(path -> refs.resolvePath(fork, path));
        assertTrue(tool.execute(Map.of("path", saved.toString())).contains("abc"));
        assertEquals(saved, refs.resolvePath(fork, saved.toString().replace('/', '\\')));
        assertThrows(IllegalArgumentException.class, () -> refs.resolvePath(unrelated, saved.toString()));
        assertThrows(IllegalArgumentException.class, () -> refs.resolvePath(fork, sibling.toString()));
        assertThrows(IllegalArgumentException.class,
            () -> refs.resolvePath(fork, saved.getParent() + "/../" + saved.getFileName()));
        assertThrows(IllegalArgumentException.class,
            () -> refs.resolvePath(fork, root.resolve("references.db").toString()));
        sessions.dropEventsFrom(fork, 1);
        memory.save(fork, List.of());
        assertTrue(tool.execute(Map.of("path", saved.toString())).startsWith("ERROR"));
    }

    @Test
    void rejectsSymlinkAttachments() throws Exception {
        var attachments = new AttachmentService(root.resolve("attachments"));
        Path directory = Files.createDirectories(attachments.sessionDir("session"));
        Path outside = Files.writeString(root.resolve("private.txt"), "private");
        String name = "00000000-0000-0000-0000-000000000001-link.txt";
        try {
            Files.createSymbolicLink(directory.resolve(name), outside);
        } catch (UnsupportedOperationException | FileSystemException e) {
            Assumptions.abort("当前平台未提供创建符号链接权限");
        }
        assertThrows(IllegalArgumentException.class, () -> attachments.resolveAttachment("session", name));
    }
}
