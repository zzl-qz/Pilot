package com.pilot.server.session;

import com.pilot.core.event.ApprovalRequired;
import com.pilot.core.event.ApprovalResolved;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunRetrying;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.session.RunCheckpoint;
import com.pilot.core.session.RunCheckpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 把运行推进过程写成可恢复的调度状态，并用租约防止重复接管。
 *
 * <p>事件流回答「发生过什么」，这里回答「现在在哪、下一步做什么、谁有权继续」。
 * 每次工具开始/结束、等待审批、重试都会更新检查点；运行结束时释放租约但保留记录，
 * 便于诊断「上次成功步骤」。
 *
 * <p>租约每 {@value #LEASE_SECONDS} 秒续一次。续租失败说明另一个实例已经接管或数据库不可写，
 * 此时停止在本实例上继续把状态当成权威，避免两边同时写同一会话。
 */
@Component
public class RunStateTracker {
    private static final Logger log = LoggerFactory.getLogger(RunStateTracker.class);
    public static final long LEASE_SECONDS = 30L;
    private static final Duration LEASE = Duration.ofSeconds(LEASE_SECONDS);

    /** 启动恢复时展示给用户的一条接管信息。 */
    public record RecoveryEntry(String runId, String sessionId, String step, String lastSuccessfulStep,
                                String nextAction, String workspace, String leaseState, Instant updatedAt) {
    }

    private final RunCheckpointRepository repository;
    private final String instanceId;
    private final ConcurrentMap<String, String> activeRuns = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> lostLeases = new ConcurrentHashMap<>();

    public RunStateTracker(RunCheckpointRepository repository,
                           @Value("${pilot.instance.id:}") String configuredInstanceId) {
        this.repository = repository;
        this.instanceId = configuredInstanceId == null || configuredInstanceId.isBlank()
                ? "pilot-" + UUID.randomUUID().toString().substring(0, 8)
                : configuredInstanceId.strip();
    }

    public String instanceId() {
        return instanceId;
    }

    /** 新运行开始：写入起点并抢占租约。 */
    public void begin(String sessionId, String runId, String workspace) {
        Instant now = Instant.now();
        try {
            repository.save(RunCheckpoint.start(runId, sessionId, workspace, instanceId, now, LEASE));
            if (!repository.claimLease(runId, instanceId, now, LEASE)) {
                log.warn("运行 {} 的租约被其他实例持有，本实例不会把它当成独占运行", runId);
            }
            activeRuns.put(runId, sessionId);
        } catch (RuntimeException e) {
            // 检查点写不进去不能拦住任务本身，但必须留下痕迹，诊断信息会显示存储降级
            log.warn("写入运行检查点失败：session={}, run={}", sessionId, runId, e);
        }
    }

    /** 根据事件推进检查点；未知事件忽略。 */
    public void onEvent(String sessionId, String runId, SessionEvent event) {
        if (event == null || sessionId == null || runId == null || !affectsCheckpoint(event)) {
            // 文本增量占事件的绝大多数，且不改变调度状态：连读库都省掉
            return;
        }
        try {
            RunCheckpoint current = repository.find(runId)
                    .orElseGet(() -> RunCheckpoint.start(runId, sessionId, null, instanceId,
                            Instant.now(), LEASE));
            Instant now = Instant.now();
            RunCheckpoint updated = switch (event) {
                case RunStarted ignored -> current
                        .advance(current.step(), current.lastToolCallId(), "收到任务输入",
                                "请求模型决定下一步", RunCheckpoint.PHASE_MODEL, now);
                case ToolStarted started -> current
                        .advance(current.step(), started.callId(), current.lastSuccessfulStep(),
                                "等待工具 " + started.name() + " 返回", RunCheckpoint.PHASE_TOOL, now);
                case ToolCompleted completed -> current
                        .advance(current.step() + 1, completed.callId(),
                                "工具调用成功：" + clip(completed.resultSummary(), 120),
                                "根据工具结果决定下一步", RunCheckpoint.PHASE_MODEL, now);
                case ApprovalRequired approval -> current
                        .advance(current.step(), current.lastToolCallId(), current.lastSuccessfulStep(),
                                "等待用户批准 " + approval.name(), RunCheckpoint.PHASE_WAITING_APPROVAL, now);
                case ApprovalResolved ignored -> current
                        .advance(current.step(), current.lastToolCallId(), current.lastSuccessfulStep(),
                                "继续执行已批准的工具", RunCheckpoint.PHASE_TOOL, now);
                case RunRetrying retrying -> current
                        .advance(current.step(), current.lastToolCallId(), current.lastSuccessfulStep(),
                                "等待模型恢复后重试（第 " + retrying.attempt() + " 次）",
                                RunCheckpoint.PHASE_MODEL, now);
                case RunCompleted ignored -> current
                        .advance(current.step(), current.lastToolCallId(), "本轮已完成",
                                "等待用户验收或下一轮输入", RunCheckpoint.PHASE_FINISHED, now)
                        .status(RunCheckpoint.STATUS_COMPLETED, now);
                case RunCancelled ignored -> current
                        .advance(current.step(), current.lastToolCallId(), "运行已被取消，进度与对话已保存",
                                "确认后可继续执行", RunCheckpoint.PHASE_FINISHED, now)
                        .status(RunCheckpoint.STATUS_INTERRUPTED, now);
                case RunFailed failed -> current
                        .advance(current.step(), current.lastToolCallId(),
                                current.lastSuccessfulStep() == null ? "本轮未成功完成任何步骤"
                                        : current.lastSuccessfulStep(),
                                failed.retryable() ? "确认原因后可继续执行" : "按失败原因处理后重新开始",
                                RunCheckpoint.PHASE_FINISHED, now)
                        .status(failed.retryable() ? RunCheckpoint.STATUS_INTERRUPTED
                                : RunCheckpoint.STATUS_FAILED, now);
                // 文本增量等事件不影响调度状态：不写库，否则每个 token 都会产生一次落盘
                default -> null;
            };
            if (updated != null && !updated.equals(current)) {
                repository.save(updated);
            }
        } catch (RuntimeException e) {
            log.debug("更新运行检查点失败：session={}, run={}", sessionId, runId, e);
        }
    }

    /** 运行结束时落终态并释放租约；记录保留用于诊断。 */
    public void finish(String sessionId, String runId, String status) {
        activeRuns.remove(runId);
        try {
            repository.releaseLease(runId, terminalStatus(status), Instant.now());
        } catch (RuntimeException e) {
            log.warn("释放运行租约失败：session={}, run={}", sessionId, runId, e);
        }
    }

    public Optional<RunCheckpoint> current(String sessionId) {
        try {
            return repository.activeForSession(sessionId);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 删除会话时清掉其检查点。 */
    public int forget(String sessionId) {
        return repository.deleteBySession(sessionId);
    }

    public Optional<RunCheckpoint> checkpoint(String runId) {
        try {
            return repository.find(runId);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public List<RunCheckpoint> resumable() {
        try {
            return repository.resumable();
        } catch (RuntimeException e) {
            log.warn("读取可恢复检查点失败", e);
            return List.of();
        }
    }

    /** 本实例是否已经失去该运行的租约；失去后不应继续把状态当成权威。 */
    public boolean leaseLost(String runId) {
        return lostLeases.containsKey(runId);
    }

    public String leaseState(String runId) {
        return lostLeases.get(runId);
    }

    /**
     * 启动恢复：把上次遗留的运行标记为中断并释放租约，返回可接管的运行清单。
     *
     * <p>不删除检查点：用户需要看到「上次做到哪一步、下一步本来要做什么」。
     */
    public List<RecoveryEntry> recoverAfterRestart() {
        Instant now = Instant.now();
        List<RecoveryEntry> entries = new ArrayList<>();
        for (RunCheckpoint checkpoint : resumable()) {
            String leaseState;
            try {
                boolean leasedByOther = checkpoint.leasedByOther(instanceId, now);
                repository.releaseLease(checkpoint.runId(), RunCheckpoint.STATUS_INTERRUPTED, now);
                leaseState = leasedByOther
                        ? "上一个租约属于其他实例（" + checkpoint.owner() + "），已收敛为中断"
                        : "已释放本机遗留租约";
            } catch (RuntimeException e) {
                leaseState = "租约收敛失败：" + e.getMessage();
            }
            entries.add(new RecoveryEntry(checkpoint.runId(), checkpoint.sessionId(),
                    "第 " + checkpoint.step() + " 步", checkpoint.lastSuccessfulStep(),
                    checkpoint.nextAction(), checkpoint.workspace(), leaseState, checkpoint.updatedAt()));
            log.warn("运行 {}（会话 {}）上次未正常结束：{}，下一步：{}", checkpoint.runId(),
                    checkpoint.sessionId(), checkpoint.lastSuccessfulStep(), checkpoint.nextAction());
        }
        return List.copyOf(entries);
    }

    /** 定期续租；失去租约时记录并停止把它当成独占运行。 */
    @Scheduled(fixedDelay = 10_000L, initialDelay = 15_000L)
    public void heartbeat() {
        if (activeRuns.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (var entry : activeRuns.entrySet()) {
            String runId = entry.getKey();
            try {
                if (!repository.renewLease(runId, instanceId, now, LEASE)) {
                    lostLeases.put(runId, "租约已被其他实例接管或已过期");
                    log.error("运行 {} 的租约已丢失，停止把本实例状态当成权威", runId);
                }
            } catch (RuntimeException e) {
                lostLeases.put(runId, "续租失败：" + e.getMessage());
                log.warn("运行 {} 续租失败", runId, e);
            }
        }
    }

    private static boolean affectsCheckpoint(SessionEvent event) {
        return event instanceof RunStarted || event instanceof ToolStarted
                || event instanceof ToolCompleted || event instanceof ApprovalRequired
                || event instanceof ApprovalResolved || event instanceof RunRetrying
                || event instanceof RunCompleted || event instanceof RunCancelled
                || event instanceof RunFailed;
    }

    private static String terminalStatus(String status) {
        return switch (status == null ? "" : status) {
            case "COMPLETED" -> RunCheckpoint.STATUS_COMPLETED;
            case "PAUSED" -> RunCheckpoint.STATUS_PAUSED;
            case "INTERRUPTED", "CANCELLED", "MAX_STEPS" -> RunCheckpoint.STATUS_INTERRUPTED;
            default -> RunCheckpoint.STATUS_FAILED;
        };
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.replaceAll("\\s+", " ").strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
