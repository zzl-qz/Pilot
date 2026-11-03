package com.pilot.server.schedule;

import com.pilot.core.session.*;
import com.pilot.core.event.RunStarted;
import com.pilot.server.config.PilotDataDir;
import com.pilot.server.session.*;
import com.pilot.server.store.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScheduleServiceTest {
    @TempDir Path root;
    final MutableClock clock = new MutableClock();
    SessionService sessions;
    TaskControlService tasks;
    ScheduleRepository repository;
    FakeRunner runner;
    ScheduleService service;
    String source;
    @BeforeEach void setup() {
        sessions = new SessionService(clock);
        source = sessions.createSession("原始任务", root.toString()).id();
        var mapper = JsonMapper.builder().build();
        tasks = new TaskControlService(mapper, new PilotDataDir(root.resolve("data").toString()));
        repository = new ScheduleRepository(new SqliteStore(root.resolve("schedules.db")), mapper);
        runner = new FakeRunner();
        service = new ScheduleService(repository, sessions, runner, tasks, null, clock);
    }
    ScheduledTask create() {
        return service.create(new ScheduleService.Create("修复依赖", source, "参考开源方案修复依赖", clock.instant().plusSeconds(60),
                "Asia/Shanghai", new TaskControlService.Requirements(List.of("保持接口"), List.of(new TaskControlService.Acceptance("测试通过", false, ""))), null, true));
    }
    void due() { clock.at = clock.at.plusSeconds(61); service.tick(); }
    void finish(ScheduledTask job, String status) { runner.active.remove(job.sessionId()); tasks.status(job.sessionId(), status); }
    @Test void persistsDueTimeAndRunsExactlyOnceWithFullRequirements() {
        var job = create();
        assertNotEquals(source, job.sessionId());
        service.tick(); assertEquals(0, runner.starts);
        due(); service.tick();
        assertEquals(1, runner.starts);
        var task = tasks.load(job.sessionId());
        assertEquals(List.of("保持接口"), task.constraints());
        assertEquals("测试通过", task.acceptance().getFirst().text());
        assertTrue(task.automation().fullAccess()); assertTrue(task.automation().requireResearch());
        assertEquals(task.taskId(), service.get(job.id()).taskId());
        finish(job, "COMPLETED"); service.tick();
        assertEquals("COMPLETED", service.get(job.id()).state());
        assertEquals(clock.instant(), service.get(job.id()).endedAt());
        assertEquals("COMPLETED", service.get(job.id()).history().getFirst().status());
        var reopened = new ScheduleRepository(new SqliteStore(root.resolve("schedules.db")), JsonMapper.builder().build());
        assertEquals(service.get(job.id()), reopened.find(job.id()).orElseThrow());
    }
    @Test void busySessionKeepsPayloadAndDoesNotConsumeSchedule() {
        var job = create(); runner.active.put(job.sessionId(), "busy"); due();
        assertEquals("SCHEDULED", service.get(job.id()).state()); assertEquals(0, runner.starts);
        runner.active.clear(); service.tick(); assertEquals(1, runner.starts);
    }
    @Test void waitingReleasesWorkerAndResumesSameGoalOnlyAtDueTime() {
        var job = create(); due(); String taskId = tasks.load(job.sessionId()).taskId();
        Instant wake = clock.instant().plusSeconds(600);
        service.waitUntil(job.sessionId(), wake, "等待 CI 完成");
        runner.active.clear(); service.tick(); assertEquals(0, runner.resumes);
        clock.at = wake; service.tick();
        assertEquals(1, runner.resumes); assertEquals(taskId, tasks.load(job.sessionId()).taskId());
        assertEquals(2, service.get(job.id()).history().size());
    }
    @Test void pauseCancelAndChangedTargetNeverAutoResume() {
        var job = create(); service.control(job.id(), "pause", null); due(); assertEquals(0, runner.starts);
        service.control(job.id(), "resume", null); service.tick(); assertEquals(1, runner.starts);
        finish(job, "PAUSED"); service.tick(); assertEquals("PAUSED", service.get(job.id()).state());
        service.control(job.id(), "resume", null); service.tick(); assertEquals(1, runner.resumes);
        service.control(job.id(), "cancel", null); service.tick(); assertEquals("CANCELLED", service.get(job.id()).state());
        assertEquals(1, runner.cancels);
        var other = create(); due(); finish(other, "INTERRUPTED");
        tasks.start(other.sessionId(), "用户换了目标", List.of(), List.of()); service.tick();
        assertEquals("NEEDS_ATTENTION", service.get(other.id()).state()); assertEquals(1, runner.resumes);
    }
    @Test void neverOverwritesOrCancelsAnotherGoalInTheScheduleSession() {
        var fresh = create();
        tasks.start(fresh.sessionId(), "另一个目标", List.of(), List.of());
        runner.active.put(fresh.sessionId(), "other-run");
        due();
        assertEquals("NEEDS_ATTENTION", service.get(fresh.id()).state());
        assertEquals(0, runner.starts);
        service.control(fresh.id(), "cancel", null);
        assertEquals(0, runner.cancels);
        assertEquals("other-run", runner.active.get(fresh.sessionId()));
        var running = create(); due();
        tasks.start(running.sessionId(), "后来更换的目标", List.of(), List.of());
        service.control(running.id(), "pause", null);
        assertEquals(0, runner.pauses);
    }
    @Test void forkDoesNotInheritUnattendedAuthorization() {
        var job = create(); due();
        String fork = sessions.createSession("fork", root.toString()).id();
        tasks.copy(job.sessionId(), fork);
        assertNull(tasks.load(fork).automation());
    }
    @Test void restartRecoveryIsBoundedAndPreservesBudgetStop() {
        var job = create(); due();
        for (int i = 0; i < 3; i++) {
            finish(job, "INTERRUPTED"); service.tick();
            assertEquals("WAITING", service.get(job.id()).state());
            clock.at = service.get(job.id()).runAt(); service.tick();
        }
        assertEquals(3, runner.resumes);
        finish(job, "INTERRUPTED"); service.tick(); assertEquals("NEEDS_ATTENTION", service.get(job.id()).state());
        var limited = create(); due(); finish(limited, "BUDGET_LIMITED"); service.tick();
        assertEquals("NEEDS_ATTENTION", service.get(limited.id()).state()); assertEquals(3, runner.resumes);
    }
    @Test void overdueAfterOfflineRunsOnceAndDeadlineStopsIt() {
        var job = create(); clock.at = clock.at.plus(Duration.ofDays(2)); service.tick(); assertEquals(1, runner.starts);
        service.tick(); assertEquals(1, runner.starts);
        clock.at = clock.at.plus(Duration.ofHours(25)); service.tick();
        assertEquals("NEEDS_ATTENTION", service.get(job.id()).state()); assertEquals(1, runner.pauses);
    }
    @Test void dispatchCrashBeforePublishCanRetryButUncertainDeliveryCannot() {
        var job = create();
        var claimed = job.change("DISPATCHING", null, "run_crash", job.runAt(), clock.instant().plusSeconds(900), 1, 0, "", List.of());
        assertTrue(repository.replace(job, claimed)); due();
        assertEquals("SCHEDULED", service.get(job.id()).state()); service.tick(); assertEquals(1, runner.starts);
        var other = create();
        assertTrue(repository.replace(other, other.change("DISPATCHING", null, "run_unknown", other.runAt(), null, 1, 0, "", List.of())));
        sessions.publish(other.sessionId(), "run_unknown", new RunStarted("已投递但未启动"));
        due(); assertEquals("NEEDS_ATTENTION", service.get(other.id()).state()); assertEquals(1, runner.starts);
    }
    @Test void sqliteCompareAndSetProtectsAgainstStaleCancelOrDuplicateClaim() {
        var job = create();
        var second = new ScheduleRepository(new SqliteStore(root.resolve("schedules.db")), JsonMapper.builder().build());
        var stale = second.find(job.id()).orElseThrow();
        service.control(job.id(), "cancel", null);
        assertFalse(second.replace(stale, stale.change("DISPATCHING", null, "duplicate", stale.runAt(), null, 1, 0, "", List.of())));
        due(); assertEquals(0, runner.starts);
    }
    @Test void invalidTimeZoneAndPastDateAreRejectedWithoutCreatingSessions() {
        int count = sessions.list().size();
        assertThrows(IllegalArgumentException.class, () -> service.create(new ScheduleService.Create("x", source, "目标", clock.instant(), "UTC", null, null, true)));
        assertThrows(DateTimeException.class, () -> service.create(new ScheduleService.Create("x", source, "目标", clock.instant().plusSeconds(60), "Mars/Nope", null, null, true)));
        assertEquals(count, sessions.list().size());
    }
    @Test void rescheduleAndUtcOffsetsKeepTheSameInstant() {
        var job = create();
        Instant at = OffsetDateTime.parse("2026-11-04T09:00:00+08:00").toInstant();
        service.control(job.id(), "reschedule", at);
        assertEquals(Instant.parse("2026-11-04T01:00:00Z"), service.get(job.id()).runAt());
        assertEquals("Asia/Shanghai", service.get(job.id()).timezone());
    }
    @Test void defaultIsolationUsesCommittedHeadAndHandoffKeepsExecutionSession() throws Exception {
        Path repo = java.nio.file.Files.createDirectories(root.resolve("仓库 spaces"));
        git(repo, "init"); git(repo, "config", "user.email", "t@example.com"); git(repo, "config", "user.name", "test");
        java.nio.file.Files.writeString(repo.resolve("a.txt"), "baseline"); git(repo, "add", "."); git(repo, "commit", "-m", "初始版本");
        java.nio.file.Files.writeString(repo.resolve("untracked.txt"), "不要复制");
        sessions.moveToWorkspace(source, repo.toString());
        var factory = org.mockito.Mockito.mock(com.pilot.server.workspace.WorkspaceToolFactory.class);
        org.mockito.Mockito.when(factory.resolve(repo.toString())).thenReturn(repo);
        var worktrees = new com.pilot.server.workspace.GitWorktrees(new PilotDataDir(root.resolve("isolated-data").toString()));
        service = new ScheduleService(repository, sessions, runner, tasks, null, clock, worktrees, factory);
        var job = service.create(new ScheduleService.Create("独立执行", source, "修复", clock.instant().plusSeconds(60), "UTC", null, null, true, null));
        assertEquals("ISOLATED", job.workspace().mode()); Path copy = Path.of(job.workspace().path());
        assertFalse(java.nio.file.Files.exists(copy.resolve("untracked.txt")));
        assertEquals(copy.toString(), sessions.find(job.sessionId()).orElseThrow().workspace());
        assertThrows(IllegalStateException.class, () -> service.handoffPreview(job.id()));
        due(); java.nio.file.Files.writeString(copy.resolve("a.txt"), "finished"); finish(job, "COMPLETED"); service.tick();
        var preview = service.handoffPreview(job.id()); assertEquals("baseline", java.nio.file.Files.readString(repo.resolve("a.txt")));
        var handed = service.handoff(job.id(), preview.revision()); assertNotNull(handed.workspace().handedOffAt());
        assertEquals("finished", java.nio.file.Files.readString(repo.resolve("a.txt")));
        assertEquals(copy.toString(), sessions.find(job.sessionId()).orElseThrow().workspace());
        assertThrows(IllegalStateException.class, () -> service.handoff(job.id(), preview.revision()));
        var missing = service.create(new ScheduleService.Create("丢失目录", source, "修复", clock.instant().plusSeconds(60), "UTC", null, null, true, true));
        worktrees.remove(Path.of(missing.workspace().path())); due();
        assertEquals("NEEDS_ATTENTION", service.get(missing.id()).state()); assertEquals(1, runner.starts);
    }
    @Test void oldSchedulePayloadWithoutWorkspaceRemainsReadable() {
        var mapper = JsonMapper.builder().build(); var job = create();
        var json = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(job); json.remove("workspace");
        assertNull(mapper.treeToValue(json, ScheduledTask.class).workspace());
    }
    private static void git(Path directory, String... arguments) throws Exception {
        var args = new java.util.ArrayList<String>(); args.add("git"); args.addAll(List.of(arguments));
        var process = new ProcessBuilder(args).directory(directory.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }
    RecurringScheduleService recurring() {
        return new RecurringScheduleService(new AutomationStore(new SqliteStore(root.resolve("recurring.db"))), JsonMapper.builder().build(), service, sessions, clock);
    }
    RecurringScheduleService.Series series(RecurringScheduleService recurring,String misfire) {
        var task=new ScheduleService.Create("周期检查",source,"复用链路执行",clock.instant().plusSeconds(60),"Asia/Shanghai",null,null,true,false);
        return recurring.create(new RecurringScheduleService.Create(task,"0 * * * * *",misfire));
    }
    @Test void recurringReusesOneShotExecutionAndNeverOverlapsOrReplaysEveryMissedTick() {
        var recurring=recurring();var series=series(recurring,"ONCE");clock.at=clock.at.plusSeconds(61);recurring.tick();
        var current=recurring.get(series.id());assertEquals(1,current.occurrences());assertNotNull(current.activeId());
        clock.at=clock.at.plusSeconds(2);service.tick();assertEquals(1,runner.starts);
        clock.at=clock.at.plusSeconds(300);recurring.tick();assertEquals(1,recurring.get(series.id()).occurrences());
        finish(service.get(current.activeId()),"COMPLETED");service.tick();recurring.tick();
        assertEquals(2,recurring.get(series.id()).occurrences());assertTrue(recurring.get(series.id()).nextAt().isAfter(clock.instant()));
        var paused=recurring.control(series.id(),recurring.get(series.id()).version(),"pause");
        clock.at=clock.at.plusSeconds(600);recurring.tick();assertEquals(2,recurring.get(series.id()).occurrences());
        assertThrows(IllegalStateException.class,()->recurring.control(series.id(),series.version(),"resume"));
        recurring.control(series.id(),paused.version(),"cancel");assertEquals("CANCELLED",recurring.get(series.id()).state());
    }
    @Test void recurringSkipsLateTicksAndRecoversCrashAfterOccurrenceWasPersisted() {
        var recurring=recurring();var skip=series(recurring,"SKIP");clock.at=clock.at.plusSeconds(180);recurring.tick();
        assertEquals(0,recurring.get(skip.id()).occurrences());assertTrue(recurring.get(skip.id()).nextAt().isAfter(clock.instant()));
        var recovery=series(recurring,"SKIP");String key=recovery.id()+"_"+recovery.nextAt().toEpochMilli();
        var job=service.createOccurrence(recovery.task(),key);int count=sessions.list().size();
        clock.at=clock.at.plusSeconds(180);var restarted=recurring();restarted.tick();
        assertEquals(key,restarted.get(recovery.id()).activeId());assertEquals(1,restarted.get(recovery.id()).occurrences());
        assertEquals(count,sessions.list().size());assertEquals(job,service.get(key));
    }
    @Test void cronPreviewUsesNamedTimezoneRejectsDstGapAndTemplatesUseVersionedSnapshots() {
        var r=recurring();
        var times=r.times("2026-03-07T09:00","America/New_York","0 0 9 * * *");
        assertEquals(Instant.parse("2026-03-07T14:00:00Z"),times.runAt());
        assertEquals(Instant.parse("2026-03-08T13:00:00Z"),times.next().get(1));
        assertThrows(IllegalArgumentException.class,()->r.times("2026-03-08T02:30","America/New_York","0 30 2 * * *"));
        assertThrows(IllegalArgumentException.class,()->r.times("2026-03-07T09:00","UTC","* * * * * *"));
        var task=new ScheduleService.Create("模板目标",source,"原目标",clock.instant().plusSeconds(60),"UTC",null,null,true,false);
        var template=r.saveTemplate(null,0,"日常检查",task);assertNull(template.task().sourceSessionId());assertNull(template.task().runAt());
        var changed=r.saveTemplate(template.id(),template.version(),"更新后的检查",task);assertEquals(2,changed.version());
        assertThrows(IllegalStateException.class,()->r.saveTemplate(template.id(),1,"过期写入",task));
        assertEquals(2,recurring().templates().getFirst().version());r.deleteTemplate(changed.id(),changed.version());assertTrue(r.templates().isEmpty());
    }
    @Test void recurringDoesNotSilentlyFollowSourceWorkspaceChanges() {
        var r=recurring();var plan=series(r,"ONCE");int count=sessions.list().size();
        sessions.moveToWorkspace(source,root.resolve("另一个工作区").toString());clock.at=clock.at.plusSeconds(61);r.tick();
        assertEquals("ERROR",r.get(plan.id()).state());assertTrue(r.get(plan.id()).message().contains("来源工作区已改变"));
        assertEquals(count,sessions.list().size());assertEquals(0,runner.starts);
    }
    class FakeRunner implements RunStarter {
        final Map<String, String> active = new HashMap<>(); int starts, resumes, pauses, cancels;
        public Optional<String> activeRun(String session) { return Optional.ofNullable(active.get(session)); }
        public Optional<String> claimRun(String session) { if (active.containsKey(session)) return Optional.empty(); String id="run_"+UUID.randomUUID();active.put(session,id);return Optional.of(id); }
        public void abandon(String run) { active.values().remove(run); }
        public void start(String session, String run, String input) { throw new AssertionError("必须保留完整投递配置"); }
        public void start(String session, String run, String input, List<com.pilot.core.loop.Attachment> attachments, List<Integer> images, TaskControlService.Requirements req, String skill) {
            starts++; tasks.start(session, input, req.constraints(), req.acceptance()); tasks.configureAutomation(session, req.automation());
        }
        public boolean resumable(String session) { return tasks.load(session) != null; }
        public void resume(String session, String run) { resumes++; tasks.status(session, "ACTIVE"); }
        public boolean pause(String run) { pauses++; String id=active.entrySet().stream().filter(e->e.getValue().equals(run)).findFirst().orElseThrow().getKey();tasks.status(id,"PAUSED");abandon(run);return true; }
        public boolean cancel(String run) { cancels++;abandon(run);return true; }
    }
    static class MutableClock extends Clock {
        Instant at=Instant.parse("2026-11-03T00:00:00Z");
        public Instant instant(){ return at; } public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;}
    }
}
