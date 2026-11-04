package com.pilot.server.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import com.pilot.server.config.PilotDataDir;
import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.json.JsonMapper;

class BackupServiceTest {

    @TempDir
    Path dataDir;

    @SuppressWarnings("unchecked")
    private BackupService service(SqliteStore store) {
        ObjectProvider<SqliteStore> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(store);
        return new BackupService(provider, new PilotDataDir(dataDir.resolve(".pilot").toString()),
            JsonMapper.builder().build());
    }

    @Test
    void backupCapturesDatabaseAndLooseFiles() throws IOException {
        Path root = dataDir.resolve(".pilot");
        Files.createDirectories(root);
        SqliteStore store = new SqliteStore(root.resolve("pilot.db"));
        Files.createDirectories(root.resolve("multi/ses_1"));
        Files.writeString(root.resolve("multi/ses_1/tasks.json"), "[{\"id\":\"T1\"}]");
        Files.createDirectories(root.resolve("validation"));
        Files.writeString(root.resolve("validation/ses_1.json"), "{\"entries\":[]}");

        BackupService.BackupReport report = service(store).backup();

        assertTrue(report.ok(), report.message());
        assertTrue(report.files() >= 3, "至少包含数据库、任务板和校验证据: " + report.files());
        Path target = Path.of(report.directory());
        assertTrue(Files.isRegularFile(target.resolve("manifest.json")));
        assertTrue(Files.isRegularFile(target.resolve("pilot.db")));
        assertTrue(Files.isRegularFile(target.resolve("multi/ses_1/tasks.json")));
        String manifest = Files.readString(target.resolve("manifest.json"));
        assertTrue(manifest.contains("sha256"), manifest.substring(0, Math.min(200, manifest.length())));
        assertTrue(manifest.contains("restoreHint"));
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
