package com.pilot.server.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import com.pilot.core.event.RunStarted;
import com.pilot.core.loop.Message;
import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.session.SessionService;
import com.pilot.server.config.AttachmentLimits;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.session.AttachmentService;
import com.pilot.server.session.RunRegistry;
import com.pilot.server.store.EventCodec;
import com.pilot.server.store.JdbcSessionRepository;
import com.pilot.server.store.SqliteStore;
import com.pilot.server.validation.ValidationEvidence;
import com.pilot.server.validation.ValidationLedger;
import com.pilot.server.workspace.RunSnapshotService;

import tools.jackson.databind.json.JsonMapper;

class RetentionServiceTest {
    @TempDir
    Path temp;

    @Test
    void attachmentsStayWhenRetentionDisabled() throws IOException {
        AttachmentService attachments = attachments();
        Path file = attachment(attachments.sessionDir("ses_1"), "old.png");

        RetentionService.PurgeReport report = retention(attachments, 0).purgeNow();

        assertEquals(0, report.attachments());
        assertTrue(Files.exists(file), "关闭时不清理附件");
    }

    @Test
    void purgesOrphanDirectoriesAndKeepsLiveSessionAttachments() throws IOException {
        AttachmentService attachments = attachments();
        SessionService sessions = new SessionService(Clock.systemUTC(), new InMemorySessionRepository());
        String liveId = sessions.createSession("标题", null).id();
        Path liveOld = attachment(attachments.sessionDir(liveId), "old.png");
        Path liveNew = attachment(attachments.sessionDir(liveId), "new.txt");
        Path orphan = attachment(attachments.sessionDir("ses_gone"), "gone.pdf");
        Path recentOrphan = attachment(attachments.sessionDir("ses_recent"), "recent.pdf");
        Files.setLastModifiedTime(liveOld, FileTime.from(Instant.now().minus(3, ChronoUnit.DAYS)));
        Files.setLastModifiedTime(orphan.getParent(), FileTime.from(Instant.now().minus(3, ChronoUnit.DAYS)));

        RetentionService.PurgeReport report = retention(attachments, sessions, 1).purgeNow();

        assertEquals(1, report.attachments(), "只清除孤儿文件");
        assertEquals(1, report.attachmentDirs(), "孤儿目录");
        assertFalse(Files.exists(orphan), "孤儿整目录回收");
        assertTrue(Files.exists(liveOld), "在册会话的历史消息可能仍引用旧附件");
        assertTrue(Files.exists(liveNew), "未过期文件保留");
        assertTrue(Files.exists(recentOrphan), "未超过保留期的孤儿目录保留");
    }

    @Test
    void keepsReferencesFromRetainedEventsAndInvalidatesExpiredEvidence() throws IOException {
        PilotDataDir data = new PilotDataDir(temp.resolve("data").toString());
        var mapper = JsonMapper.builder().build();
        SqliteStore sqlite = new SqliteStore(data.file("pilot.db"));
        var repo = new JdbcSessionRepository(sqlite, new EventCodec(mapper));
        SessionService current = new SessionService(Clock.systemUTC(), repo);
        Instant old = Instant.now().minus(3, ChronoUnit.DAYS);
        SessionService past = new SessionService(Clock.fixed(old, ZoneOffset.UTC), repo);
        String referenced = current.createSession("仍有记录").id();
        String expired = past.createSession("过期").id();
        current.publish(referenced, "run_kept", new RunStarted("当前"));
        past.publish(expired, "run_old", new RunStarted("过期"));
        RunSnapshotService snapshots = new RunSnapshotService(mapper, data);
        for (String run : List.of("run_kept", "run_old")) {
            snapshots.saveConversation(run, List.of(Message.user("记录")), List.of());
            Files.setLastModifiedTime(data.file("snapshots", run), FileTime.from(old));
        }
        for (String session : List.of(referenced, expired)) {
            Path trace = data.file("traces", session + ".jsonl");
            Files.createDirectories(trace.getParent());
            Files.writeString(trace, "{}");
            Files.setLastModifiedTime(trace, FileTime.from(old));
        }
        ValidationLedger ledger = new ValidationLedger(data, mapper);
        ledger.record(
            new ValidationEvidence("val", referenced, "run_kept", "hash", List.of(), List.of(), "passed", "", "", old));
        Files.setLastModifiedTime(data.file("validation", referenced + ".json"), FileTime.from(old));
        assertTrue(ledger.report(referenced, "hash").current());

        @SuppressWarnings("unchecked")
        ObjectProvider<SqliteStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sqlite);
        var service = new RetentionService(1, 0, 1, 0, provider, snapshots, mock(BackupService.class), data, current,
            attachments(), ledger, new RunRegistry());
        var report = service.purgeNow();

        assertEquals(1, report.events());
        assertEquals(1, report.snapshotRuns());
        assertEquals(1, report.traces());
        assertEquals(1, report.validationFiles());
        assertTrue(snapshots.conversation("run_kept") != null);
        assertTrue(Files.exists(data.file("traces", referenced + ".jsonl")));
        assertFalse(ledger.report(referenced, "hash").current());
    }

    private AttachmentService attachments() {
        return new AttachmentService("", new PilotDataDir(temp.resolve("data").toString()),
            AttachmentLimits.defaults());
    }

    private RetentionService retention(AttachmentService attachments, int attachmentsDays) {
        return retention(attachments, new SessionService(Clock.systemUTC(), new InMemorySessionRepository()),
            attachmentsDays);
    }

    private RetentionService retention(AttachmentService attachments, SessionService sessions, int attachmentsDays) {
        @SuppressWarnings("unchecked")
        ObjectProvider<SqliteStore> store = mock(ObjectProvider.class);
        PilotDataDir dataDir = new PilotDataDir(temp.resolve("data").toString());
        return new RetentionService(0, 0, 0, attachmentsDays, store, mock(RunSnapshotService.class),
            mock(BackupService.class), dataDir, sessions, attachments,
            new ValidationLedger(dataDir, JsonMapper.builder().build()), new RunRegistry());
    }

    private static Path attachment(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve("12345678-1234-1234-1234-123456789abc-" + name), "content",
            StandardCharsets.UTF_8);
    }
}
