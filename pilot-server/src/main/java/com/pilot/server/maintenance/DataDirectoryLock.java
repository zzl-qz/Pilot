package com.pilot.server.maintenance;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.pilot.core.multi.DiffSandbox;

/** 服务器和离线维护共用的进程间锁。锁文件放在要切换的目录之外。 */
public final class DataDirectoryLock implements AutoCloseable {
    private final List<FileChannel> channels = new ArrayList<>();

    private DataDirectoryLock() {
    }

    public static DataDirectoryLock acquire(Path data, Path database, Path attachments) throws IOException {
        Path root = canonical(data);
        List<Path> targets = new ArrayList<>();
        targets.add(root);
        for (Path path : new Path[] {database, attachments}) {
            if (path != null) {
                Path target = canonical(path);
                if (!target.startsWith(root)) {
                    targets.add(target);
                }
            }
        }
        DataDirectoryLock lock = new DataDirectoryLock();
        try {
            for (Path target : targets.stream().distinct().sorted().toList()) {
                Path directory = Files.createDirectories(control(target));
                FileChannel channel =
                    FileChannel.open(directory.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                lock.channels.add(channel);
                if (channel.tryLock() == null) {
                    throw new IOException("数据正被其他 Pilot 进程使用，请先退出服务：" + target);
                }
            }
            return lock;
        } catch (IOException | RuntimeException e) {
            try {
                lock.close();
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            if (e instanceof OverlappingFileLockException) {
                throw new IOException("数据正被其他 Pilot 实例使用，请先退出服务", e);
            }
            throw e;
        }
    }

    static Path canonical(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute)) {
            return absolute.toRealPath();
        }
        Path parent = absolute.getParent();
        return parent == null ? absolute : canonical(parent).resolve(absolute.getFileName());
    }

    static Path control(Path target) throws IOException {
        Path canonical = canonical(target);
        if (canonical.getParent() == null) {
            throw new IOException("文件系统根目录不能用作维护目标");
        }
        String identity = canonical.toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            identity = identity.toLowerCase(Locale.ROOT);
        }
        return canonical.getParent().resolve(".pilot-maintenance").resolve(DiffSandbox.fingerprint(identity));
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (FileChannel channel : channels.reversed()) {
            try {
                channel.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
