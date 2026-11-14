package com.pilot.server.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import com.pilot.core.event.RunStarted;
import com.pilot.core.message.Attachment;
import com.pilot.core.message.Message;
import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.session.SessionService;
import com.pilot.server.attachment.AttachmentLimits;
import com.pilot.server.attachment.AttachmentService;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.common.data.StorageHealth;
import com.pilot.server.store.EventCodec;
import com.pilot.server.store.JdbcConversationRepository;
import com.pilot.server.store.JdbcSessionRepository;
import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.json.JsonMapper;

class BackupServiceTest {

    @TempDir
    Path dataDir;

    @SuppressWarnings("unchecked")
    private BackupService service(SqliteStore store) {
        ObjectProvider<SqliteStore> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(store);
        AttachmentService attachments = Mockito.mock(AttachmentService.class);
        Mockito.when(attachments.root()).thenReturn(dataDir.resolve(".pilot/attachments"));
        return new BackupService(provider, new PilotDataDir(dataDir.resolve(".pilot").toString()),
            JsonMapper.builder().build(), attachments);
    }

    @Test
    void backupCapturesDatabaseAndLooseFiles() throws Exception {
        Path root = dataDir.resolve(".pilot");
        Files.createDirectories(root);
        SqliteStore store = new SqliteStore(root.resolve("pilot.db"));
        Files.createDirectories(root.resolve("multi/ses_1"));
        Files.writeString(root.resolve("multi/ses_1/tasks.json"), "[{\"id\":\"T1\"}]");
        Files.createDirectories(root.resolve("validation"));
        Files.writeString(root.resolve("validation/ses_1.json"), "{\"entries\":[]}");
        byte[] binary = new byte[] {(byte)0xff, 0, (byte)0x80, 0x41};
        Files.createDirectories(root.resolve("attachments/ses_1"));
        Files.write(root.resolve("attachments/ses_1/input.bin"), binary);
        Files.createDirectories(root.resolve("snapshots/run_1/files"));
        Files.writeString(root.resolve("snapshots/run_1/meta.json"), "{}");
        Files.createDirectories(root.resolve("tasks/ses_1"));
        Files.writeString(root.resolve("tasks/ses_1/current.json"), "{}");
        Files.createDirectories(root.resolve("mobile/receipts"));
        Files.writeString(root.resolve("mobile/receipts/state.json"), "{}");

        BackupService.BackupReport report = service(store).backup();

        assertTrue(report.ok(), report.message());
        assertTrue(report.files() >= 3, "至少包含数据库、任务板和校验证据: " + report.files());
        Path target = Path.of(report.directory());
        assertTrue(Files.isRegularFile(target.resolve("manifest.json")));
        assertTrue(Files.isRegularFile(target.resolve("pilot.db")));
        assertTrue(Files.isRegularFile(target.resolve("multi/ses_1/tasks.json")));
        assertTrue(Files.isRegularFile(target.resolve("attachments/ses_1/input.bin")));
        assertTrue(Files.isRegularFile(target.resolve("snapshots/run_1/meta.json")));
        assertTrue(Files.isRegularFile(target.resolve("tasks/ses_1/current.json")));
        assertTrue(Files.isRegularFile(target.resolve("mobile/receipts/state.json")));
        String manifest = Files.readString(target.resolve("manifest.json"));
        assertTrue(manifest.contains("sha256"), manifest.substring(0, Math.min(200, manifest.length())));
        assertTrue(manifest.contains("restoreHint"));
        assertTrue(manifest.contains("consistency"));
        assertTrue(report.message().contains("受管数据已核验"), report.message());
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
        assertTrue(manifest.contains(expectedHash), manifest);
        assertTrue(Files.isRegularFile(target.resolve("COMPLETE")));
    }

    @Test
    void backupRecordsAndCapturesConfiguredExternalAttachments() throws IOException {
        ObjectProvider<SqliteStore> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(new SqliteStore(dataDir.resolve("db.sqlite")));
        Path external = dataDir.resolve("outside-attachments");
        Files.createDirectories(external.resolve("ses_1"));
        Files.writeString(external.resolve("ses_1/input.txt"), "输入");
        AttachmentService attachments = Mockito.mock(AttachmentService.class);
        Mockito.when(attachments.root()).thenReturn(external);
        BackupService backups = new BackupService(provider, new PilotDataDir(dataDir.resolve(".pilot").toString()),
            JsonMapper.builder().build(), attachments);

        BackupService.BackupReport report = backups.backup();

        assertTrue(report.message().contains(external.toString()), report.message());
        assertTrue(Files.isRegularFile(Path.of(report.directory()).resolve("_external/attachments/ses_1/input.txt")));
        assertTrue(
            Files.readString(Path.of(report.directory()).resolve("manifest.json")).contains("outside-attachments"));
    }

    @Test
    void includesMcpConfigAndCopiesNestedAttachmentsOnce() throws IOException {
        Path root = dataDir.resolve(".pilot");
        SqliteStore store = new SqliteStore(root.resolve("studio/custom.db"));
        Path nested = Files.createDirectories(root.resolve("studio/attachments"));
        Files.writeString(nested.resolve("image.bin"), "fixture");
        Files.writeString(root.resolve("mcp.json"), "{\"mcpServers\":{}}");
        @SuppressWarnings("unchecked")
        ObjectProvider<SqliteStore> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(store);
        AttachmentService attachments = Mockito.mock(AttachmentService.class);
        Mockito.when(attachments.root()).thenReturn(nested);
        BackupService backups =
            new BackupService(provider, new PilotDataDir(root.toString()), JsonMapper.builder().build(), attachments);

        BackupService.BackupReport report = backups.backup();

        assertTrue(report.ok(), report.message());
        assertEquals(3, report.files());
        Path target = Path.of(report.directory());
        assertTrue(Files.isRegularFile(target.resolve("mcp.json")));
        assertTrue(Files.isRegularFile(target.resolve("studio/attachments/image.bin")));
        assertFalse(Files.exists(target.resolve("studio/custom.db")));
        assertTrue(backups.verifyLatestBackup().valid());
    }

    @Test
    void repeatedBackupsUseUniquePublishedDirectoriesAndIgnoreIncompleteOnes() throws IOException {
        BackupService backups = service(new SqliteStore(dataDir.resolve("db.sqlite")));
        BackupService.BackupReport first = backups.backup();
        BackupService.BackupReport second = backups.backup();
        Path incomplete = dataDir.resolve(".pilot/backups/incomplete");
        Files.createDirectories(incomplete);
        Files.writeString(incomplete.resolve("partial.db"), "partial");

        assertTrue(first.ok(), first.message());
        assertTrue(second.ok(), second.message());
        assertFalse(first.directory().equals(second.directory()));
        assertEquals(2, backups.status().backups());
    }

    @Test
    void statusReportsBackupCountAndStorageHealth() throws IOException {
        Path root = dataDir.resolve(".pilot");
        Files.createDirectories(root);
        SqliteStore store = new SqliteStore(root.resolve("pilot.db"));
        BackupService backups = service(store);
        backups.backup();

        StorageHealth.markDegraded("validation", "磁盘只读");
        BackupService.Status status = backups.status();

        assertEquals(1, status.backups());
        assertFalse(status.storage().healthy());
        assertTrue(status.storage().issues().getFirst().reason().contains("磁盘只读"));
        StorageHealth.reset();
    }

    @Test
    void verifiesBackupBytesAndReportsTampering() throws IOException {
        BackupService backups = service(null);
        Path source = dataDir.resolve(".pilot/tasks/session/state.bin");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[] {(byte)0xff, 0, 0x42});
        BackupService.BackupReport report = backups.backup();

        BackupService.VerificationReport valid = backups.verifyLatestBackup();
        assertTrue(valid.valid(), valid.failures().toString());
        assertEquals(report.directory(), valid.directory());
        assertEquals(report.files(), valid.checkedFiles());

        Files.writeString(Path.of(report.directory()).resolve("tasks/session/state.bin"), "tampered");
        BackupService.VerificationReport invalid = backups.verifyLatestBackup();
        assertFalse(invalid.valid());
        assertTrue(
            invalid.failures().stream()
                .anyMatch(failure -> failure.contains("大小不匹配") || failure.contains("SHA-256 不匹配")),
            invalid.failures().toString());

        Files.writeString(Path.of(report.directory()).resolve("unlisted.txt"), "extra");
        BackupService.VerificationReport extra = backups.verifyLatestBackup();
        assertFalse(extra.valid());
        assertTrue(extra.failures().stream().anyMatch(failure -> failure.contains("未列入清单")),
            extra.failures().toString());
    }

    @Test
    void purgeRemovesOldBackupsOnly() throws IOException {
        Path root = dataDir.resolve(".pilot");
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("backups/20200101-000000"));
        Path legacy = root.resolve("backups/20200101-000000");
        writeLegacyBackup(legacy);
        Files.setLastModifiedTime(root.resolve("backups/20200101-000000"), FileTime.fromMillis(0));

        BackupService backups = service(null);
        assertEquals(1, backups.purgeOlderThan(Instant.now().minusSeconds(60)));
        assertFalse(Files.exists(root.resolve("backups/20200101-000000")));
    }

    @Test
    void excludesV3WithoutMarkerAndStagingEvenWithMarker() throws IOException {
        BackupService backups = service(new SqliteStore(dataDir.resolve("db.sqlite")));
        Path published = Path.of(backups.backup().directory());
        Path marker = published.resolve("COMPLETE");
        String created = Files.readString(marker);
        Files.delete(marker);
        Path manifest = published.resolve("manifest.json");
        Files.writeString(manifest,
            Files.readString(manifest).replace("\"schemaVersion\":\"4\"", "\"schemaVersion\":\"3\""));

        assertEquals(0, backups.status().backups());
        assertFalse(backups.verifyLatestBackup().valid());

        Files.writeString(marker, created);
        assertTrue(backups.verifyLatestBackup().valid());
        Files.move(published, published.resolveSibling(".interrupted.tmp"));
        assertEquals(0, backups.status().backups());
        assertFalse(backups.verifyLatestBackup().valid());
    }

    @Test
    void verifiesV2BinaryPayloadWithItsOriginalAlgorithm() throws IOException {
        BackupService backups = service(null);
        Path legacy = Files.createDirectories(dataDir.resolve(".pilot/backups/20200101-000000"));
        writeLegacyBackup(legacy);
        BackupService.VerificationReport result = backups.verifyLatestBackup();
        assertTrue(result.valid(), result.failures().toString());
        assertTrue(result.message().contains("V2"));
    }

    @Test
    void cannotPublishAnEmptyFileBackup() {
        BackupService backups = service(null);
        assertFalse(backups.backup().ok());
        assertEquals(0, backups.status().backups());
    }

    @Test
    void backupWaitsForTheWholeDatabaseAndAttachmentMutation() throws Exception {
        PilotDataDir data = new PilotDataDir(dataDir.resolve(".pilot").toString());
        SqliteStore sqlite = new SqliteStore(data.file("pilot.db"));
        SessionService sessions = new SessionService(Clock.systemUTC(),
            new JdbcSessionRepository(sqlite, new EventCodec(JsonMapper.builder().build())));
        AttachmentService attachments = new AttachmentService("", data, AttachmentLimits.defaults());
        String session = sessions.createSession("待删除").id();
        Path directory = Files.createDirectories(attachments.sessionDir(session));
        Path image = Files.writeString(directory.resolve("00000000-0000-0000-0000-000000000001-image.png"), "image");
        sessions.publish(session, "run",
            new RunStarted("带图片", List.of(new Attachment("image.png", "image/png", image.toString()))));
        BackupService backups = service(sqlite, data, attachments);

        CountDownLatch deletedFromDb = new CountDownLatch(1);
        CountDownLatch deleteFile = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion = executor.submit(() -> {
                try (var activity = data.maintenance().activity()) {
                    sessions.delete(session);
                    deletedFromDb.countDown();
                    if (!deleteFile.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待删除附件超时");
                    }
                    attachments.deleteBySession(session);
                }
                return null;
            });
            try {
                assertTrue(deletedFromDb.await(5, TimeUnit.SECONDS));
                var report = backups.backup();
                assertFalse(report.ok());
                assertTrue(report.message().contains("正在进行"), report.message());
                assertEquals(0, backups.status().backups());
            } finally {
                deleteFile.countDown();
            }
            deletion.get(5, TimeUnit.SECONDS);
        }
        var report = backups.backup();
        assertTrue(report.ok(), report.message());
        assertTrue(backups.verifyLatestBackup().valid());
        assertFalse(Files.exists(Path.of(report.directory()).resolve("attachments").resolve(session)));
    }

    @Test
    void refusesToPublishDatabaseWithMissingAttachment() {
        PilotDataDir data = new PilotDataDir(dataDir.resolve(".pilot").toString());
        SqliteStore sqlite = new SqliteStore(data.file("pilot.db"));
        SessionService sessions = new SessionService(Clock.systemUTC(),
            new JdbcSessionRepository(sqlite, new EventCodec(JsonMapper.builder().build())));
        String session = sessions.createSession("附件缺失").id();
        sessions.publish(session, "run", new RunStarted("带图片", List.of(new Attachment("missing.png", "image/png",
            data.file("attachments", session, "00000000-0000-0000-0000-000000000001-missing.png").toString()))));
        BackupService backups = service(sqlite);

        var report = backups.backup();
        assertFalse(report.ok());
        assertTrue(report.message().contains("缺少消息引用的附件"), report.message());
        assertEquals(0, backups.status().backups());
        StorageHealth.reset();
    }

    @Test
    void verifiesSharedUploadsWithoutTreatingToolImagesAsUploads() throws IOException {
        PilotDataDir data = new PilotDataDir(dataDir.resolve(".pilot").toString());
        SqliteStore sqlite = new SqliteStore(data.file("pilot.db"));
        var mapper = JsonMapper.builder().build();
        SessionService sessions =
            new SessionService(Clock.systemUTC(), new JdbcSessionRepository(sqlite, new EventCodec(mapper)));
        String owner = sessions.createSession("原会话").id();
        String fork = sessions.createSession("分支").id();
        Path dir = Files.createDirectories(data.file("attachments", owner));
        Path upload = Files.writeString(dir.resolve("00000000-0000-0000-0000-000000000001-file.png"), "image");
        var conversations = new JdbcConversationRepository(sqlite, mapper);
        conversations.save(fork,
            List.of(Message.user("共享", List.of(new Attachment("file.png", "image/png", upload.toString()),
                new Attachment("tool.png", "image/png", dataDir.resolve("workspace/tool.png").toString())))));
        BackupService backup = service(sqlite);

        assertTrue(backup.backup().ok());
        assertTrue(backup.verifyLatestBackup().valid());
        Files.delete(upload);
        assertFalse(backup.backup().ok());
        StorageHealth.reset();
    }

    @SuppressWarnings("unchecked")
    private BackupService service(SqliteStore sqlite, PilotDataDir data, AttachmentService attachments) {
        ObjectProvider<SqliteStore> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(sqlite);
        return new BackupService(provider, data, JsonMapper.builder().build(), attachments);
    }

    private static void writeLegacyBackup(Path legacy) throws IOException {
        byte[] payload = {0, (byte)0xff};
        Files.write(legacy.resolve("bytes.bin"), payload);
        String hash = DiffSandbox.fingerprint(new String(payload, StandardCharsets.ISO_8859_1));
        Files.writeString(legacy.resolve("manifest.json"), """
            {"schemaVersion":"2","files":[{"path":"bytes.bin","bytes":2,"sha256":"%s"}]}
            """.formatted(hash));
    }
}
