package com.pilot.server.coordination;

import java.util.Map;
import java.util.Optional;

import com.pilot.core.coordination.WorkspaceEventDraft;
import com.pilot.core.coordination.WorkspaceEventPublisher;
import com.pilot.core.coordination.WorkspaceEventType;
import com.pilot.core.event.ArbitrationRequired;
import com.pilot.core.event.ArbitrationResolved;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunEnded;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.SandboxDiffReady;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionActivityListener;
import com.pilot.core.session.SessionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 把会话生命周期与会话内事件翻译成工作区协调事件。
 *
 * <p>只做映射：会话状态仍由 {@link SessionRepository} 与会话服务决定，这里既不读取文件内容，
 * 也不把事件写回会话历史，因此不会与会话事件流互相影响。发布失败只记日志，不影响产生事件的业务流程。
 */
@Slf4j
@RequiredArgsConstructor
public class CoordinationEventRelay implements SessionActivityListener {

    private final WorkspaceEventPublisher publisher;
    private final SessionRepository sessions;

    @Override
    public void started(Session session) {
        emit(WorkspaceEventType.SESSION_STARTED, session, "会话已打开");
    }

    @Override
    public void stopped(Session session) {
        emit(WorkspaceEventType.SESSION_STOPPED, session, "会话已归档");
    }

    /**
     * 会话内事件的工作区投影。只有运行、沙箱与仲裁这几类对其他会话有意义，其余直接忽略。
     *
     * @param runId 产生事件的那一轮运行；终止事件会把它带进事件摘要，便于跨会话对齐
     */
    public void sessionEvent(String sessionId, String runId, SessionEvent event) {
        WorkspaceEventType type = switch (event) {
            case RunStarted started -> WorkspaceEventType.RUN_STARTED;
            case RunCompleted completed -> WorkspaceEventType.RUN_FINISHED;
            case RunFailed failed -> WorkspaceEventType.RUN_FINISHED;
            case RunCancelled cancelled -> WorkspaceEventType.RUN_FINISHED;
            case RunEnded ended -> WorkspaceEventType.RUN_FINISHED;
            case SandboxDiffReady diff -> WorkspaceEventType.SANDBOX_CHANGED;
            case ArbitrationRequired required -> WorkspaceEventType.ARBITRATION_CREATED;
            case ArbitrationResolved resolved -> WorkspaceEventType.ARBITRATION_RESOLVED;
            default -> null;
        };
        if (type == null) {
            return;
        }
        Optional<Session> session = sessions.find(sessionId);
        if (session.isEmpty()) {
            return;
        }
        emit(type, session.get(), subjectOf(event),
            detailsOf(event).isEmpty() ? Map.of("runId", runId) : withRunId(detailsOf(event), runId));
    }

    private void emit(WorkspaceEventType type, Session session, String subject) {
        emit(type, session, subject, Map.of());
    }

    private void emit(WorkspaceEventType type, Session session, String subject, Map<String, String> details) {
        try {
            publisher.publish(new WorkspaceEventDraft(type, session.projectId(), session.id(), session.workspace(),
                subject, details));
        } catch (RuntimeException failure) {
            log.warn("发布工作区事件失败：{} session={}", type.wireName(), session.id(), failure);
        }
    }

    private static Map<String, String> withRunId(Map<String, String> details, String runId) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(details);
        if (runId != null) {
            merged.put("runId", runId);
        }
        return merged;
    }

    /** 给界面看的一句话摘要；只带标识与状态，不带正文。 */
    private static String subjectOf(SessionEvent event) {
        return switch (event) {
            case RunStarted ignored -> "开始新一轮运行";
            case RunCompleted ignored -> "运行已完成";
            case RunFailed failed -> "运行失败：" + failed.code();
            case RunCancelled ignored -> "运行已停止";
            case RunEnded ended -> "运行结束：" + ended.code();
            case SandboxDiffReady diff -> "沙箱改动：" + diff.path();
            case ArbitrationRequired required -> "仲裁待决定：" + required.kind();
            case ArbitrationResolved resolved -> "仲裁已决定：" + resolved.choice();
            default -> event.type().wireName();
        };
    }

    private static Map<String, String> detailsOf(SessionEvent event) {
        return switch (event) {
            case RunFailed failed -> Map.of("code", failed.code());
            case RunEnded ended -> Map.of("code", ended.code());
            case RunCancelled cancelled -> Map.of("reason", cancelled.reason());
            case RunCompleted completed -> Map.of("changedFiles", Integer.toString(completed.changedFiles().size()));
            case SandboxDiffReady diff -> Map.of("sandboxId", diff.sandboxId(), "path", diff.path());
            case ArbitrationRequired required ->
                Map.of("arbitrationId", required.arbitrationId(), "kind", required.kind());
            case ArbitrationResolved resolved ->
                Map.of("arbitrationId", resolved.arbitrationId(), "choice", resolved.choice());
            default -> Map.of();
        };
    }
}
