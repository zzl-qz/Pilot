package com.pilot.server.workspace;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.pilot.core.message.Message;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.core.tool.command.CommandChangeTracker;
import com.pilot.core.util.FileTrees;
import com.pilot.core.util.Sha256;
import com.pilot.core.util.Texts;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.workspace.ChangeService.RunChangeReport;

import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 运行级文件快照：写工具落盘前备份原文件，支持按 runId 回滚。
 *
 * <p>快照保存在 {@code .pilot/snapshots/{runId}/}，不污染用户仓库。
 * 运行上下文通过 ThreadLocal 绑定，因为工具实例按工作区缓存、跨运行复用。
 */
@Component
@Slf4j
public class RunSnapshotService implements SnapshotSink, CommandChangeTracker {
    private static final String META_FILE = "meta.json";
    private static final String CHANGES_FILE = "changes.json";
    /** 轮末另存一份完成态内容，之后磁盘再被改动也能算出这一轮到底改了什么。 */
    private static final String COMPLETED_DIR = "completed";
    /**
     * 超过这个大小的文件不做备份。整文件复制的代价是「每次运行 × 文件大小」，
     * 十几 GB 的日志/产物备份一遍就能把磁盘吃满；跳过时仍记录条目，
     * 回滚会把该文件报进 failed，而不是悄悄删掉它。
     */
    private static final long MAX_SNAPSHOT_BYTES = 8L * 1024 * 1024;
    private static final int MAX_COMMAND_FILES = 100_000;
    private static final long MAX_COMMAND_HASH_BYTES = 64L * 1024 * 1024;
    private static final long MAX_COMMAND_BACKUP_BYTES = 128L * 1024 * 1024;
    /**
     * 命令前基线是 best-effort：超过这个时间就降级为“大小+修改时间”指纹，
     * 不能再把整个基线（连带本轮所有改动）弄没。
     */
    private static final long COMMAND_BASELINE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long GIT_IGNORE_TIMEOUT_MILLIS = 2_000L;
    private static final Set<String> COMMAND_IGNORED_DIRECTORIES =
        Set.of(".git", ".pilot", ".gradle", ".idea", ".vscode", ".next", ".nuxt", ".turbo", ".cache", "coverage",
            "node_modules", "target", "build", "dist", "out", "__pycache__", ".pytest_cache", ".mypy_cache",
            ".ruff_cache", ".venv", "venv", ".analysis", ".parcel-cache", ".pnpm-store", ".yarn", "profiles");

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
                context.entries.set(i, new SnapshotEntry(entry.path(), entry.existed(), entry.originalHash(), completedHash));
                keepCompletedContent(context, entry.path(), completedHash, false);
            } catch (IOException e) {
                unavailable.add(entry.path());
                log.warn("读取 {} 的完成态指纹失败", entry.path(), e);
            }
        }
        context.completed = unavailable.isEmpty();
        SnapshotMeta meta = persist(context);
        context.finished = true;
        try {
            RunChangeReport changes = RunSnapshotDiff.capture(meta, context.directory().resolve("files"),
                context.directory().resolve(COMPLETED_DIR), unavailable);
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
        return begin(workspace, COMMAND_BASELINE_BUDGET_NANOS);
    }

    synchronized CommandChangeTracker.Handle begin(Path workspace, long budgetNanos) {
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
            long deadline = System.nanoTime() + budgetNanos;
            CommandBaseline baseline = commandInventory(normalized, directory.resolve("files"), deadline, Map.of());
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
            throw new UncheckedIOException("建立命令前文件基线失败：" + describe(e), (IOException)e);
        }
    }

    /** 错误信息必须带异常类型与消息，否则调用方只能看到一句“失败”，无法定位是锁文件、超时还是路径。 */
    private static String describe(Exception error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
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
            CommandBaseline after = commandInventory(workspace, null, 0, baseline.files());
            Set<String> untracked = new LinkedHashSet<>(baseline.skipped());
            untracked.addAll(after.skipped());
            Set<String> names = new LinkedHashSet<>(baseline.files().keySet());
            names.addAll(after.files().keySet());
            names.removeAll(untracked);
            List<String> changed = names.stream()
                .filter(name -> !Objects.equals(
                    baseline.files().get(name) == null ? null : baseline.files().get(name).hash(),
                    after.files().get(name) == null ? null : after.files().get(name).hash()))
                .sorted().toList();
            Set<String> ignoredOutputs = gitIgnoredUntracked(workspace, changed);
            if (!ignoredOutputs.isEmpty()) {
                changed = changed.stream().filter(name -> !ignoredOutputs.contains(name)).toList();
            }
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
            String warning = skippedWarning(untracked);
            long metadataOnlyFiles = baseline.files().values().stream().filter(CommandFile::metadataOnly).count();
            if (metadataOnlyFiles > 0) {
                if (!warning.isBlank()) {
                    warning += "；";
                }
                warning += metadataOnlyFiles + " 个文件仅按大小和修改时间检测变化，未保存原内容，可能漏报同大小、同修改时间的改动。";
            }
            CommandChangeTracker.Result result = new CommandChangeTracker.Result(changed, true, warning);
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

    /**
     * 过滤构建/编译命令生成的 Git 忽略产物（如 Maven/Vite 写出的静态资源），避免它们污染 changedFiles 并把先前通过的检查标成 CHECKS_STALE。
     * {@code git check-ignore} 默认不命中已跟踪文件；非 Git 目录、git 不可用或超时一律回退保留原清单。
     */
    static Set<String> gitIgnoredUntracked(Path workspace, List<String> relativePaths) {
        if (workspace == null || relativePaths == null || relativePaths.isEmpty() || !insideGitRepo(workspace)) {
            return Set.of();
        }
        Process process = null;
        try {
            process = new ProcessBuilder("git", "check-ignore", "--stdin", "-z").directory(workspace.toFile()).start();
            Process started = process;
            byte[][] stdout = new byte[1][];
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    stdout[0] = started.getInputStream().readAllBytes();
                } catch (IOException ignored) {
                    stdout[0] = new byte[0];
                }
            });
            try (OutputStream out = started.getOutputStream()) {
                for (String path : relativePaths) {
                    out.write(path.getBytes(StandardCharsets.UTF_8));
                    out.write(0);
                }
            }
            if (!started.waitFor(GIT_IGNORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                started.destroyForcibly();
                return Set.of();
            }
            reader.join(GIT_IGNORE_TIMEOUT_MILLIS);
            if (started.exitValue() != 0 || stdout[0] == null || stdout[0].length == 0) {
                return Set.of();
            }
            return parseNullSeparatedPaths(stdout[0]);
        } catch (IOException | InterruptedException | RuntimeException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Set.of();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static boolean insideGitRepo(Path workspace) {
        Path current = workspace.toAbsolutePath().normalize();
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getParent()) {
            if (Files.exists(current.resolve(".git"))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> parseNullSeparatedPaths(byte[] raw) {
        Set<String> ignored = new HashSet<>();
        int start = 0;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == 0) {
                if (i > start) {
                    ignored.add(new String(raw, start, i - start, StandardCharsets.UTF_8).replace('\\', '/'));
                }
                start = i + 1;
            }
        }
        if (start < raw.length) {
            ignored.add(new String(raw, start, raw.length - start, StandardCharsets.UTF_8).replace('\\', '/'));
        }
        return ignored;
    }

    private CommandBaseline commandInventory(Path workspace, Path backupRoot, long deadlineNanos,
        Map<String, CommandFile> beforeFiles) throws IOException {
        LinkedHashMap<String, CommandFile> files = new LinkedHashMap<>();
        LinkedHashSet<String> skipped = new LinkedHashSet<>();
        int[] count = {0};
        long[] backedUpBytes = {0L};
        boolean[] degraded = {false};
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
                count[0]++;
                if (count[0] > MAX_COMMAND_FILES) {
                    throw new IOException("工作区可跟踪文件超过上限 " + MAX_COMMAND_FILES);
                }
                String relative = workspace.relativize(file).toString().replace('\\', '/');
                if (!degraded[0] && backupRoot != null && ((count[0] - 1) & 0x1F) == 0
                    && System.nanoTime() - deadlineNanos >= 0) {
                    // 超时后不再读内容：改用大小+修改时间指纹，基线仍然完整，只是精度降低。
                    degraded[0] = true;
                }
                CommandFile before = beforeFiles.get(relative);
                // 降级过的路径在命令后也要用元数据；异种指纹比较会把未改动的文件判成改动。
                if (degraded[0] || (before != null && before.metadataOnly())) {
                    files.put(relative, new CommandFile(
                        "stat:" + attributes.size() + ":" + attributes.lastModifiedTime().toMillis(), false));
                    return FileVisitResult.CONTINUE;
                }
                Path target = backupRoot == null ? null : backupRoot.resolve(relative).normalize();
                if (target != null && !target.startsWith(backupRoot.normalize())) {
                    throw new IOException("非法命令基线路径");
                }
                try {
                    files.put(relative, recordCommandFile(file, attributes, target, backedUpBytes));
                } catch (IOException e) {
                    // 被占用、无权限或临时消失的文件不能拖垮整份基线：跳过它，由 finish 明确报告。
                    skipped.add(relative);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                if (workspace.equals(file)) {
                    throw error;
                }
                skipped.add(workspace.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
        });
        return new CommandBaseline(workspace.toString(), Map.copyOf(files), List.copyOf(skipped));
    }

    /** 读取单个文件的基线指纹与备份；读不了就抛 IOException，由调用方跳过而不是放弃整份基线。 */
    CommandFile recordCommandFile(Path file, BasicFileAttributes attributes, Path backupTarget, long[] backedUpBytes)
        throws IOException {
        long size = attributes.size();
        String hash = size <= MAX_COMMAND_HASH_BYTES ? hashIfRegularFile(file)
            : "large:" + size + ":" + attributes.lastModifiedTime().toMillis();
        boolean copied = false;
        if (backupTarget != null && size <= MAX_SNAPSHOT_BYTES && backedUpBytes[0] + size <= MAX_COMMAND_BACKUP_BYTES) {
            Files.createDirectories(backupTarget.getParent());
            Files.copy(file, backupTarget, StandardCopyOption.REPLACE_EXISTING);
            backedUpBytes[0] += size;
            copied = true;
        }
        return new CommandFile(hash, copied);
    }

    /** 读不了的文件既不能被当成“没有改动”，也不该让整份基线失败；单独说明它们没进追踪。 */
    private static String skippedWarning(Set<String> untracked) {
        if (untracked.isEmpty()) {
            return "";
        }
        List<String> names = untracked.stream().sorted().toList();
        String sample = String.join("、", names.subList(0, Math.min(5, names.size())));
        return untracked.size() + " 个文件读不了（被占用、无权限或临时消失），没有进入命令前文件基线，" + "这些文件的改动不在本轮差异/回滚追踪内，不能据此认为它们没有改动：" + sample
            + (names.size() > 5 ? " 等" : "");
    }

    private Path commandTrackingDirectory(String runId, String id) {
        if (id == null || !id.matches("command_[A-Za-z0-9-]{1,160}")) {
            throw new IllegalArgumentException("非法命令基线标识符");
        }
        return root.resolve(sanitize(runId)).resolve("commands").resolve(id).normalize();
    }

    record CommandBaseline(String workspace, Map<String, CommandFile> files, List<String> skipped) {
        CommandBaseline {
            files = files == null ? Map.of() : Map.copyOf(files);
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
        }
    }

    record CommandFile(String hash, boolean backedUp) {
        boolean metadataOnly() {
            return hash != null && hash.startsWith("stat:");
        }
    }

    /**
     * 返回本轮首次写入前的文本。新建文件返回 {@code null}；快照缺失或内容不是可检查文本时抛异常。
     */
    public synchronized String originalText(String relativePath) {
        Context context = current.get();
        if (context == null || relativePath == null || relativePath.isBlank()) {
            throw new IllegalStateException("当前运行没有可用的写前快照");
        }
        String key = relativePath.replace('\\', '/');
        SnapshotEntry entry = null;
        for (SnapshotEntry candidate : context.entries) {
            if (candidate.path().equals(key)) {
                entry = candidate;
                break;
            }
        }
        if (entry == null) {
            throw new IllegalStateException("缺少写前快照: " + key);
        }
        if (!entry.existed()) {
            return null;
        }
        Path backup = context.directory().resolve("files").resolve(key).normalize();
        if (!backup.startsWith(context.directory().resolve("files").normalize()) || !Files.isRegularFile(backup)) {
            throw new IllegalStateException("写前快照不可读: " + key);
        }
        try {
            if (Files.size(backup) > MAX_SNAPSHOT_BYTES) {
                throw new IllegalStateException("写前快照超过文本检查上限: " + key);
            }
            byte[] bytes = Files.readAllBytes(backup);
            if (Texts.isBinary(bytes)) {
                throw new IllegalStateException("写前快照不是文本: " + key);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取写前快照失败: " + key, e);
        }
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
        keepCompletedContent(context, key, context.writtenHashes.get(key), true);
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
                keepCompletedContent(context, key, currentHash, true);
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
            keepCompletedContent(context, key, currentHash, true);
            persist(context);
        } catch (IOException e) {
            throw new UncheckedIOException("记录命令变更失败: " + key, e);
        }
    }

    /**
     * 把本轮完成时的文件内容另存一份，供之后生成改动卡使用。
     *
     * <p>改动卡要回答的是「这一轮改了什么」，不是「磁盘现在和记录还能不能对上」。
     * 写完就把内容留在手里，事后手工改动、{@code git stash}、删掉文件都不会把这一轮的数字变成 0。
     * 超过备份上限的文件不复制，差异回退到读当前磁盘；副本坏了由读取端按指纹识别。
     *
     * @param replace 写完之后立刻存的是最新内容，要覆盖旧副本；轮末补存时不能覆盖写的时候留下的那一份
     */
    private void keepCompletedContent(Context context, String key, String completedHash, boolean replace) {
        Path directory = context.directory().resolve(COMPLETED_DIR).normalize();
        Path target = directory.resolve(key).normalize();
        if (target.equals(directory) || !target.startsWith(directory)) {
            return;
        }
        try {
            if (completedHash == null) {
                Files.deleteIfExists(target);
                return;
            }
            if (!replace && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Path source = context.workspace.resolve(key).normalize();
            if (!source.startsWith(context.workspace) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.size(source) > MAX_SNAPSHOT_BYTES) {
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.debug("保存 {} 的完成态副本失败", key, e);
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
