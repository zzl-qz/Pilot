package com.pilot.server.workspace;

import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.loop.Message;
import com.pilot.core.plan.PlanItem;
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
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * 运行级文件快照：写工具落盘前备份原文件，支持按 runId 回滚。
 *
 * <p>快照保存在 {@code .pilot/snapshots/{runId}/}，不污染用户仓库。
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

    public record SnapshotEntry(String path, boolean existed, String originalHash, String completedHash) {
        public SnapshotEntry(String path, boolean existed) {
            this(path, existed, null, null);
        }

        public SnapshotEntry(String path, boolean existed, String originalHash) {
            this(path, existed, originalHash, null);
        }
    }

    public record SnapshotMeta(String runId, String workspace, List<SnapshotEntry> files, Boolean completed, List<String> conflicts) {
        public SnapshotMeta {
            completed = Boolean.TRUE.equals(completed);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }
    }

    /** 独立保存每轮开始时的模型记忆，压缩、重复输入和进程重启都不影响定位。 */
    public record ConversationCheckpoint(List<Message> messages, List<PlanItem> plan) { }

    public void saveConversation(String runId, List<Message> messages, List<PlanItem> plan) {
        Path directory = root.resolve(sanitize(runId));
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("conversation.json"), mapper.writeValueAsString(
                    new ConversationCheckpoint(List.copyOf(messages), List.copyOf(plan))));
        } catch (IOException e) {
            throw new UncheckedIOException("保存消息检查点失败", e);
        }
    }

    public ConversationCheckpoint conversation(String runId) {
        Path file = root.resolve(sanitize(runId)).resolve("conversation.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            return mapper.readValue(Files.readString(file), ConversationCheckpoint.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取消息检查点失败", e);
        }
    }

    public record RollbackReport(String runId, String workspace, List<String> restored,
                                 List<String> deleted, List<String> failed, List<String> conflicts) {
    }

    public void bind(String runId, Path workspace) {
        Path directory = root.resolve(sanitize(runId));
        current.set(new Context(runId, workspace.toAbsolutePath().normalize(), directory));
    }

    public void finish() {
        Context context = current.get();
        if (context == null) {
            return;
        }
        boolean completed = true;
        for (int i = 0; i < context.entries.size(); i++) {
            SnapshotEntry entry = context.entries.get(i);
            try {
                Path file = context.workspace.resolve(entry.path()).normalize();
                String completedHash = context.writtenHashes.containsKey(entry.path()) ? context.writtenHashes.get(entry.path())
                        : file.startsWith(context.workspace) ? hashIfRegularFile(file) : null;
                context.entries.set(i, new SnapshotEntry(entry.path(), entry.existed(), entry.originalHash(), completedHash));
            } catch (IOException e) {
                completed = false;
                log.warn("读取 {} 的完成态指纹失败", entry.path(), e);
            }
        }
        context.completed = completed;
        persist(context);
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
            try {
                if (context.writtenHashes.containsKey(key)
                        && !Objects.equals(context.writtenHashes.get(key), hashIfRegularFile(absolute))) {
                    context.conflicts.add(key);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("检查外部修改失败: " + key, e);
            }
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
        try {
            context.entries.add(new SnapshotEntry(key, existed, hashIfRegularFile(absolute)));
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件指纹失败: " + key, e);
        }
        persist(context);
    }

    @Override
    public void afterWrite(Path file) {
        Context context = current.get();
        if (context == null) return;
        String key = context.workspace.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        if (!context.recorded.contains(key)) return;
        try {
            context.writtenHashes.put(key, hashIfRegularFile(file));
        } catch (IOException e) {
            context.conflicts.add(key);
            throw new UncheckedIOException("记录工具写入指纹失败: " + key, e);
        }
    }

    /** 读取某次运行的文件清单；旧运行或快照已清理时返回 {@code null}。 */
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

    public record RestorePreview(List<String> files, List<String> conflicts, List<String> unavailable, boolean ready) {
        public RestorePreview(List<String> files, List<String> conflicts, List<String> unavailable) {
            this(files, conflicts, unavailable, conflicts.isEmpty() && unavailable.isEmpty());
        }
    }

    /** runIds 按时间升序。先合成最终恢复目标，再检查整个链，避免逐轮回滚误判或部分覆盖。 */
    public synchronized RestorePreview preview(List<String> runIds) {
        RestorePlan plan = prepare(runIds);
        return new RestorePreview(List.copyOf(plan.actions.keySet()), List.copyOf(plan.conflicts),
                List.copyOf(plan.unavailable));
    }

    public synchronized RollbackReport rollback(String runId) {
        if (meta(runId) == null) throw new IllegalArgumentException("没有该运行的回滚快照: " + runId);
        return rollback(List.of(runId));
    }

    public synchronized RollbackReport rollback(List<String> runIds) {
        RestorePlan plan = prepare(runIds);
        String runId = runIds.isEmpty() ? null : runIds.getFirst();
        List<String> restored = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>(plan.unavailable);
        if (!plan.conflicts.isEmpty() || !failed.isEmpty()) {
            return new RollbackReport(runId, plan.workspace, restored, deleted, failed, List.copyOf(plan.conflicts));
        }
        List<RestoreAction> applied = new ArrayList<>();
        for (RestoreAction action : plan.actions.values()) {
            try {
                // 预览后仍重新校验；每次实际写入前再检查，避免覆盖预览期间的手工修改。
                if (!safePath(action.workspace, action.target)
                        || !Objects.equals(hashIfRegularFile(action.target), action.currentHash)) {
                    plan.conflicts.add(action.path);
                    break;
                }
                writeContent(action.target, action.content);
                applied.add(action);
                (action.content == null ? deleted : restored).add(action.path);
            } catch (IOException e) {
                log.warn("回滚 {} 失败", action.path, e);
                failed.add(action.path);
                break;
            }
        }
        if (!plan.conflicts.isEmpty() || !failed.isEmpty()) {
            // I/O 失败时补偿已经完成的文件；不覆盖此期间其他编辑器刚写入的内容。
            for (RestoreAction action : applied.reversed()) {
                try {
                    if (safePath(action.workspace, action.target)
                            && Objects.equals(hashIfRegularFile(action.target), hash(action.content))) {
                        writeContent(action.target, action.previous);
                    } else {
                        failed.add(action.path);
                    }
                } catch (IOException e) {
                    failed.add(action.path);
                }
            }
            restored.clear();
            deleted.clear();
        }
        return new RollbackReport(runId, plan.workspace, restored, deleted, failed, List.copyOf(plan.conflicts));
    }

    private RestorePlan prepare(List<String> runIds) {
        RestorePlan plan = new RestorePlan();
        Map<String, String> lastHash = new LinkedHashMap<>();
        Path workspace = null;
        for (String runId : runIds) {
            SnapshotMeta meta = meta(runId);
            if (meta == null || !meta.completed()) {
                plan.unavailable.add("检查点不完整或已过期: " + runId);
                continue;
            }
            plan.conflicts.addAll(meta.conflicts());
            Path runWorkspace = Path.of(meta.workspace()).toAbsolutePath().normalize();
            if (workspace == null) {
                workspace = runWorkspace;
                plan.workspace = workspace.toString();
            } else if (!workspace.equals(runWorkspace)) {
                plan.unavailable.add("所选消息之后切换过工作区，无法一起回退代码");
                continue;
            }
            for (SnapshotEntry entry : meta.files()) {
                Path target = workspace.resolve(entry.path()).normalize();
                try {
                    if (!safePath(workspace, target)) {
                        plan.conflicts.add(entry.path());
                        continue;
                    }
                    if (lastHash.containsKey(entry.path())
                            && !Objects.equals(lastHash.get(entry.path()), entry.originalHash())) {
                        // 两轮之间有人改过同一个文件，恢复首轮快照会丢掉这些修改。
                        plan.conflicts.add(entry.path());
                    }
                    lastHash.put(entry.path(), entry.completedHash());
                    if (!plan.actions.containsKey(entry.path())) {
                        Path backup = root.resolve(sanitize(runId)).resolve("files").resolve(entry.path()).normalize();
                        byte[] content = entry.existed() && Files.isRegularFile(backup) ? Files.readAllBytes(backup) : null;
                        if (entry.existed() && (content == null || !Objects.equals(hash(content), entry.originalHash()))) {
                            plan.unavailable.add(entry.path());
                            continue;
                        }
                        if (Files.exists(target) && Files.size(target) > MAX_SNAPSHOT_BYTES) {
                            plan.unavailable.add(entry.path() + "（当前文件超过 8MB）");
                            continue;
                        }
                        String currentHash = hashIfRegularFile(target);
                        byte[] previous = currentHash == null ? null : Files.readAllBytes(target);
                        plan.actions.put(entry.path(), new RestoreAction(entry.path(), workspace, target,
                                content, currentHash, previous));
                    }
                } catch (IOException e) {
                    plan.unavailable.add(entry.path());
                }
            }
        }
        for (RestoreAction action : plan.actions.values()) {
            if (!Objects.equals(action.currentHash, lastHash.get(action.path))) {
                plan.conflicts.add(action.path);
            }
        }
        return plan;
    }

    private static boolean safePath(Path workspace, Path target) throws IOException {
        if (!target.startsWith(workspace) || target.equals(workspace)) return false;
        Path part = workspace;
        if (Files.isSymbolicLink(part)) return false;
        for (Path segment : workspace.relativize(target)) {
            part = part.resolve(segment);
            if (Files.isSymbolicLink(part)) return false;
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return true;
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            if (((Number) Files.getAttribute(target, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() > 1) return false;
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // Windows 文件系统没有 unix:nlink 属性。
        }
        return true;
    }

    private static void writeContent(Path target, byte[] content) throws IOException {
        if (content == null) {
            Files.deleteIfExists(target);
            return;
        }
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".pilot-restore-", ".tmp");
        try {
            Files.write(temporary, content);
            if (Files.exists(target)) {
                try { Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target)); }
                catch (UnsupportedOperationException ignored) { }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record RestoreAction(String path, Path workspace, Path target, byte[] content,
                                 String currentHash, byte[] previous) { }

    private static final class RestorePlan {
        String workspace;
        final Map<String, RestoreAction> actions = new LinkedHashMap<>();
        final Set<String> conflicts = new LinkedHashSet<>();
        final Set<String> unavailable = new LinkedHashSet<>();
    }

    private static String hashIfRegularFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hash(byte[] content) {
        if (content == null) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
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
            var meta = new SnapshotMeta(context.runId, context.workspace.toString(), List.copyOf(context.entries), context.completed, List.copyOf(context.conflicts));
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
        private boolean completed;
        private final Map<String, String> writtenHashes = new LinkedHashMap<>();
        private final Set<String> conflicts = new LinkedHashSet<>();

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
