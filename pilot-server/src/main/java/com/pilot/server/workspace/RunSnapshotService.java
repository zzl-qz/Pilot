package com.pilot.server.workspace;

import com.pilot.core.tool.SnapshotSink;
import com.pilot.server.config.PilotDataDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 运行级文件快照：写工具落盘前备份原文件，支持按 runId 回滚。
 *
 * <p>快照保存在 {@code ~/.pilot/snapshots/{runId}/}，不污染用户仓库。
 * 运行上下文通过 ThreadLocal 绑定，因为工具实例按工作区缓存、跨运行复用。
 */
@Component
public class RunSnapshotService implements SnapshotSink {
    private static final Logger log = LoggerFactory.getLogger(RunSnapshotService.class);
    private static final String META_FILE = "meta.json";
    /**
     * 超过这个大小的文件不做备份。整文件复制的代价是「每次运行 × 文件大小」，
     * 十几 GB 的日志/产物备份一遍就能把磁盘吃满；跳过时仍记录条目，
     * 回滚会把该文件报进 failed，而不是悄悄删掉它。
     */
    private static final long MAX_SNAPSHOT_BYTES = 8L * 1024 * 1024;

    private final ObjectMapper mapper;
    private final Path root;
    private final ThreadLocal<Context> current = new ThreadLocal<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RunSnapshotService(ObjectMapper mapper, PilotDataDir dataDir) {
        this(mapper, dataDir.file("snapshots"));
    }

    RunSnapshotService(ObjectMapper mapper, Path root) {
        this.mapper = mapper;
        this.root = root.toAbsolutePath().normalize();
    }

    public record SnapshotEntry(String path, boolean existed) {
    }

    public record SnapshotMeta(String runId, String workspace, List<SnapshotEntry> files) {
    }

    public record RollbackReport(String runId, String workspace, List<String> restored,
                                 List<String> deleted, List<String> failed) {
    }

    public void bind(String runId, Path workspace) {
        Path directory = root.resolve(sanitize(runId));
        current.set(new Context(runId, workspace.toAbsolutePath().normalize(), directory));
    }

    public void clear() {
        current.remove();
    }

    @Override
    public void beforeWrite(Path file) {
        Context context = current.get();
        if (context == null) {
            return;
        }
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(context.workspace)) {
            throw new IllegalStateException("拒绝快照工作区之外的路径: " + absolute);
        }
        Path relative = context.workspace.relativize(absolute);
        String key = relative.toString().replace('\\', '/');
        if (!context.recorded.add(key)) {
            return;
        }

        boolean existed = Files.isRegularFile(absolute);
        if (existed) {
            try {
                long size = Files.size(absolute);
                if (size > MAX_SNAPSHOT_BYTES) {
                    log.warn("{} 有 {} 字节，超过快照上限，跳过备份；本次运行无法回滚这个文件", key, size);
                } else {
                    Path target = context.directory().resolve("files").resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(absolute, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("备份 " + key + " 失败", e);
            }
        }
        context.entries.add(new SnapshotEntry(key, existed));
        persist(context);
    }

    /** 读取某次运行写过的文件清单；没有写过文件（或快照已被清理）时返回 {@code null}。 */
    public SnapshotMeta meta(String runId) {
        Path metaFile = root.resolve(sanitize(runId)).resolve(META_FILE);
        if (!Files.isRegularFile(metaFile)) {
            return null;
        }
        try {
            return mapper.readValue(Files.readString(metaFile), SnapshotMeta.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取快照元数据失败", e);
        }
    }

    public RollbackReport rollback(String runId) {
        Path directory = root.resolve(sanitize(runId));
        SnapshotMeta meta = meta(runId);
        if (meta == null) {
            throw new IllegalArgumentException("没有该运行的回滚快照: " + runId);
        }

        Path workspace = Path.of(meta.workspace()).toAbsolutePath().normalize();
        List<String> restored = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (SnapshotEntry entry : meta.files()) {
            Path target = workspace.resolve(entry.path()).normalize();
            if (!target.startsWith(workspace)) {
                failed.add(entry.path());
                continue;
            }
            try {
                if (entry.existed()) {
                    Path backup = directory.resolve("files").resolve(entry.path());
                    if (!Files.isRegularFile(backup)) {
                        failed.add(entry.path());
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                    restored.add(entry.path());
                } else if (Files.deleteIfExists(target)) {
                    deleted.add(entry.path());
                }
            } catch (IOException e) {
                log.warn("回滚 {} 失败", entry.path(), e);
                failed.add(entry.path());
            }
        }
        return new RollbackReport(runId, workspace.toString(), restored, deleted, failed);
    }

    /** 删除最后修改时间早于 {@code cutoff} 的运行快照，返回删掉的运行数。 */
    public int purgeOlderThan(java.time.Instant cutoff) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int removed = 0;
        try (var entries = Files.list(root)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                try {
                    if (Files.getLastModifiedTime(directory).toInstant().isBefore(cutoff)) {
                        deleteRecursively(directory);
                        removed++;
                    }
                } catch (IOException e) {
                    log.warn("清理快照 {} 失败", directory, e);
                }
            }
        } catch (IOException e) {
            log.warn("遍历快照目录失败: {}", root, e);
        }
        return removed;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var entries = Files.walk(directory)) {
            for (Path path : entries.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void persist(Context context) {
        Path directory = context.directory();
        try {
            Files.createDirectories(directory);
            var meta = new SnapshotMeta(context.runId, context.workspace.toString(), List.copyOf(context.entries));
            Files.writeString(directory.resolve(META_FILE), mapper.writeValueAsString(meta));
        } catch (IOException e) {
            throw new UncheckedIOException("写入快照元数据失败", e);
        }
    }

    private static String sanitize(String runId) {
        return runId.replaceAll("[^A-Za-z0-9._-]", "_").toLowerCase(Locale.ROOT);
    }

    private static final class Context {
        private final String runId;
        private final Path workspace;
        private final Path directory;
        private final Set<String> recorded = new LinkedHashSet<>();
        private final List<SnapshotEntry> entries = new ArrayList<>();

        private Context(String runId, Path workspace, Path directory) {
            this.runId = runId;
            this.workspace = workspace;
            this.directory = directory;
        }

        private Path directory() {
            return directory;
        }
    }
}
