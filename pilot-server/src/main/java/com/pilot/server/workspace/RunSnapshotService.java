package com.pilot.server.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pilot.core.loop.Message;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.tool.CommandChangeTracker;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.util.FileTrees;
import com.pilot.core.util.Sha256;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.workspace.ChangeService.RunChangeReport;

import tools.jackson.databind.ObjectMapper;

/**
 * 运行级文件快照：写工具落盘前备份原文件，支持按 runId 回滚。
 *
 * <p>快照保存在 {@code .pilot/snapshots/{runId}/}，不污染用户仓库。
 * 运行上下文通过 ThreadLocal 绑定，因为工具实例按工作区缓存、跨运行复用。
 */
@Component
public class RunSnapshotService implements SnapshotSink, CommandChangeTracker {
    private static final Logger log = LoggerFactory.getLogger(RunSnapshotService.class);
    private static final String META_FILE = "meta.json";
    private static final String CHANGES_FILE = "changes.json";
    /**
     * 超过这个大小的文件不做备份。整文件复制的代价是「每次运行 × 文件大小」，
     * 十几 GB 的日志/产物备份一遍就能把磁盘吃满；跳过时仍记录条目，
     * 回滚会把该文件报进 failed，而不是悄悄删掉它。
     */
    private static final long MAX_SNAPSHOT_BYTES = 8L * 1024 * 1024;
    private static final int MAX_COMMAND_FILES = 100_000;
    private static final long MAX_COMMAND_HASH_BYTES = 64L * 1024 * 1024;
    private static final long MAX_COMMAND_BACKUP_BYTES = 128L * 1024 * 1024;
    private static final Set<String> COMMAND_IGNORED_DIRECTORIES =
        Set.of(".git", ".pilot", ".gradle", ".idea", ".vscode", ".next", ".nuxt", "node_modules", "target", "build",
            "dist", "out", "coverage", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", ".venv", "venv");

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

    public record SnapshotMeta(String runId, String workspace, List<SnapshotEntry> files, Boolean completed,
        List<String> conflicts) {
        public SnapshotMeta {
            completed = Boolean.TRUE.equals(completed);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }
    }

    /** 独立保存每轮开始时的模型记忆，压缩、重复输入和进程重启都不影响定位。 */
    public record ConversationCheckpoint(List<Message> messages, List<PlanItem> plan) {
    }

    public void saveConversation(String runId, List<Message> messages, List<PlanItem> plan) {
        Path directory = root.resolve(sanitize(runId));
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("conversation.json"),
                mapper.writeValueAsString(new ConversationCheckpoint(List.copyOf(messages), List.copyOf(plan))));
        } catch (IOException e) {
            throw new UncheckedIOException("保存消息检查点失败", e);
        }
    }

    public ConversationCheckpoint conversation(String runId) {
        Path file = root.resolve(sanitize(runId)).resolve("conversation.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return mapper.readValue(Files.readString(file), ConversationCheckpoint.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取消息检查点失败", e);
        }
    }

    public record RollbackReport(String runId, String workspace, List<String> restored, List<String> deleted,
        List<String> failed, List<String> conflicts) {
    }

    public void bind(String runId, Path workspace) {
        Path directory = root.resolve(sanitize(runId));
        current.set(new Context(runId, workspace.toAbsolutePath().normalize(), directory));
    }

    public void finish() {
        Context context = current.get();
        if (context == null || context.finished) {
            return;
        }
        Set<String> unavailable = new LinkedHashSet<>();
        for (int i = 0; i < context.entries.size(); i++) {
            SnapshotEntry entry = context.entries.get(i);
            try {
                Path file = context.workspace.resolve(entry.path()).normalize();
                String completedHash =
                    context.writtenHashes.containsKey(entry.path()) ? context.writtenHashes.get(entry.path())
                        : file.startsWith(context.workspace) ? hashIfRegularFile(file) : null;
                context.entries.set(i,
                    new SnapshotEntry(entry.path(), entry.existed(), entry.originalHash(), completedHash));
            } catch (IOException e) {
                unavailable.add(entry.path());
                log.warn("读取 {} 的完成态指纹失败", entry.path(), e);
            }
        }
        context.completed = unavailable.isEmpty();
        SnapshotMeta meta = persist(context);
        context.finished = true;
        try {
            RunChangeReport changes = RunSnapshotDiff.capture(meta, context.directory().resolve("files"), unavailable);
            writeContent(context.directory().resolve(CHANGES_FILE), mapper.writeValueAsBytes(changes));
        } catch (IOException | RuntimeException e) {
            log.warn("保存本轮差异失败 {}", context.runId, e);
        }
    }

    public void clear() {
        current.remove();
    }

    /** 仅返回本轮写前快照与当前原始字节一致的文件；缺失或越界基线不能证明还原。 */
    public synchronized Set<String> restoredPaths() {
        Context context = current.get();
        if (context == null) {
            return Set.of();
        }
        Set<String> restored = new LinkedHashSet<>();
        for (SnapshotEntry entry : context.entries) {
            try {
                Path file = context.workspace.resolve(entry.path()).normalize();
                if (!file.startsWith(context.workspace) || Files.isSymbolicLink(file)) {
                    continue;
                }
                if (!entry.existed()) {
                    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                        restored.add(entry.path());
                    }
                } else if (entry.originalHash() != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && file.toRealPath().startsWith(context.workspace.toRealPath())
                    && entry.originalHash().equals(hashIfRegularFile(file))) {
                    restored.add(entry.path());
                }
            } catch (IOException | RuntimeException ignored) {
                // 检查失败的路径继续视为已改动。
            }
        }
        return Set.copyOf(restored);
    }

    @Override
    public synchronized CommandChangeTracker.Handle begin(Path workspace) {
        Context context = current.get();
        if (context == null) {
            return CommandChangeTracker.Handle.none();
        }
        Path requested = workspace.toAbsolutePath().normalize();
        if (!sameWorkspace(requested, context.workspace)) {
            throw new IllegalStateException("命令工作区与当前运行快照不一致");
        }
        // Keep the lexical root captured by bind(). Snapshot entries may already use that spelling
        // (for example /var on macOS), even when PathValidator resolved the same directory as /private/var.
        Path normalized = context.workspace;
        String id = "command_" + UUID.randomUUID();
        Path directory = commandTrackingDirectory(context.runId, id);
        try {
            Files.createDirectories(directory);
            CommandBaseline baseline = commandInventory(normalized, directory.resolve("files"), true);
            Files.writeString(directory.resolve("baseline.json"), mapper.writeValueAsString(baseline));
            return new CommandChangeTracker.Handle(context.runId, id);
        } catch (IOException | RuntimeException e) {
            try {
                FileTrees.delete(directory);
            } catch (IOException ignored) {
            }
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new UncheckedIOException("建立命令前文件基线失败", (IOException)e);
        }
    }

    /** Recognize platform path aliases without rewriting the snapshot's lexical workspace root. */
    private static boolean sameWorkspace(Path left, Path right) {
        if (left.equals(right)) {
            return true;
        }
        try {
            return Files.isSameFile(left, right);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public synchronized CommandChangeTracker.Result finish(CommandChangeTracker.Handle handle) {
        if (handle == null || !handle.active()) {
            return CommandChangeTracker.Result.empty();
        }
        Path directory = commandTrackingDirectory(handle.runId(), handle.id());
        Path resultFile = directory.resolve("result.json");
        if (Files.isRegularFile(resultFile)) {
            try {
                return mapper.readValue(Files.readString(resultFile), CommandChangeTracker.Result.class);
            } catch (IOException e) {
                return new CommandChangeTracker.Result(List.of(), false, "命令变更结果损坏");
            }
        }
        Path baselineFile = directory.resolve("baseline.json");
        if (!Files.isRegularFile(baselineFile)) {
            return new CommandChangeTracker.Result(List.of(), false, "命令前文件基线不存在或已过期");
        }
        try {
            CommandBaseline baseline = mapper.readValue(Files.readString(baselineFile), CommandBaseline.class);
            Path workspace = Path.of(baseline.workspace()).toAbsolutePath().normalize();
            CommandBaseline after = commandInventory(workspace, null, false);
            Set<String> names = new LinkedHashSet<>(baseline.files().keySet());
            names.addAll(after.files().keySet());
            List<String> changed = names.stream()
                .filter(name -> !Objects.equals(
                    baseline.files().get(name) == null ? null : baseline.files().get(name).hash(),
                    after.files().get(name) == null ? null : after.files().get(name).hash()))
                .sorted().toList();
            for (String name : changed) {
                CommandFile before = baseline.files().get(name);
                byte[] content = null;
                if (before != null && before.backedUp()) {
                    Path backup = directory.resolve("files").resolve(name).normalize();
                    if (!backup.startsWith(directory.resolve("files").normalize()) || !Files.isRegularFile(backup)) {
                        throw new IOException("命令基线备份缺失: " + name);
                    }
                    content = Files.readAllBytes(backup);
                }
                externalChange(workspace.resolve(name),
                    new SnapshotSink.FileSnapshot(before != null, content, before == null ? "" : before.hash()));
            }
            CommandChangeTracker.Result result = new CommandChangeTracker.Result(changed, true, "");
            Files.writeString(resultFile, mapper.writeValueAsString(result));
            return result;
        } catch (IOException | RuntimeException e) {
            CommandChangeTracker.Result result = new CommandChangeTracker.Result(List.of(), false,
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            try {
                Files.writeString(resultFile, mapper.writeValueAsString(result));
            } catch (IOException ignored) {
            }
            return result;
        }
    }

    private CommandBaseline commandInventory(Path workspace, Path backupRoot, boolean backup) throws IOException {
        LinkedHashMap<String, CommandFile> files = new LinkedHashMap<>();
        int[] count = {0};
        long[] backedUpBytes = {0L};
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(workspace)
                    && (directory.toAbsolutePath().normalize().startsWith(RunSnapshotService.this.root)
                        || COMMAND_IGNORED_DIRECTORIES.contains(directory.getFileName().toString()))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
                    return FileVisitResult.CONTINUE;
                }
                if (++count[0] > MAX_COMMAND_FILES) {
                    throw new IOException("工作区可跟踪文件超过上限 " + MAX_COMMAND_FILES);
                }
                String relative = workspace.relativize(file).toString().replace('\\', '/');
                long size = attributes.size();
                String hash = size <= MAX_COMMAND_HASH_BYTES ? hashIfRegularFile(file)
                    : "large:" + size + ":" + attributes.lastModifiedTime().toMillis();
                boolean copied = false;
                if (backup && size <= MAX_SNAPSHOT_BYTES && backedUpBytes[0] + size <= MAX_COMMAND_BACKUP_BYTES) {
                    Path target = backupRoot.resolve(relative).normalize();
                    if (!target.startsWith(backupRoot.normalize())) {
                        throw new IOException("非法命令基线路径");
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    backedUpBytes[0] += size;
                    copied = true;
                }
                files.put(relative, new CommandFile(hash, copied));
                return FileVisitResult.CONTINUE;
            }
        });
        return new CommandBaseline(workspace.toString(), Map.copyOf(files));
    }

    private Path commandTrackingDirectory(String runId, String id) {
        if (id == null || !id.matches("command_[A-Za-z0-9-]{1,160}")) {
            throw new IllegalArgumentException("非法命令基线标识符");
        }
        return root.resolve(sanitize(runId)).resolve("commands").resolve(id).normalize();
    }

    private record CommandBaseline(String workspace, Map<String, CommandFile> files) {
        private CommandBaseline {
            files = files == null ? Map.of() : Map.copyOf(files);
        }
    }

    private record CommandFile(String hash, boolean backedUp) {
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
        if (context == null) {
            return;
        }
        String key = context.workspace.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        if (!context.recorded.contains(key)) {
            return;
        }
        try {
            context.writtenHashes.put(key, hashIfRegularFile(file));
        } catch (IOException e) {
            context.conflicts.add(key);
            throw new UncheckedIOException("记录工具写入指纹失败: " + key, e);
        }
    }

    /** Import a command-discovered change using the pre-command bytes, never the already changed file. */
    @Override
    public synchronized void externalChange(Path file, SnapshotSink.FileSnapshot original) {
        Context context = current.get();
        if (context == null) {
            return;
        }
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(context.workspace) || absolute.equals(context.workspace)) {
            throw new IllegalStateException("拒绝记录工作区之外的命令变更: " + absolute);
        }
        Path relative = context.workspace.relativize(absolute);
        String key = relative.toString().replace('\\', '/');
        try {
            String currentHash = hashIfRegularFile(absolute);
            if (!context.recorded.add(key)) {
                String expected = context.writtenHashes.get(key);
                if (expected != null && original != null && original.hash() != null && !original.hash().isBlank()
                    && !Objects.equals(expected, original.hash())) {
                    context.conflicts.add(key);
                }
                context.writtenHashes.put(key, currentHash);
                persist(context);
                return;
            }

            boolean existed = original != null && original.existed();
            byte[] content = original == null ? null : original.content();
            String originalHash = original == null ? null : original.hash();
            if (existed && content != null) {
                Path target = context.directory().resolve("files").resolve(relative).normalize();
                if (!target.startsWith(context.directory().resolve("files").normalize())) {
                    throw new IllegalStateException("非法命令快照路径: " + key);
                }
                Files.createDirectories(target.getParent());
                Files.write(target, content);
                if (originalHash == null || originalHash.isBlank()) {
                    originalHash = hash(content);
                }
            }
            context.entries.add(new SnapshotEntry(key, existed, originalHash, currentHash));
            context.writtenHashes.put(key, currentHash);
            persist(context);
        } catch (IOException e) {
            throw new UncheckedIOException("记录命令变更失败: " + key, e);
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

    /** 按完成时的内容读取审阅补丁，旧快照没有这份记录。 */
    public RunChangeReport changes(String runId) {
        Path file = root.resolve(sanitize(runId)).resolve(CHANGES_FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            RunChangeReport report = mapper.readValue(Files.readString(file), RunChangeReport.class);
            return runId.equals(report.runId()) ? report : null;
        } catch (IOException e) {
            throw new UncheckedIOException("读取本轮差异失败", e);
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

    /** 仅准备文件前后像，由历史操作日志统一应用和恢复。 */
    public synchronized List<HistoryFileChange> historyChanges(List<String> runIds) {
        RestorePlan plan = prepare(runIds);
        if (!plan.conflicts.isEmpty() || !plan.unavailable.isEmpty()) {
            throw new IllegalStateException("检查点文件已变化或不可用，请重新预览");
        }
        return plan.actions.values().stream()
            .map(action -> new HistoryFileChange(action.workspace, action.target, action.previous, action.content))
            .toList();
    }

    public synchronized RollbackReport rollback(String runId) {
        if (meta(runId) == null) {
            throw new IllegalArgumentException("没有该运行的回滚快照: " + runId);
        }
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
                        byte[] content =
                            entry.existed() && Files.isRegularFile(backup) ? Files.readAllBytes(backup) : null;
                        if (entry.existed()
                            && (content == null || !Objects.equals(hash(content), entry.originalHash()))) {
                            plan.unavailable.add(entry.path());
                            continue;
                        }
                        if (Files.exists(target) && Files.size(target) > MAX_SNAPSHOT_BYTES) {
                            plan.unavailable.add(entry.path() + "（当前文件超过 8MB）");
                            continue;
                        }
                        String currentHash = hashIfRegularFile(target);
                        byte[] previous = currentHash == null ? null : Files.readAllBytes(target);
                        plan.actions.put(entry.path(),
                            new RestoreAction(entry.path(), workspace, target, content, currentHash, previous));
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

    public static boolean safePath(Path workspace, Path target) throws IOException {
        if (!target.startsWith(workspace) || target.equals(workspace)) {
            return false;
        }
        Path part = workspace;
        if (Files.isSymbolicLink(part)) {
            return false;
        }
        for (Path segment : workspace.relativize(target)) {
            part = part.resolve(segment);
            if (Files.isSymbolicLink(part)) {
                return false;
            }
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            if (((Number)Files.getAttribute(target, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() > 1) {
                return false;
            }
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // Windows 文件系统没有 unix:nlink 属性。
        }
        return true;
    }

    public static void writeContent(Path target, byte[] content) throws IOException {
        if (content == null) {
            Files.deleteIfExists(target);
            return;
        }
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".pilot-restore-", ".tmp");
        try {
            Files.write(temporary, content);
            try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (Files.exists(target)) {
                try {
                    Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target));
                } catch (UnsupportedOperationException ignored) {
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record RestoreAction(String path, Path workspace, Path target, byte[] content, String currentHash,
        byte[] previous) {
    }

    private static final class RestorePlan {
        String workspace;
        final Map<String, RestoreAction> actions = new LinkedHashMap<>();
        final Set<String> conflicts = new LinkedHashSet<>();
        final Set<String> unavailable = new LinkedHashSet<>();
    }

    private static String hashIfRegularFile(Path file) throws IOException {
        return Files.isRegularFile(file) ? Sha256.hex(file) : null;
    }

    static String hash(byte[] content) {
        return content == null ? null : Sha256.hex(content);
    }

    /** 删除最后修改时间早于 {@code cutoff} 的运行快照，返回删掉的运行数。 */
    public int purgeOlderThan(java.time.Instant cutoff) {
        return purgeOlderThan(cutoff, Set.of());
    }

    public int purgeOlderThan(java.time.Instant cutoff, Set<String> protectedRunIds) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> entries = Files.list(root)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                try {
                    Path metaFile = directory.resolve(META_FILE);
                    String runId = Files.isRegularFile(metaFile)
                        ? mapper.readValue(Files.readString(metaFile), SnapshotMeta.class).runId()
                        : directory.getFileName().toString();
                    if (!protectedRunIds.contains(runId)
                        && Files.getLastModifiedTime(directory).toInstant().isBefore(cutoff)) {
                        FileTrees.delete(directory);
                        removed++;
                    }
                } catch (IOException | RuntimeException e) {
                    log.warn("清理快照 {} 失败", directory, e);
                }
            }
        } catch (IOException e) {
            log.warn("遍历快照目录失败: {}", root, e);
        }
        return removed;
    }

    private SnapshotMeta persist(Context context) {
        Path directory = context.directory();
        try {
            SnapshotMeta meta = new SnapshotMeta(context.runId, context.workspace.toString(),
                List.copyOf(context.entries), context.completed, List.copyOf(context.conflicts));
            writeContent(directory.resolve(META_FILE), mapper.writeValueAsBytes(meta));
            return meta;
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
        private boolean finished;
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
