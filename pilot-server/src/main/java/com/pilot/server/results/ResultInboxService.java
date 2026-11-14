package com.pilot.server.results;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunCancelled;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailed;
import com.pilot.core.message.ToolNames;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.util.WireEnum;
import com.pilot.server.common.web.SessionLookup;
import com.pilot.server.run.EvidenceStatus;
import com.pilot.server.run.ExecutionPhase;
import com.pilot.server.run.TaskControlService;
import com.pilot.server.schedule.ScheduleService;
import com.pilot.server.schedule.ScheduleState;
import com.pilot.server.schedule.ScheduledTask;

import tools.jackson.databind.ObjectMapper;

@Service
public class ResultInboxService {
    private final ResultInboxRepository repository;
    private final SessionService sessions;
    private final ScheduleService schedules;
    private final TaskControlService tasks;
    private final ObjectMapper mapper;

    public ResultInboxService(ResultInboxRepository repository, SessionService sessions, ScheduleService schedules,
        TaskControlService tasks, ObjectMapper mapper) {
        this.repository = repository;
        this.sessions = sessions;
        this.schedules = schedules;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    /** 条目来源，名字就是接口里的 source。 */
    private enum Source {
        SCHEDULE, CHAT
    }

    /** 对话结果的状态，名字就是接口里的 state；计划条目直接沿用计划状态。 */
    private enum ChatState {
        COMPLETED, NEEDS_ATTENTION, AWAITING_REVIEW
    }

    /** 没有执行记录时详情里的核验阶段。 */
    private static final String UNKNOWN_VERIFICATION = "UNKNOWN";

    public record Item(String id, String revision, String sessionId, String title, String source, String state,
        String summary, Instant at, String runId, String scheduleId, boolean read, boolean handled,
        List<String> changedFiles, List<String> checks, List<String> openSteps, ScheduledTask.Workspace workspace) {
    }

    /** 条目是否需要人工处理；计划条目和对话条目用的是同一个状态名。 */
    public static boolean needsAttention(Item item) {
        return ChatState.NEEDS_ATTENTION.name().equals(item.state());
    }

    public record Page(List<Item> items, boolean truncated) {
    }

    public record Research(String id, List<String> sources, String decision, String license,
        List<String> sourceEvidence, long observedAt, boolean structured, String raw) {
    }

    public record Detail(Item item, List<Research> research, String verification,
        List<TaskControlService.Acceptance> acceptance, String verificationDetail) {
    }

    public Page list() {
        return page(500, Instant.EPOCH);
    }

    /** 通知不能因 UI 的最近 500 条窗口而漏掉离线结果。 */
    public List<Item> deliveryItems(Instant since) {
        return page(Integer.MAX_VALUE, since).items();
    }

    private Page page(int limit, Instant since) {
        List<EventEnvelope> endings = repository.endings(limit == Integer.MAX_VALUE ? limit : limit + 1, since);
        Map<String, EventEnvelope> byRun = new HashMap<>();
        endings.forEach(e -> byRun.put(e.runId(), e));
        var jobs = schedules.list();
        Set<String> scheduledSessions = new HashSet<>();
        List<Item> items = new ArrayList<>();
        Map<String, Session> sessionIndex = new HashMap<>();
        sessions.list().forEach(session -> sessionIndex.put(session.id(), session));
        List<String> receiptIds = new ArrayList<>(byRun.keySet());
        jobs.forEach(job -> receiptIds.add(job.id()));
        var receipts = limit == Integer.MAX_VALUE ? Map.<String, ResultInboxRepository.Receipt>of()
            : repository.receipts(receiptIds);
        for (var job : jobs) {
            scheduledSessions.add(job.sessionId());
            if (!WireEnum.in(job.state(), ScheduleState.COMPLETED, ScheduleState.NEEDS_ATTENTION)
                || !sessionIndex.containsKey(job.sessionId())) {
                continue;
            }
            EventEnvelope end = byRun.get(job.runId());
            var completed = end != null && end.payload() instanceof RunCompleted c ? c : null;
            String revision =
                job.state() + ":" + job.runId() + ":" + job.attempts() + ":" + (end == null ? 0 : end.sequence());
            var receipt = receipt(receipts, job.id(), revision);
            items.add(new Item(job.id(), revision, job.sessionId(), job.name(), Source.SCHEDULE.name(), job.state(),
                scheduleSummary(job, end),
                job.endedAt() != null ? job.endedAt() : end == null ? job.runAt() : end.timestamp(), job.runId(),
                job.id(), receipt.seen(), receipt.handled(), completed == null ? List.of() : completed.changedFiles(),
                completed == null ? List.of() : completed.checks(),
                completed == null ? List.of() : completed.openSteps(), job.workspace()));
        }
        for (var end : endings) {
            if (scheduledSessions.contains(end.sessionId())) {
                continue;
            }
            if (ResultInboxRepository.stillPending(end.payload())) {
                continue;
            }
            if (end.payload() instanceof RunCancelled) {
                continue;
            }
            var session = sessionIndex.get(end.sessionId());
            if (session == null) {
                continue;
            }
            var completed = end.payload() instanceof RunCompleted c ? c : null;
            var resultTask = tasks.result(end.runId());
            ChatState state =
                completed == null || verificationNeedsAttention(resultTask, completed) ? ChatState.NEEDS_ATTENTION
                    : completed.openSteps().isEmpty() ? ChatState.COMPLETED : ChatState.AWAITING_REVIEW;
            String summary = completed == null ? ((RunFailed)end.payload()).message() : completed.summary();
            String revision = end.sessionId() + ":" + end.sequence();
            var receipt = receipt(receipts, end.runId(), revision);
            items.add(new Item(end.runId(), revision, session.id(), session.title(), Source.CHAT.name(), state.name(),
                summary, end.timestamp(), end.runId(), null, receipt.seen(), receipt.handled(),
                completed == null ? List.of() : completed.changedFiles(),
                completed == null ? List.of() : completed.checks(),
                completed == null ? List.of() : completed.openSteps(), null));
        }
        items.sort(Comparator.comparing(Item::at).reversed().thenComparing(Item::id));
        return new Page(items.stream().filter(item -> !item.at().isBefore(since)).limit(limit).toList(),
            endings.size() > limit || items.size() > limit);
    }

    private static boolean verificationNeedsAttention(TaskControlService.TaskContract task, RunCompleted completed) {
        if (task == null || task.execution() == null) {
            return false;
        }
        var execution = task.execution();
        if (WireEnum.in(execution.phase(), ExecutionPhase.CHECKS_FAILED, ExecutionPhase.CHECKS_STALE)) {
            return true;
        }
        if (!ExecutionPhase.UNVERIFIED.is(execution.phase())) {
            return false;
        }
        // 问答与文档修改可以正常交付；代码改动缺证据则需要关注，不能冒充验证完成。
        return Stream.concat(execution.changedPaths().stream(), completed.changedFiles().stream())
            .anyMatch(path -> path != null && !path.isBlank() && !path.matches("(?i).*\\.(md|txt|rst|adoc)$"))
            || execution.evidence().stream().anyMatch(e -> EvidenceStatus.RUNNING.is(e.status()) && e.check() != null);
    }

    private static String scheduleSummary(ScheduledTask job, EventEnvelope end) {
        String result = end == null ? "" : end.payload() instanceof RunCompleted c ? c.summary()
            : end.payload() instanceof RunFailed f ? f.message() : "";
        if (result == null || result.isBlank()) {
            return job.message();
        }
        return ScheduleState.NEEDS_ATTENTION.is(job.state()) ? job.message() + "\n\n最近运行：" + result : result;
    }

    private static ResultInboxRepository.Receipt receipt(Map<String, ResultInboxRepository.Receipt> receipts, String id,
        String revision) {
        var saved = receipts.get(id);
        return saved != null && saved.revision().equals(revision) ? saved
            : new ResultInboxRepository.Receipt(revision, false, false);
    }

    public Item get(String id) {
        return list().items.stream().filter(item -> item.id.equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("结果不存在或不在最近结果窗口，请打开原对话查看"));
    }

    public Item mark(String id, String revision, String action) {
        Item item = get(id);
        if (!item.revision.equals(revision)) {
            throw new IllegalStateException("结果已更新，请刷新后操作");
        }
        repository.mark(id, revision, action);
        return get(id);
    }

    public Detail detail(String id) {
        var item = get(id);
        TaskControlService.TaskContract task = item.runId == null ? null : tasks.result(item.runId);
        if (task == null && item.scheduleId != null) {
            var job = schedules.get(item.scheduleId);
            var current = tasks.load(item.sessionId);
            if (current != null && Objects.equals(current.taskId(), job.taskId())) {
                task = current;
            }
        }
        return new Detail(item, research(task),
            task == null || task.execution() == null ? UNKNOWN_VERIFICATION : task.execution().phase(),
            task == null ? List.of() : task.acceptance(),
            task == null || task.execution() == null ? "" : task.execution().gap());
    }

    public List<Research> sessionResearch(String sessionId) {
        if (sessions.find(sessionId).isEmpty()) {
            throw new IllegalArgumentException(SessionLookup.MISSING);
        }
        return research(tasks.load(sessionId));
    }

    private List<Research> research(TaskControlService.TaskContract task) {
        if (task == null || task.execution() == null) {
            return List.of();
        }
        List<Research> result = new ArrayList<>();
        for (var e : task.execution().evidence()) {
            if (!ToolNames.RECORD_RESEARCH.equals(e.tool()) || !EvidenceStatus.SUCCESS.is(e.status())) {
                continue;
            }
            try {
                var json = mapper.readTree(e.result());
                List<String> sources = new ArrayList<>();
                List<String> evidence = new ArrayList<>();
                if (json.has("sources")) {
                    json.get("sources").forEach(value -> sources.add(value.asText()));
                }
                if (json.has("sourceEvidence")) {
                    json.get("sourceEvidence").forEach(value -> evidence.add(value.asText()));
                }
                result.add(new Research(e.id(), sources, json.path("decision").asText(""),
                    json.path("license").asText(""), evidence, e.observedAtEpochMillis(), true, ""));
            } catch (RuntimeException ex) {
                result.add(
                    new Research(e.id(), List.of(), "", "", List.of(), e.observedAtEpochMillis(), false, e.result()));
            }
        }
        return List.copyOf(result);
    }
}
