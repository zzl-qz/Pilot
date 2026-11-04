package com.pilot.server.maintenance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.pilot.core.multi.DiffSandbox;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.ObjectMapper;

/**
 * 数据备份与恢复演练支持。
 *
 * <p>数据库用 SQLite 的 {@code VACUUM INTO} 复制：它能拿到事务一致的快照，
 * 直接在服务运行时拷贝 {@code .db} 文件则可能拿到写了一半的 WAL。
 * 其余需要一起备份的是「没有进数据库、但恢复要用」的材料：多 Agent 清单、校验证据、命令日志。
 *
 * <p>恢复不自动执行：停服务、替换数据目录、再启动，是唯一安全的顺序。
 * 这里提供清单与校验和，让恢复后能核对内容是否完整。
 */
@Component
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final List<String> DIRECTORIES = List.of("multi", "validation", "commands", "traces");

    public record BackupReport(boolean ok, String directory, int files, long bytes, String message, Instant createdAt) {
    }

    public record Status(String dataDir, long databaseBytes, int backups, String lastBackupAt,
        StorageHealth.Snapshot storage, List<String> includedDirectories) {
        public Status {
            includedDirectories = includedDirectories == null ? List.of() : List.copyOf(includedDirectories);
        }
    }

    private final ObjectProvider<SqliteStore> store;
    private final PilotDataDir dataDir;
    private final ObjectMapper mapper;

    public BackupService(ObjectProvider<SqliteStore> store, PilotDataDir dataDir, ObjectMapper mapper) {
        this.store = store;
        this.dataDir = dataDir;
        this.mapper = mapper;
    }

    public synchronized BackupReport backup() {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Path target = dataDir.file("backups", stamp);
        try {
            Files.createDirectories(target);
            List<Map<String, Object>> manifest = new ArrayList<>();
            SqliteStore sqlite = store.getIfAvailable();
            if (sqlite != null) {
                Path database = target.resolve("pilot.db");
                vacuumInto(sqlite, database);
                manifest.add(entry("pilot.db", database));
            }
            for (String directory : DIRECTORIES) {
                Path source = dataDir.file(directory);
                if (!Files.isDirectory(source)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(source)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        Path relative = source.relativize(file);
                        Path destination = target.resolve(directory).resolve(relative);
                        Files.createDirectories(destination.getParent());
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                        manifest.add(entry(directory + "/" + relative.toString().replace('\\', '/'), destination));
                    }
                }
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("createdAt", Instant.now().toString());
            root.put("dataDir", dataDir.root().toString());
            root.put("schemaVersion", "2");
            root.put("files", manifest);
            root.put("restoreHint", "停止服务后，把本目录内容覆盖回数据目录，再启动；不要在服务运行时恢复。");
            Files.writeString(target.resolve("manifest.json"), mapper.writeValueAsString(root), StandardCharsets.UTF_8);
            long bytes = manifest.stream().mapToLong(item -> ((Number)item.get("bytes")).longValue()).sum();
            StorageHealth.recovered("backup");
            log.info("备份完成: {}（{} 个文件，{} 字节）", target, manifest.size(), bytes);
            return new BackupReport(true, target.toString(), manifest.size(), bytes, "备份完成", Instant.now());
        } catch (IOException | RuntimeException e) {
            StorageHealth.markDegraded("backup", e.getMessage());
            log.warn("备份失败: {}", target, e);
            return new BackupReport(false, target.toString(), 0, 0, "备份失败：" + e.getMessage(), Instant.now());
        }
    }

    public Status status() {
        SqliteStore sqlite = store.getIfAvailable();
        long databaseBytes = 0;
        if (sqlite != null) {
            try {
                databaseBytes = Files.size(sqlite.path());
            } catch (IOException e) {
                StorageHealth.markDegraded("database", e.getMessage());
            }
        }
        Path backups = dataDir.file("backups");
        List<Path> directories = new ArrayList<>();
        if (Files.isDirectory(backups)) {
            try (Stream<Path> list = Files.list(backups)) {
                directories.addAll(list.filter(Files::isDirectory).toList());
            } catch (IOException e) {
                log.debug("读取备份目录失败", e);
            }
        }
        directories.sort(Comparator.comparing(path -> path.getFileName().toString()));
        String last = directories.isEmpty() ? "" : directories.getLast().getFileName().toString();
        return new Status(dataDir.root().toString(), databaseBytes, directories.size(), last, StorageHealth.snapshot(),
            DIRECTORIES);
    }

    /** 删除早于 cutoff 的备份目录，避免备份把磁盘吃满。 */
    public int purgeOlderThan(Instant cutoff) {
        Path backups = dataDir.file("backups");
        if (!Files.isDirectory(backups)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> list = Files.list(backups)) {
            for (Path directory : list.filter(Files::isDirectory).toList()) {
                try {
                    if (Files.getLastModifiedTime(directory).toInstant().isBefore(cutoff)) {
                        deleteRecursively(directory);
                        removed++;
                    }
                } catch (IOException e) {
                    log.warn("清理备份 {} 失败", directory, e);
                }
            }
        } catch (IOException e) {
            log.warn("遍历备份目录失败", e);
        }
        return removed;
    }

    private static void vacuumInto(SqliteStore sqlite, Path target) {
        String path = target.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
        try (Connection connection = sqlite.open(); Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + path + "'");
        } catch (Exception e) {
            throw new IllegalStateException("导出数据库快照失败：" + e.getMessage(), e);
        }
    }

    private static Map<String, Object> entry(String name, Path file) throws IOException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", name);
        item.put("bytes", Files.size(file));
        item.put("sha256", DiffSandbox.fingerprint(Files.readString(file, StandardCharsets.ISO_8859_1)));
        return item;
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
