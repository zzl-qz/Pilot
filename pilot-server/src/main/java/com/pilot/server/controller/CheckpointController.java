package com.pilot.server.controller;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunStarted;
import com.pilot.core.session.ConversationRepository;
import com.pilot.core.session.PlanRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.session.RunStarter;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.workspace.RunSnapshotService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;

/** 按用户消息回到发送前；分支保留原对话，使用当前工作区文件。 */
@RestController
@RequestMapping("/api/sessions/{id}/checkpoints")
public class CheckpointController {
    private final SessionService sessions;
    private final RunStarter runs;
    private final RunSnapshotService snapshots;
    private final ConversationRepository conversations;
    private final PlanRepository plans;
    private final AgentProperties properties;
    private final com.pilot.server.session.TaskControlService tasks;

    public CheckpointController(SessionService sessions, RunStarter runs, RunSnapshotService snapshots,
                                ConversationRepository conversations, PlanRepository plans, AgentProperties properties, com.pilot.server.session.TaskControlService tasks) {
        this.sessions = sessions;
        this.runs = runs;
        this.snapshots = snapshots;
        this.conversations = conversations;
        this.plans = plans;
        this.properties = properties;
        this.tasks = tasks;
    }

    @GetMapping
    public Preview preview(@PathVariable String id, @RequestParam long sequence) {
        Selection selection = select(id, sequence);
        return new Preview(sequence, selection.events().getLast().sequence(), selection.input(), selection.runIds().size(),
                snapshots.conversation(selection.event().runId()) != null, codePreview(selection));
    }

    @PostMapping("/restore")
    public RestoreResponse restore(@PathVariable String id, @RequestBody Request request) {
        if (request == null || request.sequence() == null || request.mode() == null || !List.of("code", "conversation", "both").contains(request.mode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择只撤代码、只撤对话或一起撤");
        }
        String slot = claim(id);
        try {
            Selection selection = select(id, request.sequence());
            checkRevision(selection, request.revision());
            boolean withConversation = !"code".equals(request.mode());
            var checkpoint = withConversation ? snapshots.conversation(selection.event().runId()) : null;
            if (withConversation && checkpoint == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "对话检查点不存在或已过期，无法恢复模型记忆");
            }
            if (withConversation && !runs.queued(id).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请先处理等待发送的消息，再回退对话");
            }
            RunSnapshotService.RollbackReport report = null;
            if (!"conversation".equals(request.mode())) {
                requireIdleWorkspace(selection.session(), slot);
                if (!runs.lockWorkspace(slot, Path.of(workspace(selection.session())))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "同一工作区有任务正在运行或回退，请稍后重试");
                }
                var preview = codePreview(selection);
                if (!preview.ready()) {
                    return new RestoreResponse(false, selection.input(), new RunSnapshotService.RollbackReport(
                            selection.event().runId(), workspace(selection.session()), List.of(), List.of(),
                            preview.unavailable(), preview.conflicts()));
                }
                report = snapshots.rollback(selection.runIds());
                if (!report.conflicts().isEmpty() || !report.failed().isEmpty()) {
                    return new RestoreResponse(false, selection.input(), report);
                }
            }
            if (withConversation) {
                tasks.restoreTo(id, selection.event().runId());
                runs.discardSteering(id);
                conversations.save(id, checkpoint.messages());
                plans.save(id, checkpoint.plan());
                sessions.dropEventsFrom(id, request.sequence());
            }
            return new RestoreResponse(true, selection.input(), report);
        } finally {
            runs.abandon(slot);
        }
    }

    @PostMapping("/fork")
    public ForkResponse fork(@PathVariable String id, @RequestBody Request request) {
        if (request == null || request.sequence() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sequence must not be null");
        }
        String slot = claim(id);
        try {
            Selection selection = select(id, request.sequence());
            checkRevision(selection, request.revision());
            var checkpoint = snapshots.conversation(selection.event().runId());
            if (checkpoint == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "对话检查点不存在或已过期，无法创建分支");
            }
            Session source = selection.session();
            Session fork = sessions.createSession(source.title() + " · 分支", source.workspace(), source.projectId(), false);
            try {
                for (EventEnvelope event : selection.events()) {
                    if (event.sequence() >= request.sequence()) break;
                    sessions.publish(fork.id(), event.runId(), event.payload());
                }
                tasks.restoreTo(fork.id(), selection.event().runId());
                conversations.save(fork.id(), checkpoint.messages());
                plans.save(fork.id(), checkpoint.plan());
            } catch (RuntimeException e) {
                tasks.delete(fork.id());
                conversations.delete(fork.id());
                plans.delete(fork.id());
                sessions.delete(fork.id());
                throw e;
            }
            return new ForkResponse(fork.id(), fork.title(), fork.workspace(), selection.input());
        } finally {
            runs.abandon(slot);
        }
    }

    private void checkRevision(Selection selection, Long revision) {
        if (revision != null && selection.events().getLast().sequence() != revision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "对话在预览后已变化，请关闭并重新选择检查点");
        }
    }

    private String claim(String id) {
        requireSession(id);
        return runs.claimRun(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "该会话仍在运行，请等任务结束或停止后再操作"));
    }

    private void requireIdleWorkspace(Session source, String slot) {
        for (Session session : sessions.list()) {
            if (sameWorkspace(workspace(source), workspace(session))
                    && runs.activeRun(session.id()).filter(runId -> !runId.equals(slot)).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "同一工作区有其他对话正在运行，请等它结束再回退代码");
            }
        }
    }

    private RunSnapshotService.RestorePreview codePreview(Selection selection) {
        var preview = snapshots.preview(selection.runIds());
        for (String runId : selection.runIds()) {
            var meta = snapshots.meta(runId);
            if (meta != null && !sameWorkspace(workspace(selection.session()), meta.workspace())) {
                var unavailable = new java.util.ArrayList<>(preview.unavailable());
                unavailable.add("当前工作区与检查点不同，请切回原工作区后回退代码");
                return new RunSnapshotService.RestorePreview(preview.files(), preview.conflicts(), unavailable);
            }
        }
        return preview;
    }

    private String workspace(Session session) {
        return session.workspace() == null || session.workspace().isBlank() ? properties.workspace() : session.workspace();
    }

    private static boolean sameWorkspace(String first, String second) {
        if (first == null || second == null) return java.util.Objects.equals(first, second);
        return Path.of(first).toAbsolutePath().normalize().equals(Path.of(second).toAbsolutePath().normalize());
    }

    private Selection select(String id, long sequence) {
        Session session = requireSession(id);
        List<EventEnvelope> events = sessions.events(id);
        EventEnvelope selected = events.stream().filter(event -> event.sequence() == sequence
                        && event.payload() instanceof RunStarted).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "该消息已变化，请刷新后重新选择用户消息"));
        List<String> runIds = events.stream().filter(event -> event.sequence() >= sequence)
                .map(EventEnvelope::runId).distinct().toList();
        return new Selection(session, selected, events, runIds, ((RunStarted) selected.payload()).input());
    }

    private Session requireSession(String id) {
        return sessions.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + id));
    }

    private record Selection(Session session, EventEnvelope event, List<EventEnvelope> events,
                             List<String> runIds, String input) { }
    public record Request(Long sequence, String mode, Long revision) { }
    public record Preview(long sequence, long revision, String input, int runs, boolean conversationAvailable,
                          RunSnapshotService.RestorePreview code) { }
    public record RestoreResponse(boolean applied, String input, RunSnapshotService.RollbackReport code) { }
    public record ForkResponse(String sessionId, String title, String workspace, String input) { }
}
