package com.pilot.server.maintenance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.pilot.server.config.PilotDataDir;
import com.pilot.server.session.AttachmentService;
import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 数据备份与恢复演练支持。
 *
 * <p>数据库用 SQLite 的 {@code VACUUM INTO} 复制：它能拿到事务一致的快照，
 * 直接在服务运行时拷贝 {@code .db} 文件则可能拿到写了一半的 WAL。
 * 其余需要一起备份的是「没有进数据库、但恢复要用」的材料；缓存与外部工作区单独说明。
 *
 * <p>恢复不自动执行：停服务、替换数据目录、再启动，是唯一安全的顺序。
 * 这里提供清单与校验和，让恢复后能核对内容是否完整。
 */
@Component
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final List<String> DIRECTORIES = List.of("multi", "validation", "commands", "traces", "snapshots",
        "work-products", "file-versions", "studio", "apply-journal", "agents", "skills", "tasks", "mobile");

    public record BackupReport(boolean ok, String directory, int files, long bytes, String message, Instant createdAt) {
    }

    public record VerificationReport(boolean valid, String directory, int checkedFiles, List<String> failures,
        String message) {
        public VerificationReport {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
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
    private final AttachmentService attachments;

    public BackupService(ObjectProvider<SqliteStore> store, PilotDataDir dataDir, ObjectMapper mapper,
        AttachmentService attachments) {
        this.store = store;
        this.dataDir = dataDir;
        this.mapper = mapper;
        this.attachments = attachments;
    }

    public synchronized BackupReport backup() {
        String id = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now())
            + "-" + UUID.randomUUID();
        Path backups = dataDir.file("backups");
        Path target = backups.resolve(id);
        Path staging = backups.resolve("." + id + ".tmp");
        try {
            Files.createDirectories(staging);
            List<Map<String, Object>> manifest = new ArrayList<>();
            SqliteStore sqlite = store.getIfAvailable();
            if (sqlite != null) {
                Path database = staging.resolve(BackupManifest.DATABASE);
                vacuumInto(sqlite, database);
                manifest.add(entry(BackupManifest.DATABASE, database));
            }
            List<String> included = new ArrayList<>(DIRECTORIES);
            Path attachmentRoot = attachments.root().toAbsolutePath().normalize();
            Path dataRoot = dataDir.root().toAbsolutePath().normalize();
            boolean attachmentIncluded = attachmentRoot.startsWith(dataRoot) && !attachmentRoot.equals(dataRoot);
            String attachmentDirectory = "";
            if (attachmentIncluded) {
                attachmentDirectory = dataRoot.relativize(attachmentRoot).toString().replace('\\', '/');
                if (!attachmentDirectory.isBlank() && !included.contains(attachmentDirectory)) {
                    included.add(attachmentDirectory);
                }
            }
            for (String directory : included) {
                Path source = directory.equals(attachmentDirectory) ? attachmentRoot : dataDir.file(directory);
                if (!Files.isDirectory(source)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(source)) {
                    List<Path> files = walk.filter(Files::isRegularFile).toList();
                    Set<Path> before = new HashSet<>();
                    for (Path file : files) {
                        Path relative = source.relativize(file);
                        before.add(relative);
                        Path destination = staging.resolve(directory).resolve(relative);
                        Files.createDirectories(destination.getParent());
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                        Map<String, Object> item =
                            entry(directory + "/" + relative.toString().replace('\\', '/'), destination);
                        if (!item.get("sha256").equals(BackupManifest.sha256(file))) {
                            throw new IOException("备份期间文件发生变化: " + file);
                        }
                        manifest.add(item);
                    }
                    try (Stream<Path> verify = Files.walk(source)) {
                        Set<Path> after = new HashSet<>();
                        verify.filter(Files::isRegularFile).forEach(file -> after.add(source.relativize(file)));
                        if (!before.equals(after)) {
                            throw new IOException("备份期间目录内容发生变化: " + source);
                        }
                    }
                }
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("createdAt", Instant.now().toString());
            root.put("dataDir", dataDir.root().toString());
            root.put("schemaVersion", BackupManifest.VERSION);
            root.put("id", id);
            root.put("storage", sqlite == null ? "files" : "sqlite");
            root.put("files", manifest);
            root.put("includedDirectories", included);
            root.put("excludedData", List.of("外部工作区、Git worktree 及其工作区内恢复日志", "缓存与可重建数据"));
            root.put("externalAttachmentPath", attachmentIncluded ? "" : attachmentRoot.toString());
            root.put("consistency", "SQLite 使用事务快照；动态文件逐个复制，不保证与数据库处于同一业务时点。备份期间请暂停写入。");
            root.put("restoreHint", "停止服务后，将本备份内容恢复到数据目录，再启动；外部工作区和清单标注未包含的数据需单独恢复。");
            Files.writeString(staging.resolve(BackupManifest.FILE), mapper.writeValueAsString(root),
                StandardCharsets.UTF_8);
            BackupManifest.Check check = BackupManifest.verify(staging, BackupManifest.read(staging, mapper));
            if (!check.valid()) {
                throw new IOException(String.join("；", check.failures()));
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, target);
            }
            Files.writeString(target.resolve(BackupManifest.COMPLETE), Instant.now().toString(),
                StandardCharsets.UTF_8);
            long bytes = manifest.stream().mapToLong(item -> ((Number)item.get("bytes")).longValue()).sum();
            StorageHealth.recovered("backup");
            log.info("备份完成: {}（{} 个文件，{} 字节）", target, manifest.size(), bytes);
            String message = "备份完成；备份期间请暂停写入，外部工作区未包含";
            if (!attachmentIncluded) {
                message += "；外置附件目录未包含: " + attachmentRoot;
            }
            return new BackupReport(true, target.toString(), manifest.size(), bytes, message, Instant.now());
        } catch (IOException | RuntimeException e) {
            try {
                if (Files.exists(staging)) {
                    BackupManifest.deleteTree(staging);
                }
                if (Files.exists(target) && !BackupManifest.published(target, mapper)) {
                    BackupManifest.deleteTree(target);
                }
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
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
                directories.addAll(list.filter(Files::isDirectory).filter(this::isComplete).toList());
            } catch (IOException e) {
                log.debug("读取备份目录失败", e);
            }
        }
        directories.sort(Comparator.comparing(path -> path.getFileName().toString()));
        String last = directories.isEmpty() ? "" : directories.getLast().getFileName().toString();
        return new Status(dataDir.root().toString(), databaseBytes, directories.size(), last, StorageHealth.snapshot(),
            includedDirectories());
    }

    /** 校验最近一份完整备份的清单、文件大小和原始字节 SHA-256。 */
    public synchronized VerificationReport verifyLatestBackup() {
        Path latest = latestCompleteBackup();
        if (latest == null) {
            return new VerificationReport(false, "", 0, List.of("没有可校验的完整备份"), "没有可校验的完整备份");
        }
        List<String> failures;
        int checked = 0;
        String verified = "最近备份文件校验通过";
        try {
            JsonNode manifest = BackupManifest.readPublished(latest, mapper);
            BackupManifest.Check check = BackupManifest.verify(latest, manifest);
            failures = check.failures();
            checked = check.files();
            if ("2".equals(BackupManifest.version(manifest))) {
                verified += "（V2 旧版校验算法与备份范围）";
            }
        } catch (IOException | RuntimeException e) {
            failures = List.of("读取备份清单失败: " + e.getMessage());
        }
        boolean valid = failures.isEmpty();
        if (valid) {
            StorageHealth.recovered("backup");
        } else {
            StorageHealth.markDegraded("backup", String.join("；", failures));
        }
        return new VerificationReport(valid, latest.toString(), checked, failures, valid ? verified : "最近备份校验失败");
    }

    private Path latestCompleteBackup() {
        Path backups = dataDir.file("backups");
        if (!Files.isDirectory(backups)) {
            return null;
        }
        try (Stream<Path> list = Files.list(backups)) {
            return list.filter(Files::isDirectory).filter(this::isComplete)
                .max(Comparator.comparing(path -> path.getFileName().toString())).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private List<String> includedDirectories() {
        Path attachmentRoot = attachments.root().toAbsolutePath().normalize();
        Path dataRoot = dataDir.root().toAbsolutePath().normalize();
        if (!attachmentRoot.startsWith(dataRoot) || attachmentRoot.equals(dataRoot)) {
            return DIRECTORIES;
        }
        List<String> result = new ArrayList<>(DIRECTORIES);
        String attachmentDirectory = dataRoot.relativize(attachmentRoot).toString().replace('\\', '/');
        if (!result.contains(attachmentDirectory)) {
            result.add(attachmentDirectory);
        }
        return List.copyOf(result);
    }

    /** 删除早于 cutoff 的备份目录，避免备份把磁盘吃满。 */
    public synchronized int purgeOlderThan(Instant cutoff) {
        Path backups = dataDir.file("backups");
        if (!Files.isDirectory(backups)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> list = Files.list(backups)) {
            for (Path directory : list.filter(Files::isDirectory).toList()) {
                try {
                    if (isComplete(directory) && Files.getLastModifiedTime(directory).toInstant().isBefore(cutoff)) {
                        BackupManifest.deleteTree(directory);
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
        item.put("sha256", BackupManifest.sha256(file));
        return item;
    }

    private boolean isComplete(Path directory) {
        return BackupManifest.published(directory, mapper);
    }
}
