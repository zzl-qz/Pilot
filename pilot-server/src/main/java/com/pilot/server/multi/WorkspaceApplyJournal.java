package com.pilot.server.multi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.SandboxChange;
import com.pilot.core.workspace.PathValidator;

import tools.jackson.databind.ObjectMapper;

/** 可恢复的文件应用日志。单文件原子替换；整批失败补偿，不承诺外部进程看到跨文件原子切换。 */
public final class WorkspaceApplyJournal {
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final Path directory;
    private final ObjectMapper mapper;
    private final FaultPoint faults;

    @FunctionalInterface
    interface FaultPoint {
        void reached(String point, int index) throws IOException;
    }

    public record Entry(String path, String before, String after, List<String> permissions) {
    }

    public record Journal(int version, String operationId, String workspace, String sessionId, String sandboxId,
        String phase, long targetRevision, List<Entry> entries) {
    }

    public record State(String operationId, String phase, String sessionId, String sandboxId, List<String> paths) {
    }

    public WorkspaceApplyJournal(Path directory, ObjectMapper mapper) {
        this(directory, mapper, (point, index) -> {
        });
    }

    WorkspaceApplyJournal(Path directory, ObjectMapper mapper, FaultPoint faults) {
        this.directory = directory;
        this.mapper = mapper;
        this.faults = faults;
    }

    public <T> T locked(Path workspace, Supplier<T> work) {
        Path root = new PathValidator(workspace).root();
        ReentrantLock lock = LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock());
        lock.lock();
        try {
            if (lock.getHoldCount() > 1) {
                return work.get();
            }
            Path folder = folder(root);
            Files.createDirectories(folder);
            if (Files.getFileAttributeView(folder, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
            }
            try (
                var channel =
                    FileChannel.open(folder.resolve("apply.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var ignored = channel.lock()) {
                return work.get();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法锁定变更应用记录", e);
        } finally {
            lock.unlock();
        }
    }

    public State state(Path workspace) {
        return locked(workspace, () -> {
            Journal journal = read(workspace);
            return journal == null ? null : new State(journal.operationId(), journal.phase(), journal.sessionId(),
                journal.sandboxId(), journal.entries().stream().map(Entry::path).toList());
        });
    }

    void checkpoint(String point) throws IOException {
        faults.reached(point, -1);
    }

    /** 开启下一轮前不能删除尚待恢复的沙箱元数据。此检查由 SandboxStore 锁保护。 */
    void assertSessionIdle(String sessionId) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var folders = Files.list(directory)) {
            for (Path folder : folders.toList()) {
                Path file = folder.resolve("pending.json");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Journal journal = mapper.readValue(Files.readString(file), Journal.class);
                if (journal == null || sessionId.equals(journal.sessionId())) {
                    throw new IllegalStateException("会话存在未恢复的变更应用，请先恢复再开启新一轮任务");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法确认应用恢复状态，停止清理会话", e);
        }
    }

    Journal read(Path workspace) {
        Path root = new PathValidator(workspace).root();
        Path file = folder(root).resolve("pending.json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            Journal journal = mapper.readValue(Files.readString(file), Journal.class);
            if (journal == null || journal.version() != 1 || !root.toString().equals(journal.workspace())
                || !Set.of("prepared", "applying", "applied").contains(journal.phase()) || journal.entries() == null
                || journal.entries().isEmpty() || journal.targetRevision() < 1 || journal.sessionId() == null
                || journal.sandboxId() == null || journal.operationId() == null) {
                throw new IOException("应用日志内容无效");
            }
            Set<Path> paths = new HashSet<>();
            for (Entry entry : journal.entries()) {
                if (entry == null || entry.after() == null || entry.permissions() == null
                    || !paths.add(target(root, entry.path()))) {
                    throw new IOException("应用日志文件条目无效");
                }
            }
            return journal;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("应用日志损坏，停止写入并保留现场", e);
        }
    }

    /** 必须持有 locked；先检查整批冲突、落盘备份，再触碰任何目标文件。 */
    Journal apply(Path workspace, String sessionId, String sandboxId, List<SandboxChange> changes, long targetRevision)
        throws IOException {
        Path root = new PathValidator(workspace).root();
        if (read(root) != null) {
            throw new IllegalStateException("有未完成的变更应用，请先恢复");
        }
        List<Entry> entries = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (SandboxChange change : changes) {
            Path file = target(root, change.path());
            if (!seen.add(file)) {
                throw new IOException("重复的目标路径：" + change.path());
            }
            String before = content(file);
            if (!Objects.equals(before, change.newContent()) && (change.existed()
                ? before == null || !DiffSandbox.fingerprint(before).equals(change.baseHash()) : before != null)) {
                throw new IOException("文件已被修改或占用，整批未写入：" + change.path());
            }
            entries.add(new Entry(change.path(), before, change.newContent(), permissions(file)));
        }
        Journal journal = new Journal(1, UUID.randomUUID().toString(), root.toString(), sessionId, sandboxId,
            "prepared", targetRevision, List.copyOf(entries));
        save(root, journal);
        try {
            faults.reached("prepared", -1);
            journal = phase(journal, "applying");
            save(root, journal);
            int index = 0;
            for (Entry entry : entries) {
                Path file = target(root, entry.path());
                faults.reached("before-write", index);
                if (!Objects.equals(content(file), entry.before())) {
                    throw new IOException("应用期间文件发生变化：" + entry.path());
                }
                replace(file, entry.after(), entry.permissions());
                faults.reached("after-write", index++);
            }
            // 提交前再次检查整批，不能把中途的外部修改记为已接受。
            for (Entry entry : entries) {
                if (!Objects.equals(content(target(root, entry.path())), entry.after())) {
                    throw new IOException("应用期间文件发生变化：" + entry.path());
                }
            }
            faults.reached("before-commit", -1);
            journal = phase(journal, "applied");
            save(root, journal);
            faults.reached("after-commit", -1);
            return journal;
        } catch (IOException | RuntimeException failure) {
            try {
                rollback(root, journal);
            } catch (IOException | RuntimeException recovery) {
                failure.addSuppressed(recovery);
                throw new IOException("应用失败且恢复遇到冲突，已保留备份，请处理后重试恢复：" + recovery.getMessage(), failure);
            }
            throw failure;
        }
    }

    void rollback(Path workspace, Journal journal) throws IOException {
        Path root = new PathValidator(workspace).root();
        // 先检查所有文件，遇到用户新修改时不覆盖任何一个冲突文件。
        for (Entry entry : journal.entries()) {
            assertRecoverable(root, entry);
        }
        List<Entry> reversed = new ArrayList<>(journal.entries());
        Collections.reverse(reversed);
        for (Entry entry : reversed) {
            assertRecoverable(root, entry);
            Path file = target(root, entry.path());
            if (Objects.equals(content(file), entry.before())) {
                continue;
            }
            if (entry.before() == null) {
                Files.delete(file);
            } else {
                replace(file, entry.before(), entry.permissions());
            }
        }
        clear(root);
    }

    private static void assertRecoverable(Path root, Entry entry) throws IOException {
        String current = content(target(root, entry.path()));
        if (!Objects.equals(current, entry.before()) && !Objects.equals(current, entry.after())) {
            throw new IOException("文件包含日志之外的修改，拒绝覆盖：" + entry.path());
        }
    }

    void clear(Path workspace) throws IOException {
        Files.deleteIfExists(folder(new PathValidator(workspace).root()).resolve("pending.json"));
    }

    private Path folder(Path root) {
        return directory.resolve(DiffSandbox.fingerprint(root.toString()));
    }

    private static Journal phase(Journal journal, String phase) {
        return new Journal(journal.version(), journal.operationId(), journal.workspace(), journal.sessionId(),
            journal.sandboxId(), phase, journal.targetRevision(), journal.entries());
    }

    private void save(Path root, Journal journal) throws IOException {
        Path file = folder(root).resolve("pending.json");
        Files.createDirectories(file.getParent());
        List<String> privateMode = Files.getFileAttributeView(file.getParent(), PosixFileAttributeView.class) == null
            ? List.of() : List.of("OWNER_READ", "OWNER_WRITE");
        replace(file, mapper.writeValueAsString(journal), privateMode);
    }

    static Path target(Path root, String relative) throws IOException {
        if (relative == null || Path.of(relative).isAbsolute()) {
            throw new IOException("目标必须为工作区内相对文件");
        }
        Path given = root.resolve(relative).normalize();
        if (!given.startsWith(root) || given.equals(root)) {
            throw new IOException("目标超出工作区");
        }
        for (Path part = given; !part.equals(root); part = part.getParent()) {
            if (Files.isSymbolicLink(part)) {
                throw new IOException("不通过符号链接应用变更：" + relative);
            }
            String name = part.getFileName().toString();
            if (name.equalsIgnoreCase(".git") || name.equalsIgnoreCase(".pilot")) {
                throw new IOException("不能修改内部管理目录：" + relative);
            }
        }
        return new PathValidator(root).resolve(relative);
    }

    static String content(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("目标不是普通文件：" + file.getFileName());
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static List<String> permissions(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.getPosixFilePermissions(file).stream().map(Enum::name).sorted().toList();
        } catch (UnsupportedOperationException ignored) {
            return List.of();
        }
    }

    private static void replace(Path file, String content, List<String> permissions) throws IOException {
        Files.createDirectories(file.getParent());
        Path staged = file.resolveSibling(".pilot-apply-" + UUID.randomUUID() + ".tmp");
        try {
            try (var channel = FileChannel.open(staged, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            if (!permissions.isEmpty()) {
                Files.setPosixFilePermissions(staged,
                    permissions.stream().map(PosixFilePermission::valueOf).collect(Collectors.toSet()));
            }
            // 不支持原子替换的平台明确失败；不能降级成截断目标文件再复制。
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
