package com.pilot.server.maintenance;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** 跨数据库和文件的操作在同一维护窗口内排空。 */
public final class DataMaintenance {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private volatile String blocked;

    /** 文件恢复冲突时停止后续存储活动，保留现场等待重启恢复。 */
    public void block(String reason) {
        blocked = reason;
    }

    public void requireHealthy() {
        if (blocked != null) {
            throw new Busy(blocked);
        }
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static final class Busy extends IllegalStateException {
        public Busy(String message) {
            super(message);
        }
    }

    public Scope activity() {
        requireHealthy();
        lock.readLock().lock();
        return lock.readLock()::unlock;
    }

    public Scope tryActivity() {
        if (blocked != null) {
            return null;
        }
        try {
            return lock.readLock().tryLock(0, TimeUnit.NANOSECONDS) ? lock.readLock()::unlock : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void whenAvailable(Runnable action) {
        try (Scope scope = tryActivity()) {
            if (scope != null) {
                action.run();
            }
        }
    }

    public Scope exclusive(Duration timeout) {
        requireHealthy();
        if (lock.getReadHoldCount() > 0 && !lock.isWriteLockedByCurrentThread()) {
            throw new Busy("当前操作尚未结束，请稍后进入维护");
        }
        try {
            if (lock.writeLock().tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                return lock.writeLock()::unlock;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Busy("维护已中断，请重试");
        }
        throw new Busy("仍有任务或写入正在进行，请等操作结束后重试");
    }
}
