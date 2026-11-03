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
