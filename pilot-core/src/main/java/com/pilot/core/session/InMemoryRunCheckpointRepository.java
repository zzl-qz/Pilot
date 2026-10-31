package com.pilot.core.session;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存实现：用于单元测试与不落盘的运行方式。
 *
 * <p>语义与持久化实现保持一致，特别是租约抢占的原子性——这里靠同步块而不是 SQL 的条件更新。
 */
public final class InMemoryRunCheckpointRepository implements RunCheckpointRepository {
    private final Map<String, RunCheckpoint> checkpoints = new LinkedHashMap<>();

    @Override
    public synchronized void save(RunCheckpoint checkpoint) {
        checkpoints.put(checkpoint.runId(), checkpoint);
    }

    @Override
    public synchronized Optional<RunCheckpoint> find(String runId) {
        return Optional.ofNullable(checkpoints.get(runId));
    }

    @Override
    public synchronized Optional<RunCheckpoint> activeForSession(String sessionId) {
        return checkpoints.values().stream()
                .filter(checkpoint -> checkpoint.sessionId().equals(sessionId))
                .filter(RunCheckpoint::resumable)
                .max(java.util.Comparator.comparing(RunCheckpoint::updatedAt));
    }

    @Override
    public synchronized List<RunCheckpoint> resumable() {
        return checkpoints.values().stream()
                .filter(RunCheckpoint::resumable)
                .sorted(java.util.Comparator.comparing(RunCheckpoint::updatedAt))
                .toList();
    }

    @Override
    public synchronized boolean claimLease(String runId, String owner, Instant now, Duration lease) {
        RunCheckpoint checkpoint = checkpoints.get(runId);
        if (checkpoint == null) {
            return false;
        }
        Instant at = now == null ? Instant.now() : now;
        if (checkpoint.leasedByOther(owner, at)) {
            return false;
        }
        Duration span = lease == null ? Duration.ofSeconds(30) : lease;
        checkpoints.put(runId, new RunCheckpoint(checkpoint.runId(), checkpoint.sessionId(), checkpoint.phase(),
                checkpoint.step(), checkpoint.lastToolCallId(), checkpoint.lastSuccessfulStep(),
                checkpoint.nextAction(), checkpoint.workspace(), checkpoint.status(), owner,
                at.plus(span), checkpoint.heartbeatAt(), at));
        return true;
    }

    @Override
    public synchronized boolean renewLease(String runId, String owner, Instant now, Duration lease) {
        RunCheckpoint checkpoint = checkpoints.get(runId);
        if (checkpoint == null || checkpoint.owner() == null || !checkpoint.owner().equals(owner)) {
            return false;
        }
        Duration span = lease == null ? Duration.ofSeconds(30) : lease;
        Instant at = now == null ? Instant.now() : now;
        checkpoints.put(runId, checkpoint.heartbeat(owner, at, span));
        return true;
    }

    @Override
    public synchronized void releaseLease(String runId, String status, Instant now) {
        RunCheckpoint checkpoint = checkpoints.get(runId);
        if (checkpoint == null) {
            return;
        }
        checkpoints.put(runId, new RunCheckpoint(checkpoint.runId(), checkpoint.sessionId(),
                checkpoint.phase(), checkpoint.step(), checkpoint.lastToolCallId(),
                checkpoint.lastSuccessfulStep(), checkpoint.nextAction(), checkpoint.workspace(),
                status == null ? RunCheckpoint.STATUS_COMPLETED : status, null, null,
                checkpoint.heartbeatAt(), now == null ? Instant.now() : now));
    }

    @Override
    public synchronized int deleteBySession(String sessionId) {
        int before = checkpoints.size();
        checkpoints.values().removeIf(checkpoint -> checkpoint.sessionId().equals(sessionId));
        return before - checkpoints.size();
    }
}
