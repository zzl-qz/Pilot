package com.pilot.server.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.pilot.core.event.RunStarted;
import com.pilot.core.message.Attachment;
import com.pilot.core.session.SessionService;
import com.pilot.server.attachment.AttachmentLimits;
import com.pilot.server.attachment.AttachmentService;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.store.EventCodec;
import com.pilot.server.store.JdbcSessionRepository;
import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.json.JsonMapper;

class RestoreServiceTest {
    private static final String IMAGE = "00000000-0000-0000-0000-000000000001-image.png";

    @TempDir
    Path temp;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void validatesPortablePathsAndRemapsAnOriginalWindowsLocation() throws IOException {
        for (String name : List.of("../pilot.db", "dir/../pilot.db", "C:/pilot.db", "dir\\file", "NUL.txt", "file.")) {
            assertThrows(IOException.class, () -> BackupManifest.relative(name));
        }
        String original = "C:\\用户\\Pilot 数据";
        var paths = new RestorePaths(Map.of(original, temp), mapper);
        assertEquals(temp.resolve("附件").resolve("图像.png").toString(), paths.relocate(original + "\\附件\\图像.png"));
        assertEquals("C:\\用户\\Pilot 数据旧\\文件", paths.relocate("C:\\用户\\Pilot 数据旧\\文件"));
    }

    @Test
    void restoresIntoACleanRootAndKeepsOldDataAndRepository() throws IOException {
        Path data = temp.resolve("源数据");
        Fixture fixture = fixture(data, data.resolve("pilot.db"), data.resolve("attachments"));
        Path backup = fixture.backup();
        String later = fixture.sessions().createSession("备份后").id();
        Path laterFile = Files.writeString(data.resolve("later.json"), "不应进入恢复目录");
        RestoreService restore = new RestoreService(mapper);
        var options = new RestoreService.Options(data, null, null, Map.of());

        restore.preflight(backup, options);
        assertTrue(Files.exists(laterFile));
        assertFalse(Files.exists(DataDirectoryLock.control(data)));

        var result = restore.restore(backup, options);

        assertFalse(Files.exists(laterFile));
        assertTrue(Files.exists(result.previous().resolve(data.getFileName()).resolve("later.json")));
        assertTrue(Files.isRegularFile(backup.resolve("COMPLETE")));
        SessionService restored = sessions(new SqliteStore(data.resolve("pilot.db")));
        assertTrue(restored.find(later).isEmpty());
        assertTrue(restored.find(fixture.session()).isPresent());
        assertEquals("image", Files.readString(data.resolve("attachments").resolve(fixture.session()).resolve(IMAGE)));
    }

    @Test
    void remapsExternalStorageAndPreservesOldDatabaseSidecars() throws IOException {
        Path original = temp.resolve("original");
        Path oldDatabase = temp.resolve("source storage/old.db");
        Path oldAttachments = temp.resolve("source attachments");
        Fixture fixture = fixture(original, oldDatabase, oldAttachments);
        Path data = temp.resolve("新 数据");
        Path database = temp.resolve("new storage/new.db");
        Path attachments = temp.resolve("new attachments");
        Files.createDirectories(database.getParent());
        Files.writeString(database, "old database");
        Path wal = Files.writeString(Path.of(database + "-wal"), "old WAL");
        Files.createDirectories(attachments);
        Files.writeString(attachments.resolve("old.txt"), "previous");
        Path workspace = temp.resolve("moved workspace");
        Files.createDirectory(workspace);
        RestoreService restore = new RestoreService(mapper);
        var options = new RestoreService.Options(data, database, attachments, Map.of(fixture.workspace(), workspace));

        assertThrows(IOException.class,
            () -> restore.preflight(fixture.backup(), new RestoreService.Options(data, null, null, Map.of())));
        restore.restore(fixture.backup(), options);

        SessionService reopened = sessions(new SqliteStore(database));
        assertEquals(workspace.toRealPath().toString(), reopened.find(fixture.session()).orElseThrow().workspace());
        RunStarted event = (RunStarted)reopened.events(fixture.session()).getFirst().payload();
        Path image = Path.of(event.attachments().getFirst().path());
        assertEquals(attachments.toRealPath().resolve(fixture.session()).resolve(IMAGE), image);
        assertEquals("image", Files.readString(image));
        assertFalse(Files.exists(attachments.resolve("old.txt")));
        try (var files = Files.walk(DataDirectoryLock.control(database))) {
            Path saved = files.filter(path -> path.getFileName().equals(wal.getFileName())).findFirst().orElseThrow();
            assertEquals("old WAL", Files.readString(saved));
        }
    }

    @Test
    void refusesCorruptBackupAndActiveServerWithoutWritingLiveData() throws IOException {
        Path data = temp.resolve("data");
        Fixture fixture = fixture(data, data.resolve("pilot.db"), data.resolve("attachments"));
        var options = new RestoreService.Options(data, null, null, Map.of());
        RestoreService restore = new RestoreService(mapper);
        try (var live = DataDirectoryLock.acquire(data, null, null)) {
            assertTrue(assertThrows(IOException.class, () -> restore.restore(fixture.backup(), options)).getMessage()
                .contains("退出服务"));
        }
        Files.writeString(fixture.backup().resolve("pilot.db"), "damaged");
        assertThrows(IOException.class, () -> restore.restore(fixture.backup(), options));
        assertTrue(fixture.sessions().find(fixture.session()).isPresent());
    }

    @Test
    void rollsBackAFailedStartupCheckAndAnInterruptedSwap() throws IOException {
        Path data = Files.createDirectory(temp.resolve("data"));
        Files.writeString(data.resolve("old.txt"), "old");
        Path stage = Files.createDirectory(temp.resolve("staged"));
        Files.writeString(stage.resolve("new.txt"), "new");
        Path previous = temp.resolve("previous");
        RestoreSwap swap = new RestoreSwap(data, mapper);
        var step = new RestoreSwap.Step(data, stage, previous);

        assertThrows(IOException.class, () -> swap.apply(List.of(step), () -> {
            throw new IOException("模拟恢复检查失败");
        }));
        assertTrue(Files.exists(data.resolve("old.txt")));
        assertFalse(Files.exists(data.resolve("new.txt")));

        Path control = Files.createDirectories(DataDirectoryLock.control(data));
        Files.writeString(control.resolve("restore.json"),
            mapper.writeValueAsString(new RestoreSwap.Transaction(List.of(step))));
        RestoreSwap.move(data, previous);
        assertThrows(IOException.class, swap::requireReady);
        assertTrue(swap.rollback());
        assertEquals("old", Files.readString(data.resolve("old.txt")));
        assertFalse(swap.rollback());
    }

    private Fixture fixture(Path data, Path database, Path attachments) throws IOException {
        PilotDataDir directory = new PilotDataDir(data.toString());
        SqliteStore sqlite = new SqliteStore(database);
        SessionService sessions = sessions(sqlite);
        String workspace = Files.createTempDirectory(temp, "workspace").toString();
        String id = sessions.createSession("有附件", workspace).id();
        Path folder = Files.createDirectories(attachments.resolve(id));
        Path image = Files.writeString(folder.resolve(IMAGE), "image");
        sessions.publish(id, "run",
            new RunStarted("图片", List.of(new Attachment("image.png", "image/png", image.toString()))));
        Files.createDirectories(data);
        Files.writeString(data.resolve("mcp.json"), "{\"mcpServers\":{}}");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("store", sqlite);
        var backup = new BackupService(beans.getBeanProvider(SqliteStore.class), directory, mapper,
            new AttachmentService(attachments.toString(), directory, AttachmentLimits.defaults())).backup();
        assertTrue(backup.ok(), backup.message());
        return new Fixture(Path.of(backup.directory()), sessions, id, workspace);
    }

    private SessionService sessions(SqliteStore sqlite) {
        return new SessionService(Clock.systemUTC(), new JdbcSessionRepository(sqlite, new EventCodec(mapper)));
    }

    private record Fixture(Path backup, SessionService sessions, String session, String workspace) {
    }
}
