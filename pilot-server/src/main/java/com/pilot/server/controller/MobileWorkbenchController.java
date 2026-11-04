package com.pilot.server.controller;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.approval.PendingApproval;
import com.pilot.core.event.ApprovalRequired;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.session.SessionRepository;
import com.pilot.server.results.ResultInboxService;

/** 应用级移动快照；无须打开某条任务即可发现其他任务的审批和结果。 */
@RestController
@RequestMapping("/api/mobile/workbench")
public class MobileWorkbenchController {
    private final SessionController sessions;
    private final ProjectController projects;
    private final ApprovalBroker approvals;
    private final SessionRepository repository;
    private final ResultInboxService results;

    public MobileWorkbenchController(SessionController sessions, ProjectController projects, ApprovalBroker approvals,
        SessionRepository repository, ResultInboxService results) {
        this.sessions = sessions;
        this.projects = projects;
        this.approvals = approvals;
        this.repository = repository;
        this.results = results;
    }

    @GetMapping
    public Map<String, Object> snapshot() {
        return Map.of("version", 1, "sessions", sessions.list(null), "projects", projects.list(), "approvals",
            pending(), "results", results.list(), "capabilities", Map.of("applicationScope", true, "idempotency", true,
                "attachmentsMaxBytes", 6 * 1024 * 1024, "backgroundPush", false));
    }

    /** 未找到归属时禁止客户端猜测当前任务；事件发布与审批注册的短暂间隔会在下一轮刷新补全。 */
    private List<MobileApproval> pending() {
        List<PendingApproval> pending = approvals.pending();
        Instant since = pending.stream().map(PendingApproval::requestedAt).min(Instant::compareTo).orElse(null);
        if (since == null) {
            return List.of();
        }
        Map<String, EventEnvelope> owners = new HashMap<>();
        repository.eventsOfType("approval.required", since.minusSeconds(60)).forEach(event -> {
            if (event.payload() instanceof ApprovalRequired approval) {
                owners.put(approval.callId(), event);
            }
        });
        return pending.stream().map(approval -> {
            EventEnvelope owner = owners.get(approval.callId());
            String sessionId = owner == null ? "" : owner.sessionId();
            String title = repository.find(sessionId).map(session -> session.title()).orElse("正在确认任务归属");
            return new MobileApproval(approval.callId(), approval.toolName(), approval.argsSummary(),
                approval.requestedAt(), sessionId, owner == null ? "" : owner.runId(), title);
        }).toList();
    }

    public record MobileApproval(String callId, String toolName, String argsSummary, Instant requestedAt,
        String sessionId, String runId, String title) {
    }
}
