package com.pilot.core.runtime.bootstrap;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/** 跨窗口与跨进程的安装锁，异常和中断时也释放文件句柄。 */
final class BootstrapLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private BootstrapLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static BootstrapLock acquire(Path file, Duration timeout) throws Exception {
        Files.createDirectories(file.getParent());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                try {
                    FileLock lock = channel.tryLock();
                    if (lock != null) {
                        return new BootstrapLock(channel, lock);
                    }
                } catch (OverlappingFileLockException e) {
                    // 同一 JVM 的另一个窗口拥有此锁。
                }
                Thread.sleep(100);
            }
            throw new IOException("等待另一个环境准备进程超时");
        } catch (Exception e) {
            channel.close();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
