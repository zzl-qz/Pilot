package com.pilot.server.multi;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.event.ArbitrationResolved;
import com.pilot.core.multi.ArtifactDiff;
import com.pilot.core.multi.ArtifactManifest;
import com.pilot.core.multi.ChangeStatus;
import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.SandboxChange;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.common.web.SessionLookup;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.multi.WorkspaceApplyJournal.State;

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
    /** 派发记录出口；未配置持久化存储时为空，接口返回空列表。 */
    private final JdbcDispatchStore dispatchStore;

    public MultiAgentController(SessionService sessions, SandboxStore sandboxes, AgentProperties properties,
        VerificationGate verification) {
        this(sessions, sandboxes, properties, verification, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MultiAgentController(SessionService sessions, SandboxStore sandboxes, AgentProperties properties,
        VerificationGate verification, JdbcDispatchStore dispatchStore) {
        this.sessions = sessions;
        this.sandboxes = sandboxes;
        this.properties = properties;
        this.verification = verification;
        this.dispatchStore = dispatchStore;
    }

    /** 一次派发的持久化视图：图、节点状态与每次尝试，重启后仍然可查。 */
    public record DispatchState(String dispatchId, String status, String createdAt,
        List<JdbcDispatchStore.NodeRow> nodes, List<JdbcDispatchStore.AttemptRow> attempts) {
    }

    public record MultiState(String sessionId, List<SandboxStore.TaskView> tasks, List<SandboxStore.DiffView> diffs,
        List<SandboxStore.Arbitration> arbitrations, List<SandboxStore.ArtifactView> manifests,
        Map<String, SandboxStore.VerificationState> verifications) {
    }

    public record ResolveRequest(String choice) {
    }

    @GetMapping
    public MultiState state(@PathVariable String id) {
        requireSession(id);
        List<SandboxStore.ArtifactView> manifests = sandboxes.sandboxes(id).stream()
            .flatMap(sandbox -> sandboxes.manifestHistory(id, sandbox.id()).stream()).toList();
        Map<String, SandboxStore.VerificationState> verifications = new LinkedHashMap<>();
        for (DiffSandbox sandbox : sandboxes.sandboxes(id)) {
            verifications.put(sandbox.id(), sandboxes.verificationState(id, sandbox.id()));
        }
        return new MultiState(id, sandboxes.tasks(id), sandboxes.diffs(id), sandboxes.arbitrations(id), manifests,
            verifications);
    }

    /** 持久化的派发记录：界面与排查都用同一份状态，不只读内存看板。 */
    @GetMapping("/dispatches")
    public List<DispatchState> dispatches(@PathVariable String id) {
        requireSession(id);
        if (dispatchStore == null) {
            return List.of();
        }
        return dispatchStore.dispatches(id).stream().map(row -> new DispatchState(row.dispatchId(), row.status(),
            row.createdAt(), dispatchStore.nodes(row.dispatchId()), dispatchStore.attempts(row.dispatchId()))).toList();
    }

    /** 某个沙箱的产物清单历史：每一版都绑定当时的候选指纹、依赖与校验结论。 */
    @GetMapping("/sandboxes/{sandboxId}/manifests")
    public List<SandboxStore.ArtifactView> manifests(@PathVariable String id, @PathVariable String sandboxId) {
        requireSession(id);
        if (sandboxes.find(id, sandboxId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个沙箱: " + sandboxId);
        }
        return sandboxes.manifestHistory(id, sandboxId);
    }

    /** 最近两版的差异，以及当前校验状态为什么有效/失效。 */
    @GetMapping("/sandboxes/{sandboxId}/manifest")
    public SandboxManifestReport manifest(@PathVariable String id, @PathVariable String sandboxId) {
        Session session = requireSession(id);
        if (sandboxes.find(id, sandboxId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个沙箱: " + sandboxId);
        }
        var recovery = sandboxes.applyState(workspaceOf(session));
        return sandboxes.withCandidate(id, sandboxId, () -> {
            var current = sandboxes.manifest(id, sandboxId, SandboxStore.environmentFingerprint());
            var candidate = sandboxes.candidate(id, sandboxId);
            String disposition = disposition(candidate.changes());
            return new SandboxManifestReport(current, sandboxes.manifestDiff(id, sandboxId),
                sandboxes.verificationState(id, sandboxId), sandboxes.dependenciesOf(id, sandboxId),
                candidate.reviewHash(), disposition, recovery);
        });
    }

    private static String disposition(List<SandboxChange> changes) {
        if (changes.isEmpty()) {
            return "empty";
        }
        if (changes.stream().anyMatch(change -> change.status() == ChangeStatus.PENDING)) {
            return "pending";
        }
        if (changes.stream().allMatch(change -> change.status() == ChangeStatus.ACCEPTED)) {
            return "accepted";
        }
        if (changes.stream().allMatch(change -> change.status() == ChangeStatus.REJECTED)) {
            return "rejected";
        }
        return "closed";
    }

    public record SandboxManifestReport(ArtifactManifest manifest, ArtifactDiff diff,
        SandboxStore.VerificationState verification, List<String> dependencies, String reviewHash, String disposition,
        State recovery) {
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
        try {
            Path workspace = workspaceOf(session);
            return sandboxes.withWorkspaceLock(workspace, () -> acceptCandidate(id, sandboxId, workspace, request));
        } catch (IllegalStateException | UncheckedIOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    /** 由工作区锁保护：恢复、审阅版本校验、硬校验和应用按顺序执行。 */
    private SandboxStore.ApplyReport acceptCandidate(String id, String sandboxId, Path workspace,
        AcceptRequest request) {
        sandboxes.recoverApply(id, sandboxId, workspace);
        if (sandboxes.find(id, sandboxId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个沙箱");
        }
        var candidate = sandboxes.candidate(id, sandboxId);
        if (request != null && request.candidateHash() != null) {
            sandboxes.assertCandidate(id, sandboxId, request.candidateHash());
        }
        requireNoArbitration(id);
        verifyCandidate(id, sandboxId, workspace, candidate, request);
        requireNoArbitration(id);
        return sandboxes.accept(id, sandboxId, workspace, candidate.reviewHash());
    }

    private void verifyCandidate(String id, String sandboxId, Path workspace, SandboxStore.Candidate candidate,
        AcceptRequest request) {
        var changes = candidate.changes().stream().filter(change -> change.status() == ChangeStatus.PENDING).toList();
        if (!changes.isEmpty()) {
            VerificationGate.Report report = verification.verify(workspace, candidate.baseCommit(), changes);
            sandboxes.recordCandidateVerification(id, sandboxId, candidate.reviewHash(), report);
            if (!report.ok() && (request == null || !Boolean.TRUE.equals(request.acknowledgeUnverified()))) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    (VerificationGate.Report.FAILED.equals(report.status()) ? "硬校验未通过，改动未写入：" : "变更未经校验，改动未写入：")
                        + report.detail() + "。显式确认后可接受此候选版本。");
            }
        }
    }

    private void requireNoArbitration(String id) {
        var pending = sandboxes.arbitrations(id);
        if (!pending.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "先处理待决仲裁：" + pending.stream().map(SandboxStore.Arbitration::id).collect(Collectors.joining("、")));
        }
    }

    @PostMapping("/sandboxes/{sandboxId}/recover")
    public ResponseEntity<Void> recover(@PathVariable String id, @PathVariable String sandboxId) {
        Session session = requireSession(id);
        try {
            sandboxes.recoverApply(id, sandboxId, workspaceOf(session));
        } catch (IllegalStateException | UncheckedIOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
        return ResponseEntity.noContent().build();
    }

    /** @param acknowledgeUnverified 用户明确接受未通过或未执行校验的改动 */
    public record AcceptRequest(Boolean acknowledgeUnverified, String candidateHash) {
        public AcceptRequest(Boolean acknowledgeUnverified) {
            this(acknowledgeUnverified, null);
        }
    }

    @PostMapping("/sandboxes/{sandboxId}/reject")
    public ResponseEntity<Void> reject(@PathVariable String id, @PathVariable String sandboxId) {
        Session session = requireSession(id);
        try {
            return sandboxes.withWorkspaceLock(workspaceOf(session), () -> {
                sandboxes.recoverApply(id, sandboxId, workspaceOf(session));
                if (!sandboxes.reject(id, sandboxId)) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个沙箱: " + sandboxId);
                }
                return ResponseEntity.noContent().build();
            });
        } catch (IllegalStateException | UncheckedIOException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
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
        try {
            if (sandboxes.resolve(id, arbitrationId, choice)) {
                sessions.publish(id, "run_arbitration_" + UUID.randomUUID(),
                    new ArbitrationResolved(arbitrationId, choice));
            }
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    private Session requireSession(String id) {
        return SessionLookup.require(sessions, id);
    }

    private Path workspaceOf(Session session) {
        String workspace =
            session.workspace() == null || session.workspace().isBlank() ? properties.workspace() : session.workspace();
        return Path.of(workspace).toAbsolutePath().normalize();
    }
}
