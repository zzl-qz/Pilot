package com.pilot.server.controller;

import com.pilot.core.event.ArbitrationResolved;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.multi.SandboxStore;
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

    public MultiAgentController(SessionService sessions, SandboxStore sandboxes, AgentProperties properties) {
        this.sessions = sessions;
        this.sandboxes = sandboxes;
        this.properties = properties;
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

    @PostMapping("/sandboxes/{sandboxId}/accept")
    public SandboxStore.ApplyReport accept(@PathVariable String id, @PathVariable String sandboxId) {
        Session session = requireSession(id);
        SandboxStore.ApplyReport report = sandboxes.accept(id, sandboxId, workspaceOf(session));
        if (report.ok()) {
            sandboxes.markAccepted(id, sandboxId);
        }
        return report;
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
