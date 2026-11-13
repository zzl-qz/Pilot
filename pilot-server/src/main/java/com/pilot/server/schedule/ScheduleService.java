package com.pilot.server.schedule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.Tool;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.maintenance.DataMaintenance;
import com.pilot.server.session.RunStarter;
import com.pilot.server.session.RunSubmissionService;
import com.pilot.server.session.SkillService;
import com.pilot.server.session.TaskControlService;
import com.pilot.server.store.ScheduleRepository;
import com.pilot.server.workspace.GitWorktrees;
import com.pilot.server.workspace.GitWorktrees.Created;
import com.pilot.server.workspace.GitWorktrees.Preview;
import com.pilot.server.workspace.WorkspaceToolFactory;

/** 持久计划只负责投递；所有模型工作仍由 RunStarter 执行。服务就绪后才收敛/调度。 */
@Service
public class ScheduleService {
    private final ScheduleRepository repository;
    private final SessionService sessions;
    private final RunStarter runs;
    private final TaskControlService tasks;
    private final SkillService skills;
    private final Clock clock;
    private DataMaintenance maintenance = new DataMaintenance();

    @org.springframework.beans.factory.annotation.Autowired
    void useMaintenance(PilotDataDir dataDir) {
        this.maintenance = dataDir.maintenance();
    }

    private final GitWorktrees worktrees;
    private final WorkspaceToolFactory toolFactory;
    private volatile boolean ready;
    private static final int MAX_WAKE_ATTEMPTS = 100;
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final long RECOVERY_DELAY_SECONDS = 30L;
    private static final long WAITING_DELAY_SECONDS = 60;
    private static final Duration TOTAL_EXECUTION_LIMIT = Duration.ofHours(24);
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "CANCELLED", "NEEDS_ATTENTION");

    public record Create(String name, String sourceSessionId, String content, Instant runAt, String timezone,
        TaskControlService.Requirements requirements, String skill, Boolean fullAccess, Boolean isolated,
        Boolean requireResearch) {
        public Create(String name, String sourceSessionId, String content, Instant runAt, String timezone,
            TaskControlService.Requirements requirements, String skill, Boolean fullAccess, Boolean isolated) {
            this(name, sourceSessionId, content, runAt, timezone, requirements, skill, fullAccess, isolated, null);
        }

        public Create(String name, String sourceSessionId, String content, Instant runAt, String timezone,
            TaskControlService.Requirements requirements, String skill, Boolean fullAccess) {
            this(name, sourceSessionId, content, runAt, timezone, requirements, skill, fullAccess, false);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ScheduleService(ScheduleRepository repository, SessionService sessions, RunStarter runs,
        TaskControlService tasks, SkillService skills, GitWorktrees worktrees, WorkspaceToolFactory toolFactory) {
        this(repository, sessions, runs, tasks, skills, Clock.systemUTC(), worktrees, toolFactory);
    }

    public ScheduleService(ScheduleRepository repository, SessionService sessions, RunStarter runs,
        TaskControlService tasks, SkillService skills, Clock clock) {
        this(repository, sessions, runs, tasks, skills, clock, null, null);
    }

    public ScheduleService(ScheduleRepository repository, SessionService sessions, RunStarter runs,
        TaskControlService tasks, SkillService skills, Clock clock, GitWorktrees worktrees,
        WorkspaceToolFactory toolFactory) {
        this.repository = repository;
        this.sessions = sessions;
        this.runs = runs;
        this.tasks = tasks;
        this.skills = skills;
        this.clock = clock;
        this.worktrees = worktrees;
        this.toolFactory = toolFactory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        ready = true;
    }

    public List<ScheduledTask> list() {
        return repository.list();
    }

    public Optional<ScheduledTask> findOccurrence(String id) {
        return repository.find(id);
    }

    public ScheduledTask get(String id) {
        return repository.find(id).orElseThrow(() -> new IllegalArgumentException("定时任务不存在"));
    }

    /** 取消和超时先写终态，再请求异步停止；旧运行退出前不能启动下一轮。 */
    synchronized boolean occurrenceFinished(String id) {
        var job = get(id);
        return TERMINAL.contains(job.state()) && runs.activeRun(job.sessionId()).isEmpty();
    }

    public synchronized ScheduledTask create(Create request) {
        return createOccurrence(request, "schedule_" + UUID.randomUUID());
    }

    /** 周期触发使用稳定的实例 ID；重启后重复投递只返回原任务。 */
    synchronized ScheduledTask createOccurrence(Create request, String occurrenceId) {
        var existing = repository.find(occurrenceId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("请填写任务目标");
        }
        if (request.content().length() > 100_000) {
            throw new IllegalArgumentException("任务目标过长");
        }
        Instant now = clock.instant();
        if (request.runAt() == null || !request.runAt().isAfter(now)) {
            throw new IllegalArgumentException("执行时间必须在未来");
        }
        String zone = request.timezone() == null ? "UTC" : request.timezone();
        ZoneId.of(zone);
        Session source = sessions.find(request.sourceSessionId() == null ? "" : request.sourceSessionId())
            .orElseThrow(() -> new IllegalArgumentException("请先选择任务所属的对话和工作区"));
        if (source.isSide()) {
            throw new IllegalArgumentException("请从主对话创建定时任务");
        }
        String name = request.name() == null || request.name().isBlank()
            ? request.content().strip().substring(0, Math.min(40, request.content().strip().length()))
            : request.name().strip();
        if (name.length() > 120) {
            throw new IllegalArgumentException("任务名称最多 120 个字符");
        }
        String skill = request.skill() == null || request.skill().isBlank() ? null : request.skill().strip();
        if (skill != null && skills.catalogFor(source.workspace()).find(skill).isEmpty()) {
            throw new IllegalArgumentException("指定技能不存在");
        }
        String id = occurrenceId;
        var req = request.requirements() == null ? new TaskControlService.Requirements(List.of(), List.of())
            : request.requirements();
        var requirements = new TaskControlService.Requirements(req.constraints(),
            req.acceptance().stream().map(a -> new TaskControlService.Acceptance(a.text(), false, "")).toList(),
            new TaskControlService.Automation(id, !Boolean.FALSE.equals(request.fullAccess()),
                request.requireResearch() != null ? request.requireResearch()
                    : req.automation() != null && req.automation().requireResearch()));
        // 独立会话已经有计划内容，不占普通「空白对话」名额。
        Created isolated = null;
        Session target = null;
        try {
            String path = source.workspace();
            ScheduledTask.Workspace executionWorkspace;
            if (!Boolean.FALSE.equals(request.isolated())) {
                if (worktrees == null || toolFactory == null) {
                    throw new IllegalStateException("独立工作区服务不可用");
                }
                isolated = worktrees.create(toolFactory.resolve(path), "HEAD");
                path = isolated.path();
                if (skill != null && skills.catalogFor(path).find(skill).isEmpty()) {
                    throw new IllegalArgumentException("独立工作区缺少所选技能；请先将项目技能提交到 Git，或改用原目录执行");
                }
                executionWorkspace = new ScheduledTask.Workspace("ISOLATED", path, isolated.base(), isolated.branch(),
                    isolated.commit(), null);
            } else {
                executionWorkspace = new ScheduledTask.Workspace("SHARED", path, path, "", "", null);
            }
            target = sessions.createSession(name, path, source.projectId(), false);
            var task = new ScheduledTask(id, name, target.id(), null, request.content().strip(), requirements, skill,
                request.runAt(), zone, "SCHEDULED", null, 0, 0, null, "等待执行", List.of(), 0, executionWorkspace);
            repository.insert(task);
            return task;
        } catch (RuntimeException e) {
            if (target != null) {
                sessions.delete(target.id());
            }
            if (isolated != null) {
                try {
                    worktrees.remove(Path.of(isolated.path()));
                } catch (RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
    }

    public synchronized ScheduledTask control(String id, String action, Instant runAt) {
        var job = get(id);
        if ("cancel".equals(action)) {
            if ("COMPLETED".equals(job.state())) {
                throw new IllegalArgumentException("已完成的任务不能取消");
            }
            change(job, "CANCELLED", "已取消；正在执行的步骤将在安全边界停止", job.runAt());
            ownedActive(job).ifPresent(runs::cancel);
        } else if ("pause".equals(action)) {
            if (TERMINAL.contains(job.state())) {
                throw new IllegalArgumentException("任务已结束");
            }
            change(job, "PAUSED", "已暂停", job.runAt());
            ownedActive(job).ifPresent(runs::pause);
        } else if ("resume".equals(action)) {
            if (!"PAUSED".equals(job.state())) {
                throw new IllegalArgumentException("只有暂停的计划可以恢复");
            }
            if (runs.activeRun(job.sessionId()).isPresent()) {
                throw new IllegalArgumentException("当前步骤正在暂停，请稍后恢复");
            }
            var task = tasks.load(job.sessionId());
            if (task != null && Objects.equals(task.taskId(), job.taskId()) && "PAUSED".equals(task.status())) {
                tasks.status(job.sessionId(), "INTERRUPTED");
            }
            change(job, job.taskId() == null ? "SCHEDULED" : "WAITING", "等待执行", clock.instant());
        } else if ("reschedule".equals(action)) {
            if (!Set.of("SCHEDULED", "PAUSED", "WAITING").contains(job.state())
                || runs.activeRun(job.sessionId()).isPresent()) {
                throw new IllegalArgumentException("执行中的任务不能修改时间");
            }
            if (runAt == null || !runAt.isAfter(clock.instant())) {
                throw new IllegalArgumentException("执行时间必须在未来");
            }
            change(job, job.state(), "执行时间已更新", runAt);
        } else {
            throw new IllegalArgumentException("不支持的操作");
        }
        return get(id);
    }

    @Scheduled(fixedDelayString = "${pilot.schedules.poll-ms:2000}")
    public void poll() {
        if (ready) {
            maintenance.whenAvailable(this::tick);
        }
    }

    public synchronized void tick() {
        for (var job : repository.list()) {
            try {
                advance(job);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger(getClass()).warn("定时任务 {} 调度失败，保留状态", job.id(), e);
            }
        }
    }

    private Optional<String> ownedActive(ScheduledTask job) {
        var task = tasks.load(job.sessionId());
        return task != null && task.automation() != null && job.id().equals(task.automation().scheduleId())
            && (job.taskId() == null || job.taskId().equals(task.taskId())) ? runs.activeRun(job.sessionId())
                : Optional.empty();
    }

    private void advance(ScheduledTask job) {
        if (TERMINAL.contains(job.state()) || "PAUSED".equals(job.state())) {
            return;
        }
        if (sessions.find(job.sessionId()).isEmpty()) {
            change(job, "CANCELLED", "目标对话已删除", job.runAt());
            return;
        }
        if (job.workspace() != null && "ISOLATED".equals(job.workspace().mode())) {
            String path = sessions.find(job.sessionId()).orElseThrow().workspace();
            if (!Objects.equals(path, job.workspace().path()) || !Files.isDirectory(Path.of(path))
                || !Files.isRegularFile(Path.of(path).resolve(".git"))) {
                change(job, "NEEDS_ATTENTION", "独立工作区已移动或缺失，未切回原目录执行", job.runAt());
                return;
            }
        }
        Instant now = clock.instant();
        if (job.deadline() != null && !now.isBefore(job.deadline())) {
            change(job, "NEEDS_ATTENTION", "已达 24 小时总执行时限，进度已保存", job.runAt());
            ownedActive(job).ifPresent(runs::pause);
            return;
        }
        var task = tasks.load(job.sessionId());
        boolean owned = task != null && task.automation() != null && job.id().equals(task.automation().scheduleId());
        if (job.taskId() != null && (!owned || !job.taskId().equals(task.taskId()))) {
            change(job, "NEEDS_ATTENTION", "会话目标已变化，未续跑其他任务", job.runAt());
            return;
        }
        if (job.taskId() == null && task != null && !owned) {
            change(job, "NEEDS_ATTENTION", "计划对话中已有其他目标，未覆盖该任务", job.runAt());
            return;
        }
        if (runs.activeRun(job.sessionId()).isPresent()) {
            return;
        }
        if (Set.of("RUNNING", "DISPATCHING").contains(job.state())) {
            recoverDispatchedTask(job, task, owned, now);
            return;
        }
        advanceDueTask(job, task, owned, now);
    }

    /** 已派发但当前没有活动运行：先收敛原任务状态，不能盲目重发。 */
    private void recoverDispatchedTask(ScheduledTask job, TaskControlService.TaskContract task, boolean owned,
        Instant now) {
        if (!owned) {
            // 无 RunStarted 事件才可断定领取后未投递；投递到一半的未知状态不盲目重发。
            String expectedRun = job.runId();
            boolean started =
                sessions.events(job.sessionId()).stream().anyMatch(e -> Objects.equals(e.runId(), expectedRun));
            change(job, started ? "NEEDS_ATTENTION" : "SCHEDULED", started ? "上次投递未完整启动，请检查执行记录" : "恢复未投递的计划", now);
            return;
        }
        if (job.taskId() == null) {
            var bound = job.change(job.state(), task.taskId(), job.runId(), job.runAt(), job.deadline(), job.attempts(),
                job.recoveries(), job.message(), job.history());
            if (!repository.replace(job, bound)) {
                return;
            }
            job = bound;
        }
        String status = task.status();
        if ("COMPLETED".equals(status)) {
            change(job, "COMPLETED", "目标已核验完成", job.runAt());
            return;
        }
        if ("PAUSED".equals(status)) {
            change(job, "PAUSED", "执行已暂停", job.runAt());
            return;
        }
        if ("WAITING".equals(status)) {
            change(job, "WAITING", "等待外部条件，稍后再次检查", now.plusSeconds(WAITING_DELAY_SECONDS));
            return;
        }
        if ("INTERRUPTED".equals(status) && job.recoveries() < MAX_RECOVERY_ATTEMPTS
            && runs.resumable(job.sessionId())) {
            var next = job.change("WAITING", job.taskId(), job.runId(),
                now.plusSeconds(RECOVERY_DELAY_SECONDS << job.recoveries()), job.deadline(), job.attempts(),
                job.recoveries() + 1, "执行中断，保留进度后重试", finishHistory(job, "INTERRUPTED"));
            repository.replace(job, next);
            return;
        }
        change(job, "NEEDS_ATTENTION", "任务停止：" + status + "，请打开执行记录查看原因", job.runAt());
    }

    private void advanceDueTask(ScheduledTask job, TaskControlService.TaskContract task, boolean owned, Instant now) {
        if (job.runAt().isAfter(now)) {
            return;
        }
        if (owned && "PAUSED".equals(task.status())) {
            change(job, "PAUSED", "执行已暂停", job.runAt());
            return;
        }
        if (owned
            && Set.of("BUDGET_LIMITED", "NEED_INPUT", "BLOCKED", "COMPLETED", "NO_PROGRESS", "VERIFICATION_FAILED")
                .contains(task.status())) {
            change(job, "COMPLETED".equals(task.status()) ? "COMPLETED" : "NEEDS_ATTENTION", "任务状态：" + task.status(),
                job.runAt());
            return;
        }
        if (job.taskId() != null && !runs.resumable(job.sessionId())) {
            change(job, "NEEDS_ATTENTION", "没有可续跑的对话记忆", job.runAt());
            return;
        }
        if (job.attempts() >= MAX_WAKE_ATTEMPTS) {
            change(job, "NEEDS_ATTENTION", "已达到 100 次唤醒上限", job.runAt());
            return;
        }
        dispatchTask(job, now);
    }

    /** 领取运行后再次核对任务归属，保留原来的 CAS 和失败释放顺序。 */
    private void dispatchTask(ScheduledTask job, Instant now) {
        String runId = runs.claimRun(job.sessionId()).orElse(null);
        if (runId == null) {
            return;
        }
        List<ScheduledTask.Invocation> history = new ArrayList<>(finishHistory(job, "WAITING"));
        history.add(new ScheduledTask.Invocation(runId, now, "RUNNING"));
        var claimed = job.change("DISPATCHING", job.taskId(), runId, job.runAt(),
            job.deadline() == null ? now.plus(TOTAL_EXECUTION_LIMIT) : job.deadline(), job.attempts() + 1,
            job.recoveries(), "正在执行", history);
        try {
            if (!repository.replace(job, claimed)) {
                runs.abandon(runId);
                return;
            }
            // 占位后再核对身份，阻断用户新任务恰好插入的竞态。
            var current = tasks.load(job.sessionId());
            if ((job.taskId() != null && (current == null || !job.taskId().equals(current.taskId())))
                || (job.taskId() == null && current != null
                    && (current.automation() == null || !job.id().equals(current.automation().scheduleId())))) {
                throw new IllegalStateException("目标已变更");
            }
            if (job.taskId() == null) {
                RunSubmissionService.start(sessions, runs, job.sessionId(), runId, job.content(), List.of(), List.of(),
                    job.requirements(), job.skill());
            } else {
                RunSubmissionService.resume(sessions, runs, job.sessionId(), runId);
            }
            current = tasks.load(job.sessionId());
            // wakeTool 可能已更新版本；CAS 失败时保留模型设置的等待状态。
            repository.replace(claimed,
                claimed.change("RUNNING", current == null ? null : current.taskId(), runId, claimed.runAt(),
                    claimed.deadline(), claimed.attempts(), claimed.recoveries(), "正在执行", claimed.history()));
        } catch (RuntimeException e) {
            runs.abandon(runId);
            var latest = get(job.id());
            change(latest, "NEEDS_ATTENTION", "投递失败：" + e.getMessage(), latest.runAt());
        }
    }

    public Preview handoffPreview(String id) {
        var job = handoffJob(id);
        return worktrees.preview(Path.of(job.workspace().path()));
    }

    public synchronized ScheduledTask handoff(String id, String revision) {
        if (revision == null || revision.isBlank()) {
            throw new IllegalArgumentException("请先预览改动");
        }
        var job = handoffJob(id);
        worktrees.handoff(Path.of(job.workspace().path()), revision);
        return handedOff(id, job.version());
    }

    private ScheduledTask handoffJob(String id) {
        var job = get(id);
        if (!Set.of("COMPLETED", "NEEDS_ATTENTION").contains(job.state())
            || runs.activeRun(job.sessionId()).isPresent()) {
            throw new IllegalStateException("任务还在运行或等待，请结束执行后交回");
        }
        if (worktrees == null || job.workspace() == null || !"ISOLATED".equals(job.workspace().mode())) {
            throw new IllegalArgumentException("该任务未使用独立工作区");
        }
        if (job.workspace().handedOffAt() != null) {
            throw new IllegalStateException("改动已经交回，请在原目录查看");
        }
        var current = sessions.find(job.sessionId()).orElseThrow(() -> new IllegalArgumentException("执行对话不存在"));
        if (!Objects.equals(current.workspace(), job.workspace().path())) {
            throw new IllegalStateException("执行位置已变化，请打开对话检查");
        }
        return job;
    }

    private synchronized ScheduledTask handedOff(String id, long expectedVersion) {
        var job = get(id);
        if (job.version() != expectedVersion) {
            throw new IllegalStateException("计划已变化，请重新检查交回结果");
        }
        var w = job.workspace();
        var next = job.withWorkspace(
            new ScheduledTask.Workspace(w.mode(), w.path(), w.base(), w.branch(), w.commit(), clock.instant()));
        if (!repository.replace(job, next)) {
            throw new IllegalStateException("交回已执行，但计划状态写入冲突，请核对原目录");
        }
        return next;
    }

    private List<ScheduledTask.Invocation> finishHistory(ScheduledTask job, String status) {
        return job.history().stream()
            .map(h -> "RUNNING".equals(h.status()) ? new ScheduledTask.Invocation(h.runId(), h.startedAt(), status) : h)
            .toList();
    }

    private void change(ScheduledTask job, String state, String message, Instant at) {
        var history = Set.of("COMPLETED", "CANCELLED", "PAUSED", "NEEDS_ATTENTION", "WAITING").contains(state)
            ? finishHistory(job, state) : job.history();
        var next = job.change(state, job.taskId(), job.runId(), at, job.deadline(), job.attempts(), job.recoveries(),
            message, history);
        if (TERMINAL.contains(state)) {
            next = next.ended(clock.instant());
        }
        if (!repository.replace(job, next)) {
            throw new IllegalStateException("任务状态已变化，请刷新后重试");
        }
    }

    public Tool wakeTool(String sessionId) {
        return new Tool() {
            public String name() {
                return "wait_until";
            }

            public boolean readOnly() {
                return true;
            }

            public String description() {
                return "外部条件尚未就绪时，保存进度并预约下次唤醒。调用后本轮停止，不要 sleep 或反复轮询。时间为带时区的 ISO 8601；最少等待 30 秒。";
            }

            public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties",
                    Map.of("run_at", Map.of("type", "string"), "reason", Map.of("type", "string")), "required",
                    List.of("run_at", "reason"));
            }

            public String execute(Map<String, Object> args) {
                return waitUntil(sessionId, Instant.parse(Objects.toString(args.get("run_at"), "")),
                    Objects.toString(args.get("reason"), ""));
            }
        };
    }

    synchronized String waitUntil(String sessionId, Instant at, String reason) {
        var task = tasks.load(sessionId);
        if (task == null || task.automation() == null || reason.isBlank()) {
            throw new IllegalArgumentException("缺少任务或等待原因");
        }
        var job = get(task.automation().scheduleId());
        if (!job.sessionId().equals(sessionId) || (job.taskId() != null && !job.taskId().equals(task.taskId()))) {
            throw new IllegalStateException("计划与当前任务不匹配");
        }
        if (!Set.of("RUNNING", "DISPATCHING").contains(job.state())) {
            throw new IllegalStateException("计划已暂停或结束");
        }
        if (at.isBefore(clock.instant().plusSeconds(30)) || (job.deadline() != null && !at.isBefore(job.deadline()))) {
            throw new IllegalArgumentException("唤醒时间须在 30 秒以后且不超过总执行期限");
        }
        var next = job.change("WAITING", task.taskId(), job.runId(), at, job.deadline(), job.attempts(),
            job.recoveries(), reason, finishHistory(job, "WAITING"));
        if (!repository.replace(job, next)) {
            throw new IllegalStateException("计划已变化");
        }
        tasks.status(sessionId, "WAITING");
        return "已保存，将在 " + at + " 唤醒。停止本轮执行。";
    }
}
