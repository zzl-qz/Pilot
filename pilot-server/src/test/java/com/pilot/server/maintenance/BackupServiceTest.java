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
import java.time.Instant;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import com.pilot.core.multi.DiffSandbox;
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
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
        assertTrue(manifest.contains(expectedHash), manifest);
        assertTrue(Files.isRegularFile(target.resolve("COMPLETE")));
    }

    @Test
    void backupWarnsWhenAttachmentsAreOutsideTheDataDirectory() throws IOException {
        ObjectProvider<SqliteStore> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(new SqliteStore(dataDir.resolve("db.sqlite")));
        Path external = dataDir.resolve("outside-attachments");
        AttachmentService attachments = Mockito.mock(AttachmentService.class);
        Mockito.when(attachments.root()).thenReturn(external);
        BackupService backups = new BackupService(provider, new PilotDataDir(dataDir.resolve(".pilot").toString()),
            JsonMapper.builder().build(), attachments);

        BackupService.BackupReport report = backups.backup();

        assertTrue(report.message().contains(external.toString()), report.message());
        assertTrue(
            Files.readString(Path.of(report.directory()).resolve("manifest.json")).contains("outside-attachments"));
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

    private static void writeLegacyBackup(Path legacy) throws IOException {
        byte[] payload = {0, (byte)0xff};
        Files.write(legacy.resolve("bytes.bin"), payload);
        String hash = DiffSandbox.fingerprint(new String(payload, StandardCharsets.ISO_8859_1));
        Files.writeString(legacy.resolve("manifest.json"), """
            {"schemaVersion":"2","files":[{"path":"bytes.bin","bytes":2,"sha256":"%s"}]}
            """.formatted(hash));
    }
}
