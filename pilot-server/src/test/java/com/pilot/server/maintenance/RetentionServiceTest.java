package com.pilot.server.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.session.SessionService;
import com.pilot.server.config.AttachmentLimits;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.session.AttachmentService;
import com.pilot.server.store.SqliteStore;
import com.pilot.server.workspace.RunSnapshotService;

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
        Files.setLastModifiedTime(liveOld, FileTime.from(Instant.now().minus(3, ChronoUnit.DAYS)));

        RetentionService.PurgeReport report = retention(attachments, sessions, 1).purgeNow();

        assertEquals(1, report.attachments(), "只清除孤儿文件");
        assertEquals(1, report.attachmentDirs(), "孤儿目录");
        assertFalse(Files.exists(orphan), "孤儿整目录回收");
        assertTrue(Files.exists(liveOld), "在册会话的历史消息可能仍引用旧附件");
        assertTrue(Files.exists(liveNew), "未过期文件保留");
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
            mock(BackupService.class), dataDir, sessions, attachments);
    }

    private static Path attachment(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve("12345678-1234-1234-1234-123456789abc-" + name), "content",
            StandardCharsets.UTF_8);
    }
}
