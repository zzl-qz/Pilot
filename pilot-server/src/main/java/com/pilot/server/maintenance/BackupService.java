package com.pilot.server.maintenance;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.pilot.core.tool.CommandTaskManager;
import com.pilot.server.attachment.AttachmentService;
import com.pilot.server.common.data.DataMaintenance;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.common.data.StorageHealth;
import com.pilot.server.git.GitWorktrees;
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
    private static final List<String> DIRECTORIES =
        List.of("multi", "validation", "commands", "traces", "snapshots", "work-products", "file-versions", "studio",
            "apply-journal", "history-edits", "agents", "skills", "tasks", "mobile", "artifacts");
    static final String EXTERNAL_ATTACHMENTS = "_external/attachments";
    private static final Duration QUIESCE_TIMEOUT = Duration.ofSeconds(2);

    public record WorkspaceStatus(String path, String kind, boolean included) {
    }

    public record BackupReport(boolean ok, String directory, int files, long bytes, String message, Instant createdAt,
        List<WorkspaceStatus> workspaces) {
    }

    public record VerificationReport(boolean valid, String directory, int checkedFiles, List<String> failures,
        String message) {
        public VerificationReport {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    public record Status(String dataDir, long databaseBytes, int backups, String lastBackupAt,
        StorageHealth.Snapshot storage, List<String> includedDirectories, List<WorkspaceStatus> workspaces) {
        public Status {
            includedDirectories = includedDirectories == null ? List.of() : List.copyOf(includedDirectories);
        }
    }

    private final ObjectProvider<SqliteStore> store;
    private final PilotDataDir dataDir;
    private final ObjectMapper mapper;
    private final AttachmentService attachments;

    private ObjectProvider<CommandTaskManager> commandTasks;
    private String defaultWorkspace = System.getProperty("user.dir");

    @Value("${pilot.agent.workspace:${user.dir}}")
    void useDefaultWorkspace(String workspace) {
        this.defaultWorkspace = workspace;
    }

    @Autowired
    void useCommandTasks(ObjectProvider<CommandTaskManager> tasks) {
        this.commandTasks = tasks;
    }

    public BackupService(ObjectProvider<SqliteStore> store, PilotDataDir dataDir, ObjectMapper mapper,
        AttachmentService attachments) {
        this.store = store;
        this.dataDir = dataDir;
        this.mapper = mapper;
        this.attachments = attachments;
    }

    public BackupReport backup() {
        return backup(List.of());
    }

    BackupReport backup(List<Path> workspaceSelection) {
        String id = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now())
            + "-" + UUID.randomUUID();
        Path backups = dataDir.file("backups");
        Path target = backups.resolve(id);
        Path staging = backups.resolve("." + id + ".tmp");
        try {
            BackupManifest.privateDirectories(staging);
            List<Map<String, Object>> manifest = new ArrayList<>();
            SqliteStore sqlite = store.getIfAvailable();
            Path attachmentRoot = attachments.root().toAbsolutePath().normalize();
            String attachmentEntry = attachmentEntry(attachmentRoot);
            List<WorkspaceArchive.Workspace> workspaces;
            try (var maintenance = dataDir.maintenance().exclusive(QUIESCE_TIMEOUT)) {
                CommandTaskManager tasks = commandTasks == null ? null : commandTasks.getIfAvailable();
                if (tasks != null && tasks.hasRunningTasks()) {
                    throw new DataMaintenance.Busy("后台命令仍在运行，请等命令结束后备份");
                }
                try (Connection reserved = reserveDatabase(sqlite); FileChannel mobile = reservePairing()) {
                    capture(staging, manifest, sqlite, attachmentRoot, attachmentEntry);
                    Set<String> known = sqlite == null ? Set.of()
                        : BackupDatabase.workspaces(staging.resolve(BackupManifest.DATABASE), defaultWorkspace);
                    workspaces = WorkspaceArchive.capture(dataDir, staging, known, workspaceSelection, mapper);
                    Path archived = staging.resolve(WorkspaceArchive.DIRECTORY);
                    if (Files.isDirectory(archived)) {
                        try (var walk = Files.walk(archived)) {
                            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                                manifest.add(entry(BackupManifest.portable(staging.relativize(file)), file));
                            }
                        }
                    }
                }
            }
            if (sqlite != null) {
                BackupDatabase.check(staging.resolve(BackupManifest.DATABASE),
                    staging.resolve(BackupManifest.relative(attachmentEntry)), attachmentRoot.toString(), mapper);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("createdAt", Instant.now().toString());
            root.put("dataDir", dataDir.root().toString());
            root.put("schemaVersion", BackupManifest.VERSION);
            root.put("id", id);
            root.put("storage", sqlite == null ? "files" : "sqlite");
            root.put("files", manifest);
            root.put("includedDirectories", includedDirectories());
            root.put("defaultWorkspace", defaultWorkspace);
            root.put("workspaces", workspaces);
            root.put("excludedData", List.of("未选择的工作区文件、Git 忽略文件", "访问审计、MCP 诊断日志", "缓存与可重建数据"));
            root.put("databaseSource", sqlite == null ? "" : sqlite.path().toString());
            root.put("attachmentSource", attachmentRoot.toString());
            root.put("attachmentEntry", attachmentEntry);
            root.put("externalAttachmentPath",
                EXTERNAL_ATTACHMENTS.equals(attachmentEntry) ? attachmentRoot.toString() : "");
            root.put("consistency", "维护窗口内导出 SQLite 和 Pilot 受管文件，已核对消息附件引用。外部编辑器写入应在备份前暂停。");
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
            String message = "备份完成。Pilot 受管数据已核验";
            if (workspaces.stream().anyMatch(workspace -> !workspace.included())) {
                message += "。清单中的外部工作区需单独恢复";
            }
            List<WorkspaceStatus> workspaceStatus = workspaces.stream()
                .map(workspace -> new WorkspaceStatus(workspace.path(), workspace.kind(), workspace.included()))
                .toList();
            if (EXTERNAL_ATTACHMENTS.equals(attachmentEntry)) {
                message += "。外置附件已单独收录: " + attachmentRoot;
            }
            return new BackupReport(true, target.toString(), manifest.size(), bytes, message, Instant.now(),
                workspaceStatus);
        } catch (IOException | SQLException | RuntimeException e) {
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
            if (!(e instanceof DataMaintenance.Busy)) {
                StorageHealth.markDegraded("backup", e.getMessage());
                log.warn("备份失败: {}", target, e);
            }
            return new BackupReport(false, target.toString(), 0, 0, "备份失败：" + e.getMessage(), Instant.now(), List.of());
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
            includedDirectories(), directories.isEmpty() ? List.of() : workspaceStatus(directories.getLast()));
    }

    private List<WorkspaceStatus> workspaceStatus(Path directory) {
        try {
            List<WorkspaceStatus> result = new ArrayList<>();
            for (JsonNode workspace : BackupManifest.readPublished(directory, mapper).path("workspaces")) {
                result.add(new WorkspaceStatus(workspace.path("path").asText(), workspace.path("kind").asText(),
                    workspace.path("included").asBoolean()));
            }
            return List.copyOf(result);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
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
            if (check.valid() && BackupManifest.VERSION.equals(BackupManifest.version(manifest))
                && "sqlite".equals(manifest.path("storage").asText())) {
                BackupDatabase.check(latest.resolve(BackupManifest.DATABASE),
                    latest.resolve(BackupManifest.relative(manifest.path("attachmentEntry").asText())),
                    manifest.path("attachmentSource").asText(), mapper);
            }
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
        try {
            String entry = attachmentEntry(attachmentRoot);
            return Stream.concat(DIRECTORIES.stream(), Stream.of(entry)).distinct().toList();
        } catch (IOException e) {
            return DIRECTORIES;
        }
    }

    private String attachmentEntry(Path attachmentsPath) throws IOException {
        Path root = DataDirectoryLock.canonical(dataDir.root());
        Path physical = DataDirectoryLock.canonical(attachmentsPath);
        if (root.startsWith(physical) || physical.startsWith(root.resolve("backups"))
            || physical.startsWith(root.resolve("_external")) || physical.startsWith(root.resolve("worktrees"))) {
            throw new IOException("附件目录与数据目录或备份仓库重叠: " + attachmentsPath);
        }
        return physical.startsWith(root) ? BackupManifest.portable(root.relativize(physical)) : EXTERNAL_ATTACHMENTS;
    }

    private Map<String, Path> sources(SqliteStore sqlite, Path attachmentsPath, String attachmentEntry)
        throws IOException {
        Map<String, Path> result = new LinkedHashMap<>();
        for (String directory : DIRECTORIES) {
            collect(result, dataDir.file(directory), directory, sqlite);
        }
        if (!DIRECTORIES.contains(attachmentEntry.split("/", 2)[0])) {
            collect(result, attachmentsPath, attachmentEntry, sqlite);
        }
        for (var worktree : new GitWorktrees(dataDir).registrations()) {
            String name = "worktrees/" + worktree.id() + ".properties";
            addSource(result, dataDir.file(name), name, sqlite);
        }
        Path mcp = dataDir.file("mcp.json");
        if (Files.exists(mcp, LinkOption.NOFOLLOW_LINKS)) {
            addSource(result, mcp, "mcp.json", sqlite);
        }
        return result;
    }

    private static void collect(Map<String, Path> files, Path directory, String prefix, SqliteStore sqlite)
        throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("备份源目录不可读取: " + directory);
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path file : walk.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                if (prefix.equals("mobile") && file.equals(directory.resolve(".pairing.lock"))) {
                    continue;
                }
                addSource(files, file, prefix + "/" + BackupManifest.portable(directory.relativize(file)), sqlite);
            }
        }
    }

    private static void addSource(Map<String, Path> files, Path file, String name, SqliteStore sqlite)
        throws IOException {
        if (sqlite != null && (file.equals(sqlite.path()) || file.equals(Path.of(sqlite.path() + "-wal"))
            || file.equals(Path.of(sqlite.path() + "-shm")) || file.equals(Path.of(sqlite.path() + "-journal")))) {
            return;
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("备份源包含链接或非常规文件: " + file);
        }
        BackupManifest.relative(name);
        if (files.putIfAbsent(name, file) != null) {
            throw new IOException("备份路径重复: " + name);
        }
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

    private void capture(Path staging, List<Map<String, Object>> manifest, SqliteStore sqlite, Path attachmentRoot,
        String attachmentEntry) throws IOException {
        if (sqlite != null) {
            Path database = staging.resolve(BackupManifest.DATABASE);
            vacuumInto(sqlite, database);
            manifest.add(entry(BackupManifest.DATABASE, database));
        }
        Map<String, Path> sources = sources(sqlite, attachmentRoot, attachmentEntry);
        for (var source : sources.entrySet()) {
            Path destination = staging.resolve(BackupManifest.relative(source.getKey()));
            Files.createDirectories(destination.getParent());
            Files.copy(source.getValue(), destination, LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("备份源在复制时变为链接或非常规文件：" + source.getValue());
            }
            manifest.add(entry(source.getKey(), destination));
        }
        if (!sources.equals(sources(sqlite, attachmentRoot, attachmentEntry))) {
            throw new IOException("备份期间目录内容发生变化");
        }
        for (var item : manifest) {
            Path source = sources.get(item.get("path"));
            if (source != null && (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || !item.get("sha256").equals(BackupManifest.sha256(source)))) {
                throw new IOException("备份期间文件发生变化: " + source);
            }
        }
    }

    private static Connection reserveDatabase(SqliteStore sqlite) throws IOException {
        if (sqlite == null) {
            return null;
        }
        try {
            Connection connection = sqlite.open();
            try (Statement statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                return connection;
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException("无法暂停数据库写入：" + e.getMessage(), e);
        }
    }

    private FileChannel reservePairing() throws IOException {
        Path directory = dataDir.file("mobile");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        FileChannel channel = FileChannel.open(directory.resolve(".pairing.lock"), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            if (channel.tryLock() == null) {
                throw new DataMaintenance.Busy("手机设备授权正在写入，请稍后备份");
            }
            return channel;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static void vacuumInto(SqliteStore sqlite, Path target) {
        try (Connection connection = sqlite.open(); var statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, target.toAbsolutePath().toString());
            statement.execute();
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
