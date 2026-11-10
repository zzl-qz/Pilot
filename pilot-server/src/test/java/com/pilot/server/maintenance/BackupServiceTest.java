package com.pilot.server.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import com.pilot.server.config.PilotDataDir;
import com.pilot.server.session.AttachmentService;
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
        assertTrue(report.message().contains("暂停写入"), report.message());
        String expectedHash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
        assertTrue(manifest.contains(expectedHash), manifest);
        assertTrue(Files.isRegularFile(target.resolve("COMPLETE")));
    }

    @Test
    void backupWarnsWhenAttachmentsAreOutsideTheDataDirectory() throws IOException {
        ObjectProvider<SqliteStore> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(null);
        Path external = dataDir.resolve("outside-attachments");
        AttachmentService attachments = Mockito.mock(AttachmentService.class);
        Mockito.when(attachments.root()).thenReturn(external);
        BackupService backups = new BackupService(provider, new PilotDataDir(dataDir.resolve(".pilot").toString()),
            JsonMapper.builder().build(), attachments);

        BackupService.BackupReport report = backups.backup();

        assertTrue(report.message().contains(external.toString()), report.message());
        assertTrue(Files.readString(Path.of(report.directory()).resolve("manifest.json")).contains("outside-attachments"));
    }

    @Test
    void repeatedBackupsUseUniquePublishedDirectoriesAndIgnoreIncompleteOnes() throws IOException {
        BackupService backups = service(null);
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
        assertTrue(invalid.failures().stream().anyMatch(failure -> failure.contains("大小不匹配")
            || failure.contains("SHA-256 不匹配")), invalid.failures().toString());

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
        Files.writeString(root.resolve("backups/20200101-000000/manifest.json"), "{}");
        Files.setLastModifiedTime(root.resolve("backups/20200101-000000"), FileTime.fromMillis(0));

        BackupService backups = service(null);
        assertEquals(1, backups.purgeOlderThan(Instant.now().minusSeconds(60)));
        assertFalse(Files.exists(root.resolve("backups/20200101-000000")));
    }
}
