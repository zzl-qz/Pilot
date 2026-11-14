package com.pilot.server.mobile;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.approval.PendingApproval;
import com.pilot.core.event.ApprovalRequired;
import com.pilot.core.event.EventEnvelope;
import com.pilot.core.session.SessionRepository;
import com.pilot.core.util.Sha256;
import com.pilot.server.attachment.AttachmentLimits;
import com.pilot.server.results.ResultInboxService;
import com.pilot.server.session.ProjectController;
import com.pilot.server.session.SessionController;

import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/** 应用级移动快照；无须打开某条任务即可发现其他任务的审批和结果。 */
@RestController
@RequestMapping("/api/mobile/workbench")
@RequiredArgsConstructor
public class MobileWorkbenchController {
    private final SessionController sessions;
    private final ProjectController projects;
    private final ApprovalBroker approvals;
    private final SessionRepository repository;
    private final ResultInboxService results;
    private final ObjectMapper mapper;
    private final AttachmentLimits limits;

    @GetMapping
    public Map<String, Object> snapshot(@RequestParam(required = false) String revision) {
        var page = results.list();
        var summaries = page.items().stream().map(item -> new MobileResult(item.id(), item.revision(), item.sessionId(),
            item.title(), item.state(), abbreviate(item.summary()), item.at(), item.read(), item.handled())).toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("version", 1);
        snapshot.put("sessions", sessions.list(null));
        snapshot.put("projects", projects.list());
        snapshot.put("approvals", pending());
        snapshot.put("results", new MobileResultPage(summaries, page.truncated()));
        AttachmentLimits mobile = limits.mobile();
        snapshot.put("capabilities",
            Map.of("applicationScope", true, "idempotency", true, "attachmentsMaxBytes", (long)mobile.maxTotalBytes(),
                "attachmentsMaxFileBytes", (long)mobile.maxFileBytes(), "attachmentsMaxFiles", mobile.maxFiles(),
                "attachmentsMaxRequestBytes", mobile.maxRequestBytes(), "backgroundPush", false, "conditionalSnapshot",
                true));
        String current = revision(snapshot);
        if (current.equals(revision)) {
            return Map.of("version", 1, "revision", current, "unchanged", true);
        }
        snapshot.put("revision", current);
        return snapshot;
    }

    private String revision(Map<String, Object> snapshot) {
        return Sha256.hex(mapper.writeValueAsString(snapshot));
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.codePointCount(0, text.length()) <= 240 ? text
            : text.substring(0, text.offsetByCodePoints(0, 240)) + "…";
    }

    /** 首屏只传列表用到的摘要，完整结果由已有详情接口按需读取。 */
    public record MobileResult(String id, String revision, String sessionId, String title, String state, String summary,
        Instant at, boolean read, boolean handled) {
    }

    public record MobileResultPage(List<MobileResult> items, boolean truncated) {
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
