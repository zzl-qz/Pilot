package com.pilot.server.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.pilot.core.util.HostOs;
import com.pilot.core.util.Sha256;
import com.pilot.server.common.data.DataMaintenance;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.store.SqliteStore;
import com.pilot.server.workspace.HistoryFileChange;
import com.pilot.server.workspace.RunSnapshotService;

import tools.jackson.databind.ObjectMapper;

/** 文件前后像先落盘；数据库提交标记决定回滚或补齐，不覆盖恢复期间的外部修改。 */
@Component
public class HistoryEditCoordinator {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private final Path directory;
    private final ObjectMapper mapper;
    private final SqliteStore store;
    private final DataMaintenance maintenance;
    private final Consumer<String> faults;
    private final ReentrantReadWriteLock[] access =
        IntStream.range(0, 256).mapToObj(i -> new ReentrantReadWriteLock(true)).toArray(ReentrantReadWriteLock[]::new);

    public DataMaintenance.Scope tryRead(String sessionId) {
        var lock = access[(sessionId.hashCode() & Integer.MAX_VALUE) % access.length].readLock();
        return lock.tryLock() ? lock::unlock : null;
    }

    private DataMaintenance.Scope writeAccess(String key) {
        String sessionId = key.substring(0, key.indexOf(':'));
        var lock = access[(sessionId.hashCode() & Integer.MAX_VALUE) % access.length].writeLock();
        lock.lock();
        return lock::unlock;
    }

    public record Entry(String root, String target, byte[] before, byte[] after, String beforeHash, String afterHash,
        String permissions) {
    }

    public record Pending(int version, String operationId, String key, List<Entry> files) {
    }

    public record Committed(String key, String result) {
    }

    @Autowired
    public HistoryEditCoordinator(PilotDataDir data, ObjectMapper mapper, ObjectProvider<SqliteStore> stores) {
        this(data, mapper, stores.getIfAvailable(), point -> {
        });
    }

    HistoryEditCoordinator(PilotDataDir data, ObjectMapper mapper, SqliteStore store, Consumer<String> faults) {
        this.directory = data.file("history-edits");
        this.maintenance = data.maintenance();
        this.mapper = mapper;
        this.store = store;
        this.faults = faults;
    }

    public static String operationId(String supplied) {
        String id = supplied == null ? UUID.randomUUID().toString() : supplied;
        if (!id.matches("[a-zA-Z0-9_-]{16,100}")) {
            throw new IllegalArgumentException("历史操作标识无效");
        }
        return id;
    }

    public <T> Optional<T> completed(String id, String key, Class<T> type) {
        if (id == null) {
            return Optional.empty();
        }
        operationId(id);
        return committed(id).map(value -> {
            if (!Objects.equals(key, value.key())) {
                throw new IllegalArgumentException("操作标识已用于不同的历史修改");
            }
            return mapper.readValue(value.result(), type);
        });
    }

    /** commit 必须将 mark 与历史修改放进同一数据库事务；文件准备完成前不能修改数据库。 */
    public synchronized void execute(String id, String key, List<HistoryFileChange> changes, Object result,
        Consumer<Runnable> commit) {
        operationId(id);
        try (var activity = maintenance.activity(); var access = writeAccess(key)) {
            if (committed(id).isPresent()) {
                throw new IllegalStateException("历史操作已完成，请读取原结果");
            }
            Path pendingFile = file(id);
            if (Files.exists(pendingFile)) {
                throw new IllegalStateException("历史操作尚待恢复，请重启后重试");
            }
            List<Entry> entries = new ArrayList<>();
            long bytes = 0;
            var targets = new HashSet<Path>();
            for (var change : changes) {
                if (!targets.add(change.target()) || !RunSnapshotService.safePath(change.root(), change.target())) {
                    throw new IOException("恢复目标不安全或重复：" + change.target());
                }
                bytes += (change.before() == null ? 0 : change.before().length)
                    + (long)(change.after() == null ? 0 : change.after().length);
                if (bytes > MAX_BYTES || entries.size() >= 10_000) {
                    throw new IOException("恢复文件超过单次操作预算");
                }
                if (!Objects.equals(currentHash(change.target()), hash(change.before()))) {
                    throw new IOException("文件在准备期间已变化：" + change.target());
                }
                String permissions = null;
                if (Files.exists(change.target())) {
                    try {
                        permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(change.target()));
                    } catch (UnsupportedOperationException ignored) {
                        /* Windows 由文件系统继承权限。 */ }
                }
                entries.add(new Entry(change.root().toString(), change.target().toString(), change.before(),
                    change.after(), hash(change.before()), hash(change.after()), permissions));
            }
            Pending pending = new Pending(1, id, key, List.copyOf(entries));
            try {
                write(pendingFile, mapper.writeValueAsBytes(pending));
                faults.accept("prepared");
                apply(pending, true);
                faults.accept("files-applied");
                String value = mapper.writeValueAsString(new Committed(key, mapper.writeValueAsString(result)));
                var memoryMarked = new AtomicBoolean();
                commit.accept(() -> {
                    if (store != null) {
                        store.commitHistoryOperation(id, value);
                    } else {
                        memoryMarked.set(true);
                    }
                });
                if (store == null && memoryMarked.get()) {
                    write(directory.resolve(id + ".committed"), value.getBytes(StandardCharsets.UTF_8));
                }
                if (committed(id).isEmpty()) {
                    throw new IllegalStateException("历史事务缺少提交标记");
                }
                faults.accept("committed");
                Files.delete(pendingFile);
                forceDirectory(directory);
            } catch (RuntimeException | IOException failure) {
                try {
                    recoverOne(pendingFile);
                } catch (RuntimeException | IOException recovery) {
                    failure.addSuppressed(recovery);
                    maintenance.block("历史回退未恢复完成，请处理文件冲突并重启：" + id);
                }
                throw failure;
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("历史文件操作失败", failure);
        }
    }

    /** code-only 操作也需要持久提交标记，以便重启区分成功回退和中途失败。 */
    public void commitCodeOnly(Runnable mark) {
        if (store == null) {
            mark.run();
        } else {
            store.transaction(() -> {
                mark.run();
                return null;
            });
        }
    }

    @PostConstruct
    public synchronized void recover() {
        if (!Files.exists(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".pending.json")).toList()) {
                recoverOne(file);
            }
        } catch (IOException | RuntimeException failure) {
            maintenance.block("历史回退恢复失败，停止服务以保留现场：" + failure.getMessage());
            throw new IllegalStateException("历史回退恢复失败，请处理冲突后重新启动", failure);
        }
    }

    private void recoverOne(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES * 2) {
            throw new IOException("历史操作日志无效");
        }
        Pending pending = mapper.readValue(Files.readAllBytes(file), Pending.class);
        if (pending.version() != 1 || !file.equals(file(operationId(pending.operationId()))) || pending.files() == null
            || pending.files().size() > 10_000) {
            throw new IOException("历史操作日志格式无效");
        }
        Optional<Committed> committed = committed(pending.operationId());
        if (committed.isPresent() && !Objects.equals(committed.get().key(), pending.key())) {
            throw new IOException("历史提交标记不匹配");
        }
        apply(pending, committed.isPresent());
        Files.delete(file);
        forceDirectory(directory);
    }

    private void apply(Pending pending, boolean forward) throws IOException {
        List<Entry> entries = forward ? pending.files() : pending.files().reversed();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            Path root = Path.of(entry.root()).toAbsolutePath().normalize();
            Path target = Path.of(entry.target()).toAbsolutePath().normalize();
            if (!Objects.equals(hash(entry.before()), entry.beforeHash())
                || !Objects.equals(hash(entry.after()), entry.afterHash())
                || !RunSnapshotService.safePath(root, target)) {
                throw new IOException("历史文件记录校验失败：" + target);
            }
            String current = currentHash(target);
            String desired = forward ? entry.afterHash() : entry.beforeHash();
            if (Objects.equals(current, desired)) {
                continue;
            }
            if (!Objects.equals(current, forward ? entry.beforeHash() : entry.afterHash())) {
                throw new IOException("恢复期间文件被外部修改：" + target);
            }
            RunSnapshotService.writeContent(target, forward ? entry.after() : entry.before());
            if (Files.exists(target) && entry.permissions() != null) {
                try {
                    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(entry.permissions()));
                } catch (UnsupportedOperationException ignored) {
                }
            }
            forceDirectory(target.getParent());
            faults.accept("file-" + i);
        }
    }

    private Optional<Committed> committed(String id) {
        if (store != null) {
            return store.historyCommit(id).map(value -> mapper.readValue(value, Committed.class));
        }
        Path marker = directory.resolve(id + ".committed");
        try {
            return Files.exists(marker) ? Optional.of(mapper.readValue(Files.readAllBytes(marker), Committed.class))
                : Optional.empty();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private Path file(String id) {
        return directory.resolve(id + ".pending.json");
    }

    private static String currentHash(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) {
            throw new IOException("文件类型或大小不支持恢复：" + path);
        }
        return hash(Files.readAllBytes(path));
    }

    private static String hash(byte[] bytes) {
        return bytes == null ? null : Sha256.hex(bytes);
    }

    private void write(Path file, byte[] bytes) {
        try {
            RunSnapshotService.writeContent(file, bytes);
            faults.accept("journal-written");
            forceDirectory(file.getParent());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        // Windows 不允许打开目录 FileChannel；原子文件替换仍使用标准 NIO。
        if (HostOs.isWindows()) {
            return;
        }
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
