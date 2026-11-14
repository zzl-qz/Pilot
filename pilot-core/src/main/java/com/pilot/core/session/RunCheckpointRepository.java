package com.pilot.core.session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 运行检查点端口。
 *
 * <p>持久化实现必须保证「租约抢占」是一个原子操作：同一会话在任何时刻只允许一个实例推进。
 * 内存实现只用于测试和无存储场景，重启后不保留任何状态。
 */
public interface RunCheckpointRepository {

    /** 覆盖保存；由租约持有者调用。 */
    void save(RunCheckpoint checkpoint);

    Optional<RunCheckpoint> find(String runId);

    Optional<RunCheckpoint> activeForSession(String sessionId);

    /** 所有仍可恢复的检查点（RUNNING / INTERRUPTED / PAUSED），按更新时间升序。 */
    List<RunCheckpoint> resumable();

    /**
     * 原子抢占租约。未指定时长时使用 {@link RunCheckpoint#DEFAULT_LEASE}。
     *
     * @return true 表示调用方现在持有租约；false 表示另一个实例持有未过期租约
     */
    boolean claimLease(String runId, String owner, Instant now, Duration lease);

    /** 续租。时长默认值与抢占相同，返回 false 表示租约已丢失，调用方应停止推进。 */
    boolean renewLease(String runId, String owner, Instant now, Duration lease);

    /** 主动释放租约（正常结束时），保留检查点用于诊断。 */
    void releaseLease(String runId, String status, Instant now);

    int deleteBySession(String sessionId);
}
