package com.pilot.server.controller;

import com.pilot.core.event.ArbitrationResolved;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.multi.SandboxStore;
import com.pilot.server.multi.VerificationGate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 多智能体面板接口：任务看板、沙箱改动、人工仲裁与变更接受/拒绝。
 *
 * <p>「接受变更」是唯一会写真实工作区的入口，且必须先经过校验与人工确认；
 * 拒绝只丢弃沙箱，不改动任何文件。
 */
@RestController
@RequestMapping("/api/sessions/{id}/multi")
public class MultiAgentController {
    private final SessionService sessions;
    private final SandboxStore sandboxes;
    private final AgentProperties properties;
    private final VerificationGate verification;

    public MultiAgentController(SessionService sessions, SandboxStore sandboxes, AgentProperties properties,
                                VerificationGate verification) {
        this.sessions = sessions;
        this.sandboxes = sandboxes;
        this.properties = properties;
        this.verification = verification;
    }

    public record MultiState(String sessionId, List<SandboxStore.TaskView> tasks,
                             List<SandboxStore.DiffView> diffs,
                             List<SandboxStore.Arbitration> arbitrations) {
    }

    public record ResolveRequest(String choice) {
    }

    @GetMapping
    public MultiState state(@PathVariable String id) {
        requireSession(id);
        return new MultiState(id, sandboxes.tasks(id), sandboxes.diffs(id), sandboxes.arbitrations(id));
    }

    /**
     * 接受变更：唯一会写真实工作区的入口。
     *
     * <p>服务端先重跑一次硬校验，不依赖界面或模型给的结论——绕过界面直接请求接口，
     * 未通过校验、未校验或仍有未解决仲裁的改动同样不会被写入。
     */
    @PostMapping("/sandboxes/{sandboxId}/accept")
    public SandboxStore.ApplyReport accept(@PathVariable String id, @PathVariable String sandboxId,
                                           @RequestBody(required = false) AcceptRequest request) {
        Session session = requireSession(id);
        boolean acknowledgeUnverified = request != null && Boolean.TRUE.equals(request.acknowledgeUnverified());
        com.pilot.core.multi.DiffSandbox sandbox = sandboxes.find(id, sandboxId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个沙箱: " + sandboxId));

        List<SandboxStore.Arbitration> pending = sandboxes.arbitrations(id);
        if (!pending.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "还有 " + pending.size() + " 项未解决的人工仲裁，先完成仲裁再接受变更："
                            + pending.stream().map(SandboxStore.Arbitration::id).collect(Collectors.joining("、")));
        }

        List<com.pilot.core.multi.SandboxChange> changes = sandbox.changes().stream()
                .filter(change -> change.status() == com.pilot.core.multi.ChangeStatus.PENDING).toList();
        if (!changes.isEmpty()) {
            VerificationGate.Report report = verification.verify(workspaceOf(session), sandbox.baseCommit(), changes);
            sandboxes.recordVerification(id, sandboxId, report);
            if (!report.ok() && !acknowledgeUnverified) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                        (VerificationGate.Report.FAILED.equals(report.status()) ? "硬校验未通过，改动未写入：" : "变更未经校验，改动未写入：")
                                + report.detail()
                                + (report.checks().isEmpty() ? "" : "（已执行：" + String.join("；", report.checks()) + "）")
                                + "。确认要接受时请带 acknowledgeUnverified=true。");
            }
        }
        SandboxStore.ApplyReport applied = sandboxes.accept(id, sandboxId, workspaceOf(session));
        if (applied.ok()) {
            sandboxes.markAccepted(id, sandboxId);
        }
        return applied;
    }

    /** @param acknowledgeUnverified 用户明确接受未通过或未执行校验的改动 */
    public record AcceptRequest(Boolean acknowledgeUnverified) {
    }

    @PostMapping("/sandboxes/{sandboxId}/reject")
    public ResponseEntity<Void> reject(@PathVariable String id, @PathVariable String sandboxId) {
        requireSession(id);
        if (!sandboxes.reject(id, sandboxId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个沙箱: " + sandboxId);
        }
        return ResponseEntity.noContent().build();
    }

    /** 记录人工仲裁结果；父 Agent 在下一轮会看到这条事件，不会自行猜测结论。 */
    @PostMapping("/arbitrations/{arbitrationId}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable String id, @PathVariable String arbitrationId,
                                        @RequestBody(required = false) ResolveRequest request) {
        requireSession(id);
        String choice = request == null || request.choice() == null ? "" : request.choice().strip();
        if (choice.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "choice must not be blank");
        }
        sessions.publish(id, "run_arbitration_" + UUID.randomUUID(),
                new ArbitrationResolved(arbitrationId, choice));
        return ResponseEntity.noContent().build();
    }

    private Session requireSession(String id) {
        return sessions.find(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + id));
    }

    private Path workspaceOf(Session session) {
        String workspace = session.workspace() == null || session.workspace().isBlank()
                ? properties.workspace()
                : session.workspace();
        return Path.of(workspace).toAbsolutePath().normalize();
    }
}
