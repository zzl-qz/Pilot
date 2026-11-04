package com.pilot.core.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次运行的可恢复调度状态。
 *
 * <p>事件流和消息历史只能回答「发生过什么」，回答不了「下一步该做什么」。
 * 服务重启后如果要安全接管，必须知道：当前进行到第几步、上一次成功的动作、
 * 下一动作是什么、谁持有这次运行的租约。这个记录就是那份最小可恢复状态。
 *
 * <p>不可变：每次推进产生一条新记录，由调用方整体覆盖保存。
 * {@code owner} + {@code leaseExpiresAt} 保证同一会话只会有一个实例在推进，
 * 租约过期后其他实例才能接管。
 *
 * @param phase             当前阶段：STARTING / MODEL / TOOL / WAITING_APPROVAL / FINISHED
 * @param step              已完成的模型轮数，从 0 开始
 * @param lastToolCallId    最近一次工具调用 id，用于幂等与诊断
 * @param lastSuccessfulStep 最近一次确认成功的动作描述，重启后据此说明「上次做到了哪」
 * @param nextAction        下一动作描述，接管后据此继续
 * @param status            RUNNING / INTERRUPTED / PAUSED / COMPLETED / FAILED
 */
public record RunCheckpoint(String runId, String sessionId, String phase, int step, String lastToolCallId,
    String lastSuccessfulStep, String nextAction, String workspace, String status, String owner, Instant leaseExpiresAt,
    Instant heartbeatAt, Instant updatedAt) {
    public static final String PHASE_STARTING = "STARTING";
    public static final String PHASE_MODEL = "MODEL";
    public static final String PHASE_TOOL = "TOOL";
    public static final String PHASE_WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String PHASE_FINISHED = "FINISHED";

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    public RunCheckpoint {
        runId = requireText(runId, "runId");
        sessionId = requireText(sessionId, "sessionId");
        phase = phase == null || phase.isBlank() ? PHASE_STARTING : phase.strip();
        if (step < 0) {
            throw new IllegalArgumentException("step must be >= 0");
        }
        lastToolCallId = blankToNull(lastToolCallId);
        lastSuccessfulStep = blankToNull(lastSuccessfulStep);
        nextAction = blankToNull(nextAction);
        workspace = blankToNull(workspace);
        status = status == null || status.isBlank() ? STATUS_RUNNING : status.strip();
        owner = blankToNull(owner);
        heartbeatAt = heartbeatAt == null ? Instant.EPOCH : heartbeatAt;
        updatedAt = updatedAt == null ? heartbeatAt : updatedAt;
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /** 新运行的初始检查点，租约归当前实例所有。 */
    public static RunCheckpoint start(String runId, String sessionId, String workspace, String owner, Instant now,
        Duration lease) {
        Instant at = now == null ? Instant.now() : now;
        return new RunCheckpoint(runId, sessionId, PHASE_STARTING, 0, null, null, "读取任务要求并开始下一步", workspace,
            STATUS_RUNNING, owner, at.plus(lease == null ? Duration.ofSeconds(30) : lease), at, at);
    }

    /** 租约有效 = 有人持有 + 尚未过期。释放后 owner 为空，即使时间未到也不算活跃。 */
    public boolean leaseActive(Instant now) {
        return owner != null && leaseExpiresAt != null && now != null && now.isBefore(leaseExpiresAt);
    }

    /** 另一个实例持有一个尚未过期的租约。 */
    public boolean leasedByOther(String self, Instant now) {
        return owner != null && !owner.equals(self) && leaseActive(now);
    }

    public boolean resumable() {
        return STATUS_RUNNING.equals(status) || STATUS_INTERRUPTED.equals(status) || STATUS_PAUSED.equals(status);
    }

    public RunCheckpoint heartbeat(String newOwner, Instant now, Duration lease) {
        Instant at = now == null ? Instant.now() : now;
        Duration span = lease == null ? Duration.ofSeconds(30) : lease;
        return new RunCheckpoint(runId, sessionId, phase, step, lastToolCallId, lastSuccessfulStep, nextAction,
            workspace, status, newOwner == null ? owner : newOwner, at.plus(span), at, at);
    }

    public RunCheckpoint status(String newStatus, Instant now) {
        Instant at = now == null ? Instant.now() : now;
        return new RunCheckpoint(runId, sessionId, phase, step, lastToolCallId, lastSuccessfulStep, nextAction,
            workspace, newStatus, owner, leaseExpiresAt, heartbeatAt, at);
    }

    public RunCheckpoint phase(String newPhase, Instant now) {
        Instant at = now == null ? Instant.now() : now;
        return new RunCheckpoint(runId, sessionId, newPhase, step, lastToolCallId, lastSuccessfulStep, nextAction,
            workspace, status, owner, leaseExpiresAt, heartbeatAt, at);
    }

    /** 记录一次已完成的动作，并给出下一动作；{@code step} 只增不减。 */
    public RunCheckpoint advance(int newStep, String toolCallId, String successfulStep, String next, String newPhase,
        Instant now) {
        Instant at = now == null ? Instant.now() : now;
        return new RunCheckpoint(runId, sessionId, newPhase == null ? phase : newPhase, Math.max(step, newStep),
            toolCallId, successfulStep == null ? lastSuccessfulStep : successfulStep, next == null ? nextAction : next,
            workspace, status, owner, leaseExpiresAt, heartbeatAt, at);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
